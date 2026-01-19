package com.flockyou.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import android.util.Log
import androidx.core.content.ContextCompat
import com.flockyou.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Monitors cellular network for anomalies that could indicate IMSI catchers (StingRay)
 * or other cell site simulators.
 * 
 * IMPROVED DETECTION with reduced false positives:
 * - Context-aware analysis considering location, time, and history
 * - Confidence scoring based on multiple indicators
 * - Distinguishes between normal network behavior and suspicious activity
 * - Tracks "trusted" cells based on historical observation
 * 
 * Detection methods:
 * 1. Encryption downgrade (4G/5G → 2G) - especially suspicious if sudden
 * 2. Suspicious MCC/MNC (test networks)
 * 3. Cell tower changes without movement (considering confidence)
 * 4. Rapid cell switching beyond normal driving speeds
 * 5. Unknown cells in familiar locations
 * 6. Signal strength anomalies (unusual spikes)
 * 7. LAC/TAC changes without cell change
 */
class CellularMonitor(
    private val context: Context,
    private val errorCallback: ScanningService.DetectorCallback? = null
) {
    
    companion object {
        private const val TAG = "CellularMonitor"

        // Thresholds - tuned to reduce false positives
        private const val SIGNAL_SPIKE_THRESHOLD = 25 // dBm - increased from 20
        private const val SIGNAL_SPIKE_TIME_WINDOW = 5_000L // Must occur within 5 seconds
        private const val DEFAULT_MIN_ANOMALY_INTERVAL_MS = 60_000L // 1 minute between same anomaly type
        private const val DEFAULT_GLOBAL_ANOMALY_COOLDOWN_MS = 30_000L // 30 seconds between ANY anomaly
        private const val CELL_HISTORY_SIZE = 100
        private const val TRUSTED_CELL_THRESHOLD = 5 // Seen 5+ times = trusted
        private const val TRUSTED_CELL_LOCATION_RADIUS = 0.002 // ~200m in lat/lon (was 500m - too broad)

        // Rapid switching thresholds - more lenient
        private const val RAPID_SWITCH_COUNT_WALKING = 5 // 5 changes/min while stationary (was 3)
        private const val RAPID_SWITCH_COUNT_DRIVING = 12 // 12 changes/min while moving fast (was 8)
        private const val MOVEMENT_SPEED_THRESHOLD = 0.0005 // ~50m in lat/lon per minute

        // How stale location data can be before we consider movement unknown
        private const val LOCATION_STALENESS_THRESHOLD_MS = 30_000L // 30 seconds

        // Known suspicious patterns
        private val SUSPICIOUS_MCC_MNC = setOf(
            "001-01", "001-00", "001-02", // ITU test networks
            "999-99", "999-01",           // Reserved test networks
            "000-00"                       // Invalid
        )

        // Carriers known to have aggressive handoffs (reduce FPs)
        private val AGGRESSIVE_HANDOFF_CARRIERS = setOf(
            "T-Mobile", "Metro", "Sprint" // These carriers hand off more frequently
        )
    }
    
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // Configurable timing
    private var minAnomalyIntervalMs: Long = DEFAULT_MIN_ANOMALY_INTERVAL_MS
    private var globalAnomalyCooldownMs: Long = DEFAULT_GLOBAL_ANOMALY_COOLDOWN_MS

    // Monitoring state
    private var isMonitoring = false
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null
    
    // Cell history for pattern detection
    private val cellHistory = mutableListOf<CellSnapshot>()
    private var lastKnownCell: CellSnapshot? = null
    private var lastAnomalyTimes = mutableMapOf<AnomalyType, Long>()
    
    // Anomalies flow
    private val _anomalies = MutableStateFlow<List<CellularAnomaly>>(emptyList())
    val anomalies: StateFlow<List<CellularAnomaly>> = _anomalies.asStateFlow()
    
    // Current cell status flow
    private val _cellStatus = MutableStateFlow<CellStatus?>(null)
    val cellStatus: StateFlow<CellStatus?> = _cellStatus.asStateFlow()
    
    // Cellular event timeline
    private val _cellularEvents = MutableStateFlow<List<CellularEvent>>(emptyList())
    val cellularEvents: StateFlow<List<CellularEvent>> = _cellularEvents.asStateFlow()
    private val eventHistory = mutableListOf<CellularEvent>()
    private val maxEventHistory = 200
    
    private val detectedAnomalies = mutableListOf<CellularAnomaly>()
    
    // Track known/trusted cells with location context
    private val trustedCells = mutableMapOf<String, TrustedCellInfo>()
    
    // Track movement for context
    private var lastLocationUpdate: LocationSnapshot? = null
    private var estimatedMovementSpeed: Double = 0.0 // lat/lon units per minute
    private var lastAnyAnomalyTime: Long = 0L // Global cooldown for all anomalies

    // Downgrade chain tracking for IMSI catcher signature detection
    private val downgradeHistory = mutableListOf<Pair<Long, String>>() // timestamp to network generation
    private val maxDowngradeHistorySize = 20
    private var lastOperator: String? = null

    // Callback for cell info changes
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null // TelephonyCallback for API 31+
    
    data class CellSnapshot(
        val timestamp: Long,
        val cellId: Long?,  // Changed to Long for 5G NCI support (36-bit value)
        val lac: Int?, // Location Area Code (2G/3G)
        val tac: Int?, // Tracking Area Code (4G/5G)
        val mcc: String?,
        val mnc: String?,
        val signalStrength: Int, // dBm
        val networkType: Int,
        val latitude: Double?,
        val longitude: Double?
    )
    
    data class LocationSnapshot(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double
    )
    
    data class TrustedCellInfo(
        val cellId: String,
        var seenCount: Int = 0,
        var firstSeen: Long = System.currentTimeMillis(),
        var lastSeen: Long = System.currentTimeMillis(),
        val locations: MutableList<Pair<Double, Double>> = mutableListOf(), // lat/lon pairs
        var operator: String? = null,
        var networkType: String? = null
    )

    /**
     * Movement classification based on speed
     */
    enum class MovementType(val displayName: String, val maxSpeedKmh: Double) {
        STATIONARY("Stationary", 1.0),
        WALKING("Walking", 7.0),
        RUNNING("Running", 20.0),
        CYCLING("Cycling", 40.0),
        VEHICLE("Vehicle", 150.0),
        HIGH_SPEED_VEHICLE("High-Speed Vehicle", 350.0),
        IMPOSSIBLE("Impossible/Teleport", Double.MAX_VALUE)
    }

    /**
     * Encryption strength classification
     */
    enum class EncryptionStrength(val displayName: String, val description: String) {
        STRONG("Strong", "5G/LTE with modern encryption (AES-256)"),
        MODERATE("Moderate", "3G UMTS encryption (KASUMI)"),
        WEAK("Weak", "2G with A5/1 cipher (crackable)"),
        NONE("None/Broken", "2G with A5/0 or no encryption")
    }

    /**
     * Comprehensive analysis of cellular anomalies including IMSI catcher signatures
     */
    data class CellularAnomalyAnalysis(
        // IMSI Catcher Signature Detection
        val encryptionDowngradeChain: List<String>,    // ["5G", "4G", "3G", "2G"] sequence
        val downgradeWithSignalSpike: Boolean,         // Downgrade coincided with signal increase
        val downgradeWithNewTower: Boolean,            // Downgrade coincided with unknown tower
        val imsiCatcherScore: Int,                     // 0-100 composite IMSI catcher likelihood

        // Movement Analysis (Haversine)
        val distanceMeters: Double,                    // Distance from last position
        val speedKmh: Double,                          // Calculated speed
        val movementType: MovementType,                // Inferred movement type
        val impossibleSpeed: Boolean,                  // Speed exceeds physical possibility
        val timeBetweenSamplesMs: Long,               // Time between measurements

        // Cell Trust Analysis
        val cellTrustScore: Int,                       // 0-100 trust score
        val cellSeenCount: Int,                        // Times this cell has been seen
        val isInFamiliarArea: Boolean,                 // User is in known area
        val nearbyTrustedCells: Int,                   // Count of trusted cells in vicinity
        val cellAgeSeconds: Long,                      // How long cell has been in database

        // Encryption Assessment
        val currentEncryption: EncryptionStrength,
        val previousEncryption: EncryptionStrength?,
        val encryptionDowngraded: Boolean,
        val vulnerabilityNote: String?,

        // Network Context
        val networkGenerationChange: String?,          // e.g., "5G → 2G"
        val lacTacChanged: Boolean,
        val operatorChanged: Boolean,
        val isRoaming: Boolean,

        // Signal Analysis
        val signalDeltaDbm: Int,                       // Change in signal strength
        val signalSpikeDetected: Boolean,
        val currentSignalDbm: Int,
        val signalQuality: String                      // "Excellent", "Good", "Fair", "Poor"
    )
    
    /**
     * Event types for the cellular timeline
     */
    enum class CellularEventType(val displayName: String, val emoji: String) {
        CELL_HANDOFF("Cell Handoff", "🔄"),
        NETWORK_CHANGE("Network Type Change", "📶"),
        SIGNAL_CHANGE("Signal Change", "📊"),
        ENCRYPTION_DOWNGRADE("Encryption Downgrade", "🔓"),
        MONITORING_STARTED("Monitoring Started", "▶️"),
        MONITORING_STOPPED("Monitoring Stopped", "⏹️"),
        ANOMALY_DETECTED("Anomaly Detected", "⚠️"),
        NEW_CELL_DISCOVERED("New Cell Discovered", "🆕"),
        RETURNED_TO_TRUSTED("Returned to Trusted Cell", "✅")
    }
    
    /**
     * Timeline event for cellular activity
     */
    data class CellularEvent(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: CellularEventType,
        val title: String,
        val description: String,
        val cellId: String?,
        val networkType: String?,
        val signalStrength: Int?,
        val isAnomaly: Boolean = false,
        val threatLevel: ThreatLevel = ThreatLevel.INFO,
        val latitude: Double? = null,
        val longitude: Double? = null
    )
    
    data class CellularAnomaly(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: AnomalyType,
        val severity: ThreatLevel,
        val confidence: AnomalyConfidence,
        val description: String,
        val technicalDetails: String,
        val cellId: Int?,
        val previousCellId: Int?,
        val signalStrength: Int,
        val previousSignalStrength: Int?,
        val networkType: String,
        val previousNetworkType: String?,
        val mccMnc: String?,
        val latitude: Double?,
        val longitude: Double?,
        val contributingFactors: List<String> = emptyList()
    )
    
    /**
     * Confidence level for anomaly - helps filter noise
     */
    enum class AnomalyConfidence(val displayName: String, val minFactors: Int) {
        LOW("Low - Possibly Normal", 1),
        MEDIUM("Medium - Suspicious", 2),
        HIGH("High - Likely Threat", 3),
        CRITICAL("Critical - Strong Indicators", 4)
    }
    
    enum class AnomalyType(
        val displayName: String, 
        val baseScore: Int, 
        val emoji: String,
        val requiresMultipleFactors: Boolean
    ) {
        SIGNAL_SPIKE("Sudden Signal Spike", 30, "📶", true),
        CELL_TOWER_CHANGE("Cell Tower Change", 20, "🗼", true), // Low base - needs context
        ENCRYPTION_DOWNGRADE("Encryption Downgrade", 80, "🔓", false), // High base - always suspicious
        SUSPICIOUS_NETWORK("Suspicious Network ID", 95, "⚠️", false), // Very high - test networks
        UNKNOWN_CELL_FAMILIAR_AREA("Unknown Cell in Familiar Area", 60, "❓", true),
        RAPID_CELL_SWITCHING("Rapid Cell Switching", 40, "🔄", true),
        LAC_TAC_ANOMALY("Location Area Anomaly", 35, "📍", true),
        STATIONARY_CELL_CHANGE("Cell Changed While Stationary", 50, "🚫", true)
    }
    
    /**
     * Current cell tower status for UI display
     */
    data class CellStatus(
        val cellId: String,
        val lac: Int?,
        val tac: Int?,
        val mcc: String?,
        val mnc: String?,
        val operator: String?,
        val networkType: String,
        val networkGeneration: String,
        val signalStrength: Int,
        val signalBars: Int,
        val isTrustedCell: Boolean,
        val trustScore: Int, // 0-100
        val isRoaming: Boolean,
        val latitude: Double?,
        val longitude: Double?
    )
    
    /**
     * Record of a seen cell tower for history
     */
    data class SeenCellTower(
        val cellId: String,
        val lac: Int?,
        val tac: Int?,
        val mcc: String?,
        val mnc: String?,
        val operator: String?,
        val networkType: String,
        val networkGeneration: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val seenCount: Int,
        val minSignal: Int,
        val maxSignal: Int,
        val lastSignal: Int,
        val latitude: Double?,
        val longitude: Double?,
        val isTrusted: Boolean
    )
    
    // Cell tower history flow
    private val _seenCellTowers = MutableStateFlow<List<SeenCellTower>>(emptyList())
    val seenCellTowers: StateFlow<List<SeenCellTower>> = _seenCellTowers.asStateFlow()
    
    private val seenCellTowerMap = mutableMapOf<String, SeenCellTower>()
    
    fun startMonitoring() {
        if (isMonitoring) return

        if (!hasPermissions()) {
            Log.w(TAG, "Missing required permissions for cellular monitoring")
            errorCallback?.onError(
                ScanningService.DetectorHealthStatus.DETECTOR_CELLULAR,
                "Missing required cellular permissions",
                recoverable = false
            )
            return
        }

        isMonitoring = true
        Log.d(TAG, "Starting cellular monitoring")

        // Add timeline event
        addTimelineEvent(
            type = CellularEventType.MONITORING_STARTED,
            title = "Cellular Monitoring Started",
            description = "Now monitoring for IMSI catcher indicators"
        )

        try {
            registerCellListener()
            errorCallback?.onDetectorStarted(ScanningService.DetectorHealthStatus.DETECTOR_CELLULAR)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register cell listener", e)
            errorCallback?.onError(
                ScanningService.DetectorHealthStatus.DETECTOR_CELLULAR,
                "Failed to register cell listener: ${e.message}",
                recoverable = true
            )
        }

        // Take initial snapshot
        try {
            takeCellSnapshot()?.let { snapshot ->
                cellHistory.add(snapshot)
                lastKnownCell = snapshot
                updateCellStatus(snapshot)

                // Mark initial cell as potentially trusted
                snapshot.cellId?.let { cellId ->
                    getOrCreateTrustedCellInfo(cellId.toString()).apply {
                        seenCount++
                        lastSeen = System.currentTimeMillis()
                        snapshot.latitude?.let { lat ->
                            snapshot.longitude?.let { lon ->
                                locations.add(lat to lon)
                            }
                        }
                    }
                }
                errorCallback?.onScanSuccess(ScanningService.DetectorHealthStatus.DETECTOR_CELLULAR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking initial cell snapshot", e)
            errorCallback?.onError(
                ScanningService.DetectorHealthStatus.DETECTOR_CELLULAR,
                "Error taking cell snapshot: ${e.message}",
                recoverable = true
            )
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
        try {
            unregisterCellListener()
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering cell listener", e)
        }

        addTimelineEvent(
            type = CellularEventType.MONITORING_STOPPED,
            title = "Cellular Monitoring Stopped",
            description = "IMSI catcher detection paused"
        )

        errorCallback?.onDetectorStopped(ScanningService.DetectorHealthStatus.DETECTOR_CELLULAR)
        Log.d(TAG, "Stopped cellular monitoring")
    }

    /**
     * Update scan timing configuration.
     * @param intervalSeconds Cooldown time between anomaly reports (1-30 seconds)
     */
    fun updateScanTiming(intervalSeconds: Int) {
        minAnomalyIntervalMs = (intervalSeconds.coerceIn(1, 30) * 1000L)
        globalAnomalyCooldownMs = (intervalSeconds.coerceIn(1, 30) * 500L) // Half the min interval
        Log.d(TAG, "Updated anomaly cooldown: min=${minAnomalyIntervalMs}ms, global=${globalAnomalyCooldownMs}ms")
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()

        // Calculate movement speed
        lastLocationUpdate?.let { last ->
            val timeDiff = (now - last.timestamp) / 60_000.0 // minutes
            if (timeDiff > 0.1) { // At least 6 seconds
                val latDiff = kotlin.math.abs(latitude - last.latitude)
                val lonDiff = kotlin.math.abs(longitude - last.longitude)
                val distance = kotlin.math.sqrt(latDiff * latDiff + lonDiff * lonDiff)
                estimatedMovementSpeed = distance / timeDiff
            }
        }

        lastLocationUpdate = LocationSnapshot(now, latitude, longitude)
        currentLatitude = latitude
        currentLongitude = longitude
    }

    /**
     * Check if we have recent location data to reliably determine movement state.
     * If location data is stale, we cannot confidently say the user is stationary.
     */
    private fun hasRecentLocationData(): Boolean {
        val lastUpdate = lastLocationUpdate ?: return false
        return (System.currentTimeMillis() - lastUpdate.timestamp) < LOCATION_STALENESS_THRESHOLD_MS
    }
    
    fun clearAnomalies() {
        detectedAnomalies.clear()
        _anomalies.value = emptyList()
    }
    
    fun clearHistory() {
        cellHistory.clear()
        seenCellTowerMap.clear()
        _seenCellTowers.value = emptyList()
        eventHistory.clear()
        _cellularEvents.value = emptyList()
        downgradeHistory.clear()
        lastOperator = null
        // Don't clear trusted cells - keep learning
    }
    
    fun destroy() {
        stopMonitoring()
        clearSensitiveData()
    }

    /**
     * Clear all sensitive cellular data from memory.
     * Call this when:
     * - User locks the app
     * - App goes to background (if configured)
     * - Service is being destroyed
     *
     * This helps protect sensitive location and cellular network data from
     * memory-based attacks.
     */
    fun clearSensitiveData() {
        // Clear anomaly data
        detectedAnomalies.clear()
        _anomalies.value = emptyList()

        // Clear cell history
        cellHistory.clear()
        lastKnownCell = null

        // Clear event history
        eventHistory.clear()
        _cellularEvents.value = emptyList()

        // Clear trusted cells (contains location correlations)
        trustedCells.clear()

        // Clear seen cell towers
        seenCellTowerMap.clear()
        _seenCellTowers.value = emptyList()

        // Clear location tracking
        lastLocationUpdate = null
        currentLatitude = null
        currentLongitude = null
        estimatedMovementSpeed = 0.0

        // Clear downgrade tracking
        downgradeHistory.clear()
        lastOperator = null

        // Clear status
        _cellStatus.value = null

        // Clear anomaly timestamps
        lastAnomalyTimes.clear()
        lastAnyAnomalyTime = 0L

        Log.d(TAG, "Cleared all sensitive cellular data from memory")
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun registerCellListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ uses TelephonyCallback
            try {
                val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        processCellInfoChange(cellInfo)
                    }
                }
                telephonyManager.registerTelephonyCallback(
                    context.mainExecutor,
                    callback
                )
                telephonyCallback = callback
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception registering callback", e)
            }
        } else {
            // Legacy PhoneStateListener
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in API 31")
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    cellInfo?.let { processCellInfoChange(it) }
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
            phoneStateListener = listener
        }
    }
    
    private fun unregisterCellListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering cell listener", e)
        }
    }
    
    private fun processCellInfoChange(cellInfoList: List<CellInfo>) {
        if (!isMonitoring) return
        
        val snapshot = extractCellSnapshot(cellInfoList) ?: return
        val previous = lastKnownCell
        
        // Always update history
        cellHistory.add(snapshot)
        if (cellHistory.size > CELL_HISTORY_SIZE) {
            cellHistory.removeAt(0)
        }
        
        // Update seen cell towers
        updateSeenCellTower(snapshot)
        
        // Update trusted cell info
        snapshot.cellId?.let { cellId ->
            val trusted = getOrCreateTrustedCellInfo(cellId.toString())
            trusted.seenCount++
            trusted.lastSeen = System.currentTimeMillis()
            trusted.operator = telephonyManager.networkOperatorName
            trusted.networkType = getNetworkTypeName(snapshot.networkType)
            snapshot.latitude?.let { lat ->
                snapshot.longitude?.let { lon ->
                    if (trusted.locations.size < 10) {
                        trusted.locations.add(lat to lon)
                    }
                }
            }
        }
        
        // Update current status
        updateCellStatus(snapshot)
        
        // Analyze for anomalies with improved heuristics
        if (previous != null) {
            analyzeForAnomaliesImproved(previous, snapshot)
        }
        
        lastKnownCell = snapshot
    }
    
    private fun analyzeForAnomaliesImproved(previous: CellSnapshot, current: CellSnapshot) {
        // Global cooldown - don't spam alerts
        val now = System.currentTimeMillis()
        if (now - lastAnyAnomalyTime < globalAnomalyCooldownMs) {
            return
        }

        // Build comprehensive analysis using enrichment functions
        val analysis = buildCellularAnalysis(current, previous)

        val contributingFactors = mutableListOf<String>()
        var totalScore = 0

        val mccMnc = "${current.mcc}-${current.mnc}"
        val timeSinceLastSnapshot = current.timestamp - previous.timestamp

        // Use enriched movement analysis instead of simple threshold
        val isStationary = analysis.movementType == MovementType.STATIONARY ||
            analysis.movementType == MovementType.WALKING
        val currentCellTrusted = analysis.cellTrustScore >= 60

        // 1. CRITICAL: Suspicious MCC/MNC - always flag
        if (mccMnc in SUSPICIOUS_MCC_MNC) {
            val enrichedFactors = buildCellularContributingFactors(analysis) + listOf(
                "Test network MCC/MNC detected",
                "Network: $mccMnc"
            )
            reportAnomalyImproved(
                type = AnomalyType.SUSPICIOUS_NETWORK,
                description = "Connected to suspicious test network",
                technicalDetails = buildCellularTechnicalDetails(analysis, current, previous),
                contributingFactors = enrichedFactors,
                confidence = AnomalyConfidence.CRITICAL,
                current = current,
                previous = previous,
                analysis = analysis
            )
            return // Don't analyze further - this is definitive
        }

        // 2. HIGH: Encryption downgrade to 2G - now with IMSI catcher signature detection
        if (analysis.encryptionDowngraded && analysis.currentEncryption.ordinal >= EncryptionStrength.WEAK.ordinal) {
            contributingFactors.add("Encryption downgrade: ${analysis.previousEncryption?.displayName} → ${analysis.currentEncryption.displayName}")
            totalScore += AnomalyType.ENCRYPTION_DOWNGRADE.baseScore

            // Use IMSI catcher score to boost confidence
            if (analysis.imsiCatcherScore >= 70) {
                totalScore += 30
                contributingFactors.add("HIGH IMSI catcher signature (${analysis.imsiCatcherScore}%)")
            } else if (analysis.imsiCatcherScore >= 50) {
                totalScore += 15
                contributingFactors.add("Moderate IMSI catcher signature (${analysis.imsiCatcherScore}%)")
            }

            // Additional factors from enrichment
            if (analysis.downgradeWithSignalSpike) {
                contributingFactors.add("Downgrade with signal spike (+${analysis.signalDeltaDbm} dBm)")
                totalScore += 15
            }
            if (analysis.downgradeWithNewTower) {
                contributingFactors.add("Downgrade to unknown cell tower")
                totalScore += 10
            }
            if (isStationary) {
                contributingFactors.add("Device is ${analysis.movementType.displayName.lowercase()}")
                totalScore += 15
            }
            if (analysis.impossibleSpeed) {
                contributingFactors.add("Impossible movement detected (${String.format("%.0f", analysis.speedKmh)} km/h)")
                totalScore += 20
            }

            // 2G downgrade is always at least MEDIUM, but boost based on IMSI score
            val confidence = when {
                analysis.imsiCatcherScore >= 70 || totalScore >= 100 -> AnomalyConfidence.CRITICAL
                analysis.imsiCatcherScore >= 50 || totalScore >= 85 -> AnomalyConfidence.HIGH
                else -> AnomalyConfidence.MEDIUM
            }

            reportAnomalyImproved(
                type = AnomalyType.ENCRYPTION_DOWNGRADE,
                description = "Network forced to use weaker encryption - IMSI catcher likelihood: ${analysis.imsiCatcherScore}%",
                technicalDetails = buildCellularTechnicalDetails(analysis, current, previous),
                contributingFactors = contributingFactors,
                confidence = confidence,
                current = current,
                previous = previous,
                analysis = analysis
            )
            return
        }

        // 3. Cell tower changed - check context with enriched analysis
        if (current.cellId != previous.cellId && current.cellId != null) {
            // Record timeline event first
            addTimelineEvent(
                type = if (currentCellTrusted) CellularEventType.RETURNED_TO_TRUSTED else CellularEventType.CELL_HANDOFF,
                title = if (currentCellTrusted) "Returned to Known Cell" else "Cell Handoff",
                description = "Cell changed: ${previous.cellId ?: "?"} → ${current.cellId} (${analysis.movementType.displayName}, ${String.format("%.0f", analysis.distanceMeters)}m)",
                cellId = current.cellId.toString(),
                networkType = getNetworkTypeName(current.networkType),
                signalStrength = current.signalStrength
            )

            // Use enriched movement analysis for better detection
            if (isStationary && !currentCellTrusted) {
                // Stationary + new cell = suspicious
                contributingFactors.add("Cell changed while ${analysis.movementType.displayName.lowercase()}")
                contributingFactors.add("New cell tower (trust: ${analysis.cellTrustScore}%)")
                totalScore += AnomalyType.STATIONARY_CELL_CHANGE.baseScore + 20

                // Check for impossible movement (potential IMSI catcher mobility)
                if (analysis.impossibleSpeed) {
                    contributingFactors.add("IMPOSSIBLE movement: ${String.format("%.0f", analysis.speedKmh)} km/h")
                    totalScore += 25
                }
            } else if (isStationary && currentCellTrusted) {
                // Stationary but returned to known cell - probably normal
                addTimelineEvent(
                    type = CellularEventType.CELL_HANDOFF,
                    title = "Normal Handoff",
                    description = "Switched to trusted cell ${current.cellId} (trust: ${analysis.cellTrustScore}%)",
                    cellId = current.cellId.toString(),
                    networkType = getNetworkTypeName(current.networkType),
                    signalStrength = current.signalStrength,
                    threatLevel = ThreatLevel.INFO
                )
                // Don't report as anomaly
            } else if (!isStationary) {
                // Moving - cell changes are expected, but check for impossible speed
                if (analysis.impossibleSpeed) {
                    contributingFactors.add("IMPOSSIBLE movement speed: ${String.format("%.0f", analysis.speedKmh)} km/h")
                    totalScore += 30
                }
            }
        }

        // 4. Check for rapid cell switching
        val recentChanges = countRecentCellChanges(60_000)
        val switchThreshold = if (isStationary) RAPID_SWITCH_COUNT_WALKING else RAPID_SWITCH_COUNT_DRIVING

        if (recentChanges > switchThreshold) {
            contributingFactors.add("$recentChanges cell changes in last minute")
            contributingFactors.add("Movement: ${analysis.movementType.displayName}")
            totalScore += AnomalyType.RAPID_CELL_SWITCHING.baseScore

            if (isStationary) {
                totalScore += 25 // Much more suspicious if not moving
            }
        }

        // 5. Check for signal spike - now with enriched context
        if (analysis.signalSpikeDetected) {
            contributingFactors.add("Signal spiked +${analysis.signalDeltaDbm}dBm in ${timeSinceLastSnapshot/1000}s")
            totalScore += AnomalyType.SIGNAL_SPIKE.baseScore

            // More suspicious if combined with cell change
            if (current.cellId != previous.cellId) {
                contributingFactors.add("Combined with cell tower change")
                totalScore += 15
            }
        }

        // 6. Unknown cell in familiar area - use enriched analysis
        if (!currentCellTrusted && analysis.isInFamiliarArea) {
            contributingFactors.add("Unknown cell (trust: ${analysis.cellTrustScore}%) in familiar area (${analysis.nearbyTrustedCells} trusted cells nearby)")
            totalScore += AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA.baseScore
        }

        // 7. LAC/TAC anomaly
        if (analysis.lacTacChanged) {
            contributingFactors.add("Location area code changed without cell change")
            totalScore += AnomalyType.LAC_TAC_ANOMALY.baseScore
        }

        // 8. NEW: Check for operator change
        if (analysis.operatorChanged) {
            contributingFactors.add("Carrier/operator changed unexpectedly")
            totalScore += 20
        }

        // Determine if we should report based on total score and factors
        if (contributingFactors.isNotEmpty() && totalScore >= 50) {
            // Use both factor count and IMSI score for confidence
            val confidence = when {
                analysis.imsiCatcherScore >= 70 -> AnomalyConfidence.CRITICAL
                contributingFactors.size >= 4 || analysis.imsiCatcherScore >= 50 -> AnomalyConfidence.HIGH
                contributingFactors.size >= 3 -> AnomalyConfidence.HIGH
                contributingFactors.size >= 2 -> AnomalyConfidence.MEDIUM
                else -> AnomalyConfidence.LOW
            }

            // Only report if confidence is at least MEDIUM, unless score is very high
            if (confidence.ordinal >= AnomalyConfidence.MEDIUM.ordinal || totalScore >= 70) {
                val primaryType = when {
                    analysis.impossibleSpeed -> AnomalyType.CELL_TOWER_CHANGE // Likely spoofing/IMSI catcher
                    contributingFactors.any { it.contains("stationary", ignoreCase = true) || it.contains("walking", ignoreCase = true) } &&
                    contributingFactors.any { it.contains("cell", ignoreCase = true) } ->
                        AnomalyType.STATIONARY_CELL_CHANGE
                    contributingFactors.any { it.contains("rapid", ignoreCase = true) || it.contains("changes in last", ignoreCase = true) } ->
                        AnomalyType.RAPID_CELL_SWITCHING
                    contributingFactors.any { it.contains("signal", ignoreCase = true) } ->
                        AnomalyType.SIGNAL_SPIKE
                    contributingFactors.any { it.contains("unknown", ignoreCase = true) } ->
                        AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA
                    else -> AnomalyType.CELL_TOWER_CHANGE
                }

                reportAnomalyImproved(
                    type = primaryType,
                    description = "Multiple suspicious indicators detected - IMSI likelihood: ${analysis.imsiCatcherScore}%",
                    technicalDetails = buildCellularTechnicalDetails(analysis, current, previous),
                    contributingFactors = contributingFactors,
                    confidence = confidence,
                    current = current,
                    previous = previous,
                    analysis = analysis
                )
            } else {
                // Low confidence - just log to timeline
                addTimelineEvent(
                    type = CellularEventType.CELL_HANDOFF,
                    title = "Cell Activity",
                    description = "${contributingFactors.firstOrNull() ?: "Cell network change"} (IMSI: ${analysis.imsiCatcherScore}%)",
                    cellId = current.cellId?.toString(),
                    networkType = getNetworkTypeName(current.networkType),
                    signalStrength = current.signalStrength,
                    threatLevel = ThreatLevel.INFO
                )
            }
        }
    }
    
    private fun reportAnomalyImproved(
        type: AnomalyType,
        description: String,
        technicalDetails: String,
        contributingFactors: List<String>,
        confidence: AnomalyConfidence,
        current: CellSnapshot,
        previous: CellSnapshot,
        analysis: CellularAnomalyAnalysis? = null
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastAnomalyTimes[type] ?: 0

        // Rate limit same anomaly type
        if (now - lastTime < minAnomalyIntervalMs) {
            return
        }
        lastAnomalyTimes[type] = now
        lastAnyAnomalyTime = now // Update global cooldown

        // Calculate severity based on confidence, base score, and IMSI catcher score
        val severity = when {
            analysis?.imsiCatcherScore ?: 0 >= 70 -> ThreatLevel.CRITICAL
            confidence == AnomalyConfidence.CRITICAL -> ThreatLevel.CRITICAL
            analysis?.imsiCatcherScore ?: 0 >= 50 -> ThreatLevel.HIGH
            confidence == AnomalyConfidence.HIGH -> ThreatLevel.HIGH
            confidence == AnomalyConfidence.MEDIUM -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }

        // Build enriched description if analysis is available
        val enrichedDescription = if (analysis != null) {
            buildString {
                append(description)
                if (analysis.impossibleSpeed) {
                    append(" | IMPOSSIBLE MOVEMENT: ${String.format("%.0f", analysis.speedKmh)} km/h")
                }
                if (analysis.downgradeWithSignalSpike) {
                    append(" | Downgrade + Signal Spike")
                }
            }
        } else {
            description
        }

        val anomaly = CellularAnomaly(
            type = type,
            severity = severity,
            confidence = confidence,
            description = enrichedDescription,
            technicalDetails = technicalDetails,
            cellId = current.cellId?.toInt(),
            previousCellId = previous.cellId?.toInt(),
            signalStrength = current.signalStrength,
            previousSignalStrength = previous.signalStrength,
            networkType = getNetworkTypeName(current.networkType),
            previousNetworkType = getNetworkTypeName(previous.networkType),
            mccMnc = "${current.mcc}-${current.mnc}",
            latitude = currentLatitude,
            longitude = currentLongitude,
            contributingFactors = contributingFactors
        )

        detectedAnomalies.add(anomaly)
        _anomalies.value = detectedAnomalies.toList()

        // Add to timeline with enriched info
        val timelineTitle = if (analysis != null && analysis.imsiCatcherScore >= 50) {
            "${type.emoji} ${type.displayName} (IMSI: ${analysis.imsiCatcherScore}%)"
        } else {
            "${type.emoji} ${type.displayName}"
        }

        addTimelineEvent(
            type = CellularEventType.ANOMALY_DETECTED,
            title = timelineTitle,
            description = enrichedDescription,
            cellId = current.cellId?.toString(),
            networkType = getNetworkTypeName(current.networkType),
            signalStrength = current.signalStrength,
            isAnomaly = true,
            threatLevel = severity
        )

        // Enhanced logging with IMSI catcher score
        val imsiInfo = analysis?.let { " | IMSI Score: ${it.imsiCatcherScore}%" } ?: ""
        Log.w(TAG, "ANOMALY [${confidence.displayName}]: ${type.displayName} - $description$imsiInfo")
    }
    
    private fun addTimelineEvent(
        type: CellularEventType,
        title: String,
        description: String,
        cellId: String? = null,
        networkType: String? = null,
        signalStrength: Int? = null,
        isAnomaly: Boolean = false,
        threatLevel: ThreatLevel = ThreatLevel.INFO
    ) {
        val event = CellularEvent(
            type = type,
            title = title,
            description = description,
            cellId = cellId,
            networkType = networkType,
            signalStrength = signalStrength,
            isAnomaly = isAnomaly,
            threatLevel = threatLevel,
            latitude = currentLatitude,
            longitude = currentLongitude
        )
        
        eventHistory.add(0, event) // Add to beginning
        if (eventHistory.size > maxEventHistory) {
            eventHistory.removeAt(eventHistory.size - 1)
        }
        _cellularEvents.value = eventHistory.toList()
    }
    
    private fun getOrCreateTrustedCellInfo(cellId: String): TrustedCellInfo {
        return trustedCells.getOrPut(cellId) {
            TrustedCellInfo(cellId = cellId)
        }
    }
    
    private fun isCellTrusted(cellId: String?): Boolean {
        if (cellId == null) return false
        val info = trustedCells[cellId] ?: return false
        return info.seenCount >= TRUSTED_CELL_THRESHOLD
    }
    
    private fun isInFamiliarArea(): Boolean {
        val lat = currentLatitude ?: return false
        val lon = currentLongitude ?: return false

        // BUG FIX: Require at least 2 TRUSTED cells (seen 5+ times) near this location
        // to consider it a "familiar area". Previously any single cell sighting made
        // everywhere "familiar", causing too many false positives.
        val trustedCellsNearby = trustedCells.values.count { cell ->
            // Only count cells that are actually trusted (seen enough times)
            cell.seenCount >= TRUSTED_CELL_THRESHOLD &&
            cell.locations.any { (cellLat, cellLon) ->
                kotlin.math.abs(lat - cellLat) < TRUSTED_CELL_LOCATION_RADIUS &&
                kotlin.math.abs(lon - cellLon) < TRUSTED_CELL_LOCATION_RADIUS
            }
        }
        return trustedCellsNearby >= 2
    }
    
    private fun isEncryptionDowngrade(previousType: Int, currentType: Int): Boolean {
        val previousGen = getNetworkGeneration(previousType)
        val currentGen = getNetworkGeneration(currentType)
        // Downgrade to 2G is suspicious (2G has weak/no encryption)
        return previousGen >= 3 && currentGen == 2
    }
    
    @Suppress("DEPRECATION")
    private fun getNetworkGeneration(networkType: Int): Int {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> 2
            
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> 3
            
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> 4
            
            TelephonyManager.NETWORK_TYPE_NR -> 5
            
            else -> 0
        }
    }
    
    @Suppress("DEPRECATION")
    private fun getNetworkTypeName(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA (2G)"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT (2G)"
            TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN (2G)"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM (2G)"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO 0 (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO A (3G)"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA (3G)"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA (3G)"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO B (3G)"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD (3G)"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+ (3G)"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA (3G)"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (4G)"
            TelephonyManager.NETWORK_TYPE_NR -> "NR (5G)"
            else -> "Unknown"
        }
    }
    
    private fun countRecentCellChanges(withinMs: Long): Int {
        val cutoff = System.currentTimeMillis() - withinMs
        val recentSnapshots = cellHistory.filter { it.timestamp > cutoff }
        
        if (recentSnapshots.size < 2) return 0
        
        var changes = 0
        for (i in 1 until recentSnapshots.size) {
            if (recentSnapshots[i].cellId != recentSnapshots[i - 1].cellId) {
                changes++
            }
        }
        return changes
    }
    
    private fun hasLacTacAnomaly(previous: CellSnapshot, current: CellSnapshot): Boolean {
        val lacChanged = previous.lac != null && current.lac != null && 
            previous.lac != current.lac && previous.lac != 0 && current.lac != 0
        val tacChanged = previous.tac != null && current.tac != null && 
            previous.tac != current.tac && previous.tac != 0 && current.tac != 0
        val cellSame = previous.cellId == current.cellId && current.cellId != null
        
        return (lacChanged || tacChanged) && cellSame
    }
    
    private fun takeCellSnapshot(): CellSnapshot? {
        if (!hasPermissions()) return null
        
        try {
            @Suppress("MissingPermission")
            val cellInfoList = telephonyManager.allCellInfo ?: return null
            return extractCellSnapshot(cellInfoList)
        } catch (e: Exception) {
            Log.e(TAG, "Error taking cell snapshot", e)
            return null
        }
    }
    
    private fun extractCellSnapshot(cellInfoList: List<CellInfo>): CellSnapshot? {
        val registeredCell = cellInfoList.firstOrNull { it.isRegistered } ?: return null

        var cellId: Long? = null  // Changed to Long for 5G NCI support
        var lac: Int? = null
        var tac: Int? = null
        var mcc: String? = null
        var mnc: String? = null
        var signalDbm = -100
        var networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN

        when (registeredCell) {
            is CellInfoLte -> {
                cellId = registeredCell.cellIdentity.ci.toLong()
                tac = registeredCell.cellIdentity.tac
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mcc = registeredCell.cellIdentity.mccString
                    mnc = registeredCell.cellIdentity.mncString
                }
                signalDbm = registeredCell.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_LTE
            }
            is CellInfoGsm -> {
                cellId = registeredCell.cellIdentity.cid.toLong()
                lac = registeredCell.cellIdentity.lac
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mcc = registeredCell.cellIdentity.mccString
                    mnc = registeredCell.cellIdentity.mncString
                }
                signalDbm = registeredCell.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_GSM
            }
            is CellInfoWcdma -> {
                cellId = registeredCell.cellIdentity.cid.toLong()
                lac = registeredCell.cellIdentity.lac
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mcc = registeredCell.cellIdentity.mccString
                    mnc = registeredCell.cellIdentity.mncString
                }
                signalDbm = registeredCell.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_UMTS
            }
            is CellInfoCdma -> {
                cellId = registeredCell.cellIdentity.basestationId.toLong()
                signalDbm = registeredCell.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_CDMA
            }
        }

        // Handle NR (5G) for API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (registeredCell is CellInfoNr) {
                val nrIdentity = registeredCell.cellIdentity as? CellIdentityNr
                cellId = nrIdentity?.nci  // NCI is already Long, no truncation
                tac = nrIdentity?.tac
                mcc = nrIdentity?.mccString
                mnc = nrIdentity?.mncString
                signalDbm = (registeredCell.cellSignalStrength as? CellSignalStrengthNr)?.dbm ?: -100
                networkType = TelephonyManager.NETWORK_TYPE_NR
            }
        }
        
        return CellSnapshot(
            timestamp = System.currentTimeMillis(),
            cellId = cellId,
            lac = lac,
            tac = tac,
            mcc = mcc,
            mnc = mnc,
            signalStrength = signalDbm,
            networkType = networkType,
            latitude = currentLatitude,
            longitude = currentLongitude
        )
    }
    
    private fun updateCellStatus(snapshot: CellSnapshot) {
        val cellIdStr = snapshot.cellId?.toString() ?: "Unknown"
        val networkGen = getNetworkGeneration(snapshot.networkType)
        val genName = when (networkGen) {
            5 -> "5G"
            4 -> "4G"
            3 -> "3G"
            2 -> "2G"
            else -> "Unknown"
        }
        
        val signalBars = when {
            snapshot.signalStrength >= -70 -> 4
            snapshot.signalStrength >= -85 -> 3
            snapshot.signalStrength >= -100 -> 2
            snapshot.signalStrength >= -110 -> 1
            else -> 0
        }
        
        val trusted = trustedCells[cellIdStr]
        val trustScore = when {
            trusted == null -> 0
            trusted.seenCount >= 20 -> 100
            trusted.seenCount >= 10 -> 80
            trusted.seenCount >= 5 -> 60
            trusted.seenCount >= 2 -> 30
            else -> 10
        }
        
        _cellStatus.value = CellStatus(
            cellId = cellIdStr,
            lac = snapshot.lac,
            tac = snapshot.tac,
            mcc = snapshot.mcc,
            mnc = snapshot.mnc,
            operator = telephonyManager.networkOperatorName,
            networkType = getNetworkTypeName(snapshot.networkType),
            networkGeneration = genName,
            signalStrength = snapshot.signalStrength,
            signalBars = signalBars,
            isTrustedCell = trustScore >= 60,
            trustScore = trustScore,
            isRoaming = telephonyManager.isNetworkRoaming,
            latitude = snapshot.latitude,
            longitude = snapshot.longitude
        )
    }
    
    private fun updateSeenCellTower(snapshot: CellSnapshot) {
        val cellId = snapshot.cellId?.toString() ?: return
        val networkGen = getNetworkGeneration(snapshot.networkType)
        val genName = when (networkGen) {
            5 -> "5G"
            4 -> "4G"
            3 -> "3G"
            2 -> "2G"
            else -> "Unknown"
        }
        
        val existing = seenCellTowerMap[cellId]
        val trusted = trustedCells[cellId]
        
        if (existing != null) {
            seenCellTowerMap[cellId] = existing.copy(
                lastSeen = System.currentTimeMillis(),
                seenCount = existing.seenCount + 1,
                minSignal = minOf(existing.minSignal, snapshot.signalStrength),
                maxSignal = maxOf(existing.maxSignal, snapshot.signalStrength),
                lastSignal = snapshot.signalStrength,
                latitude = snapshot.latitude ?: existing.latitude,
                longitude = snapshot.longitude ?: existing.longitude,
                isTrusted = trusted?.seenCount ?: 0 >= TRUSTED_CELL_THRESHOLD
            )
        } else {
            // New cell discovered - add to timeline
            addTimelineEvent(
                type = CellularEventType.NEW_CELL_DISCOVERED,
                title = "New Cell Tower",
                description = "Cell $cellId (${getNetworkTypeName(snapshot.networkType)}) - ${telephonyManager.networkOperatorName ?: "Unknown operator"}",
                cellId = cellId,
                networkType = getNetworkTypeName(snapshot.networkType),
                signalStrength = snapshot.signalStrength
            )
            
            seenCellTowerMap[cellId] = SeenCellTower(
                cellId = cellId,
                lac = snapshot.lac,
                tac = snapshot.tac,
                mcc = snapshot.mcc,
                mnc = snapshot.mnc,
                operator = telephonyManager.networkOperatorName,
                networkType = getNetworkTypeName(snapshot.networkType),
                networkGeneration = genName,
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                seenCount = 1,
                minSignal = snapshot.signalStrength,
                maxSignal = snapshot.signalStrength,
                lastSignal = snapshot.signalStrength,
                latitude = snapshot.latitude,
                longitude = snapshot.longitude,
                isTrusted = false
            )
        }
        
        _seenCellTowers.value = seenCellTowerMap.values
            .sortedByDescending { it.lastSeen }
            .toList()
    }
    
    // ==================== ENRICHMENT ANALYSIS FUNCTIONS ====================

    /**
     * Calculate the Haversine distance between two lat/lon points in meters.
     * Uses the spherical law of cosines for accuracy on Earth's surface.
     */
    private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0 // Earth's radius in meters

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

        return earthRadiusMeters * c
    }

    /**
     * Infer movement type from speed in km/h
     */
    private fun inferMovementType(speedKmh: Double): MovementType {
        return when {
            speedKmh < MovementType.STATIONARY.maxSpeedKmh -> MovementType.STATIONARY
            speedKmh < MovementType.WALKING.maxSpeedKmh -> MovementType.WALKING
            speedKmh < MovementType.RUNNING.maxSpeedKmh -> MovementType.RUNNING
            speedKmh < MovementType.CYCLING.maxSpeedKmh -> MovementType.CYCLING
            speedKmh < MovementType.VEHICLE.maxSpeedKmh -> MovementType.VEHICLE
            speedKmh < MovementType.HIGH_SPEED_VEHICLE.maxSpeedKmh -> MovementType.HIGH_SPEED_VEHICLE
            else -> MovementType.IMPOSSIBLE
        }
    }

    /**
     * Get encryption strength for a network generation
     */
    private fun getEncryptionStrength(networkType: Int): EncryptionStrength {
        val gen = getNetworkGeneration(networkType)
        return when (gen) {
            5, 4 -> EncryptionStrength.STRONG
            3 -> EncryptionStrength.MODERATE
            2 -> EncryptionStrength.WEAK // Could be NONE depending on A5/0 vs A5/1
            else -> EncryptionStrength.NONE
        }
    }

    /**
     * Get a human-readable network generation name
     */
    private fun getNetworkGenerationName(networkType: Int): String {
        return when (getNetworkGeneration(networkType)) {
            5 -> "5G"
            4 -> "4G"
            3 -> "3G"
            2 -> "2G"
            else -> "Unknown"
        }
    }

    /**
     * Track the encryption downgrade chain over time
     */
    private fun trackDowngradeChain(networkType: Int) {
        val genName = getNetworkGenerationName(networkType)
        val now = System.currentTimeMillis()

        // Remove old entries (older than 5 minutes)
        val cutoff = now - 300_000
        downgradeHistory.removeAll { it.first < cutoff }

        // Add current
        if (downgradeHistory.isEmpty() || downgradeHistory.last().second != genName) {
            downgradeHistory.add(now to genName)
            if (downgradeHistory.size > maxDowngradeHistorySize) {
                downgradeHistory.removeAt(0)
            }
        }
    }

    /**
     * Get the recent downgrade chain (e.g., ["5G", "4G", "3G", "2G"])
     */
    private fun getRecentDowngradeChain(): List<String> {
        return downgradeHistory.map { it.second }
    }

    /**
     * Check if there's an IMSI catcher signature in the downgrade pattern
     * Returns score 0-100
     */
    private fun calculateImsiCatcherScore(
        analysis: CellularAnomalyAnalysis
    ): Int {
        var score = 0

        // Downgrade chain analysis - progressive downgrade is highly suspicious
        val chain = analysis.encryptionDowngradeChain
        if (chain.size >= 2) {
            val hasProgressiveDowngrade = chain.zipWithNext().all { (prev, curr) ->
                val prevGen = when (prev) { "5G" -> 5; "4G" -> 4; "3G" -> 3; "2G" -> 2; else -> 0 }
                val currGen = when (curr) { "5G" -> 5; "4G" -> 4; "3G" -> 3; "2G" -> 2; else -> 0 }
                currGen < prevGen || currGen == prevGen
            }
            if (hasProgressiveDowngrade && chain.last() == "2G") {
                score += 30 // Clear downgrade pattern ending in 2G
            }
        }

        // Ended on 2G specifically
        if (analysis.currentEncryption == EncryptionStrength.WEAK ||
            analysis.currentEncryption == EncryptionStrength.NONE) {
            score += 25
        }

        // Downgrade with signal spike - classic IMSI catcher behavior
        if (analysis.downgradeWithSignalSpike) {
            score += 20
        }

        // Downgrade with new/unknown tower
        if (analysis.downgradeWithNewTower) {
            score += 15
        }

        // User was stationary
        if (analysis.movementType == MovementType.STATIONARY) {
            score += 10
        }

        // Low cell trust
        if (analysis.cellTrustScore < 30) {
            score += 10
        }

        // Impossible movement (potential spoofing)
        if (analysis.impossibleSpeed) {
            score += 15
        }

        return score.coerceIn(0, 100)
    }

    /**
     * Count trusted cells near a location
     */
    private fun countNearbyTrustedCells(lat: Double?, lon: Double?): Int {
        if (lat == null || lon == null) return 0

        return trustedCells.values.count { cell ->
            cell.seenCount >= TRUSTED_CELL_THRESHOLD &&
            cell.locations.any { (cellLat, cellLon) ->
                val distance = haversineDistanceMeters(lat, lon, cellLat, cellLon)
                distance < 500 // Within 500 meters
            }
        }
    }

    /**
     * Get signal quality description
     */
    private fun getSignalQuality(signalDbm: Int): String {
        return when {
            signalDbm >= -70 -> "Excellent"
            signalDbm >= -85 -> "Good"
            signalDbm >= -100 -> "Fair"
            signalDbm >= -110 -> "Poor"
            else -> "Very Poor"
        }
    }

    /**
     * Build comprehensive cellular analysis from current and previous snapshots
     */
    private fun buildCellularAnalysis(
        current: CellSnapshot,
        previous: CellSnapshot?
    ): CellularAnomalyAnalysis {
        val now = System.currentTimeMillis()

        // Movement analysis using Haversine
        var distanceMeters = 0.0
        var speedKmh = 0.0
        var timeBetweenMs = 0L

        if (previous != null && current.latitude != null && current.longitude != null &&
            previous.latitude != null && previous.longitude != null) {
            distanceMeters = haversineDistanceMeters(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
            timeBetweenMs = current.timestamp - previous.timestamp
            if (timeBetweenMs > 0) {
                val hours = timeBetweenMs / 3_600_000.0
                speedKmh = (distanceMeters / 1000.0) / hours
            }
        }

        val movementType = inferMovementType(speedKmh)
        val impossibleSpeed = movementType == MovementType.IMPOSSIBLE

        // Encryption analysis
        val currentEncryption = getEncryptionStrength(current.networkType)
        val previousEncryption = previous?.let { getEncryptionStrength(it.networkType) }
        val encryptionDowngraded = previousEncryption != null &&
            currentEncryption.ordinal > previousEncryption.ordinal

        // Track and get downgrade chain
        trackDowngradeChain(current.networkType)
        val chain = getRecentDowngradeChain()

        // Signal analysis
        val signalDelta = previous?.let { current.signalStrength - it.signalStrength } ?: 0
        val signalSpiked = signalDelta > SIGNAL_SPIKE_THRESHOLD &&
            timeBetweenMs < SIGNAL_SPIKE_TIME_WINDOW

        // Cell trust analysis
        val cellIdStr = current.cellId?.toString()
        val trustedInfo = cellIdStr?.let { trustedCells[it] }
        val cellTrustScore = when {
            trustedInfo == null -> 0
            trustedInfo.seenCount >= 20 -> 100
            trustedInfo.seenCount >= 10 -> 80
            trustedInfo.seenCount >= 5 -> 60
            trustedInfo.seenCount >= 2 -> 30
            else -> 10
        }
        val cellSeenCount = trustedInfo?.seenCount ?: 0
        val cellAgeSeconds = trustedInfo?.let { (now - it.firstSeen) / 1000 } ?: 0

        // Area familiarity
        val inFamiliarArea = isInFamiliarArea()
        val nearbyTrusted = countNearbyTrustedCells(current.latitude, current.longitude)

        // Network context
        val networkGenChange = if (previous != null && getNetworkGeneration(previous.networkType) != getNetworkGeneration(current.networkType)) {
            "${getNetworkGenerationName(previous.networkType)} → ${getNetworkGenerationName(current.networkType)}"
        } else null

        val lacTacChanged = hasLacTacAnomaly(previous ?: current, current)
        val currentOperator = telephonyManager.networkOperatorName
        val operatorChanged = lastOperator != null && lastOperator != currentOperator
        lastOperator = currentOperator

        // Vulnerability note
        val vulnerabilityNote = when (currentEncryption) {
            EncryptionStrength.NONE -> "WARNING: No encryption - all communications are in plaintext"
            EncryptionStrength.WEAK -> "A5/1 cipher can be cracked in real-time with commodity hardware"
            EncryptionStrength.MODERATE -> "KASUMI cipher has known weaknesses but requires significant resources"
            EncryptionStrength.STRONG -> null
        }

        // Build initial analysis to calculate IMSI score
        val prelimAnalysis = CellularAnomalyAnalysis(
            encryptionDowngradeChain = chain,
            downgradeWithSignalSpike = encryptionDowngraded && signalSpiked,
            downgradeWithNewTower = encryptionDowngraded && cellTrustScore < 30,
            imsiCatcherScore = 0, // Will be calculated
            distanceMeters = distanceMeters,
            speedKmh = speedKmh,
            movementType = movementType,
            impossibleSpeed = impossibleSpeed,
            timeBetweenSamplesMs = timeBetweenMs,
            cellTrustScore = cellTrustScore,
            cellSeenCount = cellSeenCount,
            isInFamiliarArea = inFamiliarArea,
            nearbyTrustedCells = nearbyTrusted,
            cellAgeSeconds = cellAgeSeconds,
            currentEncryption = currentEncryption,
            previousEncryption = previousEncryption,
            encryptionDowngraded = encryptionDowngraded,
            vulnerabilityNote = vulnerabilityNote,
            networkGenerationChange = networkGenChange,
            lacTacChanged = lacTacChanged,
            operatorChanged = operatorChanged,
            isRoaming = telephonyManager.isNetworkRoaming,
            signalDeltaDbm = signalDelta,
            signalSpikeDetected = signalSpiked,
            currentSignalDbm = current.signalStrength,
            signalQuality = getSignalQuality(current.signalStrength)
        )

        // Calculate IMSI catcher score
        val imsiScore = calculateImsiCatcherScore(prelimAnalysis)

        return prelimAnalysis.copy(imsiCatcherScore = imsiScore)
    }

    /**
     * Build enriched technical details string from analysis
     */
    private fun buildCellularTechnicalDetails(
        analysis: CellularAnomalyAnalysis,
        current: CellSnapshot,
        previous: CellSnapshot?
    ): String {
        val parts = mutableListOf<String>()

        // IMSI Catcher likelihood
        parts.add("IMSI Catcher Likelihood: ${analysis.imsiCatcherScore}%")

        // Encryption details
        parts.add("Encryption: ${analysis.currentEncryption.displayName}")
        if (analysis.encryptionDowngraded && analysis.previousEncryption != null) {
            parts.add("Downgrade: ${analysis.previousEncryption.displayName} → ${analysis.currentEncryption.displayName}")
        }
        analysis.vulnerabilityNote?.let { parts.add("⚠️ $it") }

        // Movement analysis
        if (analysis.distanceMeters > 0) {
            parts.add("Movement: ${String.format("%.1f", analysis.distanceMeters)}m at ${String.format("%.1f", analysis.speedKmh)} km/h (${analysis.movementType.displayName})")
        }
        if (analysis.impossibleSpeed) {
            parts.add("⚠️ IMPOSSIBLE SPEED - potential location spoofing or IMSI catcher mobility")
        }

        // Cell trust
        parts.add("Cell Trust: ${analysis.cellTrustScore}% (seen ${analysis.cellSeenCount} times)")
        if (analysis.cellAgeSeconds > 0) {
            val ageMinutes = analysis.cellAgeSeconds / 60
            parts.add("Cell age: ${ageMinutes}min in database")
        }

        // Network context
        analysis.networkGenerationChange?.let { parts.add("Network change: $it") }
        if (analysis.lacTacChanged) parts.add("⚠️ LAC/TAC changed without cell change")
        if (analysis.operatorChanged) parts.add("⚠️ Operator changed")
        if (analysis.isRoaming) parts.add("Currently roaming")

        // Signal
        parts.add("Signal: ${analysis.currentSignalDbm} dBm (${analysis.signalQuality})")
        if (analysis.signalSpikeDetected) {
            parts.add("⚠️ Signal spiked +${analysis.signalDeltaDbm} dBm")
        }

        // Downgrade chain
        if (analysis.encryptionDowngradeChain.size > 1) {
            parts.add("Recent network chain: ${analysis.encryptionDowngradeChain.joinToString(" → ")}")
        }

        return parts.joinToString("\n")
    }

    /**
     * Build contributing factors list from analysis
     */
    private fun buildCellularContributingFactors(
        analysis: CellularAnomalyAnalysis
    ): List<String> {
        val factors = mutableListOf<String>()

        if (analysis.encryptionDowngraded) {
            factors.add("Encryption downgraded to ${analysis.currentEncryption.displayName}")
        }

        if (analysis.downgradeWithSignalSpike) {
            factors.add("Downgrade coincided with signal spike (+${analysis.signalDeltaDbm} dBm)")
        }

        if (analysis.downgradeWithNewTower) {
            factors.add("Downgrade to unknown/untrusted cell tower")
        }

        if (analysis.impossibleSpeed) {
            factors.add("Impossible movement speed (${String.format("%.0f", analysis.speedKmh)} km/h)")
        }

        if (analysis.movementType == MovementType.STATIONARY && analysis.cellTrustScore < 30) {
            factors.add("Cell changed while stationary to untrusted tower")
        }

        if (analysis.cellTrustScore < 20) {
            factors.add("Very low cell trust score (${analysis.cellTrustScore}%)")
        }

        if (!analysis.isInFamiliarArea && analysis.nearbyTrustedCells == 0) {
            factors.add("In unfamiliar area with no trusted cells nearby")
        }

        if (analysis.lacTacChanged) {
            factors.add("Location area code changed without cell change (unusual)")
        }

        if (analysis.operatorChanged) {
            factors.add("Carrier/operator changed unexpectedly")
        }

        if (analysis.signalSpikeDetected) {
            factors.add("Sudden signal spike detected")
        }

        analysis.vulnerabilityNote?.let {
            factors.add("Vulnerability: $it")
        }

        // IMSI catcher chain detection
        val chain = analysis.encryptionDowngradeChain
        if (chain.size >= 3 && chain.last() == "2G") {
            factors.add("Progressive downgrade pattern detected: ${chain.joinToString(" → ")}")
        }

        if (analysis.imsiCatcherScore >= 70) {
            factors.add("HIGH IMSI catcher signature score: ${analysis.imsiCatcherScore}%")
        } else if (analysis.imsiCatcherScore >= 50) {
            factors.add("Moderate IMSI catcher signature score: ${analysis.imsiCatcherScore}%")
        }

        return factors
    }

    /**
     * Convert a cellular anomaly to a Detection for storage
     */
    fun anomalyToDetection(anomaly: CellularAnomaly): Detection {
        val detectionMethod = when (anomaly.type) {
            AnomalyType.ENCRYPTION_DOWNGRADE -> DetectionMethod.CELL_ENCRYPTION_DOWNGRADE
            AnomalyType.SUSPICIOUS_NETWORK -> DetectionMethod.CELL_SUSPICIOUS_NETWORK
            AnomalyType.CELL_TOWER_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
            AnomalyType.STATIONARY_CELL_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
            AnomalyType.RAPID_CELL_SWITCHING -> DetectionMethod.CELL_RAPID_SWITCHING
            AnomalyType.SIGNAL_SPIKE -> DetectionMethod.CELL_SIGNAL_ANOMALY
            AnomalyType.LAC_TAC_ANOMALY -> DetectionMethod.CELL_LAC_TAC_ANOMALY
            AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> DetectionMethod.CELL_TOWER_CHANGE
        }
        
        val deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}"
        
        return Detection(
            deviceType = DeviceType.STINGRAY_IMSI,
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = detectionMethod,
            deviceName = deviceName,
            macAddress = null,
            ssid = null,
            rssi = anomaly.signalStrength,
            signalStrength = rssiToSignalStrength(anomaly.signalStrength),
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            threatLevel = anomaly.severity,
            threatScore = when (anomaly.confidence) {
                AnomalyConfidence.CRITICAL -> 95
                AnomalyConfidence.HIGH -> 75
                AnomalyConfidence.MEDIUM -> 50
                AnomalyConfidence.LOW -> 25
            },
            manufacturer = "Cell: ${anomaly.cellId ?: "Unknown"}",
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = anomaly.contributingFactors.joinToString(", ")
        )
    }
}

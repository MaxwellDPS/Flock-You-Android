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
class CellularMonitor(private val context: Context) {
    
    companion object {
        private const val TAG = "CellularMonitor"

        // Thresholds - tuned to reduce false positives
        private const val SIGNAL_SPIKE_THRESHOLD = 25 // dBm - increased from 20
        private const val SIGNAL_SPIKE_TIME_WINDOW = 5_000L // Must occur within 5 seconds
        private const val MIN_ANOMALY_INTERVAL_MS = 60_000L // 1 minute between same anomaly type
        private const val GLOBAL_ANOMALY_COOLDOWN_MS = 30_000L // 30 seconds between ANY anomaly
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
        
        registerCellListener()
        
        // Take initial snapshot
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
        }
    }
    
    fun stopMonitoring() {
        isMonitoring = false
        unregisterCellListener()
        
        addTimelineEvent(
            type = CellularEventType.MONITORING_STOPPED,
            title = "Cellular Monitoring Stopped",
            description = "IMSI catcher detection paused"
        )
        
        Log.d(TAG, "Stopped cellular monitoring")
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
        if (now - lastAnyAnomalyTime < GLOBAL_ANOMALY_COOLDOWN_MS) {
            return
        }

        val contributingFactors = mutableListOf<String>()
        var totalScore = 0

        val mccMnc = "${current.mcc}-${current.mnc}"
        val timeSinceLastSnapshot = current.timestamp - previous.timestamp

        // BUG FIX: Only consider user stationary if we have RECENT location data.
        // If GPS is stale/unavailable, assume user MIGHT be moving (benefit of the doubt).
        val hasRecentLocation = hasRecentLocationData()
        val isStationary = hasRecentLocation && estimatedMovementSpeed < MOVEMENT_SPEED_THRESHOLD

        val currentCellTrusted = isCellTrusted(current.cellId?.toString())
        
        // 1. CRITICAL: Suspicious MCC/MNC - always flag
        if (mccMnc in SUSPICIOUS_MCC_MNC) {
            reportAnomalyImproved(
                type = AnomalyType.SUSPICIOUS_NETWORK,
                description = "Connected to suspicious test network",
                technicalDetails = "MCC/MNC $mccMnc is a known test/development network identifier commonly used by IMSI catchers",
                contributingFactors = listOf("Test network MCC/MNC detected", "Network: $mccMnc"),
                confidence = AnomalyConfidence.CRITICAL,
                current = current,
                previous = previous
            )
            return // Don't analyze further - this is definitive
        }
        
        // 2. HIGH: Encryption downgrade to 2G
        if (isEncryptionDowngrade(previous.networkType, current.networkType)) {
            contributingFactors.add("Encryption downgrade: ${getNetworkTypeName(previous.networkType)} → ${getNetworkTypeName(current.networkType)}")
            totalScore += AnomalyType.ENCRYPTION_DOWNGRADE.baseScore
            
            // Additional factors that increase suspicion
            if (isStationary) {
                contributingFactors.add("Device is stationary")
                totalScore += 15
            }
            if (!currentCellTrusted) {
                contributingFactors.add("New/unknown cell tower")
                totalScore += 10
            }
            
            // 2G downgrade is always at least MEDIUM
            val confidence = when {
                totalScore >= 100 -> AnomalyConfidence.CRITICAL
                totalScore >= 85 -> AnomalyConfidence.HIGH
                else -> AnomalyConfidence.MEDIUM
            }
            
            reportAnomalyImproved(
                type = AnomalyType.ENCRYPTION_DOWNGRADE,
                description = "Network forced to use weaker 2G encryption",
                technicalDetails = "Downgraded from ${getNetworkTypeName(previous.networkType)} to ${getNetworkTypeName(current.networkType)}. " +
                    "2G networks (GSM/EDGE) use A5/1 or A5/0 encryption which is vulnerable or non-existent.",
                contributingFactors = contributingFactors,
                confidence = confidence,
                current = current,
                previous = previous
            )
            return
        }
        
        // 3. Cell tower changed - check context
        if (current.cellId != previous.cellId && current.cellId != null) {
            // Record timeline event first
            addTimelineEvent(
                type = if (currentCellTrusted) CellularEventType.RETURNED_TO_TRUSTED else CellularEventType.CELL_HANDOFF,
                title = if (currentCellTrusted) "Returned to Known Cell" else "Cell Handoff",
                description = "Cell changed: ${previous.cellId ?: "?"} → ${current.cellId}",
                cellId = current.cellId.toString(),
                networkType = getNetworkTypeName(current.networkType),
                signalStrength = current.signalStrength
            )
            
            // Only flag if suspicious circumstances
            if (isStationary && !currentCellTrusted) {
                // Stationary + new cell = suspicious
                contributingFactors.add("Cell changed while stationary")
                contributingFactors.add("New cell tower not previously seen")
                totalScore += AnomalyType.STATIONARY_CELL_CHANGE.baseScore + 20
            } else if (isStationary && currentCellTrusted) {
                // Stationary but returned to known cell - probably normal
                addTimelineEvent(
                    type = CellularEventType.CELL_HANDOFF,
                    title = "Normal Handoff",
                    description = "Switched to trusted cell ${current.cellId}",
                    cellId = current.cellId.toString(),
                    networkType = getNetworkTypeName(current.networkType),
                    signalStrength = current.signalStrength,
                    threatLevel = ThreatLevel.INFO
                )
                // Don't report as anomaly
            } else if (!isStationary) {
                // Moving - cell changes are expected
                // Only log in timeline, don't report
            }
        }
        
        // 4. Check for rapid cell switching
        val recentChanges = countRecentCellChanges(60_000)
        val switchThreshold = if (isStationary) RAPID_SWITCH_COUNT_WALKING else RAPID_SWITCH_COUNT_DRIVING
        
        if (recentChanges > switchThreshold) {
            contributingFactors.add("$recentChanges cell changes in last minute")
            contributingFactors.add(if (isStationary) "Device is stationary" else "Device is moving")
            totalScore += AnomalyType.RAPID_CELL_SWITCHING.baseScore
            
            if (isStationary) {
                totalScore += 25 // Much more suspicious if not moving
            }
        }
        
        // 5. Check for signal spike
        val signalDelta = current.signalStrength - (previous.signalStrength)
        if (signalDelta > SIGNAL_SPIKE_THRESHOLD && timeSinceLastSnapshot < SIGNAL_SPIKE_TIME_WINDOW) {
            contributingFactors.add("Signal spiked +${signalDelta}dBm in ${timeSinceLastSnapshot/1000}s")
            totalScore += AnomalyType.SIGNAL_SPIKE.baseScore
            
            // More suspicious if combined with cell change
            if (current.cellId != previous.cellId) {
                contributingFactors.add("Combined with cell tower change")
                totalScore += 15
            }
        }
        
        // 6. Unknown cell in familiar area
        if (!currentCellTrusted && isInFamiliarArea()) {
            contributingFactors.add("Unknown cell tower in familiar location")
            totalScore += AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA.baseScore
        }
        
        // 7. LAC/TAC anomaly
        if (hasLacTacAnomaly(previous, current)) {
            contributingFactors.add("Location area code changed without cell change")
            totalScore += AnomalyType.LAC_TAC_ANOMALY.baseScore
        }
        
        // Determine if we should report based on total score and factors
        if (contributingFactors.isNotEmpty() && totalScore >= 50) {
            val confidence = when {
                contributingFactors.size >= 4 -> AnomalyConfidence.CRITICAL
                contributingFactors.size >= 3 -> AnomalyConfidence.HIGH
                contributingFactors.size >= 2 -> AnomalyConfidence.MEDIUM
                else -> AnomalyConfidence.LOW
            }
            
            // Only report if confidence is at least MEDIUM, unless score is very high
            if (confidence.ordinal >= AnomalyConfidence.MEDIUM.ordinal || totalScore >= 70) {
                val primaryType = when {
                    contributingFactors.any { it.contains("stationary", ignoreCase = true) && it.contains("cell", ignoreCase = true) } ->
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
                    description = "Multiple suspicious indicators detected",
                    technicalDetails = contributingFactors.joinToString("; "),
                    contributingFactors = contributingFactors,
                    confidence = confidence,
                    current = current,
                    previous = previous
                )
            } else {
                // Low confidence - just log to timeline
                addTimelineEvent(
                    type = CellularEventType.CELL_HANDOFF,
                    title = "Cell Activity",
                    description = contributingFactors.firstOrNull() ?: "Cell network change",
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
        previous: CellSnapshot
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastAnomalyTimes[type] ?: 0

        // Rate limit same anomaly type
        if (now - lastTime < MIN_ANOMALY_INTERVAL_MS) {
            return
        }
        lastAnomalyTimes[type] = now
        lastAnyAnomalyTime = now // Update global cooldown
        
        // Calculate severity based on confidence and base score
        val severity = when (confidence) {
            AnomalyConfidence.CRITICAL -> ThreatLevel.CRITICAL
            AnomalyConfidence.HIGH -> ThreatLevel.HIGH
            AnomalyConfidence.MEDIUM -> ThreatLevel.MEDIUM
            AnomalyConfidence.LOW -> ThreatLevel.LOW
        }
        
        val anomaly = CellularAnomaly(
            type = type,
            severity = severity,
            confidence = confidence,
            description = description,
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
        
        // Add to timeline
        addTimelineEvent(
            type = CellularEventType.ANOMALY_DETECTED,
            title = "${type.emoji} ${type.displayName}",
            description = description,
            cellId = current.cellId?.toString(),
            networkType = getNetworkTypeName(current.networkType),
            signalStrength = current.signalStrength,
            isAnomaly = true,
            threatLevel = severity
        )
        
        Log.w(TAG, "ANOMALY [${confidence.displayName}]: ${type.displayName} - $description")
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
                mcc = registeredCell.cellIdentity.mccString
                mnc = registeredCell.cellIdentity.mncString
                signalDbm = registeredCell.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_LTE
            }
            is CellInfoGsm -> {
                cellId = registeredCell.cellIdentity.cid.toLong()
                lac = registeredCell.cellIdentity.lac
                mcc = registeredCell.cellIdentity.mccString
                mnc = registeredCell.cellIdentity.mncString
                signalDbm = registeredCell.cellSignalStrength.dbm
                networkType = TelephonyManager.NETWORK_TYPE_GSM
            }
            is CellInfoWcdma -> {
                cellId = registeredCell.cellIdentity.cid.toLong()
                lac = registeredCell.cellIdentity.lac
                mcc = registeredCell.cellIdentity.mccString
                mnc = registeredCell.cellIdentity.mncString
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

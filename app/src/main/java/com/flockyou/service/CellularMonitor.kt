package com.flockyou.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import android.util.Log
import androidx.core.content.ContextCompat
import com.flockyou.data.model.*
import com.flockyou.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
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

        // ==================== KNOWN IMSI CATCHER SIGNATURES ====================
        // Real-world IMSI catcher device characteristics based on documented deployments

        /**
         * Known IMSI Catcher Device Types:
         *
         * 1. Harris StingRay/StingRay II:
         *    - Most common law enforcement IMSI catcher in the US
         *    - Often uses LAC 1 or very low LAC values (1-10)
         *    - Forces 2G downgrade for interception
         *    - Typical cell IDs: sequential or round numbers
         *
         * 2. Harris Hailstorm:
         *    - Upgraded StingRay with 4G/LTE capability
         *    - Can intercept LTE but still often forces 2G for content
         *    - Similar LAC patterns to StingRay
         *
         * 3. DRT (DRTbox/Dirtbox):
         *    - Airplane-mounted IMSI catcher (used by US Marshals, FBI)
         *    - Characterized by VERY strong signals from above
         *    - Larger coverage area, signals from unusual elevation
         *    - Often -50 dBm or stronger signal
         *
         * 4. Septier IMSI Catcher:
         *    - Israeli-made, common in Middle East and Europe
         *    - Similar characteristics to StingRay
         *
         * 5. Ability Unlimited ULIN:
         *    - Passive collection variant (harder to detect)
         *    - May not force downgrades
         */

        // Suspicious LAC values commonly associated with IMSI catchers
        // StingRay devices often use LAC 1 or very low LAC values
        private val SUSPICIOUS_LAC_VALUES = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        // Very low TAC values are also suspicious for LTE
        private val SUSPICIOUS_TAC_VALUES = setOf(0, 1, 2, 3, 4, 5)

        // Known suspicious/test MCC-MNC codes that should NEVER appear on real networks
        private val SUSPICIOUS_MCC_MNC = setOf(
            // ITU Test Networks (used by IMSI catchers)
            "001-01", "001-00", "001-02", "001-001",
            // Reserved test networks
            "999-99", "999-01", "999-00",
            // Invalid codes
            "000-00", "000-01",
            // Additional test codes sometimes seen
            "002-01", "002-02",
            // Codes that should never be broadcast
            "901-01", "901-18"  // International networks - shouldn't appear as primary
        )

        // ==================== US CARRIER MCC-MNC CODES ====================
        // Legitimate US carrier codes - if connected to US but NOT one of these, suspicious

        // T-Mobile USA and subsidiaries
        private val TMOBILE_MNC_CODES = setOf(
            "260", "200", "210", "220", "230", "240", "250", "260", "270",
            "310", "490", "660", "800", "160", "026"
        )

        // AT&T USA
        private val ATT_MNC_CODES = setOf(
            "410", "150", "170", "380", "560", "680", "980", "070"
        )

        // Verizon Wireless
        private val VERIZON_MNC_CODES = setOf(
            "480", "481", "482", "483", "484", "485", "486", "487", "488", "489",
            "012", "110"
        )

        // US Cellular
        private val US_CELLULAR_MNC_CODES = setOf(
            "580", "581", "582", "583", "584", "585", "586", "587", "588"
        )

        // All known legitimate US MNCs (MCC 310 and 311)
        private val KNOWN_US_MNC_CODES_310 = TMOBILE_MNC_CODES + ATT_MNC_CODES + setOf(
            // Sprint (now T-Mobile)
            "120", "130", "140", "150", "160", "170", "180", "190",
            // Other legitimate carriers
            "030", "032", "033", "034", "040", "050", "060", "070", "080", "090"
        )

        private val KNOWN_US_MNC_CODES_311 = VERIZON_MNC_CODES + US_CELLULAR_MNC_CODES + setOf(
            // Other legitimate carriers
            "040", "050", "060", "070", "080", "090", "100", "110", "120", "220"
        )

        // ==================== IMSI CATCHER CELL ID PATTERNS ====================

        /**
         * Check if a cell ID has suspicious patterns:
         * - Sequential numbers (12345, 11111)
         * - Round numbers (10000, 50000, 100000)
         * - Very low values (1-100) - often test values
         */
        private fun isSuspiciousCellIdPattern(cellId: Long?): Boolean {
            if (cellId == null) return false

            // Very low cell IDs are suspicious
            if (cellId in 1..100) return true

            // Round numbers (divisible by 1000 or 10000)
            if (cellId % 10000L == 0L && cellId > 0) return true
            if (cellId % 1000L == 0L && cellId < 100000L && cellId > 0) return true

            // Check for repeated digits (11111, 22222, etc.)
            val str = cellId.toString()
            if (str.length >= 4 && str.all { it == str[0] }) return true

            // Check for sequential (12345, 54321)
            if (str.length >= 5) {
                val ascending = str.zipWithNext().all { (a, b) -> b.code == a.code + 1 }
                val descending = str.zipWithNext().all { (a, b) -> b.code == a.code - 1 }
                if (ascending || descending) return true
            }

            return false
        }

        // Very strong signal threshold - signals stronger than this are suspicious
        // especially if they appear suddenly or are from an unknown cell
        // DRTbox (airplane-mounted) often produces -50 dBm or stronger
        private const val SUSPICIOUSLY_STRONG_SIGNAL_DBM = -55

        // Carriers known to have aggressive handoffs (reduce FPs)
        private val AGGRESSIVE_HANDOFF_CARRIERS = setOf(
            "T-Mobile", "Metro", "Sprint", "T-Mobile USA" // These carriers hand off more frequently
        )

        // 5G-specific thresholds - 5G has more frequent handoffs due to smaller cells and beam management
        private const val NR_5G_STATIONARY_HANDOFF_GRACE_PERIOD_MS = 120_000L // 2 minutes grace period for new 5G cells
        private const val NR_5G_MINIMUM_IMSI_SCORE_TO_REPORT = 40 // Higher threshold for 5G (was implicit 0)
    }
    
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // Database for persistence
    private val database by lazy { FlockYouDatabase.getDatabase(context) }
    private val cellularDao by lazy { database.cellularDao() }
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var isDataLoaded = false

    // Ephemeral mode - when enabled, no data is persisted to database
    @Volatile private var ephemeralModeEnabled = false

    /**
     * Set ephemeral mode state. When enabled, cellular data is stored in RAM only
     * and NOT persisted to the database for maximum privacy.
     */
    fun setEphemeralMode(enabled: Boolean) {
        val wasEnabled = ephemeralModeEnabled
        ephemeralModeEnabled = enabled

        if (enabled && !wasEnabled) {
            // Transitioning to ephemeral mode - clear any persisted data
            Log.d(TAG, "Ephemeral mode enabled - clearing persisted cellular data")
            clearPersistedData()
        } else if (!enabled && wasEnabled && isMonitoring) {
            // Transitioning from ephemeral to normal mode while monitoring
            // Persist current in-memory data
            Log.d(TAG, "Ephemeral mode disabled - persisting current cellular data")
            persistAllCurrentData()
        }
    }

    /**
     * Persist all current in-memory data to the database.
     * Called when transitioning from ephemeral to normal mode.
     */
    private fun persistAllCurrentData() {
        if (ephemeralModeEnabled) return

        persistenceScope.launch {
            try {
                // Persist all seen cell towers
                seenCellTowerMap.values.forEach { tower ->
                    cellularDao.insertSeenCellTower(seenCellTowerToEntity(tower))
                }

                // Persist all trusted cells
                trustedCells.forEach { (cellId, info) ->
                    cellularDao.insertTrustedCell(trustedCellInfoToEntity(cellId, info))
                }

                // Persist recent events
                eventHistory.forEach { event ->
                    cellularDao.insertCellularEvent(cellularEventToEntity(event))
                }

                Log.d(TAG, "Persisted ${seenCellTowerMap.size} towers, ${trustedCells.size} trusted cells, ${eventHistory.size} events")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist current cellular data", e)
            }
        }
    }

    // Configurable timing
    private var minAnomalyIntervalMs: Long = DEFAULT_MIN_ANOMALY_INTERVAL_MS
    private var globalAnomalyCooldownMs: Long = DEFAULT_GLOBAL_ANOMALY_COOLDOWN_MS

    // Monitoring state - use volatile for thread visibility
    @Volatile private var isMonitoring = false
    @Volatile private var currentLatitude: Double? = null
    @Volatile private var currentLongitude: Double? = null

    // Cell history for pattern detection - synchronized access required
    private val cellHistory = mutableListOf<CellSnapshot>()
    private val cellHistoryLock = Any()
    @Volatile private var lastKnownCell: CellSnapshot? = null
    private val lastAnomalyTimes = mutableMapOf<AnomalyType, Long>()
    private val anomalyTimesLock = Any()
    
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

    // Stationary cell change pattern tracking for improved heuristics
    private data class StationaryCellChangeEvent(
        val timestamp: Long,
        val fromCellId: Long?,
        val toCellId: Long?,
        val returnedToOriginal: Boolean = false
    )
    private val stationaryCellChanges = mutableListOf<StationaryCellChangeEvent>()
    private val maxStationaryCellChangeHistory = 20
    private val stationaryChangeWindowMs = 300_000L // 5 minute window for pattern analysis
    private val quickReturnThresholdMs = 60_000L // If we return to original cell within 1 minute, likely network optimization

    // Callback for cell info changes
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null // TelephonyCallback for API 31+

    // 5G NSA detection: In NSA mode, CellInfo only shows LTE anchor but TelephonyDisplayInfo shows 5G
    // This tracks the override network type from TelephonyDisplayInfo
    private var displayInfoOverrideType: Int = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
    
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
        val signalQuality: String,                     // "Excellent", "Good", "Fair", "Poor"

        // False Positive Heuristics
        val falsePositiveLikelihood: Float = 0f,       // 0-100%
        val fpIndicators: List<String> = emptyList(),
        val isLikelyNormalHandoff: Boolean = false,    // Normal cell tower switching
        val isLikelyCarrierBehavior: Boolean = false,  // Known aggressive carrier handoff patterns
        val isLikelyEdgeCoverage: Boolean = false,     // User at edge of cell coverage
        val isLikely5gBeamSteering: Boolean = false    // 5G beam management causing handoffs
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
        val contributingFactors: List<String> = emptyList(),
        // Full heuristics analysis for LLM processing
        val analysis: CellularAnomalyAnalysis? = null
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
    
    /**
     * Anomaly types with calibrated base scores.
     *
     * SCORING PHILOSOPHY:
     * - Base scores represent the MINIMUM suspicion level for the anomaly type
     * - Additional factors ADD to the score (never exceed 100)
     * - Score determines severity: 90-100=CRITICAL, 70-89=HIGH, 50-69=MEDIUM, 30-49=LOW, 0-29=INFO
     *
     * IMPORTANT: Base scores are intentionally LOW for common events that need context.
     * A single cell change while stationary is common (network optimization).
     * Multiple cell changes, or changes combined with other factors, are suspicious.
     */
    enum class AnomalyType(
        val displayName: String,
        val baseScore: Int,
        val emoji: String,
        val requiresMultipleFactors: Boolean,
        val userFriendlyExplanation: String
    ) {
        SIGNAL_SPIKE("Sudden Signal Spike", 15, "📶", true,
            "Your phone's signal strength jumped unusually high, which can indicate a nearby fake cell tower broadcasting a strong signal to attract your phone."),
        CELL_TOWER_CHANGE("Cell Tower Change", 10, "🗼", true,
            "Your phone switched to a different cell tower. This is usually normal, but can be suspicious if combined with other indicators."),
        ENCRYPTION_DOWNGRADE("Encryption Downgrade", 60, "🔓", false,
            "Your phone was forced to use an older, weaker network (like 2G) that has known encryption vulnerabilities. IMSI catchers often force this downgrade to intercept communications."),
        SUSPICIOUS_NETWORK("Suspicious Network ID", 90, "⚠️", false,
            "Your phone connected to a network using test/invalid identifiers. Legitimate networks never use these codes. This is a strong indicator of surveillance equipment."),
        UNKNOWN_CELL_FAMILIAR_AREA("Unknown Cell in Familiar Area", 25, "❓", true,
            "A cell tower appeared in an area where you've been before, but this specific tower has never been seen. Could indicate a new legitimate tower or a temporary fake one."),
        RAPID_CELL_SWITCHING("Rapid Cell Switching", 20, "🔄", true,
            "Your phone is switching between cell towers more frequently than normal, which can indicate competing signals from surveillance equipment."),
        LAC_TAC_ANOMALY("Location Area Anomaly", 20, "📍", true,
            "The cell tower's location area code changed without actually changing towers. This is technically unusual and can indicate network manipulation."),
        STATIONARY_CELL_CHANGE("Cell Changed While Stationary", 15, "🚫", true,
            "Your phone switched towers even though you weren't moving. Single occurrences are often normal (network load balancing), but repeated changes are suspicious.")
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

        // Load persisted data before starting
        loadPersistedData()

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
                synchronized(cellHistoryLock) {
                    cellHistory.add(snapshot)
                }
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
        synchronized(cellHistoryLock) {
            cellHistory.clear()
        }
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
        cancelPersistence()
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
        // Clear persisted data from database
        clearPersistedData()

        // Clear anomaly data
        detectedAnomalies.clear()
        _anomalies.value = emptyList()

        // Clear cell history
        synchronized(cellHistoryLock) {
            cellHistory.clear()
        }
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

        // Clear stationary cell change tracking
        stationaryCellChanges.clear()

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
            // API 31+ uses TelephonyCallback with DisplayInfoListener for 5G NSA detection
            try {
                val callback = object : TelephonyCallback(),
                    TelephonyCallback.CellInfoListener,
                    TelephonyCallback.DisplayInfoListener {
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        processCellInfoChange(cellInfo)
                    }

                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        // Track the override type for 5G NSA detection
                        // In 5G NSA mode, CellInfo shows LTE but DisplayInfo shows NR_NSA
                        displayInfoOverrideType = telephonyDisplayInfo.overrideNetworkType
                        Log.d(TAG, "DisplayInfo changed: override=${getOverrideTypeName(displayInfoOverrideType)}")

                        // Re-update cell status if we have a current snapshot to reflect 5G NSA
                        lastKnownCell?.let { updateCellStatus(it) }
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

        // Always update history - synchronized for thread safety
        synchronized(cellHistoryLock) {
            cellHistory.add(snapshot)
            while (cellHistory.size > CELL_HISTORY_SIZE) {
                cellHistory.removeAt(0)
            }
        }
        
        // Update seen cell towers
        updateSeenCellTower(snapshot)
        
        // Update trusted cell info
        snapshot.cellId?.let { cellId ->
            val cellIdStr = cellId.toString()
            val trusted = getOrCreateTrustedCellInfo(cellIdStr)
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
            // Persist trusted cell update
            persistTrustedCell(cellIdStr, trusted)
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

        // 3. Cell tower changed - check context with enriched analysis and pattern heuristics
        if (current.cellId != previous.cellId && current.cellId != null) {
            // Check if this is a 5G handoff - these are MUCH more frequent and normal
            val is5GHandoff = current.networkType == TelephonyManager.NETWORK_TYPE_NR &&
                previous.networkType == TelephonyManager.NETWORK_TYPE_NR
            val isSameCarrier = current.mcc == previous.mcc && current.mnc == previous.mnc
            val isAggressiveHandoffCarrier = telephonyManager.networkOperatorName in AGGRESSIVE_HANDOFF_CARRIERS

            // Record timeline event first
            addTimelineEvent(
                type = if (currentCellTrusted) CellularEventType.RETURNED_TO_TRUSTED else CellularEventType.CELL_HANDOFF,
                title = if (currentCellTrusted) "Returned to Known Cell" else if (is5GHandoff) "5G Cell Handoff" else "Cell Handoff",
                description = "Cell changed: ${previous.cellId ?: "?"} -> ${current.cellId} (${analysis.movementType.displayName}, ${String.format("%.0f", analysis.distanceMeters)}m)",
                cellId = current.cellId.toString(),
                networkType = getNetworkTypeName(current.networkType),
                signalStrength = current.signalStrength
            )

            // ===== STATIONARY CELL CHANGE PATTERN ANALYSIS =====
            // Track cell changes while stationary for pattern detection
            if (isStationary) {
                trackStationaryCellChange(previous.cellId, current.cellId)
            }

            // Analyze stationary cell change patterns
            val stationaryPattern = analyzeStationaryCellPattern(previous.cellId, current.cellId)

            // Use enriched movement analysis for better detection
            if (isStationary && !currentCellTrusted) {
                // 5G networks do frequent handoffs even while stationary - this is NORMAL behavior
                // Only flag if:
                // 1. IMSI catcher score is significant (>= 40%)
                // 2. OR there are multiple other suspicious factors
                // 3. AND it's NOT a same-carrier 5G handoff on an aggressive handoff carrier
                val shouldSuppress5GHandoff = is5GHandoff && isSameCarrier &&
                    (isAggressiveHandoffCarrier || analysis.imsiCatcherScore < NR_5G_MINIMUM_IMSI_SCORE_TO_REPORT)

                // NEW: Check for quick return pattern (network optimization, not threat)
                if (stationaryPattern.isQuickReturn) {
                    Log.d(TAG, "Quick return pattern detected - likely network optimization, suppressing")
                    contributingFactors.add("Note: Quick return to original cell detected (likely network optimization)")
                    // Don't add score - this is likely benign
                } else if (shouldSuppress5GHandoff) {
                    // Normal 5G handoff - don't report but log for debug
                    Log.d(TAG, "Suppressing 5G handoff alert: same carrier, IMSI=${analysis.imsiCatcherScore}%")
                } else {
                    // Stationary + new cell = potentially suspicious, but calibrate score based on patterns
                    contributingFactors.add("Cell changed while ${analysis.movementType.displayName.lowercase()}")
                    contributingFactors.add("New cell tower (trust: ${analysis.cellTrustScore}%)")

                    // NEW: Score based on pattern, not just single event
                    val baseHandoffScore = AnomalyType.STATIONARY_CELL_CHANGE.baseScore // Now 15 (was 50)

                    // Add context-based scoring
                    var patternScore = baseHandoffScore

                    // Multiple changes in short window = more suspicious
                    if (stationaryPattern.recentChangesCount >= 3) {
                        patternScore += 25
                        contributingFactors.add("${stationaryPattern.recentChangesCount} cell changes in ${stationaryChangeWindowMs / 60000} minutes while stationary")
                    } else if (stationaryPattern.recentChangesCount >= 2) {
                        patternScore += 10
                        contributingFactors.add("${stationaryPattern.recentChangesCount} cell changes recently while stationary")
                    }

                    // Oscillating between same cells = network issue, not threat
                    if (stationaryPattern.isOscillating) {
                        patternScore -= 10 // Reduce suspicion
                        contributingFactors.add("Note: Oscillating between same cells (likely edge-of-coverage)")
                    }

                    // Unknown cell that's never been seen anywhere = more suspicious
                    if (analysis.cellTrustScore == 0 && !analysis.isInFamiliarArea) {
                        patternScore += 15
                        contributingFactors.add("Completely unknown cell in unfamiliar area")
                    }

                    // 5G same-carrier handoffs are less suspicious
                    if (is5GHandoff && isSameCarrier) {
                        patternScore -= 5
                    }

                    totalScore += patternScore.coerceAtLeast(0)

                    // Check for impossible movement (potential IMSI catcher mobility)
                    if (analysis.impossibleSpeed) {
                        contributingFactors.add("IMPOSSIBLE movement: ${String.format("%.0f", analysis.speedKmh)} km/h")
                        totalScore += 25
                    }
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
                // Don't report as anomaly - this is normal behavior
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
        // FIXED: Use IMSI score for reporting threshold, not arbitrary confidence levels
        // Only report if IMSI score reaches at least LOW concern level (30+)
        if (contributingFactors.isNotEmpty() && analysis.imsiCatcherScore >= 30) {
            // Determine confidence based on factor quality and count
            val confidence = when {
                analysis.imsiCatcherScore >= 90 -> AnomalyConfidence.CRITICAL
                analysis.imsiCatcherScore >= 70 -> AnomalyConfidence.HIGH
                analysis.imsiCatcherScore >= 50 -> AnomalyConfidence.MEDIUM
                else -> AnomalyConfidence.LOW
            }

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

            // Build a specific description based on the primary anomaly type and factors
            val specificDescription = buildSpecificAnomalyDescription(primaryType, analysis, contributingFactors)

            reportAnomalyImproved(
                type = primaryType,
                description = specificDescription,
                technicalDetails = buildCellularTechnicalDetails(analysis, current, previous),
                contributingFactors = contributingFactors,
                confidence = confidence,
                current = current,
                previous = previous,
                analysis = analysis
            )
        } else if (contributingFactors.isNotEmpty() && analysis.imsiCatcherScore >= 15) {
            // Low score (15-29) - log to timeline as informational, don't alert
            addTimelineEvent(
                type = CellularEventType.CELL_HANDOFF,
                title = "Cell Activity (Normal)",
                description = "${contributingFactors.firstOrNull() ?: "Cell network change"} - IMSI score: ${analysis.imsiCatcherScore}% (below alert threshold)",
                cellId = current.cellId?.toString(),
                networkType = getNetworkTypeName(current.networkType),
                signalStrength = current.signalStrength,
                threatLevel = ThreatLevel.INFO
            )
        }
        // Below 15% IMSI score - don't report at all, this is normal network behavior
    }

    /**
     * Build a specific, contextual description for the anomaly instead of generic text.
     */
    private fun buildSpecificAnomalyDescription(
        type: AnomalyType,
        analysis: CellularAnomalyAnalysis,
        factors: List<String>
    ): String {
        val sb = StringBuilder()

        when (type) {
            AnomalyType.STATIONARY_CELL_CHANGE -> {
                sb.append("Cell tower changed while you were stationary")
                if (analysis.cellTrustScore < 30) {
                    sb.append(" to an unfamiliar tower")
                }
                if (factors.any { it.contains("changes in") }) {
                    val countMatch = factors.find { it.contains("changes in") }
                    sb.append(". $countMatch")
                }
            }
            AnomalyType.RAPID_CELL_SWITCHING -> {
                sb.append("Rapid cell tower switching detected")
                val countMatch = factors.find { it.contains("cell changes") }
                countMatch?.let { sb.append(": $it") }
            }
            AnomalyType.SIGNAL_SPIKE -> {
                sb.append("Unusual signal spike detected")
                if (analysis.signalDeltaDbm > 0) {
                    sb.append(" (+${analysis.signalDeltaDbm} dBm)")
                }
            }
            AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> {
                sb.append("Unknown cell tower appeared in an area where you've been before")
            }
            AnomalyType.CELL_TOWER_CHANGE -> {
                if (analysis.impossibleSpeed) {
                    sb.append("Cell tower change with impossible movement speed (${String.format("%.0f", analysis.speedKmh)} km/h) - possible IMSI catcher")
                } else {
                    sb.append("Suspicious cell tower change detected")
                }
            }
            else -> {
                sb.append(type.displayName)
            }
        }

        return sb.toString()
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

        // Rate limit same anomaly type - thread-safe access
        val lastTime = synchronized(anomalyTimesLock) { lastAnomalyTimes[type] ?: 0 }
        if (now - lastTime < minAnomalyIntervalMs) {
            return
        }
        synchronized(anomalyTimesLock) {
            lastAnomalyTimes[type] = now
        }
        lastAnyAnomalyTime = now // Update global cooldown

        val imsiScore = analysis?.imsiCatcherScore ?: 0

        // FIXED: Severity MUST match the IMSI likelihood score
        // Score 90-100 = CRITICAL (immediate threat)
        // Score 70-89 = HIGH (confirmed surveillance indicators)
        // Score 50-69 = MEDIUM (likely surveillance equipment)
        // Score 30-49 = LOW (possible, continue monitoring)
        // Score 0-29 = INFO (notable but not threatening)
        val severity = when {
            imsiScore >= 90 -> ThreatLevel.CRITICAL
            imsiScore >= 70 -> ThreatLevel.HIGH
            imsiScore >= 50 -> ThreatLevel.MEDIUM
            imsiScore >= 30 -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }

        // Build rich, actionable description
        val enrichedDescription = buildRichDescription(type, analysis, contributingFactors, imsiScore)

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
            contributingFactors = contributingFactors,
            analysis = analysis  // Include full heuristics analysis for LLM
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

        // Persist the event
        persistCellularEvent(event)
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

    /**
     * Get human-readable name for TelephonyDisplayInfo override network type.
     * Used for 5G NSA detection logging.
     */
    private fun getOverrideTypeName(overrideType: Int): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (overrideType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADVANCED_PRO"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA (5G)"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> "NR_NSA_MMWAVE (5G)"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED (5G)"
                else -> "UNKNOWN($overrideType)"
            }
        } else {
            "N/A (API < 30)"
        }
    }

    private fun countRecentCellChanges(withinMs: Long): Int {
        val cutoff = System.currentTimeMillis() - withinMs
        // Take a thread-safe snapshot of cell history
        val historySnapshot = synchronized(cellHistoryLock) { cellHistory.toList() }
        val recentSnapshots = historySnapshot.filter { it.timestamp > cutoff }

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
        // In 5G NSA (Non-Standalone) mode, phones connect to both LTE (anchor) and NR (5G)
        // simultaneously. Prioritize NR cells to correctly show 5G when active.
        val registeredCells = cellInfoList.filter { it.isRegistered }
        if (registeredCells.isEmpty()) return null

        // Check for 5G NR first (prioritize over LTE in NSA mode)
        val registeredCell = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registeredCells.firstOrNull { it is CellInfoNr } ?: registeredCells.first()
        } else {
            registeredCells.first()
        }

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
        var networkGen = getNetworkGeneration(snapshot.networkType)

        // Check for 5G NSA: In NSA mode, CellInfo shows LTE but TelephonyDisplayInfo shows 5G
        // Override the network generation if we're actually on 5G NSA
        var effectiveNetworkType = snapshot.networkType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val is5gNsa = displayInfoOverrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                displayInfoOverrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE ||
                displayInfoOverrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
            if (is5gNsa && networkGen < 5) {
                networkGen = 5
                effectiveNetworkType = TelephonyManager.NETWORK_TYPE_NR
                Log.d(TAG, "5G NSA detected via DisplayInfo override, upgrading display from ${getNetworkTypeName(snapshot.networkType)} to 5G")
            }
        }

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
            networkType = getNetworkTypeName(effectiveNetworkType),
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

        // Check for 5G NSA: apply same override logic as updateCellStatus()
        var effectiveNetworkType = snapshot.networkType
        var networkGen = getNetworkGeneration(snapshot.networkType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val is5gNsa = displayInfoOverrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                displayInfoOverrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE ||
                displayInfoOverrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
            if (is5gNsa && networkGen < 5) {
                networkGen = 5
                effectiveNetworkType = TelephonyManager.NETWORK_TYPE_NR
            }
        }

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
            val updatedTower = existing.copy(
                lastSeen = System.currentTimeMillis(),
                seenCount = existing.seenCount + 1,
                minSignal = minOf(existing.minSignal, snapshot.signalStrength),
                maxSignal = maxOf(existing.maxSignal, snapshot.signalStrength),
                lastSignal = snapshot.signalStrength,
                latitude = snapshot.latitude ?: existing.latitude,
                longitude = snapshot.longitude ?: existing.longitude,
                isTrusted = trusted?.seenCount ?: 0 >= TRUSTED_CELL_THRESHOLD
            )
            seenCellTowerMap[cellId] = updatedTower
            persistSeenCellTower(updatedTower)
        } else {
            // New cell discovered - add to timeline
            addTimelineEvent(
                type = CellularEventType.NEW_CELL_DISCOVERED,
                title = "New Cell Tower",
                description = "Cell $cellId (${getNetworkTypeName(effectiveNetworkType)}) - ${telephonyManager.networkOperatorName ?: "Unknown operator"}",
                cellId = cellId,
                networkType = getNetworkTypeName(effectiveNetworkType),
                signalStrength = snapshot.signalStrength
            )

            val newTower = SeenCellTower(
                cellId = cellId,
                lac = snapshot.lac,
                tac = snapshot.tac,
                mcc = snapshot.mcc,
                mnc = snapshot.mnc,
                operator = telephonyManager.networkOperatorName,
                networkType = getNetworkTypeName(effectiveNetworkType),
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
            seenCellTowerMap[cellId] = newTower
            persistSeenCellTower(newTower)
        }

        _seenCellTowers.value = seenCellTowerMap.values
            .sortedByDescending { it.lastSeen }
            .toList()
    }
    
    // ==================== STATIONARY CELL CHANGE PATTERN ANALYSIS ====================

    /**
     * Result of analyzing stationary cell change patterns.
     */
    private data class StationaryCellPatternResult(
        val recentChangesCount: Int,           // Number of changes in tracking window
        val isQuickReturn: Boolean,            // Changed and quickly returned to original
        val isOscillating: Boolean,            // Bouncing between same 2-3 cells
        val uniqueCellsCount: Int,             // Number of unique cells seen recently
        val timeSinceFirstChange: Long         // Time since first change in window
    )

    /**
     * Track a stationary cell change event for pattern analysis.
     */
    private fun trackStationaryCellChange(fromCellId: Long?, toCellId: Long?) {
        val now = System.currentTimeMillis()

        // Check if this is a return to a previous cell
        val isReturn = stationaryCellChanges.isNotEmpty() &&
            stationaryCellChanges.any { it.toCellId == fromCellId || it.fromCellId == toCellId }

        stationaryCellChanges.add(StationaryCellChangeEvent(
            timestamp = now,
            fromCellId = fromCellId,
            toCellId = toCellId,
            returnedToOriginal = isReturn
        ))

        // Clean up old entries
        val cutoff = now - stationaryChangeWindowMs
        stationaryCellChanges.removeAll { it.timestamp < cutoff }

        // Limit history size
        while (stationaryCellChanges.size > maxStationaryCellChangeHistory) {
            stationaryCellChanges.removeAt(0)
        }
    }

    /**
     * Analyze recent stationary cell changes to detect patterns.
     *
     * Heuristics:
     * - Quick return to original cell = likely network optimization (NOT suspicious)
     * - Oscillating between 2-3 cells = likely edge-of-coverage (less suspicious)
     * - Multiple unique cells = more suspicious
     * - Sustained change to unknown cell = most suspicious
     */
    private fun analyzeStationaryCellPattern(fromCellId: Long?, toCellId: Long?): StationaryCellPatternResult {
        val now = System.currentTimeMillis()
        val cutoff = now - stationaryChangeWindowMs
        val recentChanges = stationaryCellChanges.filter { it.timestamp > cutoff }

        if (recentChanges.isEmpty()) {
            return StationaryCellPatternResult(
                recentChangesCount = 0,
                isQuickReturn = false,
                isOscillating = false,
                uniqueCellsCount = 0,
                timeSinceFirstChange = 0
            )
        }

        // Count unique cells involved in recent changes
        val uniqueCells = mutableSetOf<Long>()
        recentChanges.forEach { change ->
            change.fromCellId?.let { uniqueCells.add(it) }
            change.toCellId?.let { uniqueCells.add(it) }
        }

        // Check for quick return pattern:
        // If we changed FROM cell A TO cell B, and now we're changing back TO cell A
        // within the quick return threshold, this is likely network optimization
        val isQuickReturn = recentChanges.any { change ->
            change.toCellId == fromCellId &&
            (now - change.timestamp) < quickReturnThresholdMs
        }

        // Check for oscillation pattern:
        // If we're bouncing between the same 2-3 cells, this is usually edge-of-coverage
        val isOscillating = uniqueCells.size <= 3 && recentChanges.size >= 3 &&
            recentChanges.count { it.returnedToOriginal } >= recentChanges.size / 2

        val timeSinceFirst = if (recentChanges.isNotEmpty()) {
            now - recentChanges.first().timestamp
        } else 0L

        return StationaryCellPatternResult(
            recentChangesCount = recentChanges.size,
            isQuickReturn = isQuickReturn,
            isOscillating = isOscillating,
            uniqueCellsCount = uniqueCells.size,
            timeSinceFirstChange = timeSinceFirst
        )
    }

    // ==================== RICH DESCRIPTION GENERATION ====================

    /**
     * Build a rich, actionable description for the user.
     * This replaces generic messages like "Multiple suspicious indicators detected"
     * with specific, contextual information about what was detected and why it matters.
     */
    private fun buildRichDescription(
        type: AnomalyType,
        analysis: CellularAnomalyAnalysis?,
        contributingFactors: List<String>,
        imsiScore: Int
    ): String {
        val sb = StringBuilder()

        // Start with the user-friendly explanation of this anomaly type
        sb.append(type.userFriendlyExplanation)
        sb.append("\n\n")

        // Add assessment based on IMSI score
        val assessmentLabel = when {
            imsiScore >= 90 -> "CRITICAL THREAT"
            imsiScore >= 70 -> "HIGH THREAT"
            imsiScore >= 50 -> "MODERATE CONCERN"
            imsiScore >= 30 -> "LOW CONCERN"
            else -> "INFORMATIONAL"
        }
        sb.append("Assessment: $assessmentLabel (${imsiScore}% IMSI catcher likelihood)\n\n")

        // List specific contributing factors
        if (contributingFactors.isNotEmpty()) {
            sb.append("Why this was flagged:\n")
            contributingFactors.take(5).forEach { factor ->
                sb.append("  - $factor\n")
            }
            sb.append("\n")
        }

        // Add movement context if available
        analysis?.let { a ->
            if (a.movementType == MovementType.STATIONARY) {
                sb.append("Context: You appear to be stationary. ")
                if (a.cellTrustScore >= 60) {
                    sb.append("The cell you switched to is one you've seen before, which reduces suspicion.\n")
                } else {
                    sb.append("Switching to an unfamiliar cell while not moving is more suspicious than while traveling.\n")
                }
            } else if (a.movementType == MovementType.VEHICLE || a.movementType == MovementType.HIGH_SPEED_VEHICLE) {
                sb.append("Context: You appear to be traveling (${String.format("%.0f", a.speedKmh)} km/h). Cell changes while moving are normal.\n")
            }

            if (a.impossibleSpeed) {
                sb.append("\nWARNING: The calculated movement speed (${String.format("%.0f", a.speedKmh)} km/h) is physically impossible. This could indicate location spoofing or a mobile IMSI catcher.\n")
            }
        }

        // Add recommendations based on threat level
        sb.append("\n")
        sb.append(getRecommendationsForScore(imsiScore))

        return sb.toString()
    }

    /**
     * Get specific recommendations based on the IMSI score.
     */
    private fun getRecommendationsForScore(imsiScore: Int): String {
        return when {
            imsiScore >= 90 -> """
                |IMMEDIATE ACTIONS RECOMMENDED:
                |  1. Enable airplane mode to disconnect from cellular network
                |  2. Use only WiFi with a VPN for communications
                |  3. Leave the area if possible
                |  4. Do NOT make phone calls or send SMS - use encrypted apps only
                |  5. Report this detection to local authorities if you believe you're being targeted
            """.trimMargin()

            imsiScore >= 70 -> """
                |HIGH THREAT - TAKE PRECAUTIONS:
                |  1. Avoid making sensitive phone calls or sending SMS
                |  2. Use end-to-end encrypted messaging apps (Signal, WhatsApp)
                |  3. Consider enabling airplane mode if you need to discuss sensitive topics
                |  4. Note your location for future reference
                |  5. Monitor for additional anomalies in this area
            """.trimMargin()

            imsiScore >= 50 -> """
                |MODERATE CONCERN - MONITOR SITUATION:
                |  1. Be cautious with sensitive communications
                |  2. Prefer encrypted messaging apps over SMS
                |  3. The detection could be a false positive, but stay alert
                |  4. If you see more anomalies, the threat level increases
            """.trimMargin()

            imsiScore >= 30 -> """
                |LOW CONCERN - STAY AWARE:
                |  1. This is likely normal network behavior
                |  2. Continue monitoring - patterns matter more than single events
                |  3. No immediate action required
            """.trimMargin()

            else -> """
                |INFORMATIONAL - NO ACTION NEEDED:
                |  1. This event was logged for pattern analysis
                |  2. By itself, this is not concerning
                |  3. Multiple similar events would warrant investigation
            """.trimMargin()
        }
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
     * Check if there's an IMSI catcher signature in the downgrade pattern.
     * Returns score 0-100 based on real-world IMSI catcher characteristics.
     *
     * SCORING METHODOLOGY (based on documented IMSI catcher behavior):
     *
     * HIGH VALUE INDICATORS (20-30 points each):
     * - Progressive downgrade to 2G (classic StingRay behavior)
     * - Suspicious LAC/TAC values (LAC 1-10 is StingRay signature)
     * - Test network MCC-MNC codes
     * - Suspiciously strong signal (DRTbox characteristic)
     *
     * MEDIUM VALUE INDICATORS (10-20 points each):
     * - Encryption downgrade with signal spike
     * - Unknown tower while stationary
     * - Suspicious cell ID patterns
     * - Unknown carrier MNC for the region
     *
     * LOW VALUE INDICATORS (5-10 points each):
     * - Single stationary cell change
     * - Low cell trust score
     * - Recent network generation change
     */
    private fun calculateImsiCatcherScore(
        analysis: CellularAnomalyAnalysis
    ): Int {
        var score = 0
        val reasons = mutableListOf<String>()

        // ==================== HIGH VALUE INDICATORS ====================

        // Downgrade chain analysis - progressive downgrade is highly suspicious
        // This is the PRIMARY method used by StingRay to enable interception
        val chain = analysis.encryptionDowngradeChain
        if (chain.size >= 2) {
            val hasProgressiveDowngrade = chain.zipWithNext().all { (prev, curr) ->
                val prevGen = when (prev) { "5G" -> 5; "4G" -> 4; "3G" -> 3; "2G" -> 2; else -> 0 }
                val currGen = when (curr) { "5G" -> 5; "4G" -> 4; "3G" -> 3; "2G" -> 2; else -> 0 }
                currGen < prevGen || currGen == prevGen
            }
            if (hasProgressiveDowngrade && chain.last() == "2G") {
                score += 30 // Clear downgrade pattern ending in 2G - CLASSIC STINGRAY
                reasons.add("Progressive downgrade to 2G (StingRay signature)")
            }
        }

        // Ended on 2G - this is where interception happens
        // 2G has no mutual authentication, allowing MITM attacks
        if (analysis.currentEncryption == EncryptionStrength.WEAK ||
            analysis.currentEncryption == EncryptionStrength.NONE) {
            score += 25
            reasons.add("Currently on 2G with weak/no encryption")
        }

        // Suspiciously strong signal - characteristic of DRTbox (airplane-mounted)
        // and close-proximity ground-based IMSI catchers
        if (analysis.currentSignalDbm >= SUSPICIOUSLY_STRONG_SIGNAL_DBM) {
            score += 20
            reasons.add("Suspiciously strong signal (${analysis.currentSignalDbm} dBm) - possible close IMSI catcher")
        }

        // ==================== MEDIUM VALUE INDICATORS ====================

        // Downgrade with signal spike - classic IMSI catcher behavior
        // The fake tower broadcasts stronger to attract phones
        if (analysis.downgradeWithSignalSpike) {
            score += 20
            reasons.add("Encryption downgrade coincided with signal spike")
        }

        // Downgrade with new/unknown tower
        if (analysis.downgradeWithNewTower) {
            score += 15
            reasons.add("Downgrade to unknown cell tower")
        }

        // Impossible movement (potential spoofing or mobile IMSI catcher)
        if (analysis.impossibleSpeed) {
            score += 15
            reasons.add("Impossible movement speed detected")
        }

        // User was stationary - cell changes are more suspicious when not moving
        if (analysis.movementType == MovementType.STATIONARY) {
            score += 10
            reasons.add("Cell changed while stationary")
        }

        // Low cell trust - unknown towers are more suspicious
        if (analysis.cellTrustScore < 30) {
            score += 10
            reasons.add("Unknown/untrusted cell tower")
        }

        // ==================== LOW VALUE INDICATORS ====================

        // Rapid network generation changes
        if (analysis.networkGenerationChange != null) {
            score += 5
        }

        // LAC/TAC changed without cell change (unusual network behavior)
        if (analysis.lacTacChanged) {
            score += 10
            reasons.add("LAC/TAC changed without cell change")
        }

        // Operator changed (could indicate spoofing)
        if (analysis.operatorChanged) {
            score += 10
            reasons.add("Carrier identifier changed")
        }

        return score.coerceIn(0, 100)
    }

    /**
     * Enhanced IMSI catcher score calculation that includes cell snapshot data.
     * This version can check LAC values, cell ID patterns, and MCC-MNC validity.
     */
    private fun calculateEnhancedImsiScore(
        analysis: CellularAnomalyAnalysis,
        current: CellSnapshot,
        previous: CellSnapshot?
    ): Pair<Int, List<String>> {
        var score = calculateImsiCatcherScore(analysis)
        val reasons = mutableListOf<String>()

        // Check for suspicious LAC values (StingRay often uses LAC 1-10)
        current.lac?.let { lac ->
            if (lac in SUSPICIOUS_LAC_VALUES) {
                score += 25
                reasons.add("Suspicious LAC value ($lac) - common in StingRay devices")
            }
        }

        // Check for suspicious TAC values (LTE equivalent)
        current.tac?.let { tac ->
            if (tac in SUSPICIOUS_TAC_VALUES) {
                score += 20
                reasons.add("Suspicious TAC value ($tac) - common in IMSI catchers")
            }
        }

        // Check for suspicious cell ID patterns
        if (isSuspiciousCellIdPattern(current.cellId)) {
            score += 15
            reasons.add("Suspicious cell ID pattern (${current.cellId}) - test/sequential number")
        }

        // Check for unknown MNC in known MCC region (potential fake network)
        val mcc = current.mcc
        val mnc = current.mnc
        if (mcc == "310" && mnc != null && mnc !in KNOWN_US_MNC_CODES_310) {
            score += 20
            reasons.add("Unknown carrier code MCC-MNC: 310-$mnc (not a recognized US carrier)")
        } else if (mcc == "311" && mnc != null && mnc !in KNOWN_US_MNC_CODES_311) {
            score += 20
            reasons.add("Unknown carrier code MCC-MNC: 311-$mnc (not a recognized US carrier)")
        }

        // Check for suspiciously strong signal with unknown tower
        if (current.signalStrength >= SUSPICIOUSLY_STRONG_SIGNAL_DBM && analysis.cellTrustScore < 30) {
            score += 15
            reasons.add("Very strong signal (${current.signalStrength} dBm) from unknown tower")
        }

        return Pair(score.coerceIn(0, 100), reasons)
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
            signalStrength = cellularDbmToSignalStrength(anomaly.signalStrength),
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

    // ==================== Persistence ====================

    /**
     * Load persisted cellular data from the database.
     * Called on initialization to restore state.
     */
    fun loadPersistedData() {
        if (isDataLoaded) return

        // Don't load persisted data in ephemeral mode
        if (ephemeralModeEnabled) {
            Log.d(TAG, "Ephemeral mode enabled - skipping load of persisted cellular data")
            isDataLoaded = true
            return
        }

        persistenceScope.launch {
            try {
                Log.d(TAG, "Loading persisted cellular data...")

                // Load seen cell towers
                val seenTowers = cellularDao.getAllSeenCellTowersSnapshot()
                seenTowers.forEach { entity ->
                    val tower = entityToSeenCellTower(entity)
                    seenCellTowerMap[entity.cellId] = tower
                }
                _seenCellTowers.value = seenCellTowerMap.values.toList()
                    .sortedByDescending { it.lastSeen }

                // Load trusted cells
                val trustedEntities = cellularDao.getAllTrustedCellsSnapshot()
                trustedEntities.forEach { entity ->
                    trustedCells[entity.cellId] = entityToTrustedCellInfo(entity)
                }

                // Load cellular events (most recent 200)
                val events = cellularDao.getRecentCellularEventsSnapshot(maxEventHistory)
                eventHistory.clear()
                eventHistory.addAll(events.map { entityToCellularEvent(it) })
                _cellularEvents.value = eventHistory.toList()

                isDataLoaded = true
                Log.d(TAG, "Loaded ${seenTowers.size} seen towers, ${trustedEntities.size} trusted cells, ${events.size} events")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load persisted cellular data", e)
            }
        }
    }

    /**
     * Save a seen cell tower to the database.
     */
    private fun persistSeenCellTower(tower: SeenCellTower) {
        // Skip persistence in ephemeral mode
        if (ephemeralModeEnabled) return

        persistenceScope.launch {
            try {
                cellularDao.insertSeenCellTower(seenCellTowerToEntity(tower))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist seen cell tower", e)
            }
        }
    }

    /**
     * Save a trusted cell to the database.
     */
    private fun persistTrustedCell(cellId: String, info: TrustedCellInfo) {
        // Skip persistence in ephemeral mode
        if (ephemeralModeEnabled) return

        persistenceScope.launch {
            try {
                cellularDao.insertTrustedCell(trustedCellInfoToEntity(cellId, info))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist trusted cell", e)
            }
        }
    }

    /**
     * Save a cellular event to the database.
     */
    private fun persistCellularEvent(event: CellularEvent) {
        // Skip persistence in ephemeral mode
        if (ephemeralModeEnabled) return

        persistenceScope.launch {
            try {
                cellularDao.insertCellularEvent(cellularEventToEntity(event))
                // Trim old events to keep database size manageable
                val count = cellularDao.getCellularEventCount()
                if (count > maxEventHistory * 2) {
                    cellularDao.trimCellularEvents(maxEventHistory)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist cellular event", e)
            }
        }
    }

    /**
     * Clear all persisted cellular data.
     */
    fun clearPersistedData() {
        persistenceScope.launch {
            try {
                cellularDao.deleteAllSeenCellTowers()
                cellularDao.deleteAllTrustedCells()
                cellularDao.deleteAllCellularEvents()
                Log.d(TAG, "Cleared all persisted cellular data")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear persisted cellular data", e)
            }
        }
    }

    // ==================== Entity Conversions ====================

    private fun seenCellTowerToEntity(tower: SeenCellTower): SeenCellTowerEntity {
        return SeenCellTowerEntity(
            cellId = tower.cellId,
            lac = tower.lac,
            tac = tower.tac,
            mcc = tower.mcc,
            mnc = tower.mnc,
            operator = tower.operator,
            networkType = tower.networkType,
            networkGeneration = tower.networkGeneration,
            firstSeen = tower.firstSeen,
            lastSeen = tower.lastSeen,
            seenCount = tower.seenCount,
            minSignal = tower.minSignal,
            maxSignal = tower.maxSignal,
            lastSignal = tower.lastSignal,
            latitude = tower.latitude,
            longitude = tower.longitude,
            isTrusted = tower.isTrusted
        )
    }

    private fun entityToSeenCellTower(entity: SeenCellTowerEntity): SeenCellTower {
        return SeenCellTower(
            cellId = entity.cellId,
            lac = entity.lac,
            tac = entity.tac,
            mcc = entity.mcc,
            mnc = entity.mnc,
            operator = entity.operator,
            networkType = entity.networkType,
            networkGeneration = entity.networkGeneration,
            firstSeen = entity.firstSeen,
            lastSeen = entity.lastSeen,
            seenCount = entity.seenCount,
            minSignal = entity.minSignal,
            maxSignal = entity.maxSignal,
            lastSignal = entity.lastSignal,
            latitude = entity.latitude,
            longitude = entity.longitude,
            isTrusted = entity.isTrusted
        )
    }

    private fun trustedCellInfoToEntity(cellId: String, info: TrustedCellInfo): TrustedCellEntity {
        val locationsJson = JSONArray().apply {
            info.locations.forEach { (lat, lon) ->
                put(JSONArray().apply {
                    put(lat)
                    put(lon)
                })
            }
        }.toString()

        return TrustedCellEntity(
            cellId = cellId,
            seenCount = info.seenCount,
            firstSeen = info.firstSeen,
            lastSeen = info.lastSeen,
            locationsJson = locationsJson,
            operator = info.operator,
            networkType = info.networkType
        )
    }

    private fun entityToTrustedCellInfo(entity: TrustedCellEntity): TrustedCellInfo {
        val locations = mutableListOf<Pair<Double, Double>>()
        try {
            val jsonArray = JSONArray(entity.locationsJson)
            for (i in 0 until jsonArray.length()) {
                val locArray = jsonArray.getJSONArray(i)
                locations.add(Pair(locArray.getDouble(0), locArray.getDouble(1)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse locations JSON", e)
        }

        return TrustedCellInfo(
            cellId = entity.cellId,
            seenCount = entity.seenCount,
            firstSeen = entity.firstSeen,
            lastSeen = entity.lastSeen,
            locations = locations,
            operator = entity.operator,
            networkType = entity.networkType
        )
    }

    private fun cellularEventToEntity(event: CellularEvent): CellularEventEntity {
        return CellularEventEntity(
            id = event.id,
            timestamp = event.timestamp,
            eventType = event.type.name,
            title = event.title,
            description = event.description,
            cellId = event.cellId,
            networkType = event.networkType,
            signalStrength = event.signalStrength,
            isAnomaly = event.isAnomaly,
            threatLevel = event.threatLevel.name,
            latitude = event.latitude,
            longitude = event.longitude
        )
    }

    private fun entityToCellularEvent(entity: CellularEventEntity): CellularEvent {
        val eventType = try {
            CellularEventType.valueOf(entity.eventType)
        } catch (e: IllegalArgumentException) {
            CellularEventType.MONITORING_STARTED
        }

        val threatLevel = try {
            ThreatLevel.valueOf(entity.threatLevel)
        } catch (e: IllegalArgumentException) {
            ThreatLevel.INFO
        }

        return CellularEvent(
            id = entity.id,
            timestamp = entity.timestamp,
            type = eventType,
            title = entity.title,
            description = entity.description,
            cellId = entity.cellId,
            networkType = entity.networkType,
            signalStrength = entity.signalStrength,
            isAnomaly = entity.isAnomaly,
            threatLevel = threatLevel,
            latitude = entity.latitude,
            longitude = entity.longitude
        )
    }

    /**
     * Cancel persistence operations on cleanup.
     */
    fun cancelPersistence() {
        persistenceScope.cancel()
    }
}

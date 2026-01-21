package com.flockyou.monitoring

import android.Manifest
import android.content.Context
import android.location.GnssStatus
import android.location.GnssMeasurementsEvent
import android.location.GnssMeasurement
import android.location.GnssClock
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.SignalStrength
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.math.abs

/**
 * GnssSatelliteMonitor - Collects raw GNSS satellite data for spoofing/jamming detection
 *
 * Collects:
 * - Satellite positions (azimuth, elevation)
 * - Signal-to-noise ratios (C/N0)
 * - Constellation info (GPS, GLONASS, Galileo, BeiDou, QZSS, SBAS, IRNSS)
 * - Ephemeris/almanac availability
 * - Carrier frequency info
 * - Raw measurements (pseudorange, Doppler, carrier phase)
 *
 * Detects:
 * - GNSS spoofing (inconsistent C/N0, impossible satellite positions)
 * - GNSS jamming (sudden signal loss across all satellites)
 * - Constellation anomalies (unexpected satellite configurations)
 * - Multipath interference
 *
 * ===================================================================================
 * REAL-WORLD GNSS SPOOFING/JAMMING KNOWLEDGE BASE
 * ===================================================================================
 *
 * KNOWN SPOOFING SCENARIOS:
 * -------------------------
 * 1. Russian GPS Spoofing (Kremlin Circle Pattern):
 *    - Documented around Moscow Kremlin since 2017
 *    - Ships in Black Sea report being located at Vnukovo Airport (37km away)
 *    - Creates "circular" spoofing pattern centered on protected location
 *    - GLONASS (Russian system) often unaffected - key detection indicator
 *    - Affects GPS L1 C/A code primarily; military P(Y) code harder to spoof
 *    - Detection: If GPS shows anomaly but GLONASS is consistent, suspect spoofing
 *
 * 2. Iranian GPS Spoofing (RQ-170 Drone Capture, 2011):
 *    - Gradually shifts position to lead aircraft to wrong location
 *    - "Meaconing" technique: record and replay real signals with modified timing
 *    - Position drift is gradual (meters per second) to avoid detection
 *    - Detection: Look for steady position drift in one direction over time
 *
 * 3. Chinese GPS Interference (Shanghai Port):
 *    - Documented interference in Shanghai Huangpu River area
 *    - Affects commercial shipping GPS receivers
 *    - May be inadvertent from nearby military installations
 *    - Detection: Regional pattern affecting multiple vessels simultaneously
 *
 * 4. Trucking/Fleet Spoofing (Privacy-motivated):
 *    - Drivers hiding location from employer tracking systems
 *    - Typically single-frequency, affects GPS L1 only
 *    - Low power, affects only the vehicle with the device
 *    - Detection: Single device affected while others nearby are normal
 *
 * 5. Pokemon GO / Gaming Spoofing:
 *    - Consumer-grade, very weak signal
 *    - Typically software-only (mock location providers)
 *    - Does not affect raw GNSS measurements, only location API
 *    - Detection: Raw GNSS measurements normal, but reported location impossible
 *
 * SPOOFING SIGNATURE DETECTION:
 * -----------------------------
 * - ALL satellites same signal strength = STRONG spoofing indicator
 *   (Real sky: signals vary 10-20 dB based on elevation, atmosphere)
 * - ALL satellites same elevation angle = spoofing
 *   (Real sky: satellites range from horizon to zenith)
 * - Carrier phase discontinuities = meaconing or replay attack
 *   (Replay attacks can't maintain phase coherence)
 * - Navigation message bit errors = possible attack
 *   (Legitimate broadcasts have FEC and parity protection)
 * - C/N0 significantly higher than expected = nearby transmitter
 *   (Space-based signals have max ~55 dB-Hz; stronger suggests terrestrial)
 * - Position drifts steadily in one direction = gradual spoofing attack
 *   (Iranian-style "lead away" attack)
 *
 * JAMMING VS SPOOFING DIFFERENTIATION:
 * ------------------------------------
 * - JAMMING: Total signal loss, no fix possible, AGC increases dramatically
 *   - Noise floor rises across all frequencies
 *   - Receiver reports "no satellites" or very weak signals
 *   - Recovery is immediate when jamming stops
 *
 * - SPOOFING: Fix acquired but position is WRONG
 *   - Signals appear healthy (good C/N0, proper modulation)
 *   - Position is internally consistent but externally wrong
 *   - May see position "jump" when spoofing starts/stops
 *
 * - MEACONING: Delayed replay of real signals (hardest to detect)
 *   - Signals are genuine but time-shifted
 *   - Position error is proportional to delay
 *   - Detection: Compare with authenticated time sources (NTP, Galileo OSNMA)
 *
 * CONSTELLATION-SPECIFIC KNOWLEDGE:
 * ---------------------------------
 * - GPS (USA): Most commonly spoofed; L1 C/A code is publicly documented
 * - GLONASS (Russia): FDMA-based, harder to spoof all frequencies simultaneously
 * - Galileo (EU): Has OSNMA authentication (since 2023) - detect auth failures!
 * - BeiDou (China): Primarily Asia-Pacific coverage; suspicious if strong elsewhere
 * - QZSS (Japan): Japan region only; suspicious if strong signal outside coverage
 * - IRNSS/NavIC (India): India region focused; geostationary component
 *
 * ENVIRONMENTAL FALSE POSITIVE AWARENESS:
 * ---------------------------------------
 * - Urban canyons: Multipath is NORMAL, signals bounce off buildings
 * - Indoors: Weak/no signal is NORMAL, not jamming
 * - Near water: Strong reflections are common (specular multipath)
 * - Mountains: Poor satellite geometry can be legitimate
 * - Dense forest: Signal attenuation of 10-20 dB is expected
 * - Parking garages: Near-total signal loss is normal
 *
 * TECHNICAL BACKGROUND (FOR LLM ANALYSIS):
 * ----------------------------------------
 * - Ephemeris: Precise orbital parameters valid for ~4 hours
 *   - Allows receiver to predict satellite positions
 *   - Spoofing may provide stale ephemeris (detectable)
 *
 * - PRN Codes: Pseudo-random sequences unique to each satellite
 *   - GPS C/A code: 1023 chips, 1ms period
 *   - Spoofing must generate correct PRN for claimed satellite
 *
 * - Multi-constellation: Attacker must spoof ALL systems coherently
 *   - GPS + GLONASS + Galileo = very difficult to spoof together
 *   - Cross-constellation consistency check is powerful defense
 *
 * - RAIM (Receiver Autonomous Integrity Monitoring):
 *   - Requires 5+ satellites to detect single faulty signal
 *   - Requires 6+ satellites to exclude faulty signal
 *   - Modern receivers implement RAIM; check its status
 * ===================================================================================
 */
class GnssSatelliteMonitor(
    private val context: Context,
    private val errorCallback: com.flockyou.service.ScanningService.DetectorCallback? = null
) {

    companion object {
        private const val TAG = "GnssSatelliteMonitor"

        // Spoofing detection thresholds
        const val MIN_SATELLITES_FOR_FIX = 4
        const val GOOD_FIX_SATELLITES = 10  // When fix is this good, suppress low-confidence anomalies
        const val EXCELLENT_FIX_SATELLITES = 20  // Excellent fix - very high confidence in legitimate operation
        const val STRONG_FIX_SATELLITES = 30  // Strong fix - suppress nearly all low-confidence anomalies

        // Signal uniformity - ONLY flag if EXTREMELY uniform (clear spoofing indicator)
        // Normal GNSS has variance due to different elevation angles, atmospheric conditions, etc.
        // Variance of 0.5-3.0 is NORMAL. Only variance < 0.1 suggests spoofing (all signals identical)
        const val SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD = 0.15  // dB-Hz - extremely low variance = spoofing
        const val CN0_UNIFORMITY_WARNING_THRESHOLD = 0.5  // dB-Hz - low variance, only flag with other indicators

        const val MAX_VALID_CN0_DBH = 55.0  // Above this is suspicious
        const val MIN_VALID_CN0_DBH = 10.0  // Below this is noise
        const val JAMMING_CN0_DROP_THRESHOLD = 15.0  // Sudden drop in dB-Hz
        const val SPOOFING_ELEVATION_THRESHOLD = 5.0  // Satellites claiming <5° elevation suspiciously strong

        // Multipath detection - VERY conservative to avoid false positives
        // Multipath is NORMAL in: urban environments, indoors, near water/metal/vehicles
        // Only flag multipath if it's actually degrading position quality
        const val MULTIPATH_SNR_VARIANCE_THRESHOLD = 8.0  // High variance = multipath
        const val MULTIPATH_MIN_RATIO = 0.85f  // >85% of measurements must show multipath AND poor fix
        const val MULTIPATH_SUPPRESS_ABOVE_SATELLITES = 15  // Don't report multipath with this many sats used

        // Jamming detection - require ACTUAL jamming indicators
        // True jamming causes: satellite count drop, signal degradation across ALL satellites, loss of fix
        // 70% jamming likelihood with 32 satellites used is IMPOSSIBLE
        const val JAMMING_MIN_SATELLITE_LOSS = 10  // Must lose at least this many satellites
        const val JAMMING_MIN_SIGNAL_DROP_DB = 10.0  // Signal must drop by at least this much
        const val JAMMING_MAX_SATELLITES_FOR_DETECTION = 8  // Can't claim jamming with more than this many sats

        // Timing thresholds
        const val MAX_CLOCK_DRIFT_NS = 1_000_000L  // 1ms max drift between measurements
        const val HISTORY_SIZE = 100
        const val DEFAULT_ANOMALY_COOLDOWN_MS = 30_000L  // 30 seconds between same anomaly type

        // ==================== REAL-WORLD SPOOFING DETECTION THRESHOLDS ====================

        // Position drift detection (Iranian-style gradual spoofing attack)
        const val POSITION_DRIFT_WINDOW_SIZE = 10  // Number of positions to track
        const val SUSPICIOUS_DRIFT_RATE_M_PER_S = 5.0  // Steady drift > 5 m/s in one direction
        const val DRIFT_DIRECTION_CONSISTENCY_THRESHOLD = 0.8f  // 80% same direction = suspicious

        // Elevation angle uniformity (spoofing indicator - real satellites have varied elevations)
        const val ELEVATION_VARIANCE_SUSPICIOUS_THRESHOLD = 100.0  // degrees^2 - low variance is suspicious
        const val ELEVATION_MEAN_SUSPICIOUS_HIGH = 60.0  // All satellites at high elevation is unusual

        // Cross-constellation consistency thresholds
        const val CROSS_CONSTELLATION_SIGNAL_RANGE_SUSPICIOUS = 2.0  // dB-Hz - all same is suspicious
        const val MIN_CONSTELLATIONS_FOR_CROSS_CHECK = 3

        // Kremlin-style spoofing detection (GPS affected, GLONASS normal)
        const val GPS_GLONASS_DEVIATION_THRESHOLD = 15.0  // dB-Hz difference triggers alert

        // Signal strength anomalies
        const val TERRESTRIAL_SIGNAL_THRESHOLD = 55.0  // dB-Hz - signals stronger suggest terrestrial source
        const val LOW_ELEVATION_HIGH_SIGNAL_CN0 = 40.0  // dB-Hz - low elev satellites shouldn't be this strong

        // Doppler shift consistency (for future raw measurement analysis)
        const val DOPPLER_VARIANCE_SUSPICIOUS_THRESHOLD = 50.0  // Hz^2 - all same Doppler is suspicious

        // Indoor/environment detection for false positive suppression
        const val INDOOR_SIGNAL_THRESHOLD = 25.0  // dB-Hz - below this likely indoors
        const val INDOOR_SUPPRESSION_DURATION_MS = 120_000L  // 2 minutes suppression after indoor detection
        const val URBAN_MULTIPATH_CN0_VARIANCE_THRESHOLD = 8.0  // High variance = multipath environment
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private var coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Configurable timing
    private var anomalyCooldownMs: Long = DEFAULT_ANOMALY_COOLDOWN_MS
    private val mainHandler = Handler(Looper.getMainLooper())

    // Current satellite status
    private val _gnssStatus = MutableStateFlow<GnssEnvironmentStatus?>(null)
    val gnssStatus: StateFlow<GnssEnvironmentStatus?> = _gnssStatus.asStateFlow()

    // Per-satellite info
    private val _satellites = MutableStateFlow<List<SatelliteInfo>>(emptyList())
    val satellites: StateFlow<List<SatelliteInfo>> = _satellites.asStateFlow()

    // Anomalies
    private val _anomalies = MutableStateFlow<List<GnssAnomaly>>(emptyList())
    val anomalies: StateFlow<List<GnssAnomaly>> = _anomalies.asStateFlow()
    private val detectedAnomalies = mutableListOf<GnssAnomaly>()

    // Timeline events
    private val _events = MutableStateFlow<List<GnssEvent>>(emptyList())
    val events: StateFlow<List<GnssEvent>> = _events.asStateFlow()
    private val eventHistory = mutableListOf<GnssEvent>()

    // Raw measurements (when available)
    private val _measurements = MutableStateFlow<GnssMeasurementData?>(null)
    val measurements: StateFlow<GnssMeasurementData?> = _measurements.asStateFlow()

    // Callbacks
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var gnssMeasurementsCallback: GnssMeasurementsEvent.Callback? = null

    // History for anomaly detection
    private val cn0History = mutableListOf<Double>()
    private val satelliteCountHistory = mutableListOf<Int>()
    private var lastClockBiasNs: Long? = null
    private var lastDiscontinuityCount: Int? = null  // Track hardware clock discontinuities

    // Enhanced tracking for enrichments
    private val clockDriftHistory = mutableListOf<Long>()     // Accumulated drift values
    private var cumulativeClockDriftNs: Long = 0L
    private val constellationHistory = mutableListOf<Set<ConstellationType>>()
    private var cn0BaselineMean: Double = 0.0
    private var cn0BaselineStdDev: Double = 0.0
    private var cn0BaselineCalculated: Boolean = false
    private val maxDriftHistorySize = 50
    private val driftJumpThresholdNs = 100_000L  // 100 microseconds

    // ==================== REAL-WORLD SPOOFING PATTERN TRACKING ====================

    // Position drift tracking for gradual spoofing detection (Iranian-style attack)
    private val positionHistory = mutableListOf<PositionSample>()
    private data class PositionSample(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float?
    )

    // Per-constellation signal tracking for Kremlin-style detection
    private val constellationSignalHistory = mutableMapOf<ConstellationType, MutableList<Double>>()

    // Environment context for false positive suppression
    private var lastIndoorDetectionTime: Long = 0L
    private var likelyIndoorEnvironment: Boolean = false
    private var likelyUrbanEnvironment: Boolean = false
    private var lastEnvironmentAssessmentTime: Long = 0L

    // Attack pattern tracking
    private var suspectedAttackType: SuspectedAttackType? = null
    private var attackPatternConfidence: Float = 0f

    /**
     * Real-world attack types based on documented incidents
     */
    enum class SuspectedAttackType(val displayName: String, val description: String) {
        KREMLIN_CIRCLE(
            "Kremlin-Circle Pattern",
            "GPS affected while GLONASS remains normal - typical of Russian military GPS spoofing"
        ),
        GRADUAL_DRIFT(
            "Gradual Position Drift",
            "Position slowly shifting in consistent direction - Iranian-style lead-away attack"
        ),
        SIGNAL_REPLAY(
            "Signal Replay/Meaconing",
            "Signals appear genuine but time-shifted - carrier phase discontinuities detected"
        ),
        UNIFORM_SPOOFING(
            "Uniform Signal Spoofing",
            "All satellites show identical signal strength - single transmitter spoofing"
        ),
        SELECTIVE_JAMMING(
            "Selective Constellation Jamming",
            "Specific constellation(s) degraded while others normal - targeted interference"
        ),
        CONSUMER_SPOOFER(
            "Consumer-Grade Spoofer",
            "Weak spoofing signal - possibly personal privacy device or gaming spoofer"
        )
    }

    // Anomaly rate limiting
    private val lastAnomalyTimes = mutableMapOf<GnssAnomalyType, Long>()

    // Location tracking
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    // Monitoring state
    private var isMonitoring = false

    // ==================== Data Classes ====================

    data class GnssEnvironmentStatus(
        val timestamp: Long = System.currentTimeMillis(),
        val totalSatellites: Int = 0,
        val satellitesUsedInFix: Int = 0,
        val constellationCounts: Map<ConstellationType, Int> = emptyMap(),
        val averageCn0DbHz: Double = 0.0,
        val hasFix: Boolean = false,
        val fixAccuracyMeters: Float? = null,
        val hdop: Float? = null,
        val vdop: Float? = null,
        val pdop: Float? = null,
        val spoofingRiskLevel: SpoofingRiskLevel = SpoofingRiskLevel.UNKNOWN,
        val jammingDetected: Boolean = false,
        val hasRawMeasurements: Boolean = false
    )

    data class SatelliteInfo(
        val svid: Int,
        val constellation: ConstellationType,
        val cn0DbHz: Float,
        val elevationDegrees: Float,
        val azimuthDegrees: Float,
        val hasEphemeris: Boolean,
        val hasAlmanac: Boolean,
        val usedInFix: Boolean,
        val carrierFrequencyHz: Float? = null,
        val basebandCn0DbHz: Float? = null
    )

    data class GnssMeasurementData(
        val timestamp: Long = System.currentTimeMillis(),
        val clockBiasNs: Double? = null,
        val clockDriftNsPerSec: Double? = null,
        val clockDiscontinuityCount: Int? = null,
        val measurementCount: Int = 0,
        val hasPseudorange: Boolean = false,
        val hasCarrierPhase: Boolean = false,
        val hasDoppler: Boolean = false,
        val multipathIndicators: List<Int> = emptyList()
    )

    enum class ConstellationType(val displayName: String, val code: String) {
        GPS("GPS", "G"),
        GLONASS("GLONASS", "R"),
        GALILEO("Galileo", "E"),
        BEIDOU("BeiDou", "C"),
        QZSS("QZSS", "J"),
        SBAS("SBAS", "S"),
        IRNSS("NavIC/IRNSS", "I"),
        UNKNOWN("Unknown", "?")
    }

    enum class SpoofingRiskLevel(val displayName: String) {
        UNKNOWN("Unknown"),
        NONE("None Detected"),
        LOW("Low Risk"),
        MEDIUM("Medium Risk"),
        HIGH("High Risk"),
        CRITICAL("Active Spoofing Likely")
    }

    data class GnssAnomaly(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: GnssAnomalyType,
        val severity: ThreatLevel,
        val confidence: AnomalyConfidence,
        val description: String,
        val technicalDetails: String,
        val affectedConstellations: List<ConstellationType> = emptyList(),
        val contributingFactors: List<String> = emptyList(),
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    enum class GnssAnomalyType(val displayName: String, val baseScore: Int, val emoji: String) {
        SPOOFING_DETECTED("GNSS Spoofing", 90, "🎯"),
        JAMMING_DETECTED("GNSS Jamming", 85, "📵"),
        SIGNAL_UNIFORMITY("Suspicious Signal Uniformity", 70, "📊"),
        IMPOSSIBLE_GEOMETRY("Impossible Satellite Geometry", 80, "🛰️"),
        SUDDEN_SIGNAL_LOSS("Sudden Signal Loss", 65, "📉"),
        CLOCK_ANOMALY("Clock Discontinuity", 60, "⏰"),
        MULTIPATH_SEVERE("Severe Multipath", 40, "🔀"),
        CONSTELLATION_DROPOUT("Constellation Dropout", 50, "❌"),
        CN0_SPIKE("Abnormal Signal Strength", 55, "📈"),
        ELEVATION_ANOMALY("Elevation Angle Anomaly", 65, "📐")
    }

    enum class AnomalyConfidence(val displayName: String, val minFactors: Int) {
        LOW("Low", 1),
        MEDIUM("Medium", 2),
        HIGH("High", 3),
        CRITICAL("Critical", 4)
    }

    data class GnssEvent(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: GnssEventType,
        val title: String,
        val description: String,
        val isAnomaly: Boolean = false,
        val threatLevel: ThreatLevel = ThreatLevel.INFO,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    enum class GnssEventType {
        MONITORING_STARTED,
        MONITORING_STOPPED,
        FIX_ACQUIRED,
        FIX_LOST,
        SATELLITES_CHANGED,
        ANOMALY_DETECTED,
        CONSTELLATION_AVAILABLE,
        CONSTELLATION_LOST,
        MEASUREMENTS_AVAILABLE
    }

    /**
     * Clock drift trend classification
     */
    enum class DriftTrend(val displayName: String) {
        STABLE("Stable"),
        INCREASING("Increasing"),
        DECREASING("Decreasing"),
        ERRATIC("Erratic")
    }

    /**
     * Comprehensive GNSS anomaly analysis with enriched data
     */
    data class GnssAnomalyAnalysis(
        // Constellation Fingerprinting
        val expectedConstellations: Set<ConstellationType>,
        val observedConstellations: Set<ConstellationType>,
        val missingConstellations: Set<ConstellationType>,
        val unexpectedConstellation: Boolean,
        val constellationMatchScore: Int,        // 0-100

        // C/N0 Baseline Analysis
        val historicalCn0Mean: Double,
        val historicalCn0StdDev: Double,
        val currentCn0Mean: Double,
        val cn0DeviationSigmas: Double,          // How many std devs from mean
        val cn0Anomalous: Boolean,
        val cn0TooUniform: Boolean,              // Spoofing indicator
        val cn0Variance: Double,

        // Clock Drift Accumulation
        val cumulativeDriftNs: Long,
        val driftTrend: DriftTrend,
        val driftAnomalous: Boolean,
        val maxDriftInWindowNs: Long,
        val driftJumpCount: Int,                 // Number of large jumps

        // Satellite Geometry Analysis
        val geometryScore: Float,                // 0-1.0, DOP-like
        val elevationDistribution: String,       // "Normal", "Clustered", "Suspicious"
        val azimuthCoverage: Float,              // 0-360 coverage percentage
        val lowElevHighSignalCount: Int,         // Spoofing indicator

        // Signal Anomaly Scoring
        val uniformityScore: Float,              // 0-1.0, lower = more uniform (suspicious)
        val signalSpikeCount: Int,
        val signalDropCount: Int,

        // Composite Spoofing Score
        val spoofingLikelihood: Float,           // 0-100%
        val jammingLikelihood: Float,            // 0-100%
        val spoofingIndicators: List<String>,
        val jammingIndicators: List<String>,

        // Cross-Constellation Validation
        val crossConstellationCount: Int = 0,
        val crossConstellationConsistent: Boolean = true,
        val allConstellationsIdenticalSignals: Boolean = false,
        val crossConstellationSpoofingAdjustment: Float = 0f,

        // False Positive Heuristics
        val falsePositiveLikelihood: Float = 0f, // 0-100%
        val fpIndicators: List<String> = emptyList(),
        val isLikelyNormalOperation: Boolean = false,
        val isLikelyUrbanMultipath: Boolean = false,
        val isLikelyIndoorSignalLoss: Boolean = false
    )

    // ==================== Lifecycle ====================

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun startMonitoring() {
        if (isMonitoring) {
            Log.w(TAG, "Already monitoring")
            return
        }

        Log.i(TAG, "Starting GNSS Satellite Monitor")
        isMonitoring = true

        try {
            registerGnssStatusCallback()
            registerGnssMeasurementsCallback()
            errorCallback?.onDetectorStarted(com.flockyou.service.ScanningService.DetectorHealthStatus.DETECTOR_GNSS)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GNSS monitoring", e)
            errorCallback?.onError(
                com.flockyou.service.ScanningService.DetectorHealthStatus.DETECTOR_GNSS,
                "Failed to register GNSS callbacks: ${e.message}",
                recoverable = true
            )
        }

        addTimelineEvent(
            type = GnssEventType.MONITORING_STARTED,
            title = "GNSS Monitoring Started",
            description = "Collecting satellite data for spoofing/jamming detection"
        )
    }

    fun stopMonitoring() {
        if (!isMonitoring) return

        Log.i(TAG, "Stopping GNSS Satellite Monitor")
        isMonitoring = false

        try {
            unregisterCallbacks()
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering GNSS callbacks", e)
        }

        // Cancel and recreate scope for potential restart
        coroutineScope.cancel()
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        addTimelineEvent(
            type = GnssEventType.MONITORING_STOPPED,
            title = "GNSS Monitoring Stopped",
            description = "Satellite data collection ended"
        )

        errorCallback?.onDetectorStopped(com.flockyou.service.ScanningService.DetectorHealthStatus.DETECTOR_GNSS)
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    /**
     * Update scan timing configuration.
     * @param intervalSeconds Cooldown time between same anomaly type reports (1-30 seconds)
     */
    fun updateScanTiming(intervalSeconds: Int) {
        anomalyCooldownMs = (intervalSeconds.coerceIn(1, 30) * 1000L)
        Log.d(TAG, "Updated anomaly cooldown: ${anomalyCooldownMs}ms")
    }

    // ==================== GNSS Status Callback ====================

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun registerGnssStatusCallback() {
        gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onStarted() {
                Log.d(TAG, "GNSS engine started")
            }

            override fun onStopped() {
                Log.d(TAG, "GNSS engine stopped")
            }

            override fun onFirstFix(ttffMillis: Int) {
                Log.d(TAG, "First fix in ${ttffMillis}ms")
                addTimelineEvent(
                    type = GnssEventType.FIX_ACQUIRED,
                    title = "GNSS Fix Acquired",
                    description = "Time to first fix: ${ttffMillis}ms"
                )
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                processGnssStatus(status)
            }
        }

        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback!!, mainHandler)
            Log.i(TAG, "GNSS status callback registered")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for GNSS status callback", e)
            gnssStatusCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register GNSS status callback", e)
            gnssStatusCallback = null
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun registerGnssMeasurementsCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                processGnssMeasurements(event)
            }

            override fun onStatusChanged(status: Int) {
                val statusStr = when (status) {
                    GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED -> "NOT_SUPPORTED"
                    GnssMeasurementsEvent.Callback.STATUS_READY -> "READY"
                    GnssMeasurementsEvent.Callback.STATUS_LOCATION_DISABLED -> "LOCATION_DISABLED"
                    else -> "UNKNOWN($status)"
                }
                Log.d(TAG, "GNSS measurements status: $statusStr")

                if (status == GnssMeasurementsEvent.Callback.STATUS_READY) {
                    addTimelineEvent(
                        type = GnssEventType.MEASUREMENTS_AVAILABLE,
                        title = "Raw Measurements Available",
                        description = "Device supports GNSS raw measurements"
                    )
                }
            }
        }

        try {
            val registered = locationManager.registerGnssMeasurementsCallback(
                gnssMeasurementsCallback!!,
                mainHandler
            )
            Log.i(TAG, "GNSS measurements callback registered: $registered")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for GNSS measurements callback", e)
            gnssMeasurementsCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register GNSS measurements callback", e)
            gnssMeasurementsCallback = null
        }
    }

    private fun unregisterCallbacks() {
        gnssStatusCallback?.let {
            locationManager.unregisterGnssStatusCallback(it)
            gnssStatusCallback = null
        }
        gnssMeasurementsCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.unregisterGnssMeasurementsCallback(it)
            }
            gnssMeasurementsCallback = null
        }
    }

    // ==================== Data Processing ====================

    private fun processGnssStatus(status: GnssStatus) {
        val satelliteList = mutableListOf<SatelliteInfo>()
        val constellationCounts = mutableMapOf<ConstellationType, Int>()
        var totalCn0 = 0.0
        var usedInFix = 0

        for (i in 0 until status.satelliteCount) {
            val constellation = mapConstellationType(status.getConstellationType(i))
            val cn0 = status.getCn0DbHz(i)

            val satInfo = SatelliteInfo(
                svid = status.getSvid(i),
                constellation = constellation,
                cn0DbHz = cn0,
                elevationDegrees = status.getElevationDegrees(i),
                azimuthDegrees = status.getAzimuthDegrees(i),
                hasEphemeris = status.hasEphemerisData(i),
                hasAlmanac = status.hasAlmanacData(i),
                usedInFix = status.usedInFix(i),
                carrierFrequencyHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null,
                basebandCn0DbHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    status.hasBasebandCn0DbHz(i)) status.getBasebandCn0DbHz(i) else null
            )

            satelliteList.add(satInfo)
            constellationCounts[constellation] = (constellationCounts[constellation] ?: 0) + 1
            totalCn0 += cn0
            if (status.usedInFix(i)) usedInFix++
        }

        val avgCn0 = if (satelliteList.isNotEmpty()) totalCn0 / satelliteList.size else 0.0

        // Update history for anomaly detection
        cn0History.add(avgCn0)
        if (cn0History.size > HISTORY_SIZE) cn0History.removeAt(0)
        satelliteCountHistory.add(status.satelliteCount)
        if (satelliteCountHistory.size > HISTORY_SIZE) satelliteCountHistory.removeAt(0)

        // Analyze for anomalies
        val spoofingRisk = analyzeSpoofingRisk(satelliteList, avgCn0)
        val jammingDetected = analyzeJamming(satelliteList, avgCn0)

        val envStatus = GnssEnvironmentStatus(
            totalSatellites = status.satelliteCount,
            satellitesUsedInFix = usedInFix,
            constellationCounts = constellationCounts,
            averageCn0DbHz = avgCn0,
            hasFix = usedInFix >= MIN_SATELLITES_FOR_FIX,
            spoofingRiskLevel = spoofingRisk,
            jammingDetected = jammingDetected,
            hasRawMeasurements = gnssMeasurementsCallback != null
        )

        _satellites.value = satelliteList
        _gnssStatus.value = envStatus

        // Run anomaly detection
        runAnomalyDetection(satelliteList, envStatus)

        // Report successful scan
        errorCallback?.onScanSuccess(com.flockyou.service.ScanningService.DetectorHealthStatus.DETECTOR_GNSS)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun processGnssMeasurements(event: GnssMeasurementsEvent) {
        val clock = event.clock
        val measurements = event.measurements

        var hasPseudorange = false
        var hasCarrierPhase = false
        var hasDoppler = false
        val multipathIndicators = mutableListOf<Int>()

        for (m in measurements) {
            val state = m.state
            if (state and GnssMeasurement.STATE_CODE_LOCK != 0) hasPseudorange = true
            if (state and GnssMeasurement.STATE_TOW_DECODED != 0) hasDoppler = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (m.accumulatedDeltaRangeState and GnssMeasurement.ADR_STATE_VALID != 0) {
                    hasCarrierPhase = true
                }
            }
            multipathIndicators.add(m.multipathIndicator)
        }

        // Track clock drift accumulation for enriched analysis
        val currentBias = clock.biasNanos.toLong()
        trackClockDriftAccumulation(currentBias)

        // Check for immediate large clock anomalies
        if (clockDriftHistory.isNotEmpty()) {
            val lastDrift = clockDriftHistory.last()
            if (abs(lastDrift) > MAX_CLOCK_DRIFT_NS) {
                val driftTrend = analyzeDriftTrend()
                reportAnomaly(
                    type = GnssAnomalyType.CLOCK_ANOMALY,
                    description = "Large clock discontinuity detected - trend: ${driftTrend.displayName}",
                    technicalDetails = "Clock drift: ${abs(lastDrift)}ns (threshold: ${MAX_CLOCK_DRIFT_NS}ns)\n" +
                        "Cumulative drift: ${cumulativeClockDriftNs / 1_000_000}ms\n" +
                        "Jump count: ${countDriftJumps()}",
                    confidence = if (driftTrend == DriftTrend.ERRATIC) AnomalyConfidence.HIGH else AnomalyConfidence.MEDIUM,
                    contributingFactors = listOf(
                        "Clock bias jumped ${abs(lastDrift) / 1_000_000}ms",
                        "Drift trend: ${driftTrend.displayName}",
                        "Total jumps: ${countDriftJumps()}"
                    )
                )
            }
        }

        val measurementData = GnssMeasurementData(
            clockBiasNs = clock.biasNanos,
            clockDriftNsPerSec = clock.driftNanosPerSecond,
            clockDiscontinuityCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                clock.hardwareClockDiscontinuityCount else null,
            measurementCount = measurements.size,
            hasPseudorange = hasPseudorange,
            hasCarrierPhase = hasCarrierPhase,
            hasDoppler = hasDoppler,
            multipathIndicators = multipathIndicators
        )

        _measurements.value = measurementData

        // Check for severe multipath - but suppress in most cases
        // IMPORTANT: Multipath is NORMAL and EXPECTED in:
        // - Urban environments (buildings reflect signals)
        // - Indoor locations (walls, ceilings, floors)
        // - Near water, vehicles, or metal structures
        // - Areas with trees or vegetation
        //
        // Multipath should ONLY be flagged as "severe" if:
        // 1. It's actually degrading position accuracy (low satellite count in fix)
        // 2. It affects multiple constellations uniformly (suggests attack vs. environment)
        // 3. The multipath pattern is inconsistent with the environment
        val severeMultipath = multipathIndicators.count {
            it == GnssMeasurement.MULTIPATH_INDICATOR_DETECTED
        }
        val multipathRatio = if (measurements.isNotEmpty()) severeMultipath.toFloat() / measurements.size else 0f
        val currentStatus = _gnssStatus.value

        // Determine fix quality - the more satellites used, the less concerning multipath is
        val satellitesUsedInFix = currentStatus?.satellitesUsedInFix ?: 0
        val hasFix = currentStatus?.hasFix == true
        val hasGoodFix = satellitesUsedInFix >= GOOD_FIX_SATELLITES
        val hasExcellentFix = satellitesUsedInFix >= EXCELLENT_FIX_SATELLITES
        val hasStrongFix = satellitesUsedInFix >= STRONG_FIX_SATELLITES

        // Check if multipath is affecting position quality
        // With many satellites (15+), multipath doesn't matter - receiver can exclude bad signals
        val multipathAffectingQuality = !hasFix ||
            (satellitesUsedInFix < MULTIPATH_SUPPRESS_ABOVE_SATELLITES && !hasGoodFix)

        // Analyze multipath pattern across constellations
        val constellationMultipathCounts = mutableMapOf<ConstellationType, Pair<Int, Int>>() // (multipath, total)
        val currentSatellites = _satellites.value
        measurements.forEachIndexed { index, m ->
            val svid = m.svid
            val constellation = mapConstellationType(m.constellationType)
            val current = constellationMultipathCounts.getOrDefault(constellation, Pair(0, 0))
            val hasMultipath = multipathIndicators.getOrNull(index) == GnssMeasurement.MULTIPATH_INDICATOR_DETECTED
            constellationMultipathCounts[constellation] = Pair(
                current.first + if (hasMultipath) 1 else 0,
                current.second + 1
            )
        }

        // Check if multipath affects ALL constellations similarly (suggests attack)
        // vs. varying by constellation (suggests natural environment)
        val constellationMultipathRatios = constellationMultipathCounts
            .filter { it.value.second >= 3 }  // Need at least 3 satellites per constellation
            .mapValues { it.value.first.toFloat() / it.value.second }

        val allConstellationsAffectedSimilarly = if (constellationMultipathRatios.size >= 2) {
            val ratios = constellationMultipathRatios.values.toList()
            val minRatio = ratios.minOrNull() ?: 0f
            val maxRatio = ratios.maxOrNull() ?: 0f
            // If all constellations have similar multipath (within 20%), it's suspicious
            maxRatio - minRatio < 0.2f && minRatio > 0.7f
        } else false

        // Build detailed multipath analysis for description
        val affectedConstellationDetails = constellationMultipathCounts
            .filter { it.value.first > 0 }
            .map { "${it.key.code}: ${it.value.first}/${it.value.second}" }
            .joinToString(", ")

        // Only report multipath if ALL these conditions are met:
        // 1. Extremely high multipath ratio (>85% of ALL measurements)
        // 2. We have enough satellites to be meaningful (>4)
        // 3. Multipath is actually affecting position quality (NOT having a good fix)
        // 4. Satellites used in fix is low enough to be concerning
        // 5. Pattern suggests attack (all constellations affected similarly) OR no fix at all
        val shouldReportMultipath = multipathRatio > MULTIPATH_MIN_RATIO &&
            measurements.size >= MIN_SATELLITES_FOR_FIX &&
            multipathAffectingQuality &&
            !hasStrongFix &&  // Never report with 30+ satellites
            (allConstellationsAffectedSimilarly || !hasFix)

        if (shouldReportMultipath) {
            val environmentContext = when {
                hasExcellentFix -> "Note: Good fix quality suggests normal urban multipath"
                hasGoodFix -> "Note: Moderate fix quality, multipath impact limited"
                hasFix -> "Multipath may be affecting position accuracy"
                else -> "Multipath preventing position fix - move to open sky area"
            }

            val userAction = when {
                !hasFix -> "Move to an open sky area away from buildings and obstacles"
                satellitesUsedInFix < MIN_SATELLITES_FOR_FIX -> "Position accuracy degraded - consider moving to open area"
                else -> "Position fix maintained despite multipath - no action needed"
            }

            reportAnomaly(
                type = GnssAnomalyType.MULTIPATH_SEVERE,
                description = if (!hasFix) {
                    "Multipath interference preventing position fix"
                } else if (allConstellationsAffectedSimilarly) {
                    "Unusual multipath pattern - all constellations affected uniformly"
                } else {
                    "Elevated multipath interference detected"
                },
                technicalDetails = buildString {
                    appendLine("Multipath: $severeMultipath/${measurements.size} signals (${String.format("%.0f", multipathRatio * 100)}%)")
                    appendLine("Satellites used in fix: $satellitesUsedInFix")
                    appendLine("By constellation: $affectedConstellationDetails")
                    appendLine("All constellations similar: $allConstellationsAffectedSimilarly")
                    appendLine()
                    appendLine(environmentContext)
                    appendLine("Recommendation: $userAction")
                },
                confidence = when {
                    !hasFix && allConstellationsAffectedSimilarly -> AnomalyConfidence.MEDIUM
                    !hasFix -> AnomalyConfidence.LOW
                    allConstellationsAffectedSimilarly -> AnomalyConfidence.LOW
                    else -> AnomalyConfidence.LOW
                },
                contributingFactors = listOfNotNull(
                    if (!hasFix) "No position fix available" else null,
                    if (allConstellationsAffectedSimilarly) "All constellations affected uniformly (unusual)" else null,
                    "Likely environment: urban canyon, indoor, or near reflective surfaces",
                    "Recommendation: $userAction"
                )
            )
        }
    }

    // ==================== Anomaly Detection ====================

    private fun analyzeSpoofingRisk(satellites: List<SatelliteInfo>, avgCn0: Double): SpoofingRiskLevel {
        if (satellites.isEmpty()) return SpoofingRiskLevel.UNKNOWN

        var riskFactors = 0

        // Check 1: Too uniform C/N0 values (spoofing signature)
        val cn0Values = satellites.map { it.cn0DbHz.toDouble() }
        val cn0Variance = calculateVariance(cn0Values)
        if (cn0Variance < SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD && satellites.size >= MIN_SATELLITES_FOR_FIX) {
            riskFactors += 2
        }

        // Check 2: Abnormally high C/N0 values
        val highCn0Count = satellites.count { it.cn0DbHz > MAX_VALID_CN0_DBH }
        if (highCn0Count > 0) riskFactors++

        // Check 3: Low elevation satellites with high signal strength
        val suspiciousElevation = satellites.count {
            it.elevationDegrees < SPOOFING_ELEVATION_THRESHOLD && it.cn0DbHz > 40
        }
        if (suspiciousElevation > 2) riskFactors += 2

        // Check 4: All satellites from single constellation (unusual)
        val constellations = satellites.map { it.constellation }.distinct()
        if (constellations.size == 1 && satellites.size >= 6) riskFactors++

        // Check 5: No satellites have ephemeris (unusual for a good fix)
        val withEphemeris = satellites.count { it.hasEphemeris }
        if (withEphemeris == 0 && satellites.size >= MIN_SATELLITES_FOR_FIX) riskFactors++

        return when {
            riskFactors >= 5 -> SpoofingRiskLevel.CRITICAL
            riskFactors >= 4 -> SpoofingRiskLevel.HIGH
            riskFactors >= 3 -> SpoofingRiskLevel.MEDIUM
            riskFactors >= 1 -> SpoofingRiskLevel.LOW
            else -> SpoofingRiskLevel.NONE
        }
    }

    /**
     * Analyze for GNSS jamming indicators.
     *
     * TRUE jamming characteristics:
     * 1. Satellite count drops DRAMATICALLY (not just slightly)
     * 2. Signal strength degrades across ALL satellites simultaneously
     * 3. Loss of position fix
     * 4. Cannot have 30+ satellites and claim jamming - that's impossible
     *
     * FALSE POSITIVE indicators (do NOT flag as jamming):
     * - Many satellites visible (30+) with good signals
     * - Good position fix maintained
     * - Only one constellation affected (likely just bad geometry)
     * - Minor signal fluctuations
     */
    private fun analyzeJamming(satellites: List<SatelliteInfo>, avgCn0: Double): Boolean {
        if (cn0History.size < 5) return false

        // CRITICAL CHECK: Cannot claim jamming if we have many satellites
        // True jamming would prevent satellite acquisition entirely
        val currentSatelliteCount = satellites.size
        val satellitesUsedInFix = satellites.count { it.usedInFix }

        if (currentSatelliteCount > JAMMING_MAX_SATELLITES_FOR_DETECTION) {
            // Having 8+ visible satellites is incompatible with jamming
            return false
        }

        if (satellitesUsedInFix >= MIN_SATELLITES_FOR_FIX) {
            // If we can maintain a fix, we're not being jammed
            return false
        }

        // Check for sudden SIGNIFICANT drop in signal strength (not minor fluctuation)
        val recentAvg = cn0History.takeLast(3).average()
        val previousList = cn0History.dropLast(3).takeLast(5)

        if (previousList.isEmpty()) return false
        val previousAvg = previousList.average()

        val signalDropDb = previousAvg - recentAvg
        val hasSignificantSignalDrop = signalDropDb > JAMMING_MIN_SIGNAL_DROP_DB

        // Check for sudden DRAMATIC loss of satellites (not just a few)
        var hasDramaticSatelliteLoss = false
        if (satelliteCountHistory.size >= 8) {
            val recentCount = satelliteCountHistory.takeLast(3).average()
            val previousCountList = satelliteCountHistory.dropLast(3).takeLast(5)

            if (previousCountList.isNotEmpty()) {
                val previousCount = previousCountList.average()
                val satelliteLoss = previousCount - recentCount

                // Must lose at least JAMMING_MIN_SATELLITE_LOSS satellites
                hasDramaticSatelliteLoss = satelliteLoss >= JAMMING_MIN_SATELLITE_LOSS &&
                    recentCount < MIN_SATELLITES_FOR_FIX
            }
        }

        // Check if signal drop affects ALL constellations (true jamming)
        // vs. just one constellation (likely obstruction or geometry)
        val constellationSignals = satellites.groupBy { it.constellation }
            .mapValues { it.value.map { sat -> sat.cn0DbHz.toDouble() }.average() }

        val allConstellationsWeakSignal = constellationSignals.isNotEmpty() &&
            constellationSignals.values.all { it < 25.0 }  // All constellations below 25 dB-Hz

        // Only report jamming if we have MULTIPLE strong indicators
        val jammingIndicatorCount = listOf(
            hasSignificantSignalDrop,
            hasDramaticSatelliteLoss,
            allConstellationsWeakSignal,
            currentSatelliteCount < MIN_SATELLITES_FOR_FIX
        ).count { it }

        // Need at least 3 indicators for jamming determination
        return jammingIndicatorCount >= 3
    }

    private fun runAnomalyDetection(satellites: List<SatelliteInfo>, status: GnssEnvironmentStatus) {
        // Build comprehensive enriched analysis
        val analysis = buildGnssAnalysis(satellites, status)

        // Spoofing detection with enriched analysis
        if (status.spoofingRiskLevel == SpoofingRiskLevel.HIGH ||
            status.spoofingRiskLevel == SpoofingRiskLevel.CRITICAL ||
            analysis.spoofingLikelihood >= 50) {

            val enrichedFactors = buildGnssContributingFactors(analysis)

            val confidence = when {
                analysis.spoofingLikelihood >= 80 -> AnomalyConfidence.CRITICAL
                status.spoofingRiskLevel == SpoofingRiskLevel.CRITICAL -> AnomalyConfidence.CRITICAL
                analysis.spoofingLikelihood >= 60 -> AnomalyConfidence.HIGH
                status.spoofingRiskLevel == SpoofingRiskLevel.HIGH -> AnomalyConfidence.HIGH
                analysis.spoofingLikelihood >= 40 -> AnomalyConfidence.MEDIUM
                else -> AnomalyConfidence.LOW
            }

            reportAnomaly(
                type = GnssAnomalyType.SPOOFING_DETECTED,
                description = "GNSS spoofing indicators - likelihood: ${String.format("%.0f", analysis.spoofingLikelihood)}%",
                technicalDetails = buildGnssTechnicalDetails(analysis),
                confidence = confidence,
                contributingFactors = enrichedFactors,
                affectedConstellations = satellites.map { it.constellation }.distinct()
            )
        }

        // Jamming detection with enriched analysis
        // CRITICAL: Do NOT report jamming if we have many satellites with good signals
        // True jamming would prevent satellite acquisition
        val canReportJamming = status.totalSatellites <= JAMMING_MAX_SATELLITES_FOR_DETECTION &&
            status.satellitesUsedInFix < GOOD_FIX_SATELLITES &&
            !(status.hasFix && status.averageCn0DbHz > 30.0)

        if (canReportJamming && (status.jammingDetected || analysis.jammingLikelihood >= 50)) {
            val enrichedFactors = buildGnssContributingFactors(analysis)

            val confidence = when {
                analysis.jammingLikelihood >= 80 && !status.hasFix -> AnomalyConfidence.CRITICAL
                analysis.jammingLikelihood >= 60 && status.totalSatellites < MIN_SATELLITES_FOR_FIX -> AnomalyConfidence.HIGH
                analysis.jammingLikelihood >= 40 -> AnomalyConfidence.MEDIUM
                else -> AnomalyConfidence.LOW
            }

            val jammingDescription = buildString {
                append("GNSS jamming indicators detected")
                if (!status.hasFix) {
                    append(" - position fix lost")
                }
                if (status.totalSatellites < MIN_SATELLITES_FOR_FIX) {
                    append(" - satellite visibility degraded")
                }
                append(" (likelihood: ${String.format("%.0f", analysis.jammingLikelihood)}%)")
            }

            reportAnomaly(
                type = GnssAnomalyType.JAMMING_DETECTED,
                description = jammingDescription,
                technicalDetails = buildString {
                    appendLine(buildGnssTechnicalDetails(analysis))
                    appendLine()
                    appendLine("=== Jamming Analysis ===")
                    appendLine("Visible satellites: ${status.totalSatellites}")
                    appendLine("Satellites used in fix: ${status.satellitesUsedInFix}")
                    appendLine("Has position fix: ${status.hasFix}")
                    appendLine("Average signal strength: ${String.format("%.1f", status.averageCn0DbHz)} dB-Hz")
                    if (satelliteCountHistory.size >= 5) {
                        val recentCount = satelliteCountHistory.takeLast(3).average()
                        val previousCount = satelliteCountHistory.dropLast(3).takeLast(3).let {
                            if (it.isEmpty()) 0.0 else it.average()
                        }
                        appendLine("Recent satellite trend: ${String.format("%.1f", previousCount)} -> ${String.format("%.1f", recentCount)}")
                    }
                },
                confidence = confidence,
                contributingFactors = enrichedFactors + listOfNotNull(
                    if (!status.hasFix) "Position fix lost" else null,
                    if (status.totalSatellites < MIN_SATELLITES_FOR_FIX) "Very few satellites visible (${status.totalSatellites})" else null,
                    "Recommendation: Move to open sky area if possible"
                )
            )
        }

        // Signal uniformity anomaly (spoofing indicator) - enriched
        //
        // IMPORTANT: Signal variance explained:
        // - Real GNSS signals have variance due to: different elevation angles, atmospheric conditions,
        //   multipath, different satellite transmit powers, antenna gain patterns, etc.
        // - Variance of 0.5-5.0 is NORMAL and EXPECTED
        // - Only variance < 0.15 is truly suspicious (indicates all signals are artificially identical)
        // - Variance < 0.5 is noteworthy only if OTHER spoofing indicators are present
        //
        // A variance of 0.53 (as seen in debug data) is COMPLETELY NORMAL and should NOT be flagged
        val hasStrongFix = status.satellitesUsedInFix >= STRONG_FIX_SATELLITES  // 30+ satellites
        val hasExcellentFix = status.satellitesUsedInFix >= EXCELLENT_FIX_SATELLITES  // 20+ satellites
        val hasGoodFix = status.satellitesUsedInFix >= GOOD_FIX_SATELLITES  // 10+ satellites

        // Only flag uniformity if variance is EXTREMELY low (clear spoofing indicator)
        val isExtremelyUniform = analysis.cn0Variance < SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD  // < 0.15
        val isSomewhatUniform = analysis.cn0Variance < CN0_UNIFORMITY_WARNING_THRESHOLD  // < 0.5

        // Count other spoofing indicators (excluding uniformity-related ones)
        val otherSpoofingIndicators = analysis.spoofingIndicators.filter {
            !it.contains("uniformity", ignoreCase = true) && !it.contains("variance", ignoreCase = true)
        }
        val hasStrongSpoofingIndicators = otherSpoofingIndicators.size >= 2 ||
            analysis.lowElevHighSignalCount > 2 ||
            status.spoofingRiskLevel == SpoofingRiskLevel.HIGH ||
            status.spoofingRiskLevel == SpoofingRiskLevel.CRITICAL

        // Decision matrix for reporting signal uniformity:
        // - Extremely uniform (< 0.15) AND not good fix -> Report with MEDIUM confidence
        // - Extremely uniform (< 0.15) AND good fix -> Only report if other strong indicators
        // - Somewhat uniform (< 0.5) -> Only report if multiple other strong spoofing indicators
        // - Normal variance (>= 0.5) -> NEVER report (this is the debug data case: 0.53)
        val shouldReportUniformity = when {
            !isExtremelyUniform && !isSomewhatUniform -> false  // Normal variance - don't report
            hasStrongFix -> false  // 30+ satellites - don't report uniformity at all
            isExtremelyUniform && !hasGoodFix -> true  // Very suspicious
            isExtremelyUniform && hasStrongSpoofingIndicators -> true  // Multiple indicators
            isSomewhatUniform && !hasExcellentFix && hasStrongSpoofingIndicators -> true  // Combined evidence
            else -> false
        }

        if (shouldReportUniformity && !status.jammingDetected) {
            val uniformityContext = when {
                isExtremelyUniform -> "All satellite signals have nearly identical strength - strong spoofing indicator"
                isSomewhatUniform -> "Signal variance is low - combined with other indicators, may suggest spoofing"
                else -> "Signal uniformity detected"
            }

            reportAnomaly(
                type = GnssAnomalyType.SIGNAL_UNIFORMITY,
                description = if (isExtremelyUniform) {
                    "Signal uniformity highly suspicious - variance: ${String.format("%.3f", analysis.cn0Variance)} dB-Hz"
                } else {
                    "Signal uniformity elevated - variance: ${String.format("%.2f", analysis.cn0Variance)} dB-Hz (corroborating indicator)"
                },
                technicalDetails = buildString {
                    appendLine(buildGnssTechnicalDetails(analysis))
                    appendLine()
                    appendLine("=== Signal Uniformity Analysis ===")
                    appendLine("Signal variance: ${String.format("%.3f", analysis.cn0Variance)} dB-Hz")
                    appendLine("Normal range: 0.5-5.0 dB-Hz")
                    appendLine("Suspicious threshold: < ${SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD} dB-Hz")
                    appendLine()
                    appendLine(uniformityContext)
                    if (otherSpoofingIndicators.isNotEmpty()) {
                        appendLine()
                        appendLine("Corroborating indicators: ${otherSpoofingIndicators.joinToString(", ")}")
                    }
                },
                confidence = if (isExtremelyUniform) AnomalyConfidence.MEDIUM else AnomalyConfidence.LOW,
                contributingFactors = listOf(uniformityContext) + otherSpoofingIndicators
            )
        }

        // Clock drift anomaly - enriched
        if (analysis.driftAnomalous) {
            reportAnomaly(
                type = GnssAnomalyType.CLOCK_ANOMALY,
                description = "Clock drift anomaly - ${analysis.driftTrend.displayName}, ${analysis.driftJumpCount} jumps",
                technicalDetails = buildGnssTechnicalDetails(analysis),
                confidence = if (analysis.driftJumpCount > 5) AnomalyConfidence.HIGH else AnomalyConfidence.MEDIUM,
                contributingFactors = listOf(
                    "Drift trend: ${analysis.driftTrend.displayName}",
                    "Jump count: ${analysis.driftJumpCount}",
                    "Max drift: ${analysis.maxDriftInWindowNs / 1000} µs"
                )
            )
        }

        // Elevation anomaly (low elevation + high signal = spoofing)
        if (analysis.lowElevHighSignalCount > 2) {
            reportAnomaly(
                type = GnssAnomalyType.ELEVATION_ANOMALY,
                description = "${analysis.lowElevHighSignalCount} low-elevation satellites with suspiciously high signal",
                technicalDetails = buildGnssTechnicalDetails(analysis),
                confidence = AnomalyConfidence.HIGH,
                contributingFactors = listOf(
                    "Low elevation (<5°) satellites with C/N0 > 40 dB-Hz",
                    "This pattern is physically implausible without spoofing",
                    "Geometry score: ${String.format("%.0f", analysis.geometryScore * 100)}%"
                )
            )
        }

        // Constellation dropout - enriched
        val currentConstellations = status.constellationCounts.keys
        if (currentConstellations.size == 1 && satellites.size >= 6) {
            reportAnomaly(
                type = GnssAnomalyType.CONSTELLATION_DROPOUT,
                description = "Only ${currentConstellations.first().displayName} visible (missing: ${analysis.missingConstellations.joinToString { it.code }})",
                technicalDetails = buildGnssTechnicalDetails(analysis),
                confidence = AnomalyConfidence.LOW,
                contributingFactors = listOf(
                    "Constellation match score: ${analysis.constellationMatchScore}%",
                    "Expected: ${analysis.expectedConstellations.joinToString { it.code }}",
                    "Single constellation unusual in open sky"
                )
            )
        }
    }

    private fun reportAnomaly(
        type: GnssAnomalyType,
        description: String,
        technicalDetails: String,
        confidence: AnomalyConfidence,
        contributingFactors: List<String>,
        affectedConstellations: List<ConstellationType> = emptyList()
    ) {
        // Rate limiting
        val now = System.currentTimeMillis()
        val lastTime = lastAnomalyTimes[type] ?: 0L
        if (now - lastTime < anomalyCooldownMs) return
        lastAnomalyTimes[type] = now

        val severity = when (confidence) {
            AnomalyConfidence.CRITICAL -> ThreatLevel.CRITICAL
            AnomalyConfidence.HIGH -> ThreatLevel.HIGH
            AnomalyConfidence.MEDIUM -> ThreatLevel.MEDIUM
            AnomalyConfidence.LOW -> ThreatLevel.LOW
        }

        val anomaly = GnssAnomaly(
            type = type,
            severity = severity,
            confidence = confidence,
            description = description,
            technicalDetails = technicalDetails,
            affectedConstellations = affectedConstellations,
            contributingFactors = contributingFactors,
            latitude = currentLatitude,
            longitude = currentLongitude
        )

        detectedAnomalies.add(0, anomaly)
        if (detectedAnomalies.size > HISTORY_SIZE) {
            detectedAnomalies.removeAt(detectedAnomalies.size - 1)
        }
        _anomalies.value = detectedAnomalies.toList()

        addTimelineEvent(
            type = GnssEventType.ANOMALY_DETECTED,
            title = "${type.emoji} ${type.displayName}",
            description = description,
            isAnomaly = true,
            threatLevel = severity
        )

        Log.w(TAG, "GNSS Anomaly: ${type.displayName} - $description")
    }

    // ==================== Utility ====================

    private fun mapConstellationType(type: Int): ConstellationType {
        return when (type) {
            GnssStatus.CONSTELLATION_GPS -> ConstellationType.GPS
            GnssStatus.CONSTELLATION_GLONASS -> ConstellationType.GLONASS
            GnssStatus.CONSTELLATION_GALILEO -> ConstellationType.GALILEO
            GnssStatus.CONSTELLATION_BEIDOU -> ConstellationType.BEIDOU
            GnssStatus.CONSTELLATION_QZSS -> ConstellationType.QZSS
            GnssStatus.CONSTELLATION_SBAS -> ConstellationType.SBAS
            GnssStatus.CONSTELLATION_IRNSS -> ConstellationType.IRNSS
            else -> ConstellationType.UNKNOWN
        }
    }

    /**
     * Calculate variance of a list of values.
     * Returns 0.0 for empty or single-element lists.
     * Callers should check for empty/single-element input to avoid
     * using 0.0 variance in division operations.
     */
    private fun calculateVariance(values: List<Double>): Double {
        if (values.size < 2) return 0.0 // Need at least 2 values for meaningful variance
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    /**
     * Safe variance calculation that returns null instead of 0.0 for invalid inputs.
     * Use this when the result will be used as a divisor.
     */
    private fun calculateVarianceOrNull(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return if (variance > 0.0) variance else null
    }

    private fun addTimelineEvent(
        type: GnssEventType,
        title: String,
        description: String,
        isAnomaly: Boolean = false,
        threatLevel: ThreatLevel = ThreatLevel.INFO
    ) {
        val event = GnssEvent(
            type = type,
            title = title,
            description = description,
            isAnomaly = isAnomaly,
            threatLevel = threatLevel,
            latitude = currentLatitude,
            longitude = currentLongitude
        )

        eventHistory.add(0, event)
        if (eventHistory.size > HISTORY_SIZE) {
            eventHistory.removeAt(eventHistory.size - 1)
        }
        _events.value = eventHistory.toList()
    }

    // ==================== ENRICHMENT ANALYSIS FUNCTIONS ====================

    /**
     * Get expected constellations based on location.
     * Different regions have different constellation visibility.
     */
    private fun getExpectedConstellations(lat: Double?, lon: Double?): Set<ConstellationType> {
        // GPS is globally available
        val expected = mutableSetOf(ConstellationType.GPS)

        // GLONASS is globally available
        expected.add(ConstellationType.GLONASS)

        // Galileo is globally available (EU system)
        expected.add(ConstellationType.GALILEO)

        // BeiDou has better coverage in Asia-Pacific
        if (lat != null && lon != null) {
            if (lon > 70 && lon < 180) { // Asia-Pacific region
                expected.add(ConstellationType.BEIDOU)
            }
            // QZSS primarily covers Japan and surrounding area
            if (lat > 20 && lat < 50 && lon > 120 && lon < 150) {
                expected.add(ConstellationType.QZSS)
            }
            // NavIC/IRNSS covers India
            if (lat > 0 && lat < 40 && lon > 60 && lon < 100) {
                expected.add(ConstellationType.IRNSS)
            }
        }

        // SBAS depends on region but is generally expected
        expected.add(ConstellationType.SBAS)

        return expected
    }

    /**
     * Update C/N0 baseline from history - creates adaptive thresholds
     */
    private fun updateCn0Baseline() {
        if (cn0History.size < 20) return

        // Use the middle 80% of samples to exclude outliers
        val sorted = cn0History.sorted()
        val trimStart = (sorted.size * 0.1).toInt()
        val trimEnd = (sorted.size * 0.9).toInt()

        // Ensure we have a valid range (at least 2 elements for variance calculation)
        if (trimEnd <= trimStart + 1) return

        val trimmed = sorted.subList(trimStart, trimEnd)

        if (trimmed.size >= 2) {
            cn0BaselineMean = trimmed.average()
            val variance = calculateVariance(trimmed)
            // Only update stdDev if variance is positive (avoid NaN from sqrt of 0)
            cn0BaselineStdDev = if (variance > 0.0) kotlin.math.sqrt(variance) else 0.0
            cn0BaselineCalculated = true
        }
    }

    /**
     * Track clock drift accumulation and detect anomalies.
     * Handles hardware clock discontinuities properly by resetting
     * cumulative tracking when a discontinuity is detected.
     *
     * @param biasNs Current clock bias in nanoseconds
     * @param discontinuityCount Hardware clock discontinuity count (from GnssClock)
     */
    private fun trackClockDriftAccumulation(biasNs: Long, discontinuityCount: Int? = null) {
        // Check for hardware clock discontinuity - reset tracking if detected
        discontinuityCount?.let { currentCount ->
            lastDiscontinuityCount?.let { lastCount ->
                if (currentCount != lastCount) {
                    Log.d(TAG, "Clock discontinuity detected ($lastCount -> $currentCount), resetting drift tracking")
                    lastClockBiasNs = null
                    cumulativeClockDriftNs = 0L
                    clockDriftHistory.clear()
                }
            }
            lastDiscontinuityCount = currentCount
        }

        lastClockBiasNs?.let { prevBias ->
            val drift = biasNs - prevBias
            cumulativeClockDriftNs += drift

            clockDriftHistory.add(drift)
            if (clockDriftHistory.size > maxDriftHistorySize) {
                clockDriftHistory.removeAt(0)
            }
        }
        lastClockBiasNs = biasNs
    }

    /**
     * Analyze clock drift trend
     */
    private fun analyzeDriftTrend(): DriftTrend {
        if (clockDriftHistory.size < 5) return DriftTrend.STABLE

        val recent = clockDriftHistory.takeLast(5)
        val older = clockDriftHistory.dropLast(5).takeLast(5)

        if (older.isEmpty()) return DriftTrend.STABLE

        val recentAvg = recent.average()
        val olderAvg = older.average()

        // Check for erratic behavior (high variance)
        val variance = calculateVariance(recent.map { it.toDouble() })
        if (variance > driftJumpThresholdNs * driftJumpThresholdNs) {
            return DriftTrend.ERRATIC
        }

        return when {
            recentAvg > olderAvg * 1.5 -> DriftTrend.INCREASING
            recentAvg < olderAvg * 0.5 -> DriftTrend.DECREASING
            else -> DriftTrend.STABLE
        }
    }

    /**
     * Count significant drift jumps
     */
    private fun countDriftJumps(): Int {
        return clockDriftHistory.count { abs(it) > driftJumpThresholdNs }
    }

    /**
     * Cross-constellation validation for spoofing detection.
     *
     * Real GNSS signals from different constellations should be CONSISTENT:
     * - GPS, GLONASS, Galileo, BeiDou should all produce the same approximate position
     * - Signal characteristics should vary naturally between constellations
     * - Clock offsets between constellations should be stable
     *
     * Spoofing typically affects all constellations identically, which is suspicious.
     */
    data class CrossConstellationAnalysis(
        val constellationCount: Int,
        val constellationsPresent: Set<ConstellationType>,
        val signalStrengthsByConstellation: Map<ConstellationType, Double>,
        val signalVarianceByConstellation: Map<ConstellationType, Double>,
        val allConstellationsIdenticalSignals: Boolean,  // Strong spoofing indicator
        val singleConstellationAnomaly: ConstellationType?,  // One constellation behaving differently
        val constellationsConsistent: Boolean,  // Are constellations giving consistent results?
        val spoofingIndicators: List<String>,
        val spoofingLikelihoodAdjustment: Float  // Positive = more likely spoofing, negative = less likely
    )

    /**
     * Perform cross-constellation validation
     */
    private fun analyzeCrossConstellation(satellites: List<SatelliteInfo>): CrossConstellationAnalysis {
        // Group satellites by constellation
        val byConstellation = satellites
            .filter { it.constellation != ConstellationType.UNKNOWN }
            .groupBy { it.constellation }
            .filter { it.value.size >= 2 }  // Need at least 2 satellites per constellation

        val constellationCount = byConstellation.size
        val constellationsPresent = byConstellation.keys

        // Calculate average signal strength per constellation
        val signalStrengthsByConstellation = byConstellation.mapValues { (_, sats) ->
            sats.map { it.cn0DbHz.toDouble() }.average()
        }

        // Calculate signal variance per constellation
        val signalVarianceByConstellation = byConstellation.mapValues { (_, sats) ->
            calculateVariance(sats.map { it.cn0DbHz.toDouble() })
        }

        val spoofingIndicators = mutableListOf<String>()
        var spoofingLikelihoodAdjustment = 0f

        // Check 1: Are ALL constellations showing identical signal patterns?
        // This is highly suspicious - real signals vary by constellation
        val avgSignals = signalStrengthsByConstellation.values.toList()
        val allConstellationsIdenticalSignals = if (avgSignals.size >= 2) {
            val signalRange = (avgSignals.maxOrNull() ?: 0.0) - (avgSignals.minOrNull() ?: 0.0)
            signalRange < 2.0  // Less than 2 dB-Hz difference is suspicious
        } else false

        if (allConstellationsIdenticalSignals && constellationCount >= 3) {
            spoofingIndicators.add("All ${constellationCount} constellations have identical signal strength (range < 2 dB-Hz)")
            spoofingLikelihoodAdjustment += 25f
        }

        // Check 2: Do all constellations have similar variance? (Suspicious)
        // Real signals: different constellations have different variance due to orbital geometry
        val variances = signalVarianceByConstellation.values.toList()
        val allVariancesSimilar = if (variances.size >= 2) {
            val varianceRange = (variances.maxOrNull() ?: 0.0) - (variances.minOrNull() ?: 0.0)
            varianceRange < 1.0 && (variances.minOrNull() ?: 0.0) < 0.5
        } else false

        if (allVariancesSimilar && constellationCount >= 3) {
            spoofingIndicators.add("All constellations have suspiciously similar low variance")
            spoofingLikelihoodAdjustment += 15f
        }

        // Check 3: Is one constellation behaving completely differently?
        // This might indicate partial spoofing or legitimate anomaly
        var singleConstellationAnomaly: ConstellationType? = null
        if (constellationCount >= 3) {
            for ((constellation, avgSignal) in signalStrengthsByConstellation) {
                val othersAvg = signalStrengthsByConstellation
                    .filter { it.key != constellation }
                    .values.average()
                val deviation = abs(avgSignal - othersAvg)
                if (deviation > 10.0) {  // More than 10 dB-Hz different
                    singleConstellationAnomaly = constellation
                    spoofingIndicators.add("${constellation.displayName} signals deviate by ${String.format("%.1f", deviation)} dB-Hz from others")
                    // This could be spoofing OR legitimate (e.g., one constellation obstructed)
                    // Don't adjust likelihood strongly
                    break
                }
            }
        }

        // Check 4: Cross-validate satellite positions (are satellites where they should be?)
        // This would require ephemeris data - for now, check if elevation/azimuth distribution is realistic
        val constellationsConsistent = !allConstellationsIdenticalSignals && singleConstellationAnomaly == null

        // Positive adjustment: multiple constellations, all behaving naturally different
        if (constellationCount >= 3 && constellationsConsistent) {
            spoofingLikelihoodAdjustment -= 15f  // Reduce spoofing likelihood
        }

        // Very strong fix with multiple constellations is strong evidence AGAINST spoofing
        if (constellationCount >= 4 && satellites.count { it.usedInFix } >= 20) {
            spoofingLikelihoodAdjustment -= 25f  // Strong evidence against spoofing
        }

        return CrossConstellationAnalysis(
            constellationCount = constellationCount,
            constellationsPresent = constellationsPresent,
            signalStrengthsByConstellation = signalStrengthsByConstellation,
            signalVarianceByConstellation = signalVarianceByConstellation,
            allConstellationsIdenticalSignals = allConstellationsIdenticalSignals,
            singleConstellationAnomaly = singleConstellationAnomaly,
            constellationsConsistent = constellationsConsistent,
            spoofingIndicators = spoofingIndicators,
            spoofingLikelihoodAdjustment = spoofingLikelihoodAdjustment
        )
    }

    // ==================== REAL-WORLD ATTACK PATTERN DETECTION ====================

    /**
     * Detect Kremlin-Circle Pattern spoofing.
     *
     * REAL-WORLD CONTEXT:
     * Russian GPS spoofing around the Kremlin and other protected sites has been
     * documented since 2017. Ships in the Black Sea have reported being "teleported"
     * to Vnukovo Airport, 37km from the Kremlin.
     *
     * KEY DETECTION INDICATOR:
     * - GPS satellites show anomalies (signal uniformity, position errors)
     * - GLONASS satellites remain NORMAL (Russian system, not targeted)
     *
     * This pattern is highly specific to state-level GPS spoofing where the
     * operator doesn't need to (or can't easily) spoof their own GLONASS system.
     */
    data class KremlinPatternAnalysis(
        val detected: Boolean,
        val gpsAnomalyScore: Float,
        val glonassHealthScore: Float,
        val gpsGlonassDeviation: Double,  // dB-Hz difference
        val description: String,
        val confidence: Float
    )

    private fun detectKremlinPattern(satellites: List<SatelliteInfo>): KremlinPatternAnalysis {
        val gpsSatellites = satellites.filter { it.constellation == ConstellationType.GPS }
        val glonassSatellites = satellites.filter { it.constellation == ConstellationType.GLONASS }

        // Need both constellations for this check
        if (gpsSatellites.size < 4 || glonassSatellites.size < 4) {
            return KremlinPatternAnalysis(
                detected = false,
                gpsAnomalyScore = 0f,
                glonassHealthScore = 100f,
                gpsGlonassDeviation = 0.0,
                description = "Insufficient satellites for Kremlin pattern analysis",
                confidence = 0f
            )
        }

        // Analyze GPS signal characteristics
        val gpsCn0Values = gpsSatellites.map { it.cn0DbHz.toDouble() }
        val gpsCn0Mean = gpsCn0Values.average()
        val gpsCn0Variance = calculateVariance(gpsCn0Values)

        // Analyze GLONASS signal characteristics
        val glonassCn0Values = glonassSatellites.map { it.cn0DbHz.toDouble() }
        val glonassCn0Mean = glonassCn0Values.average()
        val glonassCn0Variance = calculateVariance(glonassCn0Values)

        // Calculate deviation between GPS and GLONASS
        val gpsGlonassDeviation = abs(gpsCn0Mean - glonassCn0Mean)

        // GPS anomaly indicators
        var gpsAnomalyScore = 0f
        val gpsAnomalies = mutableListOf<String>()

        // Check 1: GPS signal uniformity (spoofing signature)
        if (gpsCn0Variance < SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD) {
            gpsAnomalyScore += 30f
            gpsAnomalies.add("GPS signals suspiciously uniform (variance: ${String.format("%.3f", gpsCn0Variance)})")
        }

        // Check 2: GPS elevation distribution (spoofed signals often clustered)
        val gpsElevations = gpsSatellites.map { it.elevationDegrees.toDouble() }
        val gpsElevVariance = calculateVariance(gpsElevations)
        if (gpsElevVariance < ELEVATION_VARIANCE_SUSPICIOUS_THRESHOLD) {
            gpsAnomalyScore += 20f
            gpsAnomalies.add("GPS satellites clustered at similar elevations")
        }

        // Check 3: Low elevation GPS satellites with high signal (physically implausible)
        val gpsLowElevHighSignal = gpsSatellites.count {
            it.elevationDegrees < SPOOFING_ELEVATION_THRESHOLD && it.cn0DbHz > LOW_ELEVATION_HIGH_SIGNAL_CN0
        }
        if (gpsLowElevHighSignal > 1) {
            gpsAnomalyScore += 25f
            gpsAnomalies.add("$gpsLowElevHighSignal GPS satellites at low elevation with impossibly high signal")
        }

        // GLONASS health indicators (should be NORMAL if Kremlin pattern)
        var glonassHealthScore = 100f

        // GLONASS should have normal variance
        if (glonassCn0Variance >= 0.5) {
            // Normal variance - good
        } else {
            glonassHealthScore -= 30f  // GLONASS also uniform = probably not Kremlin pattern
        }

        // GLONASS should have normal elevation distribution
        val glonassElevations = glonassSatellites.map { it.elevationDegrees.toDouble() }
        val glonassElevVariance = calculateVariance(glonassElevations)
        if (glonassElevVariance >= ELEVATION_VARIANCE_SUSPICIOUS_THRESHOLD) {
            // Normal distribution - good
        } else {
            glonassHealthScore -= 20f
        }

        // GLONASS low-elev high-signal count
        val glonassLowElevHighSignal = glonassSatellites.count {
            it.elevationDegrees < SPOOFING_ELEVATION_THRESHOLD && it.cn0DbHz > LOW_ELEVATION_HIGH_SIGNAL_CN0
        }
        if (glonassLowElevHighSignal > 0) {
            glonassHealthScore -= glonassLowElevHighSignal * 15f
        }

        // Kremlin pattern detected if:
        // 1. GPS shows significant anomalies (score > 40)
        // 2. GLONASS remains healthy (score > 70)
        // 3. There's a meaningful deviation between the two systems
        val kremlinPatternDetected = gpsAnomalyScore >= 40f &&
            glonassHealthScore >= 70f &&
            (gpsGlonassDeviation > 5.0 || gpsAnomalyScore >= 50f)

        val confidence = if (kremlinPatternDetected) {
            ((gpsAnomalyScore / 100f) * (glonassHealthScore / 100f) * 100f).coerceIn(0f, 95f)
        } else 0f

        val description = if (kremlinPatternDetected) {
            buildString {
                append("KREMLIN-CIRCLE PATTERN DETECTED: ")
                append("GPS signals show spoofing indicators while GLONASS remains normal. ")
                append("This is consistent with Russian state-level GPS spoofing. ")
                append("GPS anomalies: ${gpsAnomalies.joinToString("; ")}. ")
                append("GLONASS health: ${String.format("%.0f", glonassHealthScore)}%")
            }
        } else {
            "No Kremlin pattern detected"
        }

        // Update attack pattern tracking
        if (kremlinPatternDetected && confidence > attackPatternConfidence) {
            suspectedAttackType = SuspectedAttackType.KREMLIN_CIRCLE
            attackPatternConfidence = confidence
        }

        return KremlinPatternAnalysis(
            detected = kremlinPatternDetected,
            gpsAnomalyScore = gpsAnomalyScore,
            glonassHealthScore = glonassHealthScore,
            gpsGlonassDeviation = gpsGlonassDeviation,
            description = description,
            confidence = confidence
        )
    }

    /**
     * Detect gradual position drift (Iranian-style "lead-away" attack).
     *
     * REAL-WORLD CONTEXT:
     * The 2011 capture of the RQ-170 drone allegedly used gradual GPS spoofing
     * to lead the aircraft to a wrong landing site. The key characteristic is
     * a steady drift in position that accumulates over time.
     *
     * DETECTION METHOD:
     * Track position history and look for consistent drift in one direction
     * that exceeds normal GPS error/drift patterns.
     */
    data class GradualDriftAnalysis(
        val detected: Boolean,
        val driftRateMetersPerSec: Double,
        val driftDirectionDegrees: Double,  // 0-360, 0=North
        val driftConsistency: Float,  // 0-1, higher = more consistent direction
        val totalDriftMeters: Double,
        val description: String,
        val confidence: Float
    )

    /**
     * Update position history for drift detection.
     * Call this whenever a new position is obtained.
     */
    fun updatePositionForDriftDetection(latitude: Double, longitude: Double, accuracy: Float?) {
        val now = System.currentTimeMillis()
        positionHistory.add(PositionSample(now, latitude, longitude, accuracy))

        // Keep history manageable
        while (positionHistory.size > POSITION_DRIFT_WINDOW_SIZE * 2) {
            positionHistory.removeAt(0)
        }

        // Also update standard location tracking
        currentLatitude = latitude
        currentLongitude = longitude
    }

    private fun detectGradualDrift(): GradualDriftAnalysis {
        if (positionHistory.size < POSITION_DRIFT_WINDOW_SIZE) {
            return GradualDriftAnalysis(
                detected = false,
                driftRateMetersPerSec = 0.0,
                driftDirectionDegrees = 0.0,
                driftConsistency = 0f,
                totalDriftMeters = 0.0,
                description = "Insufficient position history for drift analysis",
                confidence = 0f
            )
        }

        // Calculate drift vectors between consecutive positions
        val driftVectors = mutableListOf<Pair<Double, Double>>()  // (distance, bearing)
        for (i in 1 until positionHistory.size) {
            val prev = positionHistory[i - 1]
            val curr = positionHistory[i]
            val timeDeltaSec = (curr.timestamp - prev.timestamp) / 1000.0

            if (timeDeltaSec > 0 && timeDeltaSec < 60) {  // Only consider reasonable time gaps
                val distance = haversineDistance(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
                val bearing = calculateBearing(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
                val rate = distance / timeDeltaSec
                driftVectors.add(Pair(rate, bearing))
            }
        }

        if (driftVectors.isEmpty()) {
            return GradualDriftAnalysis(
                detected = false,
                driftRateMetersPerSec = 0.0,
                driftDirectionDegrees = 0.0,
                driftConsistency = 0f,
                totalDriftMeters = 0.0,
                description = "No valid drift vectors computed",
                confidence = 0f
            )
        }

        // Calculate average drift rate and direction
        val avgDriftRate = driftVectors.map { it.first }.average()

        // Calculate direction consistency using circular mean
        val bearings = driftVectors.map { it.second }
        val avgBearing = circularMean(bearings)

        // Calculate consistency (how many drift segments are in similar direction)
        val consistentCount = bearings.count { angleDifference(it, avgBearing) < 45.0 }
        val driftConsistency = consistentCount.toFloat() / bearings.size

        // Calculate total drift from first to last position
        val first = positionHistory.first()
        val last = positionHistory.last()
        val totalDrift = haversineDistance(first.latitude, first.longitude, last.latitude, last.longitude)

        // Gradual drift attack indicators:
        // 1. Consistent drift rate above threshold
        // 2. High directional consistency (>80% same direction)
        // 3. Significant total drift
        val gradualDriftDetected = avgDriftRate > SUSPICIOUS_DRIFT_RATE_M_PER_S &&
            driftConsistency > DRIFT_DIRECTION_CONSISTENCY_THRESHOLD &&
            totalDrift > 100.0  // At least 100 meters total drift

        val confidence = if (gradualDriftDetected) {
            val rateScore = (avgDriftRate / 20.0).coerceIn(0.0, 1.0)
            val consistencyScore = driftConsistency.toDouble()
            val driftScore = (totalDrift / 1000.0).coerceIn(0.0, 1.0)
            ((rateScore * 0.3 + consistencyScore * 0.5 + driftScore * 0.2) * 100).toFloat()
        } else 0f

        val description = if (gradualDriftDetected) {
            buildString {
                append("GRADUAL DRIFT ATTACK DETECTED: ")
                append("Position drifting ${String.format("%.1f", avgDriftRate)} m/s ")
                append("toward ${bearingToCardinal(avgBearing)} (${String.format("%.0f", avgBearing)} degrees). ")
                append("Direction consistency: ${String.format("%.0f", driftConsistency * 100)}%. ")
                append("Total drift: ${String.format("%.0f", totalDrift)} meters. ")
                append("This pattern is consistent with Iranian-style 'lead-away' GPS spoofing.")
            }
        } else {
            "No gradual drift pattern detected"
        }

        // Update attack pattern tracking
        if (gradualDriftDetected && confidence > attackPatternConfidence) {
            suspectedAttackType = SuspectedAttackType.GRADUAL_DRIFT
            attackPatternConfidence = confidence
        }

        return GradualDriftAnalysis(
            detected = gradualDriftDetected,
            driftRateMetersPerSec = avgDriftRate,
            driftDirectionDegrees = avgBearing,
            driftConsistency = driftConsistency,
            totalDriftMeters = totalDrift,
            description = description,
            confidence = confidence
        )
    }

    /**
     * Assess environment context for false positive suppression.
     *
     * REAL-WORLD CONTEXT:
     * Many GNSS anomalies are caused by environment, not attacks:
     * - Urban canyons cause severe multipath (signals bounce off buildings)
     * - Indoor locations have weak/no signal (not jamming!)
     * - Parking garages block signals almost completely
     * - Tunnels and underpasses cause temporary signal loss
     */
    data class EnvironmentAssessment(
        val likelyIndoor: Boolean,
        val likelyUrbanCanyon: Boolean,
        val likelyNaturalObstruction: Boolean,
        val signalCharacteristic: String,
        val falsePositiveSuppression: Float,  // 0-1, how much to reduce threat score
        val userGuidance: String
    )

    private fun assessEnvironmentContext(satellites: List<SatelliteInfo>, status: GnssEnvironmentStatus): EnvironmentAssessment {
        val now = System.currentTimeMillis()

        // Check for indoor indicators
        val avgSignal = status.averageCn0DbHz
        val likelyIndoor = avgSignal < INDOOR_SIGNAL_THRESHOLD && status.satellitesUsedInFix < MIN_SATELLITES_FOR_FIX

        if (likelyIndoor) {
            lastIndoorDetectionTime = now
            likelyIndoorEnvironment = true
        } else if (now - lastIndoorDetectionTime > INDOOR_SUPPRESSION_DURATION_MS) {
            likelyIndoorEnvironment = false
        }

        // Check for urban canyon indicators (high multipath = high signal variance)
        val cn0Values = satellites.map { it.cn0DbHz.toDouble() }
        val cn0Variance = if (cn0Values.size >= 2) calculateVariance(cn0Values) else 0.0
        val likelyUrban = cn0Variance > URBAN_MULTIPATH_CN0_VARIANCE_THRESHOLD

        // Check for natural obstructions (partial sky blockage)
        val elevations = satellites.map { it.elevationDegrees }
        val highElevCount = elevations.count { it > 45 }
        val lowElevCount = elevations.count { it < 30 }
        val likelyObstruction = highElevCount > 0 && lowElevCount == 0 && status.hasFix
        // (If we only see high-elevation satellites, likely in a canyon or forest)

        // Determine signal characteristic
        val signalCharacteristic = when {
            likelyIndoor -> "Weak signals - likely indoors or obstructed"
            likelyUrban -> "High signal variance - urban multipath environment"
            likelyObstruction -> "Partial sky view - natural obstruction (canyon, forest)"
            status.hasFix && status.satellitesUsedInFix > GOOD_FIX_SATELLITES -> "Strong fix - open sky"
            else -> "Normal conditions"
        }

        // Calculate false positive suppression factor
        val suppressionFactor = when {
            likelyIndoorEnvironment -> 0.8f  // Heavily suppress threats when recently indoors
            likelyUrban -> 0.4f  // Moderate suppression for urban multipath
            likelyObstruction -> 0.3f  // Some suppression for natural obstructions
            else -> 0.0f  // No suppression
        }

        // Generate user guidance
        val userGuidance = when {
            likelyIndoor -> "You appear to be indoors or in a signal-blocked area. Move to an open area for accurate GNSS assessment."
            likelyUrban -> "Urban multipath detected. Signal reflections from buildings are normal in cities - not necessarily an attack."
            likelyObstruction -> "Partial sky visibility detected. Trees, buildings, or terrain may be blocking some satellites."
            else -> "Environment appears suitable for GNSS assessment."
        }

        lastEnvironmentAssessmentTime = now
        likelyUrbanEnvironment = likelyUrban

        return EnvironmentAssessment(
            likelyIndoor = likelyIndoorEnvironment,
            likelyUrbanCanyon = likelyUrban,
            likelyNaturalObstruction = likelyObstruction,
            signalCharacteristic = signalCharacteristic,
            falsePositiveSuppression = suppressionFactor,
            userGuidance = userGuidance
        )
    }

    /**
     * Detect constellation-specific targeting (selective jamming/spoofing).
     *
     * REAL-WORLD CONTEXT:
     * Some interference targets specific constellations:
     * - GPS-only jammers (most common, cheap consumer devices)
     * - Regional systems (BeiDou, QZSS, IRNSS) may be targeted in conflicts
     * - Galileo has OSNMA authentication - failures may indicate spoofing attempt
     */
    data class ConstellationTargetingAnalysis(
        val targetedConstellation: ConstellationType?,
        val targetingType: String,  // "JAMMING", "SPOOFING", "NONE"
        val healthyConstellations: Set<ConstellationType>,
        val degradedConstellations: Set<ConstellationType>,
        val description: String
    )

    private fun detectConstellationTargeting(satellites: List<SatelliteInfo>): ConstellationTargetingAnalysis {
        val byConstellation = satellites.groupBy { it.constellation }
            .filter { it.key != ConstellationType.UNKNOWN }

        if (byConstellation.size < 2) {
            return ConstellationTargetingAnalysis(
                targetedConstellation = null,
                targetingType = "NONE",
                healthyConstellations = byConstellation.keys,
                degradedConstellations = emptySet(),
                description = "Insufficient constellation diversity for targeting analysis"
            )
        }

        // Analyze each constellation's health
        data class ConstellationHealth(
            val constellation: ConstellationType,
            val satelliteCount: Int,
            val avgSignal: Double,
            val usedInFix: Int,
            val signalVariance: Double
        )

        val healthScores = byConstellation.map { (constellation, sats) ->
            ConstellationHealth(
                constellation = constellation,
                satelliteCount = sats.size,
                avgSignal = sats.map { it.cn0DbHz.toDouble() }.average(),
                usedInFix = sats.count { it.usedInFix },
                signalVariance = calculateVariance(sats.map { it.cn0DbHz.toDouble() })
            )
        }

        // Find the "healthy baseline" (constellation with best characteristics)
        val healthyBaseline = healthScores.maxByOrNull {
            it.avgSignal * 0.4 + it.usedInFix * 10 + it.satelliteCount * 2
        }

        if (healthyBaseline == null) {
            return ConstellationTargetingAnalysis(
                targetedConstellation = null,
                targetingType = "NONE",
                healthyConstellations = emptySet(),
                degradedConstellations = emptySet(),
                description = "Unable to establish healthy baseline"
            )
        }

        // Find degraded constellations (significantly worse than baseline)
        val degraded = healthScores.filter { health ->
            health.constellation != healthyBaseline.constellation &&
                (health.avgSignal < healthyBaseline.avgSignal - 10.0 ||  // 10 dB weaker
                    health.usedInFix == 0 && healthyBaseline.usedInFix > 2)
        }

        val healthy = healthScores.filter { it !in degraded }.map { it.constellation }.toSet()

        val targetedConstellation = degraded.minByOrNull { it.avgSignal }?.constellation
        val targetingType = when {
            degraded.isEmpty() -> "NONE"
            degraded.any { it.avgSignal < 20.0 && it.usedInFix == 0 } -> "JAMMING"
            degraded.any { it.signalVariance < 0.2 } -> "SPOOFING"
            else -> "INTERFERENCE"
        }

        val description = if (targetedConstellation != null) {
            buildString {
                append("${targetedConstellation.displayName} appears to be targeted with $targetingType. ")
                append("Healthy constellations: ${healthy.joinToString { it.code }}. ")
                append("This could indicate selective interference targeting ${targetedConstellation.displayName}.")
            }
        } else {
            "No constellation-specific targeting detected"
        }

        return ConstellationTargetingAnalysis(
            targetedConstellation = targetedConstellation,
            targetingType = targetingType,
            healthyConstellations = healthy,
            degradedConstellations = degraded.map { it.constellation }.toSet(),
            description = description
        )
    }

    // ==================== GEOGRAPHIC/MATH UTILITIES ====================

    /**
     * Calculate haversine distance between two points in meters
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0  // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    /**
     * Calculate bearing from point 1 to point 2 (0-360 degrees, 0=North)
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2Rad)
        val x = kotlin.math.cos(lat1Rad) * kotlin.math.sin(lat2Rad) -
            kotlin.math.sin(lat1Rad) * kotlin.math.cos(lat2Rad) * kotlin.math.cos(dLon)
        val bearing = Math.toDegrees(kotlin.math.atan2(y, x))
        return (bearing + 360) % 360
    }

    /**
     * Calculate circular mean of angles (for averaging bearings)
     */
    private fun circularMean(angles: List<Double>): Double {
        val sinSum = angles.sumOf { kotlin.math.sin(Math.toRadians(it)) }
        val cosSum = angles.sumOf { kotlin.math.cos(Math.toRadians(it)) }
        val mean = Math.toDegrees(kotlin.math.atan2(sinSum, cosSum))
        return (mean + 360) % 360
    }

    /**
     * Calculate angle difference (handles wraparound)
     */
    private fun angleDifference(a1: Double, a2: Double): Double {
        val diff = abs(a1 - a2)
        return if (diff > 180) 360 - diff else diff
    }

    /**
     * Convert bearing to cardinal direction
     */
    private fun bearingToCardinal(bearing: Double): String {
        val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = ((bearing + 11.25) / 22.5).toInt() % 16
        return directions[index]
    }

    /**
     * Analyze satellite geometry for spoofing indicators
     */
    private fun analyzeGeometry(satellites: List<SatelliteInfo>): Triple<Float, String, Float> {
        if (satellites.isEmpty()) return Triple(0f, "No satellites", 0f)

        // Analyze elevation distribution
        val elevations = satellites.map { it.elevationDegrees }
        val elevMean = elevations.average()
        val elevVariance = calculateVariance(elevations.map { it.toDouble() })

        val elevationDistribution = when {
            elevVariance < 100 && elevMean > 30 -> "Clustered" // Suspicious
            elevVariance < 200 -> "Narrow"
            else -> "Normal"
        }

        // Calculate azimuth coverage (0-360 degrees)
        val azimuths = satellites.map { it.azimuthDegrees.toInt() }
        val azimuthBuckets = (0 until 12).map { bucket ->
            val start = bucket * 30
            val end = start + 30
            azimuths.any { it >= start && it < end }
        }
        val azimuthCoverage = (azimuthBuckets.count { it } / 12.0f) * 100f

        // Geometry score (simplified DOP-like metric)
        // Good geometry = wide elevation spread + good azimuth coverage
        val geometryScore = when {
            elevationDistribution == "Normal" && azimuthCoverage > 66 -> 0.9f
            elevationDistribution == "Normal" && azimuthCoverage > 33 -> 0.7f
            elevationDistribution == "Narrow" && azimuthCoverage > 50 -> 0.5f
            elevationDistribution == "Clustered" -> 0.3f
            else -> 0.4f
        }

        return Triple(geometryScore, elevationDistribution, azimuthCoverage)
    }

    /**
     * Build comprehensive GNSS analysis
     */
    private fun buildGnssAnalysis(
        satellites: List<SatelliteInfo>,
        status: GnssEnvironmentStatus
    ): GnssAnomalyAnalysis {
        // Update baselines
        updateCn0Baseline()

        // Constellation fingerprinting
        val observed = satellites.map { it.constellation }.toSet() - ConstellationType.UNKNOWN
        val expected = getExpectedConstellations(currentLatitude, currentLongitude)
        val missing = expected - observed
        val unexpected = observed.any { it !in expected && it != ConstellationType.UNKNOWN }

        // Track constellation history
        constellationHistory.add(observed)
        if (constellationHistory.size > HISTORY_SIZE) constellationHistory.removeAt(0)

        val constellationMatchScore = if (expected.isNotEmpty()) {
            ((observed.intersect(expected).size.toFloat() / expected.size) * 100).toInt()
        } else 100

        // C/N0 analysis
        val cn0Values = satellites.map { it.cn0DbHz.toDouble() }
        val currentMean = if (cn0Values.isNotEmpty()) cn0Values.average() else 0.0
        val cn0Variance = calculateVariance(cn0Values)

        val cn0DeviationSigmas = if (cn0BaselineCalculated && cn0BaselineStdDev > 0) {
            abs(currentMean - cn0BaselineMean) / cn0BaselineStdDev
        } else 0.0

        val cn0Anomalous = cn0DeviationSigmas > 3.0
        // Only flag as "too uniform" if variance is EXTREMELY low (< 0.15)
        // Normal GNSS signals have variance of 0.5-5.0 due to different elevation angles, etc.
        val cn0TooUniform = cn0Variance < SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD &&  // < 0.15
            satellites.size >= MIN_SATELLITES_FOR_FIX

        // Uniformity score (lower = more uniform = more suspicious)
        val uniformityScore = if (cn0Variance > 0) {
            (cn0Variance / 20.0).coerceIn(0.0, 1.0).toFloat()
        } else 0f

        // Clock drift analysis
        val driftTrend = analyzeDriftTrend()
        val driftJumpCount = countDriftJumps()
        val maxDrift = clockDriftHistory.maxOfOrNull { abs(it) } ?: 0L
        val driftAnomalous = driftTrend == DriftTrend.ERRATIC || driftJumpCount > 3

        // Geometry analysis
        val (geometryScore, elevDistribution, azimuthCoverage) = analyzeGeometry(satellites)

        // Count low elevation with high signal (spoofing indicator)
        val lowElevHighSignal = satellites.count {
            it.elevationDegrees < SPOOFING_ELEVATION_THRESHOLD && it.cn0DbHz > 40
        }

        // Signal spike/drop counts from history
        val signalSpikeCount = if (cn0History.size > 2) {
            cn0History.zipWithNext().count { (prev, curr) -> curr - prev > 10 }
        } else 0
        val signalDropCount = if (cn0History.size > 2) {
            cn0History.zipWithNext().count { (prev, curr) -> prev - curr > 10 }
        } else 0

        // Build spoofing indicators list
        // Be careful not to flag normal conditions as suspicious
        val spoofingIndicators = mutableListOf<String>()

        // Only flag uniformity if EXTREMELY low (< 0.15) - normal variance is 0.5-5.0
        if (cn0TooUniform) {
            spoofingIndicators.add("Signal uniformity extremely suspicious (variance: ${String.format("%.3f", cn0Variance)})")
        }

        // Low elevation with high signal is a strong spoofing indicator
        if (lowElevHighSignal > 2) {
            spoofingIndicators.add("$lowElevHighSignal low-elevation satellites with suspiciously high signal (physically implausible)")
        }

        // Clustered satellites suggest potential spoofing
        if (elevDistribution == "Clustered") {
            spoofingIndicators.add("Satellites clustered at similar elevations (unusual distribution)")
        }

        // Abnormally high signals (> 55 dB-Hz) are suspicious
        val highSignalSats = satellites.filter { it.cn0DbHz > MAX_VALID_CN0_DBH }
        if (highSignalSats.isNotEmpty()) {
            spoofingIndicators.add("${highSignalSats.size} satellites with abnormally high signal strength (>${MAX_VALID_CN0_DBH} dB-Hz)")
        }

        // No ephemeris data is unusual for a good fix
        if (satellites.none { it.hasEphemeris } && satellites.size >= MIN_SATELLITES_FOR_FIX) {
            spoofingIndicators.add("No satellites have ephemeris data (unusual for ${satellites.size} visible satellites)")
        }

        // Missing expected constellations
        if (constellationMatchScore < 50) {
            spoofingIndicators.add("Missing expected constellations (only ${constellationMatchScore}% match)")
        }

        // Build jamming indicators list
        val jammingIndicators = mutableListOf<String>()
        if (signalDropCount > 3) jammingIndicators.add("Multiple sudden signal drops detected")
        if (status.jammingDetected) jammingIndicators.add("Rapid degradation across all signals")
        if (cn0Anomalous && currentMean < cn0BaselineMean) jammingIndicators.add("Signal strength significantly below baseline")

        // Perform cross-constellation validation
        val crossConstellation = analyzeCrossConstellation(satellites)

        // Add cross-constellation spoofing indicators
        spoofingIndicators.addAll(crossConstellation.spoofingIndicators)

        // Calculate composite scores
        val spoofingLikelihood = calculateSpoofingLikelihood(
            cn0TooUniform, lowElevHighSignal, elevDistribution,
            uniformityScore, satellites, status
        )
        val jammingLikelihood = calculateJammingLikelihood(
            status, signalDropCount, cn0Anomalous, currentMean
        )

        // Determine if this is likely normal operation (strong evidence against threats)
        val isLikelyNormalOperation = status.satellitesUsedInFix >= EXCELLENT_FIX_SATELLITES &&
            crossConstellation.constellationCount >= 3 &&
            crossConstellation.constellationsConsistent &&
            !cn0TooUniform &&
            lowElevHighSignal == 0

        return GnssAnomalyAnalysis(
            expectedConstellations = expected,
            observedConstellations = observed,
            missingConstellations = missing,
            unexpectedConstellation = unexpected,
            constellationMatchScore = constellationMatchScore,
            historicalCn0Mean = cn0BaselineMean,
            historicalCn0StdDev = cn0BaselineStdDev,
            currentCn0Mean = currentMean,
            cn0DeviationSigmas = cn0DeviationSigmas,
            cn0Anomalous = cn0Anomalous,
            cn0TooUniform = cn0TooUniform,
            cn0Variance = cn0Variance,
            cumulativeDriftNs = cumulativeClockDriftNs,
            driftTrend = driftTrend,
            driftAnomalous = driftAnomalous,
            maxDriftInWindowNs = maxDrift,
            driftJumpCount = driftJumpCount,
            geometryScore = geometryScore,
            elevationDistribution = elevDistribution,
            azimuthCoverage = azimuthCoverage,
            lowElevHighSignalCount = lowElevHighSignal,
            uniformityScore = uniformityScore,
            signalSpikeCount = signalSpikeCount,
            signalDropCount = signalDropCount,
            spoofingLikelihood = spoofingLikelihood,
            jammingLikelihood = jammingLikelihood,
            spoofingIndicators = spoofingIndicators,
            jammingIndicators = jammingIndicators,
            // Cross-constellation validation
            crossConstellationCount = crossConstellation.constellationCount,
            crossConstellationConsistent = crossConstellation.constellationsConsistent,
            allConstellationsIdenticalSignals = crossConstellation.allConstellationsIdenticalSignals,
            crossConstellationSpoofingAdjustment = crossConstellation.spoofingLikelihoodAdjustment,
            // False positive heuristics
            isLikelyNormalOperation = isLikelyNormalOperation
        )
    }

    /**
     * Calculate spoofing likelihood percentage
     *
     * IMPORTANT: Strong satellite fix (many satellites, multiple constellations)
     * is strong evidence AGAINST spoofing. Spoofing is very difficult to do
     * perfectly across multiple constellations.
     */
    private fun calculateSpoofingLikelihood(
        cn0TooUniform: Boolean,
        lowElevHighSignal: Int,
        elevDistribution: String,
        uniformityScore: Float,
        satellites: List<SatelliteInfo>,
        status: GnssEnvironmentStatus
    ): Float {
        var score = 0f

        // Perform cross-constellation validation
        val crossConstellation = analyzeCrossConstellation(satellites)

        // Only add points for extremely uniform signals (variance < 0.15)
        if (cn0TooUniform) score += 25f

        // Low elevation with high signal is physically implausible
        if (lowElevHighSignal > 2) score += lowElevHighSignal * 10f

        // Clustered elevations are suspicious
        if (elevDistribution == "Clustered") score += 15f

        // Very low uniformity score (but only if variance is truly suspicious)
        if (uniformityScore < 0.01f && satellites.size >= MIN_SATELLITES_FOR_FIX) score += 20f

        // Abnormally high signal strength
        if (satellites.any { it.cn0DbHz > MAX_VALID_CN0_DBH }) score += 15f

        // No ephemeris data is unusual
        if (satellites.none { it.hasEphemeris } && satellites.size >= MIN_SATELLITES_FOR_FIX) score += 10f

        // Apply cross-constellation adjustment (can be positive or negative)
        score += crossConstellation.spoofingLikelihoodAdjustment

        // Add cross-constellation specific indicators
        if (crossConstellation.allConstellationsIdenticalSignals) {
            score += 20f  // Strong spoofing indicator
        }

        // CRITICAL: Strong satellite fix is evidence AGAINST spoofing
        // Spoofing 30+ satellites across 4+ constellations is extremely difficult
        if (status.satellitesUsedInFix >= STRONG_FIX_SATELLITES) {
            score *= 0.3f  // 70% reduction for very strong fix
        } else if (status.satellitesUsedInFix >= EXCELLENT_FIX_SATELLITES) {
            score *= 0.5f  // 50% reduction for excellent fix
        } else if (status.satellitesUsedInFix >= GOOD_FIX_SATELLITES) {
            score *= 0.7f  // 30% reduction for good fix
        }

        // Multiple healthy constellations reduce spoofing likelihood
        if (crossConstellation.constellationCount >= 4 && crossConstellation.constellationsConsistent) {
            score *= 0.6f  // 40% reduction for multi-constellation consistency
        }

        // Boost based on existing risk assessment (but not too much)
        when (status.spoofingRiskLevel) {
            SpoofingRiskLevel.CRITICAL -> score = (score * 1.3f).coerceAtMost(95f)
            SpoofingRiskLevel.HIGH -> score = (score * 1.2f).coerceAtMost(85f)
            else -> {}
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Calculate jamming likelihood percentage
     *
     * CRITICAL: Jamming is INCOMPATIBLE with:
     * - Having many visible satellites (30+)
     * - Having a good position fix (10+ satellites used)
     * - Good signal strength across satellites
     *
     * If any of these are true, jamming likelihood should be ZERO or very low.
     */
    private fun calculateJammingLikelihood(
        status: GnssEnvironmentStatus,
        signalDropCount: Int,
        cn0Anomalous: Boolean,
        currentMean: Double
    ): Float {
        // CRITICAL: If we have many satellites with good signals, jamming is IMPOSSIBLE
        // True jamming would prevent satellite acquisition entirely
        if (status.totalSatellites > JAMMING_MAX_SATELLITES_FOR_DETECTION) {
            // Cannot claim jamming with 8+ satellites visible
            return 0f
        }

        if (status.satellitesUsedInFix >= GOOD_FIX_SATELLITES) {
            // Cannot claim jamming with 10+ satellites used in fix
            return 0f
        }

        if (status.hasFix && status.averageCn0DbHz > 30.0) {
            // Cannot claim jamming with good fix and good signal strength
            return 0f
        }

        // Calculate score based on actual jamming indicators
        var score = 0f

        // Base score from detected jamming (but only if satellites are actually affected)
        if (status.jammingDetected && status.totalSatellites < MIN_SATELLITES_FOR_FIX) {
            score += 40f
        }

        // Signal drop count - only significant if dramatic
        if (signalDropCount > 5) {
            score += (signalDropCount - 5) * 8f  // Only count drops beyond 5
        }

        // C/N0 significantly below baseline - but only if also losing satellites
        if (cn0Anomalous && currentMean < cn0BaselineMean && status.totalSatellites < GOOD_FIX_SATELLITES) {
            val deviationFromBaseline = cn0BaselineMean - currentMean
            score += (deviationFromBaseline / 5.0).coerceAtMost(25.0).toFloat()
        }

        // Sudden loss of satellites is the strongest jamming indicator
        if (satelliteCountHistory.size >= 5) {
            val recentCount = satelliteCountHistory.takeLast(3).average()
            val previousCount = satelliteCountHistory.dropLast(3).takeLast(3).let {
                if (it.isEmpty()) return@let 0.0
                it.average()
            }

            if (previousCount > 0) {
                val lossRatio = (previousCount - recentCount) / previousCount
                if (lossRatio > 0.5 && recentCount < MIN_SATELLITES_FOR_FIX) {
                    // Lost more than 50% of satellites and now below fix threshold
                    score += 35f
                }
            }
        }

        // Final sanity check - reduce score if we still have reasonable satellite visibility
        if (status.totalSatellites >= MIN_SATELLITES_FOR_FIX) {
            score *= 0.5f  // Halve the score if we still have 4+ satellites
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Build enriched technical details from analysis
     */
    private fun buildGnssTechnicalDetails(analysis: GnssAnomalyAnalysis): String {
        return buildString {
            // Summary assessment
            appendLine("=== Threat Assessment ===")
            appendLine("Spoofing Likelihood: ${String.format("%.0f", analysis.spoofingLikelihood)}%")
            appendLine("Jamming Likelihood: ${String.format("%.0f", analysis.jammingLikelihood)}%")

            if (analysis.isLikelyNormalOperation) {
                appendLine()
                appendLine("*** LIKELY NORMAL OPERATION ***")
                appendLine("Strong satellite fix with consistent multi-constellation data")
                appendLine("suggests legitimate GNSS signals.")
            }

            // Cross-Constellation Analysis
            appendLine()
            appendLine("=== Cross-Constellation Validation ===")
            appendLine("Constellations present: ${analysis.crossConstellationCount}")
            appendLine("Observed: ${analysis.observedConstellations.joinToString { it.code }}")
            if (analysis.missingConstellations.isNotEmpty()) {
                appendLine("Missing: ${analysis.missingConstellations.joinToString { it.code }}")
            }
            appendLine("Constellations consistent: ${if (analysis.crossConstellationConsistent) "Yes" else "No - anomaly detected"}")
            if (analysis.allConstellationsIdenticalSignals) {
                appendLine("WARNING: All constellations have identical signal strength - spoofing indicator")
            }
            if (analysis.crossConstellationSpoofingAdjustment != 0f) {
                val direction = if (analysis.crossConstellationSpoofingAdjustment > 0) "+" else ""
                appendLine("Cross-constellation adjustment: $direction${String.format("%.0f", analysis.crossConstellationSpoofingAdjustment)}%")
            }

            // Signal Analysis
            appendLine()
            appendLine("=== Signal Analysis ===")
            appendLine("Average C/N0: ${String.format("%.1f", analysis.currentCn0Mean)} dB-Hz")
            if (analysis.historicalCn0Mean > 0) {
                appendLine("Baseline C/N0: ${String.format("%.1f", analysis.historicalCn0Mean)} dB-Hz")
            }
            appendLine("Signal variance: ${String.format("%.2f", analysis.cn0Variance)} dB-Hz")

            // Explain variance assessment
            val varianceAssessment = when {
                analysis.cn0Variance < SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD -> "SUSPICIOUS - extremely uniform (< ${SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD})"
                analysis.cn0Variance < CN0_UNIFORMITY_WARNING_THRESHOLD -> "Low - worth monitoring"
                analysis.cn0Variance < 5.0 -> "Normal"
                else -> "High - likely multipath environment"
            }
            appendLine("Variance assessment: $varianceAssessment")

            if (analysis.cn0DeviationSigmas > 2) {
                appendLine("Signal deviation: ${String.format("%.1f", analysis.cn0DeviationSigmas)} sigma from baseline")
            }

            // Geometry Analysis
            appendLine()
            appendLine("=== Satellite Geometry ===")
            appendLine("Geometry score: ${String.format("%.0f", analysis.geometryScore * 100)}%")
            appendLine("Elevation distribution: ${analysis.elevationDistribution}")
            appendLine("Azimuth coverage: ${String.format("%.0f", analysis.azimuthCoverage)}% of sky")
            if (analysis.lowElevHighSignalCount > 0) {
                appendLine("Low-elev high-signal satellites: ${analysis.lowElevHighSignalCount}")
                appendLine("  (Physically implausible - strong spoofing indicator)")
            }

            // Clock Analysis
            if (analysis.driftTrend != DriftTrend.STABLE || analysis.driftJumpCount > 0) {
                appendLine()
                appendLine("=== Clock Analysis ===")
                appendLine("Drift trend: ${analysis.driftTrend.displayName}")
                appendLine("Drift jumps: ${analysis.driftJumpCount}")
                appendLine("Max drift: ${analysis.maxDriftInWindowNs / 1000} microseconds")
            }

            // Actionable recommendations
            appendLine()
            appendLine("=== Recommendations ===")
            when {
                analysis.jammingLikelihood >= 50 && !analysis.isLikelyNormalOperation -> {
                    appendLine("- Move to an open sky area away from potential interference")
                    appendLine("- Check for nearby RF interference sources")
                    appendLine("- If issue persists, report to authorities")
                }
                analysis.spoofingLikelihood >= 50 && !analysis.isLikelyNormalOperation -> {
                    appendLine("- Cross-check position with other sources (cellular, WiFi)")
                    appendLine("- Compare with visual landmarks if possible")
                    appendLine("- Document the anomaly for investigation")
                }
                analysis.isLikelyNormalOperation -> {
                    appendLine("- No action needed - GNSS appears to be operating normally")
                    appendLine("- Continue monitoring for changes")
                }
                else -> {
                    appendLine("- Continue monitoring GNSS status")
                    appendLine("- Consider environmental factors (buildings, trees, weather)")
                }
            }
        }
    }

    /**
     * Build contributing factors from analysis
     */
    private fun buildGnssContributingFactors(analysis: GnssAnomalyAnalysis): List<String> {
        val factors = mutableListOf<String>()

        // Add spoofing indicators
        factors.addAll(analysis.spoofingIndicators)

        // Add jamming indicators
        factors.addAll(analysis.jammingIndicators)

        // Add geometry issues
        if (analysis.geometryScore < 0.5f) {
            factors.add("Poor satellite geometry (score: ${String.format("%.0f", analysis.geometryScore * 100)}%)")
        }

        // Add constellation issues
        if (analysis.constellationMatchScore < 70) {
            factors.add("Constellation coverage ${analysis.constellationMatchScore}% of expected")
        }

        // Add drift issues
        if (analysis.driftAnomalous) {
            factors.add("Clock drift anomaly (${analysis.driftTrend.displayName}, ${analysis.driftJumpCount} jumps)")
        }

        // Summary scores
        if (analysis.spoofingLikelihood >= 70) {
            factors.add("HIGH spoofing likelihood: ${String.format("%.0f", analysis.spoofingLikelihood)}%")
        } else if (analysis.spoofingLikelihood >= 40) {
            factors.add("Moderate spoofing likelihood: ${String.format("%.0f", analysis.spoofingLikelihood)}%")
        }

        if (analysis.jammingLikelihood >= 70) {
            factors.add("HIGH jamming likelihood: ${String.format("%.0f", analysis.jammingLikelihood)}%")
        } else if (analysis.jammingLikelihood >= 40) {
            factors.add("Moderate jamming likelihood: ${String.format("%.0f", analysis.jammingLikelihood)}%")
        }

        return factors
    }

    // ==================== Detection Conversion ====================

    fun anomalyToDetection(anomaly: GnssAnomaly): Detection {
        val detectionMethod = when (anomaly.type) {
            GnssAnomalyType.SPOOFING_DETECTED -> DetectionMethod.GNSS_SPOOFING
            GnssAnomalyType.JAMMING_DETECTED -> DetectionMethod.GNSS_JAMMING
            GnssAnomalyType.SIGNAL_UNIFORMITY -> DetectionMethod.GNSS_SIGNAL_ANOMALY
            GnssAnomalyType.IMPOSSIBLE_GEOMETRY -> DetectionMethod.GNSS_GEOMETRY_ANOMALY
            GnssAnomalyType.SUDDEN_SIGNAL_LOSS -> DetectionMethod.GNSS_SIGNAL_LOSS
            GnssAnomalyType.CLOCK_ANOMALY -> DetectionMethod.GNSS_CLOCK_ANOMALY
            GnssAnomalyType.MULTIPATH_SEVERE -> DetectionMethod.GNSS_MULTIPATH
            GnssAnomalyType.CONSTELLATION_DROPOUT -> DetectionMethod.GNSS_CONSTELLATION_ANOMALY
            GnssAnomalyType.CN0_SPIKE -> DetectionMethod.GNSS_SIGNAL_ANOMALY
            GnssAnomalyType.ELEVATION_ANOMALY -> DetectionMethod.GNSS_GEOMETRY_ANOMALY
        }

        val threatScore = when (anomaly.confidence) {
            AnomalyConfidence.CRITICAL -> 95
            AnomalyConfidence.HIGH -> 75
            AnomalyConfidence.MEDIUM -> 50
            AnomalyConfidence.LOW -> 25
        }

        return Detection(
            deviceType = DeviceType.GNSS_SPOOFER,
            protocol = DetectionProtocol.GNSS,
            detectionMethod = detectionMethod,
            deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}",
            macAddress = null,
            ssid = null,
            rssi = _gnssStatus.value?.averageCn0DbHz?.toInt() ?: 0,
            signalStrength = SignalStrength.UNKNOWN,
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            threatLevel = anomaly.severity,
            threatScore = threatScore,
            manufacturer = anomaly.affectedConstellations.joinToString(", ") { it.displayName },
            matchedPatterns = anomaly.contributingFactors.joinToString(", ")
        )
    }
}

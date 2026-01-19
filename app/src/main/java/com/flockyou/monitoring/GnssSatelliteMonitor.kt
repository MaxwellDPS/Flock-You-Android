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
 */
class GnssSatelliteMonitor(
    private val context: Context,
    private val errorCallback: com.flockyou.service.ScanningService.DetectorCallback? = null
) {

    companion object {
        private const val TAG = "GnssSatelliteMonitor"

        // Spoofing detection thresholds
        const val MIN_SATELLITES_FOR_FIX = 4
        const val SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD = 3.0  // dB-Hz - too uniform = spoofing
        const val MAX_VALID_CN0_DBH = 55.0  // Above this is suspicious
        const val MIN_VALID_CN0_DBH = 10.0  // Below this is noise
        const val JAMMING_CN0_DROP_THRESHOLD = 15.0  // Sudden drop in dB-Hz
        const val SPOOFING_ELEVATION_THRESHOLD = 5.0  // Satellites claiming <5° elevation suspiciously strong

        // Multipath detection
        const val MULTIPATH_SNR_VARIANCE_THRESHOLD = 8.0  // High variance = multipath

        // Timing thresholds
        const val MAX_CLOCK_DRIFT_NS = 1_000_000L  // 1ms max drift between measurements
        const val HISTORY_SIZE = 100
        const val DEFAULT_ANOMALY_COOLDOWN_MS = 30_000L  // 30 seconds between same anomaly type
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

    // Enhanced tracking for enrichments
    private val clockDriftHistory = mutableListOf<Long>()     // Accumulated drift values
    private var cumulativeClockDriftNs: Long = 0L
    private val constellationHistory = mutableListOf<Set<ConstellationType>>()
    private var cn0BaselineMean: Double = 0.0
    private var cn0BaselineStdDev: Double = 0.0
    private var cn0BaselineCalculated: Boolean = false
    private val maxDriftHistorySize = 50
    private val driftJumpThresholdNs = 100_000L  // 100 microseconds

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
        val jammingIndicators: List<String>
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

        // Check for severe multipath
        val severeMultipath = multipathIndicators.count {
            it == GnssMeasurement.MULTIPATH_INDICATOR_DETECTED
        }
        if (severeMultipath > measurements.size / 2 && measurements.size >= MIN_SATELLITES_FOR_FIX) {
            reportAnomaly(
                type = GnssAnomalyType.MULTIPATH_SEVERE,
                description = "Severe multipath interference detected",
                technicalDetails = "$severeMultipath of ${measurements.size} satellites showing multipath",
                confidence = AnomalyConfidence.MEDIUM,
                contributingFactors = listOf("Urban canyon or indoor environment likely")
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

    private fun analyzeJamming(satellites: List<SatelliteInfo>, avgCn0: Double): Boolean {
        if (cn0History.size < 3) return false

        // Check for sudden drop in signal strength
        val recentAvg = cn0History.takeLast(3).average()
        val previousList = cn0History.dropLast(3).takeLast(5)

        // Guard against empty collection
        if (previousList.isEmpty()) return false
        val previousAvg = previousList.average()

        if (previousAvg - recentAvg > JAMMING_CN0_DROP_THRESHOLD) {
            return true
        }

        // Check for sudden loss of satellites
        if (satelliteCountHistory.size >= 8) {
            val recentCount = satelliteCountHistory.takeLast(3).average()
            val previousCountList = satelliteCountHistory.dropLast(3).takeLast(5)

            // Guard against empty collection
            if (previousCountList.isEmpty()) return false
            val previousCount = previousCountList.average()

            if (previousCount > 6 && recentCount < 2) {
                return true
            }
        }

        return false
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
        if (status.jammingDetected || analysis.jammingLikelihood >= 50) {
            val enrichedFactors = buildGnssContributingFactors(analysis)

            val confidence = when {
                analysis.jammingLikelihood >= 80 -> AnomalyConfidence.CRITICAL
                analysis.jammingLikelihood >= 60 -> AnomalyConfidence.HIGH
                analysis.jammingLikelihood >= 40 -> AnomalyConfidence.MEDIUM
                else -> AnomalyConfidence.LOW
            }

            reportAnomaly(
                type = GnssAnomalyType.JAMMING_DETECTED,
                description = "GNSS jamming indicators - likelihood: ${String.format("%.0f", analysis.jammingLikelihood)}%",
                technicalDetails = buildGnssTechnicalDetails(analysis),
                confidence = confidence,
                contributingFactors = enrichedFactors
            )
        }

        // Signal uniformity anomaly (spoofing indicator) - enriched
        if (analysis.cn0TooUniform && !status.jammingDetected) {
            reportAnomaly(
                type = GnssAnomalyType.SIGNAL_UNIFORMITY,
                description = "Signal uniformity suspicious - variance: ${String.format("%.2f", analysis.cn0Variance)}",
                technicalDetails = buildGnssTechnicalDetails(analysis),
                confidence = AnomalyConfidence.MEDIUM,
                contributingFactors = analysis.spoofingIndicators
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

    private fun calculateVariance(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
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
        val trimmed = sorted.subList(trimStart, trimEnd)

        if (trimmed.isNotEmpty()) {
            cn0BaselineMean = trimmed.average()
            cn0BaselineStdDev = kotlin.math.sqrt(calculateVariance(trimmed))
            cn0BaselineCalculated = true
        }
    }

    /**
     * Track clock drift accumulation and detect anomalies
     */
    private fun trackClockDriftAccumulation(biasNs: Long) {
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
        val cn0TooUniform = cn0Variance < SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD &&
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
        val spoofingIndicators = mutableListOf<String>()
        if (cn0TooUniform) spoofingIndicators.add("Signal uniformity too perfect (variance: ${String.format("%.2f", cn0Variance)})")
        if (lowElevHighSignal > 2) spoofingIndicators.add("$lowElevHighSignal low-elevation satellites with suspiciously high signal")
        if (elevDistribution == "Clustered") spoofingIndicators.add("Satellites clustered at similar elevations")
        if (satellites.any { it.cn0DbHz > MAX_VALID_CN0_DBH }) spoofingIndicators.add("Abnormally high signal strength detected")
        if (satellites.none { it.hasEphemeris } && satellites.size >= MIN_SATELLITES_FOR_FIX) {
            spoofingIndicators.add("No satellites have ephemeris data (unusual)")
        }
        if (constellationMatchScore < 50) spoofingIndicators.add("Missing expected constellations")

        // Build jamming indicators list
        val jammingIndicators = mutableListOf<String>()
        if (signalDropCount > 3) jammingIndicators.add("Multiple sudden signal drops detected")
        if (status.jammingDetected) jammingIndicators.add("Rapid degradation across all signals")
        if (cn0Anomalous && currentMean < cn0BaselineMean) jammingIndicators.add("Signal strength significantly below baseline")

        // Calculate composite scores
        val spoofingLikelihood = calculateSpoofingLikelihood(
            cn0TooUniform, lowElevHighSignal, elevDistribution,
            uniformityScore, satellites, status
        )
        val jammingLikelihood = calculateJammingLikelihood(
            status, signalDropCount, cn0Anomalous, currentMean
        )

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
            jammingIndicators = jammingIndicators
        )
    }

    /**
     * Calculate spoofing likelihood percentage
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

        if (cn0TooUniform) score += 25f
        if (lowElevHighSignal > 0) score += lowElevHighSignal * 8f
        if (elevDistribution == "Clustered") score += 15f
        if (uniformityScore < 0.2f && satellites.size >= MIN_SATELLITES_FOR_FIX) score += 20f
        if (satellites.any { it.cn0DbHz > MAX_VALID_CN0_DBH }) score += 15f
        if (satellites.none { it.hasEphemeris } && satellites.size >= MIN_SATELLITES_FOR_FIX) score += 10f

        // Boost based on existing risk assessment
        when (status.spoofingRiskLevel) {
            SpoofingRiskLevel.CRITICAL -> score = (score * 1.5f).coerceAtMost(100f)
            SpoofingRiskLevel.HIGH -> score = (score * 1.3f).coerceAtMost(100f)
            else -> {}
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Calculate jamming likelihood percentage
     */
    private fun calculateJammingLikelihood(
        status: GnssEnvironmentStatus,
        signalDropCount: Int,
        cn0Anomalous: Boolean,
        currentMean: Double
    ): Float {
        var score = 0f

        if (status.jammingDetected) score += 50f
        if (signalDropCount > 3) score += signalDropCount * 5f
        if (cn0Anomalous && currentMean < cn0BaselineMean) score += 20f
        if (status.satellitesUsedInFix < MIN_SATELLITES_FOR_FIX && satelliteCountHistory.takeLast(3).average() > 6) {
            score += 25f
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Build enriched technical details from analysis
     */
    private fun buildGnssTechnicalDetails(analysis: GnssAnomalyAnalysis): String {
        val parts = mutableListOf<String>()

        // Spoofing/Jamming likelihood
        parts.add("Spoofing Likelihood: ${String.format("%.0f", analysis.spoofingLikelihood)}%")
        parts.add("Jamming Likelihood: ${String.format("%.0f", analysis.jammingLikelihood)}%")

        // C/N0 Analysis
        parts.add("C/N0: ${String.format("%.1f", analysis.currentCn0Mean)} dB-Hz (baseline: ${String.format("%.1f", analysis.historicalCn0Mean)})")
        if (analysis.cn0TooUniform) {
            parts.add("⚠️ Signal uniformity suspicious (variance: ${String.format("%.2f", analysis.cn0Variance)})")
        }
        if (analysis.cn0DeviationSigmas > 2) {
            parts.add("⚠️ Signal ${String.format("%.1f", analysis.cn0DeviationSigmas)}σ from baseline")
        }

        // Geometry
        parts.add("Geometry Score: ${String.format("%.0f", analysis.geometryScore * 100)}%")
        parts.add("Elevation Distribution: ${analysis.elevationDistribution}")
        parts.add("Azimuth Coverage: ${String.format("%.0f", analysis.azimuthCoverage)}%")
        if (analysis.lowElevHighSignalCount > 0) {
            parts.add("⚠️ ${analysis.lowElevHighSignalCount} low-elev satellites with high signal")
        }

        // Clock Drift
        if (analysis.driftTrend != DriftTrend.STABLE) {
            parts.add("Clock Drift: ${analysis.driftTrend.displayName}")
        }
        if (analysis.driftJumpCount > 0) {
            parts.add("Drift Jumps: ${analysis.driftJumpCount}")
        }

        // Constellations
        parts.add("Constellations: ${analysis.observedConstellations.joinToString { it.code }}")
        if (analysis.missingConstellations.isNotEmpty()) {
            parts.add("Missing: ${analysis.missingConstellations.joinToString { it.code }}")
        }

        return parts.joinToString("\n")
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

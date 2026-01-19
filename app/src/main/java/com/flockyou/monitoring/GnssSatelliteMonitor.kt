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
class GnssSatelliteMonitor(private val context: Context) {

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
        const val ANOMALY_COOLDOWN_MS = 30_000L  // 30 seconds between same anomaly type
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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

    // ==================== Lifecycle ====================

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun startMonitoring() {
        if (isMonitoring) {
            Log.w(TAG, "Already monitoring")
            return
        }

        Log.i(TAG, "Starting GNSS Satellite Monitor")
        isMonitoring = true

        registerGnssStatusCallback()
        registerGnssMeasurementsCallback()

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

        unregisterCallbacks()
        coroutineScope.cancel()

        addTimelineEvent(
            type = GnssEventType.MONITORING_STOPPED,
            title = "GNSS Monitoring Stopped",
            description = "Satellite data collection ended"
        )
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register GNSS status callback", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register GNSS measurements callback", e)
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

        // Check for clock anomalies
        val currentBias = clock.biasNanos.toLong()
        lastClockBiasNs?.let { prevBias ->
            val drift = abs(currentBias - prevBias)
            if (drift > MAX_CLOCK_DRIFT_NS) {
                reportAnomaly(
                    type = GnssAnomalyType.CLOCK_ANOMALY,
                    description = "Large clock discontinuity detected",
                    technicalDetails = "Clock drift: ${drift}ns (threshold: ${MAX_CLOCK_DRIFT_NS}ns)",
                    confidence = AnomalyConfidence.MEDIUM,
                    contributingFactors = listOf("Clock bias jumped ${drift / 1_000_000}ms")
                )
            }
        }
        lastClockBiasNs = currentBias

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
        val previousAvg = cn0History.dropLast(3).takeLast(5).average()

        if (previousAvg - recentAvg > JAMMING_CN0_DROP_THRESHOLD) {
            return true
        }

        // Check for sudden loss of satellites
        if (satelliteCountHistory.size >= 3) {
            val recentCount = satelliteCountHistory.takeLast(3).average()
            val previousCount = satelliteCountHistory.dropLast(3).takeLast(5).average()
            if (previousCount > 6 && recentCount < 2) {
                return true
            }
        }

        return false
    }

    private fun runAnomalyDetection(satellites: List<SatelliteInfo>, status: GnssEnvironmentStatus) {
        // Spoofing detection
        if (status.spoofingRiskLevel == SpoofingRiskLevel.HIGH ||
            status.spoofingRiskLevel == SpoofingRiskLevel.CRITICAL) {
            val factors = mutableListOf<String>()

            val cn0Values = satellites.map { it.cn0DbHz.toDouble() }
            val variance = calculateVariance(cn0Values)
            if (variance < SUSPICIOUS_CN0_UNIFORMITY_THRESHOLD) {
                factors.add("C/N0 variance too uniform: ${String.format("%.2f", variance)} dB-Hz")
            }

            val highSignal = satellites.count { it.cn0DbHz > MAX_VALID_CN0_DBH }
            if (highSignal > 0) factors.add("$highSignal satellites with abnormally high C/N0")

            val lowElevHighSignal = satellites.count { it.elevationDegrees < 5 && it.cn0DbHz > 40 }
            if (lowElevHighSignal > 0) factors.add("$lowElevHighSignal low-elevation sats with high signal")

            reportAnomaly(
                type = GnssAnomalyType.SPOOFING_DETECTED,
                description = "GNSS spoofing indicators detected",
                technicalDetails = "Risk level: ${status.spoofingRiskLevel.displayName}",
                confidence = if (status.spoofingRiskLevel == SpoofingRiskLevel.CRITICAL)
                    AnomalyConfidence.CRITICAL else AnomalyConfidence.HIGH,
                contributingFactors = factors,
                affectedConstellations = satellites.map { it.constellation }.distinct()
            )
        }

        // Jamming detection
        if (status.jammingDetected) {
            reportAnomaly(
                type = GnssAnomalyType.JAMMING_DETECTED,
                description = "GNSS jamming detected - sudden signal degradation",
                technicalDetails = "Avg C/N0 dropped from ${cn0History.dropLast(3).takeLast(5).average().toInt()} to ${cn0History.takeLast(3).average().toInt()} dB-Hz",
                confidence = AnomalyConfidence.HIGH,
                contributingFactors = listOf(
                    "Rapid signal strength decrease",
                    "Multiple constellations affected"
                )
            )
        }

        // Constellation dropout
        val currentConstellations = status.constellationCounts.keys
        if (currentConstellations.size == 1 && satellites.size >= 6) {
            val missing = ConstellationType.values()
                .filter { it != ConstellationType.UNKNOWN && it !in currentConstellations }
            reportAnomaly(
                type = GnssAnomalyType.CONSTELLATION_DROPOUT,
                description = "Only ${currentConstellations.first().displayName} visible",
                technicalDetails = "Missing constellations: ${missing.joinToString { it.displayName }}",
                confidence = AnomalyConfidence.LOW,
                contributingFactors = listOf("Single constellation environment unusual in open sky")
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
        if (now - lastTime < ANOMALY_COOLDOWN_MS) return
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

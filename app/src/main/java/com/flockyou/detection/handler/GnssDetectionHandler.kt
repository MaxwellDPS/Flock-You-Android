package com.flockyou.detection.handler

import android.util.Log
import com.flockyou.ai.EnrichedDetectorData
import com.flockyou.ai.PromptTemplates
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.SignalStrength
import com.flockyou.data.model.ThreatLevel
import com.flockyou.monitoring.GnssSatelliteMonitor
import com.flockyou.monitoring.GnssSatelliteMonitor.ConstellationType
import com.flockyou.monitoring.GnssSatelliteMonitor.DriftTrend
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomalyAnalysis
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomalyType
import com.flockyou.monitoring.GnssSatelliteMonitor.AnomalyConfidence
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Detection handler for GNSS (GPS/satellite) spoofing and jamming attacks.
 *
 * Handles all GNSS-related detection methods:
 * - GNSS_SPOOFING: Fake satellite signals attempting to manipulate position
 * - GNSS_JAMMING: Signal blocking/degradation attacks
 * - GNSS_SIGNAL_ANOMALY: Unusual signal characteristics (too uniform, abnormal levels)
 * - GNSS_GEOMETRY_ANOMALY: Impossible satellite positions
 * - GNSS_SIGNAL_LOSS: Sudden loss of satellite signals
 * - GNSS_CLOCK_ANOMALY: Timing discontinuities suggesting manipulation
 * - GNSS_MULTIPATH: Severe reflection interference (urban canyons, indoor)
 * - GNSS_CONSTELLATION_ANOMALY: Unexpected constellation behavior
 *
 * Performs:
 * - Constellation fingerprinting (GPS/GLONASS/Galileo/BeiDou/QZSS/SBAS/IRNSS)
 * - C/N0 baseline deviation tracking
 * - Clock drift accumulation analysis
 * - Satellite geometry validation
 * - Spoofing/jamming likelihood calculation
 */
@Singleton
class GnssDetectionHandler @Inject constructor() {

    companion object {
        private const val TAG = "GnssDetectionHandler"

        // Detection thresholds
        private const val SPOOFING_HIGH_THRESHOLD = 70f
        private const val SPOOFING_MEDIUM_THRESHOLD = 40f
        private const val JAMMING_HIGH_THRESHOLD = 70f
        private const val JAMMING_MEDIUM_THRESHOLD = 40f

        // C/N0 thresholds
        private const val CN0_VARIANCE_SUSPICIOUS = 3.0  // dB-Hz - too uniform suggests spoofing
        private const val CN0_DEVIATION_SIGNIFICANT = 2.0  // standard deviations from baseline

        // Geometry thresholds
        private const val GEOMETRY_POOR_THRESHOLD = 0.4f
        private const val LOW_ELEV_HIGH_SIGNAL_THRESHOLD = 2

        // Clock drift thresholds
        private const val DRIFT_JUMP_SUSPICIOUS = 3
        private const val DRIFT_CUMULATIVE_SUSPICIOUS_MS = 10  // milliseconds
    }

    /**
     * Supported detection methods for GNSS handler
     */
    val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.GNSS_SPOOFING,
        DetectionMethod.GNSS_JAMMING,
        DetectionMethod.GNSS_SIGNAL_ANOMALY,
        DetectionMethod.GNSS_GEOMETRY_ANOMALY,
        DetectionMethod.GNSS_SIGNAL_LOSS,
        DetectionMethod.GNSS_CLOCK_ANOMALY,
        DetectionMethod.GNSS_MULTIPATH,
        DetectionMethod.GNSS_CONSTELLATION_ANOMALY
    )

    /**
     * Supported device types for GNSS handler
     */
    fun getSupportedDeviceTypes(): Set<DeviceType> = setOf(
        DeviceType.GNSS_SPOOFER,
        DeviceType.GNSS_JAMMER
    )

    /**
     * Protocol for this handler
     */
    fun getProtocol(): DetectionProtocol = DetectionProtocol.GNSS

    /**
     * Check if this handler can process the given detection method
     */
    fun canHandle(method: DetectionMethod): Boolean {
        return method in supportedMethods
    }

    /**
     * Check if this handler can process the given device type
     */
    fun canHandleDeviceType(deviceType: DeviceType): Boolean {
        return deviceType in getSupportedDeviceTypes()
    }

    /**
     * Handle a GNSS detection context and produce a Detection object.
     * Returns null if the detection should be filtered out.
     */
    fun handleDetection(context: GnssDetectionContext): Detection? {
        Log.d(TAG, "Handling GNSS detection: method=${context.detectionMethod}, " +
            "spoofing=${context.spoofingLikelihood}%, jamming=${context.jammingLikelihood}%")

        val detectionMethod = determineDetectionMethod(context)
        val deviceType = determineDeviceType(context)
        val threatLevel = calculateThreatLevel(context)
        val threatScore = calculateThreatScore(context)

        return Detection(
            id = context.id ?: UUID.randomUUID().toString(),
            timestamp = context.timestamp,
            protocol = DetectionProtocol.GNSS,
            detectionMethod = detectionMethod,
            deviceType = deviceType,
            deviceName = buildDeviceName(context, detectionMethod),
            macAddress = null,  // GNSS doesn't use MAC addresses
            ssid = null,
            rssi = context.avgCn0.toInt(),  // Use C/N0 as signal indicator
            signalStrength = cn0ToSignalStrength(context.avgCn0),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = threatLevel,
            threatScore = threatScore,
            manufacturer = buildConstellationFingerprint(context.constellations),
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = buildMatchedPatterns(context),
            rawData = buildRawData(context),
            isActive = true,
            seenCount = 1,
            lastSeenTimestamp = context.timestamp
        )
    }

    /**
     * Handle a GNSS anomaly from the monitor and convert to Detection
     */
    fun handleAnomaly(
        anomaly: GnssAnomaly,
        analysis: GnssAnomalyAnalysis?
    ): Detection? {
        val context = GnssDetectionContext(
            timestamp = anomaly.timestamp,
            constellations = anomaly.affectedConstellations.toSet(),
            satelliteCount = 0,  // Not available from anomaly
            avgCn0 = analysis?.currentCn0Mean ?: 0.0,
            cn0Deviation = analysis?.cn0DeviationSigmas ?: 0.0,
            cn0Variance = analysis?.cn0Variance ?: 0.0,
            clockDrift = analysis?.cumulativeDriftNs ?: 0L,
            driftTrend = analysis?.driftTrend ?: DriftTrend.STABLE,
            driftJumpCount = analysis?.driftJumpCount ?: 0,
            geometryScore = analysis?.geometryScore ?: 0f,
            elevationDistribution = analysis?.elevationDistribution ?: "Unknown",
            azimuthCoverage = analysis?.azimuthCoverage ?: 0f,
            lowElevHighSignalCount = analysis?.lowElevHighSignalCount ?: 0,
            jammingIndicator = analysis?.jammingLikelihood ?: 0f,
            spoofingLikelihood = analysis?.spoofingLikelihood ?: 0f,
            jammingLikelihood = analysis?.jammingLikelihood ?: 0f,
            spoofingIndicators = analysis?.spoofingIndicators ?: emptyList(),
            jammingIndicators = analysis?.jammingIndicators ?: emptyList(),
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            detectionMethod = anomalyTypeToMethod(anomaly.type),
            rawAnomaly = anomaly,
            rawAnalysis = analysis
        )

        return handleDetection(context)
    }

    /**
     * Build an AI prompt for GNSS detection analysis
     */
    fun generateAiPrompt(context: GnssDetectionContext): String {
        val analysis = context.rawAnalysis
        if (analysis != null) {
            // Use the enriched prompt if we have full analysis data
            val detection = handleDetection(context)
            if (detection != null) {
                return PromptTemplates.buildGnssEnrichedPrompt(detection, analysis)
            }
        }

        // Build a basic prompt without full analysis
        return buildBasicGnssPrompt(context)
    }

    /**
     * Get enriched detector data for AI analysis
     */
    fun getEnrichedData(context: GnssDetectionContext): EnrichedDetectorData? {
        return context.rawAnalysis?.let { EnrichedDetectorData.Gnss(it) }
    }

    /**
     * Calculate spoofing likelihood percentage based on context
     */
    fun calculateSpoofingLikelihood(context: GnssDetectionContext): Float {
        var score = 0f

        // Signal uniformity (too uniform = suspicious)
        if (context.cn0Variance < CN0_VARIANCE_SUSPICIOUS && context.satelliteCount >= 4) {
            score += 25f
        }

        // Low elevation with high signal
        if (context.lowElevHighSignalCount > 0) {
            score += context.lowElevHighSignalCount * 8f
        }

        // Poor geometry
        if (context.geometryScore < GEOMETRY_POOR_THRESHOLD) {
            score += 15f
        }

        // Constellation anomalies
        if (context.constellations.size == 1 && context.satelliteCount >= 6) {
            score += 10f  // Single constellation unusual
        }

        // Clock drift anomalies
        if (context.driftJumpCount > DRIFT_JUMP_SUSPICIOUS) {
            score += 15f
        }
        if (context.driftTrend == DriftTrend.ERRATIC) {
            score += 10f
        }

        // C/N0 deviation from baseline
        if (context.cn0Deviation > CN0_DEVIATION_SIGNIFICANT) {
            score += 10f
        }

        // Use existing spoofing indicators
        score += context.spoofingIndicators.size * 5f

        return score.coerceIn(0f, 100f)
    }

    /**
     * Calculate jamming likelihood percentage based on context
     */
    fun calculateJammingLikelihood(context: GnssDetectionContext): Float {
        var score = 0f

        // Direct jamming indicator
        if (context.jammingIndicator > 50) {
            score += 40f
        } else if (context.jammingIndicator > 25) {
            score += 20f
        }

        // Signal loss indicators
        if (context.satelliteCount < 4) {
            score += 20f
        }

        // C/N0 significantly below baseline
        if (context.cn0Deviation < -CN0_DEVIATION_SIGNIFICANT) {
            score += 25f
        }

        // Use existing jamming indicators
        score += context.jammingIndicators.size * 10f

        return score.coerceIn(0f, 100f)
    }

    /**
     * Perform constellation fingerprinting
     */
    fun fingerprintConstellations(context: GnssDetectionContext): ConstellationFingerprint {
        val observed = context.constellations
        val expected = getExpectedConstellations(context.latitude, context.longitude)

        val missing = expected - observed
        val unexpected = observed - expected - setOf(ConstellationType.UNKNOWN)

        val matchScore = if (expected.isNotEmpty()) {
            ((observed.intersect(expected).size.toFloat() / expected.size) * 100).toInt()
        } else 100

        return ConstellationFingerprint(
            observed = observed,
            expected = expected,
            missing = missing,
            unexpected = unexpected,
            matchScore = matchScore,
            isSuspicious = matchScore < 50 || unexpected.isNotEmpty()
        )
    }

    // ==================== Private Helper Methods ====================

    private fun determineDetectionMethod(context: GnssDetectionContext): DetectionMethod {
        // Use explicit method if provided
        context.detectionMethod?.let { return it }

        // Otherwise determine from context
        val spoofing = context.spoofingLikelihood
        val jamming = context.jammingLikelihood

        return when {
            spoofing >= SPOOFING_HIGH_THRESHOLD -> DetectionMethod.GNSS_SPOOFING
            jamming >= JAMMING_HIGH_THRESHOLD -> DetectionMethod.GNSS_JAMMING
            context.lowElevHighSignalCount > LOW_ELEV_HIGH_SIGNAL_THRESHOLD -> DetectionMethod.GNSS_GEOMETRY_ANOMALY
            context.driftTrend == DriftTrend.ERRATIC || context.driftJumpCount > DRIFT_JUMP_SUSPICIOUS -> DetectionMethod.GNSS_CLOCK_ANOMALY
            context.cn0Variance < CN0_VARIANCE_SUSPICIOUS -> DetectionMethod.GNSS_SIGNAL_ANOMALY
            context.satelliteCount < 4 && jamming > JAMMING_MEDIUM_THRESHOLD -> DetectionMethod.GNSS_SIGNAL_LOSS
            context.constellations.size == 1 && context.satelliteCount >= 6 -> DetectionMethod.GNSS_CONSTELLATION_ANOMALY
            spoofing >= SPOOFING_MEDIUM_THRESHOLD -> DetectionMethod.GNSS_SPOOFING
            jamming >= JAMMING_MEDIUM_THRESHOLD -> DetectionMethod.GNSS_JAMMING
            else -> DetectionMethod.GNSS_SIGNAL_ANOMALY
        }
    }

    private fun determineDeviceType(context: GnssDetectionContext): DeviceType {
        val spoofing = context.spoofingLikelihood
        val jamming = context.jammingLikelihood

        return when {
            jamming > spoofing && jamming >= JAMMING_MEDIUM_THRESHOLD -> DeviceType.GNSS_JAMMER
            spoofing >= SPOOFING_MEDIUM_THRESHOLD -> DeviceType.GNSS_SPOOFER
            context.satelliteCount < 4 -> DeviceType.GNSS_JAMMER  // Signal loss suggests jamming
            else -> DeviceType.GNSS_SPOOFER  // Default to spoofer for other anomalies
        }
    }

    private fun calculateThreatLevel(context: GnssDetectionContext): ThreatLevel {
        val spoofing = context.spoofingLikelihood
        val jamming = context.jammingLikelihood
        val maxLikelihood = maxOf(spoofing, jamming)

        return when {
            maxLikelihood >= 80 -> ThreatLevel.CRITICAL
            maxLikelihood >= 60 -> ThreatLevel.HIGH
            maxLikelihood >= 40 -> ThreatLevel.MEDIUM
            maxLikelihood >= 20 -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }
    }

    private fun calculateThreatScore(context: GnssDetectionContext): Int {
        var score = 0

        // Base score from likelihood
        score += (context.spoofingLikelihood * 0.4f).toInt()
        score += (context.jammingLikelihood * 0.4f).toInt()

        // Geometry issues
        if (context.geometryScore < GEOMETRY_POOR_THRESHOLD) {
            score += 10
        }
        if (context.lowElevHighSignalCount > 0) {
            score += context.lowElevHighSignalCount * 5
        }

        // Clock anomalies
        if (context.driftTrend == DriftTrend.ERRATIC) {
            score += 10
        }
        if (context.driftJumpCount > DRIFT_JUMP_SUSPICIOUS) {
            score += 5
        }

        // Signal anomalies
        if (context.cn0Variance < CN0_VARIANCE_SUSPICIOUS && context.satelliteCount >= 4) {
            score += 10
        }

        return score.coerceIn(0, 100)
    }

    private fun buildDeviceName(context: GnssDetectionContext, method: DetectionMethod): String {
        val emoji = when (method) {
            DetectionMethod.GNSS_SPOOFING -> "\uD83C\uDFAF"  // Target emoji
            DetectionMethod.GNSS_JAMMING -> "\uD83D\uDCF5"  // No mobile phones emoji
            DetectionMethod.GNSS_SIGNAL_ANOMALY -> "\uD83D\uDCC8"  // Chart emoji
            DetectionMethod.GNSS_GEOMETRY_ANOMALY -> "\uD83D\uDEF0\uFE0F"  // Satellite emoji
            DetectionMethod.GNSS_SIGNAL_LOSS -> "\uD83D\uDCC9"  // Chart decreasing emoji
            DetectionMethod.GNSS_CLOCK_ANOMALY -> "\u23F0"  // Alarm clock emoji
            DetectionMethod.GNSS_MULTIPATH -> "\uD83D\uDD00"  // Shuffle emoji
            DetectionMethod.GNSS_CONSTELLATION_ANOMALY -> "\u274C"  // Cross mark emoji
            else -> "\uD83D\uDEF0\uFE0F"  // Default satellite emoji
        }

        val likelihood = maxOf(context.spoofingLikelihood, context.jammingLikelihood)
        return "$emoji ${method.displayName} (${String.format("%.0f", likelihood)}%)"
    }

    private fun buildConstellationFingerprint(constellations: Set<ConstellationType>): String {
        return constellations
            .filter { it != ConstellationType.UNKNOWN }
            .sortedBy { it.ordinal }
            .joinToString(", ") { it.displayName }
            .ifEmpty { "Unknown" }
    }

    private fun buildMatchedPatterns(context: GnssDetectionContext): String {
        val patterns = mutableListOf<String>()

        // Add spoofing indicators
        patterns.addAll(context.spoofingIndicators)

        // Add jamming indicators
        patterns.addAll(context.jammingIndicators)

        // Add geometry issues
        if (context.geometryScore < GEOMETRY_POOR_THRESHOLD) {
            patterns.add("Poor satellite geometry (${String.format("%.0f", context.geometryScore * 100)}%)")
        }
        if (context.lowElevHighSignalCount > 0) {
            patterns.add("${context.lowElevHighSignalCount} low-elevation high-signal satellites")
        }

        // Add clock drift issues
        if (context.driftTrend != DriftTrend.STABLE) {
            patterns.add("Clock drift: ${context.driftTrend.displayName}")
        }
        if (context.driftJumpCount > 0) {
            patterns.add("${context.driftJumpCount} clock drift jumps")
        }

        // Add C/N0 issues
        if (context.cn0Variance < CN0_VARIANCE_SUSPICIOUS && context.satelliteCount >= 4) {
            patterns.add("Signal uniformity suspicious (variance: ${String.format("%.2f", context.cn0Variance)})")
        }
        if (abs(context.cn0Deviation) > CN0_DEVIATION_SIGNIFICANT) {
            patterns.add("C/N0 ${String.format("%.1f", context.cn0Deviation)} sigma from baseline")
        }

        return patterns.joinToString(", ")
    }

    private fun buildRawData(context: GnssDetectionContext): String {
        return buildString {
            appendLine("=== GNSS Detection Context ===")
            appendLine("Satellites: ${context.satelliteCount}")
            appendLine("Constellations: ${context.constellations.joinToString { it.code }}")
            appendLine("Avg C/N0: ${String.format("%.1f", context.avgCn0)} dB-Hz")
            appendLine("C/N0 Deviation: ${String.format("%.2f", context.cn0Deviation)} sigma")
            appendLine("C/N0 Variance: ${String.format("%.2f", context.cn0Variance)}")
            appendLine("Geometry Score: ${String.format("%.0f", context.geometryScore * 100)}%")
            appendLine("Elevation Dist: ${context.elevationDistribution}")
            appendLine("Azimuth Coverage: ${String.format("%.0f", context.azimuthCoverage)}%")
            appendLine("Low-Elev High-Signal: ${context.lowElevHighSignalCount}")
            appendLine("Clock Drift: ${context.clockDrift / 1_000_000} ms")
            appendLine("Drift Trend: ${context.driftTrend.displayName}")
            appendLine("Drift Jumps: ${context.driftJumpCount}")
            appendLine("Spoofing Likelihood: ${String.format("%.0f", context.spoofingLikelihood)}%")
            appendLine("Jamming Likelihood: ${String.format("%.0f", context.jammingLikelihood)}%")
        }
    }

    private fun cn0ToSignalStrength(cn0: Double): SignalStrength {
        return when {
            cn0 > 45 -> SignalStrength.EXCELLENT
            cn0 > 35 -> SignalStrength.GOOD
            cn0 > 25 -> SignalStrength.MEDIUM
            cn0 > 15 -> SignalStrength.WEAK
            cn0 > 0 -> SignalStrength.VERY_WEAK
            else -> SignalStrength.UNKNOWN
        }
    }

    private fun anomalyTypeToMethod(type: GnssAnomalyType): DetectionMethod {
        return when (type) {
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
    }

    private fun getExpectedConstellations(lat: Double?, lon: Double?): Set<ConstellationType> {
        // GPS and GLONASS are globally available
        val expected = mutableSetOf(
            ConstellationType.GPS,
            ConstellationType.GLONASS,
            ConstellationType.GALILEO  // EU system, globally available
        )

        if (lat != null && lon != null) {
            // BeiDou has better coverage in Asia-Pacific
            if (lon > 70 && lon < 180) {
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

        // SBAS is generally expected in most regions
        expected.add(ConstellationType.SBAS)

        return expected
    }

    private fun buildBasicGnssPrompt(context: GnssDetectionContext): String {
        return buildString {
            appendLine("Analyze this GNSS (GPS/satellite) anomaly detection.")
            appendLine()
            appendLine("=== SPOOFING/JAMMING LIKELIHOOD ===")
            appendLine("Spoofing Likelihood: ${String.format("%.0f", context.spoofingLikelihood)}%")
            appendLine("Jamming Likelihood: ${String.format("%.0f", context.jammingLikelihood)}%")
            appendLine()
            appendLine("=== CONSTELLATION ANALYSIS ===")
            appendLine("Observed: ${context.constellations.joinToString { it.code }}")
            appendLine("Satellite Count: ${context.satelliteCount}")
            appendLine()
            appendLine("=== SIGNAL ANALYSIS ===")
            appendLine("C/N0: ${String.format("%.1f", context.avgCn0)} dB-Hz")
            appendLine("Deviation: ${String.format("%.1f", context.cn0Deviation)} sigma")
            appendLine("Variance: ${String.format("%.2f", context.cn0Variance)}")
            appendLine()
            appendLine("=== GEOMETRY ===")
            appendLine("Score: ${String.format("%.0f", context.geometryScore * 100)}%")
            appendLine("Distribution: ${context.elevationDistribution}")
            appendLine("Low-Elev High-Signal: ${context.lowElevHighSignalCount}")
            appendLine()
            appendLine("=== CLOCK ===")
            appendLine("Drift: ${context.clockDrift / 1_000_000} ms")
            appendLine("Trend: ${context.driftTrend.displayName}")
            appendLine("Jumps: ${context.driftJumpCount}")
            appendLine()
            appendLine("Provide assessment of whether GPS is being spoofed or jammed.")
        }
    }
}

/**
 * Context data for GNSS detection handling.
 * Contains all the analysis data needed to make detection decisions.
 */
data class GnssDetectionContext(
    // Identification
    val id: String? = null,
    val timestamp: Long = System.currentTimeMillis(),

    // Constellation data
    val constellations: Set<ConstellationType>,
    val satelliteCount: Int,

    // C/N0 (signal-to-noise) analysis
    val avgCn0: Double,
    val cn0Deviation: Double,  // Standard deviations from baseline
    val cn0Variance: Double,

    // Clock drift analysis
    val clockDrift: Long,  // Cumulative drift in nanoseconds
    val driftTrend: DriftTrend,
    val driftJumpCount: Int,

    // Geometry analysis
    val geometryScore: Float,  // 0-1.0, higher is better
    val elevationDistribution: String,
    val azimuthCoverage: Float,  // 0-100%
    val lowElevHighSignalCount: Int,

    // Jamming indicator
    val jammingIndicator: Float,  // 0-100%

    // Calculated likelihoods
    val spoofingLikelihood: Float,  // 0-100%
    val jammingLikelihood: Float,  // 0-100%

    // Contributing factors
    val spoofingIndicators: List<String>,
    val jammingIndicators: List<String>,

    // Location
    val latitude: Double? = null,
    val longitude: Double? = null,

    // Detection method override (if known from anomaly)
    val detectionMethod: DetectionMethod? = null,

    // Raw data for AI prompts
    val rawAnomaly: GnssAnomaly? = null,
    val rawAnalysis: GnssAnomalyAnalysis? = null
)

/**
 * Constellation fingerprint result
 */
data class ConstellationFingerprint(
    val observed: Set<ConstellationType>,
    val expected: Set<ConstellationType>,
    val missing: Set<ConstellationType>,
    val unexpected: Set<ConstellationType>,
    val matchScore: Int,  // 0-100%
    val isSuspicious: Boolean
)

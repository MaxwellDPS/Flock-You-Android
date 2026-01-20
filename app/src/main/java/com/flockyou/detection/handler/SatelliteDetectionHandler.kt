package com.flockyou.detection.handler

import android.util.Log
import com.flockyou.ai.EnrichedDetectorData
import com.flockyou.data.DetectionSettingsRepository
import com.flockyou.data.SatellitePattern
import com.flockyou.data.SatelliteThresholds
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.SignalStrength
import com.flockyou.data.model.ThreatLevel
import com.flockyou.monitoring.SatelliteDetectionHeuristics
import com.flockyou.monitoring.SatelliteMonitor
import com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity
import com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly
import com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType
import com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionType
import com.flockyou.monitoring.SatelliteMonitor.SatelliteProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detection context for satellite/NTN anomalies.
 * Contains all relevant information about the satellite connection state
 * when an anomaly was detected.
 */
data class SatelliteDetectionContext(
    /** Unique identifier for this detection context */
    val id: String? = null,

    /** Timestamp of the detection */
    val timestamp: Long = System.currentTimeMillis(),

    /** The detected anomaly */
    val anomaly: SatelliteAnomaly,

    /** The type of network connection (e.g., "5G", "LTE", "Satellite", "T-Mobile Starlink") */
    val networkType: String,

    /** Satellite identifier if available (e.g., Starlink satellite ID) */
    val satelliteId: String? = null,

    /** NTN parameters from the connection */
    val ntnParameters: NtnParameters? = null,

    /** Timing advance value in microseconds (for timing anomaly detection) */
    val timingAdvance: Long? = null,

    /** Signal strength in dBm */
    val signalStrength: Int? = null,

    /** Whether terrestrial coverage is available at this location */
    val hasTerrestrialCoverage: Boolean = false,

    /** Last known terrestrial signal strength in dBm */
    val lastTerrestrialSignalDbm: Int? = null,

    /** Time since last good terrestrial signal in milliseconds */
    val timeSinceGoodTerrestrialMs: Long? = null,

    /** The satellite provider if identified */
    val provider: SatelliteProvider = SatelliteProvider.UNKNOWN,

    /** Connection type classification */
    val connectionType: SatelliteConnectionType = SatelliteConnectionType.NONE,

    /** Frequency in MHz if available */
    val frequencyMHz: Int? = null,

    /** Whether frequency is in valid NTN band */
    val isValidNtnBand: Boolean = true,

    /** Recent handoff count (for rapid switching detection) */
    val recentHandoffCount: Int = 0,

    /** Location information */
    val latitude: Double? = null,
    val longitude: Double? = null,

    /** Whether this is an urban area with expected terrestrial coverage */
    val isUrbanArea: Boolean = false,

    /** The detection method to use */
    val detectionMethod: DetectionMethod = DetectionMethod.SAT_UNEXPECTED_CONNECTION
)

/**
 * NTN (Non-Terrestrial Network) parameters for satellite connections.
 * Based on 3GPP Release 17 NTN specifications.
 */
data class NtnParameters(
    /** Radio access technology (NB-IoT NTN, NR-NTN, eMTC-NTN, Proprietary) */
    val radioTechnology: Int = SatelliteMonitor.Companion.NTRadioTechnology.UNKNOWN,

    /** Orbital type (LEO, MEO, GEO) */
    val orbitalType: OrbitalType = OrbitalType.UNKNOWN,

    /** Expected round-trip time based on orbit in milliseconds */
    val expectedRttMs: Long? = null,

    /** Measured round-trip time in milliseconds */
    val measuredRttMs: Long? = null,

    /** HARQ process count (NTN uses up to 32 vs 16 for terrestrial) */
    val harqProcessCount: Int? = null,

    /** Channel bandwidth in MHz */
    val channelBandwidthMHz: Int? = null,

    /** Whether beamforming is active */
    val beamformingActive: Boolean = false,

    /** NTN band identifier (e.g., n253, n254, n255, n256) */
    val ntnBand: String? = null
)

/**
 * Orbital types for satellite classification.
 */
enum class OrbitalType(val displayName: String, val minRttMs: Long, val maxRttMs: Long) {
    LEO("Low Earth Orbit", 20, 80),
    MEO("Medium Earth Orbit", 80, 200),
    GEO("Geostationary Orbit", 200, 600),
    UNKNOWN("Unknown", 0, Long.MAX_VALUE)
}

/**
 * Handler for satellite/NTN detection anomalies.
 *
 * Converts SatelliteMonitor.SatelliteAnomaly events to Detection objects
 * with appropriate threat levels, device types, and AI analysis prompts.
 *
 * Supports detection methods:
 * - SAT_UNEXPECTED_CONNECTION: Satellite connection when terrestrial available
 * - SAT_FORCED_HANDOFF: Suspicious handoff to satellite
 * - SAT_SUSPICIOUS_NTN: Unusual NTN parameters suggesting spoofing
 * - SAT_TIMING_ANOMALY: Timing doesn't match claimed orbit
 * - SAT_DOWNGRADE: Forced from better technology to satellite
 *
 * Device types:
 * - SATELLITE_NTN: Standard satellite NTN device
 * - STINGRAY_IMSI: Cell site simulator using satellite-based interception
 */
@Singleton
class SatelliteDetectionHandler @Inject constructor(
    private val detectionSettingsRepository: DetectionSettingsRepository
) {

    companion object {
        private const val TAG = "SatelliteDetectionHandler"
    }

    /**
     * Supported detection methods for satellite handler
     */
    val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.SAT_UNEXPECTED_CONNECTION,
        DetectionMethod.SAT_FORCED_HANDOFF,
        DetectionMethod.SAT_SUSPICIOUS_NTN,
        DetectionMethod.SAT_TIMING_ANOMALY,
        DetectionMethod.SAT_DOWNGRADE
    )

    /**
     * Supported device types for satellite handler
     */
    val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.SATELLITE_NTN,
        DeviceType.STINGRAY_IMSI
    )

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
        return deviceType in supportedDeviceTypes
    }

    /**
     * Handle a satellite detection context and produce a Detection object.
     */
    fun handle(context: SatelliteDetectionContext): Detection {
        Log.d(TAG, "Handling satellite detection: method=${context.detectionMethod}, " +
            "provider=${context.provider}, network=${context.networkType}")

        val detectionMethod = determineDetectionMethod(context)
        val deviceType = determineDeviceType(context)
        val threatLevel = calculateThreatLevel(context)
        val threatScore = calculateThreatScore(context)

        return Detection(
            id = context.id ?: UUID.randomUUID().toString(),
            timestamp = context.timestamp,
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = detectionMethod,
            deviceType = deviceType,
            deviceName = buildDeviceName(context, detectionMethod),
            macAddress = null, // Satellites don't have MAC addresses
            ssid = context.networkType,
            rssi = context.signalStrength ?: -80,
            signalStrength = signalDbmToStrength(context.signalStrength),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = threatLevel,
            threatScore = threatScore,
            manufacturer = formatProviderName(context.provider),
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
     * Handle a satellite anomaly from the monitor.
     * Checks settings and thresholds before converting to Detection.
     */
    suspend fun handleAnomaly(
        anomaly: SatelliteAnomaly,
        connectionState: SatelliteMonitor.SatelliteConnectionState,
        lastTerrestrialSignalDbm: Int? = null,
        timeSinceGoodTerrestrialMs: Long? = null,
        recentHandoffCount: Int = 0,
        latitude: Double? = null,
        longitude: Double? = null,
        isUrbanArea: Boolean = false
    ): Detection? {
        val settings = detectionSettingsRepository.settings.first()

        // Check if satellite detection is enabled globally
        if (!settings.enableSatelliteDetection) {
            Log.d(TAG, "Satellite detection disabled globally")
            return null
        }

        // Check if the specific pattern is enabled
        val pattern = mapAnomalyTypeToPattern(anomaly.type)
        if (pattern != null && pattern !in settings.enabledSatellitePatterns) {
            Log.d(TAG, "Pattern ${pattern.name} is disabled")
            return null
        }

        // Check severity threshold - only HIGH and CRITICAL generate detections by default
        if (anomaly.severity !in listOf(AnomalySeverity.HIGH, AnomalySeverity.CRITICAL)) {
            // For MEDIUM severity, check if it meets threshold criteria
            if (anomaly.severity == AnomalySeverity.MEDIUM) {
                if (!meetsThresholds(
                    anomaly,
                    lastTerrestrialSignalDbm,
                    timeSinceGoodTerrestrialMs,
                    recentHandoffCount,
                    settings.satelliteThresholds
                )) {
                    Log.d(TAG, "Anomaly doesn't meet thresholds: ${anomaly.type}")
                    return null
                }
            } else {
                Log.d(TAG, "Anomaly severity too low: ${anomaly.severity}")
                return null
            }
        }

        val context = createContext(
            anomaly = anomaly,
            connectionState = connectionState,
            lastTerrestrialSignalDbm = lastTerrestrialSignalDbm,
            timeSinceGoodTerrestrialMs = timeSinceGoodTerrestrialMs,
            recentHandoffCount = recentHandoffCount,
            latitude = latitude,
            longitude = longitude,
            isUrbanArea = isUrbanArea
        )

        return handle(context)
    }

    /**
     * Build an AI prompt for analyzing the detection context
     */
    fun buildAiPrompt(context: SatelliteDetectionContext): String {
        val anomaly = context.anomaly

        val promptBuilder = StringBuilder()

        promptBuilder.appendLine("Analyze this satellite network anomaly for potential surveillance activity:")
        promptBuilder.appendLine()
        promptBuilder.appendLine("## Anomaly Details")
        promptBuilder.appendLine("- Type: ${anomaly.type.name}")
        promptBuilder.appendLine("- Severity: ${anomaly.severity.name}")
        promptBuilder.appendLine("- Description: ${anomaly.description}")
        promptBuilder.appendLine("- Detection Method: ${context.detectionMethod.displayName}")
        promptBuilder.appendLine()

        promptBuilder.appendLine("## Network Context")
        promptBuilder.appendLine("- Network Type: ${context.networkType}")
        promptBuilder.appendLine("- Provider: ${formatProviderName(context.provider)}")
        promptBuilder.appendLine("- Connection Type: ${context.connectionType.name}")
        context.signalStrength?.let { promptBuilder.appendLine("- Signal Strength: $it dBm") }
        promptBuilder.appendLine("- Terrestrial Coverage Available: ${context.hasTerrestrialCoverage}")
        context.lastTerrestrialSignalDbm?.let { promptBuilder.appendLine("- Last Terrestrial Signal: $it dBm") }
        promptBuilder.appendLine("- Urban Area: ${context.isUrbanArea}")
        promptBuilder.appendLine()

        // NTN-specific analysis
        context.ntnParameters?.let { ntn ->
            promptBuilder.appendLine("## NTN Parameters")
            promptBuilder.appendLine("- Orbital Type: ${ntn.orbitalType.displayName}")
            ntn.ntnBand?.let { promptBuilder.appendLine("- NTN Band: $it") }
            ntn.expectedRttMs?.let { promptBuilder.appendLine("- Expected RTT: ${it}ms") }
            ntn.measuredRttMs?.let { promptBuilder.appendLine("- Measured RTT: ${it}ms") }
            ntn.harqProcessCount?.let { promptBuilder.appendLine("- HARQ Processes: $it") }
            promptBuilder.appendLine("- Beamforming: ${if (ntn.beamformingActive) "Active" else "Inactive"}")
            promptBuilder.appendLine()

            // Timing analysis if available
            if (ntn.measuredRttMs != null) {
                promptBuilder.appendLine("## Timing Analysis")
                val timingAnalysis = SatelliteDetectionHeuristics.SurveillanceHeuristics.TimingHeuristics.analyzeRTT(
                    ntn.measuredRttMs,
                    ntn.orbitalType.name
                )
                promptBuilder.appendLine("- Analysis: $timingAnalysis")

                if (ntn.measuredRttMs < 10) {
                    promptBuilder.appendLine("- WARNING: RTT of ${ntn.measuredRttMs}ms is suspiciously low for satellite communication")
                    promptBuilder.appendLine("- This timing is more consistent with ground-based equipment")
                }
                promptBuilder.appendLine()
            }
        }

        // Frequency analysis
        context.frequencyMHz?.let { freq ->
            promptBuilder.appendLine("## Frequency Analysis")
            promptBuilder.appendLine("- Frequency: ${freq}MHz")
            promptBuilder.appendLine("- Valid NTN Band: ${context.isValidNtnBand}")
            if (!context.isValidNtnBand) {
                promptBuilder.appendLine("- WARNING: Frequency is outside standard NTN bands (L-band: 1525-1660MHz, S-band: 1980-2200MHz)")
            }
            val matchingBands = SatelliteDetectionHeuristics.NTNBands.getAllBandsForFrequency(freq)
            if (matchingBands.isNotEmpty()) {
                promptBuilder.appendLine("- Matching 3GPP NTN Bands: ${matchingBands.joinToString(", ")}")
            }
            promptBuilder.appendLine()
        }

        // Switching pattern analysis
        if (context.recentHandoffCount > 0) {
            promptBuilder.appendLine("## Switching Pattern")
            promptBuilder.appendLine("- Recent Handoff Count: ${context.recentHandoffCount}")
            val switchingAnalysis = SatelliteDetectionHeuristics.SurveillanceHeuristics.SwitchingHeuristics.analyzeSwitchingPattern(
                (0 until context.recentHandoffCount).map { context.timestamp - it * 30000L }
            )
            promptBuilder.appendLine("- Pattern Analysis: $switchingAnalysis")
            promptBuilder.appendLine()
        }

        promptBuilder.appendLine("## Analysis Questions")
        promptBuilder.appendLine("1. Is this anomaly consistent with legitimate satellite connectivity or potential surveillance?")
        promptBuilder.appendLine("2. What specific indicators suggest this could be a cell site simulator?")
        promptBuilder.appendLine("3. What actions should the user take to protect their privacy?")
        promptBuilder.appendLine()

        if (anomaly.recommendations.isNotEmpty()) {
            promptBuilder.appendLine("## Existing Recommendations")
            anomaly.recommendations.forEachIndexed { index, rec ->
                promptBuilder.appendLine("${index + 1}. $rec")
            }
        }

        return promptBuilder.toString()
    }

    /**
     * Get enriched detector data for AI analysis
     */
    fun getEnrichedData(context: SatelliteDetectionContext): EnrichedDetectorData? {
        val ntnParams = context.ntnParameters

        // Build NTN-specific enriched data
        val metadata = buildMap {
            put("networkType", context.networkType)
            put("provider", formatProviderName(context.provider))
            put("connectionType", context.connectionType.name)
            put("hasTerrestrialCoverage", context.hasTerrestrialCoverage.toString())
            put("isUrbanArea", context.isUrbanArea.toString())
            context.signalStrength?.let { put("signalStrength", "${it}dBm") }
            context.frequencyMHz?.let { put("frequency", "${it}MHz") }
            put("isValidNtnBand", context.isValidNtnBand.toString())

            ntnParams?.let { ntn ->
                put("orbitalType", ntn.orbitalType.displayName)
                ntn.expectedRttMs?.let { put("expectedRtt", "${it}ms") }
                ntn.measuredRttMs?.let { put("measuredRtt", "${it}ms") }
                ntn.ntnBand?.let { put("ntnBand", it) }
                put("beamforming", ntn.beamformingActive.toString())
            }
        }

        val riskIndicators = mutableListOf<String>()

        // Analyze risk indicators
        if (!context.isValidNtnBand) {
            riskIndicators.add("Invalid NTN frequency band")
        }
        if (context.hasTerrestrialCoverage && context.isUrbanArea) {
            riskIndicators.add("Satellite used in urban area with terrestrial coverage")
        }
        if (context.provider == SatelliteProvider.UNKNOWN) {
            riskIndicators.add("Unknown satellite provider")
        }
        ntnParams?.let { ntn ->
            if (ntn.measuredRttMs != null && ntn.measuredRttMs < 10) {
                riskIndicators.add("RTT too low for satellite (suggests ground-based spoofing)")
            }
            if (ntn.measuredRttMs != null && ntn.expectedRttMs != null) {
                val diff = kotlin.math.abs(ntn.measuredRttMs - ntn.expectedRttMs)
                if (diff > 50) {
                    riskIndicators.add("Significant RTT deviation from expected (${diff}ms)")
                }
            }
        }
        if (context.recentHandoffCount > 5) {
            riskIndicators.add("Rapid satellite switching detected (${context.recentHandoffCount} handoffs)")
        }

        return EnrichedDetectorData.Satellite(
            detectorType = "Satellite/NTN",
            metadata = metadata,
            signalCharacteristics = mapOf(
                "signalStrength" to (context.signalStrength?.toString() ?: "unknown"),
                "frequency" to (context.frequencyMHz?.toString() ?: "unknown"),
                "ntnBand" to (ntnParams?.ntnBand ?: "unknown")
            ),
            riskIndicators = riskIndicators,
            timestamp = context.timestamp
        )
    }

    // ========================================================================
    // Private Implementation Methods
    // ========================================================================

    /**
     * Determine the detection method from context.
     */
    private fun determineDetectionMethod(context: SatelliteDetectionContext): DetectionMethod {
        return mapAnomalyTypeToDetectionMethod(context.anomaly.type)
    }

    /**
     * Map anomaly type to SatellitePattern enum.
     */
    private fun mapAnomalyTypeToPattern(type: SatelliteAnomalyType): SatellitePattern? {
        return when (type) {
            SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION -> SatellitePattern.UNEXPECTED_SATELLITE
            SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF -> SatellitePattern.FORCED_HANDOFF
            SatelliteAnomalyType.SUSPICIOUS_NTN_PARAMETERS -> SatellitePattern.SUSPICIOUS_NTN_PARAMS
            SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK -> SatellitePattern.UNKNOWN_SATELLITE_NETWORK
            SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA -> SatellitePattern.SATELLITE_IN_COVERAGE
            SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING -> SatellitePattern.RAPID_SATELLITE_SWITCHING
            SatelliteAnomalyType.NTN_BAND_MISMATCH -> SatellitePattern.NTN_BAND_MISMATCH
            SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY -> SatellitePattern.TIMING_ANOMALY
            SatelliteAnomalyType.EPHEMERIS_MISMATCH -> SatellitePattern.TIMING_ANOMALY
            SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE -> SatellitePattern.DOWNGRADE_TO_SATELLITE
        }
    }

    /**
     * Map anomaly type to DetectionMethod enum.
     */
    private fun mapAnomalyTypeToDetectionMethod(type: SatelliteAnomalyType): DetectionMethod {
        return when (type) {
            SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
            SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF -> DetectionMethod.SAT_FORCED_HANDOFF
            SatelliteAnomalyType.SUSPICIOUS_NTN_PARAMETERS -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
            SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING -> DetectionMethod.SAT_FORCED_HANDOFF
            SatelliteAnomalyType.NTN_BAND_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.EPHEMERIS_MISMATCH -> DetectionMethod.SAT_TIMING_ANOMALY
            SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE -> DetectionMethod.SAT_DOWNGRADE
        }
    }

    /**
     * Determine the device type based on the anomaly context.
     * STINGRAY_IMSI is used when the anomaly suggests a cell site simulator
     * is using satellite-based interception techniques.
     */
    private fun determineDeviceType(context: SatelliteDetectionContext): DeviceType {
        val anomaly = context.anomaly

        // Indicators of cell site simulator activity via satellite
        val stingrayIndicators = listOf(
            // Unknown satellite network could be a fake satellite
            anomaly.type == SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK,
            // NTN band mismatch suggests spoofing
            anomaly.type == SatelliteAnomalyType.NTN_BAND_MISMATCH,
            // Timing anomaly with ground-level RTT suggests ground-based spoofing
            anomaly.type == SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY &&
                context.ntnParameters?.measuredRttMs?.let { it < 10 } == true,
            // Critical severity with forced handoff
            anomaly.severity == AnomalySeverity.CRITICAL &&
                anomaly.type == SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF,
            // Downgrade in urban area with good terrestrial signal
            anomaly.type == SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE &&
                context.isUrbanArea &&
                (context.lastTerrestrialSignalDbm ?: -120) > -90
        )

        return if (stingrayIndicators.any { it }) {
            DeviceType.STINGRAY_IMSI
        } else {
            DeviceType.SATELLITE_NTN
        }
    }

    /**
     * Calculate threat level based on anomaly severity and context.
     */
    private fun calculateThreatLevel(context: SatelliteDetectionContext): ThreatLevel {
        val severity = context.anomaly.severity
        val deviceType = determineDeviceType(context)

        // If identified as a StingRay, increase threat level
        if (deviceType == DeviceType.STINGRAY_IMSI) {
            return when (severity) {
                AnomalySeverity.CRITICAL -> ThreatLevel.CRITICAL
                AnomalySeverity.HIGH -> ThreatLevel.CRITICAL
                AnomalySeverity.MEDIUM -> ThreatLevel.HIGH
                AnomalySeverity.LOW -> ThreatLevel.MEDIUM
                AnomalySeverity.INFO -> ThreatLevel.LOW
            }
        }

        return when (severity) {
            AnomalySeverity.CRITICAL -> ThreatLevel.CRITICAL
            AnomalySeverity.HIGH -> ThreatLevel.HIGH
            AnomalySeverity.MEDIUM -> ThreatLevel.MEDIUM
            AnomalySeverity.LOW -> ThreatLevel.LOW
            AnomalySeverity.INFO -> ThreatLevel.INFO
        }
    }

    /**
     * Calculate a threat score based on multiple factors.
     */
    private fun calculateThreatScore(context: SatelliteDetectionContext): Int {
        val anomaly = context.anomaly

        var score = when (anomaly.severity) {
            AnomalySeverity.CRITICAL -> 90
            AnomalySeverity.HIGH -> 75
            AnomalySeverity.MEDIUM -> 55
            AnomalySeverity.LOW -> 35
            AnomalySeverity.INFO -> 15
        }

        // Increase score for suspicious indicators
        if (context.hasTerrestrialCoverage &&
            (context.lastTerrestrialSignalDbm ?: -120) > -90) {
            score += 10 // Good terrestrial signal available
        }

        if (context.isUrbanArea) {
            score += 5 // Urban area with expected coverage
        }

        if (!context.isValidNtnBand) {
            score += 15 // Invalid NTN frequency band
        }

        if (context.provider == SatelliteProvider.UNKNOWN) {
            score += 10 // Unknown provider
        }

        // Timing anomaly analysis
        context.ntnParameters?.let { ntn ->
            if (ntn.measuredRttMs != null && ntn.expectedRttMs != null) {
                val rttDiff = kotlin.math.abs(ntn.measuredRttMs - ntn.expectedRttMs)
                if (rttDiff > 50) {
                    score += 15 // Significant RTT mismatch
                }
            }
            // Ground-level RTT on claimed satellite
            if (ntn.measuredRttMs != null && ntn.measuredRttMs < 10) {
                score += 20 // Suspiciously low RTT
            }
        }

        // Rapid switching
        if (context.recentHandoffCount > 3) {
            score += 5 * (context.recentHandoffCount - 3).coerceAtMost(4)
        }

        return score.coerceIn(0, 100)
    }

    /**
     * Check if the anomaly meets configurable thresholds.
     */
    private fun meetsThresholds(
        anomaly: SatelliteAnomaly,
        lastTerrestrialSignalDbm: Int?,
        timeSinceGoodTerrestrialMs: Long?,
        recentHandoffCount: Int,
        thresholds: SatelliteThresholds
    ): Boolean {
        return when (anomaly.type) {
            SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION,
            SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA -> {
                // Check if terrestrial signal was good enough
                val signalOk = (lastTerrestrialSignalDbm ?: -120) > thresholds.minSignalForTerrestrial
                val timeOk = (timeSinceGoodTerrestrialMs ?: Long.MAX_VALUE) < thresholds.unexpectedSatelliteThresholdMs
                signalOk && timeOk
            }

            SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF -> {
                (timeSinceGoodTerrestrialMs ?: Long.MAX_VALUE) < thresholds.rapidHandoffThresholdMs
            }

            SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING -> {
                recentHandoffCount >= thresholds.rapidSwitchingCount
            }

            else -> true // Other types don't have specific thresholds
        }
    }

    /**
     * Build a user-friendly device name for the detection.
     */
    private fun buildDeviceName(context: SatelliteDetectionContext, method: DetectionMethod): String {
        val emoji = when {
            determineDeviceType(context) == DeviceType.STINGRAY_IMSI -> "📶"
            else -> "🛰️"
        }

        val typeName = when (context.anomaly.type) {
            SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION -> "Unexpected Satellite"
            SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF -> "Forced Satellite Handoff"
            SatelliteAnomalyType.SUSPICIOUS_NTN_PARAMETERS -> "Suspicious NTN"
            SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK -> "Unknown Satellite Network"
            SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA -> "Satellite in Coverage Area"
            SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING -> "Rapid Satellite Switching"
            SatelliteAnomalyType.NTN_BAND_MISMATCH -> "NTN Band Mismatch"
            SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY -> "Satellite Timing Anomaly"
            SatelliteAnomalyType.EPHEMERIS_MISMATCH -> "Satellite Position Mismatch"
            SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE -> "Network Downgrade to Satellite"
        }

        return "$emoji $typeName"
    }

    /**
     * Build a description of matched patterns and technical details.
     */
    private fun buildMatchedPatterns(context: SatelliteDetectionContext): String {
        val anomaly = context.anomaly
        val parts = mutableListOf<String>()

        // Add anomaly description
        parts.add(anomaly.description)

        // Add technical details
        if (anomaly.technicalDetails.isNotEmpty()) {
            val details = anomaly.technicalDetails.entries.joinToString(", ") { (k, v) -> "$k: $v" }
            parts.add("Technical: $details")
        }

        // Add NTN parameters if available
        context.ntnParameters?.let { ntn ->
            val ntnDetails = mutableListOf<String>()
            ntn.ntnBand?.let { ntnDetails.add("Band: $it") }
            ntn.orbitalType.takeIf { it != OrbitalType.UNKNOWN }?.let { ntnDetails.add("Orbit: ${it.displayName}") }
            ntn.measuredRttMs?.let { ntnDetails.add("RTT: ${it}ms") }
            if (ntnDetails.isNotEmpty()) {
                parts.add("NTN: ${ntnDetails.joinToString(", ")}")
            }
        }

        // Add frequency info
        context.frequencyMHz?.let { freq ->
            val bandInfo = SatelliteDetectionHeuristics.NTNBands.getBandForFrequency(freq)
            parts.add("Frequency: ${freq}MHz" + (bandInfo?.let { " ($it)" } ?: " (non-NTN)"))
        }

        return parts.joinToString(" | ")
    }

    /**
     * Build raw data JSON for advanced mode display.
     */
    private fun buildRawData(context: SatelliteDetectionContext): String {
        val anomaly = context.anomaly

        val data = buildMap {
            put("anomalyType", anomaly.type.name)
            put("severity", anomaly.severity.name)
            put("timestamp", context.timestamp)
            put("networkType", context.networkType)
            context.satelliteId?.let { put("satelliteId", it) }
            context.provider.takeIf { it != SatelliteProvider.UNKNOWN }?.let { put("provider", it.name) }
            context.connectionType.takeIf { it != SatelliteConnectionType.NONE }?.let { put("connectionType", it.name) }
            context.signalStrength?.let { put("signalStrength", it) }
            context.frequencyMHz?.let { put("frequencyMHz", it) }
            put("isValidNtnBand", context.isValidNtnBand)
            put("hasTerrestrialCoverage", context.hasTerrestrialCoverage)
            context.lastTerrestrialSignalDbm?.let { put("lastTerrestrialSignalDbm", it) }
            context.timeSinceGoodTerrestrialMs?.let { put("timeSinceGoodTerrestrialMs", it) }
            context.recentHandoffCount.takeIf { it > 0 }?.let { put("recentHandoffCount", it) }
            put("isUrbanArea", context.isUrbanArea)

            context.ntnParameters?.let { ntn ->
                val ntnData = buildMap {
                    put("radioTechnology", ntn.radioTechnology)
                    ntn.orbitalType.takeIf { it != OrbitalType.UNKNOWN }?.let { put("orbitalType", it.name) }
                    ntn.expectedRttMs?.let { put("expectedRttMs", it) }
                    ntn.measuredRttMs?.let { put("measuredRttMs", it) }
                    ntn.harqProcessCount?.let { put("harqProcessCount", it) }
                    ntn.channelBandwidthMHz?.let { put("channelBandwidthMHz", it) }
                    put("beamformingActive", ntn.beamformingActive)
                    ntn.ntnBand?.let { put("ntnBand", it) }
                }
                put("ntnParameters", ntnData)
            }

            anomaly.technicalDetails.takeIf { it.isNotEmpty() }?.let { put("technicalDetails", it) }
            anomaly.recommendations.takeIf { it.isNotEmpty() }?.let { put("recommendations", it) }
        }

        return data.entries.joinToString(",\n  ", "{\n  ", "\n}") { (k, v) ->
            "\"$k\": ${formatJsonValue(v)}"
        }
    }

    /**
     * Format a value for JSON output.
     */
    @Suppress("UNCHECKED_CAST")
    private fun formatJsonValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> {
                val map = value as Map<String, Any?>
                map.entries.joinToString(", ", "{", "}") { (k, v) ->
                    "\"$k\": ${formatJsonValue(v)}"
                }
            }
            is List<*> -> {
                value.joinToString(", ", "[", "]") { formatJsonValue(it) }
            }
            else -> "\"$value\""
        }
    }

    /**
     * Convert signal dBm to SignalStrength enum.
     */
    private fun signalDbmToStrength(dbm: Int?): SignalStrength {
        return dbm?.let {
            when {
                it > -50 -> SignalStrength.EXCELLENT
                it > -60 -> SignalStrength.GOOD
                it > -70 -> SignalStrength.MEDIUM
                it > -80 -> SignalStrength.WEAK
                else -> SignalStrength.VERY_WEAK
            }
        } ?: SignalStrength.UNKNOWN
    }

    /**
     * Format provider name for display.
     */
    private fun formatProviderName(provider: SatelliteProvider): String {
        return when (provider) {
            SatelliteProvider.STARLINK -> "SpaceX Starlink"
            SatelliteProvider.SKYLO -> "Skylo NTN"
            SatelliteProvider.GLOBALSTAR -> "Globalstar"
            SatelliteProvider.AST_SPACEMOBILE -> "AST SpaceMobile"
            SatelliteProvider.LYNK -> "Lynk"
            SatelliteProvider.IRIDIUM -> "Iridium"
            SatelliteProvider.INMARSAT -> "Inmarsat"
            SatelliteProvider.UNKNOWN -> "Unknown Provider"
        }
    }

    // ========================================================================
    // Factory Methods
    // ========================================================================

    /**
     * Create a SatelliteDetectionContext from a SatelliteAnomaly and connection state.
     */
    fun createContext(
        anomaly: SatelliteAnomaly,
        connectionState: SatelliteMonitor.SatelliteConnectionState,
        lastTerrestrialSignalDbm: Int? = null,
        timeSinceGoodTerrestrialMs: Long? = null,
        recentHandoffCount: Int = 0,
        latitude: Double? = null,
        longitude: Double? = null,
        isUrbanArea: Boolean = false
    ): SatelliteDetectionContext {
        // Extract frequency from technical details if available
        val frequencyMHz = anomaly.technicalDetails["frequency"]?.toString()?.toIntOrNull()
            ?: anomaly.technicalDetails["frequencyMHz"]?.toString()?.toIntOrNull()

        // Determine orbital type from provider
        val orbitalType = when (connectionState.provider) {
            SatelliteProvider.STARLINK -> OrbitalType.LEO
            SatelliteProvider.SKYLO -> OrbitalType.LEO
            SatelliteProvider.AST_SPACEMOBILE -> OrbitalType.LEO
            SatelliteProvider.LYNK -> OrbitalType.LEO
            SatelliteProvider.GLOBALSTAR -> OrbitalType.LEO
            SatelliteProvider.IRIDIUM -> OrbitalType.LEO
            SatelliteProvider.INMARSAT -> OrbitalType.GEO
            SatelliteProvider.UNKNOWN -> OrbitalType.UNKNOWN
        }

        // Extract RTT if available
        val measuredRttMs = anomaly.technicalDetails["rttMs"]?.toString()?.toLongOrNull()
            ?: anomaly.technicalDetails["measuredRtt"]?.toString()?.toLongOrNull()

        val ntnParameters = NtnParameters(
            radioTechnology = connectionState.radioTechnology,
            orbitalType = orbitalType,
            expectedRttMs = when (orbitalType) {
                OrbitalType.LEO -> 30L
                OrbitalType.MEO -> 140L
                OrbitalType.GEO -> 250L
                OrbitalType.UNKNOWN -> null
            },
            measuredRttMs = measuredRttMs,
            harqProcessCount = SatelliteMonitor.Companion.StarlinkDTC.MAX_HARQ_PROCESSES,
            beamformingActive = orbitalType == OrbitalType.LEO, // LEO typically uses beamforming
            ntnBand = frequencyMHz?.let { SatelliteDetectionHeuristics.NTNBands.getBandForFrequency(it) }
        )

        val isValidNtnBand = frequencyMHz?.let { SatelliteDetectionHeuristics.NTNBands.isNTNFrequency(it) } ?: true

        return SatelliteDetectionContext(
            id = UUID.randomUUID().toString(),
            timestamp = anomaly.timestamp,
            anomaly = anomaly,
            networkType = connectionState.networkName ?: connectionState.connectionType.name,
            satelliteId = anomaly.technicalDetails["satelliteId"]?.toString(),
            ntnParameters = ntnParameters,
            timingAdvance = anomaly.technicalDetails["timingAdvance"]?.toString()?.toLongOrNull(),
            signalStrength = connectionState.signalStrength?.let { it * -20 - 40 }, // Convert level to approximate dBm
            hasTerrestrialCoverage = lastTerrestrialSignalDbm != null &&
                lastTerrestrialSignalDbm > SatelliteMonitor.MIN_SIGNAL_FOR_TERRESTRIAL_DBM,
            lastTerrestrialSignalDbm = lastTerrestrialSignalDbm,
            timeSinceGoodTerrestrialMs = timeSinceGoodTerrestrialMs,
            provider = connectionState.provider,
            connectionType = connectionState.connectionType,
            frequencyMHz = frequencyMHz,
            isValidNtnBand = isValidNtnBand,
            recentHandoffCount = recentHandoffCount,
            latitude = latitude,
            longitude = longitude,
            isUrbanArea = isUrbanArea,
            detectionMethod = mapAnomalyTypeToDetectionMethod(anomaly.type)
        )
    }
}

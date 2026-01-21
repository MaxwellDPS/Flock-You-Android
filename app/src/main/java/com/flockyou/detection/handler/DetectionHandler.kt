package com.flockyou.detection.handler

import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import java.util.concurrent.ConcurrentHashMap

// ============================================================================
// Detection Context Hierarchy
// ============================================================================

/**
 * Sealed class hierarchy representing protocol-specific detection contexts.
 *
 * Each context type encapsulates the raw scan data and metadata needed for
 * detection analysis specific to its protocol. This allows handlers to work
 * with strongly-typed, protocol-appropriate data.
 */
sealed class DetectionContext {

    /** Common timestamp when the scan occurred */
    abstract val timestamp: Long

    /** Signal strength measurement (-127 to 0 dBm typical) */
    abstract val rssi: Int

    /** Optional location data */
    abstract val latitude: Double?
    abstract val longitude: Double?

    /**
     * Bluetooth Low Energy detection context.
     *
     * Contains all BLE-specific scan result data including device name,
     * MAC address, service UUIDs, and raw advertisement data.
     *
     * @property deviceName The advertised device name, if available
     * @property macAddress The BLE MAC address (may be randomized)
     * @property serviceUuids List of advertised service UUIDs
     * @property manufacturerData Map of manufacturer ID to data bytes
     * @property advertisementData Raw advertisement packet bytes
     * @property isConnectable Whether the device accepts connections
     * @property txPowerLevel Advertised transmit power level
     */
    data class BluetoothLe(
        override val timestamp: Long = System.currentTimeMillis(),
        override val rssi: Int,
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        val deviceName: String? = null,
        val macAddress: String,
        val serviceUuids: List<String> = emptyList(),
        val manufacturerData: Map<Int, ByteArray> = emptyMap(),
        val advertisementData: ByteArray? = null,
        val isConnectable: Boolean = false,
        val txPowerLevel: Int? = null
    ) : DetectionContext() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BluetoothLe) return false
            return macAddress == other.macAddress &&
                   timestamp == other.timestamp &&
                   rssi == other.rssi
        }

        override fun hashCode(): Int {
            var result = macAddress.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + rssi
            return result
        }
    }

    /**
     * WiFi detection context.
     *
     * Contains WiFi scan result data including SSID, BSSID, channel info,
     * and security configuration.
     *
     * @property ssid The network name (may be hidden/empty)
     * @property bssid The access point MAC address
     * @property channel WiFi channel number
     * @property frequency Operating frequency in MHz
     * @property capabilities Security and protocol capabilities string
     * @property isHidden Whether the SSID is hidden
     * @property seenCount Number of times this AP has been observed
     */
    data class WiFi(
        override val timestamp: Long = System.currentTimeMillis(),
        override val rssi: Int,
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        val ssid: String,
        val bssid: String,
        val channel: Int,
        val frequency: Int,
        val capabilities: String = "",
        val isHidden: Boolean = false,
        val seenCount: Int = 1
    ) : DetectionContext()

    /**
     * Cellular network detection context.
     *
     * Contains cellular network information for detecting cell site
     * simulators (Stingrays) and other cellular anomalies.
     *
     * @property mcc Mobile Country Code
     * @property mnc Mobile Network Code
     * @property lac Location Area Code
     * @property cid Cell ID
     * @property psc Primary Scrambling Code (UMTS)
     * @property arfcn Absolute Radio Frequency Channel Number
     * @property networkType Network generation (2G/3G/4G/5G)
     * @property isRoaming Whether currently roaming
     * @property previousCellInfo Previous cell tower info for comparison
     */
    data class Cellular(
        override val timestamp: Long = System.currentTimeMillis(),
        override val rssi: Int,
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        val mcc: Int,
        val mnc: Int,
        val lac: Int,
        val cid: Long,
        val psc: Int? = null,
        val arfcn: Int? = null,
        val networkType: String,
        val isRoaming: Boolean = false,
        val previousCellInfo: CellSnapshot? = null
    ) : DetectionContext()

    /**
     * GNSS/GPS detection context.
     *
     * Contains satellite signal data for detecting GPS spoofing
     * and jamming attacks.
     *
     * @property satellites List of visible satellite data
     * @property hdop Horizontal Dilution of Precision
     * @property pdop Position Dilution of Precision
     * @property fixType Type of GPS fix (none/2D/3D)
     * @property cn0DbHz Carrier-to-noise density
     * @property agcLevel Automatic Gain Control level
     */
    data class Gnss(
        override val timestamp: Long = System.currentTimeMillis(),
        override val rssi: Int = 0, // GNSS uses cn0DbHz instead
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        val satellites: List<SatelliteInfo> = emptyList(),
        val hdop: Float? = null,
        val pdop: Float? = null,
        val fixType: GnssFixType = GnssFixType.NONE,
        val cn0DbHz: Float? = null,
        val agcLevel: Float? = null
    ) : DetectionContext()

    /**
     * Audio/Ultrasonic detection context.
     *
     * Contains audio analysis data for detecting ultrasonic
     * tracking beacons and other audio-based surveillance.
     *
     * @property frequencyHz Detected frequency in Hz
     * @property amplitudeDb Signal amplitude in dB
     * @property duration Duration of detection in milliseconds
     * @property spectralPeaks List of spectral peak frequencies
     * @property modulationType Detected modulation type
     * @property isUltrasonic Whether frequency is above human hearing
     */
    data class Audio(
        override val timestamp: Long = System.currentTimeMillis(),
        override val rssi: Int = 0, // Audio uses amplitudeDb instead
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        val frequencyHz: Int,
        val amplitudeDb: Double,
        val duration: Long,
        val spectralPeaks: List<Int> = emptyList(),
        val modulationType: String? = null,
        val isUltrasonic: Boolean = false
    ) : DetectionContext()

    /**
     * RF spectrum analysis context.
     *
     * Contains radio frequency analysis data for detecting
     * jammers, hidden transmitters, and other RF anomalies.
     *
     * @property frequencyHz Center frequency in Hz
     * @property bandwidthHz Signal bandwidth in Hz
     * @property powerDbm Signal power in dBm
     * @property modulationType Detected modulation type
     * @property signalType Classified signal type
     * @property isAnomaly Whether this represents anomalous activity
     */
    data class RfSpectrum(
        override val timestamp: Long = System.currentTimeMillis(),
        override val rssi: Int,
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        val frequencyHz: Long,
        val bandwidthHz: Long,
        val powerDbm: Double,
        val modulationType: String? = null,
        val signalType: String? = null,
        val isAnomaly: Boolean = false
    ) : DetectionContext()

    /**
     * Satellite/NTN (Non-Terrestrial Network) detection context.
     *
     * Contains data for detecting suspicious satellite connections
     * and forced handoffs to satellite networks.
     *
     * @property satelliteId Identifier of the satellite
     * @property orbitType Satellite orbit type (LEO/MEO/GEO)
     * @property elevation Satellite elevation angle
     * @property azimuth Satellite azimuth angle
     * @property expectedTiming Expected signal timing
     * @property actualTiming Actual measured timing
     * @property handoffReason Reason for handoff to satellite
     */
    data class Satellite(
        override val timestamp: Long = System.currentTimeMillis(),
        override val rssi: Int,
        override val latitude: Double? = null,
        override val longitude: Double? = null,
        val satelliteId: String,
        val orbitType: String,
        val elevation: Float,
        val azimuth: Float,
        val expectedTiming: Long? = null,
        val actualTiming: Long? = null,
        val handoffReason: String? = null
    ) : DetectionContext()
}

/**
 * Snapshot of cell tower information for comparison.
 */
data class CellSnapshot(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Long,
    val timestamp: Long
)

/**
 * GNSS satellite information.
 */
data class SatelliteInfo(
    val svid: Int,
    val constellation: String,
    val cn0DbHz: Float,
    val elevation: Float,
    val azimuth: Float,
    val usedInFix: Boolean
)

/**
 * GNSS fix type enumeration.
 */
enum class GnssFixType {
    NONE,
    FIX_2D,
    FIX_3D
}

// ============================================================================
// Detection Result Types
// ============================================================================

/**
 * Result of a detection analysis operation.
 *
 * Contains the detected surveillance device (if any), confidence metrics,
 * and additional metadata about the analysis.
 *
 * @property detection The detected surveillance device, if matched
 * @property confidence Confidence score from 0.0 to 1.0
 * @property rawScore Raw threat score before normalization (0-100)
 * @property metadata Additional key-value metadata about the detection
 * @property matchedPatterns List of patterns that matched during analysis
 * @property analysisTimeMs Time taken to perform analysis in milliseconds
 * @property handlerId Identifier of the handler that produced this result
 */
data class DetectionResult(
    val detection: Detection?,
    val confidence: Float,
    val rawScore: Int = 0,
    val metadata: Map<String, Any> = emptyMap(),
    val matchedPatterns: List<PatternMatch> = emptyList(),
    val analysisTimeMs: Long = 0,
    val handlerId: String = ""
) {
    /** Whether a surveillance device was detected */
    val isDetection: Boolean get() = detection != null

    /** Whether the detection has high confidence (>= 0.7) */
    val isHighConfidence: Boolean get() = confidence >= 0.7f

    /** Whether this is a potential false positive (low confidence detection) */
    val isPotentialFalsePositive: Boolean get() = isDetection && confidence < 0.5f

    companion object {
        /** Empty result indicating no detection */
        val EMPTY = DetectionResult(
            detection = null,
            confidence = 0f,
            rawScore = 0
        )
    }
}

/**
 * Represents a pattern that matched during detection analysis.
 *
 * @property patternId Unique identifier for the pattern
 * @property patternType Type of pattern (SSID, MAC prefix, BLE UUID, etc.)
 * @property matchedValue The actual value that matched
 * @property expectedPattern The pattern definition that was matched against
 * @property threatScore Threat score contribution from this match (0-100)
 * @property confidence Confidence in this specific match (0.0-1.0)
 * @property description Human-readable description of what matched
 * @property metadata Additional pattern-specific metadata
 */
data class PatternMatch(
    val patternId: String,
    val patternType: PatternMatchType,
    val matchedValue: String,
    val expectedPattern: String,
    val threatScore: Int,
    val confidence: Float = 1.0f,
    val description: String = "",
    val metadata: Map<String, Any> = emptyMap()
) {
    /** Whether this is a high-threat match (score >= 70) */
    val isHighThreat: Boolean get() = threatScore >= 70

    /** Whether this is a definitive match (confidence >= 0.9) */
    val isDefinitive: Boolean get() = confidence >= 0.9f
}

/**
 * Result of a proper threat calculation using the enterprise-grade formula.
 *
 * Contains the full breakdown of how the threat score was calculated:
 * - Raw score from the formula: likelihood * impact * confidence
 * - The severity level derived from the score
 * - All the factors that contributed to the confidence adjustment
 * - Human-readable reasoning for debug/audit purposes
 *
 * @property rawScore Calculated threat score (0-100)
 * @property severity Derived threat level
 * @property likelihood Base likelihood percentage used (0-100)
 * @property impactFactor Impact factor for the device type (0.5-2.0)
 * @property confidence Final confidence after adjustments (0.0-1.0)
 * @property confidenceFactors List of factors that affected confidence
 * @property reasoning Human-readable explanation of the calculation
 */
data class ThreatCalculationResult(
    val rawScore: Int,
    val severity: ThreatLevel,
    val likelihood: Int,
    val impactFactor: Double,
    val confidence: Double,
    val confidenceFactors: List<String>,
    val reasoning: String
) {
    /**
     * Whether this represents a significant threat (MEDIUM or higher).
     */
    val isSignificantThreat: Boolean
        get() = severity == ThreatLevel.MEDIUM ||
                severity == ThreatLevel.HIGH ||
                severity == ThreatLevel.CRITICAL

    /**
     * Whether this is likely a false positive (low score with low confidence).
     */
    val isLikelyFalsePositive: Boolean
        get() = rawScore < 30 && confidence < 0.4

    /**
     * Generate a debug export map for this result.
     */
    fun toDebugMap(): Map<String, Any> = mapOf(
        "raw_score" to rawScore,
        "severity" to severity.name,
        "severity_display" to severity.displayName,
        "likelihood_percent" to likelihood,
        "impact_factor" to impactFactor,
        "confidence_percent" to (confidence * 100).toInt(),
        "confidence_factors" to confidenceFactors,
        "reasoning" to reasoning,
        "is_significant_threat" to isSignificantThreat,
        "is_likely_false_positive" to isLikelyFalsePositive,
        "formula" to "score = $likelihood * ${"%.2f".format(impactFactor)} * ${"%.2f".format(confidence)} = $rawScore"
    )
}

/**
 * Types of patterns that can be matched during detection.
 */
enum class PatternMatchType(val displayName: String) {
    SSID_REGEX("SSID Pattern"),
    SSID_EXACT("SSID Exact Match"),
    MAC_PREFIX("MAC Prefix (OUI)"),
    BLE_NAME_REGEX("BLE Name Pattern"),
    BLE_NAME_EXACT("BLE Name Exact Match"),
    BLE_SERVICE_UUID("BLE Service UUID"),
    BLE_MANUFACTURER_ID("BLE Manufacturer ID"),
    CELLULAR_MCC_MNC("Cellular MCC/MNC"),
    CELLULAR_BEHAVIOR("Cellular Behavior"),
    GNSS_ANOMALY("GNSS Anomaly"),
    ULTRASONIC_FREQUENCY("Ultrasonic Frequency"),
    RF_SIGNATURE("RF Signature"),
    BEHAVIORAL("Behavioral Pattern"),
    TEMPORAL("Temporal Pattern"),
    SPATIAL("Spatial Pattern"),
    CROSS_PROTOCOL("Cross-Protocol Correlation")
}

// ============================================================================
// Detection Thresholds
// ============================================================================

/**
 * Interface defining configurable thresholds for detection analysis.
 *
 * Implementations can provide protocol-specific or use-case-specific
 * threshold values to tune detection sensitivity.
 */
interface DetectionThresholds {
    /** Minimum RSSI to consider a signal (typically -90 to -100 dBm) */
    val minRssi: Int

    /** Minimum confidence score to report a detection (0.0-1.0) */
    val minConfidence: Float

    /** Minimum threat score to report (0-100) */
    val minThreatScore: Int

    /** Maximum age of cached results in milliseconds */
    val cacheExpiryMs: Long

    /** Number of sightings required before reporting */
    val minSightingsRequired: Int

    /** Time window for counting sightings in milliseconds */
    val sightingWindowMs: Long

    /** Whether to report low-confidence detections as informational */
    val reportLowConfidence: Boolean
}

/**
 * Default threshold values suitable for most detection scenarios.
 */
data class DefaultDetectionThresholds(
    override val minRssi: Int = -90,
    override val minConfidence: Float = 0.3f,
    override val minThreatScore: Int = 20,
    override val cacheExpiryMs: Long = 30 * 60 * 1000L, // 30 minutes
    override val minSightingsRequired: Int = 1,
    override val sightingWindowMs: Long = 60 * 1000L, // 1 minute
    override val reportLowConfidence: Boolean = true
) : DetectionThresholds

/**
 * High-sensitivity thresholds for maximum detection coverage.
 * May produce more false positives.
 */
data class HighSensitivityThresholds(
    override val minRssi: Int = -95,
    override val minConfidence: Float = 0.15f,
    override val minThreatScore: Int = 10,
    override val cacheExpiryMs: Long = 60 * 60 * 1000L, // 1 hour
    override val minSightingsRequired: Int = 1,
    override val sightingWindowMs: Long = 120 * 1000L, // 2 minutes
    override val reportLowConfidence: Boolean = true
) : DetectionThresholds

/**
 * Low-sensitivity thresholds for reducing false positives.
 * May miss some legitimate detections.
 */
data class LowSensitivityThresholds(
    override val minRssi: Int = -80,
    override val minConfidence: Float = 0.6f,
    override val minThreatScore: Int = 50,
    override val cacheExpiryMs: Long = 15 * 60 * 1000L, // 15 minutes
    override val minSightingsRequired: Int = 2,
    override val sightingWindowMs: Long = 30 * 1000L, // 30 seconds
    override val reportLowConfidence: Boolean = false
) : DetectionThresholds

// ============================================================================
// Device Type Profile
// ============================================================================

/**
 * Profile containing detailed information about a specific device type.
 *
 * Used to provide context for AI analysis and user-facing information
 * about detected surveillance devices.
 *
 * @property deviceType The device type this profile describes
 * @property manufacturer Known manufacturer(s) of this device type
 * @property description Detailed description of the device
 * @property capabilities List of known device capabilities
 * @property typicalThreatLevel Typical threat level for this device type
 * @property knownPatterns Known detection patterns for this device
 * @property legalConsiderations Legal information about the device
 * @property mitigationAdvice Advice for users who detect this device
 * @property documentationUrls Links to additional documentation
 */
data class DeviceTypeProfile(
    val deviceType: DeviceType,
    val manufacturer: String,
    val description: String,
    val capabilities: List<String> = emptyList(),
    val typicalThreatLevel: ThreatLevel,
    val knownPatterns: List<String> = emptyList(),
    val legalConsiderations: String = "",
    val mitigationAdvice: String = "",
    val documentationUrls: List<String> = emptyList()
)

// ============================================================================
// Detection Handler Interface
// ============================================================================

/**
 * Protocol-agnostic interface for detection handlers.
 *
 * Detection handlers are responsible for analyzing scan results from a specific
 * protocol (BLE, WiFi, Cellular, etc.) and producing detection results. This
 * interface provides a unified contract for all protocol handlers while allowing
 * protocol-specific context types via the generic parameter.
 *
 * ## Implementation Guidelines
 *
 * 1. Each handler should focus on a single detection protocol
 * 2. Use the appropriate [DetectionContext] subclass for the protocol
 * 3. Implement pattern matching using the [matchesPattern] method
 * 4. Provide meaningful device profiles via [getDeviceProfile]
 * 5. Generate informative AI prompts with [generatePrompt]
 * 6. Configure appropriate thresholds via [getThresholds]
 *
 * ## Thread Safety
 *
 * Handler implementations must be thread-safe as they may be called
 * concurrently from multiple scanning threads.
 *
 * @param T The specific [DetectionContext] subtype this handler processes
 */
interface DetectionHandler<T : DetectionContext> {

    /**
     * The detection protocol this handler supports.
     */
    val protocol: DetectionProtocol

    /**
     * Set of device types this handler can detect.
     */
    val supportedDeviceTypes: Set<DeviceType>

    /**
     * Set of detection methods this handler implements.
     */
    val supportedMethods: Set<DetectionMethod>

    /**
     * Unique identifier for this handler instance.
     */
    val handlerId: String
        get() = "${protocol.name}_handler"

    /**
     * Human-readable name for this handler.
     */
    val displayName: String
        get() = "${protocol.displayName} Detection Handler"

    /**
     * Analyzes a detection context and returns a result.
     *
     * This is the primary analysis method that processes raw scan data
     * and determines if a surveillance device is present.
     *
     * @param context The protocol-specific detection context to analyze
     * @return [DetectionResult] containing the analysis outcome, or null if
     *         the context could not be processed
     */
    fun analyze(context: T): DetectionResult?

    /**
     * Retrieves the profile for a specific device type.
     *
     * Device profiles provide detailed information about surveillance
     * devices for AI analysis and user display.
     *
     * @param deviceType The device type to get profile information for
     * @return [DeviceTypeProfile] if available for the device type, null otherwise
     */
    fun getDeviceProfile(deviceType: DeviceType): DeviceTypeProfile?

    /**
     * Generates an AI prompt for analyzing a detection.
     *
     * Creates a structured prompt suitable for local LLM analysis that
     * includes detection context, matched patterns, and enriched data.
     *
     * @param detection The detection to generate a prompt for
     * @param enrichedData Optional additional data to include in the prompt
     * @return Formatted prompt string for AI analysis
     */
    fun generatePrompt(detection: Detection, enrichedData: Any? = null): String

    /**
     * Returns the detection thresholds for this handler.
     *
     * Thresholds control sensitivity and can be customized for different
     * scanning modes or user preferences.
     *
     * @return [DetectionThresholds] instance with current threshold values
     */
    fun getThresholds(): DetectionThresholds

    /**
     * Attempts to match a scan result against known patterns.
     *
     * Pattern matching is the first stage of detection, identifying
     * potential surveillance devices before full analysis.
     *
     * @param scanResult The raw scan result to match (type depends on protocol)
     * @return [PatternMatch] if a pattern matched, null otherwise
     */
    fun matchesPattern(scanResult: Any): PatternMatch?

    /**
     * Validates whether this handler can process the given context.
     *
     * @param context The detection context to validate
     * @return true if this handler can process the context, false otherwise
     */
    fun canHandle(context: DetectionContext): Boolean {
        return when (context) {
            is DetectionContext.BluetoothLe -> protocol == DetectionProtocol.BLUETOOTH_LE
            is DetectionContext.WiFi -> protocol == DetectionProtocol.WIFI
            is DetectionContext.Cellular -> protocol == DetectionProtocol.CELLULAR
            is DetectionContext.Gnss -> protocol == DetectionProtocol.GNSS
            is DetectionContext.Audio -> protocol == DetectionProtocol.AUDIO
            is DetectionContext.RfSpectrum -> protocol == DetectionProtocol.RF
            is DetectionContext.Satellite -> protocol == DetectionProtocol.SATELLITE
        }
    }

    /**
     * Performs any necessary cleanup when the handler is no longer needed.
     */
    fun cleanup() {}

    /**
     * Starts monitoring for detections (optional lifecycle method).
     */
    fun startMonitoring() {}

    /**
     * Stops monitoring for detections (optional lifecycle method).
     */
    fun stopMonitoring() {}

    /**
     * Updates the current location for location-aware detection.
     */
    fun updateLocation(latitude: Double, longitude: Double) {}

    /**
     * Destroys the handler and releases all resources.
     */
    fun destroy() {}

    /**
     * Retrieves the profile for a device type using the simpler getProfile name.
     * Delegates to getDeviceProfile for compatibility.
     */
    fun getProfile(deviceType: DeviceType): DeviceTypeProfile? = getDeviceProfile(deviceType)
}

// ============================================================================
// Base Detection Handler
// ============================================================================

/**
 * Abstract base class providing common functionality for detection handlers.
 *
 * Extend this class to implement protocol-specific detection handlers.
 * The base class provides:
 * - Default threshold management
 * - Common pattern matching utilities
 * - Device profile lookup
 * - Prompt generation templates
 *
 * @param T The specific [DetectionContext] subtype this handler processes
 */
abstract class BaseDetectionHandler<T : DetectionContext> : DetectionHandler<T> {

    /** Configurable thresholds, can be overridden by subclasses */
    @Volatile
    private var _thresholds: DetectionThresholds = DefaultDetectionThresholds()

    /** Device type profiles registered with this handler */
    protected val deviceProfiles: MutableMap<DeviceType, DeviceTypeProfile> = ConcurrentHashMap()

    override fun getThresholds(): DetectionThresholds = _thresholds

    /**
     * Updates the detection thresholds.
     *
     * @param newThresholds The new thresholds to use
     */
    fun updateThresholds(newThresholds: DetectionThresholds) {
        _thresholds = newThresholds
    }

    override fun getDeviceProfile(deviceType: DeviceType): DeviceTypeProfile? {
        return deviceProfiles[deviceType]
    }

    /**
     * Registers a device type profile with this handler.
     *
     * @param profile The profile to register
     */
    protected fun registerProfile(profile: DeviceTypeProfile) {
        deviceProfiles[profile.deviceType] = profile
    }

    /**
     * Registers multiple device type profiles.
     *
     * @param profiles The profiles to register
     */
    protected fun registerProfiles(vararg profiles: DeviceTypeProfile) {
        profiles.forEach { registerProfile(it) }
    }

    override fun generatePrompt(detection: Detection, enrichedData: Any?): String {
        val profile = getDeviceProfile(detection.deviceType)
        return buildString {
            appendLine("=== Surveillance Device Analysis ===")
            appendLine()
            appendLine("Detection Information:")
            appendLine("- Device Type: ${detection.deviceType.displayName}")
            appendLine("- Protocol: ${detection.protocol.displayName}")
            appendLine("- Detection Method: ${detection.detectionMethod.displayName}")
            appendLine("- Threat Level: ${detection.threatLevel.displayName}")
            appendLine("- Signal Strength: ${detection.rssi} dBm (${detection.signalStrength.displayName})")

            detection.deviceName?.let { appendLine("- Device Name: $it") }
            detection.macAddress?.let { appendLine("- MAC Address: $it") }
            detection.ssid?.let { appendLine("- SSID: $it") }
            detection.manufacturer?.let { appendLine("- Manufacturer: $it") }

            if (profile != null) {
                appendLine()
                appendLine("Device Profile:")
                appendLine("- Description: ${profile.description}")
                if (profile.capabilities.isNotEmpty()) {
                    appendLine("- Capabilities: ${profile.capabilities.joinToString(", ")}")
                }
                if (profile.legalConsiderations.isNotEmpty()) {
                    appendLine("- Legal Info: ${profile.legalConsiderations}")
                }
            }

            if (enrichedData != null) {
                appendLine()
                appendLine("Additional Context:")
                appendLine(enrichedData.toString())
            }

            appendLine()
            appendLine("Please analyze this detection and provide:")
            appendLine("1. Assessment of threat legitimacy")
            appendLine("2. Likely purpose of this device")
            appendLine("3. Recommended user actions")
            appendLine("4. Confidence in this assessment (Low/Medium/High)")
        }
    }

    /**
     * Helper to create a detection result with timing information.
     *
     * @param detection The detection, if any
     * @param confidence Confidence score
     * @param rawScore Raw threat score
     * @param metadata Additional metadata
     * @param matchedPatterns Patterns that matched
     * @param startTimeMs Start time for calculating analysis duration
     */
    protected fun createResult(
        detection: Detection?,
        confidence: Float,
        rawScore: Int = 0,
        metadata: Map<String, Any> = emptyMap(),
        matchedPatterns: List<PatternMatch> = emptyList(),
        startTimeMs: Long = System.currentTimeMillis()
    ): DetectionResult {
        return DetectionResult(
            detection = detection,
            confidence = confidence,
            rawScore = rawScore,
            metadata = metadata,
            matchedPatterns = matchedPatterns,
            analysisTimeMs = System.currentTimeMillis() - startTimeMs,
            handlerId = handlerId
        )
    }

    /**
     * Calculates aggregate confidence from multiple pattern matches.
     *
     * This improved method applies proper confidence adjustments:
     * - Multiple confirming indicators: +0.2
     * - Cross-protocol correlation: +0.3
     * - High match quality: +0.1 to +0.15
     * - Single weak indicator: -0.3
     *
     * @param matches List of pattern matches
     * @param hasMultipleProtocols Whether detections came from multiple protocols
     * @return Aggregate confidence score (0.0-1.0)
     */
    protected fun calculateAggregateConfidence(
        matches: List<PatternMatch>,
        hasMultipleProtocols: Boolean = false
    ): Float {
        if (matches.isEmpty()) return 0f

        // Start with base confidence from weighted average
        val totalWeight = matches.sumOf { it.threatScore.toDouble() }
        var baseConfidence = if (totalWeight == 0.0) {
            matches.map { it.confidence }.average().toFloat()
        } else {
            val weightedSum = matches.sumOf { it.confidence * it.threatScore.toDouble() }
            (weightedSum / totalWeight).toFloat()
        }

        // Apply confidence adjustments based on ThreatScoring system

        // Multiple confirming indicators boost
        if (matches.size > 1) {
            baseConfidence += 0.2f
        } else {
            // Single indicator penalty
            baseConfidence -= 0.3f
        }

        // Cross-protocol correlation boost (strong indicator)
        if (hasMultipleProtocols) {
            baseConfidence += 0.3f
        }

        // Bonus for definitive matches
        val definitiveCount = matches.count { it.isDefinitive }
        if (definitiveCount > 0) {
            baseConfidence += 0.1f * definitiveCount.coerceAtMost(2)
        }

        return baseConfidence.coerceIn(0f, 1f)
    }

    /**
     * Calculates aggregate threat score from multiple pattern matches.
     *
     * This improved method uses the proper formula:
     *   threat_score = likelihood * impact_factor * confidence
     *
     * It ensures:
     * - 20% IMSI likelihood with low confidence -> LOW severity, not HIGH
     * - High scores only for confirmed, high-confidence threats
     *
     * @param matches List of pattern matches
     * @param baseLikelihood Base likelihood percentage (0-100) that these patterns indicate a real threat
     * @param impactFactor Impact factor based on device type (0.5-2.0)
     * @param confidence Calculated confidence (0.0-1.0)
     * @return Aggregate threat score (0-100)
     */
    protected fun calculateAggregateThreatScore(
        matches: List<PatternMatch>,
        baseLikelihood: Int = 50,
        impactFactor: Double = 1.0,
        confidence: Float = 0.5f
    ): Int {
        if (matches.isEmpty()) return 0

        // DEPRECATED BEHAVIOR (kept for backward compatibility when using old signature):
        // If using default parameters, fall back to legacy calculation
        // This will be removed once all callers are updated
        if (baseLikelihood == 50 && impactFactor == 1.0 && confidence == 0.5f) {
            return calculateLegacyAggregateThreatScore(matches)
        }

        // NEW BEHAVIOR: Use proper formula
        // threat_score = likelihood * impact_factor * confidence
        val rawScore = (baseLikelihood * impactFactor * confidence).toInt()

        // Apply minor bonus for multiple confirming matches (capped)
        val matchBonus = ((matches.size - 1) * 3).coerceAtMost(10)

        return (rawScore + matchBonus).coerceIn(0, 100)
    }

    /**
     * Legacy threat score calculation for backward compatibility.
     * DO NOT USE for new code - use calculateAggregateThreatScore with proper parameters.
     */
    @Deprecated("Use calculateAggregateThreatScore with proper likelihood, impact, and confidence parameters")
    private fun calculateLegacyAggregateThreatScore(matches: List<PatternMatch>): Int {
        if (matches.isEmpty()) return 0

        // Take the maximum score, with a small bonus for multiple matches
        val maxScore = matches.maxOf { it.threatScore }
        val bonusPerMatch = 3  // Reduced from 5 to prevent score inflation
        val bonus = ((matches.size - 1) * bonusPerMatch).coerceAtMost(10)

        return (maxScore + bonus).coerceIn(0, 100)
    }

    /**
     * Calculate threat score using the proper enterprise-grade formula.
     *
     * This method implements:
     *   threat_score = likelihood * impact_factor * confidence
     *
     * @param baseLikelihood Base probability (0-100) this is a real threat
     * @param deviceType The device type for impact factor lookup
     * @param rssi Signal strength in dBm
     * @param seenCount Number of times detected
     * @param hasMultipleIndicators Whether multiple confirming indicators exist
     * @param hasCrossProtocolCorrelation Whether seen on multiple protocols
     * @param isKnownFalsePositivePattern Whether this matches a known FP pattern
     * @param isConsumerDevice Whether this is a known consumer IoT device
     * @return ThreatCalculationResult with score, severity, and reasoning
     */
    protected fun calculateProperThreatScore(
        baseLikelihood: Int,
        deviceType: com.flockyou.data.model.DeviceType,
        rssi: Int,
        seenCount: Int = 1,
        hasMultipleIndicators: Boolean = false,
        hasCrossProtocolCorrelation: Boolean = false,
        isKnownFalsePositivePattern: Boolean = false,
        isConsumerDevice: Boolean = false
    ): ThreatCalculationResult {
        // Get impact factor based on device type
        val impactFactor = getImpactFactor(deviceType)

        // Calculate confidence with adjustments
        var confidence = 0.5

        // Signal strength adjustments
        when {
            rssi > -50 -> confidence += 0.1
            rssi > -60 -> confidence += 0.05
            rssi < -90 -> confidence -= 0.2
            rssi < -80 -> confidence -= 0.1
        }

        // Multiple indicators
        if (hasMultipleIndicators) {
            confidence += 0.2
        } else {
            confidence -= 0.3  // Single indicator penalty
        }

        // Cross-protocol correlation (strong indicator)
        if (hasCrossProtocolCorrelation) {
            confidence += 0.3
        }

        // Known false positive penalty
        if (isKnownFalsePositivePattern) {
            confidence -= 0.5
        }

        // Consumer device penalty
        if (isConsumerDevice) {
            confidence -= 0.2
        }

        // Persistence bonus
        if (seenCount > 3) {
            confidence += 0.2
        } else if (seenCount == 1) {
            confidence -= 0.2  // Brief detection penalty
        }

        // Clamp confidence
        confidence = confidence.coerceIn(0.1, 1.0)

        // Calculate final score
        val rawScore = (baseLikelihood * impactFactor * confidence).toInt().coerceIn(0, 100)

        // Determine severity
        val severity = com.flockyou.data.model.scoreToThreatLevel(rawScore)

        // Build confidence factors list
        val factors = mutableListOf<String>()
        if (hasMultipleIndicators) factors.add("+multiple_indicators")
        else factors.add("-single_indicator")
        if (hasCrossProtocolCorrelation) factors.add("+cross_protocol")
        if (isKnownFalsePositivePattern) factors.add("-known_fp_pattern")
        if (isConsumerDevice) factors.add("-consumer_device")
        when {
            rssi > -50 -> factors.add("+signal_excellent")
            rssi > -60 -> factors.add("+signal_good")
            rssi < -90 -> factors.add("-signal_very_weak")
            rssi < -80 -> factors.add("-signal_weak")
        }
        if (seenCount > 3) factors.add("+persistent")
        else if (seenCount == 1) factors.add("-brief")

        return ThreatCalculationResult(
            rawScore = rawScore,
            severity = severity,
            likelihood = baseLikelihood,
            impactFactor = impactFactor,
            confidence = confidence,
            confidenceFactors = factors,
            reasoning = buildThreatReasoning(
                baseLikelihood, impactFactor, confidence, rawScore, severity, deviceType
            )
        )
    }

    /**
     * Get impact factor for a device type.
     */
    private fun getImpactFactor(deviceType: com.flockyou.data.model.DeviceType): Double = when (deviceType) {
        // Maximum impact - intercepts all communications
        com.flockyou.data.model.DeviceType.STINGRAY_IMSI -> 2.0
        com.flockyou.data.model.DeviceType.CELLEBRITE_FORENSICS -> 2.0
        com.flockyou.data.model.DeviceType.GRAYKEY_DEVICE -> 2.0
        com.flockyou.data.model.DeviceType.MAN_IN_MIDDLE -> 2.0

        // High impact - can cause physical harm
        com.flockyou.data.model.DeviceType.GNSS_SPOOFER -> 1.8
        com.flockyou.data.model.DeviceType.GNSS_JAMMER -> 1.8
        com.flockyou.data.model.DeviceType.RF_JAMMER -> 1.8
        com.flockyou.data.model.DeviceType.WIFI_PINEAPPLE -> 1.8
        com.flockyou.data.model.DeviceType.ROGUE_AP -> 1.7

        // Tracking/stalking concern
        com.flockyou.data.model.DeviceType.AIRTAG -> 1.5
        com.flockyou.data.model.DeviceType.TILE_TRACKER -> 1.5
        com.flockyou.data.model.DeviceType.SAMSUNG_SMARTTAG -> 1.5
        com.flockyou.data.model.DeviceType.GENERIC_BLE_TRACKER -> 1.5
        com.flockyou.data.model.DeviceType.TRACKING_DEVICE -> 1.5
        com.flockyou.data.model.DeviceType.SURVEILLANCE_VAN -> 1.5
        com.flockyou.data.model.DeviceType.DRONE -> 1.4

        // Privacy violations
        com.flockyou.data.model.DeviceType.HIDDEN_CAMERA -> 1.3
        com.flockyou.data.model.DeviceType.HIDDEN_TRANSMITTER -> 1.3
        com.flockyou.data.model.DeviceType.PACKET_SNIFFER -> 1.3
        com.flockyou.data.model.DeviceType.FLOCK_SAFETY_CAMERA -> 1.2
        com.flockyou.data.model.DeviceType.LICENSE_PLATE_READER -> 1.2
        com.flockyou.data.model.DeviceType.FACIAL_RECOGNITION -> 1.2

        // Standard surveillance
        com.flockyou.data.model.DeviceType.BODY_CAMERA -> 1.0
        com.flockyou.data.model.DeviceType.POLICE_VEHICLE -> 1.0
        com.flockyou.data.model.DeviceType.CCTV_CAMERA -> 1.0
        com.flockyou.data.model.DeviceType.ULTRASONIC_BEACON -> 1.0

        // Consumer IoT - lower impact
        com.flockyou.data.model.DeviceType.RING_DOORBELL -> 0.8
        com.flockyou.data.model.DeviceType.NEST_CAMERA -> 0.8
        com.flockyou.data.model.DeviceType.WYZE_CAMERA -> 0.8
        com.flockyou.data.model.DeviceType.AMAZON_SIDEWALK -> 0.7
        com.flockyou.data.model.DeviceType.BLUETOOTH_BEACON -> 0.7

        // Traffic infrastructure - minimal impact
        com.flockyou.data.model.DeviceType.SPEED_CAMERA -> 0.6
        com.flockyou.data.model.DeviceType.RED_LIGHT_CAMERA -> 0.6
        com.flockyou.data.model.DeviceType.TOLL_READER -> 0.6
        com.flockyou.data.model.DeviceType.TRAFFIC_SENSOR -> 0.5

        // Environmental/signal issues
        com.flockyou.data.model.DeviceType.RF_INTERFERENCE -> 0.5
        com.flockyou.data.model.DeviceType.RF_ANOMALY -> 0.5

        // Default
        else -> 1.0
    }

    /**
     * Build human-readable reasoning for the threat calculation.
     */
    private fun buildThreatReasoning(
        likelihood: Int,
        impactFactor: Double,
        confidence: Double,
        score: Int,
        severity: ThreatLevel,
        deviceType: com.flockyou.data.model.DeviceType
    ): String {
        return buildString {
            appendLine("Threat Assessment: ${severity.displayName}")
            appendLine()
            appendLine("Formula: score = likelihood * impact * confidence")
            appendLine("  Likelihood: $likelihood% (base probability of real threat)")
            appendLine("  Impact: ${"%.2f".format(impactFactor)} (${deviceType.displayName})")
            appendLine("  Confidence: ${"%.0f".format(confidence * 100)}%")
            appendLine("  Final Score: $score")
        }
    }

    /**
     * Checks if the RSSI meets the minimum threshold.
     *
     * @param rssi The RSSI value to check
     * @return true if RSSI is above the minimum threshold
     */
    protected fun meetsRssiThreshold(rssi: Int): Boolean {
        return rssi >= getThresholds().minRssi
    }

    /**
     * Checks if the confidence meets the minimum threshold.
     *
     * @param confidence The confidence value to check
     * @return true if confidence is above the minimum threshold
     */
    protected fun meetsConfidenceThreshold(confidence: Float): Boolean {
        return confidence >= getThresholds().minConfidence
    }

    /**
     * Checks if the threat score meets the minimum threshold.
     *
     * @param score The threat score to check
     * @return true if score is above the minimum threshold
     */
    protected fun meetsThreatScoreThreshold(score: Int): Boolean {
        return score >= getThresholds().minThreatScore
    }
}

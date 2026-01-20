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
     * @param matches List of pattern matches
     * @return Aggregate confidence score (0.0-1.0)
     */
    protected fun calculateAggregateConfidence(matches: List<PatternMatch>): Float {
        if (matches.isEmpty()) return 0f

        // Use a weighted average where higher threat scores contribute more
        val totalWeight = matches.sumOf { it.threatScore.toDouble() }
        if (totalWeight == 0.0) return matches.map { it.confidence }.average().toFloat()

        val weightedSum = matches.sumOf { it.confidence * it.threatScore.toDouble() }
        return (weightedSum / totalWeight).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Calculates aggregate threat score from multiple pattern matches.
     *
     * @param matches List of pattern matches
     * @return Aggregate threat score (0-100)
     */
    protected fun calculateAggregateThreatScore(matches: List<PatternMatch>): Int {
        if (matches.isEmpty()) return 0

        // Take the maximum score, with a bonus for multiple matches
        val maxScore = matches.maxOf { it.threatScore }
        val bonusPerMatch = 5
        val bonus = (matches.size - 1) * bonusPerMatch

        return (maxScore + bonus).coerceIn(0, 100)
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

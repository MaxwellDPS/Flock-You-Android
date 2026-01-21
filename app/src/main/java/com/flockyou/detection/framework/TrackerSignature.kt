package com.flockyou.detection.framework

import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel

/**
 * Unified Tracker Signature Framework
 *
 * This framework provides a common structure for detecting tracking devices across
 * multiple protocols (BLE, WiFi, RF, Ultrasonic). It enables easy maintenance,
 * extensibility, and cross-domain correlation of threats.
 *
 * Key Features:
 * - Centralized signature database for all tracker types
 * - Protocol-agnostic threat assessment
 * - Cross-domain correlation support
 * - Easy addition of new tracker signatures
 */

// ============================================================================
// Core Signature Types
// ============================================================================

/**
 * Base interface for all tracker signatures
 */
interface TrackerSignature {
    val id: String
    val name: String
    val manufacturer: String
    val category: TrackerCategory
    val threatLevel: ThreatLevel
    val description: String
    val protocols: Set<DetectionProtocolType>
}

/**
 * Categories of tracking devices
 */
enum class TrackerCategory(val displayName: String, val icon: String) {
    // Consumer trackers
    PERSONAL_TRACKER("Personal Item Tracker", "📍"),
    PET_TRACKER("Pet Tracker", "🐕"),
    VEHICLE_TRACKER("Vehicle Tracker", "🚗"),

    // Commercial/Retail
    RETAIL_BEACON("Retail Beacon", "🛒"),
    ADVERTISING_BEACON("Advertising Beacon", "📺"),
    PROXIMITY_BEACON("Proximity Beacon", "📡"),

    // Surveillance
    COVERT_TRACKER("Covert Tracker", "🕵️"),
    LAW_ENFORCEMENT("Law Enforcement", "👮"),

    // Audio
    ULTRASONIC_BEACON("Ultrasonic Beacon", "🔊"),
    CROSS_DEVICE_TRACKER("Cross-Device Tracker", "📱"),

    // RF
    RF_TRACKER("RF Tracker", "📻"),
    GPS_TRACKER("GPS Tracker", "🛰️"),

    // Unknown
    UNKNOWN("Unknown Tracker", "❓")
}

/**
 * Detection protocol types
 */
enum class DetectionProtocolType {
    BLE,
    BLE_BEACON,
    WIFI,
    SUBGHZ_RF,
    ULTRASONIC,
    NFC,
    CELLULAR,
    MULTI_PROTOCOL
}

// ============================================================================
// BLE Tracker Signatures
// ============================================================================

/**
 * BLE-specific tracker signature with manufacturer ID and service UUID matching
 */
data class BleTrackerSignature(
    override val id: String,
    override val name: String,
    override val manufacturer: String,
    override val category: TrackerCategory,
    override val threatLevel: ThreatLevel,
    override val description: String,

    // BLE-specific identifiers
    val manufacturerIds: Set<Int> = emptySet(),
    val serviceUuids: Set<String> = emptySet(),
    val namePatterns: List<Regex> = emptyList(),

    // Beacon protocol support
    val beaconProtocol: BeaconProtocolType? = null,

    // Behavior indicators
    val usesRandomAddress: Boolean = false,
    val rotatesAddress: Boolean = false,
    val addressRotationIntervalMs: Long? = null,

    // Detection thresholds
    val strongSignalThreshold: Int = -50,  // dBm
    val followingThreshold: Int = 3,        // locations

    override val protocols: Set<DetectionProtocolType> = setOf(DetectionProtocolType.BLE)
) : TrackerSignature

/**
 * Beacon protocol types
 */
enum class BeaconProtocolType(val displayName: String) {
    IBEACON("Apple iBeacon"),
    EDDYSTONE_UID("Google Eddystone-UID"),
    EDDYSTONE_URL("Google Eddystone-URL"),
    EDDYSTONE_TLM("Google Eddystone-TLM"),
    EDDYSTONE_EID("Google Eddystone-EID"),
    ALTBEACON("AltBeacon"),
    FIND_MY("Apple Find My"),
    EXPOSURE_NOTIFICATION("Exposure Notification"),
    FAST_PAIR("Google Fast Pair"),
    SWIFT_PAIR("Microsoft Swift Pair"),
    UNKNOWN("Unknown Protocol")
}

// ============================================================================
// Ultrasonic Beacon Signatures
// ============================================================================

/**
 * Ultrasonic beacon signature for audio-based tracking
 *
 * Enhanced with real-world tracking knowledge including:
 * - Known tracking systems (SilverPush, Alphonso, LISNR, Shopkick, Samba TV, etc.)
 * - Modulation types for fingerprinting (FSK, PSK, CHIRP)
 * - Beacon timing patterns
 * - Privacy impact classification
 * - Legal status and regulatory context
 * - Real-world confirmation methods
 * - Mitigation advice
 */
data class UltrasonicTrackerSignature(
    override val id: String,
    override val name: String,
    override val manufacturer: String,
    override val category: TrackerCategory,
    override val threatLevel: ThreatLevel,
    override val description: String,

    // Frequency specifications
    val primaryFrequencyHz: Int,
    val frequencyToleranceHz: Int = 100,
    val secondaryFrequencies: List<Int> = emptyList(),

    // Signal characteristics
    val typicalAmplitudeDb: Double = -35.0,
    val modulationType: UltrasonicModulation = UltrasonicModulation.CONTINUOUS,
    val pulsePatternMs: List<Int>? = null,

    // Purpose classification
    val trackingPurpose: UltrasonicTrackingPurpose,

    // === Enhanced Real-World Context Fields ===

    // Beacon timing characteristics (for fingerprinting)
    // SilverPush beacons typically last 2-5 seconds
    val beaconDurationMs: Pair<Long, Long>? = null,

    // Privacy impact classification
    val privacyImpact: PrivacyImpact = PrivacyImpact.UNKNOWN,

    // Legal/regulatory status
    val legalStatus: LegalStatus = LegalStatus.UNKNOWN,

    // Whether this system has legitimate uses (e.g., LISNR for payments)
    val hasLegitimateUses: Boolean = false,

    // Known deployment locations (for context-aware threat scoring)
    // e.g., Shopkick deployed in Target, Macy's, Best Buy
    val deploymentLocations: List<String> = emptyList(),

    // Real-world confirmation method for users
    // e.g., "Check if detection correlates with TV commercials"
    val confirmationMethod: String? = null,

    // Mitigation advice for users
    // e.g., "Revoke mic permission from apps that don't need it"
    val mitigationAdvice: String? = null,

    override val protocols: Set<DetectionProtocolType> = setOf(DetectionProtocolType.ULTRASONIC)
) : TrackerSignature

/**
 * Ultrasonic modulation types
 *
 * Different tracking systems use different modulation:
 * - FSK (SilverPush): Frequency Shift Keying - shows as pulsing pattern
 * - PSK (Alphonso): Phase Shift Keying - phase-shifted patterns
 * - CHIRP (LISNR): Frequency sweep - higher bandwidth
 * - OOK: On-Off Keying - simple presence beacons
 */
enum class UltrasonicModulation(val displayName: String) {
    CONTINUOUS("Continuous Tone"),
    PULSED("Pulsed Signal"),
    FSK("Frequency Shift Keying (FSK)"),
    PSK("Phase Shift Keying (PSK)"),
    OOK("On-Off Keying (OOK)"),
    CHIRP("CHIRP (Frequency Sweep)"),
    UNKNOWN("Unknown Modulation")
}

/**
 * Purpose classification for ultrasonic beacons
 */
enum class UltrasonicTrackingPurpose(val displayName: String, val privacyConcern: String) {
    AD_TRACKING(
        "Advertising Attribution",
        "Links your devices to ads you've seen, enabling targeted advertising across all your screens"
    ),
    TV_ATTRIBUTION(
        "TV/Video Attribution",
        "Tracks what TV shows and ads you watch, even when your phone is in your pocket"
    ),
    RETAIL_ANALYTICS(
        "Retail Analytics",
        "Tracks your movement within stores, time spent in aisles, and store visit frequency"
    ),
    CROSS_DEVICE_LINKING(
        "Cross-Device Linking",
        "Creates hidden links between your phone, tablet, laptop, and smart TV for unified tracking"
    ),
    LOCATION_VERIFICATION(
        "Location Verification",
        "Verifies your physical presence at specific locations for advertising or analytics"
    ),
    PRESENCE_DETECTION(
        "Presence Detection",
        "Detects when you are nearby for triggering location-based actions"
    ),
    UNKNOWN(
        "Unknown Purpose",
        "Purpose unknown - may be tracking your device"
    )
}

/**
 * Privacy impact classification for ultrasonic tracking
 *
 * Understanding the real privacy implications:
 * - Cross-device tracking: Links phone, tablet, laptop, smart TV
 * - De-anonymization: Can link anonymous browsing to real identity
 * - Location history: Tracks store visits, time spent
 * - Ad attribution: Knows which TV ad made you buy
 * - Household mapping: Identifies all devices in home
 */
enum class PrivacyImpact(val displayName: String, val description: String) {
    CROSS_DEVICE_LINKING(
        "Cross-Device Linking",
        "Links all your devices together. Can de-anonymize anonymous browsing " +
            "by connecting your laptop browsing to your phone's real identity."
    ),
    VIEWING_HABITS(
        "Viewing Habits",
        "Tracks what you watch on TV. Companies know your show preferences, " +
            "ad exposure, and can sell this data to advertisers."
    ),
    LOCATION_TRACKING(
        "Location Tracking",
        "Tracks your physical location history. Knows which stores you visit, " +
            "how long you spend there, and your shopping patterns."
    ),
    HOUSEHOLD_MAPPING(
        "Household Mapping",
        "Maps all devices in your home. Can identify household members, " +
            "shared devices, and build detailed household profiles."
    ),
    AD_ATTRIBUTION(
        "Ad Attribution",
        "Links TV ads to your purchases. Advertisers know which ad made you " +
            "visit a website or buy a product."
    ),
    UNKNOWN(
        "Unknown",
        "Privacy impact is not fully understood."
    )
}

/**
 * Legal/regulatory status of ultrasonic tracking systems
 */
enum class LegalStatus(val displayName: String, val description: String) {
    FTC_INVESTIGATED(
        "FTC Investigated",
        "FTC has investigated this tracking technology. Ruled that SilverPush-style " +
            "tracking can be deceptive if users aren't properly informed."
    ),
    FTC_SETTLED(
        "FTC Settlement",
        "Company paid FTC settlement for privacy violations related to this tracking."
    ),
    GDPR_CONSENT_REQUIRED(
        "GDPR Consent Required",
        "Under GDPR, explicit consent is required for this type of tracking in the EU."
    ),
    CCPA_OPT_OUT_REQUIRED(
        "CCPA Opt-Out Required",
        "Under CCPA, companies must disclose this tracking and allow California residents to opt out."
    ),
    BANNED_SOME_COUNTRIES(
        "Banned in Some Countries",
        "This tracking method is banned without explicit consent in certain jurisdictions."
    ),
    LEGITIMATE(
        "Legitimate",
        "Generally considered legitimate with proper user consent."
    ),
    UNKNOWN(
        "Unknown",
        "Legal status is not well established."
    )
}

/**
 * Known false positive sources for ultrasonic frequencies
 *
 * These are common sources of ultrasonic frequencies that are NOT tracking beacons.
 * Use these to reduce false positive alerts.
 */
enum class FalsePositiveUltrasonicSource(
    val displayName: String,
    val frequencyRanges: List<IntRange>,
    val description: String
) {
    CRT_MONITOR(
        "CRT Monitor/TV",
        listOf(15700..15800),
        "CRT horizontal scan at 15.75 kHz. Fading out as CRTs become rare."
    ),
    LCD_BACKLIGHT_PWM(
        "LCD Backlight PWM",
        listOf(20000..25000),
        "LCD backlight pulse-width modulation can generate ultrasonic frequencies."
    ),
    SWITCHING_POWER_SUPPLY(
        "Switching Power Supply",
        listOf(20000..100000),
        "Switching power supplies (laptop chargers, USB adapters) emit ultrasonic noise."
    ),
    HVAC_HUMIDIFIER(
        "HVAC Ultrasonic Humidifier",
        listOf(20000..25000),
        "Ultrasonic humidifiers in HVAC systems generate constant ultrasonic frequencies."
    ),
    DOG_PEST_DETERRENT(
        "Dog/Pest Deterrent",
        listOf(18000..25000),
        "Ultrasonic pest/dog deterrent devices emit continuous or pulsed tones."
    ),
    HARD_DRIVE_WHINE(
        "Hard Drive (older)",
        listOf(17000..20000),
        "Older mechanical hard drives can produce high-frequency whine."
    ),
    ELECTRONIC_INTERFERENCE(
        "Electronic Interference",
        listOf(17500..22000),
        "General electronic interference from various devices."
    ),
    FLUORESCENT_LIGHTS(
        "Fluorescent Lights",
        listOf(20000..40000),
        "Fluorescent light ballasts can generate ultrasonic frequencies."
    )
}

// ============================================================================
// RF Tracker Signatures
// ============================================================================

/**
 * Sub-GHz RF tracker signature
 */
data class RfTrackerSignature(
    override val id: String,
    override val name: String,
    override val manufacturer: String,
    override val category: TrackerCategory,
    override val threatLevel: ThreatLevel,
    override val description: String,

    // Frequency specifications
    val frequencyRanges: List<FrequencyRange>,
    val modulationType: RfModulation,

    // Protocol info
    val protocolId: Int? = null,
    val protocolName: String? = null,

    // Regional info
    val regions: Set<RfRegion> = setOf(RfRegion.WORLDWIDE),

    override val protocols: Set<DetectionProtocolType> = setOf(DetectionProtocolType.SUBGHZ_RF)
) : TrackerSignature

/**
 * Frequency range specification
 */
data class FrequencyRange(
    val centerHz: Long,
    val bandwidthHz: Long = 1_000_000L,
    val purpose: String = ""
) {
    fun contains(frequencyHz: Long): Boolean {
        val halfBandwidth = bandwidthHz / 2
        return frequencyHz in (centerHz - halfBandwidth)..(centerHz + halfBandwidth)
    }
}

/**
 * RF modulation types
 */
enum class RfModulation {
    AM, FM, ASK, FSK, PSK, OOK, GFSK, LORA, UNKNOWN
}

/**
 * RF regulatory regions
 */
enum class RfRegion(val displayName: String) {
    NORTH_AMERICA("North America (FCC)"),
    EUROPE("Europe (ETSI)"),
    ASIA_PACIFIC("Asia Pacific"),
    JAPAN("Japan (MIC)"),
    WORLDWIDE("Worldwide")
}

// ============================================================================
// iBeacon Data Structure
// ============================================================================

/**
 * Parsed iBeacon advertisement data
 */
data class IBeaconData(
    val uuid: String,           // 16-byte proximity UUID
    val major: Int,             // 2-byte major identifier
    val minor: Int,             // 2-byte minor identifier
    val txPower: Int,           // 1-byte calibrated TX power at 1m
    val rssi: Int               // Measured signal strength
) {
    /**
     * Calculate approximate distance in meters
     */
    fun calculateDistance(): Double {
        if (rssi == 0) return -1.0

        val ratio = rssi.toDouble() / txPower.toDouble()
        return if (ratio < 1.0) {
            Math.pow(ratio, 10.0)
        } else {
            0.89976 * Math.pow(ratio, 7.7095) + 0.111
        }
    }

    /**
     * Get proximity zone
     */
    fun getProximity(): IBeaconProximity = when {
        calculateDistance() < 0 -> IBeaconProximity.UNKNOWN
        calculateDistance() < 0.5 -> IBeaconProximity.IMMEDIATE
        calculateDistance() < 3.0 -> IBeaconProximity.NEAR
        else -> IBeaconProximity.FAR
    }
}

enum class IBeaconProximity(val displayName: String) {
    IMMEDIATE("Immediate (< 0.5m)"),
    NEAR("Near (0.5-3m)"),
    FAR("Far (> 3m)"),
    UNKNOWN("Unknown")
}

// ============================================================================
// Eddystone Data Structures
// ============================================================================

/**
 * Base class for Eddystone frames
 */
sealed class EddystoneFrame {
    abstract val rssi: Int

    /**
     * Eddystone-UID: Static namespace + instance identifier
     */
    data class UID(
        val namespace: ByteArray,    // 10 bytes
        val instance: ByteArray,     // 6 bytes
        val txPower: Int,
        override val rssi: Int
    ) : EddystoneFrame() {
        val namespaceHex: String get() = namespace.toHexString()
        val instanceHex: String get() = instance.toHexString()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UID) return false
            return namespace.contentEquals(other.namespace) &&
                   instance.contentEquals(other.instance)
        }

        override fun hashCode(): Int = namespace.contentHashCode() + instance.contentHashCode()
    }

    /**
     * Eddystone-URL: Compressed URL
     */
    data class URL(
        val url: String,
        val txPower: Int,
        override val rssi: Int
    ) : EddystoneFrame()

    /**
     * Eddystone-TLM: Telemetry data
     */
    data class TLM(
        val batteryMillivolts: Int,
        val temperatureCelsius: Float,
        val advertisementCount: Long,
        val uptimeSeconds: Long,
        override val rssi: Int
    ) : EddystoneFrame()

    /**
     * Eddystone-EID: Ephemeral Identifier (rotating)
     */
    data class EID(
        val ephemeralId: ByteArray,  // 8 bytes - rotates periodically
        val txPower: Int,
        override val rssi: Int
    ) : EddystoneFrame() {
        val ephemeralIdHex: String get() = ephemeralId.toHexString()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EID) return false
            return ephemeralId.contentEquals(other.ephemeralId)
        }

        override fun hashCode(): Int = ephemeralId.contentHashCode()
    }
}

// ============================================================================
// BLE Address Analysis
// ============================================================================

/**
 * Analysis of BLE MAC address behavior
 */
data class BleAddressAnalysis(
    val macAddress: String,
    val addressType: BleAddressCategory,
    val isRotating: Boolean,
    val rotationIntervalMs: Long?,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenAtLocations: Int,
    val advertisementConsistency: Float,  // 0-1, same payload with different MACs?
    val followingScore: Float              // 0-1, appears at multiple user locations?
)

/**
 * BLE address type categories
 */
enum class BleAddressCategory(val displayName: String) {
    PUBLIC("Public (Manufacturer Assigned)"),
    RANDOM_STATIC("Random Static"),
    RANDOM_RESOLVABLE("Random Resolvable Private"),
    RANDOM_NON_RESOLVABLE("Random Non-Resolvable Private")
}

/**
 * Determine BLE address category from MAC address bytes
 */
fun classifyBleAddress(macBytes: ByteArray): BleAddressCategory {
    if (macBytes.size < 6) return BleAddressCategory.PUBLIC

    val upperTwoBits = (macBytes[0].toInt() and 0xC0) shr 6

    return when (upperTwoBits) {
        0b00 -> BleAddressCategory.PUBLIC           // 00 = Public
        0b11 -> BleAddressCategory.RANDOM_STATIC    // 11 = Random Static
        0b01 -> BleAddressCategory.RANDOM_RESOLVABLE // 01 = Resolvable Private
        0b10 -> BleAddressCategory.RANDOM_NON_RESOLVABLE // 10 = Non-resolvable Private
        else -> BleAddressCategory.PUBLIC
    }
}

/**
 * Parse MAC address string to bytes
 */
fun parseMacAddress(mac: String): ByteArray {
    return mac.split(":")
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

// ============================================================================
// Cross-Domain Correlation
// ============================================================================

/**
 * Correlated threat from multiple detection domains
 */
data class CorrelatedThreat(
    val id: String,
    val timestamp: Long,
    val primarySource: DetectionProtocolType,

    // Linked detections
    val bleDetection: BleDetectionInfo? = null,
    val rfDetection: RfDetectionInfo? = null,
    val ultrasonicDetection: UltrasonicDetectionInfo? = null,
    val wifiDetection: WifiDetectionInfo? = null,

    // Correlation metrics
    val correlationScore: Float,          // 0-1 likelihood same source
    val temporalOverlap: Float,           // 0-1 how much detections overlap in time
    val spatialProximity: Float,          // 0-1 how close detections are spatially

    // Location history
    val sharedLocations: List<LocationPoint>,

    // Aggregated threat assessment
    val aggregatedThreatLevel: ThreatLevel,
    val threatIndicators: List<String>,

    // Tracking behavior
    val isFollowing: Boolean,
    val followDurationMs: Long,
    val uniqueLocationsFollowed: Int
)

data class BleDetectionInfo(
    val macAddress: String,
    val deviceName: String?,
    val manufacturerId: Int?,
    val serviceUuids: List<String>,
    val rssi: Int,
    val timestamp: Long
)

data class RfDetectionInfo(
    val frequency: Long,
    val modulation: String,
    val rssi: Int,
    val protocolName: String?,
    val timestamp: Long
)

data class UltrasonicDetectionInfo(
    val frequency: Int,
    val amplitudeDb: Double,
    val source: String,
    val timestamp: Long
)

data class WifiDetectionInfo(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val channel: Int,
    val timestamp: Long
)

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float? = null
)

// ============================================================================
// Utility Extensions
// ============================================================================

/**
 * Convert ByteArray to hex string
 */
fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

/**
 * Convert hex string to ByteArray
 */
fun String.hexToByteArray(): ByteArray {
    val hex = this.replace(":", "").replace(" ", "")
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

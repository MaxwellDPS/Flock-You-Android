package com.flockyou.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a detected surveillance device
 */
@Entity(
    tableName = "detections",
    indices = [
        Index(value = ["macAddress"]),
        Index(value = ["ssid"]),
        Index(value = ["threatLevel"]),
        Index(value = ["deviceType"]),
        Index(value = ["timestamp"]),
        Index(value = ["lastSeenTimestamp"]),
        Index(value = ["isActive"]),
        Index(value = ["serviceUuids"])
    ]
)
data class Detection(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val protocol: DetectionProtocol,
    val detectionMethod: DetectionMethod,
    val deviceType: DeviceType,
    val deviceName: String? = null,
    val macAddress: String? = null,
    val ssid: String? = null,
    val rssi: Int,
    val signalStrength: SignalStrength,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val threatLevel: ThreatLevel,
    val threatScore: Int = 0,
    val manufacturer: String? = null,
    val firmwareVersion: String? = null,
    @ColumnInfo(name = "serviceUuids", defaultValue = "NULL")
    val serviceUuids: String? = null,  // BLE service UUIDs (comma-separated)
    val matchedPatterns: String? = null, // JSON array of matched patterns
    val rawData: String? = null, // Raw advertisement/frame data as hex string for advanced mode display
    val isActive: Boolean = true,
    val seenCount: Int = 1, // Number of times this device has been seen
    val lastSeenTimestamp: Long = System.currentTimeMillis(), // When device was last seen

    // Detection source tracking
    @ColumnInfo(name = "detectionSource", defaultValue = "UNKNOWN")
    val detectionSource: DetectionSource = DetectionSource.UNKNOWN,

    // False positive analysis fields (computed in background)
    @ColumnInfo(name = "fpScore", defaultValue = "NULL")
    val fpScore: Float? = null,              // 0.0-1.0, null if not analyzed
    @ColumnInfo(name = "fpReason", defaultValue = "NULL")
    val fpReason: String? = null,            // Primary reason for FP classification
    @ColumnInfo(name = "fpCategory", defaultValue = "NULL")
    val fpCategory: String? = null,          // FpCategory name
    @ColumnInfo(name = "analyzedAt", defaultValue = "NULL")
    val analyzedAt: Long? = null,            // Timestamp when FP analysis was performed
    @ColumnInfo(name = "llmAnalyzed", defaultValue = "0")
    val llmAnalyzed: Boolean = false         // Whether LLM was used (vs rule-based only)
)

enum class DetectionProtocol(val displayName: String, val icon: String) {
    WIFI("WiFi", "📡"),
    BLUETOOTH_LE("Bluetooth LE", "📶"),
    CELLULAR("Cellular", "📱"),
    SATELLITE("Satellite", "🛰️"),
    GNSS("GNSS/GPS", "🛰️"),
    AUDIO("Audio/Ultrasonic", "🔊"),
    RF("RF Analysis", "📻")
}

enum class DetectionMethod(val displayName: String, val description: String) {
    SSID_PATTERN("SSID Match", "Device identified by WiFi network name pattern"),
    MAC_PREFIX("MAC Address", "Device identified by manufacturer OUI prefix"),
    MANUFACTURER_OUI("Manufacturer OUI", "Device identified by manufacturer OUI fingerprint"),
    BEHAVIOR_ANALYSIS("Behavior Analysis", "Device identified through behavioral pattern analysis"),
    BLE_DEVICE_NAME("BLE Name", "Device identified by Bluetooth advertised name"),
    BLE_SERVICE_UUID("BLE Service", "Device identified by Bluetooth service UUIDs"),
    RAVEN_SERVICE_UUID("Raven Services", "Raven gunshot detector identified by specific BLE services"),
    PROBE_REQUEST("Probe Request", "Device detected via WiFi probe request"),
    BEACON_FRAME("Beacon Frame", "Device detected via WiFi beacon broadcast"),
    // Cellular anomaly detection methods
    CELL_ENCRYPTION_DOWNGRADE("Encryption Downgrade", "Network forced from 4G/5G to 2G with weak encryption"),
    CELL_SUSPICIOUS_NETWORK("Suspicious Network", "Connected to test/invalid MCC-MNC identifier"),
    CELL_TOWER_CHANGE("Unexpected Cell Change", "Cell tower changed without user movement"),
    CELL_RAPID_SWITCHING("Rapid Cell Switching", "Phone switching towers abnormally fast"),
    CELL_SIGNAL_ANOMALY("Signal Anomaly", "Sudden unexplained signal strength change"),
    CELL_LAC_TAC_ANOMALY("Location Area Anomaly", "LAC/TAC changed unexpectedly"),
    // Satellite anomaly detection methods
    SAT_UNEXPECTED_CONNECTION("Unexpected Satellite", "Satellite connection when terrestrial available"),
    SAT_FORCED_HANDOFF("Forced Satellite Handoff", "Rapid or suspicious handoff to satellite"),
    SAT_SUSPICIOUS_NTN("Suspicious NTN", "Unusual NTN parameters suggesting spoofing"),
    SAT_TIMING_ANOMALY("Satellite Timing Anomaly", "NTN timing doesn't match claimed orbit"),
    SAT_DOWNGRADE("Downgrade to Satellite", "Forced from better tech to satellite"),
    // WiFi rogue AP detection methods
    WIFI_EVIL_TWIN("Evil Twin AP", "Same SSID broadcast from multiple different MAC addresses"),
    WIFI_DEAUTH_ATTACK("Deauth Attack", "Rapid WiFi disconnections indicating deauth flood"),
    WIFI_HIDDEN_CAMERA("Hidden Camera", "WiFi network matching hidden camera patterns"),
    WIFI_ROGUE_AP("Rogue AP", "Suspicious or unauthorized access point"),
    WIFI_SIGNAL_ANOMALY("WiFi Signal Anomaly", "Unusual WiFi signal behavior"),
    WIFI_FOLLOWING("Following Network", "Network appearing at multiple locations you visit"),
    WIFI_SURVEILLANCE_VAN("Surveillance Van", "Mobile hotspot matching surveillance patterns"),
    WIFI_KARMA_ATTACK("Karma Attack", "AP responding to all probe requests"),
    // RF signal analysis methods
    RF_JAMMER("RF Jammer", "Sudden drop in all wireless signals indicating jamming"),
    RF_DRONE("Drone Detected", "Drone WiFi signal detected nearby"),
    RF_SURVEILLANCE_AREA("Surveillance Area", "High concentration of surveillance cameras"),
    RF_SPECTRUM_ANOMALY("Spectrum Anomaly", "Unusual RF spectrum activity"),
    RF_UNUSUAL_ACTIVITY("Unusual RF Activity", "Abnormal wireless activity patterns"),
    RF_INTERFERENCE("RF Interference", "Significant change in RF environment"),
    RF_HIDDEN_TRANSMITTER("Hidden Transmitter", "Possible covert RF transmission detected"),
    // Ultrasonic detection methods
    ULTRASONIC_TRACKING_BEACON("Tracking Beacon", "Ultrasonic beacon for cross-device tracking"),
    ULTRASONIC_AD_BEACON("Ad Beacon", "Advertising/TV tracking ultrasonic signal"),
    ULTRASONIC_RETAIL_BEACON("Retail Beacon", "Retail location tracking ultrasonic"),
    ULTRASONIC_CONTINUOUS("Continuous Ultrasonic", "Persistent ultrasonic transmission"),
    ULTRASONIC_CROSS_DEVICE("Cross-Device Tracking", "Signal linking multiple devices"),
    ULTRASONIC_UNKNOWN("Unknown Ultrasonic", "Unidentified ultrasonic source"),
    // GNSS satellite detection methods
    GNSS_SPOOFING("GNSS Spoofing", "Fake satellite signals detected - position may be manipulated"),
    GNSS_JAMMING("GNSS Jamming", "Satellite signals being blocked or degraded"),
    GNSS_SIGNAL_ANOMALY("GNSS Signal Anomaly", "Unusual satellite signal characteristics"),
    GNSS_GEOMETRY_ANOMALY("GNSS Geometry Anomaly", "Impossible satellite positions detected"),
    GNSS_SIGNAL_LOSS("GNSS Signal Loss", "Sudden loss of satellite signals"),
    GNSS_CLOCK_ANOMALY("GNSS Clock Anomaly", "Satellite timing discontinuity detected"),
    GNSS_MULTIPATH("GNSS Multipath", "Severe signal reflection interference"),
    GNSS_CONSTELLATION_ANOMALY("Constellation Anomaly", "Unexpected satellite constellation behavior"),
    // Smart home/IoT detection methods
    IOT_DOORBELL("Smart Doorbell", "Smart doorbell device detected via WiFi/BLE"),
    IOT_CAMERA("Smart Camera", "Smart home camera detected via WiFi/BLE"),
    IOT_SECURITY_SYSTEM("Security System", "Home security system detected"),
    AMAZON_SIDEWALK_BRIDGE("Sidewalk Bridge", "Amazon Sidewalk mesh network bridge detected"),
    // Tracker detection methods
    AIRTAG_DETECTED("AirTag Detected", "Apple AirTag tracker detected via BLE"),
    TILE_DETECTED("Tile Detected", "Tile tracker detected via BLE"),
    SMARTTAG_DETECTED("SmartTag Detected", "Samsung SmartTag tracker detected via BLE"),
    UNKNOWN_TRACKER("Unknown Tracker", "Unknown BLE tracker following pattern"),
    TRACKER_FOLLOWING("Tracker Following", "Tracker device detected at multiple locations"),
    // Retail/commercial detection methods
    RETAIL_BEACON_DETECTED("Retail Beacon", "Retail tracking beacon detected"),
    CROWD_SENSOR("Crowd Sensor", "Crowd analytics or people counting sensor"),
    FACIAL_REC_SYSTEM("Facial Recognition", "Facial recognition camera system detected"),
    // Traffic enforcement detection methods
    TRAFFIC_CAMERA("Traffic Camera", "Traffic enforcement camera detected"),
    TOLL_SYSTEM("Toll System", "Electronic toll collection system detected"),
    // Network attack detection methods
    PINEAPPLE_DETECTED("WiFi Pineapple", "Hak5 WiFi Pineapple pen testing device detected"),
    MITM_DETECTED("MITM Attack", "Potential man-in-the-middle attack detected"),
    PACKET_CAPTURE("Packet Capture", "Network packet capture device detected"),
    // Flipper Zero and hacking tool detection methods
    FLIPPER_ZERO_DETECTED("Flipper Zero", "Flipper Zero multi-tool hacking device detected via BLE"),
    FLIPPER_BLE_SPAM("Flipper BLE Spam", "Flipper Zero BLE spam attack detected (popup flood)"),
    FLIPPER_FAST_PAIR_SPAM("Fast Pair Spam", "Android Fast Pair spam attack detected"),
    FLIPPER_APPLE_SPAM("Apple BLE Spam", "Apple device impersonation spam attack detected"),
    HACKING_TOOL_DETECTED("Hacking Tool", "Security testing/hacking tool detected nearby"),
    KARMA_ATTACK_DETECTED("Karma Attack", "WiFi Pineapple karma attack - AP responding to all probes"),
    // Law enforcement detection methods
    ACOUSTIC_SENSOR("Acoustic Sensor", "ShotSpotter or similar acoustic gunshot sensor"),
    FORENSIC_DEVICE("Forensic Device", "Mobile forensics device detected"),
    SURVEILLANCE_SYSTEM("Surveillance System", "General surveillance system detected")
}

enum class DeviceType(val displayName: String, val emoji: String) {
    FLOCK_SAFETY_CAMERA("Flock Safety ALPR", "📸"),
    PENGUIN_SURVEILLANCE("Penguin Surveillance", "🐧"),
    PIGVISION_SYSTEM("Pigvision System", "🐷"),
    RAVEN_GUNSHOT_DETECTOR("Raven Gunshot Detector", "🦅"),
    MOTOROLA_POLICE_TECH("Motorola Police Tech", "📻"),
    AXON_POLICE_TECH("Axon Police Tech", "⚡"),
    L3HARRIS_SURVEILLANCE("L3Harris Surveillance", "🛰️"),
    CELLEBRITE_FORENSICS("Cellebrite Forensics", "📱"),
    BODY_CAMERA("Body Camera", "🎥"),
    POLICE_RADIO("Police Radio System", "📡"),
    POLICE_VEHICLE("Police/Emergency Vehicle", "🚔"),
    FLEET_VEHICLE("Fleet Vehicle", "🚐"),
    STINGRAY_IMSI("Cell Site Simulator", "📶"),
    // WiFi threat device types
    ROGUE_AP("Rogue Access Point", "🏴"),
    HIDDEN_CAMERA("Hidden Camera", "📹"),
    SURVEILLANCE_VAN("Surveillance Van", "🚙"),
    TRACKING_DEVICE("Tracking Device", "📍"),
    // RF device types
    RF_JAMMER("RF Jammer", "📵"),
    DRONE("Drone/UAV", "🚁"),
    SURVEILLANCE_INFRASTRUCTURE("Surveillance Infrastructure", "🏢"),
    RF_INTERFERENCE("RF Interference", "⚡"),
    RF_ANOMALY("RF Anomaly", "📊"),
    HIDDEN_TRANSMITTER("Hidden Transmitter", "📻"),
    // Audio device types
    ULTRASONIC_BEACON("Ultrasonic Beacon", "🔊"),
    // Satellite device types
    SATELLITE_NTN("Satellite NTN Device", "🛰️"),
    // GNSS device types
    GNSS_SPOOFER("GNSS Spoofer", "🎯"),
    GNSS_JAMMER("GNSS Jammer", "📵"),
    // Smart home/IoT surveillance devices
    RING_DOORBELL("Ring Doorbell", "🔔"),
    NEST_CAMERA("Nest/Google Camera", "📷"),
    AMAZON_SIDEWALK("Amazon Sidewalk Device", "📶"),
    WYZE_CAMERA("Wyze Camera", "👁️"),
    ARLO_CAMERA("Arlo Camera", "🎦"),
    EUFY_CAMERA("Eufy Camera", "🏠"),
    BLINK_CAMERA("Blink Camera", "💡"),
    SIMPLISAFE_DEVICE("SimpliSafe Device", "🛡️"),
    ADT_DEVICE("ADT Security Device", "🔐"),
    VIVINT_DEVICE("Vivint Smart Home", "🏡"),
    // Retail/commercial tracking
    BLUETOOTH_BEACON("Bluetooth Beacon", "📍"),
    RETAIL_TRACKER("Retail Tracker", "🛒"),
    CROWD_ANALYTICS("Crowd Analytics Sensor", "👥"),
    FACIAL_RECOGNITION("Facial Recognition System", "🤖"),
    // AirTag/tracker devices
    AIRTAG("Apple AirTag", "🍎"),
    TILE_TRACKER("Tile Tracker", "🔲"),
    SAMSUNG_SMARTTAG("Samsung SmartTag", "📱"),
    GENERIC_BLE_TRACKER("BLE Tracker", "📡"),
    // Traffic enforcement
    SPEED_CAMERA("Speed Camera", "⚡"),
    RED_LIGHT_CAMERA("Red Light Camera", "🚦"),
    TOLL_READER("Toll/E-ZPass Reader", "💳"),
    TRAFFIC_SENSOR("Traffic Sensor", "🚗"),
    // Law enforcement specific
    SHOTSPOTTER("ShotSpotter Sensor", "🎯"),
    CLEARVIEW_AI("Clearview AI System", "👤"),
    PALANTIR_DEVICE("Palantir Device", "🔮"),
    GRAYKEY_DEVICE("GrayKey Forensics", "🔓"),
    // Network surveillance
    WIFI_PINEAPPLE("WiFi Pineapple", "🍍"),
    PACKET_SNIFFER("Packet Sniffer", "🕵️"),
    MAN_IN_MIDDLE("MITM Device", "🔀"),
    // Hacking tools
    FLIPPER_ZERO("Flipper Zero", "🐬"),
    FLIPPER_ZERO_SPAM("Flipper Zero (BLE Spam)", "🐬"),
    HACKRF_SDR("HackRF/SDR Device", "📻"),
    PROXMARK("Proxmark RFID Tool", "💳"),
    USB_RUBBER_DUCKY("USB Rubber Ducky", "🦆"),
    LAN_TURTLE("LAN Turtle", "🐢"),
    BASH_BUNNY("Bash Bunny", "🐰"),
    KEYCROC("Key Croc", "🐊"),
    SHARK_JACK("Shark Jack", "🦈"),
    SCREEN_CRAB("Screen Crab", "🦀"),
    GENERIC_HACKING_TOOL("Hacking Tool", "🔧"),
    // Misc surveillance
    LICENSE_PLATE_READER("License Plate Reader", "🚘"),
    CCTV_CAMERA("CCTV Camera", "📹"),
    PTZ_CAMERA("PTZ Camera", "🎥"),
    THERMAL_CAMERA("Thermal Camera", "🌡️"),
    NIGHT_VISION("Night Vision Device", "🌙"),
    UNKNOWN_SURVEILLANCE("Unknown Surveillance", "❓")
}

enum class SignalStrength(val displayName: String, val description: String) {
    EXCELLENT("Excellent", "Very close - within ~10m"),
    GOOD("Good", "Close proximity - within ~25m"),
    MEDIUM("Medium", "Moderate distance - within ~50m"),
    WEAK("Weak", "Far - within ~75m"),
    VERY_WEAK("Very Weak", "Edge of range - 75m+"),
    UNKNOWN("Unknown", "Signal strength not available")
}

enum class ThreatLevel(val displayName: String, val description: String) {
    CRITICAL("Critical", "Active acoustic/audio surveillance - recording sounds"),
    HIGH("High", "Confirmed surveillance device - recording vehicle data"),
    MEDIUM("Medium", "Likely surveillance equipment"),
    LOW("Low", "Possible surveillance device"),
    INFO("Info", "Device of interest - may not be surveillance")
}

/**
 * Source of the detection - where the scan data originated from
 */
enum class DetectionSource(val displayName: String) {
    NATIVE_WIFI("Android WiFi Scanner"),
    NATIVE_BLE("Android BLE Scanner"),
    FLIPPER_WIFI("Flipper Zero WiFi"),
    FLIPPER_BLE("Flipper Zero BLE"),
    FLIPPER_SUBGHZ("Flipper Zero Sub-GHz"),
    FLIPPER_IR("Flipper Zero IR"),
    FLIPPER_NFC("Flipper Zero NFC"),
    FLIPPER_WIPS("Flipper Zero WIPS"),
    CELLULAR("Cellular Network"),
    GNSS("GNSS/Satellite"),
    AUDIO("Audio Analysis"),
    UNKNOWN("Unknown Source")
}

/**
 * Converts RSSI value to SignalStrength for WiFi/BLE
 * WiFi/BLE typically ranges from -30 dBm (excellent) to -90 dBm (very weak)
 */
fun rssiToSignalStrength(rssi: Int): SignalStrength = when {
    rssi > -50 -> SignalStrength.EXCELLENT
    rssi > -60 -> SignalStrength.GOOD
    rssi > -70 -> SignalStrength.MEDIUM
    rssi > -80 -> SignalStrength.WEAK
    else -> SignalStrength.VERY_WEAK
}

/**
 * Converts cellular dBm to SignalStrength
 * Cellular signals are typically weaker than WiFi/BLE:
 * - LTE: -50 dBm (excellent) to -120 dBm (no signal)
 * - 5G NR: -80 dBm (excellent) to -120 dBm (no signal)
 * - GSM/WCDMA: -70 dBm (excellent) to -110 dBm (no signal)
 *
 * These thresholds are calibrated for typical 4G/LTE signals.
 */
fun cellularDbmToSignalStrength(dbm: Int): SignalStrength = when {
    dbm > -70 -> SignalStrength.EXCELLENT   // Very strong cellular signal
    dbm > -85 -> SignalStrength.GOOD        // Good 4G/LTE coverage
    dbm > -100 -> SignalStrength.MEDIUM     // Moderate signal, still usable
    dbm > -110 -> SignalStrength.WEAK       // Weak signal, may drop calls
    else -> SignalStrength.VERY_WEAK        // Very weak or no signal
}

/**
 * Converts threat score to ThreatLevel.
 *
 * IMPORTANT: This is a LEGACY function for backward compatibility.
 * For proper threat assessment, use ThreatScoring.calculateThreat() which
 * considers likelihood, impact factor, and confidence.
 *
 * Thresholds are calibrated to ensure severity matches actual threat probability:
 * - CRITICAL (90-100): Confirmed active threat, immediate action needed
 *   Examples: Active IMSI catcher with encryption downgrade, confirmed GPS spoofing
 * - HIGH (70-89): High probability threat, investigate immediately
 *   Examples: Encryption downgrade + unknown cell, tracker following for hours
 * - MEDIUM (50-69): Moderate concern, monitor closely
 *   Examples: Unknown cell in familiar area, new tracker nearby
 * - LOW (30-49): Possible concern, log and watch
 *   Examples: Single cell change while stationary, brief ultrasonic detection
 * - INFO (0-29): Notable but not threatening
 *   Examples: Known smart home device, normal network handoff
 *
 * @param score The calculated threat score (0-100)
 * @return The corresponding ThreatLevel
 */
fun scoreToThreatLevel(score: Int): ThreatLevel = when {
    score >= 90 -> ThreatLevel.CRITICAL
    score >= 70 -> ThreatLevel.HIGH
    score >= 50 -> ThreatLevel.MEDIUM
    score >= 30 -> ThreatLevel.LOW
    else -> ThreatLevel.INFO
}

/**
 * Calculate proper threat level from likelihood and device type.
 *
 * This is the PREFERRED method for determining threat level as it uses
 * the proper formula: threat_score = likelihood * impact_factor * confidence
 *
 * This ensures:
 * - 20% IMSI likelihood -> LOW/INFO severity, not HIGH
 * - 30% spoofing likelihood -> LOW severity, not MEDIUM
 * - Severity correlates with actual threat probability
 *
 * @param baseLikelihood Base probability (0-100) that this is a real threat
 * @param deviceType The type of device detected
 * @param rssi Signal strength in dBm
 * @param hasMultipleIndicators Whether multiple confirming indicators exist
 * @param hasCrossProtocolCorrelation Whether seen on multiple protocols
 * @param isConsumerDevice Whether this is a known consumer IoT device
 * @param seenCount Number of times this device has been detected
 * @return Properly calculated ThreatLevel
 */
fun calculateThreatLevel(
    baseLikelihood: Int,
    deviceType: DeviceType,
    rssi: Int = -70,
    hasMultipleIndicators: Boolean = false,
    hasCrossProtocolCorrelation: Boolean = false,
    isConsumerDevice: Boolean = false,
    seenCount: Int = 1
): ThreatLevel {
    // Use the ThreatScoring system for proper calculation
    // Import: com.flockyou.detection.ThreatScoring
    val impactFactor = getImpactFactorForDeviceType(deviceType)

    // Calculate confidence based on available factors
    var confidence = 0.5

    // Signal strength adjustments
    when {
        rssi > -50 -> confidence += 0.1
        rssi > -60 -> confidence += 0.05
        rssi < -90 -> confidence -= 0.2
        rssi < -80 -> confidence -= 0.1
    }

    // Multiple indicators boost
    if (hasMultipleIndicators) confidence += 0.2
    else confidence -= 0.3  // Single indicator penalty

    // Cross-protocol correlation boost
    if (hasCrossProtocolCorrelation) confidence += 0.3

    // Consumer device penalty
    if (isConsumerDevice) confidence -= 0.2

    // Persistence boost
    if (seenCount > 3) confidence += 0.2

    // Clamp confidence
    confidence = confidence.coerceIn(0.1, 1.0)

    // Calculate final score: likelihood * impact * confidence
    val score = (baseLikelihood * impactFactor * confidence).toInt().coerceIn(0, 100)

    return scoreToThreatLevel(score)
}

/**
 * Get impact factor for a device type.
 * Higher impact = more potential harm if threat is real.
 */
private fun getImpactFactorForDeviceType(deviceType: DeviceType): Double = when (deviceType) {
    // Maximum impact - intercepts all communications
    DeviceType.STINGRAY_IMSI -> 2.0
    DeviceType.CELLEBRITE_FORENSICS -> 2.0
    DeviceType.GRAYKEY_DEVICE -> 2.0
    DeviceType.MAN_IN_MIDDLE -> 2.0

    // High impact - can cause physical harm
    DeviceType.GNSS_SPOOFER -> 1.8
    DeviceType.GNSS_JAMMER -> 1.8
    DeviceType.RF_JAMMER -> 1.8
    DeviceType.WIFI_PINEAPPLE -> 1.8
    DeviceType.ROGUE_AP -> 1.7

    // Hacking tools - context-dependent impact
    DeviceType.FLIPPER_ZERO -> 1.5  // Can be legitimate or malicious
    DeviceType.FLIPPER_ZERO_SPAM -> 1.9  // Active attack
    DeviceType.HACKRF_SDR -> 1.6
    DeviceType.PROXMARK -> 1.7  // RFID cloning capability
    DeviceType.BASH_BUNNY -> 1.8
    DeviceType.LAN_TURTLE -> 1.7
    DeviceType.USB_RUBBER_DUCKY -> 1.8
    DeviceType.KEYCROC -> 1.8
    DeviceType.SHARK_JACK -> 1.7
    DeviceType.SCREEN_CRAB -> 1.6
    DeviceType.GENERIC_HACKING_TOOL -> 1.5

    // Tracking/stalking concern
    DeviceType.AIRTAG -> 1.5
    DeviceType.TILE_TRACKER -> 1.5
    DeviceType.SAMSUNG_SMARTTAG -> 1.5
    DeviceType.GENERIC_BLE_TRACKER -> 1.5
    DeviceType.TRACKING_DEVICE -> 1.5

    // Privacy violations
    DeviceType.HIDDEN_CAMERA -> 1.3
    DeviceType.HIDDEN_TRANSMITTER -> 1.3
    DeviceType.FLOCK_SAFETY_CAMERA -> 1.2
    DeviceType.LICENSE_PLATE_READER -> 1.2
    DeviceType.FACIAL_RECOGNITION -> 1.2

    // Consumer IoT - lower impact
    DeviceType.RING_DOORBELL -> 0.8
    DeviceType.NEST_CAMERA -> 0.8
    DeviceType.WYZE_CAMERA -> 0.8
    DeviceType.AMAZON_SIDEWALK -> 0.7

    // Traffic infrastructure - minimal impact
    DeviceType.SPEED_CAMERA -> 0.6
    DeviceType.RED_LIGHT_CAMERA -> 0.6
    DeviceType.TOLL_READER -> 0.6
    DeviceType.TRAFFIC_SENSOR -> 0.5

    // Default for unknown types
    else -> 1.0
}

/**
 * Get estimated distance from RSSI
 */
fun rssiToDistance(rssi: Int): String {
    // Rough estimation based on free-space path loss
    // Assumes 2.4GHz, reference RSSI of -40 at 1m
    val distance = when {
        rssi > -40 -> "< 1m"
        rssi > -50 -> "~1-5m"
        rssi > -60 -> "~5-15m"
        rssi > -70 -> "~15-30m"
        rssi > -80 -> "~30-50m"
        rssi > -90 -> "~50-100m"
        else -> "> 100m"
    }
    return distance
}

/**
 * Detection pattern configuration
 */
data class DetectionPattern(
    val type: PatternType,
    val pattern: String,
    val deviceType: DeviceType,
    val manufacturer: String?,
    val threatScore: Int,
    val description: String,
    val sourceUrl: String? = null // Link to research/documentation about this pattern
)

enum class PatternType {
    SSID_REGEX,
    MAC_PREFIX,
    BLE_NAME_REGEX,
    BLE_SERVICE_UUID
}

/**
 * A custom surveillance pattern that can be imported/exported.
 * Supports multiple matching criteria per pattern.
 */
data class SurveillancePattern(
    val id: String,
    val name: String,
    val description: String,
    val deviceType: DeviceType,
    val manufacturer: String? = null,
    val threatLevel: ThreatLevel = ThreatLevel.HIGH,
    val ssidPatterns: List<String>? = null,    // Regex patterns for SSID matching
    val bleNamePatterns: List<String>? = null, // Regex patterns for BLE name matching
    val macPrefixes: List<String>? = null,     // MAC OUI prefixes (e.g., "AA:BB:CC")
    val serviceUuids: List<String>? = null,    // BLE service UUIDs
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

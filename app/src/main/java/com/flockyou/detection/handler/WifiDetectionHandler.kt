package com.flockyou.detection.handler

import android.content.Context
import android.net.wifi.ScanResult
import android.os.Build
import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionPattern
import com.flockyou.data.model.DetectionPatterns
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.rssiToSignalStrength
import com.flockyou.data.model.scoreToThreatLevel
import com.flockyou.service.RogueWifiMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi Detection Handler
 *
 * Unified handler for WiFi-based surveillance detection that encapsulates:
 * 1. SSID pattern matching (Flock Safety, police tech, surveillance patterns)
 * 2. MAC prefix matching (OUI-based manufacturer identification)
 * 3. RogueWifiMonitor integration (evil twin, deauth, hidden cameras, following networks)
 * 4. AI prompt generation for WiFi detections
 *
 * ## Detection Methods
 * - [DetectionMethod.SSID_PATTERN] - SSID regex pattern matching
 * - [DetectionMethod.MAC_PREFIX] - MAC address OUI matching
 * - [DetectionMethod.WIFI_EVIL_TWIN] - Same SSID from different MAC addresses
 * - [DetectionMethod.WIFI_DEAUTH_ATTACK] - Rapid disconnection patterns
 * - [DetectionMethod.WIFI_HIDDEN_CAMERA] - Hidden camera SSID/OUI patterns
 * - [DetectionMethod.WIFI_ROGUE_AP] - Suspicious access points
 * - [DetectionMethod.WIFI_FOLLOWING] - Networks that appear at multiple user locations
 * - [DetectionMethod.WIFI_SURVEILLANCE_VAN] - Mobile surveillance hotspots
 *
 * ## Supported Device Types
 * - Flock Safety cameras and variants
 * - Police technology (Axon, Motorola)
 * - Hidden cameras
 * - Surveillance vans
 * - Rogue access points / WiFi Pineapple
 * - Tracking devices (following networks)
 *
 * @author Flock You Android Team
 */
@Singleton
class WifiDetectionHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WifiDetectionHandler"

        // ==================== THRESHOLD CONFIGURATION ====================

        /** Minimum RSSI for detection consideration (-100 dBm = very weak, -30 dBm = very strong) */
        const val DEFAULT_RSSI_THRESHOLD = -90

        /** Strong signal threshold for proximity alerts */
        const val STRONG_SIGNAL_RSSI = -50

        /** Very close proximity threshold */
        const val IMMEDIATE_PROXIMITY_RSSI = -40

        /** Rate limit between detections of the same device (milliseconds) */
        const val DETECTION_RATE_LIMIT_MS = 30000L
    }

    // ==================== Handler Properties ====================

    val protocol: DetectionProtocol = DetectionProtocol.WIFI

    val supportedDeviceTypes: Set<DeviceType> = setOf(
        // Flock Safety
        DeviceType.FLOCK_SAFETY_CAMERA,
        // Police Technology
        DeviceType.AXON_POLICE_TECH,
        DeviceType.MOTOROLA_POLICE_TECH,
        DeviceType.BODY_CAMERA,
        DeviceType.POLICE_RADIO,
        DeviceType.POLICE_VEHICLE,
        DeviceType.L3HARRIS_SURVEILLANCE,
        // Surveillance
        DeviceType.PENGUIN_SURVEILLANCE,
        DeviceType.PIGVISION_SYSTEM,
        DeviceType.UNKNOWN_SURVEILLANCE,
        DeviceType.RAVEN_GUNSHOT_DETECTOR,
        // WiFi-specific threats
        DeviceType.ROGUE_AP,
        DeviceType.HIDDEN_CAMERA,
        DeviceType.SURVEILLANCE_VAN,
        DeviceType.TRACKING_DEVICE,
        DeviceType.WIFI_PINEAPPLE,
        DeviceType.PACKET_SNIFFER,
        DeviceType.MAN_IN_MIDDLE
    )

    val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.SSID_PATTERN,
        DetectionMethod.MAC_PREFIX,
        DetectionMethod.WIFI_EVIL_TWIN,
        DetectionMethod.WIFI_DEAUTH_ATTACK,
        DetectionMethod.WIFI_HIDDEN_CAMERA,
        DetectionMethod.WIFI_ROGUE_AP,
        DetectionMethod.WIFI_SIGNAL_ANOMALY,
        DetectionMethod.WIFI_FOLLOWING,
        DetectionMethod.WIFI_SURVEILLANCE_VAN,
        DetectionMethod.WIFI_KARMA_ATTACK
    )

    val displayName: String = "WiFi Detection Handler"

    // ==================== State ====================

    private var _isActive: Boolean = false
    val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    val detections: Flow<Detection> = _detections.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    /** Configuration for detection behavior */
    private var config = WifiHandlerConfig()

    /** Last detection time per BSSID for rate limiting */
    private val lastDetectionTime = ConcurrentHashMap<String, Long>()

    // Underlying monitor for advanced detection (evil twins, deauth, following networks)
    private var rogueWifiMonitor: RogueWifiMonitor? = null

    // ==================== Configuration ====================

    /**
     * Configuration for WiFi detection behavior.
     *
     * @property rssiThreshold Minimum RSSI to consider for detection
     * @property enableSsidPatternMatching Enable SSID regex pattern matching
     * @property enableMacPrefixMatching Enable MAC OUI-based detection
     * @property enableRogueApDetection Enable evil twin/deauth/rogue AP detection
     * @property enableHiddenCameraDetection Enable hidden camera pattern detection
     * @property enableSurveillancePatterns Enable police/surveillance tech patterns
     * @property rateLimitMs Rate limit between detections of same device
     */
    data class WifiHandlerConfig(
        val rssiThreshold: Int = DEFAULT_RSSI_THRESHOLD,
        val enableSsidPatternMatching: Boolean = true,
        val enableMacPrefixMatching: Boolean = true,
        val enableRogueApDetection: Boolean = true,
        val enableHiddenCameraDetection: Boolean = true,
        val enableSurveillancePatterns: Boolean = true,
        val rateLimitMs: Long = DETECTION_RATE_LIMIT_MS
    )

    /**
     * Update handler configuration.
     *
     * @param newConfig The new configuration to apply
     */
    fun updateConfig(newConfig: WifiHandlerConfig) {
        config = newConfig
        Log.d(TAG, "Configuration updated: rssiThreshold=${config.rssiThreshold}")
    }

    // ==================== Lifecycle ====================

    fun startMonitoring() {
        if (_isActive) return
        _isActive = true

        rogueWifiMonitor = RogueWifiMonitor(context).apply {
            startMonitoring()
        }

        // Collect anomalies from RogueWifiMonitor and convert to detections
        scope.launch {
            rogueWifiMonitor?.anomalies?.collect { anomalies ->
                anomalies.forEach { anomaly ->
                    val detection = rogueWifiMonitor?.anomalyToDetection(anomaly)
                    if (detection != null) {
                        _detections.emit(detection)
                    }
                }
            }
        }

        Log.d(TAG, "WiFi detection monitoring started")
    }

    fun stopMonitoring() {
        _isActive = false
        rogueWifiMonitor?.stopMonitoring()
        Log.d(TAG, "WiFi detection monitoring stopped")
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
        rogueWifiMonitor?.updateLocation(latitude, longitude)
    }

    fun clearHistory() {
        lastDetectionTime.clear()
        rogueWifiMonitor?.clearHistory()
        Log.d(TAG, "WiFi detection history cleared")
    }

    fun destroy() {
        stopMonitoring()
        rogueWifiMonitor?.destroy()
        rogueWifiMonitor = null
        clearHistory()
    }

    // ==================== Detection Processing ====================

    /**
     * Process WiFi scan results and produce detections.
     *
     * This is the main entry point called by ScanningService. It processes
     * WiFi scan results through multiple detection methods in priority order:
     *
     * 1. SSID pattern matching (highest priority - known surveillance SSIDs)
     * 2. MAC prefix matching (OUI-based manufacturer identification)
     * 3. RogueWifiMonitor analysis (evil twins, following networks, etc.)
     *
     * @param data List of WiFi scan results from WifiManager
     * @return List of detections found
     */
    suspend fun processData(data: List<ScanResult>): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Process each scan result through pattern matching
        for (result in data) {
            val context = scanResultToContext(result) ?: continue

            // Process through pattern matching
            val detection = handlePatternMatching(context)
            if (detection != null) {
                detections.add(detection.detection)
                _detections.emit(detection.detection)
            }
        }

        // Also process through RogueWifiMonitor for advanced detection
        if (config.enableRogueApDetection) {
            processWithRogueMonitor(data, detections)
        }

        return detections
    }

    /**
     * Process a single WiFi detection context.
     *
     * @param context The WiFi detection context
     * @return WifiDetectionResult if a detection was made, null otherwise
     */
    fun handlePatternMatching(context: WifiDetectionContext): WifiDetectionResult? {
        // Skip if below RSSI threshold
        if (context.rssi < config.rssiThreshold) {
            return null
        }

        // Rate limiting check
        val now = System.currentTimeMillis()
        val lastTime = lastDetectionTime[context.bssid] ?: 0L
        if (now - lastTime < config.rateLimitMs) {
            return null
        }

        // Priority 1: Check for SSID pattern match
        if (config.enableSsidPatternMatching && context.ssid.isNotEmpty()) {
            checkSsidPattern(context)?.let { result ->
                lastDetectionTime[context.bssid] = now
                return result
            }
        }

        // Priority 2: Check for MAC prefix match
        if (config.enableMacPrefixMatching) {
            checkMacPrefix(context)?.let { result ->
                lastDetectionTime[context.bssid] = now
                return result
            }
        }

        return null
    }

    /**
     * Convert Android ScanResult to WifiDetectionContext.
     */
    @Suppress("DEPRECATION")
    private fun scanResultToContext(result: ScanResult): WifiDetectionContext? {
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
        } else {
            result.SSID ?: ""
        }

        val bssid = result.BSSID ?: return null
        val rssi = result.level

        return WifiDetectionContext(
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            frequency = result.frequency,
            channel = frequencyToChannel(result.frequency),
            capabilities = result.capabilities,
            isHidden = ssid.isEmpty(),
            timestamp = System.currentTimeMillis(),
            latitude = currentLatitude,
            longitude = currentLongitude
        )
    }

    /**
     * Check SSID against known surveillance patterns.
     */
    private fun checkSsidPattern(context: WifiDetectionContext): WifiDetectionResult? {
        val pattern = DetectionPatterns.matchSsidPattern(context.ssid) ?: return null

        // Skip surveillance patterns if disabled
        if (!config.enableSurveillancePatterns && isSurveillanceDeviceType(pattern.deviceType)) {
            return null
        }

        Log.d(TAG, "SSID pattern match: ${context.ssid} -> ${pattern.deviceType}")

        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = pattern.deviceType,
            deviceName = null,
            macAddress = context.bssid,
            ssid = context.ssid,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = scoreToThreatLevel(pattern.threatScore),
            threatScore = pattern.threatScore,
            manufacturer = pattern.manufacturer,
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = buildMatchedPatternsJson(listOf(pattern.description))
        )

        return WifiDetectionResult(
            detection = detection,
            aiPrompt = buildSsidPatternPrompt(context, pattern),
            confidence = calculateConfidence(context, 0.85f)
        )
    }

    /**
     * Check MAC address prefix (OUI) against known manufacturers.
     */
    private fun checkMacPrefix(context: WifiDetectionContext): WifiDetectionResult? {
        val macPrefix = DetectionPatterns.matchMacPrefix(context.bssid) ?: return null

        // Skip surveillance patterns if disabled
        if (!config.enableSurveillancePatterns && isSurveillanceDeviceType(macPrefix.deviceType)) {
            return null
        }

        Log.d(TAG, "MAC prefix match: ${context.bssid} -> ${macPrefix.deviceType}")

        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.MAC_PREFIX,
            deviceType = macPrefix.deviceType,
            deviceName = null,
            macAddress = context.bssid,
            ssid = context.ssid,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = scoreToThreatLevel(macPrefix.threatScore),
            threatScore = macPrefix.threatScore,
            manufacturer = macPrefix.manufacturer,
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = buildMatchedPatternsJson(listOf(
                macPrefix.description.ifEmpty { "MAC prefix: ${macPrefix.prefix}" }
            ))
        )

        return WifiDetectionResult(
            detection = detection,
            aiPrompt = buildMacPrefixPrompt(context, macPrefix),
            confidence = calculateConfidence(context, 0.70f)
        )
    }

    /**
     * Process scan results through RogueWifiMonitor for advanced detection.
     */
    private suspend fun processWithRogueMonitor(
        scanResults: List<ScanResult>,
        detections: MutableList<Detection>
    ) {
        val monitor = rogueWifiMonitor ?: return

        // Process the scan results
        monitor.processScanResults(scanResults)

        // Try to get any anomalies that were detected immediately
        val anomalies = withTimeoutOrNull(100L) {
            monitor.anomalies.first { it.isNotEmpty() }
        } ?: emptyList()

        // Convert anomalies to detections (if not already in the list)
        for (anomaly in anomalies) {
            val detection = monitor.anomalyToDetection(anomaly)
            // Avoid duplicates by checking BSSID
            if (!detections.any { it.macAddress == detection.macAddress }) {
                detections.add(detection)
                _detections.emit(detection)
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Check if device type is surveillance-related.
     */
    private fun isSurveillanceDeviceType(deviceType: DeviceType): Boolean {
        return deviceType in listOf(
            DeviceType.FLOCK_SAFETY_CAMERA,
            DeviceType.AXON_POLICE_TECH,
            DeviceType.MOTOROLA_POLICE_TECH,
            DeviceType.BODY_CAMERA,
            DeviceType.POLICE_RADIO,
            DeviceType.POLICE_VEHICLE,
            DeviceType.L3HARRIS_SURVEILLANCE,
            DeviceType.PENGUIN_SURVEILLANCE,
            DeviceType.PIGVISION_SYSTEM,
            DeviceType.RAVEN_GUNSHOT_DETECTOR,
            DeviceType.SURVEILLANCE_VAN,
            DeviceType.UNKNOWN_SURVEILLANCE
        )
    }

    /**
     * Calculate detection confidence based on context.
     */
    private fun calculateConfidence(context: WifiDetectionContext, baseConfidence: Float): Float {
        var confidence = baseConfidence

        // Adjust for signal strength
        when {
            context.rssi > IMMEDIATE_PROXIMITY_RSSI -> confidence += 0.05f
            context.rssi > STRONG_SIGNAL_RSSI -> confidence += 0.02f
            context.rssi < -80 -> confidence -= 0.05f
        }

        // Adjust for hidden SSID (less certainty)
        if (context.isHidden) {
            confidence -= 0.1f
        }

        // Adjust for WPA3/WPA2 security (less suspicious)
        if (context.capabilities.contains("WPA3") || context.capabilities.contains("WPA2")) {
            confidence -= 0.02f
        }

        return confidence.coerceIn(0f, 1f)
    }

    /**
     * Convert frequency to WiFi channel number.
     */
    private fun frequencyToChannel(frequencyMhz: Int): Int {
        return when {
            frequencyMhz in 2412..2484 -> (frequencyMhz - 2407) / 5
            frequencyMhz in 5170..5825 -> (frequencyMhz - 5000) / 5
            frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5 // 6 GHz band
            else -> 0
        }
    }

    /**
     * Build JSON array string for matched patterns.
     */
    private fun buildMatchedPatternsJson(patterns: List<String>): String {
        return patterns.joinToString(
            prefix = "[\"",
            postfix = "\"]",
            separator = "\",\""
        ) { it.replace("\"", "\\\"") }
    }

    // ==================== AI Prompt Builders ====================

    /**
     * Build AI prompt for SSID pattern match detection.
     */
    private fun buildSsidPatternPrompt(
        context: WifiDetectionContext,
        pattern: DetectionPattern
    ): String {
        return """WiFi Surveillance Device Detected: ${pattern.deviceType.displayName}

=== Detection Data ===
SSID: ${context.ssid}
BSSID (MAC): ${context.bssid}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Channel: ${context.channel} (${context.frequency} MHz)
Security: ${context.capabilities}
Location: ${formatLocation(context)}

=== Pattern Match ===
Matched Pattern: ${pattern.pattern}
Device Type: ${pattern.deviceType.displayName}
Manufacturer: ${pattern.manufacturer ?: "Unknown"}
Threat Score: ${pattern.threatScore}/100
Description: ${pattern.description}
${pattern.sourceUrl?.let { "Source: $it" } ?: ""}

=== About This Device Type ===
${getDeviceTypeDescription(pattern.deviceType)}

=== Privacy Implications ===
${getPrivacyImplications(pattern.deviceType)}

Analyze this detection and provide:
1. What data this device collects and how it operates
2. Privacy risk assessment based on proximity
3. Whether this appears to be legitimate or suspicious
4. Recommended actions for the user"""
    }

    /**
     * Build AI prompt for MAC prefix match detection.
     */
    private fun buildMacPrefixPrompt(
        context: WifiDetectionContext,
        macPrefix: DetectionPatterns.MacPrefix
    ): String {
        return """WiFi Device Detected via MAC Prefix: ${macPrefix.deviceType.displayName}

=== Detection Data ===
SSID: ${context.ssid.ifEmpty { "(Hidden/None)" }}
BSSID (MAC): ${context.bssid}
MAC Prefix (OUI): ${macPrefix.prefix}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Channel: ${context.channel} (${context.frequency} MHz)
Security: ${context.capabilities}
Location: ${formatLocation(context)}

=== Manufacturer Identification ===
OUI Manufacturer: ${macPrefix.manufacturer}
Associated Device Type: ${macPrefix.deviceType.displayName}
Threat Score: ${macPrefix.threatScore}/100
Description: ${macPrefix.description}

=== Confidence Note ===
MAC prefix matching has lower confidence than SSID matching because:
- The manufacturer may produce many types of devices
- MAC addresses can be spoofed
- OUI databases may be incomplete

=== About This Manufacturer ===
${getManufacturerDescription(macPrefix.manufacturer)}

Analyze this detection and provide:
1. Assessment of whether this is likely surveillance equipment
2. What this type of device typically does
3. Privacy implications if it is surveillance
4. Confidence assessment and false positive likelihood"""
    }

    /**
     * Get description for a device type.
     */
    private fun getDeviceTypeDescription(deviceType: DeviceType): String {
        return when (deviceType) {
            DeviceType.FLOCK_SAFETY_CAMERA -> """
Flock Safety cameras are Automated License Plate Readers (ALPRs) that:
- Capture license plates of all passing vehicles 24/7
- Record vehicle make, model, color, and distinguishing features
- Store data for up to 30 days (or longer with law enforcement agreements)
- Can be used to track vehicle movements across a network of cameras
- Often deployed by HOAs, businesses, and police departments"""

            DeviceType.AXON_POLICE_TECH -> """
Axon produces police technology including:
- Body cameras that can auto-activate during incidents
- Signal devices that trigger nearby cameras to start recording
- Evidence management systems for law enforcement
- TASER devices with connectivity features"""

            DeviceType.MOTOROLA_POLICE_TECH -> """
Motorola Solutions provides police technology including:
- Body cameras (V300, V500 series)
- In-car video systems
- Radio equipment (APX, ASTRO)
- CommandCentral software suite"""

            DeviceType.HIDDEN_CAMERA -> """
Hidden camera WiFi networks may indicate:
- Covert surveillance equipment
- Spy cameras disguised as everyday objects
- Unauthorized recording devices
- IoT cameras in setup/configuration mode"""

            DeviceType.SURVEILLANCE_VAN -> """
Mobile surveillance hotspots may indicate:
- Law enforcement surveillance vehicles
- Private investigation equipment
- Mobile command centers
- Covert monitoring operations"""

            DeviceType.ROGUE_AP -> """
Rogue access points can be used for:
- Man-in-the-middle attacks (intercepting traffic)
- Evil twin attacks (impersonating legitimate networks)
- WiFi Pineapple and similar penetration testing tools
- Credential harvesting via fake captive portals"""

            else -> "This device type may be used for surveillance or tracking purposes."
        }
    }

    /**
     * Get privacy implications for a device type.
     */
    private fun getPrivacyImplications(deviceType: DeviceType): String {
        return when (deviceType) {
            DeviceType.FLOCK_SAFETY_CAMERA -> """
- Your vehicle's license plate will be captured and stored
- Travel patterns can be reconstructed from camera network
- Data may be shared with law enforcement without warrant
- Historical location data can be queried by authorities"""

            DeviceType.AXON_POLICE_TECH, DeviceType.MOTOROLA_POLICE_TECH, DeviceType.BODY_CAMERA -> """
- Indicates active police presence or equipment nearby
- Body cameras may be recording audio and video
- Your image/voice may be captured and stored
- Recordings may be used as evidence or shared"""

            DeviceType.HIDDEN_CAMERA -> """
- Covert recording of your activities
- Potential violation of privacy in private spaces
- May capture sensitive personal information
- Could be used for stalking or harassment"""

            DeviceType.SURVEILLANCE_VAN -> """
- Targeted surveillance of specific area or person
- May indicate law enforcement interest
- Could be conducting electronic surveillance
- Possible recording of wireless communications"""

            DeviceType.ROGUE_AP -> """
- Network traffic interception risk
- Login credentials could be captured
- Malware injection possible
- Browser sessions could be hijacked"""

            else -> "This device may collect data about your presence, activities, or communications."
        }
    }

    /**
     * Get description for a manufacturer.
     */
    private fun getManufacturerDescription(manufacturer: String): String {
        return when {
            manufacturer.contains("Flock", ignoreCase = true) ->
                "Flock Safety manufactures ALPR cameras and gunshot detection systems."
            manufacturer.contains("Axon", ignoreCase = true) ->
                "Axon Enterprise produces body cameras, TASERs, and police technology."
            manufacturer.contains("Motorola", ignoreCase = true) ->
                "Motorola Solutions provides radio, video, and software for public safety."
            manufacturer.contains("Quectel", ignoreCase = true) ->
                "Quectel manufactures cellular modems used in many IoT and surveillance devices."
            manufacturer.contains("Telit", ignoreCase = true) ->
                "Telit produces cellular modules commonly used in surveillance equipment."
            manufacturer.contains("Sierra", ignoreCase = true) ->
                "Sierra Wireless manufactures cellular modems for IoT applications."
            manufacturer.contains("Hikvision", ignoreCase = true) ->
                "Hikvision is a major manufacturer of surveillance cameras and DVRs."
            manufacturer.contains("Dahua", ignoreCase = true) ->
                "Dahua Technology produces surveillance cameras and security systems."
            else -> "This manufacturer produces equipment that may include surveillance capabilities."
        }
    }

    /**
     * Format location for prompts.
     */
    private fun formatLocation(context: WifiDetectionContext): String {
        return if (context.latitude != null && context.longitude != null) {
            "%.6f, %.6f".format(context.latitude, context.longitude)
        } else {
            "Location unavailable"
        }
    }

    /**
     * Emit a detection from external processing (e.g., ScanningService).
     */
    suspend fun emitDetection(detection: Detection) {
        _detections.emit(detection)
    }

    /**
     * Get the underlying RogueWifiMonitor for direct access.
     */
    fun getMonitor(): RogueWifiMonitor? = rogueWifiMonitor
}

// ==================== Data Classes ====================

/**
 * WiFi detection context containing all relevant scan result data.
 *
 * This is the input data structure for [WifiDetectionHandler.handlePatternMatching].
 *
 * @property ssid The network name (may be empty for hidden networks)
 * @property bssid The access point MAC address
 * @property rssi Signal strength in dBm
 * @property frequency Operating frequency in MHz
 * @property channel WiFi channel number
 * @property capabilities Security and protocol capabilities string
 * @property isHidden Whether the SSID is hidden/empty
 * @property timestamp Detection timestamp
 * @property latitude Current latitude (if available)
 * @property longitude Current longitude (if available)
 */
data class WifiDetectionContext(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val capabilities: String,
    val isHidden: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null
)

/**
 * WiFi detection result containing the detection and AI prompt.
 *
 * @property detection The generated Detection object
 * @property aiPrompt Contextual prompt for AI analysis
 * @property confidence Detection confidence (0.0-1.0)
 */
data class WifiDetectionResult(
    val detection: Detection,
    val aiPrompt: String,
    val confidence: Float
)

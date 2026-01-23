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

        // ==================== CACHED DEVICE TYPE SETS ====================
        // Pre-allocated sets for device type checks to avoid allocation on every call

        /** Surveillance-related device types */
        val SURVEILLANCE_DEVICE_TYPES = setOf(
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

    // Use a supervisor job that can be cancelled to clean up resources
    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.Default + supervisorJob)

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

        // Recreate scope if it was cancelled (e.g., after destroy())
        if (!supervisorJob.isActive) {
            supervisorJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.Default + supervisorJob)
        }

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
        // Cancel the supervisor job to clean up all coroutines
        supervisorJob.cancel()
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
     * Includes comprehensive error handling to prevent crashes during detection processing.
     *
     * @param data List of WiFi scan results from WifiManager
     * @return List of detections found
     */
    suspend fun processData(data: List<ScanResult>): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Process each scan result through pattern matching with error handling
        for (result in data) {
            try {
                val context = scanResultToContext(result) ?: continue

                // Process through pattern matching
                val detection = handlePatternMatching(context)
                if (detection != null) {
                    detections.add(detection.detection)
                    try {
                        _detections.emit(detection.detection)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to emit WiFi detection: ${e.message}")
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception processing WiFi scan result: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing WiFi scan result: ${e.message}", e)
            }
        }

        // Also process through RogueWifiMonitor for advanced detection
        if (config.enableRogueApDetection) {
            try {
                processWithRogueMonitor(data, detections)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing with RogueWifiMonitor: ${e.message}", e)
            }
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
        return deviceType in SURVEILLANCE_DEVICE_TYPES
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
     * Get description for a device type with real-world context.
     */
    private fun getDeviceTypeDescription(deviceType: DeviceType): String {
        return when (deviceType) {
            DeviceType.FLOCK_SAFETY_CAMERA -> """
Flock Safety cameras are Automated License Plate Readers (ALPRs) that:
- Capture license plates of all passing vehicles 24/7
- Record vehicle make, model, color, and distinguishing features (Vehicle Fingerprint)
- Store data for 30 days default (some jurisdictions retain 2+ years)
- Can track vehicle movements across a network of 5,000+ communities
- Process 20+ billion monthly plate scans nationwide
- 2,000+ law enforcement agencies have direct access to the database

DEPLOYMENT PATTERNS:
- Mounted on poles at HOA entrances, city intersections, school zones
- Small rectangular box with IR illuminators (visible at night as red glow)
- Solar-powered variants have visible panels
- Often paired: one camera per direction of traffic

REAL-WORLD CONFIRMATION:
- Check deflock.me for known camera locations in your area
- Look for small box camera on pole/post nearby (distinctive shape)
- IR illuminators may be visible as faint red glow at night
- May see "Flock Safety" branding on equipment"""

            DeviceType.AXON_POLICE_TECH -> """
Axon Enterprise (formerly TASER International) products detected:

AXON BODY CAMERAS (Body 2/3/4):
- WiFi used for: Firmware updates, evidence upload to Evidence.com
- Auto-activation: Triggered by gun draw, TASER deployment, vehicle door open
- Can stream live video to command center (Axon Respond)

AXON FLEET (In-Car Cameras):
- Dash cameras with automatic incident recording
- Syncs with body cameras when officer exits vehicle
- WiFi for bulk evidence upload at station

AXON INTERVIEW:
- Interview room recording systems
- WiFi for streaming and upload

REAL-WORLD CONFIRMATION:
- Strong signal suggests officer/vehicle within 30-50 feet
- May indicate active police presence or parked cruiser nearby
- Axon Signal devices auto-activate all nearby body cameras"""

            DeviceType.MOTOROLA_POLICE_TECH -> """
Motorola Solutions police technology detected:

APX RADIOS (APX 6000/7000/8000/NEXT):
- Used by: Most US police, fire, EMS departments
- WiFi for: Firmware updates, configuration, key loading
- Capabilities: Encrypted P25 voice, GPS tracking, emergency alerts

BODY CAMERAS (V300/V500 Series):
- WiFi for evidence upload to CommandCentral
- May have automatic activation triggers

VIGILANT ALPR:
- Motorola's competitor to Flock Safety
- Similar capabilities for license plate tracking

REAL-WORLD CONFIRMATION:
- APX radios are standard police equipment
- Detection may indicate officer on foot patrol nearby
- Fleet vehicles often have multiple Motorola devices"""

            DeviceType.L3HARRIS_SURVEILLANCE -> """
L3Harris Technologies surveillance/communications equipment detected.

THIS IS HIGHLY UNUSUAL TO DETECT. L3Harris makes:
- StingRay/Hailstorm/Kingfish cell site simulators (IMSI catchers)
- Tactical radio systems (XG-75/XG-100)
- Advanced SIGINT and electronic warfare equipment

IF YOU SEE THIS:
- StingRay SSIDs in the wild are EXTREMELY RARE (operational security)
- Real cell site simulators don't typically broadcast WiFi
- May indicate training exercise, equipment testing, or malfunction
- Could also be a hoax/honeypot SSID

REAL-WORLD CONFIRMATION:
- Look for suspicious vehicles with antennas
- Note if cellular service is degraded in the area
- Document time/location - this is significant if real"""

            DeviceType.CELLEBRITE_FORENSICS -> """
Cellebrite UFED (Universal Forensic Extraction Device) detected:

WHAT IT DOES:
- Extracts data from locked mobile phones
- Can recover: Deleted messages, app data, passwords, photos
- Bypasses screen locks on many devices
- Used by: Police, FBI, border agents, corporate security

COST: $15,000-$30,000 per unit (police departments only)

IF DETECTED NEARBY:
- May indicate forensic examination in progress
- Could be at police station, border crossing, or mobile unit
- Presence near you = potential device seizure risk

REAL-WORLD CONFIRMATION:
- Typically used in controlled environments (stations, labs)
- Mobile deployment is less common but possible
- Strong signal suggests very close proximity"""

            DeviceType.HIDDEN_CAMERA -> """
Hidden camera WiFi network detected. Common patterns include:

CAMERA SSID PATTERNS:
- IPCamera_*, IPC_*, WIFICAM_*, P2P_*, YI_*
- Many cheap cameras use default SSIDs
- Often 2.4GHz only with open network for initial setup

MANUFACTURERS TO FLAG:
- Wyze, Ring, Nest in inappropriate locations (hotel rooms, Airbnbs)
- Generic Chinese cameras (Hikvision, Dahua, Reolink OEM)
- "Nanny cam" or "spy cam" branded devices

REAL-WORLD CONFIRMATION:
- Scan room with phone camera for IR LEDs (appear as purple/white glow)
- Check smoke detectors, clocks, outlets, picture frames
- Use RF detector for hidden wireless transmitters
- Look for tiny holes in walls, objects, or ceiling
- Check behind mirrors and in decorative items"""

            DeviceType.SURVEILLANCE_VAN -> """
Mobile surveillance hotspot detected - possible surveillance vehicle.

REAL SURVEILLANCE VAN PATTERNS:
- SSID appears at multiple of YOUR locations (follows you)
- Strong signal from parking area or street
- Generic/bland SSID names (NOT "FBI_Van" - that's a joke)
- Consistent signal over extended period

WHAT TO LOOK FOR:
- Vans, SUVs, or work trucks parked with occupants
- Vehicles with running engines (for equipment power)
- Unusual antennas or equipment visible
- Tinted windows, no company markings, or generic utility appearance

REAL-WORLD CONFIRMATION:
- Note if same SSID appears at home AND work
- Check for vehicle parked where signal is strongest
- Signal strength mapping can help locate source
- Monitor over multiple days for patterns"""

            DeviceType.ROGUE_AP, DeviceType.WIFI_PINEAPPLE -> """
Rogue/Evil Twin access point detected. Attack types include:

EVIL TWIN ATTACK:
- Same SSID as known network but different BSSID (MAC)
- Stronger signal than usual for that network
- Open network mimicking a secured network
- Certificate mismatch on captive portal

KARMA ATTACK:
- AP responds to ALL probe requests
- Impersonates any network your device asks for
- WiFi Pineapple is common tool for this

DEAUTH ATTACK:
- Kicks you off real AP to force connection to fake
- Rapid disconnection patterns are the signature

REAL-WORLD CONFIRMATION:
- Compare BSSID to your known router's MAC address
- Check if 'free WiFi' appeared where it shouldn't exist
- Look for unusual device near where signal is strongest
- Verify HTTPS certificates on sensitive sites

IMMEDIATE ACTIONS:
- Disconnect from suspicious network
- Forget the network and reconnect to legitimate AP
- Use cellular data for sensitive activities
- Enable VPN before reconnecting to WiFi"""

            DeviceType.MAN_IN_MIDDLE -> """
Man-in-the-Middle (MITM) attack infrastructure detected.

WHAT THIS MEANS:
- Someone is positioned to intercept your network traffic
- Can capture login credentials on non-HTTPS sites
- Can perform SSL stripping attacks
- May inject malicious content into web pages

DETECTION SIGNS:
- Certificate warnings on normally-secure sites
- HTTPS downgraded to HTTP
- Unusual redirects or login pages
- Slower than expected network performance

REAL-WORLD CONFIRMATION:
- Check SSL certificates on banking/email sites
- Look for certificate authority mismatches
- Use a different network to verify
- Check for ARP poisoning indicators"""

            DeviceType.TRACKING_DEVICE -> """
Network following your location detected - possible tracking device.

FOLLOWING PATTERNS:
- Same SSID/BSSID seen at 3+ of your distinct locations
- Appears at both home and work
- Signal strength varies but network persists
- Movement correlation with your travel

WHAT THIS COULD BE:
- Surveillance team using mobile hotspot
- Tracking device planted on vehicle
- Coincidental neighbor/coworker (less likely if 3+ locations)
- Commuter on same route (check timing patterns)

REAL-WORLD CONFIRMATION:
- Check vehicle thoroughly for hidden devices
- Note signal strength variations to locate source
- Vary your routine to test if network follows
- Document all sighting locations and times"""

            else -> "This device type may be used for surveillance or tracking purposes."
        }
    }

    /**
     * Get privacy implications for a device type with legal context.
     */
    private fun getPrivacyImplications(deviceType: DeviceType): String {
        return when (deviceType) {
            DeviceType.FLOCK_SAFETY_CAMERA -> """
DATA COLLECTED:
- License plate number (100% capture rate at speeds up to 100mph)
- Vehicle make, model, color, year
- Distinguishing features: bumper stickers, damage, modifications
- Direction of travel, timestamp, GPS coordinates

DATA RETENTION & ACCESS:
- Default: 30 days (configurable per customer)
- Some jurisdictions retain data for 2+ years
- 2,000+ law enforcement agencies have database access
- HOAs can query their own camera data
- No warrant required for law enforcement queries
- Data shared across Flock's nationwide network

LEGAL CONTEXT (US):
- ALPR data is generally not protected by 4th Amendment
- Several states have NO restrictions on retention/sharing
- CA, VT, NH have some privacy protections
- ICE has used ALPR data for immigration enforcement

WHAT YOU CAN DO:
- Check deflock.me for camera locations
- Consider route planning to avoid clusters
- Understand your state's ALPR laws"""

            DeviceType.AXON_POLICE_TECH, DeviceType.MOTOROLA_POLICE_TECH, DeviceType.BODY_CAMERA -> """
WHAT MAY BE RECORDED:
- Video of your face, body, actions
- Audio of your voice and conversations
- Your location via officer's GPS
- Timestamp and duration of encounter

DATA HANDLING:
- Uploaded to Evidence.com (Axon) or CommandCentral (Motorola)
- Retention: 60 days to 7+ years depending on jurisdiction
- Officers can review footage before writing reports
- FOIA requests for footage often delayed or denied

AUTOMATIC ACTIVATION:
- Axon Signal triggers all nearby cameras when:
  - Officer draws firearm
  - Vehicle siren/lights activated
  - TASER deployed
  - Vehicle door opens (Axon Fleet)

LEGAL CONTEXT:
- You have RIGHT to record police in all US states
- Officers cannot delete your recordings
- BWC footage release policies vary by department"""

            DeviceType.L3HARRIS_SURVEILLANCE, DeviceType.STINGRAY_IMSI -> """
IF THIS IS A REAL CELL SITE SIMULATOR:
- All phones within 1-2km are affected
- Your IMSI (SIM ID) and IMEI (device ID) captured
- Phone may be forced to 2G (weak/no encryption)
- Calls, texts, and data can be intercepted
- Your location tracked to within meters

LEGAL CONTEXT:
- Often used under NDA - hidden from courts
- FBI requires dropping cases rather than revealing use
- Some states now require warrants (WA, CA, NY, IL)
- Frequently deployed at protests and gatherings

PRIVACY IMPACT:
- Mass surveillance affecting everyone nearby
- No notification to affected individuals
- Data retention policies are opaque
- Cell service disruption in the area"""

            DeviceType.CELLEBRITE_FORENSICS -> """
WHAT CAN BE EXTRACTED:
- All photos, videos, documents
- Text messages (including deleted)
- Call logs and voicemails
- App data (Signal, WhatsApp, Telegram)
- Passwords and authentication tokens
- Location history and GPS logs
- Social media content
- Browser history and bookmarks

LEGAL CONTEXT:
- Border agents can search devices without warrant
- Some jurisdictions allow at traffic stops
- "Consent" may be coerced
- Device seizure policies vary

IF YOU'RE CONCERNED:
- Know your rights regarding device searches
- Consider travel mode / secondary device for border crossings
- Full-disk encryption provides some protection
- Newer iPhones resist some extraction methods"""

            DeviceType.HIDDEN_CAMERA -> """
PRIVACY VIOLATION:
- Recording without consent is illegal in private spaces
- May capture intimate/private moments
- Footage may be shared or sold online
- Can enable stalking or harassment

WHERE TO CHECK:
- Hotel rooms, Airbnbs, rental properties
- Changing rooms, bathrooms
- Behind mirrors, in smoke detectors
- Clocks, picture frames, electrical outlets

LEGAL CONTEXT:
- Recording in private spaces without consent: ILLEGAL
- Expectation of privacy laws vary by state
- Hotels/landlords liable for hidden cameras
- Report to police if found

DETECTION METHODS:
- Phone camera can see IR LEDs
- RF detector for wireless transmission
- Physical inspection of suspicious objects
- Network scan (this app) for WiFi cameras"""

            DeviceType.SURVEILLANCE_VAN -> """
POTENTIAL CAPABILITIES:
- Audio surveillance (parabolic/laser mics)
- Video surveillance (telephoto, thermal)
- Cell site simulator operation
- WiFi/Bluetooth interception
- License plate reading

WHO MIGHT BE OPERATING:
- FBI, DEA, ATF, other federal agencies
- State and local police
- Private investigators
- Corporate security
- Stalkers (rare but possible)

LEGAL CONTEXT:
- Surveillance of public activities generally legal
- Electronic surveillance requires warrant (with exceptions)
- Private investigators have fewer restrictions
- Documentation may support legal challenges

WHAT TO DO:
- Document vehicle details (plate, make/model, location)
- Note times and duration of presence
- Consult attorney if targeted surveillance suspected"""

            DeviceType.ROGUE_AP, DeviceType.WIFI_PINEAPPLE, DeviceType.MAN_IN_MIDDLE -> """
IMMEDIATE RISKS:
- All unencrypted traffic can be read
- Login credentials on HTTP sites captured
- Session cookies stolen (account hijacking)
- Malware/exploit injection possible
- SSL stripping downgrades HTTPS to HTTP

TARGETED DATA:
- Banking/financial credentials
- Email passwords
- Social media logins
- Corporate VPN credentials
- Personal conversations

LEGAL CONTEXT:
- Unauthorized interception: Federal crime (Wiretap Act)
- CFAA violations for unauthorized access
- May be part of larger attack campaign

IMMEDIATE MITIGATION:
- Disconnect NOW from suspicious network
- Use cellular data for sensitive activities
- Change passwords from a secure connection
- Enable 2FA on important accounts
- Use VPN for all future WiFi connections"""

            DeviceType.TRACKING_DEVICE -> """
IF YOU'RE BEING FOLLOWED:
- This is evidence of targeted surveillance
- May indicate law enforcement interest
- Could be stalking or harassment
- Private investigation is possible

DATA BEING COLLECTED:
- Your daily routine and patterns
- Home and work locations
- Frequent stops and contacts
- Travel times and routes

LEGAL CONTEXT:
- Police generally need warrant for GPS tracking (US v. Jones)
- Stalking via tracking is criminal in all states
- PI tracking laws vary by state
- Employer tracking of company vehicles may be legal

WHAT TO DO:
- Document all sighting locations and times
- Vary your routine to confirm tracking
- Physically inspect vehicle for devices
- Consult attorney or law enforcement if stalking suspected"""

            else -> "This device may collect data about your presence, activities, or communications."
        }
    }

    /**
     * Get description for a manufacturer with real-world context.
     */
    private fun getManufacturerDescription(manufacturer: String): String {
        return when {
            manufacturer.contains("Flock", ignoreCase = true) -> """
Flock Safety (Atlanta, GA) - Founded 2017
- Leading ALPR camera manufacturer in the US
- 5,000+ communities, 20+ billion monthly scans
- Products: Falcon, Sparrow, Condor cameras; Raven gunshot detector
- 2,000+ law enforcement agencies have data access
- Also offers SafeList (vehicle hotlists) and FlockOS platform
- Camera locations mapped at deflock.me"""

            manufacturer.contains("Axon", ignoreCase = true) -> """
Axon Enterprise (Scottsdale, AZ) - Formerly TASER International
- Dominant body camera provider for US law enforcement
- Products: Body 2/3/4 cameras, Fleet in-car, TASER devices
- Evidence.com cloud platform stores billions of evidence files
- Axon Signal auto-triggers cameras on firearm/TASER deployment
- Expanding into AI transcription and redaction
- Stock ticker: AXON (NASDAQ)"""

            manufacturer.contains("Motorola", ignoreCase = true) -> """
Motorola Solutions (Chicago, IL)
- Major public safety technology provider
- Products: APX radios, V300/V500 body cameras, WatchGuard dash cams
- Vigilant ALPR platform (competitor to Flock)
- CommandCentral evidence management
- Radio encryption (P25) used by most US public safety
- Stock ticker: MSI (NYSE)"""

            manufacturer.contains("L3Harris", ignoreCase = true) -> """
L3Harris Technologies (Melbourne, FL)
- Defense and aerospace contractor
- Manufactures StingRay/Hailstorm/Kingfish cell site simulators
- Tactical radios (XG series) for law enforcement
- Advanced SIGINT and surveillance equipment
- Equipment typically classified or export-controlled
- IMSI catcher detection is SIGNIFICANT if confirmed"""

            manufacturer.contains("Cellebrite", ignoreCase = true) -> """
Cellebrite (Israel/US)
- Mobile forensics company
- UFED: Can extract data from most phones, including deleted content
- Used by law enforcement, border agents, military worldwide
- Premium license: $15,000-$30,000+
- Can bypass many screen locks and encryption
- Detection suggests active forensic operation"""

            manufacturer.contains("Grayshift", ignoreCase = true) -> """
Grayshift (Atlanta, GA)
- Founded by ex-Apple engineers
- GrayKey: iPhone unlocking and extraction device
- Can crack passcodes on modern iPhones
- Exclusively sold to law enforcement
- $15,000-$30,000 per unit
- Detection near you is HIGHLY unusual"""

            manufacturer.contains("Quectel", ignoreCase = true) -> """
Quectel Wireless Solutions (China)
- World's largest LTE/5G modem manufacturer
- Modems used in: Flock cameras, fleet trackers, IoT devices
- OUI 50:29:4D commonly found in surveillance equipment
- Module presence doesn't confirm surveillance alone
- Very common in legitimate IoT devices too"""

            manufacturer.contains("Telit", ignoreCase = true) -> """
Telit Cinterion (Italy/US)
- Cellular IoT module manufacturer
- Used in: Fleet tracking, surveillance equipment, industrial IoT
- Common in both legitimate and surveillance applications
- Detection requires additional context"""

            manufacturer.contains("Sierra", ignoreCase = true) -> """
Sierra Wireless (Canada) - Now Semtech
- Vehicle/fleet cellular routers
- Very common in: Police vehicles, ambulances, utility trucks
- Products: AirLink, MP70 series
- Cradlepoint is similar (police vehicle routers)
- Detection often indicates nearby fleet vehicle"""

            manufacturer.contains("Hikvision", ignoreCase = true) -> """
Hikvision (Hangzhou, China)
- World's largest surveillance camera manufacturer
- Partially state-owned by Chinese government
- Banned for US government purchases (NDAA)
- Common in: Commercial surveillance, some residential
- Known for security vulnerabilities
- OUIs: B4:A3:82, 44:19:B6, 54:C4:15, 28:57:BE"""

            manufacturer.contains("Dahua", ignoreCase = true) -> """
Dahua Technology (Hangzhou, China)
- Second largest surveillance camera manufacturer
- Also banned for US government (NDAA)
- Used in commercial and residential surveillance
- Known for remote access vulnerabilities
- OUIs: E0:50:8B, 3C:EF:8C, 4C:11:BF, A0:BD:1D"""

            manufacturer.contains("Nordic", ignoreCase = true) -> """
Nordic Semiconductor (Norway)
- BLE/Bluetooth chip manufacturer
- Chips used in: Axon body cameras, fitness trackers, IoT
- Very common - detection alone is not concerning
- Context (SSID, behavior) determines significance"""

            manufacturer.contains("Cradlepoint", ignoreCase = true) -> """
Cradlepoint (Boise, ID) - Now Ericsson
- Vehicle/mobile router manufacturer
- Very common in: Police vehicles, mobile command, fleet
- Products: IBR series, NetCloud platform
- Detection suggests nearby fleet vehicle
- Check for parked vehicles with antennas"""

            else -> "This manufacturer produces equipment that may include surveillance capabilities. " +
                    "Consider the context (SSID, signal strength, location) to assess risk."
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

package com.flockyou.detection.handler

import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionPattern
import com.flockyou.data.model.DetectionPatterns
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.rssiToDistance
import com.flockyou.data.model.rssiToSignalStrength
import com.flockyou.data.model.scoreToThreatLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE Detection Handler
 *
 * Handles all Bluetooth Low Energy (BLE) based surveillance detection.
 * Implements the [DetectionHandler] interface for [BleDetectionContext].
 *
 * ## Supported Device Types
 * - **Surveillance Equipment**: Flock Safety cameras, Raven gunshot detectors
 * - **Police Technology**: Axon body cameras/Signal triggers, Motorola equipment
 * - **Consumer Trackers**: Apple AirTag, Tile, Samsung SmartTag
 * - **Smart Home**: Ring, Nest, Wyze, Arlo, Eufy, Blink cameras
 * - **Beacons**: Retail tracking beacons, iBeacon, Eddystone
 *
 * ## Detection Methods
 * - [DetectionMethod.BLE_DEVICE_NAME] - Device name pattern matching
 * - [DetectionMethod.BLE_SERVICE_UUID] - Service UUID matching
 * - [DetectionMethod.RAVEN_SERVICE_UUID] - Raven-specific service detection
 * - [DetectionMethod.MAC_PREFIX] - MAC address OUI matching
 * - [DetectionMethod.AIRTAG_DETECTED] - Apple AirTag detection
 * - [DetectionMethod.TILE_DETECTED] - Tile tracker detection
 * - [DetectionMethod.SMARTTAG_DETECTED] - Samsung SmartTag detection
 *
 * ## Special Features
 * - Advertising rate spike detection for Axon Signal trigger activation
 * - Configurable RSSI thresholds
 * - AI prompt generation for detected devices
 *
 * @author Flock You Android Team
 */
/**
 * BLE Detection Handler - Standalone implementation for BLE detection logic.
 *
 * This class provides the actual detection logic while [BleRegistryHandler]
 * provides the [DetectionHandler] interface implementation for the registry.
 */
@Singleton
class BleDetectionHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "BleDetectionHandler"

        // ==================== THRESHOLD CONFIGURATION ====================

        /** Minimum RSSI for detection consideration (-100 dBm = very weak, -30 dBm = very strong) */
        const val DEFAULT_RSSI_THRESHOLD = -90

        /** Strong signal threshold for proximity alerts */
        const val STRONG_SIGNAL_RSSI = -50

        /** Very close proximity threshold */
        const val IMMEDIATE_PROXIMITY_RSSI = -40

        /** Advertising rate threshold for Signal trigger detection (packets per second) */
        const val ADVERTISING_RATE_SPIKE_THRESHOLD = 20f

        /** Normal advertising rate for most BLE devices (packets per second) */
        const val NORMAL_ADVERTISING_RATE = 1f

        /** Time window for rate calculation (milliseconds) */
        const val RATE_CALCULATION_WINDOW_MS = 5000L

        /** Rate limit between detections of the same device (milliseconds) */
        const val DETECTION_RATE_LIMIT_MS = 30000L

        // ==================== MANUFACTURER IDS ====================

        /** Apple manufacturer ID (used in AirTags and as BLE wrapper) */
        const val MANUFACTURER_ID_APPLE = 0x004C

        /** Nordic Semiconductor manufacturer ID (used in Axon devices) */
        const val MANUFACTURER_ID_NORDIC = 0x0059

        /** Samsung manufacturer ID */
        const val MANUFACTURER_ID_SAMSUNG = 0x0075

        /** Tile manufacturer ID */
        const val MANUFACTURER_ID_TILE = 0x00C7

        /** Google manufacturer ID (used in Fast Pair) */
        const val MANUFACTURER_ID_GOOGLE = 0x00E0

        // ==================== FLIPPER ZERO BLE SPAM DETECTION ====================

        /** Time window for BLE spam detection (milliseconds) */
        const val BLE_SPAM_DETECTION_WINDOW_MS = 10000L

        /** Threshold for Apple device advertisements in spam window to trigger spam detection */
        const val APPLE_SPAM_THRESHOLD = 15

        /** Threshold for Fast Pair advertisements in spam window to trigger spam detection */
        const val FAST_PAIR_SPAM_THRESHOLD = 10

        /** Threshold for unique device names in spam window (rapid name changing) */
        const val DEVICE_NAME_CHANGE_THRESHOLD = 8

        /** Google Fast Pair service UUID */
        val UUID_GOOGLE_FAST_PAIR: UUID = UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

        // ==================== SERVICE UUIDS ====================

        /** Apple Find My network service UUID */
        val UUID_APPLE_FIND_MY: UUID = UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C")

        /** Tile tracker service UUID */
        val UUID_TILE_SERVICE: UUID = UUID.fromString("FEED0001-0000-1000-8000-00805F9B34FB")

        /** Samsung SmartTag service UUID */
        val UUID_SAMSUNG_SMARTTAG: UUID = UUID.fromString("0000FD5A-0000-1000-8000-00805F9B34FB")
    }

    // ==================== DetectionHandler Implementation ====================

    val protocol: DetectionProtocol = DetectionProtocol.BLUETOOTH_LE

    val supportedDeviceTypes: Set<DeviceType> = setOf(
        // Trackers
        DeviceType.AIRTAG,
        DeviceType.TILE_TRACKER,
        DeviceType.SAMSUNG_SMARTTAG,
        DeviceType.GENERIC_BLE_TRACKER,
        // Smart home
        DeviceType.RING_DOORBELL,
        DeviceType.NEST_CAMERA,
        DeviceType.AMAZON_SIDEWALK,
        DeviceType.WYZE_CAMERA,
        DeviceType.ARLO_CAMERA,
        DeviceType.EUFY_CAMERA,
        DeviceType.BLINK_CAMERA,
        DeviceType.SIMPLISAFE_DEVICE,
        DeviceType.ADT_DEVICE,
        DeviceType.VIVINT_DEVICE,
        // Beacons
        DeviceType.BLUETOOTH_BEACON,
        DeviceType.RETAIL_TRACKER,
        // Surveillance
        DeviceType.FLOCK_SAFETY_CAMERA,
        DeviceType.RAVEN_GUNSHOT_DETECTOR,
        DeviceType.AXON_POLICE_TECH,
        DeviceType.MOTOROLA_POLICE_TECH,
        DeviceType.BODY_CAMERA,
        DeviceType.POLICE_RADIO,
        DeviceType.POLICE_VEHICLE,
        DeviceType.FLEET_VEHICLE,
        DeviceType.L3HARRIS_SURVEILLANCE,
        DeviceType.CELLEBRITE_FORENSICS,
        DeviceType.GRAYKEY_DEVICE,
        DeviceType.PENGUIN_SURVEILLANCE,
        DeviceType.PIGVISION_SYSTEM,
        // Flipper Zero and hacking tools
        DeviceType.FLIPPER_ZERO,
        DeviceType.FLIPPER_ZERO_SPAM,
        DeviceType.HACKRF_SDR,
        DeviceType.PROXMARK,
        DeviceType.USB_RUBBER_DUCKY,
        DeviceType.LAN_TURTLE,
        DeviceType.BASH_BUNNY,
        DeviceType.KEYCROC,
        DeviceType.SHARK_JACK,
        DeviceType.SCREEN_CRAB,
        DeviceType.GENERIC_HACKING_TOOL
    )

    val displayName: String = "BLE Detection Handler"

    private var _isActive: Boolean = false
    val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    val detections: Flow<Detection> = _detections.asSharedFlow()

    // ==================== State ====================

    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    /** Configuration for detection behavior */
    private var config = BleHandlerConfig()

    /** Last detection time per MAC address for rate limiting */
    private val lastDetectionTime = ConcurrentHashMap<String, Long>()

    /** Packet timestamps for advertising rate calculation (thread-safe) */
    private val packetTimestamps = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()

    // ==================== Tracker Following Detection State ====================

    /**
     * Tracking history for consumer trackers to detect stalking patterns.
     * Key: MAC address, Value: List of sightings with location and time
     */
    private val trackerSightings = ConcurrentHashMap<String, CopyOnWriteArrayList<TrackerSighting>>()

    /**
     * Signal strength history for proximity analysis.
     * Key: MAC address, Value: List of RSSI readings with timestamps
     */
    private val rssiHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<RssiReading>>()

    /**
     * Duration tracking for how long trackers have been near the user.
     * Key: MAC address, Value: First seen timestamp
     */
    private val trackerFirstSeen = ConcurrentHashMap<String, Long>()

    /**
     * Axon Signal activation tracking for correlation analysis.
     * Key: MAC address, Value: List of activation events
     */
    private val axonActivations = ConcurrentHashMap<String, CopyOnWriteArrayList<AxonActivationEvent>>()

    // ==================== BLE Spam Detection State ====================

    /**
     * Tracks Apple device advertisements for iOS popup spam detection.
     * Key: Source MAC (or "aggregate" for multi-source), Value: List of advertisement timestamps
     */
    private val appleAdvertisements = ConcurrentHashMap<String, CopyOnWriteArrayList<BleSpamEvent>>()

    /**
     * Tracks Fast Pair advertisements for Android spam detection.
     * Key: Source MAC (or "aggregate" for multi-source), Value: List of advertisement timestamps
     */
    private val fastPairAdvertisements = ConcurrentHashMap<String, CopyOnWriteArrayList<BleSpamEvent>>()

    /**
     * Tracks rapid device name changes from a single MAC (Flipper impersonation).
     * Key: MAC address, Value: List of (timestamp, deviceName) pairs
     */
    private val deviceNameHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<DeviceNameEvent>>()

    /**
     * Last spam detection alert time to prevent alert fatigue.
     */
    private var lastSpamAlertTime: Long = 0L
    private val spamAlertCooldownMs = 60000L // 1 minute cooldown between spam alerts

    // ==================== Tracker Detection Thresholds ====================

    // Note: These constants are defined as private to avoid conflicts with companion object

    /** Minimum distinct locations to consider a tracker "following" */
    private val MIN_LOCATIONS_FOR_FOLLOWING = 3

    /** Maximum distance (meters) between sightings to be considered same location */
    private val SAME_LOCATION_RADIUS_METERS = 50.0

    /** Minimum time between sightings to count as a new location visit (minutes) */
    private val MIN_TIME_BETWEEN_LOCATIONS_MINUTES = 5

    /** Time window to analyze tracker behavior (hours) */
    private val TRACKER_ANALYSIS_WINDOW_HOURS = 24

    /** Strong signal threshold indicating tracker is on your person/in your bag */
    private val TRACKER_POSSESSION_RSSI = -55

    /** Duration (minutes) a strong-signal tracker must be present to be suspicious */
    private val SUSPICIOUS_DURATION_MINUTES = 30

    /** RSSI variance threshold - low variance = tracker moving with you */
    private val LOW_RSSI_VARIANCE_THRESHOLD = 10.0

    /** Axon activation correlation window (seconds) */
    private val AXON_CORRELATION_WINDOW_SECONDS = 300

    // ==================== Configuration ====================

    /**
     * Configuration for BLE detection behavior.
     *
     * @property rssiThreshold Minimum RSSI to consider for detection
     * @property advertisingRateSpikeThreshold Threshold for Signal trigger detection (pps)
     * @property enableTrackerDetection Enable consumer tracker detection (AirTag, Tile, etc.)
     * @property enablePoliceEquipmentDetection Enable police equipment detection
     * @property enableBeaconDetection Enable retail/advertising beacon detection
     * @property enableSmartHomeDetection Enable smart home device detection
     * @property rateLimitMs Rate limit between detections of same device
     */
    data class BleHandlerConfig(
        val rssiThreshold: Int = DEFAULT_RSSI_THRESHOLD,
        val advertisingRateSpikeThreshold: Float = ADVERTISING_RATE_SPIKE_THRESHOLD,
        val enableTrackerDetection: Boolean = true,
        val enablePoliceEquipmentDetection: Boolean = true,
        val enableBeaconDetection: Boolean = true,
        val enableSmartHomeDetection: Boolean = true,
        val rateLimitMs: Long = DETECTION_RATE_LIMIT_MS
    )

    /**
     * Update handler configuration.
     *
     * @param newConfig The new configuration to apply
     */
    fun updateConfig(newConfig: BleHandlerConfig) {
        config = newConfig
        Log.d(TAG, "Configuration updated: rssiThreshold=${config.rssiThreshold}, " +
                "spikeThreshold=${config.advertisingRateSpikeThreshold}")
    }

    // ==================== Lifecycle ====================

    fun startMonitoring() {
        _isActive = true
        Log.d(TAG, "BLE detection monitoring started")
    }

    fun stopMonitoring() {
        _isActive = false
        Log.d(TAG, "BLE detection monitoring stopped")
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    fun clearHistory() {
        lastDetectionTime.clear()
        packetTimestamps.clear()
        trackerSightings.clear()
        rssiHistory.clear()
        trackerFirstSeen.clear()
        axonActivations.clear()
        // Clear BLE spam detection state
        appleAdvertisements.clear()
        fastPairAdvertisements.clear()
        deviceNameHistory.clear()
        lastSpamAlertTime = 0L
        Log.d(TAG, "BLE detection history cleared (including tracker tracking data and spam detection)")
    }

    fun destroy() {
        stopMonitoring()
        clearHistory()
    }

    // ==================== Detection Processing ====================

    /**
     * Process a BLE scan result and potentially produce detections.
     *
     * This is the main entry point called by [ScanningService] or other BLE scanners.
     * It evaluates the context against all detection methods in priority order.
     *
     * @param data The BLE detection context from scan result
     * @return List of detections found (typically 0 or 1)
     */
    suspend fun processData(data: BleDetectionContext): List<Detection> {
        val result = handleDetection(data) ?: return emptyList()

        // Emit to the detections flow
        _detections.emit(result.detection)

        return listOf(result.detection)
    }

    /**
     * Process a raw [ScanResult] by converting it to [BleDetectionContext].
     *
     * @param scanResult The Android BLE scan result
     * @return List of detections found
     */
    suspend fun processScanResult(scanResult: ScanResult): List<Detection> {
        val context = scanResultToContext(scanResult)
        return processData(context)
    }

    /**
     * Convert Android [ScanResult] to [BleDetectionContext].
     */
    @Suppress("MissingPermission")
    private fun scanResultToContext(result: ScanResult): BleDetectionContext {
        val device = result.device
        val macAddress = device.address ?: ""
        val deviceName = device.name
        val rssi = result.rssi

        // Extract service UUIDs
        val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

        // Extract manufacturer data
        val manufacturerData = mutableMapOf<Int, String>()
        result.scanRecord?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) {
                val key = data.keyAt(i)
                val value = data.valueAt(i)
                manufacturerData[key] = value.joinToString("") { "%02X".format(it) }
            }
        }

        // Calculate advertising rate
        val advertisingRate = trackPacketAndGetRate(macAddress)

        return BleDetectionContext(
            macAddress = macAddress,
            deviceName = deviceName,
            rssi = rssi,
            serviceUuids = serviceUuids,
            manufacturerData = manufacturerData,
            advertisingRate = advertisingRate,
            timestamp = System.currentTimeMillis(),
            latitude = currentLatitude,
            longitude = currentLongitude
        )
    }

    /**
     * Process a BLE detection context and generate detection if patterns match.
     *
     * Detection priority order:
     * 1. BLE Spam Attack detection (Flipper Zero popup/Fast Pair spam)
     * 2. Advertising rate spike (Axon Signal trigger activation)
     * 3. Raven service UUID matching
     * 4. BLE device name pattern matching (includes Flipper Zero)
     * 5. BLE service UUID matching
     * 6. MAC prefix matching
     * 7. Consumer tracker detection (AirTag, Tile, SmartTag)
     *
     * @param context The BLE detection context
     * @return BleDetectionResult if a detection was made, null otherwise
     */
    fun handleDetection(context: BleDetectionContext): BleDetectionResult? {
        // Skip if below RSSI threshold
        if (context.rssi < config.rssiThreshold) {
            return null
        }

        val now = System.currentTimeMillis()

        // Priority 0: Always track for BLE spam detection (even if rate limited)
        trackForBleSpam(context)

        // Rate limiting check
        val lastTime = lastDetectionTime[context.macAddress] ?: 0L
        if (now - lastTime < config.rateLimitMs) {
            return null
        }

        // Priority 1: Check for BLE spam attack (Flipper Zero iOS popup / Fast Pair spam)
        // This check runs on aggregate data, not individual packets
        checkBleSpamAttack(context)?.let { result ->
            // Don't rate limit spam detection by MAC - it's aggregate
            return result
        }

        // Priority 2: Check for advertising rate spike (Axon Signal trigger)
        if (config.enablePoliceEquipmentDetection) {
            checkAdvertisingRateSpike(context)?.let { result ->
                lastDetectionTime[context.macAddress] = now
                return result
            }
        }

        // Priority 3: Check for Raven device by service UUIDs
        checkRavenDevice(context)?.let { result ->
            lastDetectionTime[context.macAddress] = now
            return result
        }

        // Priority 4: Check device name patterns (includes Flipper Zero, hacking tools)
        context.deviceName?.let { name ->
            checkDeviceNamePattern(context, name)?.let { result ->
                lastDetectionTime[context.macAddress] = now
                return result
            }
        }

        // Priority 5: Check service UUID patterns
        checkServiceUuidPatterns(context)?.let { result ->
            lastDetectionTime[context.macAddress] = now
            return result
        }

        // Priority 6: Check MAC prefix
        checkMacPrefix(context)?.let { result ->
            lastDetectionTime[context.macAddress] = now
            return result
        }

        // Priority 7: Check for consumer trackers
        if (config.enableTrackerDetection) {
            checkConsumerTrackers(context)?.let { result ->
                lastDetectionTime[context.macAddress] = now
                return result
            }
        }

        return null
    }

    // ==================== DETECTION METHODS ====================

    /**
     * Check for advertising rate spike indicating Axon Signal trigger activation.
     *
     * Axon Signal devices normally advertise at ~1 packet/second but spike to
     * ~20-50 packets/second when activated (siren, gun draw, etc.).
     *
     * Enhanced with:
     * - Location correlation (near known police activity areas?)
     * - Time of day patterns
     * - Activation duration tracking
     * - Historical activation analysis
     */
    private fun checkAdvertisingRateSpike(context: BleDetectionContext): BleDetectionResult? {
        if (context.advertisingRate < config.advertisingRateSpikeThreshold) {
            return null
        }

        // Check for Nordic Semiconductor (common in Axon) or Apple wrapper
        val isNordic = context.manufacturerData.containsKey(MANUFACTURER_ID_NORDIC)
        val isAppleWrapper = context.manufacturerData.containsKey(MANUFACTURER_ID_APPLE)

        if (!isNordic && !isAppleWrapper) {
            return null
        }

        val now = System.currentTimeMillis()

        // Record this activation for historical analysis
        val activations = axonActivations.computeIfAbsent(context.macAddress) { CopyOnWriteArrayList() }
        activations.add(AxonActivationEvent(
            timestamp = now,
            latitude = context.latitude,
            longitude = context.longitude,
            advertisingRate = context.advertisingRate
        ))

        // Prune old activations (keep last 24 hours)
        val cutoff = now - (TRACKER_ANALYSIS_WINDOW_HOURS * 60 * 60 * 1000L)
        activations.removeIf { it.timestamp < cutoff }

        // Analyze activation patterns
        val activationAnalysis = analyzeAxonActivations(context, activations)

        Log.w(TAG, "AXON SIGNAL ACTIVATION: ${context.macAddress} " +
                "(${context.advertisingRate} pps) - ${activationAnalysis.activationContext}")

        val manufacturer = if (isNordic) "Nordic Semiconductor (Axon)" else "Apple BLE Wrapper"
        val displayName = context.deviceName?.takeIf { it.isNotBlank() }
            ?: "Axon Signal Trigger (ACTIVE)"

        // Build enhanced matched patterns
        val patterns = mutableListOf(
            "Advertising spike: ${context.advertisingRate.toInt()} packets/sec (normal: ~1 pps)",
            "Rate increase: ${(context.advertisingRate / NORMAL_ADVERTISING_RATE).toInt()}x normal",
            activationAnalysis.activationContext
        )
        if (activationAnalysis.isRecurringLocation) {
            patterns.add("RECURRING: Activation at previously seen location")
        }
        if (activationAnalysis.previousActivationsToday > 0) {
            patterns.add("PATTERN: ${activationAnalysis.previousActivationsToday + 1} activations in past 24h")
        }
        patterns.add("Time context: ${activationAnalysis.timeOfDayContext}")

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.BLE_SERVICE_UUID,
            deviceType = DeviceType.AXON_POLICE_TECH,
            deviceName = displayName,
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = ThreatLevel.CRITICAL,
            threatScore = activationAnalysis.adjustedThreatScore,
            manufacturer = manufacturer,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(patterns),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildEnhancedAxonSignalPrompt(context, activationAnalysis),
            confidence = calculateConfidence(context, 0.95f)
        )
    }

    /**
     * Analysis result for Axon Signal activations.
     */
    private data class AxonActivationAnalysis(
        val activationContext: String,
        val timeOfDayContext: String,
        val previousActivationsToday: Int,
        val isRecurringLocation: Boolean,
        val estimatedDurationSeconds: Int,
        val adjustedThreatScore: Int
    )

    /**
     * Analyze Axon Signal activation patterns for context.
     */
    private fun analyzeAxonActivations(
        context: BleDetectionContext,
        activations: List<AxonActivationEvent>
    ): AxonActivationAnalysis {
        val now = System.currentTimeMillis()

        // Count previous activations today
        val oneDayAgo = now - (24 * 60 * 60 * 1000L)
        val previousActivationsToday = activations.count { it.timestamp < now - 5000 && it.timestamp > oneDayAgo }

        // Check if this is a recurring location
        val isRecurringLocation = if (context.latitude != null && context.longitude != null) {
            activations.any { activation ->
                activation.latitude != null && activation.longitude != null &&
                        activation.timestamp < now - (5 * 60 * 1000) && // At least 5 minutes ago
                        calculateDistance(
                            context.latitude, context.longitude,
                            activation.latitude, activation.longitude
                        ) < SAME_LOCATION_RADIUS_METERS
            }
        } else false

        // Determine time of day context
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDayContext = when {
            hour in 0..5 -> "Late night (00:00-06:00) - unusual activity hours"
            hour in 6..8 -> "Early morning (06:00-09:00) - morning shift/commute"
            hour in 9..16 -> "Daytime (09:00-17:00) - regular patrol hours"
            hour in 17..20 -> "Evening (17:00-21:00) - evening shift"
            else -> "Night (21:00-00:00) - night shift hours"
        }

        // Determine activation context based on patterns
        val activationContext = when {
            context.advertisingRate > 40 -> "Very high rate suggests imminent engagement (weapon drawn, crash, or emergency)"
            context.advertisingRate > 30 -> "High rate suggests active police engagement (siren activated)"
            else -> "Moderate spike suggests manual activation or minor trigger"
        }

        // Adjust threat score based on patterns
        val baseThreatScore = 95
        val adjustedThreatScore = when {
            isRecurringLocation && previousActivationsToday >= 2 -> minOf(baseThreatScore, 85) // Likely patrol route
            previousActivationsToday >= 5 -> minOf(baseThreatScore, 80) // Very frequent, likely equipment testing
            hour in 0..5 && context.advertisingRate > 30 -> 100 // Late night high activity = critical
            else -> baseThreatScore
        }

        return AxonActivationAnalysis(
            activationContext = activationContext,
            timeOfDayContext = timeOfDayContext,
            previousActivationsToday = previousActivationsToday,
            isRecurringLocation = isRecurringLocation,
            estimatedDurationSeconds = 0, // Would need continuous monitoring to determine
            adjustedThreatScore = adjustedThreatScore
        )
    }

    /**
     * Build enhanced AI prompt for Axon Signal with full context.
     */
    private fun buildEnhancedAxonSignalPrompt(
        context: BleDetectionContext,
        analysis: AxonActivationAnalysis
    ): String {
        val displayName = context.deviceName?.takeIf { it.isNotBlank() }
            ?: "Axon Signal Trigger"

        return """CRITICAL ALERT: Axon Signal Trigger Activation Detected

=== DEVICE INFORMATION ===
Device Name: $displayName
MAC Address: ${context.macAddress}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Location: ${formatLocation(context)}

=== ACTIVATION ANALYSIS ===
Current Advertising Rate: ${context.advertisingRate.toInt()} packets/second
Normal Rate: ~1 packet/second
Rate Increase: ${(context.advertisingRate / NORMAL_ADVERTISING_RATE).toInt()}x normal

Activation Context: ${analysis.activationContext}
Time Context: ${analysis.timeOfDayContext}
Previous Activations (24h): ${analysis.previousActivationsToday}
Recurring Location: ${if (analysis.isRecurringLocation) "Yes - previously seen here" else "No - new location"}

=== WHAT IS AXON SIGNAL? ===
Axon Signal is a body camera activation system used by law enforcement. Signal devices
are installed in police vehicles, holsters, and other equipment. They broadcast at
normal rates (~1 packet/sec) during standby but spike to 20-50+ packets/sec when
an "activation event" occurs:

Activation Triggers:
- Police siren activated (Signal Vehicle)
- Weapon drawn from holster (Signal Sidearm)
- Vehicle crash or rapid deceleration (Signal Vehicle)
- Manual activation button pressed
- Door open while in pursuit mode

=== WHAT THIS MEANS FOR YOU ===
This detection indicates active police engagement in your immediate vicinity.
When Signal activates, it triggers ALL nearby Axon body cameras to start recording
automatically. This creates a network of synchronized recording devices.

Privacy Implications:
- Multiple body cameras are likely now recording
- Dash cameras in the vicinity are likely recording
- Your presence, appearance, and actions may be captured
- Footage is stored on Axon's Evidence.com cloud platform
- Retention period varies by department (typically 60-180 days)

=== RECOMMENDED ACTIONS ===
1. Be aware that you may be recorded by multiple cameras
2. If interacting with police, remain calm and cooperative
3. Note the time and location for potential FOIA requests
4. You have the right to record police in public spaces
5. If concerned about being recorded, move away from the area

=== TECHNICAL DETAILS ===
Manufacturer IDs: ${context.manufacturerData.keys.joinToString { "0x${"%04X".format(it)}" }}
Nordic Semiconductor detected: ${context.manufacturerData.containsKey(MANUFACTURER_ID_NORDIC)}
Apple BLE wrapper: ${context.manufacturerData.containsKey(MANUFACTURER_ID_APPLE)}

=== CONTEXT ASSESSMENT ===
${if (analysis.isRecurringLocation) """
This activation occurred at a location where Axon Signal has been detected before.
This could indicate:
- Regular patrol route
- Known police checkpoint or activity area
- Repeated law enforcement activity in this area
""" else """
This is the first Axon Signal activation detected at this location.
"""}

${if (analysis.previousActivationsToday > 0) """
There have been ${analysis.previousActivationsToday} other activation(s) from this device
in the past 24 hours. ${if (analysis.previousActivationsToday >= 3) "This frequency suggests either an active patrol shift or equipment testing." else ""}
""" else ""}

---
Analyze this detection and provide situational awareness guidance.
Consider the time of day, location context, and activation patterns.
"""
    }

    /**
     * Check for Raven gunshot detector by service UUIDs.
     *
     * Raven devices advertise specific service UUIDs for GPS, power management,
     * network status, and diagnostics based on firmware version.
     *
     * Enhanced with:
     * - Known deployment location database context
     * - Audio surveillance implications
     * - Civil liberties context
     */
    private fun checkRavenDevice(context: BleDetectionContext): BleDetectionResult? {
        if (!DetectionPatterns.isRavenDevice(context.serviceUuids)) {
            return null
        }

        val matchedServices = DetectionPatterns.matchRavenServices(context.serviceUuids)
        val firmwareVersion = DetectionPatterns.estimateRavenFirmwareVersion(context.serviceUuids)

        // Build proper display name - never null
        val displayName = context.deviceName?.takeIf { it.isNotBlank() }
            ?: "Raven Acoustic Sensor ($firmwareVersion)"

        Log.w(TAG, "RAVEN DEVICE DETECTED: ${context.macAddress} - $firmwareVersion")

        // Build comprehensive matched patterns
        val patterns = matchedServices.map { "${it.name}: ${it.description}" }.toMutableList()
        patterns.add("Firmware: $firmwareVersion")
        patterns.add("AUDIO SURVEILLANCE: Continuously monitors for gunfire and 'human distress'")
        patterns.add("CIVIL LIBERTIES: Records audio in public spaces without consent")

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.RAVEN_SERVICE_UUID,
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            deviceName = displayName,
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = ThreatLevel.CRITICAL,
            threatScore = 100,
            manufacturer = "Flock Safety / SoundThinking",
            firmwareVersion = firmwareVersion,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(patterns),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildEnhancedRavenPrompt(context, matchedServices, firmwareVersion),
            confidence = calculateConfidence(context, 1.0f)
        )
    }

    /**
     * Build enhanced AI prompt for Raven with civil liberties context.
     */
    private fun buildEnhancedRavenPrompt(
        context: BleDetectionContext,
        matchedServices: List<DetectionPatterns.RavenServiceInfo>,
        firmwareVersion: String
    ): String {
        val displayName = context.deviceName?.takeIf { it.isNotBlank() }
            ?: "Raven Acoustic Sensor"
        val servicesSection = matchedServices.joinToString("\n") { service ->
            "- ${service.name}: ${service.description}\n  Data exposed: ${service.dataExposed}"
        }

        return """CRITICAL: Raven Acoustic Surveillance Device Detected

=== DEVICE INFORMATION ===
Device Name: $displayName
MAC Address: ${context.macAddress}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Firmware Version: $firmwareVersion
Location: ${formatLocation(context)}

=== WHAT IS THIS DEVICE? ===
Raven is Flock Safety's acoustic surveillance system. These solar-powered devices
are deployed throughout communities and continuously monitor audio using AI to detect:

1. GUNFIRE DETECTION (Primary Purpose)
   - Listens for gunshot acoustic signatures
   - Triangulates location with other sensors
   - Sends instant alerts to law enforcement

2. "HUMAN DISTRESS" DETECTION (Announced October 2025)
   - Listens for screaming, shouting, calls for help
   - Definition of "distress" is intentionally vague
   - Scope of audio captured is unclear

=== MATCHED BLE SERVICES (${matchedServices.size}) ===
$servicesSection

=== AUDIO SURVEILLANCE IMPLICATIONS ===
Unlike cameras which have a visible field of view, audio surveillance:
- Captures sound omnidirectionally
- Can hear through walls and obstacles
- Records private conversations unintentionally
- Has no clear indication when "listening"
- Processes ALL audio to detect "distress"

What this means for your privacy:
- Your conversations within range may be processed
- AI determines what constitutes "distress"
- Audio clips are transmitted to Flock's cloud
- Law enforcement receives alerts without warrant
- Data retention policies are opaque

=== CIVIL LIBERTIES CONCERNS ===
- First Amendment: Chilling effect on public speech
- Fourth Amendment: Warrantless audio surveillance
- No consent from recorded individuals
- Disproportionate deployment in minority communities
- "Human distress" is subjective and prone to bias
- False positives can trigger armed police response

=== BLE VULNERABILITY (GainSec Research) ===
Raven devices expose sensitive data via Bluetooth:
- Exact GPS coordinates of the sensor
- Battery and solar power status
- Cellular network information
- Upload statistics and detection counts
- System diagnostics and errors

This vulnerability allows mapping of the surveillance network.

=== KNOWN DEPLOYMENT CONTEXT ===
Raven devices are typically deployed:
- Along major roadways
- In "high crime" areas (often minority neighborhoods)
- Near parks and public spaces
- In residential areas with HOA agreements
- At shopping centers and business districts

=== RECOMMENDED ACTIONS ===
1. Document this detection (location, time, signal strength)
2. Check if your local government approved this surveillance
3. Request records on Raven deployment in your area (FOIA)
4. Contact local civil liberties organizations
5. Attend city council meetings about surveillance spending

=== LEGAL RESOURCES ===
- EFF (Electronic Frontier Foundation): eff.org
- ACLU local chapter
- Local privacy advocacy groups
- Community oversight boards (if they exist)

=== RAW DETECTION DATA ===
${formatRawBleData(context)}

---
Analyze this detection and provide guidance on the civil liberties implications,
community organizing opportunities, and legal options for the user.
"""
    }

    /**
     * Check device name against known surveillance patterns.
     */
    private fun checkDeviceNamePattern(
        context: BleDetectionContext,
        deviceName: String
    ): BleDetectionResult? {
        val pattern = DetectionPatterns.matchBleNamePattern(deviceName) ?: return null

        // Skip police equipment if disabled
        if (!config.enablePoliceEquipmentDetection && isPoliceEquipment(pattern.deviceType)) {
            return null
        }

        // Skip beacons if disabled
        if (!config.enableBeaconDetection && isBeaconDevice(pattern.deviceType)) {
            return null
        }

        // Skip smart home if disabled
        if (!config.enableSmartHomeDetection && isSmartHomeDevice(pattern.deviceType)) {
            return null
        }

        Log.d(TAG, "BLE name pattern match: $deviceName -> ${pattern.deviceType}")

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.BLE_DEVICE_NAME,
            deviceType = pattern.deviceType,
            deviceName = deviceName,
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = scoreToThreatLevel(pattern.threatScore),
            threatScore = pattern.threatScore,
            manufacturer = pattern.manufacturer,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(listOf(pattern.description)),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildDeviceNamePrompt(context, pattern),
            confidence = calculateConfidence(context, 0.85f)
        )
    }

    /**
     * Check service UUIDs against known patterns.
     */
    private fun checkServiceUuidPatterns(context: BleDetectionContext): BleDetectionResult? {
        // Check for Find My network (AirTag-like)
        if (context.serviceUuids.any { it == UUID_APPLE_FIND_MY }) {
            return createTrackerDetection(
                context = context,
                deviceType = DeviceType.AIRTAG,
                detectionMethod = DetectionMethod.AIRTAG_DETECTED,
                manufacturer = "Apple",
                description = "Apple Find My network device detected",
                threatScore = 60
            )
        }

        // Check for Tile service
        if (context.serviceUuids.any { it == UUID_TILE_SERVICE }) {
            return createTrackerDetection(
                context = context,
                deviceType = DeviceType.TILE_TRACKER,
                detectionMethod = DetectionMethod.TILE_DETECTED,
                manufacturer = "Tile",
                description = "Tile Bluetooth tracker detected",
                threatScore = 55
            )
        }

        // Check for Samsung SmartTag
        if (context.serviceUuids.any { it == UUID_SAMSUNG_SMARTTAG }) {
            return createTrackerDetection(
                context = context,
                deviceType = DeviceType.SAMSUNG_SMARTTAG,
                detectionMethod = DetectionMethod.SMARTTAG_DETECTED,
                manufacturer = "Samsung",
                description = "Samsung SmartTag tracker detected",
                threatScore = 55
            )
        }

        return null
    }

    /**
     * Check MAC address prefix (OUI) against known manufacturers.
     * Enhanced with proper device name handling and OUI lookup context.
     */
    private fun checkMacPrefix(context: BleDetectionContext): BleDetectionResult? {
        val macPrefix = DetectionPatterns.matchMacPrefix(context.macAddress) ?: return null

        // Skip police equipment if disabled
        if (!config.enablePoliceEquipmentDetection && isPoliceEquipment(macPrefix.deviceType)) {
            return null
        }

        // Build proper display name - never null
        val displayName = context.deviceName?.takeIf { it.isNotBlank() }
            ?: "${macPrefix.manufacturer} Device (${context.macAddress.takeLast(8)})"

        Log.d(TAG, "MAC prefix match: ${context.macAddress} -> ${macPrefix.deviceType}")

        // Build comprehensive matched patterns
        val patterns = mutableListOf<String>()
        patterns.add(macPrefix.description.ifEmpty { "MAC prefix: ${macPrefix.prefix}" })
        patterns.add("Manufacturer OUI: ${macPrefix.prefix} -> ${macPrefix.manufacturer}")

        // Add context based on device type
        when (macPrefix.deviceType) {
            DeviceType.FLEET_VEHICLE -> {
                patterns.add("FLEET: Mobile router commonly used in police/government vehicles")
            }
            DeviceType.FLOCK_SAFETY_CAMERA -> {
                patterns.add("ALPR: LTE modem manufacturer commonly used in Flock cameras")
            }
            DeviceType.AXON_POLICE_TECH -> {
                patterns.add("POLICE TECH: Nordic/TI BLE chip common in body cameras")
            }
            else -> {}
        }

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.MAC_PREFIX,
            deviceType = macPrefix.deviceType,
            deviceName = displayName,
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = scoreToThreatLevel(macPrefix.threatScore),
            threatScore = macPrefix.threatScore,
            manufacturer = macPrefix.manufacturer,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(patterns),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildEnhancedMacPrefixPrompt(context, macPrefix),
            confidence = calculateConfidence(context, 0.70f)
        )
    }

    /**
     * Build enhanced AI prompt for MAC prefix detections with OUI context.
     */
    private fun buildEnhancedMacPrefixPrompt(
        context: BleDetectionContext,
        macPrefix: DetectionPatterns.MacPrefix
    ): String {
        val displayName = context.deviceName?.takeIf { it.isNotBlank() }
            ?: "${macPrefix.manufacturer} Device"
        val deviceInfo = DetectionPatterns.getDeviceTypeInfo(macPrefix.deviceType)

        return """BLE Device Detected via MAC OUI: ${macPrefix.deviceType.displayName}

=== DEVICE IDENTIFICATION ===
Display Name: $displayName
MAC Address: ${context.macAddress}
MAC Prefix (OUI): ${macPrefix.prefix}
Manufacturer: ${macPrefix.manufacturer}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Location: ${formatLocation(context)}

=== OUI DATABASE LOOKUP ===
The first 3 bytes of a MAC address (OUI - Organizationally Unique Identifier) identify
the hardware manufacturer. This detection was triggered by:

OUI: ${macPrefix.prefix}
Registered To: ${macPrefix.manufacturer}
Associated Device Type: ${macPrefix.deviceType.displayName}
Threat Score: ${macPrefix.threatScore}/100
Match Confidence: Lower than name/UUID matches (hardware only)

=== ABOUT THIS DEVICE TYPE ===
${deviceInfo.fullDescription}

Capabilities:
${deviceInfo.capabilities.joinToString("\n") { "- $it" }}

Privacy Concerns:
${deviceInfo.privacyConcerns.joinToString("\n") { "- $it" }}

=== DETECTION CONFIDENCE ===
MAC prefix matching has LOWER confidence than name or UUID matching because:
- Many legitimate devices use the same hardware manufacturers
- OUI only identifies the chip/modem, not the end device
- Device could be surveillance equipment OR regular consumer device

This detection should be treated as "possible" rather than "confirmed" surveillance.

=== ADDITIONAL CONTEXT ===
Service UUIDs found: ${context.serviceUuids.size}
${if (context.serviceUuids.isNotEmpty()) context.serviceUuids.joinToString("\n") { "- $it" } else "(none)"}

Manufacturer Data IDs: ${context.manufacturerData.keys.joinToString { "0x${"%04X".format(it)}" }.ifEmpty { "(none)" }}

=== FALSE POSITIVE INDICATORS ===
This detection might be a false positive if:
- The device has a consumer-oriented name (e.g., "John's Laptop")
- It's a known device in your household
- The signal is very weak (far away, incidental detection)
- You're near an office building or commercial area

=== RECOMMENDED ACTIONS ===
${deviceInfo.recommendations.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" } ?: """
- Monitor this device for patterns
- Note if it appears at multiple locations
- Check signal strength over time
- Compare with other detection methods"""}

---
Analyze this MAC prefix detection and provide assessment of:
1. Likelihood this is surveillance equipment vs. false positive
2. Additional investigation steps
3. What to do if confirmed as surveillance
"""
    }

    /**
     * Check for consumer trackers by manufacturer data.
     */
    private fun checkConsumerTrackers(context: BleDetectionContext): BleDetectionResult? {
        // Check Apple manufacturer data for potential AirTag
        if (context.manufacturerData.containsKey(MANUFACTURER_ID_APPLE)) {
            val data = context.manufacturerData[MANUFACTURER_ID_APPLE] ?: ""
            if (isLikelyAirTag(data)) {
                return createTrackerDetection(
                    context = context,
                    deviceType = DeviceType.AIRTAG,
                    detectionMethod = DetectionMethod.AIRTAG_DETECTED,
                    manufacturer = "Apple",
                    description = "Likely Apple AirTag (manufacturer data pattern)",
                    threatScore = 60
                )
            }
        }

        // Check Samsung manufacturer data for SmartTag
        if (context.manufacturerData.containsKey(MANUFACTURER_ID_SAMSUNG)) {
            return createTrackerDetection(
                context = context,
                deviceType = DeviceType.SAMSUNG_SMARTTAG,
                detectionMethod = DetectionMethod.SMARTTAG_DETECTED,
                manufacturer = "Samsung",
                description = "Samsung SmartTag (manufacturer data)",
                threatScore = 55
            )
        }

        // Check Tile manufacturer data
        if (context.manufacturerData.containsKey(MANUFACTURER_ID_TILE)) {
            return createTrackerDetection(
                context = context,
                deviceType = DeviceType.TILE_TRACKER,
                detectionMethod = DetectionMethod.TILE_DETECTED,
                manufacturer = "Tile",
                description = "Tile tracker (manufacturer data)",
                threatScore = 55
            )
        }

        return null
    }

    // ==================== FLIPPER ZERO / BLE SPAM DETECTION ====================

    /**
     * Track BLE advertisements for spam detection.
     * This method is called for every BLE advertisement to build statistics
     * for detecting Flipper Zero BLE spam attacks.
     */
    private fun trackForBleSpam(context: BleDetectionContext) {
        val now = System.currentTimeMillis()
        val cutoff = now - BLE_SPAM_DETECTION_WINDOW_MS

        // Track Apple device advertisements (for iOS popup spam detection)
        if (context.manufacturerData.containsKey(MANUFACTURER_ID_APPLE)) {
            val data = context.manufacturerData[MANUFACTURER_ID_APPLE] ?: ""
            // Check for AirPods/Beats/Apple accessory advertisement patterns
            // These are the types of ads Flipper uses for popup spam
            if (isAppleAccessoryAdvertisement(data)) {
                val events = appleAdvertisements.computeIfAbsent("aggregate") { CopyOnWriteArrayList() }
                events.add(BleSpamEvent(
                    timestamp = now,
                    macAddress = context.macAddress,
                    deviceName = context.deviceName,
                    manufacturerData = data
                ))
                // Prune old events
                events.removeIf { it.timestamp < cutoff }
            }
        }

        // Track Fast Pair advertisements (for Android spam detection)
        if (context.serviceUuids.any { it == UUID_GOOGLE_FAST_PAIR } ||
            context.manufacturerData.containsKey(MANUFACTURER_ID_GOOGLE)) {
            val events = fastPairAdvertisements.computeIfAbsent("aggregate") { CopyOnWriteArrayList() }
            events.add(BleSpamEvent(
                timestamp = now,
                macAddress = context.macAddress,
                deviceName = context.deviceName,
                manufacturerData = context.manufacturerData[MANUFACTURER_ID_GOOGLE] ?: ""
            ))
            // Prune old events
            events.removeIf { it.timestamp < cutoff }
        }

        // Track device name changes from same MAC (Flipper impersonation detection)
        context.deviceName?.let { name ->
            val nameEvents = deviceNameHistory.computeIfAbsent(context.macAddress) { CopyOnWriteArrayList() }
            // Only add if name is different from most recent
            val lastEvent = nameEvents.lastOrNull()
            if (lastEvent == null || lastEvent.deviceName != name) {
                nameEvents.add(DeviceNameEvent(
                    timestamp = now,
                    deviceName = name
                ))
            }
            // Prune old events
            nameEvents.removeIf { it.timestamp < cutoff }
        }
    }

    /**
     * Check if manufacturer data looks like Apple accessory advertisement.
     * Flipper Zero spam uses these advertisement types to trigger iOS popups.
     */
    private fun isAppleAccessoryAdvertisement(manufacturerData: String): Boolean {
        if (manufacturerData.length < 4) return false

        // Apple Nearby Action codes that trigger popups
        // 0x07 = AirPods, 0x10 = AirPods Pro, 0x0F = AirPods Max
        // 0x05 = AppleTV setup, 0x0C = HomePod, etc.
        val typeCode = manufacturerData.take(2).uppercase()
        return typeCode in listOf(
            "07", "0F", "10", "05", "0C", "0D", "0E", "13", "14",
            "01", "06", "09", "0A", "0B", "11", "12"
        )
    }

    /**
     * Check for BLE spam attack patterns.
     * Detects Flipper Zero BLE spam by looking for:
     * 1. Many Apple accessory advertisements in short time (iOS popup attack)
     * 2. Many Fast Pair advertisements in short time (Android notification spam)
     * 3. Rapid device name changes from single MAC (device impersonation)
     *
     * @return BleDetectionResult if spam attack detected, null otherwise
     */
    private fun checkBleSpamAttack(context: BleDetectionContext): BleDetectionResult? {
        val now = System.currentTimeMillis()

        // Rate limit spam alerts to prevent alert fatigue
        if (now - lastSpamAlertTime < spamAlertCooldownMs) {
            return null
        }

        // Check for Apple device spam (iOS popup attack)
        val appleEvents = appleAdvertisements["aggregate"] ?: emptyList()
        if (appleEvents.size >= APPLE_SPAM_THRESHOLD) {
            val uniqueMacs = appleEvents.map { it.macAddress }.toSet().size
            val spamAnalysis = analyzeBleSpam(appleEvents, "Apple/iOS Popup")

            if (spamAnalysis.isLikelySpam) {
                lastSpamAlertTime = now
                Log.w(TAG, "FLIPPER BLE SPAM DETECTED: iOS Popup Attack - " +
                        "${appleEvents.size} Apple ads from $uniqueMacs sources in ${BLE_SPAM_DETECTION_WINDOW_MS}ms")

                return createBleSpamDetection(
                    context = context,
                    spamType = BleSpamType.APPLE_POPUP,
                    analysis = spamAnalysis
                )
            }
        }

        // Check for Fast Pair spam (Android notification attack)
        val fastPairEvents = fastPairAdvertisements["aggregate"] ?: emptyList()
        if (fastPairEvents.size >= FAST_PAIR_SPAM_THRESHOLD) {
            val uniqueMacs = fastPairEvents.map { it.macAddress }.toSet().size
            val spamAnalysis = analyzeBleSpam(fastPairEvents, "Fast Pair/Android")

            if (spamAnalysis.isLikelySpam) {
                lastSpamAlertTime = now
                Log.w(TAG, "FLIPPER BLE SPAM DETECTED: Android Fast Pair Attack - " +
                        "${fastPairEvents.size} Fast Pair ads from $uniqueMacs sources in ${BLE_SPAM_DETECTION_WINDOW_MS}ms")

                return createBleSpamDetection(
                    context = context,
                    spamType = BleSpamType.FAST_PAIR,
                    analysis = spamAnalysis
                )
            }
        }

        // Check for rapid device name changes (Flipper impersonation)
        for ((mac, nameEvents) in deviceNameHistory) {
            if (nameEvents.size >= DEVICE_NAME_CHANGE_THRESHOLD) {
                val uniqueNames = nameEvents.map { it.deviceName }.toSet().size
                if (uniqueNames >= DEVICE_NAME_CHANGE_THRESHOLD) {
                    lastSpamAlertTime = now
                    Log.w(TAG, "FLIPPER DEVICE IMPERSONATION: MAC $mac changed names $uniqueNames times")

                    val impersonationAnalysis = BleSpamAnalysis(
                        isLikelySpam = true,
                        spamType = "Device Impersonation",
                        totalEvents = nameEvents.size,
                        uniqueSources = 1,
                        eventsPerSecond = nameEvents.size.toFloat() / (BLE_SPAM_DETECTION_WINDOW_MS / 1000f),
                        suspicionReasons = listOf(
                            "Single MAC address changing names rapidly",
                            "$uniqueNames different device names in ${BLE_SPAM_DETECTION_WINDOW_MS / 1000}s",
                            "Classic Flipper Zero impersonation behavior",
                            "Names seen: ${nameEvents.takeLast(5).map { it.deviceName }.joinToString(", ")}"
                        ),
                        threatScore = 85
                    )

                    return createBleSpamDetection(
                        context = context.copy(macAddress = mac),
                        spamType = BleSpamType.DEVICE_IMPERSONATION,
                        analysis = impersonationAnalysis
                    )
                }
            }
        }

        return null
    }

    /**
     * Analyze BLE spam events to determine if it's a real attack.
     */
    private fun analyzeBleSpam(events: List<BleSpamEvent>, spamTypeName: String): BleSpamAnalysis {
        if (events.isEmpty()) {
            return BleSpamAnalysis(
                isLikelySpam = false,
                spamType = spamTypeName,
                totalEvents = 0,
                uniqueSources = 0,
                eventsPerSecond = 0f,
                suspicionReasons = emptyList(),
                threatScore = 0
            )
        }

        val uniqueMacs = events.map { it.macAddress }.toSet()
        val uniqueNames = events.mapNotNull { it.deviceName }.toSet()
        val timeSpanMs = (events.maxOf { it.timestamp } - events.minOf { it.timestamp }).coerceAtLeast(1)
        val eventsPerSecond = events.size.toFloat() / (timeSpanMs / 1000f).coerceAtLeast(0.1f)

        val suspicionReasons = mutableListOf<String>()
        var threatScore = 50

        // High event rate is suspicious
        if (eventsPerSecond > 5f) {
            suspicionReasons.add("Very high advertisement rate: ${String.format("%.1f", eventsPerSecond)}/sec")
            threatScore += 20
        } else if (eventsPerSecond > 2f) {
            suspicionReasons.add("Elevated advertisement rate: ${String.format("%.1f", eventsPerSecond)}/sec")
            threatScore += 10
        }

        // Many unique MACs broadcasting similar ads is suspicious
        // (Flipper cycles through random MACs)
        if (uniqueMacs.size > 5) {
            suspicionReasons.add("Many unique MAC addresses (${uniqueMacs.size}) - likely MAC randomization")
            threatScore += 15
        }

        // Many unique device names is suspicious (impersonation)
        if (uniqueNames.size > 3) {
            suspicionReasons.add("Many unique device names (${uniqueNames.size}) - device impersonation")
            threatScore += 10
        }

        // Single MAC with many events is suspicious (stationary attacker)
        if (uniqueMacs.size == 1 && events.size > 10) {
            suspicionReasons.add("Single source flooding: ${events.size} events from one MAC")
            threatScore += 15
        }

        // Determine if this is likely spam
        val isLikelySpam = suspicionReasons.isNotEmpty() && threatScore >= 60

        if (isLikelySpam) {
            suspicionReasons.add(0, "ACTIVE BLE SPAM ATTACK DETECTED")
            suspicionReasons.add("Likely Flipper Zero or similar device")
        }

        return BleSpamAnalysis(
            isLikelySpam = isLikelySpam,
            spamType = spamTypeName,
            totalEvents = events.size,
            uniqueSources = uniqueMacs.size,
            eventsPerSecond = eventsPerSecond,
            suspicionReasons = suspicionReasons,
            threatScore = threatScore.coerceIn(0, 100)
        )
    }

    /**
     * Create a detection result for BLE spam attack.
     */
    private fun createBleSpamDetection(
        context: BleDetectionContext,
        spamType: BleSpamType,
        analysis: BleSpamAnalysis
    ): BleDetectionResult {
        val (deviceType, detectionMethod) = when (spamType) {
            BleSpamType.APPLE_POPUP -> DeviceType.FLIPPER_ZERO_SPAM to DetectionMethod.FLIPPER_APPLE_SPAM
            BleSpamType.FAST_PAIR -> DeviceType.FLIPPER_ZERO_SPAM to DetectionMethod.FLIPPER_FAST_PAIR_SPAM
            BleSpamType.DEVICE_IMPERSONATION -> DeviceType.FLIPPER_ZERO_SPAM to DetectionMethod.FLIPPER_BLE_SPAM
        }

        val displayName = when (spamType) {
            BleSpamType.APPLE_POPUP -> "Flipper Zero BLE Spam (iOS Popup Attack)"
            BleSpamType.FAST_PAIR -> "Flipper Zero BLE Spam (Android Fast Pair)"
            BleSpamType.DEVICE_IMPERSONATION -> "Flipper Zero Device Impersonation"
        }

        Log.w(TAG, "BLE SPAM ATTACK: $displayName - ThreatScore: ${analysis.threatScore}")

        val patterns = analysis.suspicionReasons.toMutableList()
        patterns.add("Total events: ${analysis.totalEvents} in ${BLE_SPAM_DETECTION_WINDOW_MS / 1000}s")
        patterns.add("Unique sources: ${analysis.uniqueSources}")
        patterns.add("Rate: ${String.format("%.1f", analysis.eventsPerSecond)} events/sec")

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = detectionMethod,
            deviceType = deviceType,
            deviceName = displayName,
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = ThreatLevel.HIGH,
            threatScore = analysis.threatScore,
            manufacturer = "Flipper Devices (likely)",
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(patterns),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildBleSpamPrompt(context, spamType, analysis),
            confidence = if (analysis.isLikelySpam) 0.9f else 0.6f
        )
    }

    /**
     * Build AI prompt for BLE spam attack detection.
     */
    private fun buildBleSpamPrompt(
        context: BleDetectionContext,
        spamType: BleSpamType,
        analysis: BleSpamAnalysis
    ): String {
        val attackTypeName = when (spamType) {
            BleSpamType.APPLE_POPUP -> "iOS Popup Spam Attack"
            BleSpamType.FAST_PAIR -> "Android Fast Pair Spam Attack"
            BleSpamType.DEVICE_IMPERSONATION -> "BLE Device Impersonation Attack"
        }

        val attackDescription = when (spamType) {
            BleSpamType.APPLE_POPUP -> """
                An iOS Popup Spam attack floods the Bluetooth spectrum with fake Apple device
                advertisements (AirPods, Beats, Apple TV, etc.). This causes iPhones and iPads
                in the vicinity to display constant pairing request popups, which can:
                - Make devices difficult or impossible to use
                - Crash older iOS versions
                - Distract users (possibly as cover for other attacks)
                - Harass specific individuals
            """.trimIndent()
            BleSpamType.FAST_PAIR -> """
                An Android Fast Pair Spam attack floods the Bluetooth spectrum with fake
                Google Fast Pair advertisements. This causes Android phones to display
                constant pairing notifications, which can:
                - Fill the notification shade with spam
                - Drain battery with constant notifications
                - Distract users
                - Make devices annoying to use
            """.trimIndent()
            BleSpamType.DEVICE_IMPERSONATION -> """
                Device Impersonation attack detected - a single MAC address is rapidly
                changing its advertised device name, impersonating multiple different
                Bluetooth devices. This is a classic Flipper Zero behavior used for:
                - Confusing Bluetooth scanners
                - Testing device responses to various names
                - Potentially triggering vulnerabilities in specific device handlers
            """.trimIndent()
        }

        return """CRITICAL ALERT: Flipper Zero BLE Spam Attack Detected

=== ATTACK TYPE ===
$attackTypeName

=== WHAT'S HAPPENING ===
$attackDescription

=== DETECTION STATISTICS ===
Total advertisements detected: ${analysis.totalEvents}
Unique source MACs: ${analysis.uniqueSources}
Advertisement rate: ${String.format("%.1f", analysis.eventsPerSecond)} per second
Time window: ${BLE_SPAM_DETECTION_WINDOW_MS / 1000} seconds
Threat Score: ${analysis.threatScore}/100

=== ANALYSIS ===
${analysis.suspicionReasons.joinToString("\n") { "- $it" }}

=== WHAT IS FLIPPER ZERO? ===
Flipper Zero is a portable multi-tool device (small, orange/black with LCD screen and D-pad)
that can interact with various radio protocols. The BLE spam attack is one of its most
notorious (and malicious) capabilities.

Firmware variants that enable BLE spam:
- Unleashed firmware
- RogueMaster firmware
- Xtreme firmware
- Various custom firmwares

THIS IS MALICIOUS USE - there is NO legitimate reason for BLE spam attacks.

=== IMMEDIATE ACTIONS ===
1. Turn off Bluetooth on your device to stop the popups/notifications
2. Look around for person with small handheld device (orange/black, LCD screen, D-pad)
3. Flipper Zero has limited range (~10-30m), so attacker is nearby
4. If attack follows you, document and consider reporting to authorities
5. Note time and location for pattern analysis

=== IDENTIFICATION TIPS ===
- Flipper Zero is about the size of a small TV remote
- Has a small LCD screen showing a dolphin mascot
- Orange/black body (though some have custom shells)
- Person may be looking at their device and then at you (checking if attack is working)
- Check if the popups stop when a specific person leaves

=== PROTECTION ===
- iOS: Settings > Bluetooth > Turn off (or use Control Center)
- Android: Settings > Connected devices > Connection preferences > Bluetooth > Off
- The attack only works when Bluetooth is enabled
- Airplane mode also stops the attack

=== LEGAL CONTEXT ===
While owning a Flipper Zero is legal, using it to spam BLE and disrupt devices
may violate computer fraud laws, harassment statutes, or FCC regulations
depending on jurisdiction. Document the incident if you wish to report it.

=== CURRENT DETECTION ===
Location: ${formatLocation(context)}
Signal Strength: ${context.rssi} dBm
${if (analysis.uniqueSources == 1) "Single attacker device detected" else "Attack appears to use MAC randomization"}

---
Provide guidance on protecting from this attack, identifying the attacker,
and what legal/reporting options are available.
"""
    }

    /**
     * Check if a detected device is a hacking tool (Flipper Zero, etc.)
     * and adjust context scoring for location awareness.
     */
    private fun isHackingTool(deviceType: DeviceType): Boolean {
        return deviceType in listOf(
            DeviceType.FLIPPER_ZERO,
            DeviceType.FLIPPER_ZERO_SPAM,
            DeviceType.HACKRF_SDR,
            DeviceType.PROXMARK,
            DeviceType.USB_RUBBER_DUCKY,
            DeviceType.BASH_BUNNY,
            DeviceType.LAN_TURTLE,
            DeviceType.KEYCROC,
            DeviceType.SHARK_JACK,
            DeviceType.SCREEN_CRAB,
            DeviceType.GENERIC_HACKING_TOOL,
            DeviceType.WIFI_PINEAPPLE
        )
    }

    // ==================== HELPER METHODS ====================

    /**
     * Create a tracker detection result with comprehensive stalking analysis.
     */
    private fun createTrackerDetection(
        context: BleDetectionContext,
        deviceType: DeviceType,
        detectionMethod: DetectionMethod,
        manufacturer: String,
        description: String,
        threatScore: Int
    ): BleDetectionResult {
        // Record sighting for tracking analysis
        recordTrackerSighting(context, deviceType)

        // Perform comprehensive tracker analysis
        val analysis = analyzeTracker(context, deviceType)

        // Adjust threat score based on stalking analysis
        val adjustedThreatScore = when {
            analysis.suspicionScore >= 80 -> maxOf(threatScore, 90)  // Critical - likely stalking
            analysis.suspicionScore >= 60 -> maxOf(threatScore, 75)  // High - suspicious
            analysis.suspicionScore >= 40 -> maxOf(threatScore, 60)  // Medium - investigate
            analysis.isPassingBy -> minOf(threatScore, 30)           // Reduce for passing traffic
            else -> threatScore
        }

        // Build display name - NEVER null
        val displayName = context.getDisplayName(deviceType)

        Log.d(TAG, "Tracker detected: $deviceType at ${context.macAddress} " +
                "(suspicion: ${analysis.suspicionScore}, threat: $adjustedThreatScore)")

        // Build comprehensive matched patterns including analysis
        val patterns = mutableListOf(description)
        patterns.addAll(analysis.suspicionReasons)
        if (analysis.isFollowingUser) {
            patterns.add("FOLLOWING: Detected at ${analysis.distinctLocationsCount} distinct locations")
        }
        if (analysis.isPossessionSignal) {
            patterns.add("PROXIMITY: Strong consistent signal suggests tracker is on your person/in your bag")
        }

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = if (analysis.isFollowingUser) DetectionMethod.TRACKER_FOLLOWING else detectionMethod,
            deviceType = deviceType,
            deviceName = displayName,
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = scoreToThreatLevel(adjustedThreatScore),
            threatScore = adjustedThreatScore,
            manufacturer = manufacturer,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(patterns),
            rawData = formatRawBleData(context)
        )

        // Build LLM context for comprehensive analysis
        val llmContext = buildTrackerLlmContext(context, deviceType, manufacturer, analysis)

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildEnhancedTrackerPrompt(context, deviceType, manufacturer, analysis, llmContext),
            confidence = calculateConfidence(context, 0.75f + (analysis.suspicionScore / 200f))
        )
    }

    /**
     * Build an enhanced AI prompt with full tracker analysis context.
     */
    private fun buildEnhancedTrackerPrompt(
        context: BleDetectionContext,
        deviceType: DeviceType,
        manufacturer: String,
        analysis: TrackerAnalysis,
        llmContext: TrackerLlmContext
    ): String {
        val displayName = context.getDisplayName(deviceType)

        return """BLUETOOTH TRACKER DETECTED: ${deviceType.displayName}

=== DEVICE INFORMATION ===
Device Type: ${deviceType.displayName}
Manufacturer: $manufacturer
Device Name: $displayName
MAC Address: ${context.macAddress}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Location: ${formatLocation(context)}

=== WHAT IS THIS DEVICE? ===
${llmContext.deviceDescription}

Capabilities:
${llmContext.capabilities.joinToString("\n") { "- $it" }}

=== WHY IS IT FLAGGED? ===
Suspicion Score: ${analysis.suspicionScore}/100 (${analysis.recommendation.displayText})

Detection Reasons:
${analysis.suspicionReasons.joinToString("\n") { "- $it" }}

Behavioral Indicators:
${llmContext.behavioralIndicators.joinToString("\n") { "- $it" }}

=== IS IT FOLLOWING YOU? ===
${llmContext.followingAnalysis}

Location Summary: ${llmContext.locationSummary}

=== WHAT DATA CAN IT COLLECT? ===
${llmContext.dataCollectionCapabilities.joinToString("\n") { "- $it" }}

=== WHAT SHOULD YOU DO? ===
Recommendation: ${analysis.recommendation.displayText}

Immediate Actions:
${llmContext.immediateActions.joinToString("\n") { "- $it" }}

${if (llmContext.preventiveActions.isNotEmpty()) """
Preventive Measures:
${llmContext.preventiveActions.joinToString("\n") { "- $it" }}
""" else ""}

=== LEGAL CONTEXT ===
${llmContext.legalContext}

=== REPORTING OPTIONS ===
${llmContext.reportingOptions.joinToString("\n") { "- $it" }}

=== RAW DETECTION DATA ===
${formatRawBleData(context)}

---
Analyze this detection and provide additional context, risk assessment,
and personalized recommendations based on the user's situation.
"""
    }

    /**
     * Track packet timestamp and calculate advertising rate.
     * Uses CopyOnWriteArrayList for thread-safe iteration without explicit synchronization.
     *
     * @param macAddress The device MAC address
     * @return Advertising rate in packets per second
     */
    private fun trackPacketAndGetRate(macAddress: String): Float {
        val now = System.currentTimeMillis()
        val cutoff = now - RATE_CALCULATION_WINDOW_MS

        val timestamps = packetTimestamps.computeIfAbsent(macAddress) { CopyOnWriteArrayList() }
        timestamps.add(now)

        // Remove old timestamps (CopyOnWriteArrayList handles concurrent iteration safely)
        timestamps.removeIf { it < cutoff }

        // Calculate rate
        val count = timestamps.size
        return if (count > 1) {
            count.toFloat() / (RATE_CALCULATION_WINDOW_MS / 1000f)
        } else {
            0f
        }
    }

    /**
     * Check if manufacturer data looks like an AirTag.
     * AirTags have specific manufacturer data patterns.
     */
    private fun isLikelyAirTag(manufacturerData: String): Boolean {
        if (manufacturerData.length < 4) return false

        // Check for Find My network beacon type (0x12 or 0x07)
        val typeBytes = manufacturerData.take(4)
        return typeBytes.startsWith("12") || typeBytes.startsWith("07") ||
                typeBytes.startsWith("1207") || typeBytes.startsWith("0712")
    }

    /**
     * Check if device type is police equipment.
     */
    private fun isPoliceEquipment(deviceType: DeviceType): Boolean {
        return deviceType in listOf(
            DeviceType.AXON_POLICE_TECH,
            DeviceType.MOTOROLA_POLICE_TECH,
            DeviceType.BODY_CAMERA,
            DeviceType.POLICE_RADIO,
            DeviceType.POLICE_VEHICLE,
            DeviceType.L3HARRIS_SURVEILLANCE,
            DeviceType.CELLEBRITE_FORENSICS,
            DeviceType.GRAYKEY_DEVICE
        )
    }

    /**
     * Check if device type is a beacon.
     */
    private fun isBeaconDevice(deviceType: DeviceType): Boolean {
        return deviceType in listOf(
            DeviceType.BLUETOOTH_BEACON,
            DeviceType.RETAIL_TRACKER,
            DeviceType.CROWD_ANALYTICS
        )
    }

    /**
     * Check if device type is a smart home device.
     */
    private fun isSmartHomeDevice(deviceType: DeviceType): Boolean {
        return deviceType in listOf(
            DeviceType.RING_DOORBELL,
            DeviceType.NEST_CAMERA,
            DeviceType.AMAZON_SIDEWALK,
            DeviceType.WYZE_CAMERA,
            DeviceType.ARLO_CAMERA,
            DeviceType.EUFY_CAMERA,
            DeviceType.BLINK_CAMERA,
            DeviceType.SIMPLISAFE_DEVICE,
            DeviceType.ADT_DEVICE,
            DeviceType.VIVINT_DEVICE
        )
    }

    /**
     * Check if device type is a consumer tracker (AirTag, Tile, SmartTag, etc.)
     */
    private fun isConsumerTracker(deviceType: DeviceType): Boolean {
        return deviceType in listOf(
            DeviceType.AIRTAG,
            DeviceType.TILE_TRACKER,
            DeviceType.SAMSUNG_SMARTTAG,
            DeviceType.GENERIC_BLE_TRACKER
        )
    }

    // ==================== TRACKER STALKING DETECTION ====================

    /**
     * Record a tracker sighting and update tracking history.
     * Called every time a consumer tracker is detected.
     */
    private fun recordTrackerSighting(context: BleDetectionContext, deviceType: DeviceType) {
        val now = System.currentTimeMillis()

        // Record first seen time if this is a new tracker
        trackerFirstSeen.computeIfAbsent(context.macAddress) { now }

        // Record RSSI history
        val rssiReadings = rssiHistory.computeIfAbsent(context.macAddress) { CopyOnWriteArrayList() }
        rssiReadings.add(RssiReading(now, context.rssi))

        // Prune old RSSI readings (keep last 24 hours)
        val cutoff = now - (TRACKER_ANALYSIS_WINDOW_HOURS * 60 * 60 * 1000L)
        rssiReadings.removeIf { it.timestamp < cutoff }

        // Record location sighting if location is available
        if (context.latitude != null && context.longitude != null) {
            val sightings = trackerSightings.computeIfAbsent(context.macAddress) { CopyOnWriteArrayList() }

            // Check if this is a new location
            val isNewLocation = isDistinctLocation(
                sightings,
                context.latitude,
                context.longitude,
                now
            )

            sightings.add(TrackerSighting(
                timestamp = now,
                latitude = context.latitude,
                longitude = context.longitude,
                rssi = context.rssi,
                isNewLocation = isNewLocation
            ))

            // Prune old sightings
            sightings.removeIf { it.timestamp < cutoff }
        }
    }

    /**
     * Check if a location is distinct from recent sightings.
     */
    private fun isDistinctLocation(
        sightings: List<TrackerSighting>,
        latitude: Double,
        longitude: Double,
        currentTime: Long
    ): Boolean {
        if (sightings.isEmpty()) return true

        // Find the most recent sighting
        val recentSighting = sightings.maxByOrNull { it.timestamp } ?: return true

        // Check time difference (must be at least MIN_TIME_BETWEEN_LOCATIONS_MINUTES apart)
        val timeDiffMinutes = (currentTime - recentSighting.timestamp) / (60 * 1000)
        if (timeDiffMinutes < MIN_TIME_BETWEEN_LOCATIONS_MINUTES) return false

        // Check distance
        if (recentSighting.latitude != null && recentSighting.longitude != null) {
            val distance = calculateDistance(
                latitude, longitude,
                recentSighting.latitude, recentSighting.longitude
            )
            return distance > SAME_LOCATION_RADIUS_METERS
        }

        return true
    }

    /**
     * Calculate distance between two coordinates in meters using Haversine formula.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Perform comprehensive analysis of a tracker to determine stalking likelihood.
     */
    private fun analyzeTracker(context: BleDetectionContext, deviceType: DeviceType): TrackerAnalysis {
        val now = System.currentTimeMillis()

        // Get sighting history
        val sightings = trackerSightings[context.macAddress] ?: emptyList()
        val rssiReadings = rssiHistory[context.macAddress] ?: emptyList()
        val firstSeen = trackerFirstSeen[context.macAddress] ?: now

        // Calculate distinct locations
        val distinctLocations = sightings.count { it.isNewLocation }

        // Calculate duration
        val durationMinutes = (now - firstSeen) / (60 * 1000)

        // Calculate RSSI statistics
        val recentRssiReadings = rssiReadings.filter {
            now - it.timestamp < 30 * 60 * 1000 // Last 30 minutes
        }
        val avgRssi = if (recentRssiReadings.isNotEmpty()) {
            recentRssiReadings.map { it.rssi }.average().toInt()
        } else {
            context.rssi
        }

        val rssiVariance = if (recentRssiReadings.size > 1) {
            val mean = recentRssiReadings.map { it.rssi }.average()
            recentRssiReadings.map { (it.rssi - mean) * (it.rssi - mean) }.average()
        } else {
            0.0
        }

        // Determine signal characteristics
        val isPossessionSignal = avgRssi > TRACKER_POSSESSION_RSSI && rssiVariance < LOW_RSSI_VARIANCE_THRESHOLD
        val isPassingBy = avgRssi < -75 || rssiVariance > 20.0

        // Calculate suspicion score and reasons
        val suspicionReasons = mutableListOf<String>()
        var suspicionScore = 0

        // Location-based scoring
        if (distinctLocations >= MIN_LOCATIONS_FOR_FOLLOWING) {
            suspicionScore += 40
            suspicionReasons.add("Detected at $distinctLocations distinct locations")
        } else if (distinctLocations >= 2) {
            suspicionScore += 20
            suspicionReasons.add("Seen at multiple locations")
        }

        // Duration-based scoring
        if (durationMinutes >= SUSPICIOUS_DURATION_MINUTES) {
            suspicionScore += 25
            suspicionReasons.add("Present for ${durationMinutes} minutes")
        } else if (durationMinutes >= 15) {
            suspicionScore += 10
            suspicionReasons.add("Present for extended period")
        }

        // Proximity-based scoring
        if (isPossessionSignal) {
            suspicionScore += 30
            suspicionReasons.add("Strong consistent signal (-${-avgRssi}dBm, low variance) - likely in your possession")
        } else if (avgRssi > -60) {
            suspicionScore += 15
            suspicionReasons.add("Strong signal indicating close proximity")
        }

        // Reduce score if likely passing by
        if (isPassingBy && distinctLocations < 2) {
            suspicionScore = (suspicionScore * 0.3).toInt()
            suspicionReasons.add("Weak/variable signal suggests passing traffic")
        }

        // Determine recommendation
        val recommendation = when {
            suspicionScore >= 80 -> TrackerRecommendation.CONTACT_AUTHORITIES
            suspicionScore >= 60 -> TrackerRecommendation.LOCATE_AND_DISABLE
            suspicionScore >= 40 -> TrackerRecommendation.INVESTIGATE
            suspicionScore >= 20 -> TrackerRecommendation.MONITOR
            else -> TrackerRecommendation.IGNORE
        }

        // Check if this could be user's own device (first seen at home, consistent presence)
        val likelyOwnerDevice = durationMinutes > 60 * 24 && // More than a day
                distinctLocations <= 2 && // Stayed at same locations
                suspicionScore < 30

        return TrackerAnalysis(
            macAddress = context.macAddress,
            deviceType = deviceType,
            distinctLocationsCount = distinctLocations,
            isFollowingUser = distinctLocations >= MIN_LOCATIONS_FOR_FOLLOWING,
            locationHistory = sightings.toList(),
            firstSeenTimestamp = firstSeen,
            durationMinutes = durationMinutes,
            isLongDuration = durationMinutes >= SUSPICIOUS_DURATION_MINUTES,
            averageRssi = avgRssi,
            rssiVariance = rssiVariance,
            isPossessionSignal = isPossessionSignal,
            isPassingBy = isPassingBy,
            suspicionScore = suspicionScore.coerceIn(0, 100),
            suspicionReasons = suspicionReasons,
            likelyOwnerDevice = likelyOwnerDevice,
            recommendation = recommendation
        )
    }

    /**
     * Build comprehensive LLM context for a tracker detection.
     * Enhanced with real-world tracker specifications and stalking response guidance.
     */
    private fun buildTrackerLlmContext(
        context: BleDetectionContext,
        deviceType: DeviceType,
        manufacturer: String,
        analysis: TrackerAnalysis
    ): TrackerLlmContext {
        val deviceInfo = DetectionPatterns.getDeviceTypeInfo(deviceType)

        // Get detailed tracker specification if available
        val trackerSpec = DetectionPatterns.trackerSpecifications[deviceType]

        // Build following analysis with enhanced context
        val followingAnalysis = when {
            analysis.isFollowingUser -> """
                CRITICAL WARNING: This tracker has been detected at ${analysis.distinctLocationsCount} distinct
                locations that you visited. This is a STRONG indicator of stalking.

                The tracker is moving WITH you, not staying at a fixed location. This pattern
                is consistent with someone deliberately tracking your movements.

                ${DetectionPatterns.StalkingResponseGuidance.getGuidanceForSuspicionLevel(analysis.suspicionScore)}
            """.trimIndent()
            analysis.isPossessionSignal -> """
                HIGH ALERT: This tracker has a strong, consistent signal (-${-analysis.averageRssi}dBm)
                with low variance, suggesting it is:
                - Hidden in your bag or backpack
                - In your vehicle
                - In a jacket pocket or clothing
                - On your person somewhere

                Common hiding spots to check:
                ${trackerSpec?.physicalCharacteristics?.commonHidingSpots?.take(5)?.joinToString("\n") { "- $it" }
                    ?: "- Bag pockets/lining\n- Car wheel wells\n- Jacket pockets\n- Phone case\n- Under car seats"}
            """.trimIndent()
            analysis.isPassingBy -> """
                This tracker appears to be passing by or belongs to someone nearby.
                The weak/variable signal suggests it's NOT specifically targeting you.

                However, keep the app running - if this same tracker appears again at
                a different location, the suspicion level will increase automatically.
            """.trimIndent()
            else -> """
                Monitoring this tracker. Insufficient data to determine if it's following you.
                Continue scanning at different locations to build tracking history.

                What to watch for:
                - Same tracker appearing at multiple locations you visit
                - Strong consistent signal while you're moving
                - Tracker appearing after you leave home
            """.trimIndent()
        }

        val locationSummary = if (analysis.locationHistory.isNotEmpty()) {
            "Seen at ${analysis.locationHistory.size} sightings across ${analysis.distinctLocationsCount} locations " +
                    "over ${analysis.durationMinutes} minutes"
        } else {
            "First sighting - no location history yet"
        }

        // Build device-specific confirmation methods
        val confirmationMethods = trackerSpec?.confirmationMethods ?: listOf(
            "Search your belongings and vehicle",
            "Use manufacturer's app to scan for devices",
            "Physical search of common hiding spots"
        )

        // Build immediate actions based on recommendation and tracker type
        val immediateActions = mutableListOf<String>()
        val preventiveActions = mutableListOf<String>()

        when (analysis.recommendation) {
            TrackerRecommendation.CONTACT_AUTHORITIES -> {
                immediateActions.addAll(DetectionPatterns.StalkingResponseGuidance.immediateActions)
                immediateActions.addAll(confirmationMethods.take(3))
                preventiveActions.addAll(listOf(
                    "Vary your daily routine - don't be predictable",
                    "Inform trusted friends/family about the situation",
                    "Consider professional security consultation",
                    "Have a safety check-in schedule with someone you trust"
                ))
            }
            TrackerRecommendation.LOCATE_AND_DISABLE -> {
                immediateActions.addAll(confirmationMethods)
                trackerSpec?.physicalCharacteristics?.commonHidingSpots?.forEach { spot ->
                    immediateActions.add("Check: $spot")
                }
                immediateActions.add("Once found, photograph it BEFORE removing the battery")
                preventiveActions.addAll(listOf(
                    "Consider using a Faraday bag if you can't find it",
                    "Continue monitoring for additional trackers",
                    "Note who has had access to your belongings recently"
                ))
            }
            TrackerRecommendation.INVESTIGATE -> {
                immediateActions.addAll(listOf(
                    "Check common hiding spots in your belongings",
                    "Pay attention to signal strength to locate it",
                    "Note if signal gets stronger in certain areas",
                    "Walk around with the app open to triangulate position"
                ))
                immediateActions.addAll(confirmationMethods.take(2))
            }
            TrackerRecommendation.MONITOR -> {
                immediateActions.addAll(listOf(
                    "Keep scanning at different locations",
                    "Note if this tracker appears again",
                    "The app will automatically increase suspicion if patterns emerge"
                ))
            }
            TrackerRecommendation.IGNORE -> {
                immediateActions.addAll(listOf(
                    "Likely your own tracker or someone passing by",
                    "No immediate action needed",
                    "App continues monitoring in background"
                ))
            }
        }

        // Build enhanced legal context with tracker-specific info
        val legalContext = buildEnhancedLegalContext(deviceType, trackerSpec)

        // Get anti-stalking features for this tracker
        val antiStalkingInfo = trackerSpec?.antiStalkingFeatures?.let { features ->
            buildString {
                appendLine("\n=== ANTI-STALKING FEATURES FOR THIS TRACKER ===")
                appendLine("Auto-alerts victim: ${if (features.alertsVictim) "YES" else "NO (higher risk)"}")
                appendLine("Alert platform: ${features.alertPlatform}")
                appendLine("Plays sound automatically: ${if (features.playsSoundAutomatically) "YES (after ${features.soundDelayHours}h)" else "NO"}")
                if (features.ownerInfoAccessible) {
                    appendLine("Owner info accessible: YES - ${features.ownerInfoMethod}")
                } else {
                    appendLine("Owner info accessible: NO - must involve law enforcement")
                }
            }
        } ?: ""

        // Build comprehensive capabilities list
        val capabilities = mutableListOf<String>()
        capabilities.addAll(deviceInfo.capabilities)
        trackerSpec?.let { spec ->
            spec.models.firstOrNull()?.let { model ->
                capabilities.add("Range: ${model.range}")
                capabilities.add("Sound: ${model.soundLevel}")
                if (model.hasUwb) capabilities.add("UWB precision finding capable")
                capabilities.add("Battery: ${model.batteryType} (~${model.batteryLife})")
            }
            capabilities.add("Network: ${spec.networkType.name.replace("_", " ")}")
            capabilities.add("Stalking risk: ${spec.stalkingRisk.description}")
        }

        return TrackerLlmContext(
            deviceDescription = deviceInfo.fullDescription + antiStalkingInfo,
            manufacturer = manufacturer,
            capabilities = capabilities,
            detectionReasons = analysis.suspicionReasons,
            behavioralIndicators = listOf(
                "Average signal: ${analysis.averageRssi}dBm (${if (analysis.averageRssi > -55) "VERY CLOSE" else if (analysis.averageRssi > -70) "Nearby" else "Moderate distance"})",
                "Signal variance: ${String.format("%.1f", analysis.rssiVariance)} (${if (analysis.rssiVariance < 10) "LOW - moving with you" else "Variable - may be passing"})",
                "Duration tracked: ${analysis.durationMinutes} minutes",
                "Distinct locations: ${analysis.distinctLocationsCount}",
                if (analysis.isPossessionSignal) "POSSESSION SIGNAL: Likely on your person" else "",
                if (analysis.isFollowingUser) "FOLLOWING PATTERN DETECTED" else ""
            ).filter { it.isNotEmpty() },
            dataCollectionCapabilities = listOf(
                "Your precise location history (via ${trackerSpec?.networkType?.name?.replace("_", " ") ?: "crowd-sourced"} network)",
                "Times and dates of all your movements",
                "Frequently visited locations (home, work, etc.)",
                "Movement patterns and daily routines",
                "How long you spend at each location"
            ),
            followingAnalysis = followingAnalysis,
            locationSummary = locationSummary,
            immediateActions = immediateActions,
            preventiveActions = preventiveActions,
            legalContext = legalContext,
            reportingOptions = listOf(
                "Local police (non-emergency line): Bring screenshots and documentation",
                "National Domestic Violence Hotline: 1-800-799-7233 (24/7)",
                "SPARC (Stalking Prevention): stalkingawareness.org",
                "Tech Safety (NNEDV): techsafety.org",
                trackerSpec?.let { "Manufacturer (${it.manufacturerName}): Can provide owner info to police with warrant" } ?: ""
            ).filter { it.isNotEmpty() }
        )
    }

    /**
     * Build enhanced legal context with tracker-specific information.
     */
    private fun buildEnhancedLegalContext(
        deviceType: DeviceType,
        trackerSpec: DetectionPatterns.TrackerSpecification?
    ): String {
        val baseContext = when (deviceType) {
            DeviceType.AIRTAG -> """
                APPLE AIRTAG LEGAL CONTEXT:
                - AirTags are legal to own and use for YOUR OWN property
                - Using an AirTag to track someone WITHOUT CONSENT is ILLEGAL in most jurisdictions
                - Violates: State stalking laws, federal wiretapping laws, harassment statutes
                - Many states have specific GPS tracking laws that apply
                - Apple cooperates with law enforcement to identify AirTag owners
                - NFC tap reveals partial phone number and serial (useful for police reports)

                Apple's anti-stalking features:
                - iPhone users get automatic "Unknown AirTag" alerts
                - AirTag plays sound 8-24 hours after separation from owner
                - Serial number traceable to owner's Apple ID
            """.trimIndent()
            DeviceType.TILE_TRACKER -> """
                TILE TRACKER LEGAL CONTEXT:
                - Tile trackers are legal for personal use
                - Tracking someone without consent may violate stalking/harassment laws
                - IMPORTANT: Tile has WEAKER anti-stalking features than AirTag
                - No automatic alerts to victims (must opt-in to "Scan and Secure")
                - No automatic sound playback for unknown trackers
                - Tile (owned by Life360) can provide owner info to police with warrant

                Higher stalking risk because:
                - Victims are not automatically notified
                - Must actively scan to detect unknown Tiles
            """.trimIndent()
            DeviceType.SAMSUNG_SMARTTAG -> """
                SAMSUNG SMARTTAG LEGAL CONTEXT:
                - SmartTags are legal for personal property tracking
                - Unauthorized tracking of individuals is illegal
                - Samsung Galaxy phones can detect unknown SmartTags
                - SmartTag+ has UWB for precision finding
                - Samsung can provide owner info to law enforcement with warrant

                Anti-stalking features (Galaxy phones only):
                - "Unknown Tag Detected" automatic alerts
                - Sound playback after separation
                - SmartThings app scanning capability
            """.trimIndent()
            else -> """
                BLUETOOTH TRACKER LEGAL CONTEXT:
                - Bluetooth trackers are legal for tracking personal property
                - Using them to track PEOPLE without consent is POTENTIALLY ILLEGAL
                - Laws vary by jurisdiction but commonly include:
                  * Stalking laws (criminal)
                  * Harassment statutes
                  * GPS/electronic tracking laws
                  * Privacy laws

                GENERIC/ALIEXPRESS TRACKERS:
                - Often have NO anti-stalking features
                - May not play sounds or alert victims
                - Harder to trace back to owner
                - Higher risk for stalking use
            """.trimIndent()
        }

        val additionalContext = """

            WHAT TO DO IF YOU'RE BEING STALKED:
            ${DetectionPatterns.StalkingResponseGuidance.whatNotToDo.joinToString("\n") { "- $it" }}

            SUPPORT RESOURCES:
            ${DetectionPatterns.StalkingResponseGuidance.supportResources.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }}
        """.trimIndent()

        return baseContext + additionalContext
    }

    /**
     * Calculate detection confidence based on context.
     */
    private fun calculateConfidence(context: BleDetectionContext, baseConfidence: Float): Float {
        var confidence = baseConfidence

        // Adjust for signal strength
        when {
            context.rssi > IMMEDIATE_PROXIMITY_RSSI -> confidence += 0.05f
            context.rssi > STRONG_SIGNAL_RSSI -> confidence += 0.02f
            context.rssi < -80 -> confidence -= 0.05f
        }

        // Adjust for multiple service UUIDs
        if (context.serviceUuids.size > 2) {
            confidence += 0.03f
        }

        // Adjust for device name presence
        if (context.deviceName != null) {
            confidence += 0.02f
        }

        return confidence.coerceIn(0f, 1f)
    }

    /**
     * Format raw BLE data for storage/display.
     */
    private fun formatRawBleData(context: BleDetectionContext): String {
        return buildString {
            appendLine("=== BLE Raw Data ===")
            appendLine("MAC: ${context.macAddress}")
            appendLine("Name: ${context.deviceName ?: "(none)"}")
            appendLine("RSSI: ${context.rssi} dBm")
            appendLine("Advertising Rate: ${String.format("%.1f", context.advertisingRate)} pps")
            appendLine()
            appendLine("Service UUIDs (${context.serviceUuids.size}):")
            context.serviceUuids.forEach { uuid ->
                appendLine("  $uuid")
            }
            appendLine()
            appendLine("Manufacturer Data (${context.manufacturerData.size}):")
            context.manufacturerData.forEach { (id, data) ->
                appendLine("  0x${"%04X".format(id)}: $data")
            }
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

    // ==================== AI PROMPT BUILDERS ====================

    /**
     * Build AI prompt for device name pattern match with enhanced LLM context.
     */
    private fun buildDeviceNamePrompt(
        context: BleDetectionContext,
        pattern: DetectionPattern
    ): String {
        val displayName = context.deviceName ?: pattern.deviceType.displayName
        val deviceInfo = DetectionPatterns.getDeviceTypeInfo(pattern.deviceType)

        return """BLE Device Detected: ${pattern.deviceType.displayName}

=== DEVICE IDENTIFICATION ===
Device Name: $displayName
MAC Address: ${context.macAddress}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Estimated Distance: ${rssiToDistance(context.rssi)}
Location: ${formatLocation(context)}

=== PATTERN MATCH ===
Matched Pattern: ${pattern.pattern}
Device Type: ${pattern.deviceType.displayName}
Manufacturer: ${pattern.manufacturer ?: "Unknown"}
Threat Score: ${pattern.threatScore}/100
Description: ${pattern.description}
${pattern.sourceUrl?.let { "Research Source: $it" } ?: ""}

=== WHAT IS THIS DEVICE? ===
${deviceInfo.fullDescription}

=== CAPABILITIES ===
${deviceInfo.capabilities.joinToString("\n") { "- $it" }}

=== PRIVACY CONCERNS ===
${deviceInfo.privacyConcerns.joinToString("\n") { "- $it" }}

=== SERVICE UUIDs (${context.serviceUuids.size}) ===
${context.serviceUuids.joinToString("\n") { "- $it" }.ifEmpty { "(none advertised)" }}

=== MANUFACTURER DATA ===
${context.manufacturerData.entries.joinToString("\n") { (id, data) ->
    "- Company ID 0x${"%04X".format(id)}: $data"
}.ifEmpty { "(none)" }}

=== BEHAVIORAL DATA ===
Advertising Rate: ${String.format("%.1f", context.advertisingRate)} packets/sec
${when {
    context.advertisingRate > 20f -> "WARNING: Very high advertising rate - possible activation event"
    context.advertisingRate > 10f -> "Elevated advertising rate - active communication"
    context.advertisingRate > 2f -> "Slightly elevated rate - device is active"
    else -> "Normal advertising rate"
}}

=== RECOMMENDED ACTIONS ===
${deviceInfo.recommendations.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" } ?: """
- Document this detection with timestamp and location
- Monitor for this device at other locations
- Check signal strength changes over time
- Consider the context (are you near expected surveillance?)"""}

---
Analyze this detection and provide:
1. Assessment of what this device is doing in this location
2. Privacy risk level based on proximity and device type
3. Additional context about this device category
4. Personalized recommendations for the user
"""
    }

    // ==================== UTILITY FUNCTIONS ====================

    /**
     * Format location for prompts.
     */
    private fun formatLocation(context: BleDetectionContext): String {
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
}

// ==================== DATA CLASSES ====================

/**
 * BLE detection context containing all relevant scan result data.
 *
 * This is the input data structure for [BleDetectionHandler.handleDetection].
 *
 * @property macAddress The device's MAC address
 * @property deviceName The advertised device name (may be null)
 * @property rssi Signal strength in dBm
 * @property serviceUuids List of advertised service UUIDs
 * @property manufacturerData Map of manufacturer ID to data bytes (hex string)
 * @property advertisingRate Current advertising packet rate (packets/second)
 * @property timestamp Detection timestamp
 * @property latitude Current latitude (if available)
 * @property longitude Current longitude (if available)
 */
data class BleDetectionContext(
    val macAddress: String,
    val deviceName: String?,
    val rssi: Int,
    val serviceUuids: List<UUID>,
    val manufacturerData: Map<Int, String>,
    val advertisingRate: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    /**
     * Get a display-safe device name, never returning null.
     * Uses device name if available, otherwise generates a meaningful identifier.
     */
    fun getDisplayName(deviceType: DeviceType? = null): String {
        return deviceName?.takeIf { it.isNotBlank() }
            ?: deviceType?.displayName
            ?: getManufacturerName()
            ?: "Unknown Device (${macAddress.takeLast(8)})"
    }

    /**
     * Derive manufacturer name from manufacturer data if available.
     */
    private fun getManufacturerName(): String? {
        return when {
            manufacturerData.containsKey(0x004C) -> "Apple Device"
            manufacturerData.containsKey(0x0075) -> "Samsung Device"
            manufacturerData.containsKey(0x00C7) -> "Tile Device"
            manufacturerData.containsKey(0x0059) -> "Nordic Device"
            manufacturerData.containsKey(0x0006) -> "Microsoft Device"
            manufacturerData.containsKey(0x00E0) -> "Google Device"
            else -> null
        }
    }
}

/**
 * BLE detection result containing the detection and AI prompt.
 *
 * @property detection The generated Detection object
 * @property aiPrompt Contextual prompt for AI analysis
 * @property confidence Detection confidence (0.0-1.0)
 */
data class BleDetectionResult(
    val detection: Detection,
    val aiPrompt: String,
    val confidence: Float
)

// ==================== TRACKER ANALYSIS DATA CLASSES ====================

/**
 * Records a tracker sighting at a specific location and time.
 * Used to detect if a tracker is following the user across locations.
 */
data class TrackerSighting(
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val rssi: Int,
    val isNewLocation: Boolean = false // True if this is a distinct location from previous sightings
)

/**
 * RSSI reading with timestamp for signal pattern analysis.
 */
data class RssiReading(
    val timestamp: Long,
    val rssi: Int
)

/**
 * Axon Signal activation event for correlation analysis.
 */
data class AxonActivationEvent(
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val advertisingRate: Float,
    val durationSeconds: Int = 0
)

/**
 * Analysis result for a consumer tracker (AirTag, Tile, SmartTag).
 * Contains all heuristics for determining if this is a stalking device.
 */
data class TrackerAnalysis(
    val macAddress: String,
    val deviceType: DeviceType,

    // Location-based analysis
    val distinctLocationsCount: Int,
    val isFollowingUser: Boolean,
    val locationHistory: List<TrackerSighting>,

    // Time-based analysis
    val firstSeenTimestamp: Long,
    val durationMinutes: Long,
    val isLongDuration: Boolean,

    // Proximity analysis
    val averageRssi: Int,
    val rssiVariance: Double,
    val isPossessionSignal: Boolean,  // Strong consistent signal = in bag/on person
    val isPassingBy: Boolean,         // Weak varying signal = someone else's tracker

    // Threat assessment
    val suspicionScore: Int,          // 0-100
    val suspicionReasons: List<String>,

    // Context
    val likelyOwnerDevice: Boolean,   // Could this be the user's own tracker?
    val recommendation: TrackerRecommendation
)

/**
 * Recommended action for a detected tracker.
 */
enum class TrackerRecommendation(val displayText: String, val urgency: Int) {
    IGNORE("Likely your own tracker or passing by", 0),
    MONITOR("Keep an eye on this tracker", 1),
    INVESTIGATE("Check your belongings and vehicle", 2),
    LOCATE_AND_DISABLE("Find and disable this tracker immediately", 3),
    CONTACT_AUTHORITIES("Consider contacting law enforcement", 4)
}

/**
 * Enhanced detection context with tracker analysis included.
 */
data class EnhancedTrackerDetection(
    val detection: Detection,
    val analysis: TrackerAnalysis,
    val llmContext: TrackerLlmContext
)

/**
 * Rich context for LLM analysis of tracker detections.
 */
data class TrackerLlmContext(
    // What is this device?
    val deviceDescription: String,
    val manufacturer: String,
    val capabilities: List<String>,

    // Why is it flagged?
    val detectionReasons: List<String>,
    val behavioralIndicators: List<String>,

    // What data can it collect?
    val dataCollectionCapabilities: List<String>,

    // Is it following you?
    val followingAnalysis: String,
    val locationSummary: String,

    // What should you do?
    val immediateActions: List<String>,
    val preventiveActions: List<String>,

    // Legal context
    val legalContext: String,
    val reportingOptions: List<String>
)

// ==================== BLE SPAM DETECTION DATA CLASSES ====================

/**
 * Types of BLE spam attacks that Flipper Zero can perform.
 */
enum class BleSpamType {
    /** iOS popup spam - fake Apple device advertisements causing pairing popups */
    APPLE_POPUP,
    /** Android Fast Pair spam - fake Fast Pair advertisements causing notifications */
    FAST_PAIR,
    /** Device impersonation - rapidly changing device names from single MAC */
    DEVICE_IMPERSONATION
}

/**
 * A single BLE spam event (advertisement) for tracking.
 */
data class BleSpamEvent(
    val timestamp: Long,
    val macAddress: String,
    val deviceName: String?,
    val manufacturerData: String
)

/**
 * Device name change event for impersonation detection.
 */
data class DeviceNameEvent(
    val timestamp: Long,
    val deviceName: String
)

/**
 * Analysis result for BLE spam attack detection.
 */
data class BleSpamAnalysis(
    val isLikelySpam: Boolean,
    val spamType: String,
    val totalEvents: Int,
    val uniqueSources: Int,
    val eventsPerSecond: Float,
    val suspicionReasons: List<String>,
    val threatScore: Int
)

// ==================== FLIPPER ZERO / HACKING TOOL DATA CLASSES ====================

/**
 * Flipper Zero firmware types for detection context.
 */
enum class FlipperFirmware(val displayName: String, val capabilities: String) {
    OFFICIAL("Official Firmware", "Standard features with regional restrictions"),
    UNLEASHED("Unleashed Firmware", "Removes Sub-GHz region locks"),
    ROGUEMASTER("RogueMaster Firmware", "Extended features, more aggressive capabilities"),
    XTREME("Xtreme Firmware", "Feature-packed custom firmware"),
    MOMENTUM("Momentum Firmware", "Community-driven custom firmware"),
    UNKNOWN("Unknown Firmware", "Could not determine firmware variant")
}

/**
 * Flipper Zero capability assessment.
 */
data class FlipperCapabilities(
    val subGhz: Boolean = true,       // 300-928 MHz (garage doors, key fobs)
    val rfid125Khz: Boolean = true,   // EM4100, HID Prox cards
    val nfc: Boolean = true,          // 13.56 MHz, Mifare, NTAG
    val infrared: Boolean = true,     // TV remotes, AC controls
    val iButton: Boolean = true,      // 1-Wire devices
    val gpio: Boolean = true,         // Hardware hacking
    val badUsb: Boolean = true,       // Keystroke injection
    val badBt: Boolean = true,        // Bluetooth keystroke injection
    val bleSpam: Boolean = true       // BLE advertisement spam
)

/**
 * Hacking tool threat context for LLM analysis.
 */
data class HackingToolContext(
    val deviceType: DeviceType,
    val manufacturer: String?,
    val firmwareVariant: FlipperFirmware?,
    val capabilities: List<String>,
    val threatAssessment: String,
    val locationContext: String,
    val recommendations: List<String>,
    val isActiveAttack: Boolean
)

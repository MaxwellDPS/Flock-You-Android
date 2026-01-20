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
        DeviceType.PIGVISION_SYSTEM
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
        Log.d(TAG, "BLE detection history cleared")
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
     * 1. Advertising rate spike (Axon Signal trigger activation)
     * 2. Raven service UUID matching
     * 3. BLE device name pattern matching
     * 4. BLE service UUID matching
     * 5. MAC prefix matching
     * 6. Consumer tracker detection (AirTag, Tile, SmartTag)
     *
     * @param context The BLE detection context
     * @return BleDetectionResult if a detection was made, null otherwise
     */
    fun handleDetection(context: BleDetectionContext): BleDetectionResult? {
        // Skip if below RSSI threshold
        if (context.rssi < config.rssiThreshold) {
            return null
        }

        // Rate limiting check
        val now = System.currentTimeMillis()
        val lastTime = lastDetectionTime[context.macAddress] ?: 0L
        if (now - lastTime < config.rateLimitMs) {
            return null
        }

        // Priority 1: Check for advertising rate spike (Axon Signal trigger)
        if (config.enablePoliceEquipmentDetection) {
            checkAdvertisingRateSpike(context)?.let { result ->
                lastDetectionTime[context.macAddress] = now
                return result
            }
        }

        // Priority 2: Check for Raven device by service UUIDs
        checkRavenDevice(context)?.let { result ->
            lastDetectionTime[context.macAddress] = now
            return result
        }

        // Priority 3: Check device name patterns
        context.deviceName?.let { name ->
            checkDeviceNamePattern(context, name)?.let { result ->
                lastDetectionTime[context.macAddress] = now
                return result
            }
        }

        // Priority 4: Check service UUID patterns
        checkServiceUuidPatterns(context)?.let { result ->
            lastDetectionTime[context.macAddress] = now
            return result
        }

        // Priority 5: Check MAC prefix
        checkMacPrefix(context)?.let { result ->
            lastDetectionTime[context.macAddress] = now
            return result
        }

        // Priority 6: Check for consumer trackers
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

        Log.w(TAG, "ADVERTISING RATE SPIKE: ${context.macAddress} " +
                "(${context.advertisingRate} pps) - possible Signal trigger activation")

        val manufacturer = if (isNordic) "Nordic Semiconductor (Axon)" else "Apple BLE Wrapper"

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.BLE_SERVICE_UUID,
            deviceType = DeviceType.AXON_POLICE_TECH,
            deviceName = context.deviceName ?: "Signal Trigger (Active)",
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = ThreatLevel.CRITICAL,
            threatScore = 95,
            manufacturer = manufacturer,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(listOf(
                "Advertising spike: ${context.advertisingRate.toInt()} packets/sec",
                "Possible siren/gun draw activation",
                "Nordic/Apple BLE chip detected"
            )),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildAxonSignalPrompt(context),
            confidence = calculateConfidence(context, 0.95f)
        )
    }

    /**
     * Check for Raven gunshot detector by service UUIDs.
     *
     * Raven devices advertise specific service UUIDs for GPS, power management,
     * network status, and diagnostics based on firmware version.
     */
    private fun checkRavenDevice(context: BleDetectionContext): BleDetectionResult? {
        if (!DetectionPatterns.isRavenDevice(context.serviceUuids)) {
            return null
        }

        val matchedServices = DetectionPatterns.matchRavenServices(context.serviceUuids)
        val firmwareVersion = DetectionPatterns.estimateRavenFirmwareVersion(context.serviceUuids)

        Log.w(TAG, "RAVEN DEVICE DETECTED: ${context.macAddress} - $firmwareVersion")

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.RAVEN_SERVICE_UUID,
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            deviceName = context.deviceName ?: "Raven Gunshot Detector",
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = ThreatLevel.CRITICAL,
            threatScore = 100,
            manufacturer = "SoundThinking/ShotSpotter",
            firmwareVersion = firmwareVersion,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(
                matchedServices.map { "${it.name}: ${it.description}" }
            ),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildRavenPrompt(context, matchedServices, firmwareVersion),
            confidence = calculateConfidence(context, 1.0f)
        )
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
     */
    private fun checkMacPrefix(context: BleDetectionContext): BleDetectionResult? {
        val macPrefix = DetectionPatterns.matchMacPrefix(context.macAddress) ?: return null

        // Skip police equipment if disabled
        if (!config.enablePoliceEquipmentDetection && isPoliceEquipment(macPrefix.deviceType)) {
            return null
        }

        Log.d(TAG, "MAC prefix match: ${context.macAddress} -> ${macPrefix.deviceType}")

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.MAC_PREFIX,
            deviceType = macPrefix.deviceType,
            deviceName = context.deviceName,
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = scoreToThreatLevel(macPrefix.threatScore),
            threatScore = macPrefix.threatScore,
            manufacturer = macPrefix.manufacturer,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(listOf(
                macPrefix.description.ifEmpty { "MAC prefix: ${macPrefix.prefix}" }
            )),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildMacPrefixPrompt(context, macPrefix),
            confidence = calculateConfidence(context, 0.70f)
        )
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

    // ==================== HELPER METHODS ====================

    /**
     * Create a tracker detection result.
     */
    private fun createTrackerDetection(
        context: BleDetectionContext,
        deviceType: DeviceType,
        detectionMethod: DetectionMethod,
        manufacturer: String,
        description: String,
        threatScore: Int
    ): BleDetectionResult {
        Log.d(TAG, "Tracker detected: $deviceType at ${context.macAddress}")

        val detection = Detection(
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = detectionMethod,
            deviceType = deviceType,
            deviceName = context.deviceName,
            macAddress = context.macAddress,
            rssi = context.rssi,
            signalStrength = rssiToSignalStrength(context.rssi),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = scoreToThreatLevel(threatScore),
            threatScore = threatScore,
            manufacturer = manufacturer,
            serviceUuids = context.serviceUuids.joinToString(",") { it.toString() },
            matchedPatterns = buildMatchedPatternsJson(listOf(description)),
            rawData = formatRawBleData(context)
        )

        return BleDetectionResult(
            detection = detection,
            aiPrompt = buildTrackerPrompt(context, deviceType, manufacturer),
            confidence = calculateConfidence(context, 0.75f)
        )
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
     * Build AI prompt for Axon Signal trigger detection.
     */
    private fun buildAxonSignalPrompt(context: BleDetectionContext): String {
        return """CRITICAL ALERT: Axon Signal Trigger Activation Detected

=== BLE Detection Data ===
MAC Address: ${context.macAddress}
Device Name: ${context.deviceName ?: "Unknown"}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Advertising Rate: ${context.advertisingRate.toInt()} packets/second (SPIKE DETECTED)
Location: ${formatLocation(context)}

=== Technical Details ===
Normal advertising rate: ~1 packet/second
Current rate: ${context.advertisingRate.toInt()} packets/second
Rate increase: ${(context.advertisingRate / NORMAL_ADVERTISING_RATE).toInt()}x normal

Manufacturer IDs: ${context.manufacturerData.keys.joinToString { "0x${"%04X".format(it)}" }}
Nordic Semiconductor chip detected: ${context.manufacturerData.containsKey(MANUFACTURER_ID_NORDIC)}

=== What This Means ===
Axon Signal devices broadcast at normal rates until an "activation event" occurs:
- Police siren activated
- Weapon drawn from holster
- Vehicle crash/rapid deceleration
- Manual activation by officer

When activated, the Signal device broadcasts rapidly to trigger nearby body cameras
to start recording automatically.

=== Privacy Implications ===
This detection indicates active police engagement in your immediate vicinity.
Body cameras within range are likely now recording.

Analyze this detection and provide:
1. Assessment of the immediate situation
2. Privacy implications for the user
3. Recommended actions
4. Possible false positive indicators"""
    }

    /**
     * Build AI prompt for Raven gunshot detector.
     */
    private fun buildRavenPrompt(
        context: BleDetectionContext,
        matchedServices: List<DetectionPatterns.RavenServiceInfo>,
        firmwareVersion: String
    ): String {
        val servicesSection = matchedServices.joinToString("\n") { service ->
            "- ${service.name}: ${service.description}\n  Data exposed: ${service.dataExposed}"
        }

        return """CRITICAL: Raven Acoustic Gunshot Detector Detected

=== BLE Detection Data ===
MAC Address: ${context.macAddress}
Device Name: ${context.deviceName ?: "Raven Device"}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Firmware Version: $firmwareVersion
Location: ${formatLocation(context)}

=== Matched Services (${matchedServices.size}) ===
$servicesSection

=== About Raven Devices ===
Raven is Flock Safety's acoustic surveillance system, similar to ShotSpotter.
These solar-powered devices continuously monitor audio and use AI to detect:
- Gunfire (primary purpose)
- "Human distress" - screaming, shouting (announced October 2025)

=== Privacy Concerns ===
- Continuous audio surveillance in public spaces
- "Human distress" detection scope is intentionally vague
- Audio recordings may capture private conversations
- Data shared with law enforcement without warrant
- No consent from recorded individuals

=== BLE Vulnerability ===
Based on GainSec research, Raven devices expose sensitive data via BLE:
- GPS location of the device
- Battery/solar power status
- Cellular connectivity info
- Upload statistics and detection counts
- Diagnostic/error information

Analyze this detection and provide:
1. Assessment of the surveillance implications
2. Privacy risk level based on proximity
3. Information about what data the Raven might collect
4. Recommended actions for the user"""
    }

    /**
     * Build AI prompt for device name pattern match.
     */
    private fun buildDeviceNamePrompt(
        context: BleDetectionContext,
        pattern: DetectionPattern
    ): String {
        return """BLE Device Detected: ${pattern.deviceType.displayName}

=== Detection Data ===
Device Name: ${context.deviceName}
MAC Address: ${context.macAddress}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Location: ${formatLocation(context)}

=== Pattern Match ===
Matched Pattern: ${pattern.pattern}
Device Type: ${pattern.deviceType.displayName}
Manufacturer: ${pattern.manufacturer ?: "Unknown"}
Threat Score: ${pattern.threatScore}/100
Description: ${pattern.description}
${pattern.sourceUrl?.let { "Source: $it" } ?: ""}

=== Service UUIDs (${context.serviceUuids.size}) ===
${context.serviceUuids.joinToString("\n") { "- $it" }.ifEmpty { "(none)" }}

Analyze this detection and provide:
1. What this device does and its capabilities
2. What data it may collect about the user
3. Privacy risk assessment
4. Recommended actions"""
    }

    /**
     * Build AI prompt for MAC prefix match.
     */
    private fun buildMacPrefixPrompt(
        context: BleDetectionContext,
        macPrefix: DetectionPatterns.MacPrefix
    ): String {
        return """BLE Device Detected via MAC Prefix: ${macPrefix.deviceType.displayName}

=== Detection Data ===
MAC Address: ${context.macAddress}
MAC Prefix: ${macPrefix.prefix}
Device Name: ${context.deviceName ?: "(not advertised)"}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Location: ${formatLocation(context)}

=== Manufacturer Identification ===
OUI Manufacturer: ${macPrefix.manufacturer}
Associated Device Type: ${macPrefix.deviceType.displayName}
Threat Score: ${macPrefix.threatScore}/100
Description: ${macPrefix.description}

=== Additional Data ===
Service UUIDs: ${context.serviceUuids.size}
Manufacturer Data Entries: ${context.manufacturerData.size}
Advertising Rate: ${String.format("%.1f", context.advertisingRate)} packets/sec

Note: MAC prefix matching has lower confidence than name/UUID matching.
The device may be legitimate equipment from this manufacturer.

Analyze this detection and provide:
1. Assessment of whether this is likely surveillance equipment
2. What this type of device typically does
3. Privacy implications if it is surveillance
4. Confidence assessment and false positive likelihood"""
    }

    /**
     * Build AI prompt for consumer tracker detection.
     */
    private fun buildTrackerPrompt(
        context: BleDetectionContext,
        deviceType: DeviceType,
        manufacturer: String
    ): String {
        val trackerInfo = when (deviceType) {
            DeviceType.AIRTAG -> """
Apple AirTag is a small tracking device that uses the Find My network.
- Uses crowd-sourced location via other Apple devices
- Can be used to track belongings, pets, or people
- Has anti-stalking features (plays sound after separation)
- iOS will alert iPhone users to unknown AirTags traveling with them"""
            DeviceType.TILE_TRACKER -> """
Tile is a Bluetooth tracker with its own network of users.
- Uses Tile app users to help locate lost items
- Various form factors (Mate, Pro, Slim, Sticker)
- Subscription tiers with different features
- Fewer anti-stalking features than AirTag"""
            DeviceType.SAMSUNG_SMARTTAG -> """
Samsung SmartTag uses the SmartThings Find network.
- Works with Samsung Galaxy devices
- Two versions: SmartTag and SmartTag+ (with UWB)
- Galaxy phones can detect unknown SmartTags"""
            else -> "Generic Bluetooth tracker device."
        }

        return """Bluetooth Tracker Detected: ${deviceType.displayName}

=== Detection Data ===
Device Type: ${deviceType.displayName}
Manufacturer: $manufacturer
MAC Address: ${context.macAddress}
Device Name: ${context.deviceName ?: "(hidden)"}
Signal Strength: ${context.rssi} dBm (${rssiToSignalStrength(context.rssi).displayName})
Location: ${formatLocation(context)}

=== About This Tracker ===
$trackerInfo

=== Privacy Assessment ===
Bluetooth trackers can be used for legitimate purposes (finding keys, luggage)
but can also be misused for stalking or unauthorized tracking.

Key questions to consider:
- Is this tracker yours or someone you know?
- Has it been following you to multiple locations?
- Is it hidden in your belongings or vehicle?

=== What To Do ===
If you believe this is an unknown tracker following you:
1. Try to locate the physical device
2. Most trackers can be disabled by removing the battery
3. Document the device for potential police report
4. Use the manufacturer's app to identify the owner (if possible)

Analyze this detection and provide:
1. Assessment of tracking risk
2. Steps to determine if this tracker belongs to the user
3. Actions if this appears to be unauthorized tracking
4. Information about anti-stalking features"""
    }

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
)

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

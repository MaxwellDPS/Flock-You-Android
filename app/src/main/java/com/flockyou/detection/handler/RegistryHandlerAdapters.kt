package com.flockyou.detection.handler

import android.content.Context
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry-compatible adapter handlers.
 *
 * These adapters provide minimal implementations for the DetectionRegistry.
 * The actual detection logic is handled by ScanningService and specialized handlers.
 */

/**
 * Registry adapter for BLE detection.
 */
@Singleton
class BleRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : BaseDetectionHandler<DetectionContext.BluetoothLe>() {

    override val protocol: DetectionProtocol = DetectionProtocol.BLUETOOTH_LE

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.AIRTAG,
        DeviceType.TILE_TRACKER,
        DeviceType.SAMSUNG_SMARTTAG,
        DeviceType.GENERIC_BLE_TRACKER,
        DeviceType.RING_DOORBELL,
        DeviceType.NEST_CAMERA,
        DeviceType.AMAZON_SIDEWALK,
        DeviceType.FLOCK_SAFETY_CAMERA,
        DeviceType.RAVEN_GUNSHOT_DETECTOR,
        DeviceType.BODY_CAMERA,
        DeviceType.AXON_POLICE_TECH,
        DeviceType.MOTOROLA_POLICE_TECH
    )

    override val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.BLE_DEVICE_NAME,
        DetectionMethod.BLE_SERVICE_UUID,
        DetectionMethod.MAC_PREFIX,
        DetectionMethod.RAVEN_SERVICE_UUID
    )

    override val displayName: String = "BLE Detection Handler"

    init {
        registerProfiles(
            DeviceTypeProfile(
                deviceType = DeviceType.AIRTAG,
                manufacturer = "Apple",
                description = "Apple AirTag location tracker",
                capabilities = listOf("Find My network", "UWB precision finding", "Separation alerts"),
                typicalThreatLevel = ThreatLevel.MEDIUM
            ),
            DeviceTypeProfile(
                deviceType = DeviceType.TILE_TRACKER,
                manufacturer = "Tile Inc.",
                description = "Tile Bluetooth tracker",
                capabilities = listOf("Community finding", "Range alerts"),
                typicalThreatLevel = ThreatLevel.MEDIUM
            ),
            DeviceTypeProfile(
                deviceType = DeviceType.SAMSUNG_SMARTTAG,
                manufacturer = "Samsung",
                description = "Samsung SmartTag/SmartTag+ tracker",
                capabilities = listOf("SmartThings Find", "UWB (Plus model)", "Lost mode"),
                typicalThreatLevel = ThreatLevel.MEDIUM
            )
        )
    }

    override fun analyze(context: DetectionContext.BluetoothLe): DetectionResult? {
        // BLE analysis is handled by ScanningService
        return null
    }

    override fun matchesPattern(scanResult: Any): PatternMatch? {
        // Pattern matching is delegated to DetectionPatterns
        return null
    }
}

/**
 * Registry adapter for WiFi detection.
 *
 * This adapter wraps [WifiDetectionHandler] to provide the unified
 * [DetectionHandler] interface for the DetectionRegistry.
 */
@Singleton
class WifiRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDetectionHandler: WifiDetectionHandler
) : BaseDetectionHandler<DetectionContext.WiFi>() {

    override val protocol: DetectionProtocol = DetectionProtocol.WIFI

    override val supportedDeviceTypes: Set<DeviceType> = wifiDetectionHandler.supportedDeviceTypes

    override val supportedMethods: Set<DetectionMethod> = wifiDetectionHandler.supportedMethods

    override val displayName: String = wifiDetectionHandler.displayName

    init {
        // Register device profiles for WiFi surveillance devices
        registerProfiles(
            DeviceTypeProfile(
                deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
                manufacturer = "Flock Safety",
                description = "Automated License Plate Reader (ALPR) camera",
                capabilities = listOf("License plate capture", "Vehicle identification", "24/7 recording", "Network sharing"),
                typicalThreatLevel = ThreatLevel.CRITICAL
            ),
            DeviceTypeProfile(
                deviceType = DeviceType.ROGUE_AP,
                manufacturer = "Unknown",
                description = "Potentially malicious access point",
                capabilities = listOf("Traffic interception", "Credential capture", "Man-in-the-middle"),
                typicalThreatLevel = ThreatLevel.HIGH
            ),
            DeviceTypeProfile(
                deviceType = DeviceType.HIDDEN_CAMERA,
                manufacturer = "Unknown",
                description = "Covert surveillance camera",
                capabilities = listOf("Video recording", "WiFi streaming", "Remote access"),
                typicalThreatLevel = ThreatLevel.HIGH
            ),
            DeviceTypeProfile(
                deviceType = DeviceType.SURVEILLANCE_VAN,
                manufacturer = "Unknown",
                description = "Mobile surveillance vehicle hotspot",
                capabilities = listOf("Mobile monitoring", "Electronic surveillance", "Data collection"),
                typicalThreatLevel = ThreatLevel.CRITICAL
            )
        )
    }

    /**
     * Get the underlying WifiDetectionHandler for direct access.
     */
    fun getHandler(): WifiDetectionHandler = wifiDetectionHandler

    override fun analyze(context: DetectionContext.WiFi): DetectionResult? {
        // Convert generic DetectionContext.WiFi to WifiDetectionContext
        val wifiContext = WifiDetectionContext(
            ssid = context.ssid,
            bssid = context.bssid,
            rssi = context.rssi,
            frequency = context.frequency,
            channel = context.channel,
            capabilities = context.capabilities,
            isHidden = context.isHidden,
            timestamp = context.timestamp,
            latitude = context.latitude,
            longitude = context.longitude
        )

        // Process through the handler
        val result = wifiDetectionHandler.handlePatternMatching(wifiContext)

        return result?.let { wifiResult ->
            DetectionResult(
                detection = wifiResult.detection,
                confidence = wifiResult.confidence,
                rawScore = wifiResult.detection.threatScore,
                metadata = mapOf("aiPrompt" to wifiResult.aiPrompt),
                matchedPatterns = emptyList(), // Patterns are in the detection
                analysisTimeMs = 0,
                handlerId = handlerId
            )
        }
    }

    override fun matchesPattern(scanResult: Any): PatternMatch? {
        // Pattern matching is delegated to DetectionPatterns via WifiDetectionHandler
        return null
    }

    override fun startMonitoring() {
        wifiDetectionHandler.startMonitoring()
    }

    override fun stopMonitoring() {
        wifiDetectionHandler.stopMonitoring()
    }

    override fun updateLocation(latitude: Double, longitude: Double) {
        wifiDetectionHandler.updateLocation(latitude, longitude)
    }

    override fun destroy() {
        wifiDetectionHandler.destroy()
    }
}

/**
 * Registry adapter for Cellular detection.
 */
@Singleton
class CellularRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cellularDetectionHandler: CellularDetectionHandler
) : BaseDetectionHandler<DetectionContext.Cellular>() {

    override val protocol: DetectionProtocol = DetectionProtocol.CELLULAR

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.STINGRAY_IMSI,
        DeviceType.MOTOROLA_POLICE_TECH,
        DeviceType.L3HARRIS_SURVEILLANCE,
        DeviceType.CELLEBRITE_FORENSICS,
        DeviceType.GRAYKEY_DEVICE
    )

    override val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.CELL_ENCRYPTION_DOWNGRADE,
        DetectionMethod.CELL_SUSPICIOUS_NETWORK,
        DetectionMethod.CELL_TOWER_CHANGE,
        DetectionMethod.CELL_RAPID_SWITCHING,
        DetectionMethod.CELL_SIGNAL_ANOMALY
    )

    override val displayName: String = "Cellular Detection Handler"

    fun getHandler(): CellularDetectionHandler = cellularDetectionHandler

    override fun analyze(context: DetectionContext.Cellular): DetectionResult? {
        return null
    }

    override fun matchesPattern(scanResult: Any): PatternMatch? {
        return null
    }
}

/**
 * Registry adapter for Satellite/NTN detection.
 */
@Singleton
class SatelliteRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val satelliteDetectionHandler: SatelliteDetectionHandler
) : BaseDetectionHandler<DetectionContext.Satellite>() {

    override val protocol: DetectionProtocol = DetectionProtocol.SATELLITE

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.SATELLITE_NTN,
        DeviceType.STINGRAY_IMSI
    )

    override val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.SAT_UNEXPECTED_CONNECTION,
        DetectionMethod.SAT_FORCED_HANDOFF,
        DetectionMethod.SAT_SUSPICIOUS_NTN,
        DetectionMethod.SAT_TIMING_ANOMALY
    )

    override val displayName: String = "Satellite Detection Handler"

    fun getHandler(): SatelliteDetectionHandler = satelliteDetectionHandler

    override fun analyze(context: DetectionContext.Satellite): DetectionResult? {
        return null
    }

    override fun matchesPattern(scanResult: Any): PatternMatch? {
        return null
    }
}

/**
 * Registry adapter for Ultrasonic detection.
 */
@Singleton
class UltrasonicRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ultrasonicDetectionHandler: UltrasonicDetectionHandler
) : BaseDetectionHandler<DetectionContext.Audio>() {

    override val protocol: DetectionProtocol = DetectionProtocol.AUDIO

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.ULTRASONIC_BEACON
    )

    override val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.ULTRASONIC_TRACKING_BEACON,
        DetectionMethod.ULTRASONIC_AD_BEACON,
        DetectionMethod.ULTRASONIC_RETAIL_BEACON,
        DetectionMethod.ULTRASONIC_CROSS_DEVICE
    )

    override val displayName: String = "Ultrasonic Detection Handler"

    fun getHandler(): UltrasonicDetectionHandler = ultrasonicDetectionHandler

    override fun analyze(context: DetectionContext.Audio): DetectionResult? {
        return null
    }

    override fun matchesPattern(scanResult: Any): PatternMatch? {
        return null
    }
}

/**
 * Registry adapter for GNSS spoofing/jamming detection.
 */
@Singleton
class GnssRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gnssDetectionHandler: GnssDetectionHandler
) : BaseDetectionHandler<DetectionContext.Gnss>() {

    override val protocol: DetectionProtocol = DetectionProtocol.GNSS

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.GNSS_SPOOFER,
        DeviceType.GNSS_JAMMER
    )

    override val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.GNSS_SPOOFING,
        DetectionMethod.GNSS_JAMMING,
        DetectionMethod.GNSS_SIGNAL_ANOMALY,
        DetectionMethod.GNSS_GEOMETRY_ANOMALY
    )

    override val displayName: String = "GNSS Detection Handler"

    fun getHandler(): GnssDetectionHandler = gnssDetectionHandler

    override fun analyze(context: DetectionContext.Gnss): DetectionResult? {
        return null
    }

    override fun matchesPattern(scanResult: Any): PatternMatch? {
        return null
    }
}

/**
 * Registry adapter for RF signal analysis.
 */
@Singleton
class RfRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rfDetectionHandler: RfDetectionHandler
) : BaseDetectionHandler<DetectionContext.RfSpectrum>() {

    override val protocol: DetectionProtocol = DetectionProtocol.RF

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.RF_JAMMER,
        DeviceType.DRONE,
        DeviceType.SURVEILLANCE_INFRASTRUCTURE,
        DeviceType.RF_INTERFERENCE,
        DeviceType.RF_ANOMALY,
        DeviceType.HIDDEN_TRANSMITTER
    )

    override val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.RF_JAMMER,
        DetectionMethod.RF_DRONE,
        DetectionMethod.RF_SURVEILLANCE_AREA,
        DetectionMethod.RF_SPECTRUM_ANOMALY,
        DetectionMethod.RF_HIDDEN_TRANSMITTER
    )

    override val displayName: String = "RF Detection Handler"

    fun getHandler(): RfDetectionHandler = rfDetectionHandler

    override fun analyze(context: DetectionContext.RfSpectrum): DetectionResult? {
        return null
    }

    override fun matchesPattern(scanResult: Any): PatternMatch? {
        return null
    }
}

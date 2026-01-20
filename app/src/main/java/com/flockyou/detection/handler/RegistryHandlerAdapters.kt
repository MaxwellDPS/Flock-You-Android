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
 * These adapters wrap the existing detection implementations to conform
 * to the unified DetectionHandler interface used by DetectionRegistry.
 * They extend BaseDetectionHandler for common functionality.
 */

/**
 * Registry adapter for BLE detection.
 * Wraps BLE scanning functionality for the registry.
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
        DeviceType.WYZE_CAMERA,
        DeviceType.ARLO_CAMERA,
        DeviceType.EUFY_CAMERA,
        DeviceType.BLINK_CAMERA,
        DeviceType.SIMPLISAFE_DEVICE,
        DeviceType.ADT_DEVICE,
        DeviceType.VIVINT_DEVICE,
        DeviceType.BLUETOOTH_BEACON,
        DeviceType.RETAIL_TRACKER,
        DeviceType.FLOCK_SAFETY_CAMERA,
        DeviceType.RAVEN_GUNSHOT_DETECTOR,
        DeviceType.BODY_CAMERA,
        DeviceType.POLICE_VEHICLE,
        DeviceType.FLEET_VEHICLE
    )

    override val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.BLE_TRACKER_PROFILE,
        DetectionMethod.BLE_MANUFACTURER_MATCH,
        DetectionMethod.BLE_NAME_PATTERN
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
        // BLE analysis is primarily handled by ScanningService
        // This provides the interface for the registry
        return null
    }

    override fun matchesPattern(scanResult: Any): PatternMatch? {
        // Pattern matching is delegated to the tracker database
        return null
    }
}

/**
 * Registry adapter for WiFi detection.
 * Wraps RogueWifiMonitor for the registry.
 */
@Singleton
class WifiRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : DetectionHandler<Any> {

    override val protocol: DetectionProtocol = DetectionProtocol.WIFI

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.ROGUE_AP,
        DeviceType.HIDDEN_CAMERA,
        DeviceType.SURVEILLANCE_VAN,
        DeviceType.TRACKING_DEVICE,
        DeviceType.WIFI_PINEAPPLE,
        DeviceType.PACKET_SNIFFER,
        DeviceType.MAN_IN_MIDDLE
    )

    override val displayName: String = "WiFi Detection Handler"

    private var _isActive: Boolean = false
    override val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    override val detections: Flow<Detection> = _detections.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var rogueWifiMonitor: RogueWifiMonitor? = null

    override fun startMonitoring() {
        if (_isActive) return
        _isActive = true

        rogueWifiMonitor = RogueWifiMonitor(context).apply {
            startMonitoring()
        }

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
    }

    override fun stopMonitoring() {
        _isActive = false
        rogueWifiMonitor?.stopMonitoring()
    }

    override fun updateLocation(latitude: Double, longitude: Double) {
        rogueWifiMonitor?.updateLocation(latitude, longitude)
    }

    override fun clearHistory() {
        rogueWifiMonitor?.clearHistory()
    }

    override fun destroy() {
        stopMonitoring()
        rogueWifiMonitor?.destroy()
        rogueWifiMonitor = null
    }

    fun getMonitor(): RogueWifiMonitor? = rogueWifiMonitor
}

/**
 * Registry adapter for Cellular detection.
 * Wraps CellularMonitor and CellularDetectionHandler for the registry.
 */
@Singleton
class CellularRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cellularDetectionHandler: CellularDetectionHandler
) : DetectionHandler<Any> {

    override val protocol: DetectionProtocol = DetectionProtocol.CELLULAR

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.STINGRAY_IMSI,
        DeviceType.MOTOROLA_POLICE_TECH,
        DeviceType.L3HARRIS_SURVEILLANCE,
        DeviceType.CELLEBRITE_FORENSICS,
        DeviceType.GRAYKEY_DEVICE
    )

    override val displayName: String = "Cellular Detection Handler"

    private var _isActive: Boolean = false
    override val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    override val detections: Flow<Detection> = _detections.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cellularMonitor: CellularMonitor? = null

    override fun startMonitoring() {
        if (_isActive) return
        _isActive = true

        cellularMonitor = CellularMonitor(context).apply {
            startMonitoring()
        }

        scope.launch {
            cellularMonitor?.anomalies?.collect { anomalies ->
                anomalies.forEach { anomaly ->
                    val detection = cellularDetectionHandler.convertAnomalyToDetection(anomaly)
                    if (detection != null) {
                        _detections.emit(detection)
                    }
                }
            }
        }
    }

    override fun stopMonitoring() {
        _isActive = false
        cellularMonitor?.stopMonitoring()
    }

    override fun updateLocation(latitude: Double, longitude: Double) {
        cellularMonitor?.updateLocation(latitude, longitude)
    }

    override fun clearHistory() {
        cellularMonitor?.clearHistory()
    }

    override fun destroy() {
        stopMonitoring()
        cellularMonitor?.destroy()
        cellularMonitor = null
    }

    fun getMonitor(): CellularMonitor? = cellularMonitor
    fun getHandler(): CellularDetectionHandler = cellularDetectionHandler
}

/**
 * Registry adapter for Satellite/NTN detection.
 * Wraps SatelliteMonitor and SatelliteDetectionHandler for the registry.
 */
@Singleton
class SatelliteRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val satelliteDetectionHandler: SatelliteDetectionHandler
) : DetectionHandler<Any> {

    override val protocol: DetectionProtocol = DetectionProtocol.SATELLITE

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.SATELLITE_NTN,
        DeviceType.SATELLITE_ANOMALY
    )

    override val displayName: String = "Satellite Detection Handler"

    private var _isActive: Boolean = false
    override val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    override val detections: Flow<Detection> = _detections.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var satelliteMonitor: SatelliteMonitor? = null

    override fun startMonitoring() {
        if (_isActive) return
        _isActive = true

        satelliteMonitor = SatelliteMonitor(context).apply {
            startMonitoring()
        }

        scope.launch {
            satelliteMonitor?.anomalies?.collect { anomaly ->
                val detection = satelliteMonitor?.anomalyToDetection(anomaly)
                if (detection != null) {
                    _detections.emit(detection)
                }
            }
        }
    }

    override fun stopMonitoring() {
        _isActive = false
        satelliteMonitor?.stopMonitoring()
    }

    override fun destroy() {
        stopMonitoring()
        satelliteMonitor = null
    }

    fun getMonitor(): SatelliteMonitor? = satelliteMonitor
    fun getHandler(): SatelliteDetectionHandler = satelliteDetectionHandler
}

/**
 * Registry adapter for Ultrasonic detection.
 * Wraps UltrasonicDetector and UltrasonicDetectionHandler for the registry.
 */
@Singleton
class UltrasonicRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ultrasonicDetectionHandler: UltrasonicDetectionHandler
) : DetectionHandler<Any> {

    override val protocol: DetectionProtocol = DetectionProtocol.ULTRASONIC

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.ULTRASONIC_BEACON,
        DeviceType.AD_TRACKING_BEACON,
        DeviceType.RETAIL_BEACON
    )

    override val displayName: String = "Ultrasonic Detection Handler"

    private var _isActive: Boolean = false
    override val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    override val detections: Flow<Detection> = _detections.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var ultrasonicDetector: UltrasonicDetector? = null

    override fun startMonitoring() {
        if (_isActive) return
        _isActive = true

        ultrasonicDetector = UltrasonicDetector(context).apply {
            startListening()
        }

        scope.launch {
            ultrasonicDetector?.detectedBeacons?.collect { beacons ->
                beacons.forEach { beacon ->
                    val context = UltrasonicDetectionHandler.UltrasonicDetectionContext(
                        frequencyHz = beacon.frequencyHz.toInt(),
                        amplitudeDb = beacon.amplitudeDb,
                        modulationType = beacon.modulationType,
                        detectedLocations = 1,
                        persistenceScore = beacon.persistenceScore,
                        isFollowing = beacon.isFollowing
                    )
                    val detection = ultrasonicDetectionHandler.handleDetection(context)
                    if (detection != null) {
                        _detections.emit(detection)
                    }
                }
            }
        }
    }

    override fun stopMonitoring() {
        _isActive = false
        ultrasonicDetector?.stopListening()
    }

    override fun destroy() {
        stopMonitoring()
        ultrasonicDetector?.destroy()
        ultrasonicDetector = null
    }

    fun getDetector(): UltrasonicDetector? = ultrasonicDetector
    fun getHandler(): UltrasonicDetectionHandler = ultrasonicDetectionHandler
}

/**
 * Registry adapter for GNSS spoofing/jamming detection.
 * Wraps GnssSatelliteMonitor and GnssDetectionHandler for the registry.
 */
@Singleton
class GnssRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gnssDetectionHandler: GnssDetectionHandler
) : DetectionHandler<Any> {

    override val protocol: DetectionProtocol = DetectionProtocol.GNSS

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.GNSS_SPOOFER,
        DeviceType.GNSS_JAMMER
    )

    override val displayName: String = "GNSS Detection Handler"

    private var _isActive: Boolean = false
    override val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    override val detections: Flow<Detection> = _detections.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var gnssMonitor: GnssSatelliteMonitor? = null

    override fun startMonitoring() {
        if (_isActive) return
        _isActive = true

        gnssMonitor = GnssSatelliteMonitor(context).apply {
            startMonitoring()
        }

        scope.launch {
            gnssMonitor?.anomalies?.collect { anomalies ->
                anomalies.forEach { anomaly ->
                    val detectionContext = GnssDetectionContext(
                        anomaly = anomaly,
                        spoofingLikelihood = gnssMonitor?.getSpoofingLikelihood() ?: 0f,
                        jammingLikelihood = gnssMonitor?.getJammingLikelihood() ?: 0f,
                        satelliteCount = gnssMonitor?.satellites?.value?.size ?: 0,
                        timestamp = System.currentTimeMillis()
                    )
                    val detections = gnssDetectionHandler.handle(detectionContext)
                    detections.forEach { detection ->
                        _detections.emit(detection)
                    }
                }
            }
        }
    }

    override fun stopMonitoring() {
        _isActive = false
        gnssMonitor?.stopMonitoring()
    }

    override fun destroy() {
        stopMonitoring()
        gnssMonitor = null
    }

    fun getMonitor(): GnssSatelliteMonitor? = gnssMonitor
    fun getHandler(): GnssDetectionHandler = gnssDetectionHandler
}

/**
 * Registry adapter for RF signal analysis.
 * Wraps RfSignalAnalyzer and RfDetectionHandler for the registry.
 */
@Singleton
class RfRegistryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rfDetectionHandler: RfDetectionHandler
) : DetectionHandler<Any> {

    override val protocol: DetectionProtocol = DetectionProtocol.RF

    override val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.RF_JAMMER,
        DeviceType.DRONE,
        DeviceType.SURVEILLANCE_INFRASTRUCTURE,
        DeviceType.RF_INTERFERENCE,
        DeviceType.RF_ANOMALY,
        DeviceType.HIDDEN_TRANSMITTER
    )

    override val displayName: String = "RF Detection Handler"

    private var _isActive: Boolean = false
    override val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    override val detections: Flow<Detection> = _detections.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var rfAnalyzer: RfSignalAnalyzer? = null

    override fun startMonitoring() {
        if (_isActive) return
        _isActive = true

        rfAnalyzer = RfSignalAnalyzer(context).apply {
            startAnalysis()
        }

        scope.launch {
            rfAnalyzer?.anomalies?.collect { anomalies ->
                anomalies.forEach { anomaly ->
                    val rfContext = RfDetectionContext(
                        totalNetworks = rfAnalyzer?.getTotalNetworkCount() ?: 0,
                        hiddenNetworkCount = rfAnalyzer?.getHiddenNetworkCount() ?: 0,
                        avgSignalStrength = rfAnalyzer?.getAverageSignalStrength() ?: -80,
                        signalVariance = rfAnalyzer?.getSignalVariance() ?: 0f,
                        anomalies = listOf(anomaly)
                    )
                    val detections = rfDetectionHandler.handle(rfContext)
                    detections.forEach { detection ->
                        _detections.emit(detection)
                    }
                }
            }
        }
    }

    override fun stopMonitoring() {
        _isActive = false
        rfAnalyzer?.stopAnalysis()
    }

    override fun destroy() {
        stopMonitoring()
        rfAnalyzer = null
    }

    fun getAnalyzer(): RfSignalAnalyzer? = rfAnalyzer
    fun getHandler(): RfDetectionHandler = rfDetectionHandler
}

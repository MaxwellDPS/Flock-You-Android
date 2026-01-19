package com.flockyou.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.provider.Settings
import com.flockyou.BuildConfig
import com.flockyou.MainActivity
import com.flockyou.R
import com.flockyou.ui.EmergencyAlertActivity
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.FlockYouDatabase
import com.google.android.gms.location.*
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*
import javax.inject.Inject

private const val WAKE_LOCK_TAG = "FlockYou:ScanningWakeLock"

/**
 * Foreground service that continuously scans for surveillance devices
 * using both Bluetooth LE and WiFi
 */
@AndroidEntryPoint
class ScanningService : Service() {
    
    companion object {
        private const val TAG = "ScanningService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "flockyou_scanning"

        // Broadcast actions for cross-process communication
        const val ACTION_NUKE_INITIATED = "com.flockyou.NUKE_INITIATED"
        const val ACTION_DATABASE_SHUTDOWN = "com.flockyou.DATABASE_SHUTDOWN"

        // Flag to track if database is available (set to false during nuke)
        val isDatabaseAvailable = MutableStateFlow(true)

        // Default values (can be overridden by settings)
        // Aggressive burst scan pattern: 25s on, 5s cooldown to prevent thermal throttling
        private const val DEFAULT_WIFI_SCAN_INTERVAL = 30000L  // 30 seconds between WiFi scans
        private const val DEFAULT_BLE_SCAN_DURATION = 25000L   // 25 seconds of low-latency scanning
        private const val DEFAULT_BLE_COOLDOWN = 5000L         // 5 seconds cooldown to prevent thermal throttle
        private const val DEFAULT_INACTIVE_TIMEOUT = 60000L
        private const val DEFAULT_SEEN_DEVICE_TIMEOUT = 300000L

        // Current configured values
        val currentSettings = MutableStateFlow(ScanConfig())

        // IMPORTANT: Process Isolation Note
        // This service runs in a separate process (":scanning" as declared in AndroidManifest.xml).
        // These static MutableStateFlows are process-local - they will have different instances
        // in the main app process vs the :scanning process. State synchronization between
        // processes is handled via the Messenger-based IPC mechanism (ipcMessenger, broadcastToClients).
        // Direct access to these flows from the UI will return stale/default values.
        // UI components should use the IPC bridge (ScanningServiceIpc) to get real-time state.
        val isScanning = MutableStateFlow(false)
        val lastDetection = MutableStateFlow<Detection?>(null)
        val detectionCount = MutableStateFlow(0)

        // Status tracking (see process isolation note above)
        val scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
        val bleStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)
        val wifiStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)
        val locationStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)
        val cellularStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)
        val errorLog = MutableStateFlow<List<ScanError>>(emptyList())
        
        // Seen but unmatched devices
        val seenBleDevices = MutableStateFlow<List<SeenDevice>>(emptyList())
        val seenWifiNetworks = MutableStateFlow<List<SeenDevice>>(emptyList())
        
        // Cellular monitoring data
        val cellStatus = MutableStateFlow<CellularMonitor.CellStatus?>(null)
        val seenCellTowers = MutableStateFlow<List<CellularMonitor.SeenCellTower>>(emptyList())
        val cellularAnomalies = MutableStateFlow<List<CellularMonitor.CellularAnomaly>>(emptyList())
        val cellularEvents = MutableStateFlow<List<CellularMonitor.CellularEvent>>(emptyList())
        
        // Satellite monitoring data
        val satelliteState = MutableStateFlow<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState?>(null)
        val satelliteAnomalies = MutableStateFlow<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly>>(emptyList())
        val satelliteHistory = MutableStateFlow<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionEvent>>(emptyList())
        val satelliteStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)

        // Rogue WiFi monitoring data
        val rogueWifiStatus = MutableStateFlow<RogueWifiMonitor.WifiEnvironmentStatus?>(null)
        val rogueWifiAnomalies = MutableStateFlow<List<RogueWifiMonitor.WifiAnomaly>>(emptyList())
        val rogueWifiEvents = MutableStateFlow<List<RogueWifiMonitor.WifiEvent>>(emptyList())
        val suspiciousNetworks = MutableStateFlow<List<RogueWifiMonitor.SuspiciousNetwork>>(emptyList())

        // RF signal analysis data
        val rfStatus = MutableStateFlow<RfSignalAnalyzer.RfEnvironmentStatus?>(null)
        val rfAnomalies = MutableStateFlow<List<RfSignalAnalyzer.RfAnomaly>>(emptyList())
        val rfEvents = MutableStateFlow<List<RfSignalAnalyzer.RfEvent>>(emptyList())
        val detectedDrones = MutableStateFlow<List<RfSignalAnalyzer.DroneInfo>>(emptyList())

        // Ultrasonic detection data
        val ultrasonicStatus = MutableStateFlow<UltrasonicDetector.UltrasonicStatus?>(null)
        val ultrasonicAnomalies = MutableStateFlow<List<UltrasonicDetector.UltrasonicAnomaly>>(emptyList())
        val ultrasonicEvents = MutableStateFlow<List<UltrasonicDetector.UltrasonicEvent>>(emptyList())
        val ultrasonicBeacons = MutableStateFlow<List<UltrasonicDetector.BeaconDetection>>(emptyList())

        // GNSS satellite monitoring data
        val gnssStatus = MutableStateFlow<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEnvironmentStatus?>(null)
        val gnssSatellites = MutableStateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.SatelliteInfo>>(emptyList())
        val gnssAnomalies = MutableStateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly>>(emptyList())
        val gnssEvents = MutableStateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEvent>>(emptyList())
        val gnssMeasurements = MutableStateFlow<com.flockyou.monitoring.GnssSatelliteMonitor.GnssMeasurementData?>(null)
        val gnssMonitorStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)

        // Detector health tracking for all subsystems
        val detectorHealth = MutableStateFlow<Map<String, DetectorHealthStatus>>(emptyMap())

        // Constants for health monitoring
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L // 30 seconds
        private const val DETECTOR_STALE_THRESHOLD_MS = 120_000L // 2 minutes without scan = stale

        // Scan statistics
        val scanStats = MutableStateFlow(ScanStatistics())

        // Detection refresh event - emits when detections are added/updated
        // This ensures UI updates even if Room's Flow emissions fail with SQLCipher
        private val _detectionRefreshEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
        val detectionRefreshEvent: SharedFlow<Unit> = _detectionRefreshEvent.asSharedFlow()

        // Learning mode - for capturing unknown device signatures
        val learningModeEnabled = MutableStateFlow(false)
        val learnedSignatures = MutableStateFlow<List<LearnedSignature>>(emptyList())

        // Packet rate tracking for Signal trigger detection (advertising spike detection)
        private val devicePacketCounts = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()  // MAC -> timestamps
        private val packetCountsLock = Any()  // Lock for thread-safe list modification
        val highActivityDevices = MutableStateFlow<List<String>>(emptyList())  // MACs with advertising spikes

        private const val MAX_ERROR_LOG_SIZE = 50
        private const val MAX_SEEN_DEVICES = 100
        private const val PACKET_RATE_WINDOW_MS = 5000L  // 5 second window for rate calculation
        private const val HIGH_ACTIVITY_THRESHOLD = 20f  // 20+ packets/second = Signal trigger likely active

        // Lock for thread-safe modification of seen device lists
        private val seenDevicesLock = Any()

        fun clearErrors() {
            errorLog.value = emptyList()
        }

        fun clearSeenDevices() {
            seenBleDevices.value = emptyList()
            seenWifiNetworks.value = emptyList()
        }

        /**
         * Enable learning mode to capture device signatures
         */
        fun enableLearningMode() {
            learningModeEnabled.value = true
        }

        /**
         * Disable learning mode
         */
        fun disableLearningMode() {
            learningModeEnabled.value = false
        }

        /**
         * Learn a device signature from a seen device
         */
        fun learnDeviceSignature(device: SeenDevice, notes: String? = null) {
            val signature = LearnedSignature(
                id = device.id,
                name = device.name,
                macPrefix = device.id.take(8).uppercase(),
                serviceUuids = device.serviceUuids,
                manufacturerIds = device.manufacturerData.keys.toList(),
                notes = notes
            )

            val current = learnedSignatures.value.toMutableList()
            // Remove existing signature for same device if present
            current.removeAll { it.id == device.id }
            current.add(0, signature)
            learnedSignatures.value = current
        }

        /**
         * Clear all learned signatures
         */
        fun clearLearnedSignatures() {
            learnedSignatures.value = emptyList()
        }

        /**
         * Track packet for advertising rate calculation
         */
        fun trackPacket(macAddress: String): Float {
            val now = System.currentTimeMillis()
            val cutoff = now - PACKET_RATE_WINDOW_MS

            val rate = synchronized(packetCountsLock) {
                val packets = devicePacketCounts.getOrPut(macAddress) { mutableListOf() }
                packets.add(now)

                // Remove old packets outside the window - use iterator for safe removal
                val iterator = packets.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next() < cutoff) {
                        iterator.remove()
                    }
                }

                // Calculate rate
                if (packets.size > 1) {
                    packets.size.toFloat() / (PACKET_RATE_WINDOW_MS / 1000f)
                } else {
                    0f
                }
            }

            // Check for high activity (potential Signal trigger activation)
            if (rate >= HIGH_ACTIVITY_THRESHOLD) {
                val current = highActivityDevices.value.toMutableList()
                if (!current.contains(macAddress)) {
                    current.add(macAddress)
                    highActivityDevices.value = current
                }
            }

            return rate
        }
        
        fun clearCellularHistory() {
            seenCellTowers.value = emptyList()
            cellularAnomalies.value = emptyList()
            cellularEvents.value = emptyList()
        }
        
        fun clearSatelliteHistory() {
            satelliteAnomalies.value = emptyList()
            satelliteHistory.value = emptyList()
        }
        
        fun updateSettings(
            wifiIntervalSeconds: Int = 35,
            bleDurationSeconds: Int = 10,
            inactiveTimeoutSeconds: Int = 60,
            seenDeviceTimeoutMinutes: Int = 5,
            enableBle: Boolean = true,
            enableWifi: Boolean = true,
            enableCellular: Boolean = true,
            trackSeenDevices: Boolean = true
        ) {
            currentSettings.value = ScanConfig(
                wifiScanInterval = wifiIntervalSeconds * 1000L,
                bleScanDuration = bleDurationSeconds * 1000L,
                inactiveTimeout = inactiveTimeoutSeconds * 1000L,
                seenDeviceTimeout = seenDeviceTimeoutMinutes * 60 * 1000L,
                enableBle = enableBle,
                enableWifi = enableWifi,
                enableCellular = enableCellular,
                trackSeenDevices = trackSeenDevices
            )
        }

        /**
         * Forcefully stop the scanning service and prevent auto-restart.
         * This completely stops all scanning operations.
         */
        fun forceStop(context: Context) {
            Log.w(TAG, "Force stopping scanning service")

            // Reset all state
            isScanning.value = false
            scanStatus.value = ScanStatus.Idle
            bleStatus.value = SubsystemStatus.Idle
            wifiStatus.value = SubsystemStatus.Idle
            locationStatus.value = SubsystemStatus.Idle
            cellularStatus.value = SubsystemStatus.Idle
            satelliteStatus.value = SubsystemStatus.Idle

            // Stop the service
            val intent = Intent(context, ScanningService::class.java)
            context.stopService(intent)
        }
    }
    
    /** Runtime scan configuration */
    data class ScanConfig(
        val wifiScanInterval: Long = DEFAULT_WIFI_SCAN_INTERVAL,
        val bleScanDuration: Long = DEFAULT_BLE_SCAN_DURATION,
        val bleCooldown: Long = DEFAULT_BLE_COOLDOWN,
        val inactiveTimeout: Long = DEFAULT_INACTIVE_TIMEOUT,
        val seenDeviceTimeout: Long = DEFAULT_SEEN_DEVICE_TIMEOUT,
        val enableBle: Boolean = true,
        val enableWifi: Boolean = true,
        val enableCellular: Boolean = true,
        val trackSeenDevices: Boolean = true,
        val aggressiveBleMode: Boolean = true  // Use MATCH_MODE_AGGRESSIVE for weak signal detection
    )
    
    /** Seen device that didn't match surveillance patterns */
    data class SeenDevice(
        val id: String, // MAC or BSSID
        val name: String?,
        val type: String, // "BLE" or "WiFi"
        val rssi: Int,
        val firstSeen: Long = System.currentTimeMillis(),
        val lastSeen: Long = System.currentTimeMillis(),
        val seenCount: Int = 1,
        val manufacturer: String? = null,
        val serviceUuids: List<String> = emptyList(),
        val manufacturerData: Map<Int, String> = emptyMap(), // Manufacturer ID -> hex data
        val advertisingRate: Float = 0f  // Packets per second (for Signal trigger detection)
    )

    /** Learned device signature (user-confirmed suspicious device) */
    data class LearnedSignature(
        val id: String,
        val name: String?,
        val macPrefix: String, // First 3 octets
        val serviceUuids: List<String>,
        val manufacturerIds: List<Int>,
        val learnedAt: Long = System.currentTimeMillis(),
        val notes: String? = null
    )
    
    /** Scan statistics */
    data class ScanStatistics(
        val totalBleScans: Int = 0,
        val totalWifiScans: Int = 0,
        val successfulWifiScans: Int = 0,
        val throttledWifiScans: Int = 0,
        val bleDevicesSeen: Int = 0,
        val wifiNetworksSeen: Int = 0,
        val lastBleSuccessTime: Long? = null,
        val lastWifiSuccessTime: Long? = null
    )
    
    /** Overall scanning status */
    sealed class ScanStatus {
        object Idle : ScanStatus()
        object Starting : ScanStatus()
        object Active : ScanStatus()
        object Stopping : ScanStatus()
        data class Error(val message: String, val recoverable: Boolean = true) : ScanStatus()

        /** Convert to IPC-friendly string representation */
        fun toIpcString(): String = when (this) {
            is Idle -> "Idle"
            is Starting -> "Starting"
            is Active -> "Active"
            is Stopping -> "Stopping"
            is Error -> "Error:${this.message}"
        }

        companion object {
            /** Parse IPC string back to ScanStatus */
            fun fromIpcString(str: String): ScanStatus = when {
                str == "Idle" -> Idle
                str == "Starting" -> Starting
                str == "Active" -> Active
                str == "Stopping" -> Stopping
                str.startsWith("Error:") -> Error(str.removePrefix("Error:"))
                else -> Idle
            }
        }
    }
    
    /** Individual subsystem status */
    sealed class SubsystemStatus {
        object Idle : SubsystemStatus()
        object Active : SubsystemStatus()
        object Disabled : SubsystemStatus()
        data class Error(val code: Int, val message: String) : SubsystemStatus()
        data class PermissionDenied(val permission: String) : SubsystemStatus()

        /** Convert to IPC-friendly string representation */
        fun toIpcString(): String = when (this) {
            is Idle -> "Idle"
            is Active -> "Active"
            is Disabled -> "Disabled"
            is Error -> "Error:${this.code}:${this.message}"
            is PermissionDenied -> "PermissionDenied:${this.permission}"
        }

        companion object {
            /** Parse IPC string back to SubsystemStatus */
            fun fromIpcString(str: String): SubsystemStatus = when {
                str == "Idle" -> Idle
                str == "Active" -> Active
                str == "Disabled" -> Disabled
                str.startsWith("Error:") -> {
                    val parts = str.removePrefix("Error:").split(":", limit = 2)
                    Error(parts.getOrNull(0)?.toIntOrNull() ?: -1, parts.getOrElse(1) { "Unknown" })
                }
                str.startsWith("PermissionDenied:") -> PermissionDenied(str.removePrefix("PermissionDenied:"))
                else -> Idle
            }
        }
    }

    /** Error log entry */
    data class ScanError(
        val timestamp: Long = System.currentTimeMillis(),
        val subsystem: String,
        val code: Int,
        val message: String,
        val recoverable: Boolean = true
    )

    /** Detector health status for monitoring individual detector subsystems */
    data class DetectorHealthStatus(
        val name: String,
        val isRunning: Boolean = false,
        val lastSuccessfulScan: Long? = null,
        val consecutiveFailures: Int = 0,
        val lastError: String? = null,
        val lastErrorTime: Long? = null,
        val restartCount: Int = 0,
        val isHealthy: Boolean = true
    ) {
        companion object {
            const val DETECTOR_ULTRASONIC = "Ultrasonic"
            const val DETECTOR_ROGUE_WIFI = "RogueWiFi"
            const val DETECTOR_RF_SIGNAL = "RfSignal"
            const val DETECTOR_CELLULAR = "Cellular"
            const val DETECTOR_GNSS = "GNSS"
            const val DETECTOR_SATELLITE = "Satellite"
            const val DETECTOR_BLE = "BLE"
            const val DETECTOR_WIFI = "WiFi"
        }
    }

    /** Callback interface for detectors to report errors and health status */
    interface DetectorCallback {
        fun onError(detectorName: String, error: String, recoverable: Boolean = true)
        fun onScanSuccess(detectorName: String)
        fun onDetectorStarted(detectorName: String)
        fun onDetectorStopped(detectorName: String)
    }

    @Inject
    lateinit var repository: DetectionRepository

    @Inject
    lateinit var ephemeralRepository: com.flockyou.data.repository.EphemeralDetectionRepository

    @Inject
    lateinit var broadcastSettingsRepository: com.flockyou.data.BroadcastSettingsRepository

    @Inject
    lateinit var privacySettingsRepository: com.flockyou.data.PrivacySettingsRepository

    @Inject
    lateinit var scanSettingsRepository: com.flockyou.data.ScanSettingsRepository

    @Inject
    lateinit var notificationSettingsRepository: com.flockyou.data.NotificationSettingsRepository

    private var currentBroadcastSettings: com.flockyou.data.BroadcastSettings = com.flockyou.data.BroadcastSettings()

    private var currentPrivacySettings: com.flockyou.data.PrivacySettings = com.flockyou.data.PrivacySettings()

    private var currentNotificationSettings: com.flockyou.data.NotificationSettings = com.flockyou.data.NotificationSettings()

    // Screen lock receiver for auto-purge feature (Priority 5)
    private var screenLockReceiver: ScreenLockReceiver? = null

    // Nuke receiver for graceful shutdown during data wipe
    private var nukeReceiver: BroadcastReceiver? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    
    // Bluetooth
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isBleScanningActive = false
    
    // WiFi
    private lateinit var wifiManager: WifiManager
    private var wifiScanReceiver: BroadcastReceiver? = null
    
    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    
    // Vibration
    private lateinit var vibrator: Vibrator
    
    // Scan job
    private var scanJob: Job? = null

    // Settings collector jobs (for proper lifecycle management)
    private var broadcastSettingsJob: Job? = null
    private var privacySettingsJob: Job? = null
    private var scanSettingsJob: Job? = null
    private var notificationSettingsJob: Job? = null

    // Location update jobs (for proper lifecycle management)
    private var cellularLocationJob: Job? = null
    private var rogueWifiLocationJob: Job? = null
    private var rfLocationJob: Job? = null
    private var ultrasonicLocationJob: Job? = null
    private var gnssLocationJob: Job? = null
    private var suspiciousNetworksJob: Job? = null

    // Cellular monitor
    private var cellularMonitor: CellularMonitor? = null

    // Satellite monitor
    private var satelliteMonitor: com.flockyou.monitoring.SatelliteMonitor? = null

    // Rogue WiFi monitor
    private var rogueWifiMonitor: RogueWifiMonitor? = null

    // RF signal analyzer
    private var rfSignalAnalyzer: RfSignalAnalyzer? = null

    // Ultrasonic detector
    private var ultrasonicDetector: UltrasonicDetector? = null

    // GNSS satellite monitor
    private var gnssSatelliteMonitor: com.flockyou.monitoring.GnssSatelliteMonitor? = null

    // Health check job for monitoring detector health
    private var healthCheckJob: Job? = null

    // Detector callback implementation for handling errors and health updates
    private val detectorCallbackImpl = object : DetectorCallback {
        override fun onError(detectorName: String, error: String, recoverable: Boolean) {
            handleDetectorError(detectorName, error, recoverable)
        }

        override fun onScanSuccess(detectorName: String) {
            handleDetectorSuccess(detectorName)
        }

        override fun onDetectorStarted(detectorName: String) {
            updateDetectorHealth(detectorName) { current ->
                current.copy(isRunning = true)
            }
            broadcastDetectorHealth()
        }

        override fun onDetectorStopped(detectorName: String) {
            updateDetectorHealth(detectorName) { current ->
                current.copy(isRunning = false)
            }
            broadcastDetectorHealth()
        }
    }

    // Wake lock for background operation
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager

    // IPC: Messenger for cross-process communication
    // Using CopyOnWriteArrayList for thread-safe iteration and modification
    private val ipcClients = java.util.concurrent.CopyOnWriteArrayList<Messenger>()

    // Background HandlerThread for IPC processing to avoid blocking main thread
    private val ipcHandlerThread = HandlerThread("ScanningServiceIpc").apply { start() }
    private val ipcHandler = object : Handler(ipcHandlerThread.looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ScanningServiceIpc.MSG_REGISTER_CLIENT -> {
                    msg.replyTo?.let { client ->
                        if (!ipcClients.contains(client)) {
                            ipcClients.add(client)
                            Log.d(TAG, "IPC client registered (total: ${ipcClients.size})")
                        }
                    }
                }
                ScanningServiceIpc.MSG_UNREGISTER_CLIENT -> {
                    msg.replyTo?.let { client ->
                        ipcClients.remove(client)
                        Log.d(TAG, "IPC client unregistered (total: ${ipcClients.size})")
                    }
                }
                ScanningServiceIpc.MSG_REQUEST_STATE -> {
                    msg.replyTo?.let { client ->
                        sendStateToClient(client)
                    }
                }
                ScanningServiceIpc.MSG_START_SCANNING -> {
                    if (!isScanning.value) {
                        startScanning()
                    }
                }
                ScanningServiceIpc.MSG_STOP_SCANNING -> {
                    if (isScanning.value) {
                        stopScanning()
                    }
                }
                ScanningServiceIpc.MSG_CLEAR_SEEN_DEVICES -> {
                    clearSeenDevices()
                    broadcastSeenBleDevices()
                    broadcastSeenWifiNetworks()
                }
                ScanningServiceIpc.MSG_RESET_DETECTION_COUNT -> {
                    detectionCount.value = 0
                    lastDetection.value = null
                    broadcastLastDetection()
                    broadcastStateToClients()
                }
                ScanningServiceIpc.MSG_CLEAR_CELLULAR_HISTORY -> {
                    clearCellularHistory()
                    broadcastCellularData()
                }
                ScanningServiceIpc.MSG_CLEAR_SATELLITE_HISTORY -> {
                    clearSatelliteHistory()
                    broadcastSatelliteData()
                }
                ScanningServiceIpc.MSG_CLEAR_ERRORS -> {
                    clearErrors()
                    broadcastErrorLog()
                }
                ScanningServiceIpc.MSG_CLEAR_LEARNED_SIGNATURES -> {
                    clearLearnedSignatures()
                }
                else -> super.handleMessage(msg)
            }
        }
    }
    private val ipcMessenger = Messenger(ipcHandler)

    /**
     * Send current state to a specific client (basic state only).
     */
    private fun sendStateToClient(client: Messenger) {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_STATE_UPDATE)
            msg.data = Bundle().apply {
                putBoolean(ScanningServiceIpc.KEY_IS_SCANNING, isScanning.value)
                putInt(ScanningServiceIpc.KEY_DETECTION_COUNT, detectionCount.value)
                putString(ScanningServiceIpc.KEY_SCAN_STATUS, scanStatus.value.toIpcString())
                putString(ScanningServiceIpc.KEY_BLE_STATUS, bleStatus.value.toIpcString())
                putString(ScanningServiceIpc.KEY_WIFI_STATUS, wifiStatus.value.toIpcString())
                putString(ScanningServiceIpc.KEY_LOCATION_STATUS, locationStatus.value.toIpcString())
                putString(ScanningServiceIpc.KEY_CELLULAR_STATUS, cellularStatus.value.toIpcString())
                putString(ScanningServiceIpc.KEY_SATELLITE_STATUS, satelliteStatus.value.toIpcString())
            }
            client.send(msg)

            // Also send all complex data on state request (initial sync)
            sendAllDataToClient(client)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to send state to client", e)
            ipcClients.remove(client)
        }
    }

    /**
     * Send all complex data to a specific client.
     */
    private fun sendAllDataToClient(client: Messenger) {
        try {
            // Send seen BLE devices
            val bleMsg = Message.obtain(null, ScanningServiceIpc.MSG_SEEN_BLE_DEVICES)
            bleMsg.data = Bundle().apply {
                putString(ScanningServiceIpc.KEY_JSON_DATA, ScanningServiceIpc.gson.toJson(seenBleDevices.value))
            }
            client.send(bleMsg)

            // Send seen WiFi networks
            val wifiMsg = Message.obtain(null, ScanningServiceIpc.MSG_SEEN_WIFI_NETWORKS)
            wifiMsg.data = Bundle().apply {
                putString(ScanningServiceIpc.KEY_JSON_DATA, ScanningServiceIpc.gson.toJson(seenWifiNetworks.value))
            }
            client.send(wifiMsg)

            // Send cellular data
            val cellularMsg = Message.obtain(null, ScanningServiceIpc.MSG_CELLULAR_DATA)
            cellularMsg.data = Bundle().apply {
                cellStatus.value?.let { putString(ScanningServiceIpc.KEY_CELL_STATUS_JSON, ScanningServiceIpc.gson.toJson(it)) }
                putString(ScanningServiceIpc.KEY_SEEN_TOWERS_JSON, ScanningServiceIpc.gson.toJson(seenCellTowers.value))
                putString(ScanningServiceIpc.KEY_CELLULAR_ANOMALIES_JSON, ScanningServiceIpc.gson.toJson(cellularAnomalies.value))
                putString(ScanningServiceIpc.KEY_CELLULAR_EVENTS_JSON, ScanningServiceIpc.gson.toJson(cellularEvents.value))
            }
            client.send(cellularMsg)

            // Send satellite data
            val satelliteMsg = Message.obtain(null, ScanningServiceIpc.MSG_SATELLITE_DATA)
            satelliteMsg.data = Bundle().apply {
                satelliteState.value?.let { putString(ScanningServiceIpc.KEY_SATELLITE_STATE_JSON, ScanningServiceIpc.gson.toJson(it)) }
                putString(ScanningServiceIpc.KEY_SATELLITE_ANOMALIES_JSON, ScanningServiceIpc.gson.toJson(satelliteAnomalies.value))
                putString(ScanningServiceIpc.KEY_SATELLITE_HISTORY_JSON, ScanningServiceIpc.gson.toJson(satelliteHistory.value))
            }
            client.send(satelliteMsg)

            // Send rogue WiFi data
            val rogueWifiMsg = Message.obtain(null, ScanningServiceIpc.MSG_ROGUE_WIFI_DATA)
            rogueWifiMsg.data = Bundle().apply {
                rogueWifiStatus.value?.let { putString(ScanningServiceIpc.KEY_ROGUE_WIFI_STATUS_JSON, ScanningServiceIpc.gson.toJson(it)) }
                putString(ScanningServiceIpc.KEY_ROGUE_WIFI_ANOMALIES_JSON, ScanningServiceIpc.gson.toJson(rogueWifiAnomalies.value))
                putString(ScanningServiceIpc.KEY_SUSPICIOUS_NETWORKS_JSON, ScanningServiceIpc.gson.toJson(suspiciousNetworks.value))
            }
            client.send(rogueWifiMsg)

            // Send RF data
            val rfMsg = Message.obtain(null, ScanningServiceIpc.MSG_RF_DATA)
            rfMsg.data = Bundle().apply {
                rfStatus.value?.let { putString(ScanningServiceIpc.KEY_RF_STATUS_JSON, ScanningServiceIpc.gson.toJson(it)) }
                putString(ScanningServiceIpc.KEY_RF_ANOMALIES_JSON, ScanningServiceIpc.gson.toJson(rfAnomalies.value))
                putString(ScanningServiceIpc.KEY_DETECTED_DRONES_JSON, ScanningServiceIpc.gson.toJson(detectedDrones.value))
            }
            client.send(rfMsg)

            // Send ultrasonic data
            val ultrasonicMsg = Message.obtain(null, ScanningServiceIpc.MSG_ULTRASONIC_DATA)
            ultrasonicMsg.data = Bundle().apply {
                ultrasonicStatus.value?.let { putString(ScanningServiceIpc.KEY_ULTRASONIC_STATUS_JSON, ScanningServiceIpc.gson.toJson(it)) }
                putString(ScanningServiceIpc.KEY_ULTRASONIC_ANOMALIES_JSON, ScanningServiceIpc.gson.toJson(ultrasonicAnomalies.value))
                putString(ScanningServiceIpc.KEY_ULTRASONIC_BEACONS_JSON, ScanningServiceIpc.gson.toJson(ultrasonicBeacons.value))
            }
            client.send(ultrasonicMsg)

            // Send GNSS data
            val gnssMsg = Message.obtain(null, ScanningServiceIpc.MSG_GNSS_DATA)
            gnssMsg.data = Bundle().apply {
                gnssStatus.value?.let { putString(ScanningServiceIpc.KEY_GNSS_STATUS_JSON, ScanningServiceIpc.gson.toJson(it)) }
                putString(ScanningServiceIpc.KEY_GNSS_SATELLITES_JSON, ScanningServiceIpc.gson.toJson(gnssSatellites.value))
                putString(ScanningServiceIpc.KEY_GNSS_ANOMALIES_JSON, ScanningServiceIpc.gson.toJson(gnssAnomalies.value))
                putString(ScanningServiceIpc.KEY_GNSS_EVENTS_JSON, ScanningServiceIpc.gson.toJson(gnssEvents.value))
                gnssMeasurements.value?.let { putString(ScanningServiceIpc.KEY_GNSS_MEASUREMENTS_JSON, ScanningServiceIpc.gson.toJson(it)) }
            }
            client.send(gnssMsg)

            // Send last detection
            val detectionMsg = Message.obtain(null, ScanningServiceIpc.MSG_LAST_DETECTION)
            detectionMsg.data = Bundle().apply {
                putString(ScanningServiceIpc.KEY_LAST_DETECTION_JSON, lastDetection.value?.let { ScanningServiceIpc.gson.toJson(it) })
            }
            client.send(detectionMsg)

            // Send detector health
            val healthMsg = Message.obtain(null, ScanningServiceIpc.MSG_DETECTOR_HEALTH)
            healthMsg.data = Bundle().apply {
                putString(ScanningServiceIpc.KEY_DETECTOR_HEALTH_JSON, ScanningServiceIpc.gson.toJson(detectorHealth.value))
            }
            client.send(healthMsg)

            // Send error log
            val errorMsg = Message.obtain(null, ScanningServiceIpc.MSG_ERROR_LOG)
            errorMsg.data = Bundle().apply {
                putString(ScanningServiceIpc.KEY_ERROR_LOG_JSON, ScanningServiceIpc.gson.toJson(errorLog.value))
            }
            client.send(errorMsg)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to send all data to client", e)
        }
    }

    /**
     * Helper to broadcast a message to all IPC clients.
     * CopyOnWriteArrayList allows safe iteration while removing dead clients.
     */
    private inline fun broadcastToClients(createMessage: () -> Message) {
        if (ipcClients.isEmpty()) return
        val msg = createMessage()
        for (client in ipcClients) {
            try {
                // Create a copy of the message for each client
                val clientMsg = Message.obtain(msg)
                client.send(clientMsg)
            } catch (e: RemoteException) {
                // Remove dead client - safe with CopyOnWriteArrayList
                ipcClients.remove(client)
            }
        }
        msg.recycle()
    }

    /**
     * Broadcast state update to all registered IPC clients.
     */
    private fun broadcastStateToClients() {
        for (client in ipcClients) {
            try {
                sendStateToClient(client)
            } catch (e: RemoteException) {
                ipcClients.remove(client)
            }
        }
    }

    /**
     * Broadcast scanning started to all registered IPC clients.
     */
    private fun broadcastScanningStarted() {
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_SCANNING_STARTED)
        }
    }

    /**
     * Broadcast scanning stopped to all registered IPC clients.
     */
    private fun broadcastScanningStopped() {
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_SCANNING_STOPPED)
        }
    }

    /**
     * Broadcast seen BLE devices to all registered IPC clients.
     */
    private fun broadcastSeenBleDevices() {
        if (ipcClients.isEmpty()) return
        val json = ScanningServiceIpc.gson.toJson(seenBleDevices.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_SEEN_BLE_DEVICES).apply {
                data = Bundle().apply {
                    putString(ScanningServiceIpc.KEY_JSON_DATA, json)
                }
            }
        }
    }

    /**
     * Broadcast seen WiFi networks to all registered IPC clients.
     */
    private fun broadcastSeenWifiNetworks() {
        if (ipcClients.isEmpty()) return
        val json = ScanningServiceIpc.gson.toJson(seenWifiNetworks.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_SEEN_WIFI_NETWORKS).apply {
                data = Bundle().apply {
                    putString(ScanningServiceIpc.KEY_JSON_DATA, json)
                }
            }
        }
    }

    /**
     * Broadcast cellular monitoring data to all registered IPC clients.
     */
    private fun broadcastCellularData() {
        if (ipcClients.isEmpty()) return
        val cellStatusJson = cellStatus.value?.let { ScanningServiceIpc.gson.toJson(it) }
        val towersJson = ScanningServiceIpc.gson.toJson(seenCellTowers.value)
        val anomaliesJson = ScanningServiceIpc.gson.toJson(cellularAnomalies.value)
        val eventsJson = ScanningServiceIpc.gson.toJson(cellularEvents.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_CELLULAR_DATA).apply {
                data = Bundle().apply {
                    cellStatusJson?.let { putString(ScanningServiceIpc.KEY_CELL_STATUS_JSON, it) }
                    putString(ScanningServiceIpc.KEY_SEEN_TOWERS_JSON, towersJson)
                    putString(ScanningServiceIpc.KEY_CELLULAR_ANOMALIES_JSON, anomaliesJson)
                    putString(ScanningServiceIpc.KEY_CELLULAR_EVENTS_JSON, eventsJson)
                }
            }
        }
    }

    /**
     * Broadcast satellite monitoring data to all registered IPC clients.
     */
    private fun broadcastSatelliteData() {
        if (ipcClients.isEmpty()) return
        val stateJson = satelliteState.value?.let { ScanningServiceIpc.gson.toJson(it) }
        val anomaliesJson = ScanningServiceIpc.gson.toJson(satelliteAnomalies.value)
        val historyJson = ScanningServiceIpc.gson.toJson(satelliteHistory.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_SATELLITE_DATA).apply {
                data = Bundle().apply {
                    stateJson?.let { putString(ScanningServiceIpc.KEY_SATELLITE_STATE_JSON, it) }
                    putString(ScanningServiceIpc.KEY_SATELLITE_ANOMALIES_JSON, anomaliesJson)
                    putString(ScanningServiceIpc.KEY_SATELLITE_HISTORY_JSON, historyJson)
                }
            }
        }
    }

    /**
     * Broadcast rogue WiFi monitoring data to all registered IPC clients.
     */
    private fun broadcastRogueWifiData() {
        if (ipcClients.isEmpty()) return
        val statusJson = rogueWifiStatus.value?.let { ScanningServiceIpc.gson.toJson(it) }
        val anomaliesJson = ScanningServiceIpc.gson.toJson(rogueWifiAnomalies.value)
        val suspiciousJson = ScanningServiceIpc.gson.toJson(suspiciousNetworks.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_ROGUE_WIFI_DATA).apply {
                data = Bundle().apply {
                    statusJson?.let { putString(ScanningServiceIpc.KEY_ROGUE_WIFI_STATUS_JSON, it) }
                    putString(ScanningServiceIpc.KEY_ROGUE_WIFI_ANOMALIES_JSON, anomaliesJson)
                    putString(ScanningServiceIpc.KEY_SUSPICIOUS_NETWORKS_JSON, suspiciousJson)
                }
            }
        }
    }

    /**
     * Broadcast RF signal analysis data to all registered IPC clients.
     */
    private fun broadcastRfData() {
        if (ipcClients.isEmpty()) return
        val statusJson = rfStatus.value?.let { ScanningServiceIpc.gson.toJson(it) }
        val anomaliesJson = ScanningServiceIpc.gson.toJson(rfAnomalies.value)
        val dronesJson = ScanningServiceIpc.gson.toJson(detectedDrones.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_RF_DATA).apply {
                data = Bundle().apply {
                    statusJson?.let { putString(ScanningServiceIpc.KEY_RF_STATUS_JSON, it) }
                    putString(ScanningServiceIpc.KEY_RF_ANOMALIES_JSON, anomaliesJson)
                    putString(ScanningServiceIpc.KEY_DETECTED_DRONES_JSON, dronesJson)
                }
            }
        }
    }

    /**
     * Broadcast ultrasonic detection data to all registered IPC clients.
     */
    private fun broadcastUltrasonicData() {
        if (ipcClients.isEmpty()) return
        val statusJson = ultrasonicStatus.value?.let { ScanningServiceIpc.gson.toJson(it) }
        val anomaliesJson = ScanningServiceIpc.gson.toJson(ultrasonicAnomalies.value)
        val beaconsJson = ScanningServiceIpc.gson.toJson(ultrasonicBeacons.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_ULTRASONIC_DATA).apply {
                data = Bundle().apply {
                    statusJson?.let { putString(ScanningServiceIpc.KEY_ULTRASONIC_STATUS_JSON, it) }
                    putString(ScanningServiceIpc.KEY_ULTRASONIC_ANOMALIES_JSON, anomaliesJson)
                    putString(ScanningServiceIpc.KEY_ULTRASONIC_BEACONS_JSON, beaconsJson)
                }
            }
        }
    }

    /**
     * Broadcast GNSS satellite monitoring data to all registered IPC clients.
     */
    private fun broadcastGnssData() {
        if (ipcClients.isEmpty()) return
        val statusJson = gnssStatus.value?.let { ScanningServiceIpc.gson.toJson(it) }
        val satellitesJson = ScanningServiceIpc.gson.toJson(gnssSatellites.value)
        val anomaliesJson = ScanningServiceIpc.gson.toJson(gnssAnomalies.value)
        val eventsJson = ScanningServiceIpc.gson.toJson(gnssEvents.value)
        val measurementsJson = gnssMeasurements.value?.let { ScanningServiceIpc.gson.toJson(it) }
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_GNSS_DATA).apply {
                data = Bundle().apply {
                    statusJson?.let { putString(ScanningServiceIpc.KEY_GNSS_STATUS_JSON, it) }
                    putString(ScanningServiceIpc.KEY_GNSS_SATELLITES_JSON, satellitesJson)
                    putString(ScanningServiceIpc.KEY_GNSS_ANOMALIES_JSON, anomaliesJson)
                    putString(ScanningServiceIpc.KEY_GNSS_EVENTS_JSON, eventsJson)
                    measurementsJson?.let { putString(ScanningServiceIpc.KEY_GNSS_MEASUREMENTS_JSON, it) }
                }
            }
        }
    }

    /**
     * Broadcast last detection to all registered IPC clients.
     */
    private fun broadcastLastDetection() {
        if (ipcClients.isEmpty()) return
        val json = lastDetection.value?.let { ScanningServiceIpc.gson.toJson(it) }
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_LAST_DETECTION).apply {
                data = Bundle().apply {
                    putString(ScanningServiceIpc.KEY_LAST_DETECTION_JSON, json)
                }
            }
        }
    }

    /**
     * Broadcast detection refresh event to all IPC clients.
     * Notifies UI to reload detections from database.
     */
    private fun broadcastDetectionRefresh() {
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_DETECTION_REFRESH)
        }
    }

    /**
     * Broadcast all data to IPC clients. Called periodically to keep clients in sync.
     */
    private fun broadcastAllDataToClients() {
        if (ipcClients.isEmpty()) return
        broadcastStateToClients()
        broadcastSeenBleDevices()
        broadcastSeenWifiNetworks()
        broadcastCellularData()
        broadcastSatelliteData()
        broadcastRogueWifiData()
        broadcastRfData()
        broadcastUltrasonicData()
        broadcastGnssData()
        broadcastLastDetection()
        broadcastDetectorHealth()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        // Initialize Power Manager and Wake Lock
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        // Initialize Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        
        // Initialize WiFi
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // Initialize Location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Initialize Vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        // Initialize Cellular Monitor
        cellularMonitor = CellularMonitor(applicationContext, detectorCallbackImpl)

        // Initialize Satellite Monitor with error callback
        satelliteMonitor = com.flockyou.monitoring.SatelliteMonitor(applicationContext, detectorCallbackImpl)

        // Initialize Rogue WiFi Monitor with error callback
        rogueWifiMonitor = RogueWifiMonitor(applicationContext, detectorCallbackImpl)

        // Initialize RF Signal Analyzer with error callback
        rfSignalAnalyzer = RfSignalAnalyzer(applicationContext, detectorCallbackImpl)

        // Initialize Ultrasonic Detector with error callback
        ultrasonicDetector = UltrasonicDetector(applicationContext, detectorCallbackImpl)

        // Initialize GNSS Satellite Monitor with error callback
        gnssSatelliteMonitor = com.flockyou.monitoring.GnssSatelliteMonitor(applicationContext, detectorCallbackImpl)

        // Initialize detector health data so it's available immediately when clients connect
        // This ensures Service Health screen can display data even before scanning starts
        initializeDetectorHealth()

        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")
        
        // Acquire wake lock to prevent CPU from sleeping
        acquireWakeLock()
        
        val notification = createNotification("Initializing...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Mark service as enabled for boot receiver
        BootReceiver.setServiceEnabled(this, true)
        
        // Schedule watchdog to ensure service stays running
        ServiceRestartReceiver.scheduleWatchdog(this)
        
        startScanning()
        
        return START_STICKY
    }
    
    /**
     * Acquire a partial wake lock to keep CPU running during scans.
     * Uses a 10-minute timeout which is re-acquired in the scan loop.
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
            }
        }
        
        if (wakeLock?.isHeld == false) {
            // Acquire with timeout of 10 minutes, will be re-acquired in scan loop
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d(TAG, "Wake lock acquired")
        }
    }
    
    /**
     * Release the wake lock
     */
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "Client binding to service")
        return ipcMessenger.binder
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - scheduling restart")
        
        // If service should continue running, schedule restart
        if (BootReceiver.isServiceEnabled(this)) {
            ServiceRestartReceiver.scheduleRestart(this)
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        
        // Release wake lock
        releaseWakeLock()
        
        stopScanning()
        cellularMonitor?.destroy()
        cellularMonitor = null
        satelliteMonitor?.stopMonitoring()
        satelliteMonitor = null
        rogueWifiMonitor?.destroy()
        rogueWifiMonitor = null
        rfSignalAnalyzer?.destroy()
        rfSignalAnalyzer = null
        ultrasonicDetector?.destroy()
        ultrasonicDetector = null
        gnssSatelliteMonitor?.stopMonitoring()
        gnssSatelliteMonitor = null

        // Cancel watchdog if service is intentionally stopped
        // Only schedule restart if service should still be running
        if (BootReceiver.isServiceEnabled(this)) {
            Log.d(TAG, "Service was destroyed but should be running - scheduling restart")
            ServiceRestartReceiver.scheduleRestart(this)
        } else {
            Log.d(TAG, "Service intentionally stopped - canceling watchdog")
            ServiceRestartReceiver.cancelWatchdog(this)
        }
        
        serviceScope.cancel()

        // Clean up IPC handler thread
        ipcHandlerThread.quitSafely()

        super.onDestroy()
    }
    
    /**
     * Called when user explicitly stops the service
     */
    fun stopServiceCompletely() {
        Log.d(TAG, "Service stopped by user")
        BootReceiver.setServiceEnabled(this, false)
        ServiceRestartReceiver.cancelWatchdog(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Flock You Scanning",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Surveillance device detection service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Flock You - Scanning")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (isScanning.value) return

        scanStatus.value = ScanStatus.Starting
        Log.d(TAG, "Starting scanning")

        // Collect broadcast settings
        broadcastSettingsJob = serviceScope.launch {
            broadcastSettingsRepository.settings.collect { settings ->
                currentBroadcastSettings = settings
            }
        }

        // Collect privacy settings for ephemeral mode, location-optional storage, and ultrasonic opt-in
        privacySettingsJob = serviceScope.launch {
            var isFirstEmission = true
            privacySettingsRepository.settings.collect { settings ->
                val previousSettings = currentPrivacySettings
                currentPrivacySettings = settings

                // Clear ephemeral data when ephemeral mode is enabled (on service restart)
                if (settings.ephemeralModeEnabled) {
                    ephemeralRepository.clearAll()
                    Log.d(TAG, "Ephemeral mode active - in-memory storage only")
                }

                // Handle ultrasonic detection opt-in/opt-out changes
                // On first emission, start if enabled (handles service restart with ultrasonic already enabled)
                // On subsequent emissions, only react to actual changes
                val shouldStart = settings.ultrasonicDetectionEnabled && settings.ultrasonicConsentAcknowledged
                val settingChanged = settings.ultrasonicDetectionEnabled != previousSettings.ultrasonicDetectionEnabled

                if (isFirstEmission && shouldStart) {
                    Log.i(TAG, "Ultrasonic detection enabled on startup - starting monitoring")
                    startUltrasonicDetection()
                } else if (!isFirstEmission && settingChanged) {
                    if (shouldStart) {
                        Log.i(TAG, "Ultrasonic detection enabled by user - starting monitoring")
                        startUltrasonicDetection()
                    } else {
                        Log.i(TAG, "Ultrasonic detection disabled by user - stopping monitoring")
                        stopUltrasonicDetection()
                    }
                }

                isFirstEmission = false
            }
        }

        // Collect scan settings and update detector timings
        scanSettingsJob = serviceScope.launch {
            scanSettingsRepository.settings.collect { settings ->
                Log.d(TAG, "Scan settings updated - applying to detectors")

                // Update ultrasonic detector timing
                ultrasonicDetector?.updateScanTiming(
                    intervalSeconds = settings.ultrasonicScanIntervalSeconds,
                    durationSeconds = settings.ultrasonicScanDurationSeconds
                )

                // Update GNSS satellite monitor timing
                gnssSatelliteMonitor?.updateScanTiming(settings.gnssScanIntervalSeconds)

                // Update satellite monitor timing
                satelliteMonitor?.updateScanTiming(settings.satelliteScanIntervalSeconds)

                // Update cellular monitor timing
                cellularMonitor?.updateScanTiming(settings.cellularScanIntervalSeconds)

                // Update WiFi/BLE scan config (these are used by the scan loop)
                currentSettings.value = ScanConfig(
                    wifiScanInterval = settings.wifiScanIntervalSeconds * 1000L,
                    bleScanDuration = settings.bleScanDurationSeconds * 1000L,
                    inactiveTimeout = settings.inactiveTimeoutSeconds * 1000L,
                    seenDeviceTimeout = settings.seenDeviceTimeoutMinutes * 60 * 1000L,
                    enableBle = settings.enableBleScanning,
                    enableWifi = settings.enableWifiScanning,
                    trackSeenDevices = settings.trackSeenDevices
                )
            }
        }

        // Collect notification settings for emergency popup feature
        notificationSettingsJob = serviceScope.launch {
            notificationSettingsRepository.settings.collect { settings ->
                currentNotificationSettings = settings
                Log.d(TAG, "Notification settings updated - emergency popup: ${settings.emergencyPopupEnabled}")
            }
        }

        // Register screen lock receiver for auto-purge feature (Priority 5)
        try {
            screenLockReceiver = ScreenLockReceiver.register(this)
            Log.d(TAG, "Screen lock receiver registered for auto-purge feature")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen lock receiver", e)
        }

        // Register nuke receiver for graceful database shutdown
        registerNukeReceiver()

        val config = currentSettings.value

        // Check permissions first
        if (!hasBluetoothPermissions()) {
            bleStatus.value = SubsystemStatus.PermissionDenied("BLUETOOTH_SCAN")
            logError("BLE", -1, "Bluetooth permissions not granted", recoverable = true)
        }
        
        if (!hasLocationPermissions()) {
            locationStatus.value = SubsystemStatus.PermissionDenied("ACCESS_FINE_LOCATION")
            wifiStatus.value = SubsystemStatus.PermissionDenied("ACCESS_FINE_LOCATION")
            logError("Location", -1, "Location permissions not granted", recoverable = true)
        }
        
        isScanning.value = true
        scanStatus.value = ScanStatus.Active

        // Notify IPC clients that scanning has started
        broadcastScanningStarted()

        // Get initial location
        updateLocation()
        
        // Register WiFi scan receiver
        if (config.enableWifi) {
            registerWifiReceiver()
        }
        
        // Start cellular monitoring
        if (config.enableCellular) {
            startCellularMonitoring()
        }
        
        // Start satellite monitoring
        startSatelliteMonitoring()

        // Start rogue WiFi monitoring
        startRogueWifiMonitoring()

        // Start RF signal analysis
        startRfSignalAnalysis()

        // Note: Ultrasonic detection is started by the privacy settings collector above
        // when it receives the first emission (handles the race condition between settings
        // loading and this point in the code). This ensures ultrasonic starts even if
        // settings are already enabled when the service restarts.

        // Start GNSS satellite monitoring (uses location permission already granted)
        startGnssMonitoring()

        // Initialize and start detector health monitoring
        initializeDetectorHealth()
        startHealthCheckJob()

        // Start heartbeat monitoring - sends periodic heartbeats to watchdog
        ServiceRestartReceiver.scheduleHeartbeat(this)
        ServiceRestartReceiver.scheduleJobSchedulerBackup(this)

        // Record heartbeat immediately so watchdog knows we're alive
        ServiceRestartReceiver.recordHeartbeat(this)

        // Start continuous scanning with burst pattern (25s on, 5s cooldown)
        scanJob = serviceScope.launch {
            var scanCycleCount = 0
            var consecutiveBleErrors = 0

            while (isActive) {
                val scanConfig = currentSettings.value // Re-read in case settings changed

                // Refresh wake lock to prevent timeout
                acquireWakeLock()

                try {
                    // === HEARTBEAT ===
                    // Send heartbeat every cycle to prove we're alive
                    ServiceRestartReceiver.recordHeartbeat(this@ScanningService)

                    // === BLE BURST SCAN ===
                    // Scan for 25 seconds in low-latency mode, then 5s cooldown
                    // This prevents Android thermal throttling while maximizing detection
                    if (scanConfig.enableBle) {
                        try {
                            startBleScan(scanConfig.aggressiveBleMode)
                            delay(scanConfig.bleScanDuration)
                            stopBleScan()
                            consecutiveBleErrors = 0 // Reset on success

                            // Thermal cooldown period - prevents Android from force-stopping scans
                            Log.d(TAG, "BLE cooldown: ${scanConfig.bleCooldown}ms")
                            delay(scanConfig.bleCooldown)
                        } catch (e: Exception) {
                            consecutiveBleErrors++
                            Log.e(TAG, "BLE scan error (consecutive: $consecutiveBleErrors)", e)
                            logError("BLE", -1, "Scan error: ${e.message}", recoverable = true)

                            // If too many consecutive errors, disable BLE temporarily
                            if (consecutiveBleErrors >= 3) {
                                Log.w(TAG, "Too many BLE errors, pausing BLE for this cycle")
                                bleStatus.value = SubsystemStatus.Error(-1, "Paused due to errors")
                                delay(scanConfig.bleCooldown * 2) // Extended cooldown
                            }
                        }
                    }

                    // === WiFi SCAN ===
                    // Trigger WiFi scan (results come via broadcast receiver)
                    if (scanConfig.enableWifi) {
                        try {
                            startWifiScan()
                        } catch (e: Exception) {
                            Log.e(TAG, "WiFi scan error", e)
                            logError("WiFi", -1, "Scan error: ${e.message}", recoverable = true)
                        }
                    }

                    // Update location
                    try {
                        updateLocation()
                    } catch (e: Exception) {
                        Log.e(TAG, "Location update error", e)
                    }

                    // Mark old detections as inactive
                    try {
                        val inactiveThreshold = System.currentTimeMillis() - scanConfig.inactiveTimeout
                        repository.markOldInactive(inactiveThreshold)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error marking old detections inactive", e)
                    }

                    // Clean up old seen devices
                    if (scanConfig.trackSeenDevices) {
                        try {
                            cleanupSeenDevices(scanConfig.seenDeviceTimeout)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error cleaning up seen devices", e)
                        }
                    }

                    // Update notification with status
                    try {
                        val statusText = buildStatusText()
                        updateNotification(statusText)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating notification", e)
                    }

                    // Broadcast all data to IPC clients every scan cycle
                    try {
                        broadcastAllDataToClients()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error broadcasting to IPC clients", e)
                    }

                    scanCycleCount++

                    // Every 10 cycles, re-schedule the watchdog to ensure it stays active
                    if (scanCycleCount % 10 == 0) {
                        ServiceRestartReceiver.scheduleWatchdog(this@ScanningService)
                        Log.d(TAG, "Completed $scanCycleCount scan cycles")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Scanning error", e)
                    logError("Scanner", -1, "Scan cycle error: ${e.message}", recoverable = true)
                    // Don't let any error kill the loop - just continue to next cycle
                    delay(1000) // Brief pause before retrying
                }
            }
        }
    }
    
    private fun cleanupSeenDevices(timeout: Long) {
        val cutoff = System.currentTimeMillis() - timeout
        val bleCountBefore = seenBleDevices.value.size
        val wifiCountBefore = seenWifiNetworks.value.size
        synchronized(seenDevicesLock) {
            seenBleDevices.value = seenBleDevices.value.filter { it.lastSeen > cutoff }
            seenWifiNetworks.value = seenWifiNetworks.value.filter { it.lastSeen > cutoff }
        }
        // Broadcast if devices were removed
        if (seenBleDevices.value.size != bleCountBefore) {
            broadcastSeenBleDevices()
        }
        if (seenWifiNetworks.value.size != wifiCountBefore) {
            broadcastSeenWifiNetworks()
        }
    }
    
    private fun buildStatusText(): String {
        val parts = mutableListOf<String>()
        parts.add("Detections: ${detectionCount.value}")
        
        when (val ble = bleStatus.value) {
            is SubsystemStatus.Error -> parts.add("BLE: Error ${ble.code}")
            is SubsystemStatus.PermissionDenied -> parts.add("BLE: No permission")
            is SubsystemStatus.Disabled -> parts.add("BLE: Disabled")
            else -> {}
        }
        
        when (wifiStatus.value) {
            is SubsystemStatus.Error -> parts.add("WiFi: Error")
            is SubsystemStatus.PermissionDenied -> parts.add("WiFi: No permission")
            is SubsystemStatus.Disabled -> parts.add("WiFi: Disabled")
            else -> {}
        }
        
        return parts.joinToString(" | ")
    }
    
    private fun logError(subsystem: String, code: Int, message: String, recoverable: Boolean = true) {
        val error = ScanError(
            subsystem = subsystem,
            code = code,
            message = message,
            recoverable = recoverable
        )
        Log.e(TAG, "[$subsystem] Error $code: $message")

        val currentErrors = errorLog.value.toMutableList()
        currentErrors.add(0, error)
        if (currentErrors.size > MAX_ERROR_LOG_SIZE) {
            currentErrors.removeAt(currentErrors.lastIndex)
        }
        errorLog.value = currentErrors
        broadcastErrorLog()
    }

    /**
     * Broadcast error log to all registered IPC clients.
     */
    private fun broadcastErrorLog() {
        if (ipcClients.isEmpty()) return
        val json = ScanningServiceIpc.gson.toJson(errorLog.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_ERROR_LOG).apply {
                data = Bundle().apply {
                    putString(ScanningServiceIpc.KEY_ERROR_LOG_JSON, json)
                }
            }
        }
    }
    
    private fun stopScanning() {
        scanStatus.value = ScanStatus.Stopping
        isScanning.value = false

        // Notify IPC clients that scanning has stopped
        broadcastScanningStopped()

        // Cancel settings collector jobs
        broadcastSettingsJob?.cancel()
        broadcastSettingsJob = null
        privacySettingsJob?.cancel()
        privacySettingsJob = null
        scanSettingsJob?.cancel()
        scanSettingsJob = null
        notificationSettingsJob?.cancel()
        notificationSettingsJob = null

        scanJob?.cancel()
        stopBleScan()
        unregisterWifiReceiver()
        stopCellularMonitoring()
        stopSatelliteMonitoring()
        stopRogueWifiMonitoring()
        stopRfSignalAnalysis()
        stopUltrasonicDetection()
        stopGnssMonitoring()

        // Stop health check job
        stopHealthCheckJob()

        // Unregister screen lock receiver
        screenLockReceiver?.let {
            ScreenLockReceiver.unregister(this, it)
            screenLockReceiver = null
        }

        // Unregister nuke receiver
        unregisterNukeReceiver()

        // Reset subsystem statuses
        bleStatus.value = SubsystemStatus.Idle
        wifiStatus.value = SubsystemStatus.Idle
        locationStatus.value = SubsystemStatus.Idle
        cellularStatus.value = SubsystemStatus.Idle
        satelliteStatus.value = SubsystemStatus.Idle
        scanStatus.value = ScanStatus.Idle

        Log.d(TAG, "Stopped scanning")
    }
    
    // ==================== Cellular Monitoring ====================
    
    private var cellularAnomalyJob: Job? = null
    private var cellularStatusJob: Job? = null
    private var cellularHistoryJob: Job? = null
    private var cellularEventsJob: Job? = null
    
    private fun startCellularMonitoring() {
        if (!hasTelephonyPermissions()) {
            cellularStatus.value = SubsystemStatus.PermissionDenied("READ_PHONE_STATE")
            Log.w(TAG, "Missing telephony permissions for cellular monitoring")
            return
        }
        
        cellularMonitor?.startMonitoring()
        cellularStatus.value = SubsystemStatus.Active
        Log.d(TAG, "Cellular monitoring started")
        
        // Collect cellular status updates
        cellularStatusJob = serviceScope.launch {
            cellularMonitor?.cellStatus?.collect { status ->
                Companion.cellStatus.value = status
                broadcastCellularData()
            }
        }

        // Collect seen cell tower history
        cellularHistoryJob = serviceScope.launch {
            cellularMonitor?.seenCellTowers?.collect { towers ->
                seenCellTowers.value = towers
                broadcastCellularData()
            }
        }

        // Collect cellular timeline events
        cellularEventsJob = serviceScope.launch {
            cellularMonitor?.cellularEvents?.collect { events ->
                cellularEvents.value = events
                broadcastCellularData()
            }
        }
        
        // Collect cellular anomalies and convert to detections
        cellularAnomalyJob = serviceScope.launch {
            cellularMonitor?.anomalies?.collect { anomalies ->
                cellularAnomalies.value = anomalies
                broadcastCellularData()

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendCellularAnomalyBroadcast(anomaly)

                    // Convert anomaly to detection
                    val detection = cellularMonitor?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Check if we already have this detection (use unique SSID)
                        val existing = det.ssid?.let { repository.getDetectionBySsid(it) }
                        if (existing == null) {
                            try {
                                repository.insertDetection(det)

                                // Alert and vibrate for high-severity anomalies
                                if (anomaly.severity == ThreatLevel.CRITICAL ||
                                    anomaly.severity == ThreatLevel.HIGH) {
                                    alertUser(det)
                                }

                                lastDetection.value = det
                                detectionCount.value = repository.getTotalDetectionCount()
                                broadcastLastDetection()
                                broadcastStateToClients()
                                broadcastDetectionRefresh()

                                if (BuildConfig.DEBUG) {
                                    Log.w(TAG, "CELLULAR ANOMALY: ${anomaly.type.displayName} - ${anomaly.description}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving cellular detection: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        }
        
        // Also update cellular location when we get GPS updates
        cellularLocationJob = serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    cellularMonitor?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }

    private fun stopCellularMonitoring() {
        cellularLocationJob?.cancel()
        cellularLocationJob = null
        cellularAnomalyJob?.cancel()
        cellularAnomalyJob = null
        cellularStatusJob?.cancel()
        cellularStatusJob = null
        cellularHistoryJob?.cancel()
        cellularHistoryJob = null
        cellularEventsJob?.cancel()
        cellularEventsJob = null
        cellularMonitor?.stopMonitoring()
        Log.d(TAG, "Cellular monitoring stopped")
    }
    
    // ==================== Satellite Monitoring ====================
    
    private var satelliteStateJob: Job? = null
    private var satelliteAnomalyJob: Job? = null
    
    @SuppressLint("MissingPermission")
    private fun startSatelliteMonitoring() {
        if (!hasTelephonyPermissions()) {
            satelliteStatus.value = SubsystemStatus.PermissionDenied("READ_PHONE_STATE")
            Log.w(TAG, "Missing telephony permissions for satellite monitoring")
            return
        }

        Log.d(TAG, "Starting satellite monitoring")
        satelliteStatus.value = SubsystemStatus.Active

        satelliteMonitor?.startMonitoring()
        
        // Collect satellite state updates
        satelliteStateJob = serviceScope.launch {
            satelliteMonitor?.satelliteState?.collect { state ->
                satelliteState.value = state
                broadcastSatelliteData()
                Log.d(TAG, "Satellite state updated: connected=${state.isConnected}, type=${state.connectionType}")
            }
        }

        // Collect satellite anomalies
        satelliteAnomalyJob = serviceScope.launch {
            satelliteMonitor?.anomalies?.collect { anomaly ->
                Log.d(TAG, "Satellite anomaly detected: ${anomaly.type} - ${anomaly.severity}")

                // Send broadcast for automation apps
                sendSatelliteAnomalyBroadcast(anomaly)

                // Add to anomaly list
                val currentAnomalies = satelliteAnomalies.value.toMutableList()
                currentAnomalies.add(0, anomaly)
                if (currentAnomalies.size > 100) {
                    currentAnomalies.removeLast()
                }
                satelliteAnomalies.value = currentAnomalies
                broadcastSatelliteData()
                
                // Convert high severity anomalies to detections
                if (anomaly.severity in listOf(
                    com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.HIGH,
                    com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.CRITICAL
                )) {
                    val detection = satelliteAnomalyToDetection(anomaly)
                    if (detection != null) {
                        serviceScope.launch {
                            handleDetection(detection)
                        }
                    }
                }
            }
        }
    }
    
    private fun stopSatelliteMonitoring() {
        satelliteStateJob?.cancel()
        satelliteStateJob = null
        satelliteAnomalyJob?.cancel()
        satelliteAnomalyJob = null
        satelliteMonitor?.stopMonitoring()
        Log.d(TAG, "Satellite monitoring stopped")
    }
    
    private fun satelliteAnomalyToDetection(anomaly: com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly): Detection? {
        val threatLevel = when (anomaly.severity) {
            com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.CRITICAL -> ThreatLevel.CRITICAL
            com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.HIGH -> ThreatLevel.HIGH
            com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.MEDIUM -> ThreatLevel.MEDIUM
            com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.LOW -> ThreatLevel.LOW
            com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.INFO -> ThreatLevel.LOW
        }
        
        val detectionMethod = when (anomaly.type) {
            com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION,
            com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
            com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF -> DetectionMethod.SAT_FORCED_HANDOFF
            com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE -> DetectionMethod.SAT_DOWNGRADE
            com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType.SUSPICIOUS_NTN_PARAMETERS,
            com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType.NTN_BAND_MISMATCH -> DetectionMethod.SAT_SUSPICIOUS_NTN
            com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY,
            com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomalyType.EPHEMERIS_MISMATCH -> DetectionMethod.SAT_TIMING_ANOMALY
            else -> DetectionMethod.SAT_UNEXPECTED_CONNECTION
        }
        
        return Detection(
            id = UUID.randomUUID().toString(),
            timestamp = anomaly.timestamp,
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = detectionMethod,
            deviceType = DeviceType.STINGRAY_IMSI,
            deviceName = "🛰️ ${anomaly.type.name.replace("_", " ")}",
            rssi = -100, // Unknown for satellite
            signalStrength = SignalStrength.UNKNOWN,
            latitude = currentLocation?.latitude,
            longitude = currentLocation?.longitude,
            threatLevel = threatLevel,
            threatScore = when (anomaly.severity) {
                com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.CRITICAL -> 100
                com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.HIGH -> 80
                com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.MEDIUM -> 50
                com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.LOW -> 30
                com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.INFO -> 10
            },
            manufacturer = "Satellite Network",
            matchedPatterns = anomaly.description
        )
    }
    
    private fun hasTelephonyPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ==================== Rogue WiFi Monitoring ====================

    private var rogueWifiStatusJob: Job? = null
    private var rogueWifiAnomalyJob: Job? = null
    private var rogueWifiEventsJob: Job? = null

    private fun startRogueWifiMonitoring() {
        Log.d(TAG, "Starting rogue WiFi monitoring")

        rogueWifiMonitor?.startMonitoring()

        // Collect status updates
        rogueWifiStatusJob = serviceScope.launch {
            rogueWifiMonitor?.wifiStatus?.collect { status ->
                Companion.rogueWifiStatus.value = status
                broadcastRogueWifiData()
            }
        }

        // Collect suspicious networks
        suspiciousNetworksJob = serviceScope.launch {
            rogueWifiMonitor?.suspiciousNetworks?.collect { networks ->
                Companion.suspiciousNetworks.value = networks
                broadcastRogueWifiData()
            }
        }

        // Collect events
        rogueWifiEventsJob = serviceScope.launch {
            rogueWifiMonitor?.wifiEvents?.collect { events ->
                Companion.rogueWifiEvents.value = events
                broadcastRogueWifiData()
            }
        }

        // Collect anomalies and convert to detections
        rogueWifiAnomalyJob = serviceScope.launch {
            rogueWifiMonitor?.anomalies?.collect { anomalies ->
                Companion.rogueWifiAnomalies.value = anomalies
                broadcastRogueWifiData()

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendWifiAnomalyBroadcast(anomaly)

                    val detection = rogueWifiMonitor?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Check if we already have this detection
                        val existing = det.macAddress?.let { repository.getDetectionByMacAddress(it) }
                            ?: det.ssid?.let { repository.getDetectionBySsid(it) }

                        if (existing == null) {
                            try {
                                repository.insertDetection(det)

                                if (anomaly.severity == ThreatLevel.CRITICAL ||
                                    anomaly.severity == ThreatLevel.HIGH) {
                                    alertUser(det)
                                }

                                lastDetection.value = det
                                detectionCount.value = repository.getTotalDetectionCount()
                                broadcastLastDetection()
                                broadcastStateToClients()
                                broadcastDetectionRefresh()

                                if (BuildConfig.DEBUG) {
                                    Log.w(TAG, "WIFI ANOMALY: ${anomaly.type.displayName} - ${anomaly.description}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving WiFi detection: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        }

        // Update monitor location when GPS updates
        rogueWifiLocationJob = serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    rogueWifiMonitor?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }

    private fun stopRogueWifiMonitoring() {
        rogueWifiLocationJob?.cancel()
        rogueWifiLocationJob = null
        suspiciousNetworksJob?.cancel()
        suspiciousNetworksJob = null
        rogueWifiStatusJob?.cancel()
        rogueWifiStatusJob = null
        rogueWifiAnomalyJob?.cancel()
        rogueWifiAnomalyJob = null
        rogueWifiEventsJob?.cancel()
        rogueWifiEventsJob = null
        rogueWifiMonitor?.stopMonitoring()
        Log.d(TAG, "Rogue WiFi monitoring stopped")
    }

    // ==================== RF Signal Analysis ====================

    private var rfStatusJob: Job? = null
    private var rfAnomalyJob: Job? = null
    private var rfEventsJob: Job? = null
    private var rfDronesJob: Job? = null

    private fun startRfSignalAnalysis() {
        Log.d(TAG, "Starting RF signal analysis")

        rfSignalAnalyzer?.startMonitoring()

        // Collect status updates
        rfStatusJob = serviceScope.launch {
            rfSignalAnalyzer?.rfStatus?.collect { status ->
                Companion.rfStatus.value = status
                broadcastRfData()
            }
        }

        // Collect events
        rfEventsJob = serviceScope.launch {
            rfSignalAnalyzer?.rfEvents?.collect { events ->
                Companion.rfEvents.value = events
                broadcastRfData()
            }
        }

        // Collect detected drones
        rfDronesJob = serviceScope.launch {
            rfSignalAnalyzer?.dronesDetected?.collect { drones ->
                Companion.detectedDrones.value = drones
                broadcastRfData()

                // Convert new drones to detections
                for (drone in drones) {
                    val detection = rfSignalAnalyzer?.droneToDetection(drone)
                    detection?.let { det ->
                        val existing = det.macAddress?.let { repository.getDetectionByMacAddress(it) }
                        if (existing == null) {
                            try {
                                repository.insertDetection(det)
                                alertUser(det)
                                lastDetection.value = det
                                detectionCount.value = repository.getTotalDetectionCount()
                                broadcastLastDetection()
                                broadcastStateToClients()
                                broadcastDetectionRefresh()
                                Log.w(TAG, "DRONE DETECTED: ${drone.manufacturer} at ${drone.estimatedDistance}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving drone detection: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        }

        // Collect anomalies and convert to detections
        rfAnomalyJob = serviceScope.launch {
            rfSignalAnalyzer?.anomalies?.collect { anomalies ->
                Companion.rfAnomalies.value = anomalies
                broadcastRfData()

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendRfAnomalyBroadcast(anomaly)

                    val detection = rfSignalAnalyzer?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Use timestamp-based unique ID for RF anomalies
                        val existing = repository.getDetectionBySsid(det.deviceName ?: "")
                        if (existing == null) {
                            try {
                                repository.insertDetection(det)

                                if (anomaly.severity == ThreatLevel.CRITICAL ||
                                    anomaly.severity == ThreatLevel.HIGH) {
                                    alertUser(det)
                                }

                                lastDetection.value = det
                                detectionCount.value = repository.getTotalDetectionCount()
                                broadcastLastDetection()
                                broadcastStateToClients()
                                broadcastDetectionRefresh()

                                if (BuildConfig.DEBUG) {
                                    Log.w(TAG, "RF ANOMALY: ${anomaly.type.displayName} - ${anomaly.description}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving RF detection: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        }

        // Update analyzer location
        rfLocationJob = serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    rfSignalAnalyzer?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }

    private fun stopRfSignalAnalysis() {
        rfLocationJob?.cancel()
        rfLocationJob = null
        rfStatusJob?.cancel()
        rfStatusJob = null
        rfAnomalyJob?.cancel()
        rfAnomalyJob = null
        rfEventsJob?.cancel()
        rfEventsJob = null
        rfDronesJob?.cancel()
        rfDronesJob = null
        rfSignalAnalyzer?.stopMonitoring()
        Log.d(TAG, "RF signal analysis stopped")
    }

    // ==================== Ultrasonic Detection ====================

    private var ultrasonicStatusJob: Job? = null
    private var ultrasonicAnomalyJob: Job? = null
    private var ultrasonicEventsJob: Job? = null
    private var ultrasonicBeaconsJob: Job? = null

    private fun startUltrasonicDetection() {
        // Double-check that user has opted in with consent
        if (!currentPrivacySettings.ultrasonicDetectionEnabled || !currentPrivacySettings.ultrasonicConsentAcknowledged) {
            Log.w(TAG, "Ultrasonic detection not enabled - user must opt-in via Privacy settings")
            return
        }

        if (!hasAudioPermissions()) {
            Log.w(TAG, "Missing audio permissions for ultrasonic detection")
            return
        }

        Log.d(TAG, "Starting ultrasonic beacon detection (user consented, audio encrypted in memory)")

        ultrasonicDetector?.startMonitoring()

        // Collect status updates
        ultrasonicStatusJob = serviceScope.launch {
            ultrasonicDetector?.status?.collect { status ->
                Companion.ultrasonicStatus.value = status
                broadcastUltrasonicData()
            }
        }

        // Collect events
        ultrasonicEventsJob = serviceScope.launch {
            ultrasonicDetector?.events?.collect { events ->
                Companion.ultrasonicEvents.value = events
                broadcastUltrasonicData()
            }
        }

        // Collect active beacons
        ultrasonicBeaconsJob = serviceScope.launch {
            ultrasonicDetector?.beaconsDetected?.collect { beacons ->
                Companion.ultrasonicBeacons.value = beacons
                broadcastUltrasonicData()
            }
        }

        // Collect anomalies and convert to detections
        ultrasonicAnomalyJob = serviceScope.launch {
            ultrasonicDetector?.anomalies?.collect { anomalies ->
                Companion.ultrasonicAnomalies.value = anomalies
                broadcastUltrasonicData()

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendUltrasonicAnomalyBroadcast(anomaly)

                    val detection = ultrasonicDetector?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Use frequency as unique identifier
                        val existing = det.ssid?.let { repository.getDetectionBySsid(it) }
                        if (existing == null) {
                            try {
                                repository.insertDetection(det)

                                if (anomaly.severity == ThreatLevel.CRITICAL ||
                                    anomaly.severity == ThreatLevel.HIGH) {
                                    alertUser(det)
                                }

                                lastDetection.value = det
                                detectionCount.value = repository.getTotalDetectionCount()
                                broadcastLastDetection()
                                broadcastStateToClients()
                                broadcastDetectionRefresh()

                                Log.w(TAG, "ULTRASONIC: ${anomaly.type.displayName} - ${anomaly.frequency}Hz")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving ultrasonic detection: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        }

        // Update detector location
        ultrasonicLocationJob = serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    ultrasonicDetector?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }

    private fun stopUltrasonicDetection() {
        ultrasonicLocationJob?.cancel()
        ultrasonicLocationJob = null
        ultrasonicStatusJob?.cancel()
        ultrasonicStatusJob = null
        ultrasonicAnomalyJob?.cancel()
        ultrasonicAnomalyJob = null
        ultrasonicEventsJob?.cancel()
        ultrasonicEventsJob = null
        ultrasonicBeaconsJob?.cancel()
        ultrasonicBeaconsJob = null
        ultrasonicDetector?.stopMonitoring()
        Log.d(TAG, "Ultrasonic detection stopped")
    }

    // ==================== GNSS Satellite Monitoring ====================

    private var gnssStatusJob: Job? = null
    private var gnssAnomalyJob: Job? = null
    private var gnssEventsJob: Job? = null
    private var gnssSatellitesJob: Job? = null
    private var gnssMeasurementsJob: Job? = null

    @SuppressLint("MissingPermission")
    private fun startGnssMonitoring() {
        if (!hasLocationPermissions()) {
            gnssMonitorStatus.value = SubsystemStatus.PermissionDenied("ACCESS_FINE_LOCATION")
            Log.w(TAG, "Missing location permissions for GNSS monitoring")
            return
        }

        Log.d(TAG, "Starting GNSS satellite monitoring for spoofing/jamming detection")
        gnssMonitorStatus.value = SubsystemStatus.Active

        gnssSatelliteMonitor?.startMonitoring()

        // Collect status updates
        gnssStatusJob = serviceScope.launch {
            gnssSatelliteMonitor?.gnssStatus?.collect { status ->
                Companion.gnssStatus.value = status
                broadcastGnssData()
            }
        }

        // Collect satellite info
        gnssSatellitesJob = serviceScope.launch {
            gnssSatelliteMonitor?.satellites?.collect { sats ->
                Companion.gnssSatellites.value = sats
                broadcastGnssData()
            }
        }

        // Collect events
        gnssEventsJob = serviceScope.launch {
            gnssSatelliteMonitor?.events?.collect { events ->
                Companion.gnssEvents.value = events
                broadcastGnssData()
            }
        }

        // Collect raw measurements
        gnssMeasurementsJob = serviceScope.launch {
            gnssSatelliteMonitor?.measurements?.collect { measurements ->
                Companion.gnssMeasurements.value = measurements
                broadcastGnssData()
            }
        }

        // Collect anomalies and convert to detections
        gnssAnomalyJob = serviceScope.launch {
            gnssSatelliteMonitor?.anomalies?.collect { anomalies ->
                Companion.gnssAnomalies.value = anomalies
                broadcastGnssData()

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendGnssAnomalyBroadcast(anomaly)

                    val detection = gnssSatelliteMonitor?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Use anomaly ID as unique identifier
                        val existing = repository.getDetectionByMacAddress(anomaly.id)
                        if (existing == null) {
                            try {
                                repository.insertDetection(det.copy(macAddress = anomaly.id))

                                if (anomaly.severity == ThreatLevel.CRITICAL ||
                                    anomaly.severity == ThreatLevel.HIGH) {
                                    alertUser(det)
                                }

                                lastDetection.value = det
                                detectionCount.value = repository.getTotalDetectionCount()
                                broadcastLastDetection()
                                broadcastStateToClients()
                                broadcastDetectionRefresh()

                                Log.w(TAG, "GNSS: ${anomaly.type.displayName} - ${anomaly.description}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving GNSS detection: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        }

        // Update monitor location
        gnssLocationJob = serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    gnssSatelliteMonitor?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }

    private fun stopGnssMonitoring() {
        gnssLocationJob?.cancel()
        gnssLocationJob = null
        gnssStatusJob?.cancel()
        gnssStatusJob = null
        gnssAnomalyJob?.cancel()
        gnssAnomalyJob = null
        gnssEventsJob?.cancel()
        gnssEventsJob = null
        gnssSatellitesJob?.cancel()
        gnssSatellitesJob = null
        gnssMeasurementsJob?.cancel()
        gnssMeasurementsJob = null
        gnssSatelliteMonitor?.stopMonitoring()
        gnssMonitorStatus.value = SubsystemStatus.Idle
        Log.d(TAG, "GNSS monitoring stopped")
    }

    // ==================== BLE Scanning ====================
    
    @SuppressLint("MissingPermission")
    private fun startBleScan(aggressiveMode: Boolean = true) {
        if (!hasBluetoothPermissions()) {
            bleStatus.value = SubsystemStatus.PermissionDenied("BLUETOOTH_SCAN")
            Log.w(TAG, "Missing Bluetooth permissions")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            bleStatus.value = SubsystemStatus.Disabled
            Log.w(TAG, "Bluetooth is disabled")
            return
        }

        // Always stop first to ensure clean state and prevent "scan already started" errors
        if (isBleScanningActive) {
            stopBleScan()
        }

        // Build aggressive scan settings for maximum detection capability
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // Continuous radio usage - highest detection rate
            .setReportDelay(0)  // Immediate results, no batching
            .apply {
                // MATCH_MODE_AGGRESSIVE reports devices with weak signals that might otherwise be filtered
                // This is critical for detecting fast-moving vehicles or distant surveillance equipment
                if (aggressiveMode) {
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                }
            }
            .build()

        try {
            bleScanner?.startScan(null, scanSettings, bleScanCallback)
            isBleScanningActive = true
            bleStatus.value = SubsystemStatus.Active
            Log.d(TAG, "BLE scan started (aggressive=$aggressiveMode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            bleStatus.value = SubsystemStatus.Error(-1, e.message ?: "Unknown error")
            logError("BLE", -1, "Failed to start scan: ${e.message}", recoverable = true)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isBleScanningActive) return
        
        try {
            bleScanner?.stopScan(bleScanCallback)
            isBleScanningActive = false
            Log.d(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE scan", e)
        }
    }
    
    /** BLE scan callback - handles scan results for surveillance device detection */
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            serviceScope.launch {
                processBleScanResult(result)
            }
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            serviceScope.launch {
                results.forEach { processBleScanResult(it) }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Out of hardware resources"
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scanning too frequently"
                else -> "Unknown error"
            }
            Log.e(TAG, "[BLE] Error $errorCode: $errorMessage")

            // Handle SCAN_FAILED_ALREADY_STARTED specially - the scan IS running
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                // Scan is already active, mark it as such and don't log as error
                isBleScanningActive = true
                bleStatus.value = SubsystemStatus.Active
                return
            }

            bleStatus.value = SubsystemStatus.Error(errorCode, errorMessage)
            logError("BLE", errorCode, errorMessage, recoverable = errorCode != SCAN_FAILED_FEATURE_UNSUPPORTED)
            isBleScanningActive = false
        }
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun processBleScanResult(result: ScanResult) {
        val device = result.device
        val macAddress = device.address ?: return
        val deviceName = device.name
        val rssi = result.rssi
        val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

        // Extract manufacturer data for Axon Signal trigger detection
        // Manufacturer ID 0x004C = Apple (often used as wrapper)
        // Manufacturer ID 0x0059 = Nordic Semiconductor (used in Axon devices)
        val manufacturerData = mutableMapOf<Int, String>()
        result.scanRecord?.manufacturerSpecificData?.let { data ->
            for (i in 0 until data.size()) {
                val key = data.keyAt(i)
                val value = data.valueAt(i)
                manufacturerData[key] = value.joinToString("") { "%02X".format(it) }
            }
        }

        // Track packet rate for Signal trigger spike detection
        val advertisingRate = trackPacket(macAddress)

        // Check for advertising rate spike (Signal trigger activation)
        // Axon Signal devices advertise every ~1000ms normally, but spike to ~20-50ms when activated
        if (advertisingRate >= HIGH_ACTIVITY_THRESHOLD) {
            // Check if this is a Nordic Semiconductor device (common in Axon equipment)
            val isNordic = manufacturerData.containsKey(0x0059)
            val isAppleWrapper = manufacturerData.containsKey(0x004C)

            if (isNordic || isAppleWrapper) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "HIGH ADVERTISING RATE DETECTED: $macAddress ($advertisingRate pps) - possible Signal trigger activation!")
                }

                // Create a detection for this event
                val detection = Detection(
                    protocol = DetectionProtocol.BLUETOOTH_LE,
                    detectionMethod = DetectionMethod.BLE_SERVICE_UUID,
                    deviceType = DeviceType.AXON_POLICE_TECH,
                    deviceName = deviceName ?: "Signal Trigger (Active)",
                    macAddress = macAddress,
                    rssi = rssi,
                    signalStrength = rssiToSignalStrength(rssi),
                    latitude = currentLocation?.latitude,
                    longitude = currentLocation?.longitude,
                    threatLevel = ThreatLevel.CRITICAL,
                    threatScore = 95,
                    manufacturer = if (isNordic) "Nordic Semiconductor (Axon)" else "Apple BLE Wrapper",
                    matchedPatterns = gson.toJson(listOf(
                        "Advertising spike: ${advertisingRate.toInt()} packets/sec",
                        "Possible siren/gun draw activation"
                    ))
                )

                handleDetection(detection)
            }
        }

        // Update scan stats
        scanStats.value = scanStats.value.copy(
            bleDevicesSeen = scanStats.value.bleDevicesSeen + 1,
            lastBleSuccessTime = System.currentTimeMillis()
        )
        
        // Check for Raven device (by service UUIDs)
        if (DetectionPatterns.isRavenDevice(serviceUuids)) {
            val matchedServices = DetectionPatterns.matchRavenServices(serviceUuids)
            val firmwareVersion = DetectionPatterns.estimateRavenFirmwareVersion(serviceUuids)
            
            val detection = Detection(
                protocol = DetectionProtocol.BLUETOOTH_LE,
                detectionMethod = DetectionMethod.RAVEN_SERVICE_UUID,
                deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
                deviceName = deviceName,
                macAddress = macAddress,
                ssid = null,
                rssi = rssi,
                signalStrength = rssiToSignalStrength(rssi),
                latitude = currentLocation?.latitude,
                longitude = currentLocation?.longitude,
                threatLevel = ThreatLevel.CRITICAL,
                threatScore = 100,
                manufacturer = "SoundThinking/ShotSpotter",
                firmwareVersion = firmwareVersion,
                serviceUuids = gson.toJson(serviceUuids.map { it.toString() }),
                matchedPatterns = gson.toJson(matchedServices.map { it.description })
            )
            
            handleDetection(detection)
            return
        }
        
        // Check for device name pattern match
        if (deviceName != null) {
            val pattern = DetectionPatterns.matchBleNamePattern(deviceName)
            if (pattern != null) {
                val detection = Detection(
                    protocol = DetectionProtocol.BLUETOOTH_LE,
                    detectionMethod = DetectionMethod.BLE_DEVICE_NAME,
                    deviceType = pattern.deviceType,
                    deviceName = deviceName,
                    macAddress = macAddress,
                    ssid = null,
                    rssi = rssi,
                    signalStrength = rssiToSignalStrength(rssi),
                    latitude = currentLocation?.latitude,
                    longitude = currentLocation?.longitude,
                    threatLevel = scoreToThreatLevel(pattern.threatScore),
                    threatScore = pattern.threatScore,
                    manufacturer = pattern.manufacturer,
                    firmwareVersion = null,
                    serviceUuids = gson.toJson(serviceUuids.map { it.toString() }),
                    matchedPatterns = gson.toJson(listOf(pattern.description))
                )
                
                handleDetection(detection)
                return
            }
        }
        
        // Check for MAC prefix match
        val macPrefix = DetectionPatterns.matchMacPrefix(macAddress)
        if (macPrefix != null) {
            val detection = Detection(
                protocol = DetectionProtocol.BLUETOOTH_LE,
                detectionMethod = DetectionMethod.MAC_PREFIX,
                deviceType = macPrefix.deviceType,
                deviceName = deviceName,
                macAddress = macAddress,
                ssid = null,
                rssi = rssi,
                signalStrength = rssiToSignalStrength(rssi),
                latitude = currentLocation?.latitude,
                longitude = currentLocation?.longitude,
                threatLevel = scoreToThreatLevel(macPrefix.threatScore),
                threatScore = macPrefix.threatScore,
                manufacturer = macPrefix.manufacturer,
                firmwareVersion = null,
                serviceUuids = gson.toJson(serviceUuids.map { it.toString() }),
                matchedPatterns = gson.toJson(listOf(macPrefix.description.ifEmpty { "MAC prefix: ${macPrefix.prefix}" }))
            )
            
            handleDetection(detection)
            return
        }
        
        // No match - track as seen device if enabled
        if (currentSettings.value.trackSeenDevices) {
            trackSeenBleDevice(macAddress, deviceName, rssi, serviceUuids, manufacturerData, advertisingRate)
        }

        // In learning mode, check if this device matches any learned signatures
        if (learningModeEnabled.value) {
            checkLearnedSignatures(macAddress, deviceName, rssi, serviceUuids, manufacturerData)
        }
    }

    private suspend fun checkLearnedSignatures(
        macAddress: String,
        deviceName: String?,
        rssi: Int,
        serviceUuids: List<java.util.UUID>,
        manufacturerData: Map<Int, String>
    ) {
        val macPrefix = macAddress.take(8).uppercase()
        val uuidStrings = serviceUuids.map { it.toString() }

        for (signature in learnedSignatures.value) {
            val matchesPrefix = signature.macPrefix == macPrefix
            val matchesUuids = signature.serviceUuids.isNotEmpty() &&
                    signature.serviceUuids.any { it in uuidStrings }
            val matchesMfg = signature.manufacturerIds.isNotEmpty() &&
                    signature.manufacturerIds.any { manufacturerData.containsKey(it) }

            if (matchesPrefix || matchesUuids || matchesMfg) {
                Log.w(TAG, "LEARNED SIGNATURE MATCH: $macAddress matches ${signature.id}")

                val detection = Detection(
                    protocol = DetectionProtocol.BLUETOOTH_LE,
                    detectionMethod = DetectionMethod.BLE_DEVICE_NAME,
                    deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
                    deviceName = deviceName ?: signature.name,
                    macAddress = macAddress,
                    rssi = rssi,
                    signalStrength = rssiToSignalStrength(rssi),
                    latitude = currentLocation?.latitude,
                    longitude = currentLocation?.longitude,
                    threatLevel = ThreatLevel.HIGH,
                    threatScore = 85,
                    manufacturer = "Learned Signature",
                    matchedPatterns = gson.toJson(listOf(
                        "Matches learned signature: ${signature.id}",
                        signature.notes ?: "User-confirmed suspicious device"
                    ))
                )

                handleDetection(detection)
                break
            }
        }
    }

    private fun trackSeenBleDevice(
        macAddress: String,
        deviceName: String?,
        rssi: Int,
        serviceUuids: List<java.util.UUID>,
        manufacturerData: Map<Int, String> = emptyMap(),
        advertisingRate: Float = 0f
    ) {
        // Synchronize to prevent race conditions when multiple scan results arrive concurrently
        synchronized(seenDevicesLock) {
            val currentList = seenBleDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.id == macAddress }

            if (existingIndex >= 0) {
                // Update existing
                val existing = currentList[existingIndex]
                currentList[existingIndex] = existing.copy(
                    name = deviceName ?: existing.name,
                    rssi = rssi,
                    lastSeen = System.currentTimeMillis(),
                    seenCount = existing.seenCount + 1,
                    manufacturerData = if (manufacturerData.isNotEmpty()) manufacturerData else existing.manufacturerData,
                    advertisingRate = advertisingRate
                )
            } else {
                // Add new
                val manufacturer = try {
                    // Try to identify manufacturer from MAC OUI
                    val oui = macAddress.take(8).uppercase()
                    DetectionPatterns.getManufacturerFromOui(oui)
                } catch (e: Exception) { null }

                currentList.add(0, SeenDevice(
                    id = macAddress,
                    name = deviceName,
                    type = "BLE",
                    rssi = rssi,
                    manufacturer = manufacturer,
                    serviceUuids = serviceUuids.map { it.toString() },
                    manufacturerData = manufacturerData,
                    advertisingRate = advertisingRate
                ))

                // Limit list size
                if (currentList.size > MAX_SEEN_DEVICES) {
                    currentList.removeAt(currentList.lastIndex)
                }
            }

            seenBleDevices.value = currentList
            broadcastSeenBleDevices()
        }
    }

    // ==================== WiFi Scanning ====================
    
    @SuppressLint("MissingPermission")
    private fun startWifiScan() {
        if (!hasLocationPermissions()) {
            wifiStatus.value = SubsystemStatus.PermissionDenied("ACCESS_FINE_LOCATION")
            Log.w(TAG, "Missing location permissions for WiFi scan")
            return
        }
        
        if (!wifiManager.isWifiEnabled) {
            wifiStatus.value = SubsystemStatus.Disabled
            Log.w(TAG, "WiFi is disabled")
            return
        }
        
        // Update total scan attempts
        scanStats.value = scanStats.value.copy(
            totalWifiScans = scanStats.value.totalWifiScans + 1
        )
        
        try {
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (started) {
                wifiStatus.value = SubsystemStatus.Active
                Log.d(TAG, "WiFi scan started")
            } else {
                // Rejection is expected due to Android throttling - don't spam errors
                Log.d(TAG, "WiFi scan request rejected (throttled)")
                // Status will be updated by the receiver
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WiFi scan", e)
            wifiStatus.value = SubsystemStatus.Error(-1, e.message ?: "Unknown error")
            logError("WiFi", -1, "Failed to start scan: ${e.message}", recoverable = true)
        }
    }
    
    private fun registerWifiReceiver() {
        if (wifiScanReceiver != null) return
        
        wifiScanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    if (success) {
                        wifiStatus.value = SubsystemStatus.Active
                        serviceScope.launch {
                            processWifiScanResults()
                        }
                    } else {
                        // Scan failed or was throttled - update stats but don't spam errors
                        val stats = scanStats.value
                        scanStats.value = stats.copy(
                            throttledWifiScans = stats.throttledWifiScans + 1
                        )
                        
                        // Only log throttle error once per minute to reduce spam
                        val lastThrottle = lastWifiThrottleLogTime
                        val now = System.currentTimeMillis()
                        if (lastThrottle == null || now - lastThrottle > 60000) {
                            lastWifiThrottleLogTime = now
                            wifiStatus.value = SubsystemStatus.Error(-2, "Throttled (${stats.throttledWifiScans + 1}x)")
                            logError("WiFi", -2, "WiFi scan throttled by system (Android limits: 4 scans/2min)", recoverable = true)
                        }
                    }
                }
            }
        }
        
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
    }
    
    private var lastWifiThrottleLogTime: Long? = null
    
    private fun unregisterWifiReceiver() {
        wifiScanReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering WiFi receiver", e)
            }
        }
        wifiScanReceiver = null
    }

    // ==================== Nuke Receiver ====================

    /**
     * Register a receiver to handle nuke broadcasts from the main process.
     * This allows graceful shutdown of database connections before the nuke wipes files.
     */
    private fun registerNukeReceiver() {
        if (nukeReceiver != null) return

        nukeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_NUKE_INITIATED -> {
                        Log.w(TAG, "NUKE BROADCAST RECEIVED - initiating graceful shutdown")
                        handleNukeInitiated()
                    }
                    ACTION_DATABASE_SHUTDOWN -> {
                        Log.w(TAG, "DATABASE SHUTDOWN BROADCAST RECEIVED - closing database")
                        handleDatabaseShutdown()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_NUKE_INITIATED)
            addAction(ACTION_DATABASE_SHUTDOWN)
        }

        registerReceiver(nukeReceiver, filter, RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Nuke receiver registered")
    }

    /**
     * Unregister the nuke receiver.
     */
    private fun unregisterNukeReceiver() {
        nukeReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Nuke receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering nuke receiver", e)
            }
        }
        nukeReceiver = null
    }

    /**
     * Handle nuke initiated broadcast - stop all scanning and close database.
     */
    private fun handleNukeInitiated() {
        // Mark database as unavailable to prevent further writes
        isDatabaseAvailable.value = false

        // Stop all scanning operations immediately
        serviceScope.launch {
            try {
                Log.w(TAG, "Stopping scanning due to nuke")
                stopScanning()

                // Close the database connection in this process
                closeDatabaseConnection()

                // Disable auto-restart so service doesn't come back after nuke
                BootReceiver.setServiceEnabled(this@ScanningService, false)
                ServiceRestartReceiver.cancelWatchdog(this@ScanningService)

                Log.w(TAG, "Graceful shutdown complete - stopping service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Error during nuke shutdown", e)
            }
        }
    }

    /**
     * Handle database shutdown broadcast - close database without stopping service.
     */
    private fun handleDatabaseShutdown() {
        isDatabaseAvailable.value = false
        closeDatabaseConnection()
    }

    /**
     * Close the database connection in this process.
     */
    private fun closeDatabaseConnection() {
        try {
            com.flockyou.data.repository.FlockYouDatabase.getDatabase(applicationContext).close()
            Log.d(TAG, "Database connection closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing database connection", e)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun processWifiScanResults() {
        if (!hasLocationPermissions()) return

        val results = wifiManager.scanResults
        Log.d(TAG, "Processing ${results.size} WiFi scan results")

        // Update scan stats
        scanStats.value = scanStats.value.copy(
            wifiNetworksSeen = scanStats.value.wifiNetworksSeen + results.size,
            successfulWifiScans = scanStats.value.successfulWifiScans + 1,
            lastWifiSuccessTime = System.currentTimeMillis()
        )

        // Feed results to Rogue WiFi Monitor for evil twin/rogue AP detection
        rogueWifiMonitor?.processScanResults(results)

        // Feed results to RF Signal Analyzer for spectrum analysis
        rfSignalAnalyzer?.analyzeWifiScan(results)
        
        for (result in results) {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
            } else {
                @Suppress("DEPRECATION")
                result.SSID ?: ""
            }
            
            val bssid = result.BSSID ?: continue
            val rssi = result.level
            
            var matched = false
            
            // Check for SSID pattern match
            val pattern = DetectionPatterns.matchSsidPattern(ssid)
            if (pattern != null) {
                val detection = Detection(
                    protocol = DetectionProtocol.WIFI,
                    detectionMethod = DetectionMethod.SSID_PATTERN,
                    deviceType = pattern.deviceType,
                    deviceName = null,
                    macAddress = bssid,
                    ssid = ssid,
                    rssi = rssi,
                    signalStrength = rssiToSignalStrength(rssi),
                    latitude = currentLocation?.latitude,
                    longitude = currentLocation?.longitude,
                    threatLevel = scoreToThreatLevel(pattern.threatScore),
                    threatScore = pattern.threatScore,
                    manufacturer = pattern.manufacturer,
                    firmwareVersion = null,
                    serviceUuids = null,
                    matchedPatterns = gson.toJson(listOf(pattern.description))
                )
                
                handleDetection(detection)
                matched = true
            }
            
            // Check for MAC prefix match
            if (!matched) {
                val macPrefix = DetectionPatterns.matchMacPrefix(bssid)
                if (macPrefix != null) {
                    val detection = Detection(
                        protocol = DetectionProtocol.WIFI,
                        detectionMethod = DetectionMethod.MAC_PREFIX,
                        deviceType = macPrefix.deviceType,
                        deviceName = null,
                        macAddress = bssid,
                        ssid = ssid,
                        rssi = rssi,
                        signalStrength = rssiToSignalStrength(rssi),
                        latitude = currentLocation?.latitude,
                        longitude = currentLocation?.longitude,
                        threatLevel = scoreToThreatLevel(macPrefix.threatScore),
                        threatScore = macPrefix.threatScore,
                        manufacturer = macPrefix.manufacturer,
                        firmwareVersion = null,
                        serviceUuids = null,
                        matchedPatterns = gson.toJson(listOf(macPrefix.description.ifEmpty { "MAC prefix: ${macPrefix.prefix}" }))
                    )
                    
                    handleDetection(detection)
                    matched = true
                }
            }
            
            // Track unmatched networks if enabled
            if (!matched && ssid.isNotEmpty() && currentSettings.value.trackSeenDevices) {
                trackSeenWifiNetwork(bssid, ssid, rssi)
            }
        }
    }
    
    private fun trackSeenWifiNetwork(bssid: String, ssid: String, rssi: Int) {
        val currentList = seenWifiNetworks.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == bssid }
        
        if (existingIndex >= 0) {
            val existing = currentList[existingIndex]
            currentList[existingIndex] = existing.copy(
                name = ssid,
                rssi = rssi,
                lastSeen = System.currentTimeMillis(),
                seenCount = existing.seenCount + 1
            )
        } else {
            val manufacturer = try {
                val oui = bssid.take(8).uppercase()
                DetectionPatterns.getManufacturerFromOui(oui)
            } catch (e: Exception) { null }
            
            currentList.add(0, SeenDevice(
                id = bssid,
                name = ssid,
                type = "WiFi",
                rssi = rssi,
                manufacturer = manufacturer
            ))
            
            if (currentList.size > MAX_SEEN_DEVICES) {
                currentList.removeAt(currentList.lastIndex)
            }
        }
        
        seenWifiNetworks.value = currentList
        broadcastSeenWifiNetworks()
    }

    // ==================== Detection Handling ====================

    /**
     * Apply privacy settings to a detection before storing.
     * - Strips location data if storeLocationWithDetections is disabled (Priority 4)
     */
    private fun applyPrivacySettings(detection: Detection): Detection {
        return if (!currentPrivacySettings.storeLocationWithDetections) {
            // Strip location data for privacy
            detection.copy(latitude = null, longitude = null)
        } else {
            detection
        }
    }

    private suspend fun handleDetection(detection: Detection) {
        // Check if database is available (might be unavailable during nuke)
        if (!isDatabaseAvailable.value) {
            Log.w(TAG, "Database unavailable - skipping detection save")
            return
        }

        try {
            // Apply privacy settings (strip location if disabled)
            val privacyAwareDetection = applyPrivacySettings(detection)

            // Choose storage based on ephemeral mode (Priority 1)
            val isNew = if (currentPrivacySettings.ephemeralModeEnabled) {
                // Ephemeral mode: store in RAM only
                ephemeralRepository.upsertDetection(privacyAwareDetection)
            } else {
                // Normal mode: persist to encrypted database
                repository.upsertDetection(privacyAwareDetection)
            }

            if (isNew) {
                // New detection
                detectionCount.value++
                lastDetection.value = privacyAwareDetection
                broadcastLastDetection()
                broadcastStateToClients()

                Log.d(TAG, "New detection: ${privacyAwareDetection.deviceType} - ${privacyAwareDetection.macAddress ?: privacyAwareDetection.ssid}")

                // Alert user
                alertUser(privacyAwareDetection)
            } else {
                // Existing detection - update lastDetection to refresh UI
                lastDetection.value = privacyAwareDetection
                broadcastLastDetection()
                Log.d(TAG, "Updated detection: ${privacyAwareDetection.deviceType} - ${privacyAwareDetection.macAddress ?: privacyAwareDetection.ssid}")
            }

            // Emit refresh event to ensure UI updates even if Room Flow doesn't trigger
            broadcastDetectionRefresh()
        } catch (e: android.database.sqlite.SQLiteException) {
            // Database error - likely corrupted or wiped
            Log.e(TAG, "SQLite error handling detection: ${e.message}", e)
            handleDatabaseError(e)
        } catch (e: Exception) {
            // Check for wrapped database errors
            if (isDatabaseError(e)) {
                Log.e(TAG, "Database error handling detection: ${e.message}", e)
                handleDatabaseError(e)
            } else {
                Log.e(TAG, "Error handling detection: ${e.message}", e)
                logError("Detection", 1001, "Failed to save detection: ${e.message}")
            }
        }
    }

    /**
     * Check if an exception is a database-related error.
     */
    private fun isDatabaseError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("database") ||
                message.contains("sqlite") ||
                message.contains("sqlcipher") ||
                message.contains("file is not a database") ||
                message.contains("out of memory") ||
                e.cause is android.database.sqlite.SQLiteException
    }

    /**
     * Handle database errors gracefully.
     * This typically happens when the database is wiped during a nuke operation.
     */
    private fun handleDatabaseError(e: Exception) {
        // Mark database as unavailable
        isDatabaseAvailable.value = false

        // Log the error
        logError("Database", 26, "Database error: ${e.message}", recoverable = false)

        // Switch to ephemeral mode to continue operation without persistent storage
        Log.w(TAG, "Switching to ephemeral storage due to database error")

        // Update scan status to indicate degraded operation
        scanStatus.value = ScanStatus.Error("Database unavailable - using memory storage", recoverable = true)
    }
    
    private fun alertUser(detection: Detection) {
        val notifSettings = currentNotificationSettings

        // Check if we should show emergency popup for CRITICAL threats
        if (detection.threatLevel == ThreatLevel.CRITICAL &&
            notifSettings.emergencyPopupEnabled &&
            Settings.canDrawOverlays(this)
        ) {
            showEmergencyPopup(detection)
            // Emergency popup handles its own sound and vibration, skip regular alert
            sendDetectionBroadcast(detection)
            return
        }

        // Vibrate based on threat level
        val pattern = when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> longArrayOf(0, 200, 100, 200, 100, 200)
            ThreatLevel.HIGH -> longArrayOf(0, 150, 100, 150, 100, 150)
            ThreatLevel.MEDIUM -> longArrayOf(0, 100, 100, 100)
            else -> longArrayOf(0, 100, 100)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }

        // Send notification
        sendDetectionNotification(detection)

        // Send broadcast for automation apps
        sendDetectionBroadcast(detection)
    }

    /**
     * Shows a full-screen CMAS/WEA-style emergency alert popup for critical threats.
     * This displays above the lock screen and plays an alarm sound.
     */
    private fun showEmergencyPopup(detection: Detection) {
        Log.w(TAG, "Showing emergency popup for CRITICAL detection: ${detection.deviceType}")

        val notifSettings = currentNotificationSettings

        val title = "SURVEILLANCE ALERT"
        val message = buildString {
            append("A ")
            append(detection.deviceType.name.replace("_", " "))
            append(" has been detected in your vicinity.\n\n")
            if (!detection.deviceName.isNullOrBlank()) {
                append("Device: ${detection.deviceName}\n")
            }
            if (!detection.ssid.isNullOrBlank()) {
                append("Network: ${detection.ssid}\n")
            }
            append("\nTake appropriate security measures.")
        }

        val intent = EmergencyAlertActivity.createIntent(
            context = this,
            title = title,
            message = message,
            deviceType = detection.deviceType.name.replace("_", " "),
            threatLevel = detection.threatLevel,
            detectionId = detection.id,
            playSound = notifSettings.sound,
            vibrate = notifSettings.vibrate
        )

        startActivity(intent)
    }

    private fun sendDetectionNotification(detection: Detection) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ Surveillance Device Detected!")
            .setContentText("${detection.deviceType.name.replace("_", " ")} - ${detection.threatLevel}")
            .setSmallIcon(R.drawable.ic_warning)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(detection.id.hashCode(), notification)
    }

    // ==================== Automation Broadcasts ====================

    /**
     * Send a broadcast for a detection event to automation apps (Tasker, Automate, etc.)
     */
    private fun sendDetectionBroadcast(detection: Detection) {
        val settings = currentBroadcastSettings
        if (!settings.enabled || !settings.broadcastOnDetection) return

        // Check minimum threat level
        if (!meetsMinThreatLevel(detection.threatLevel, settings.minThreatLevel)) return

        val intent = Intent(com.flockyou.data.BroadcastSettings.ACTION_DETECTION).apply {
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_DETECTION_ID, detection.id)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_DEVICE_TYPE, detection.deviceType.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_DEVICE_NAME, detection.deviceName)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_MAC_ADDRESS, detection.macAddress)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_SSID, detection.ssid)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_THREAT_LEVEL, detection.threatLevel.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_THREAT_SCORE, detection.threatScore)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_PROTOCOL, detection.protocol.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_DETECTION_METHOD, detection.detectionMethod.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_SIGNAL_STRENGTH, detection.signalStrength.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_RSSI, detection.rssi)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_TIMESTAMP, detection.timestamp)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_MANUFACTURER, detection.manufacturer)

            if (settings.includeLocation) {
                detection.latitude?.let { putExtra(com.flockyou.data.BroadcastSettings.EXTRA_LATITUDE, it) }
                detection.longitude?.let { putExtra(com.flockyou.data.BroadcastSettings.EXTRA_LONGITUDE, it) }
            }

            // Allow explicit receivers
            setPackage(null)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: ${com.flockyou.data.BroadcastSettings.ACTION_DETECTION} for ${detection.deviceType}")
    }

    /**
     * Send a broadcast for cellular anomaly events
     */
    fun sendCellularAnomalyBroadcast(anomaly: CellularMonitor.CellularAnomaly) {
        val settings = currentBroadcastSettings
        if (!settings.enabled || !settings.broadcastOnCellularAnomaly) return

        val intent = Intent(com.flockyou.data.BroadcastSettings.ACTION_CELLULAR_ANOMALY).apply {
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_TYPE, anomaly.type.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_DESCRIPTION, anomaly.description)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_THREAT_LEVEL, anomaly.severity.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_TIMESTAMP, anomaly.timestamp)
            setPackage(null)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: ${com.flockyou.data.BroadcastSettings.ACTION_CELLULAR_ANOMALY}")
    }

    /**
     * Send a broadcast for satellite anomaly events
     */
    fun sendSatelliteAnomalyBroadcast(anomaly: com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly) {
        val settings = currentBroadcastSettings
        if (!settings.enabled || !settings.broadcastOnSatelliteAnomaly) return

        val intent = Intent(com.flockyou.data.BroadcastSettings.ACTION_SATELLITE_ANOMALY).apply {
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_TYPE, anomaly.type.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_DESCRIPTION, anomaly.description)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_THREAT_LEVEL, anomaly.severity.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_TIMESTAMP, anomaly.timestamp)
            setPackage(null)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: ${com.flockyou.data.BroadcastSettings.ACTION_SATELLITE_ANOMALY}")
    }

    /**
     * Send a broadcast for WiFi anomaly events
     */
    fun sendWifiAnomalyBroadcast(anomaly: RogueWifiMonitor.WifiAnomaly) {
        val settings = currentBroadcastSettings
        if (!settings.enabled || !settings.broadcastOnWifiAnomaly) return

        val intent = Intent(com.flockyou.data.BroadcastSettings.ACTION_WIFI_ANOMALY).apply {
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_TYPE, anomaly.type.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_DESCRIPTION, anomaly.description)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_THREAT_LEVEL, anomaly.severity.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_TIMESTAMP, anomaly.timestamp)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_SSID, anomaly.ssid)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_MAC_ADDRESS, anomaly.bssid)
            setPackage(null)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: ${com.flockyou.data.BroadcastSettings.ACTION_WIFI_ANOMALY}")
    }

    /**
     * Send a broadcast for RF anomaly events
     */
    fun sendRfAnomalyBroadcast(anomaly: RfSignalAnalyzer.RfAnomaly) {
        val settings = currentBroadcastSettings
        if (!settings.enabled || !settings.broadcastOnRfAnomaly) return

        val intent = Intent(com.flockyou.data.BroadcastSettings.ACTION_RF_ANOMALY).apply {
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_TYPE, anomaly.type.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_DESCRIPTION, anomaly.description)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_THREAT_LEVEL, anomaly.severity.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_TIMESTAMP, anomaly.timestamp)
            setPackage(null)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: ${com.flockyou.data.BroadcastSettings.ACTION_RF_ANOMALY}")
    }

    /**
     * Send a broadcast for ultrasonic anomaly events
     */
    fun sendUltrasonicAnomalyBroadcast(anomaly: UltrasonicDetector.UltrasonicAnomaly) {
        val settings = currentBroadcastSettings
        if (!settings.enabled || !settings.broadcastOnUltrasonic) return

        val intent = Intent(com.flockyou.data.BroadcastSettings.ACTION_ULTRASONIC).apply {
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_TYPE, anomaly.type.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_DESCRIPTION, anomaly.description)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_THREAT_LEVEL, anomaly.severity.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_TIMESTAMP, anomaly.timestamp)
            setPackage(null)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: ${com.flockyou.data.BroadcastSettings.ACTION_ULTRASONIC}")
    }

    /**
     * Send a broadcast for GNSS anomaly events (spoofing/jamming detection)
     */
    fun sendGnssAnomalyBroadcast(anomaly: com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly) {
        val settings = currentBroadcastSettings
        if (!settings.enabled || !settings.broadcastOnGnssAnomaly) return

        val intent = Intent(com.flockyou.data.BroadcastSettings.ACTION_GNSS_ANOMALY).apply {
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_TYPE, anomaly.type.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_ANOMALY_DESCRIPTION, anomaly.description)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_THREAT_LEVEL, anomaly.severity.name)
            putExtra(com.flockyou.data.BroadcastSettings.EXTRA_TIMESTAMP, anomaly.timestamp)
            putExtra("technical_details", anomaly.technicalDetails)
            putExtra("affected_constellations", anomaly.affectedConstellations.joinToString(",") { it.code })
            putExtra("confidence", anomaly.confidence.name)
            if (settings.includeLocation) {
                anomaly.latitude?.let { putExtra(com.flockyou.data.BroadcastSettings.EXTRA_LATITUDE, it) }
                anomaly.longitude?.let { putExtra(com.flockyou.data.BroadcastSettings.EXTRA_LONGITUDE, it) }
            }
            setPackage(null)
        }

        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: ${com.flockyou.data.BroadcastSettings.ACTION_GNSS_ANOMALY}")
    }

    /**
     * Check if a threat level meets the minimum threshold
     */
    private fun meetsMinThreatLevel(actual: ThreatLevel, minimum: String): Boolean {
        val levels = listOf("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")
        val actualIndex = levels.indexOf(actual.name)
        val minIndex = levels.indexOf(minimum)
        return actualIndex >= minIndex
    }

    // ==================== Location ====================
    
    @SuppressLint("MissingPermission")
    private fun updateLocation() {
        if (!hasLocationPermissions()) {
            locationStatus.value = SubsystemStatus.PermissionDenied("ACCESS_FINE_LOCATION")
            return
        }
        
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                currentLocation = location
                locationStatus.value = if (location != null) {
                    SubsystemStatus.Active
                } else {
                    SubsystemStatus.Error(-1, "No location available")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get location", e)
                locationStatus.value = SubsystemStatus.Error(-1, e.message ?: "Location error")
                logError("Location", -1, "Failed to get location: ${e.message}", recoverable = true)
            }
    }
    
    // ==================== Permissions ====================
    
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    // ==================== Detector Health Management ====================

    /**
     * Start the periodic health check job that monitors detector health
     * and attempts to restart stalled detectors.
     */
    private fun startHealthCheckJob() {
        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            while (isActive && isScanning.value) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                performHealthCheck()
            }
        }
        Log.d(TAG, "Health check job started")
    }

    /**
     * Stop the health check job.
     */
    private fun stopHealthCheckJob() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        Log.d(TAG, "Health check job stopped")
    }

    /**
     * Perform a health check on all detectors and attempt to restart any that are stalled.
     */
    private fun performHealthCheck() {
        val now = System.currentTimeMillis()
        val currentHealth = detectorHealth.value.toMutableMap()

        for ((detectorName, status) in currentHealth) {
            if (!status.isRunning) continue

            // Check if detector has gone stale (no successful scan in threshold time)
            val lastSuccess = status.lastSuccessfulScan
            if (lastSuccess != null && (now - lastSuccess) > DETECTOR_STALE_THRESHOLD_MS) {
                Log.w(TAG, "Detector $detectorName appears stalled (no scan in ${(now - lastSuccess) / 1000}s)")

                // Mark as unhealthy
                currentHealth[detectorName] = status.copy(
                    isHealthy = false,
                    consecutiveFailures = status.consecutiveFailures + 1
                )

                // Attempt restart if we haven't exceeded max attempts
                if (status.restartCount < MAX_RESTART_ATTEMPTS) {
                    attemptDetectorRestart(detectorName)
                } else {
                    Log.e(TAG, "Detector $detectorName exceeded max restart attempts (${MAX_RESTART_ATTEMPTS})")
                    logError(detectorName, -1, "Detector failed after ${MAX_RESTART_ATTEMPTS} restart attempts", recoverable = false)
                }
            }
        }

        detectorHealth.value = currentHealth
        broadcastDetectorHealth()
    }

    /**
     * Handle an error from a detector.
     */
    private fun handleDetectorError(detectorName: String, error: String, recoverable: Boolean) {
        Log.e(TAG, "Detector error [$detectorName]: $error (recoverable=$recoverable)")

        updateDetectorHealth(detectorName) { current ->
            val newFailures = current.consecutiveFailures + 1
            current.copy(
                consecutiveFailures = newFailures,
                lastError = error,
                lastErrorTime = System.currentTimeMillis(),
                isHealthy = newFailures < MAX_CONSECUTIVE_FAILURES
            )
        }

        // Log to error log
        logError(detectorName, -1, error, recoverable)

        // Attempt restart if recoverable and not exceeded max failures
        val currentStatus = detectorHealth.value[detectorName]
        if (recoverable && currentStatus != null &&
            currentStatus.consecutiveFailures < MAX_CONSECUTIVE_FAILURES &&
            currentStatus.restartCount < MAX_RESTART_ATTEMPTS) {
            // Use exponential backoff for restart delay
            val delayMs = (1000L * (1 shl currentStatus.consecutiveFailures.coerceAtMost(4))).coerceAtMost(30_000L)
            serviceScope.launch {
                delay(delayMs)
                attemptDetectorRestart(detectorName)
            }
        }

        broadcastDetectorHealth()
    }

    /**
     * Handle a successful scan from a detector.
     */
    private fun handleDetectorSuccess(detectorName: String) {
        updateDetectorHealth(detectorName) { current ->
            current.copy(
                lastSuccessfulScan = System.currentTimeMillis(),
                consecutiveFailures = 0,
                isHealthy = true
            )
        }
        broadcastDetectorHealth()
    }

    /**
     * Update detector health status with a transformation function.
     */
    private fun updateDetectorHealth(detectorName: String, transform: (DetectorHealthStatus) -> DetectorHealthStatus) {
        val current = detectorHealth.value.toMutableMap()
        val existing = current[detectorName] ?: DetectorHealthStatus(name = detectorName)
        current[detectorName] = transform(existing)
        detectorHealth.value = current
    }

    /**
     * Initialize detector health tracking for all detectors.
     */
    private fun initializeDetectorHealth() {
        val initialHealth = mapOf(
            DetectorHealthStatus.DETECTOR_BLE to DetectorHealthStatus(name = DetectorHealthStatus.DETECTOR_BLE),
            DetectorHealthStatus.DETECTOR_WIFI to DetectorHealthStatus(name = DetectorHealthStatus.DETECTOR_WIFI),
            DetectorHealthStatus.DETECTOR_ULTRASONIC to DetectorHealthStatus(name = DetectorHealthStatus.DETECTOR_ULTRASONIC),
            DetectorHealthStatus.DETECTOR_ROGUE_WIFI to DetectorHealthStatus(name = DetectorHealthStatus.DETECTOR_ROGUE_WIFI),
            DetectorHealthStatus.DETECTOR_RF_SIGNAL to DetectorHealthStatus(name = DetectorHealthStatus.DETECTOR_RF_SIGNAL),
            DetectorHealthStatus.DETECTOR_CELLULAR to DetectorHealthStatus(name = DetectorHealthStatus.DETECTOR_CELLULAR),
            DetectorHealthStatus.DETECTOR_GNSS to DetectorHealthStatus(name = DetectorHealthStatus.DETECTOR_GNSS),
            DetectorHealthStatus.DETECTOR_SATELLITE to DetectorHealthStatus(name = DetectorHealthStatus.DETECTOR_SATELLITE)
        )
        detectorHealth.value = initialHealth
        broadcastDetectorHealth()
    }

    /**
     * Attempt to restart a specific detector.
     */
    private fun attemptDetectorRestart(detectorName: String) {
        Log.i(TAG, "Attempting to restart detector: $detectorName")

        // Increment restart count
        updateDetectorHealth(detectorName) { current ->
            current.copy(restartCount = current.restartCount + 1)
        }

        when (detectorName) {
            DetectorHealthStatus.DETECTOR_ULTRASONIC -> {
                try {
                    ultrasonicDetector?.stopMonitoring()
                    ultrasonicDetector?.startMonitoring()
                    Log.i(TAG, "Ultrasonic detector restarted")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart ultrasonic detector", e)
                }
            }
            DetectorHealthStatus.DETECTOR_ROGUE_WIFI -> {
                try {
                    rogueWifiMonitor?.stopMonitoring()
                    rogueWifiMonitor?.startMonitoring()
                    Log.i(TAG, "Rogue WiFi monitor restarted")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart rogue WiFi monitor", e)
                }
            }
            DetectorHealthStatus.DETECTOR_RF_SIGNAL -> {
                try {
                    rfSignalAnalyzer?.stopMonitoring()
                    rfSignalAnalyzer?.startMonitoring()
                    Log.i(TAG, "RF signal analyzer restarted")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart RF signal analyzer", e)
                }
            }
            DetectorHealthStatus.DETECTOR_CELLULAR -> {
                try {
                    cellularMonitor?.stopMonitoring()
                    cellularMonitor?.startMonitoring()
                    Log.i(TAG, "Cellular monitor restarted")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart cellular monitor", e)
                }
            }
            DetectorHealthStatus.DETECTOR_GNSS -> {
                try {
                    gnssSatelliteMonitor?.stopMonitoring()
                    gnssSatelliteMonitor?.startMonitoring()
                    Log.i(TAG, "GNSS monitor restarted")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart GNSS monitor", e)
                }
            }
            DetectorHealthStatus.DETECTOR_SATELLITE -> {
                try {
                    satelliteMonitor?.stopMonitoring()
                    satelliteMonitor?.startMonitoring()
                    Log.i(TAG, "Satellite monitor restarted")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart satellite monitor", e)
                }
            }
            DetectorHealthStatus.DETECTOR_BLE -> {
                try {
                    stopBleScan()
                    startBleScan()
                    Log.i(TAG, "BLE scanner restarted")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart BLE scanner", e)
                }
            }
            DetectorHealthStatus.DETECTOR_WIFI -> {
                // WiFi scanning is triggered by system, just log
                Log.i(TAG, "WiFi scanner restart requested (system-triggered)")
            }
        }

        broadcastDetectorHealth()
    }

    /**
     * Broadcast detector health status to all IPC clients.
     */
    private fun broadcastDetectorHealth() {
        if (ipcClients.isEmpty()) return
        val healthJson = ScanningServiceIpc.gson.toJson(detectorHealth.value)
        broadcastToClients {
            Message.obtain(null, ScanningServiceIpc.MSG_DETECTOR_HEALTH).apply {
                data = Bundle().apply {
                    putString(ScanningServiceIpc.KEY_DETECTOR_HEALTH_JSON, healthJson)
                }
            }
        }
    }
}

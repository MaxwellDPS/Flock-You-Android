package com.flockyou.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * IPC constants for communication between main process and scanning service process.
 */
object ScanningServiceIpc {
    private const val TAG = "ScanningServiceIpc"

    // Gson instance for JSON serialization (thread-safe)
    val gson: Gson = Gson()

    // Message types from client to service
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_UNREGISTER_CLIENT = 2
    const val MSG_REQUEST_STATE = 3
    const val MSG_START_SCANNING = 4
    const val MSG_STOP_SCANNING = 5
    const val MSG_CLEAR_SEEN_DEVICES = 6
    const val MSG_RESET_DETECTION_COUNT = 7
    const val MSG_CLEAR_CELLULAR_HISTORY = 8
    const val MSG_CLEAR_SATELLITE_HISTORY = 9
    const val MSG_CLEAR_ERRORS = 10
    const val MSG_CLEAR_LEARNED_SIGNATURES = 11

    // Message types from service to client
    const val MSG_STATE_UPDATE = 100
    const val MSG_SCANNING_STARTED = 101
    const val MSG_SCANNING_STOPPED = 102
    const val MSG_DETECTION_COUNT = 103
    const val MSG_ERROR = 104
    const val MSG_SUBSYSTEM_STATUS = 105
    const val MSG_SEEN_BLE_DEVICES = 106
    const val MSG_SEEN_WIFI_NETWORKS = 107
    const val MSG_CELLULAR_DATA = 108
    const val MSG_SATELLITE_DATA = 109
    const val MSG_ROGUE_WIFI_DATA = 110
    const val MSG_RF_DATA = 111
    const val MSG_ULTRASONIC_DATA = 112
    const val MSG_LAST_DETECTION = 113

    // Bundle keys for state data
    const val KEY_IS_SCANNING = "is_scanning"
    const val KEY_DETECTION_COUNT = "detection_count"
    const val KEY_HIGH_THREAT_COUNT = "high_threat_count"
    const val KEY_SCAN_STATUS = "scan_status"
    const val KEY_BLE_STATUS = "ble_status"
    const val KEY_WIFI_STATUS = "wifi_status"
    const val KEY_LOCATION_STATUS = "location_status"
    const val KEY_CELLULAR_STATUS = "cellular_status"
    const val KEY_SATELLITE_STATUS = "satellite_status"
    const val KEY_ERROR_MESSAGE = "error_message"

    // Bundle keys for complex JSON data
    const val KEY_JSON_DATA = "json_data"
    const val KEY_CELL_STATUS_JSON = "cell_status_json"
    const val KEY_SEEN_TOWERS_JSON = "seen_towers_json"
    const val KEY_CELLULAR_ANOMALIES_JSON = "cellular_anomalies_json"
    const val KEY_CELLULAR_EVENTS_JSON = "cellular_events_json"
    const val KEY_SATELLITE_STATE_JSON = "satellite_state_json"
    const val KEY_SATELLITE_ANOMALIES_JSON = "satellite_anomalies_json"
    const val KEY_SATELLITE_HISTORY_JSON = "satellite_history_json"
    const val KEY_ROGUE_WIFI_STATUS_JSON = "rogue_wifi_status_json"
    const val KEY_ROGUE_WIFI_ANOMALIES_JSON = "rogue_wifi_anomalies_json"
    const val KEY_SUSPICIOUS_NETWORKS_JSON = "suspicious_networks_json"
    const val KEY_RF_STATUS_JSON = "rf_status_json"
    const val KEY_RF_ANOMALIES_JSON = "rf_anomalies_json"
    const val KEY_DETECTED_DRONES_JSON = "detected_drones_json"
    const val KEY_ULTRASONIC_STATUS_JSON = "ultrasonic_status_json"
    const val KEY_ULTRASONIC_ANOMALIES_JSON = "ultrasonic_anomalies_json"
    const val KEY_ULTRASONIC_BEACONS_JSON = "ultrasonic_beacons_json"
    const val KEY_LAST_DETECTION_JSON = "last_detection_json"

    /**
     * Handler for incoming messages from the scanning service.
     * Uses a background HandlerThread to process messages and deserialize JSON
     * off the main thread, then updates StateFlows (which are thread-safe).
     */
    class IncomingHandler(
        private val connection: ScanningServiceConnection,
        looper: Looper
    ) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_STATE_UPDATE -> {
                    val bundle = msg.data
                    connection.updateState(
                        isScanning = bundle.getBoolean(KEY_IS_SCANNING, false),
                        detectionCount = bundle.getInt(KEY_DETECTION_COUNT, 0),
                        scanStatus = bundle.getString(KEY_SCAN_STATUS, "Idle"),
                        bleStatus = bundle.getString(KEY_BLE_STATUS, "Idle"),
                        wifiStatus = bundle.getString(KEY_WIFI_STATUS, "Idle"),
                        locationStatus = bundle.getString(KEY_LOCATION_STATUS, "Idle"),
                        cellularStatus = bundle.getString(KEY_CELLULAR_STATUS, "Idle"),
                        satelliteStatus = bundle.getString(KEY_SATELLITE_STATUS, "Idle")
                    )
                }
                MSG_SCANNING_STARTED -> {
                    connection.updateScanning(true)
                }
                MSG_SCANNING_STOPPED -> {
                    connection.updateScanning(false)
                }
                MSG_DETECTION_COUNT -> {
                    connection.updateDetectionCount(msg.arg1)
                }
                MSG_ERROR -> {
                    val errorMsg = msg.data?.getString(KEY_ERROR_MESSAGE) ?: "Unknown error"
                    Log.e(TAG, "Service error: $errorMsg")
                }
                MSG_SUBSYSTEM_STATUS -> {
                    val bundle = msg.data
                    connection.updateSubsystemStatus(
                        bleStatus = bundle.getString(KEY_BLE_STATUS),
                        wifiStatus = bundle.getString(KEY_WIFI_STATUS),
                        locationStatus = bundle.getString(KEY_LOCATION_STATUS),
                        cellularStatus = bundle.getString(KEY_CELLULAR_STATUS),
                        satelliteStatus = bundle.getString(KEY_SATELLITE_STATUS)
                    )
                }
                MSG_SEEN_BLE_DEVICES -> {
                    val json = msg.data?.getString(KEY_JSON_DATA)
                    connection.updateSeenBleDevices(json)
                }
                MSG_SEEN_WIFI_NETWORKS -> {
                    val json = msg.data?.getString(KEY_JSON_DATA)
                    connection.updateSeenWifiNetworks(json)
                }
                MSG_CELLULAR_DATA -> {
                    val bundle = msg.data
                    connection.updateCellularData(
                        cellStatusJson = bundle?.getString(KEY_CELL_STATUS_JSON),
                        seenTowersJson = bundle?.getString(KEY_SEEN_TOWERS_JSON),
                        anomaliesJson = bundle?.getString(KEY_CELLULAR_ANOMALIES_JSON),
                        eventsJson = bundle?.getString(KEY_CELLULAR_EVENTS_JSON)
                    )
                }
                MSG_SATELLITE_DATA -> {
                    val bundle = msg.data
                    connection.updateSatelliteData(
                        stateJson = bundle?.getString(KEY_SATELLITE_STATE_JSON),
                        anomaliesJson = bundle?.getString(KEY_SATELLITE_ANOMALIES_JSON),
                        historyJson = bundle?.getString(KEY_SATELLITE_HISTORY_JSON)
                    )
                }
                MSG_ROGUE_WIFI_DATA -> {
                    val bundle = msg.data
                    connection.updateRogueWifiData(
                        statusJson = bundle?.getString(KEY_ROGUE_WIFI_STATUS_JSON),
                        anomaliesJson = bundle?.getString(KEY_ROGUE_WIFI_ANOMALIES_JSON),
                        suspiciousJson = bundle?.getString(KEY_SUSPICIOUS_NETWORKS_JSON)
                    )
                }
                MSG_RF_DATA -> {
                    val bundle = msg.data
                    connection.updateRfData(
                        statusJson = bundle?.getString(KEY_RF_STATUS_JSON),
                        anomaliesJson = bundle?.getString(KEY_RF_ANOMALIES_JSON),
                        dronesJson = bundle?.getString(KEY_DETECTED_DRONES_JSON)
                    )
                }
                MSG_ULTRASONIC_DATA -> {
                    val bundle = msg.data
                    connection.updateUltrasonicData(
                        statusJson = bundle?.getString(KEY_ULTRASONIC_STATUS_JSON),
                        anomaliesJson = bundle?.getString(KEY_ULTRASONIC_ANOMALIES_JSON),
                        beaconsJson = bundle?.getString(KEY_ULTRASONIC_BEACONS_JSON)
                    )
                }
                MSG_LAST_DETECTION -> {
                    val json = msg.data?.getString(KEY_LAST_DETECTION_JSON)
                    connection.updateLastDetection(json)
                }
                else -> super.handleMessage(msg)
            }
        }
    }
}

/**
 * Manages the connection to the ScanningService from the main process.
 * Handles binding, messaging, and state synchronization across processes.
 */
class ScanningServiceConnection(private val context: Context) {
    private val tag = "ScanServiceConnection"

    // Messenger for sending messages to the service
    private var serviceMessenger: Messenger? = null

    // Background HandlerThread for IPC message processing (JSON deserialization off main thread)
    private val ipcHandlerThread = HandlerThread("IpcClientHandler").apply { start() }

    // Messenger for receiving messages from the service
    private val clientMessenger = Messenger(ScanningServiceIpc.IncomingHandler(this, ipcHandlerThread.looper))

    // Connection state
    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    // Mirrored state from the service process
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _detectionCount = MutableStateFlow(0)
    val detectionCount: StateFlow<Int> = _detectionCount.asStateFlow()

    private val _scanStatus = MutableStateFlow("Idle")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private val _bleStatus = MutableStateFlow("Idle")
    val bleStatus: StateFlow<String> = _bleStatus.asStateFlow()

    private val _wifiStatus = MutableStateFlow("Idle")
    val wifiStatus: StateFlow<String> = _wifiStatus.asStateFlow()

    private val _locationStatus = MutableStateFlow("Idle")
    val locationStatus: StateFlow<String> = _locationStatus.asStateFlow()

    private val _cellularStatus = MutableStateFlow("Idle")
    val cellularStatus: StateFlow<String> = _cellularStatus.asStateFlow()

    private val _satelliteStatus = MutableStateFlow("Idle")
    val satelliteStatus: StateFlow<String> = _satelliteStatus.asStateFlow()

    // Seen devices (mirrored from service process)
    private val _seenBleDevices = MutableStateFlow<List<ScanningService.SeenDevice>>(emptyList())
    val seenBleDevices: StateFlow<List<ScanningService.SeenDevice>> = _seenBleDevices.asStateFlow()

    private val _seenWifiNetworks = MutableStateFlow<List<ScanningService.SeenDevice>>(emptyList())
    val seenWifiNetworks: StateFlow<List<ScanningService.SeenDevice>> = _seenWifiNetworks.asStateFlow()

    // Cellular monitoring data (mirrored from service process)
    private val _cellStatus = MutableStateFlow<CellularMonitor.CellStatus?>(null)
    val cellStatus: StateFlow<CellularMonitor.CellStatus?> = _cellStatus.asStateFlow()

    private val _seenCellTowers = MutableStateFlow<List<CellularMonitor.SeenCellTower>>(emptyList())
    val seenCellTowers: StateFlow<List<CellularMonitor.SeenCellTower>> = _seenCellTowers.asStateFlow()

    private val _cellularAnomalies = MutableStateFlow<List<CellularMonitor.CellularAnomaly>>(emptyList())
    val cellularAnomalies: StateFlow<List<CellularMonitor.CellularAnomaly>> = _cellularAnomalies.asStateFlow()

    private val _cellularEvents = MutableStateFlow<List<CellularMonitor.CellularEvent>>(emptyList())
    val cellularEvents: StateFlow<List<CellularMonitor.CellularEvent>> = _cellularEvents.asStateFlow()

    // Satellite monitoring data (mirrored from service process)
    private val _satelliteState = MutableStateFlow<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState?>(null)
    val satelliteState: StateFlow<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState?> = _satelliteState.asStateFlow()

    private val _satelliteAnomalies = MutableStateFlow<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly>>(emptyList())
    val satelliteAnomalies: StateFlow<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly>> = _satelliteAnomalies.asStateFlow()

    private val _satelliteHistory = MutableStateFlow<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionEvent>>(emptyList())
    val satelliteHistory: StateFlow<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionEvent>> = _satelliteHistory.asStateFlow()

    // Rogue WiFi monitoring data (mirrored from service process)
    private val _rogueWifiStatus = MutableStateFlow<RogueWifiMonitor.WifiEnvironmentStatus?>(null)
    val rogueWifiStatus: StateFlow<RogueWifiMonitor.WifiEnvironmentStatus?> = _rogueWifiStatus.asStateFlow()

    private val _rogueWifiAnomalies = MutableStateFlow<List<RogueWifiMonitor.WifiAnomaly>>(emptyList())
    val rogueWifiAnomalies: StateFlow<List<RogueWifiMonitor.WifiAnomaly>> = _rogueWifiAnomalies.asStateFlow()

    private val _suspiciousNetworks = MutableStateFlow<List<RogueWifiMonitor.SuspiciousNetwork>>(emptyList())
    val suspiciousNetworks: StateFlow<List<RogueWifiMonitor.SuspiciousNetwork>> = _suspiciousNetworks.asStateFlow()

    // RF signal analysis data (mirrored from service process)
    private val _rfStatus = MutableStateFlow<RfSignalAnalyzer.RfEnvironmentStatus?>(null)
    val rfStatus: StateFlow<RfSignalAnalyzer.RfEnvironmentStatus?> = _rfStatus.asStateFlow()

    private val _rfAnomalies = MutableStateFlow<List<RfSignalAnalyzer.RfAnomaly>>(emptyList())
    val rfAnomalies: StateFlow<List<RfSignalAnalyzer.RfAnomaly>> = _rfAnomalies.asStateFlow()

    private val _detectedDrones = MutableStateFlow<List<RfSignalAnalyzer.DroneInfo>>(emptyList())
    val detectedDrones: StateFlow<List<RfSignalAnalyzer.DroneInfo>> = _detectedDrones.asStateFlow()

    // Ultrasonic detection data (mirrored from service process)
    private val _ultrasonicStatus = MutableStateFlow<UltrasonicDetector.UltrasonicStatus?>(null)
    val ultrasonicStatus: StateFlow<UltrasonicDetector.UltrasonicStatus?> = _ultrasonicStatus.asStateFlow()

    private val _ultrasonicAnomalies = MutableStateFlow<List<UltrasonicDetector.UltrasonicAnomaly>>(emptyList())
    val ultrasonicAnomalies: StateFlow<List<UltrasonicDetector.UltrasonicAnomaly>> = _ultrasonicAnomalies.asStateFlow()

    private val _ultrasonicBeacons = MutableStateFlow<List<UltrasonicDetector.BeaconDetection>>(emptyList())
    val ultrasonicBeacons: StateFlow<List<UltrasonicDetector.BeaconDetection>> = _ultrasonicBeacons.asStateFlow()

    // Last detection (mirrored from service process)
    private val _lastDetection = MutableStateFlow<com.flockyou.data.model.Detection?>(null)
    val lastDetection: StateFlow<com.flockyou.data.model.Detection?> = _lastDetection.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(tag, "Service connected")
            serviceMessenger = Messenger(service)
            _isBound.value = true

            // Register this client with the service
            try {
                val msg = Message.obtain(null, ScanningServiceIpc.MSG_REGISTER_CLIENT)
                msg.replyTo = clientMessenger
                serviceMessenger?.send(msg)

                // Request current state
                requestState()
            } catch (e: RemoteException) {
                Log.e(tag, "Failed to register client", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(tag, "Service disconnected")
            serviceMessenger = null
            _isBound.value = false
        }
    }

    /**
     * Bind to the scanning service.
     */
    fun bind() {
        if (_isBound.value) return

        val intent = Intent(context, ScanningService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Unbind from the scanning service.
     */
    fun unbind() {
        if (!_isBound.value) return

        // Unregister this client
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_UNREGISTER_CLIENT)
            msg.replyTo = clientMessenger
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to unregister client", e)
        }

        context.unbindService(connection)
        _isBound.value = false

        // Clean up the handler thread
        ipcHandlerThread.quitSafely()
    }

    /**
     * Request current state from the service.
     */
    fun requestState() {
        if (!_isBound.value) return

        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_REQUEST_STATE)
            msg.replyTo = clientMessenger
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to request state", e)
        }
    }

    /**
     * Send start scanning command to the service.
     */
    fun startScanning() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_START_SCANNING)
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send start command", e)
        }
    }

    /**
     * Send stop scanning command to the service.
     */
    fun stopScanning() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_STOP_SCANNING)
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send stop command", e)
        }
    }

    /**
     * Send clear seen devices command to the service.
     */
    fun clearSeenDevices() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_CLEAR_SEEN_DEVICES)
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send clear seen devices command", e)
        }
        // Also clear local state immediately for responsive UI
        _seenBleDevices.value = emptyList()
        _seenWifiNetworks.value = emptyList()
    }

    /**
     * Reset detection count to 0 and clear last detection.
     */
    fun resetDetectionCount() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_RESET_DETECTION_COUNT)
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send reset detection count command", e)
        }
        // Also clear local state immediately for responsive UI
        _detectionCount.value = 0
        _lastDetection.value = null
    }

    /**
     * Clear cellular monitoring history.
     */
    fun clearCellularHistory() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_CLEAR_CELLULAR_HISTORY)
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send clear cellular history command", e)
        }
        // Also clear local state immediately for responsive UI
        _seenCellTowers.value = emptyList()
        _cellularEvents.value = emptyList()
        _cellularAnomalies.value = emptyList()
    }

    /**
     * Clear satellite monitoring history.
     */
    fun clearSatelliteHistory() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_CLEAR_SATELLITE_HISTORY)
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send clear satellite history command", e)
        }
        // Also clear local state immediately for responsive UI
        _satelliteHistory.value = emptyList()
        _satelliteAnomalies.value = emptyList()
    }

    /**
     * Clear scan errors.
     */
    fun clearErrors() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_CLEAR_ERRORS)
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send clear errors command", e)
        }
    }

    /**
     * Clear learned device signatures.
     */
    fun clearLearnedSignatures() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_CLEAR_LEARNED_SIGNATURES)
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send clear learned signatures command", e)
        }
    }

    // Internal state update methods called by the handler
    internal fun updateState(
        isScanning: Boolean,
        detectionCount: Int,
        scanStatus: String,
        bleStatus: String,
        wifiStatus: String,
        locationStatus: String,
        cellularStatus: String,
        satelliteStatus: String
    ) {
        _isScanning.value = isScanning
        _detectionCount.value = detectionCount
        _scanStatus.value = scanStatus
        _bleStatus.value = bleStatus
        _wifiStatus.value = wifiStatus
        _locationStatus.value = locationStatus
        _cellularStatus.value = cellularStatus
        _satelliteStatus.value = satelliteStatus
    }

    internal fun updateScanning(isScanning: Boolean) {
        _isScanning.value = isScanning
    }

    internal fun updateDetectionCount(count: Int) {
        _detectionCount.value = count
    }

    internal fun updateSubsystemStatus(
        bleStatus: String?,
        wifiStatus: String?,
        locationStatus: String?,
        cellularStatus: String?,
        satelliteStatus: String?
    ) {
        bleStatus?.let { _bleStatus.value = it }
        wifiStatus?.let { _wifiStatus.value = it }
        locationStatus?.let { _locationStatus.value = it }
        cellularStatus?.let { _cellularStatus.value = it }
        satelliteStatus?.let { _satelliteStatus.value = it }
    }

    internal fun updateSeenBleDevices(json: String?) {
        if (json == null) return
        try {
            val type = object : TypeToken<List<ScanningService.SeenDevice>>() {}.type
            val devices: List<ScanningService.SeenDevice> = ScanningServiceIpc.gson.fromJson(json, type)
            _seenBleDevices.value = devices
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse seen BLE devices JSON", e)
        }
    }

    internal fun updateSeenWifiNetworks(json: String?) {
        if (json == null) return
        try {
            val type = object : TypeToken<List<ScanningService.SeenDevice>>() {}.type
            val networks: List<ScanningService.SeenDevice> = ScanningServiceIpc.gson.fromJson(json, type)
            _seenWifiNetworks.value = networks
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse seen WiFi networks JSON", e)
        }
    }

    internal fun updateCellularData(
        cellStatusJson: String?,
        seenTowersJson: String?,
        anomaliesJson: String?,
        eventsJson: String?
    ) {
        try {
            cellStatusJson?.let {
                _cellStatus.value = ScanningServiceIpc.gson.fromJson(it, CellularMonitor.CellStatus::class.java)
            }
            seenTowersJson?.let {
                val type = object : TypeToken<List<CellularMonitor.SeenCellTower>>() {}.type
                _seenCellTowers.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<CellularMonitor.CellularAnomaly>>() {}.type
                _cellularAnomalies.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
            eventsJson?.let {
                val type = object : TypeToken<List<CellularMonitor.CellularEvent>>() {}.type
                _cellularEvents.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse cellular data JSON", e)
        }
    }

    internal fun updateSatelliteData(
        stateJson: String?,
        anomaliesJson: String?,
        historyJson: String?
    ) {
        try {
            stateJson?.let {
                _satelliteState.value = ScanningServiceIpc.gson.fromJson(it, com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState::class.java)
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly>>() {}.type
                _satelliteAnomalies.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
            historyJson?.let {
                val type = object : TypeToken<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionEvent>>() {}.type
                _satelliteHistory.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse satellite data JSON", e)
        }
    }

    internal fun updateRogueWifiData(
        statusJson: String?,
        anomaliesJson: String?,
        suspiciousJson: String?
    ) {
        try {
            statusJson?.let {
                _rogueWifiStatus.value = ScanningServiceIpc.gson.fromJson(it, RogueWifiMonitor.WifiEnvironmentStatus::class.java)
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<RogueWifiMonitor.WifiAnomaly>>() {}.type
                _rogueWifiAnomalies.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
            suspiciousJson?.let {
                val type = object : TypeToken<List<RogueWifiMonitor.SuspiciousNetwork>>() {}.type
                _suspiciousNetworks.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse rogue WiFi data JSON", e)
        }
    }

    internal fun updateRfData(
        statusJson: String?,
        anomaliesJson: String?,
        dronesJson: String?
    ) {
        try {
            statusJson?.let {
                _rfStatus.value = ScanningServiceIpc.gson.fromJson(it, RfSignalAnalyzer.RfEnvironmentStatus::class.java)
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<RfSignalAnalyzer.RfAnomaly>>() {}.type
                _rfAnomalies.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
            dronesJson?.let {
                val type = object : TypeToken<List<RfSignalAnalyzer.DroneInfo>>() {}.type
                _detectedDrones.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse RF data JSON", e)
        }
    }

    internal fun updateUltrasonicData(
        statusJson: String?,
        anomaliesJson: String?,
        beaconsJson: String?
    ) {
        try {
            statusJson?.let {
                _ultrasonicStatus.value = ScanningServiceIpc.gson.fromJson(it, UltrasonicDetector.UltrasonicStatus::class.java)
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<UltrasonicDetector.UltrasonicAnomaly>>() {}.type
                _ultrasonicAnomalies.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
            beaconsJson?.let {
                val type = object : TypeToken<List<UltrasonicDetector.BeaconDetection>>() {}.type
                _ultrasonicBeacons.value = ScanningServiceIpc.gson.fromJson(it, type)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse ultrasonic data JSON", e)
        }
    }

    internal fun updateLastDetection(json: String?) {
        if (json == null) {
            _lastDetection.value = null
            return
        }
        try {
            _lastDetection.value = ScanningServiceIpc.gson.fromJson(json, com.flockyou.data.model.Detection::class.java)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse last detection JSON", e)
        }
    }
}

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    const val MSG_REQUEST_THREADING_DATA = 12

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
    const val MSG_GNSS_DATA = 114
    const val MSG_DETECTOR_HEALTH = 115
    const val MSG_ERROR_LOG = 116
    const val MSG_DETECTION_REFRESH = 117
    const val MSG_SCAN_STATS = 118
    const val MSG_THREADING_DATA = 119
    const val MSG_ACTIVE_DETECTIONS = 120

    /** Message to notify service that Android Auto is connected - enables boost mode */
    const val MSG_ANDROID_AUTO_CONNECTED = 130

    /** Message to notify service that Android Auto is disconnected - disables boost mode */
    const val MSG_ANDROID_AUTO_DISCONNECTED = 131

    /** Message to request current boost mode status */
    const val MSG_REQUEST_BOOST_STATUS = 132

    /** Message sent from service with boost mode status */
    const val MSG_BOOST_STATUS = 133

    /** Message sent from service with battery-adaptive mode state */
    const val MSG_BATTERY_STATE = 134

    /** Message sent from service with cross-domain correlation analysis results */
    const val MSG_CORRELATION_RESULTS = 135

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
    const val KEY_GNSS_STATUS_JSON = "gnss_status_json"
    const val KEY_GNSS_SATELLITES_JSON = "gnss_satellites_json"
    const val KEY_GNSS_ANOMALIES_JSON = "gnss_anomalies_json"
    const val KEY_GNSS_EVENTS_JSON = "gnss_events_json"
    const val KEY_GNSS_MEASUREMENTS_JSON = "gnss_measurements_json"
    const val KEY_DETECTOR_HEALTH_JSON = "detector_health_json"
    const val KEY_ERROR_LOG_JSON = "error_log_json"
    const val KEY_SCAN_STATS_JSON = "scan_stats_json"
    const val KEY_THREADING_SYSTEM_STATE_JSON = "threading_system_state_json"
    const val KEY_THREADING_SCANNER_STATES_JSON = "threading_scanner_states_json"
    const val KEY_THREADING_ALERTS_JSON = "threading_alerts_json"
    const val KEY_ACTIVE_DETECTIONS_JSON = "active_detections_json"
    const val KEY_BOOST_MODE_ACTIVE = "boost_mode_active"

    /**
     * Handler for incoming messages from the scanning service.
     * Uses a background HandlerThread to process messages and deserialize JSON
     * off the main thread, then updates StateFlows (which are thread-safe).
     *
     * All message handling is wrapped in try-catch to prevent the handler thread
     * from dying due to unexpected exceptions (robust error handling).
     */
    class IncomingHandler(
        private val connection: ScanningServiceConnection,
        looper: Looper
    ) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            Log.d(TAG, "IncomingHandler received message: ${msg.what}")
            try {
                when (msg.what) {
                    MSG_STATE_UPDATE -> {
                        Log.d(TAG, "Processing MSG_STATE_UPDATE")
                        val bundle = msg.data
                        val isScanning = bundle.getBoolean(KEY_IS_SCANNING, false)
                        val scanStatus = bundle.getString(KEY_SCAN_STATUS, "Idle")
                        Log.d(TAG, "State update: isScanning=$isScanning, scanStatus=$scanStatus")
                        connection.updateState(
                            isScanning = isScanning,
                            detectionCount = bundle.getInt(KEY_DETECTION_COUNT, 0),
                            scanStatus = scanStatus,
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
                    MSG_GNSS_DATA -> {
                        val bundle = msg.data
                        connection.updateGnssData(
                            statusJson = bundle?.getString(KEY_GNSS_STATUS_JSON),
                            satellitesJson = bundle?.getString(KEY_GNSS_SATELLITES_JSON),
                            anomaliesJson = bundle?.getString(KEY_GNSS_ANOMALIES_JSON),
                            eventsJson = bundle?.getString(KEY_GNSS_EVENTS_JSON),
                            measurementsJson = bundle?.getString(KEY_GNSS_MEASUREMENTS_JSON)
                        )
                    }
                    MSG_DETECTOR_HEALTH -> {
                        Log.d(TAG, "Processing MSG_DETECTOR_HEALTH")
                        val json = msg.data?.getString(KEY_DETECTOR_HEALTH_JSON)
                        Log.d(TAG, "Detector health JSON length: ${json?.length ?: 0}")
                        connection.updateDetectorHealth(json)
                    }
                    MSG_ERROR_LOG -> {
                        val json = msg.data?.getString(KEY_ERROR_LOG_JSON)
                        connection.updateErrorLog(json)
                    }
                    MSG_DETECTION_REFRESH -> {
                        connection.notifyDetectionRefresh()
                    }
                    MSG_SCAN_STATS -> {
                        val json = msg.data?.getString(KEY_SCAN_STATS_JSON)
                        connection.updateScanStats(json)
                    }
                    MSG_THREADING_DATA -> {
                        val bundle = msg.data
                        connection.updateThreadingData(
                            systemStateJson = bundle?.getString(KEY_THREADING_SYSTEM_STATE_JSON),
                            scannerStatesJson = bundle?.getString(KEY_THREADING_SCANNER_STATES_JSON),
                            alertsJson = bundle?.getString(KEY_THREADING_ALERTS_JSON)
                        )
                    }
                    MSG_ACTIVE_DETECTIONS -> {
                        val json = msg.data?.getString(KEY_ACTIVE_DETECTIONS_JSON)
                        connection.updateActiveDetections(json)
                    }
                    MSG_BOOST_STATUS -> {
                        val isActive = msg.data?.getBoolean(KEY_BOOST_MODE_ACTIVE, false) ?: false
                        connection._isBoostModeActive.update { isActive }
                        Log.d(TAG, "Boost mode status: $isActive")
                    }
                    else -> super.handleMessage(msg)
                }
            } catch (e: Exception) {
                // Log the error but don't let it crash the handler thread
                Log.e(TAG, "Error handling IPC message ${msg.what}: ${e.message}", e)
            }
        }
    }
}

/**
 * Manages the connection to the ScanningService from the main process.
 * Handles binding, messaging, and state synchronization across processes.
 *
 * Features robust error handling:
 * - Automatic reconnection on disconnect
 * - Message retry with exponential backoff
 * - Connection state monitoring
 */
class ScanningServiceConnection(private val context: Context) {
    private val tag = "ScanServiceConnection"

    // Coroutine scope for retry operations
    private val connectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Messenger for sending messages to the service
    private var serviceMessenger: Messenger? = null

    // Background HandlerThread for IPC message processing (JSON deserialization off main thread)
    private val ipcHandlerThread = HandlerThread("IpcClientHandler").apply { start() }

    // Messenger for receiving messages from the service
    private val clientMessenger = Messenger(ScanningServiceIpc.IncomingHandler(this, ipcHandlerThread.looper))

    // Reconnection configuration
    private var reconnectionJob: Job? = null
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 5
    private val baseReconnectDelayMs = 1000L
    private val maxReconnectDelayMs = 30000L

    // Connection state
    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    // Connection error tracking
    private val _lastConnectionError = MutableStateFlow<String?>(null)
    val lastConnectionError: StateFlow<String?> = _lastConnectionError.asStateFlow()

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

    // Active detections list (mirrored from service process for cross-process clients like Android Auto)
    private val _activeDetections = MutableStateFlow<List<com.flockyou.data.model.Detection>>(emptyList())
    val activeDetections: StateFlow<List<com.flockyou.data.model.Detection>> = _activeDetections.asStateFlow()

    // GNSS satellite monitoring data (mirrored from service process)
    private val _gnssStatus = MutableStateFlow<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEnvironmentStatus?>(null)
    val gnssStatus: StateFlow<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEnvironmentStatus?> = _gnssStatus.asStateFlow()

    private val _gnssSatellites = MutableStateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.SatelliteInfo>>(emptyList())
    val gnssSatellites: StateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.SatelliteInfo>> = _gnssSatellites.asStateFlow()

    private val _gnssAnomalies = MutableStateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly>>(emptyList())
    val gnssAnomalies: StateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly>> = _gnssAnomalies.asStateFlow()

    private val _gnssEvents = MutableStateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEvent>>(emptyList())
    val gnssEvents: StateFlow<List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEvent>> = _gnssEvents.asStateFlow()

    private val _gnssMeasurements = MutableStateFlow<com.flockyou.monitoring.GnssSatelliteMonitor.GnssMeasurementData?>(null)
    val gnssMeasurements: StateFlow<com.flockyou.monitoring.GnssSatelliteMonitor.GnssMeasurementData?> = _gnssMeasurements.asStateFlow()

    // Detector health status (mirrored from service process)
    private val _detectorHealth = MutableStateFlow<Map<String, ScanningService.DetectorHealthStatus>>(emptyMap())
    val detectorHealth: StateFlow<Map<String, ScanningService.DetectorHealthStatus>> = _detectorHealth.asStateFlow()

    // Error log (mirrored from service process)
    private val _errorLog = MutableStateFlow<List<ScanningService.ScanError>>(emptyList())
    val errorLog: StateFlow<List<ScanningService.ScanError>> = _errorLog.asStateFlow()

    // Scan statistics (mirrored from service process)
    private val _scanStats = MutableStateFlow(ScanningService.ScanStatistics())
    val scanStats: StateFlow<ScanningService.ScanStatistics> = _scanStats.asStateFlow()

    // Detection refresh event (notifies UI to refresh detections from database)
    private val _detectionRefreshEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val detectionRefreshEvent: SharedFlow<Unit> = _detectionRefreshEvent.asSharedFlow()

    // Threading monitor data (mirrored from service process)
    private val _threadingSystemState = MutableStateFlow<com.flockyou.monitoring.ScannerThreadingMonitor.SystemThreadingState?>(null)
    val threadingSystemState: StateFlow<com.flockyou.monitoring.ScannerThreadingMonitor.SystemThreadingState?> = _threadingSystemState.asStateFlow()

    private val _threadingScannerStates = MutableStateFlow<Map<String, com.flockyou.monitoring.ScannerThreadingMonitor.ScannerThreadState>>(emptyMap())
    val threadingScannerStates: StateFlow<Map<String, com.flockyou.monitoring.ScannerThreadingMonitor.ScannerThreadState>> = _threadingScannerStates.asStateFlow()

    private val _threadingAlerts = MutableStateFlow<List<com.flockyou.monitoring.ScannerThreadingMonitor.ThreadingAlert>>(emptyList())
    val threadingAlerts: StateFlow<List<com.flockyou.monitoring.ScannerThreadingMonitor.ThreadingAlert>> = _threadingAlerts.asStateFlow()

    /** Whether Android Auto boost mode is currently active */
    internal val _isBoostModeActive = MutableStateFlow(false)
    val isBoostModeActive: StateFlow<Boolean> = _isBoostModeActive.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(tag, "onServiceConnected called, service=$service")
            serviceMessenger = Messenger(service)
            _isBound.update { true }
            _lastConnectionError.update { null }
            reconnectAttempt = 0 // Reset reconnection counter on successful connection
            Log.d(tag, "Service connected, isBound=${_isBound.value}")

            // Register this client with the service
            try {
                Log.d(tag, "Registering client with service...")
                val msg = Message.obtain(null, ScanningServiceIpc.MSG_REGISTER_CLIENT)
                msg.replyTo = clientMessenger
                serviceMessenger?.send(msg)
                Log.d(tag, "Client registration message sent")

                // Request current state
                Log.d(tag, "Requesting initial state...")
                requestState()
                Log.d(tag, "Initial state request sent")
            } catch (e: RemoteException) {
                Log.e(tag, "Failed to register client", e)
                _lastConnectionError.update { "Failed to register: ${e.message}" }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(tag, "Service disconnected unexpectedly")
            serviceMessenger = null
            _isBound.update { false }
            _lastConnectionError.update { "Service disconnected" }

            // Attempt to reconnect automatically
            scheduleReconnect()
        }
    }

    /**
     * Schedule a reconnection attempt with exponential backoff.
     */
    private fun scheduleReconnect() {
        if (reconnectAttempt >= maxReconnectAttempts) {
            Log.e(tag, "Max reconnection attempts ($maxReconnectAttempts) reached, giving up")
            _lastConnectionError.update { "Connection lost after $maxReconnectAttempts attempts" }
            return
        }

        reconnectionJob?.cancel()
        reconnectionJob = connectionScope.launch {
            val delay = calculateBackoffDelay(reconnectAttempt)
            Log.d(tag, "Scheduling reconnection attempt ${reconnectAttempt + 1} in ${delay}ms")
            kotlinx.coroutines.delay(delay)

            if (!_isBound.value) {
                reconnectAttempt++
                Log.d(tag, "Attempting reconnection (attempt $reconnectAttempt)")
                bindInternal()
            }
        }
    }

    /**
     * Calculate exponential backoff delay with jitter.
     */
    private fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = baseReconnectDelayMs * (1L shl attempt.coerceAtMost(5))
        val jitter = (Math.random() * 0.3 * exponentialDelay).toLong()
        return (exponentialDelay + jitter).coerceAtMost(maxReconnectDelayMs)
    }

    /**
     * Bind to the scanning service.
     */
    fun bind() {
        Log.d(tag, "bind() called, isBound=${_isBound.value}")
        reconnectAttempt = 0 // Reset on manual bind
        bindInternal()
    }

    /**
     * Internal bind implementation used by both bind() and reconnection.
     */
    private fun bindInternal() {
        if (_isBound.value) {
            Log.d(tag, "Already bound, skipping")
            return
        }

        try {
            val intent = Intent(context, ScanningService::class.java)
            val result = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.d(tag, "bindService result: $result")

            if (!result) {
                _lastConnectionError.update { "Failed to initiate service bind" }
                scheduleReconnect()
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception during bind", e)
            _lastConnectionError.update { "Bind failed: ${e.message}" }
            scheduleReconnect()
        }
    }

    /**
     * Unbind from the scanning service.
     */
    fun unbind() {
        // Cancel any pending reconnection
        reconnectionJob?.cancel()
        reconnectionJob = null

        if (!_isBound.value) return

        // Unregister this client
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_UNREGISTER_CLIENT)
            msg.replyTo = clientMessenger
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to unregister client", e)
        }

        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            Log.e(tag, "Exception during unbind", e)
        }
        _isBound.update { false }
        serviceMessenger = null

        // Clean up the handler thread
        ipcHandlerThread.quitSafely()

        // Cancel all coroutines
        connectionScope.cancel()
    }

    /**
     * Force reconnection - call this to manually retry connection.
     */
    fun forceReconnect() {
        Log.d(tag, "Force reconnect requested")
        reconnectAttempt = 0
        reconnectionJob?.cancel()

        if (_isBound.value) {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                Log.e(tag, "Exception during force unbind", e)
            }
            _isBound.update { false }
            serviceMessenger = null
        }

        bindInternal()
    }

    /**
     * Send a message with retry logic on failure.
     *
     * @param messageType The IPC message type to send
     * @param configureMessage Optional lambda to configure the message (e.g., set data bundle)
     * @param maxRetries Maximum number of retry attempts (default 3)
     * @param onFailure Optional callback when all retries are exhausted
     */
    private fun sendMessageWithRetry(
        messageType: Int,
        configureMessage: ((Message) -> Unit)? = null,
        maxRetries: Int = 3,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        var lastException: Exception? = null

        for (attempt in 0 until maxRetries) {
            if (!_isBound.value) {
                Log.w(tag, "Cannot send message $messageType - not bound")
                lastException = IllegalStateException("Service not bound")
                break
            }

            try {
                val msg = Message.obtain(null, messageType)
                msg.replyTo = clientMessenger
                configureMessage?.invoke(msg)
                serviceMessenger?.send(msg)
                return // Success
            } catch (e: RemoteException) {
                Log.w(tag, "Failed to send message $messageType (attempt ${attempt + 1}/$maxRetries): ${e.message}")
                lastException = e

                if (attempt < maxRetries - 1) {
                    // Small delay before retry
                    Thread.sleep(100L * (attempt + 1))
                }
            } catch (e: Exception) {
                Log.e(tag, "Unexpected error sending message $messageType: ${e.message}", e)
                lastException = e
                break // Don't retry on unexpected errors
            }
        }

        // All retries exhausted
        Log.e(tag, "Failed to send message $messageType after $maxRetries attempts")
        onFailure?.invoke(lastException ?: Exception("Unknown error"))
    }

    /**
     * Request current state from the service.
     */
    fun requestState() {
        Log.d(tag, "requestState() called, isBound=${_isBound.value}")
        if (!_isBound.value) {
            Log.w(tag, "requestState() - not bound, skipping")
            return
        }

        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_REQUEST_STATE)
            msg.replyTo = clientMessenger
            Log.d(tag, "Sending MSG_REQUEST_STATE, replyTo=$clientMessenger")
            serviceMessenger?.send(msg)
            Log.d(tag, "MSG_REQUEST_STATE sent successfully")
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
        _seenBleDevices.update { emptyList() }
        _seenWifiNetworks.update { emptyList() }
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
        _detectionCount.update { 0 }
        _lastDetection.update { null }
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
        _seenCellTowers.update { emptyList() }
        _cellularEvents.update { emptyList() }
        _cellularAnomalies.update { emptyList() }
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
        _satelliteHistory.update { emptyList() }
        _satelliteAnomalies.update { emptyList() }
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

    /**
     * Request threading monitor data from the service.
     */
    fun requestThreadingData() {
        if (!_isBound.value) return
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_REQUEST_THREADING_DATA)
            msg.replyTo = clientMessenger
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to request threading data", e)
        }
    }

    /** Notify service that Android Auto has connected - enables faster scanning */
    fun notifyAndroidAutoConnected() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_ANDROID_AUTO_CONNECTED)
            serviceMessenger?.send(msg)
            Log.d(tag, "Notified service: Android Auto connected")
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send Android Auto connected notification", e)
        }
    }

    /** Notify service that Android Auto has disconnected - returns to normal scanning */
    fun notifyAndroidAutoDisconnected() {
        try {
            val msg = Message.obtain(null, ScanningServiceIpc.MSG_ANDROID_AUTO_DISCONNECTED)
            serviceMessenger?.send(msg)
            Log.d(tag, "Notified service: Android Auto disconnected")
        } catch (e: RemoteException) {
            Log.e(tag, "Failed to send Android Auto disconnected notification", e)
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
        _isScanning.update { isScanning }
        _detectionCount.update { detectionCount }
        _scanStatus.update { scanStatus }
        _bleStatus.update { bleStatus }
        _wifiStatus.update { wifiStatus }
        _locationStatus.update { locationStatus }
        _cellularStatus.update { cellularStatus }
        _satelliteStatus.update { satelliteStatus }
    }

    internal fun updateScanning(isScanning: Boolean) {
        _isScanning.update { isScanning }
    }

    internal fun updateDetectionCount(count: Int) {
        _detectionCount.update { count }
    }

    internal fun updateSubsystemStatus(
        bleStatus: String?,
        wifiStatus: String?,
        locationStatus: String?,
        cellularStatus: String?,
        satelliteStatus: String?
    ) {
        bleStatus?.let { status -> _bleStatus.update { status } }
        wifiStatus?.let { status -> _wifiStatus.update { status } }
        locationStatus?.let { status -> _locationStatus.update { status } }
        cellularStatus?.let { status -> _cellularStatus.update { status } }
        satelliteStatus?.let { status -> _satelliteStatus.update { status } }
    }

    internal fun updateSeenBleDevices(json: String?) {
        if (json == null) return
        try {
            val type = object : TypeToken<List<ScanningService.SeenDevice>>() {}.type
            val devices: List<ScanningService.SeenDevice> = ScanningServiceIpc.gson.fromJson(json, type)
            _seenBleDevices.update { devices }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse seen BLE devices JSON", e)
        }
    }

    internal fun updateSeenWifiNetworks(json: String?) {
        if (json == null) return
        try {
            val type = object : TypeToken<List<ScanningService.SeenDevice>>() {}.type
            val networks: List<ScanningService.SeenDevice> = ScanningServiceIpc.gson.fromJson(json, type)
            _seenWifiNetworks.update { networks }
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
                val status = ScanningServiceIpc.gson.fromJson(it, CellularMonitor.CellStatus::class.java)
                _cellStatus.update { status }
            }
            seenTowersJson?.let {
                val type = object : TypeToken<List<CellularMonitor.SeenCellTower>>() {}.type
                val towers: List<CellularMonitor.SeenCellTower> = ScanningServiceIpc.gson.fromJson(it, type)
                _seenCellTowers.update { towers }
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<CellularMonitor.CellularAnomaly>>() {}.type
                val anomalies: List<CellularMonitor.CellularAnomaly> = ScanningServiceIpc.gson.fromJson(it, type)
                _cellularAnomalies.update { anomalies }
            }
            eventsJson?.let {
                val type = object : TypeToken<List<CellularMonitor.CellularEvent>>() {}.type
                val events: List<CellularMonitor.CellularEvent> = ScanningServiceIpc.gson.fromJson(it, type)
                _cellularEvents.update { events }
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
                val state = ScanningServiceIpc.gson.fromJson(it, com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState::class.java)
                _satelliteState.update { state }
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly>>() {}.type
                val anomalies: List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly> = ScanningServiceIpc.gson.fromJson(it, type)
                _satelliteAnomalies.update { anomalies }
            }
            historyJson?.let {
                val type = object : TypeToken<List<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionEvent>>() {}.type
                val history: List<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionEvent> = ScanningServiceIpc.gson.fromJson(it, type)
                _satelliteHistory.update { history }
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
                val status = ScanningServiceIpc.gson.fromJson(it, RogueWifiMonitor.WifiEnvironmentStatus::class.java)
                _rogueWifiStatus.update { status }
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<RogueWifiMonitor.WifiAnomaly>>() {}.type
                val anomalies: List<RogueWifiMonitor.WifiAnomaly> = ScanningServiceIpc.gson.fromJson(it, type)
                _rogueWifiAnomalies.update { anomalies }
            }
            suspiciousJson?.let {
                val type = object : TypeToken<List<RogueWifiMonitor.SuspiciousNetwork>>() {}.type
                val networks: List<RogueWifiMonitor.SuspiciousNetwork> = ScanningServiceIpc.gson.fromJson(it, type)
                _suspiciousNetworks.update { networks }
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
                val status = ScanningServiceIpc.gson.fromJson(it, RfSignalAnalyzer.RfEnvironmentStatus::class.java)
                _rfStatus.update { status }
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<RfSignalAnalyzer.RfAnomaly>>() {}.type
                val anomalies: List<RfSignalAnalyzer.RfAnomaly> = ScanningServiceIpc.gson.fromJson(it, type)
                _rfAnomalies.update { anomalies }
            }
            dronesJson?.let {
                val type = object : TypeToken<List<RfSignalAnalyzer.DroneInfo>>() {}.type
                val drones: List<RfSignalAnalyzer.DroneInfo> = ScanningServiceIpc.gson.fromJson(it, type)
                _detectedDrones.update { drones }
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
                val status = ScanningServiceIpc.gson.fromJson(it, UltrasonicDetector.UltrasonicStatus::class.java)
                _ultrasonicStatus.update { status }
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<UltrasonicDetector.UltrasonicAnomaly>>() {}.type
                val anomalies: List<UltrasonicDetector.UltrasonicAnomaly> = ScanningServiceIpc.gson.fromJson(it, type)
                _ultrasonicAnomalies.update { anomalies }
            }
            beaconsJson?.let {
                val type = object : TypeToken<List<UltrasonicDetector.BeaconDetection>>() {}.type
                val beacons: List<UltrasonicDetector.BeaconDetection> = ScanningServiceIpc.gson.fromJson(it, type)
                _ultrasonicBeacons.update { beacons }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse ultrasonic data JSON", e)
        }
    }

    internal fun updateLastDetection(json: String?) {
        if (json == null) {
            _lastDetection.update { null }
            return
        }
        try {
            val detection = ScanningServiceIpc.gson.fromJson(json, com.flockyou.data.model.Detection::class.java)
            _lastDetection.update { detection }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse last detection JSON", e)
        }
    }

    internal fun updateActiveDetections(json: String?) {
        if (json == null) {
            _activeDetections.update { emptyList() }
            return
        }
        try {
            val type = object : TypeToken<List<com.flockyou.data.model.Detection>>() {}.type
            val detections: List<com.flockyou.data.model.Detection> = ScanningServiceIpc.gson.fromJson(json, type)
            _activeDetections.update { detections }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse active detections JSON", e)
        }
    }

    internal fun updateGnssData(
        statusJson: String?,
        satellitesJson: String?,
        anomaliesJson: String?,
        eventsJson: String?,
        measurementsJson: String?
    ) {
        try {
            statusJson?.let {
                val status = ScanningServiceIpc.gson.fromJson(it, com.flockyou.monitoring.GnssSatelliteMonitor.GnssEnvironmentStatus::class.java)
                _gnssStatus.update { status }
            }
            satellitesJson?.let {
                val type = object : TypeToken<List<com.flockyou.monitoring.GnssSatelliteMonitor.SatelliteInfo>>() {}.type
                val satellites: List<com.flockyou.monitoring.GnssSatelliteMonitor.SatelliteInfo> = ScanningServiceIpc.gson.fromJson(it, type)
                _gnssSatellites.update { satellites }
            }
            anomaliesJson?.let {
                val type = object : TypeToken<List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly>>() {}.type
                val anomalies: List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly> = ScanningServiceIpc.gson.fromJson(it, type)
                _gnssAnomalies.update { anomalies }
            }
            eventsJson?.let {
                val type = object : TypeToken<List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEvent>>() {}.type
                val events: List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEvent> = ScanningServiceIpc.gson.fromJson(it, type)
                _gnssEvents.update { events }
            }
            measurementsJson?.let {
                val measurements = ScanningServiceIpc.gson.fromJson(it, com.flockyou.monitoring.GnssSatelliteMonitor.GnssMeasurementData::class.java)
                _gnssMeasurements.update { measurements }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse GNSS data JSON", e)
        }
    }

    internal fun updateDetectorHealth(json: String?) {
        if (json == null) {
            Log.w(tag, "Received null detector health JSON")
            return
        }
        try {
            Log.d(tag, "Received detector health JSON (${json.length} chars)")
            val type = object : TypeToken<Map<String, ScanningService.DetectorHealthStatus>>() {}.type
            val health: Map<String, ScanningService.DetectorHealthStatus> = ScanningServiceIpc.gson.fromJson(json, type)
            Log.d(tag, "Parsed detector health: ${health.size} detectors, running=${health.values.count { it.isRunning }}")
            Log.d(tag, "Current _detectorHealth value before update: ${_detectorHealth.value.size} detectors")
            _detectorHealth.update { health }
            Log.d(tag, "_detectorHealth value after update: ${_detectorHealth.value.size} detectors")
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse detector health JSON: $json", e)
        }
    }

    internal fun updateErrorLog(json: String?) {
        if (json == null) return
        try {
            val type = object : TypeToken<List<ScanningService.ScanError>>() {}.type
            val errors: List<ScanningService.ScanError> = ScanningServiceIpc.gson.fromJson(json, type)
            _errorLog.update { errors }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse error log JSON", e)
        }
    }

    internal fun notifyDetectionRefresh() {
        _detectionRefreshEvent.tryEmit(Unit)
    }

    internal fun updateScanStats(json: String?) {
        if (json == null) return
        try {
            val stats: ScanningService.ScanStatistics = ScanningServiceIpc.gson.fromJson(json, ScanningService.ScanStatistics::class.java)
            _scanStats.update { stats }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse scan stats JSON", e)
        }
    }

    internal fun updateThreadingData(
        systemStateJson: String?,
        scannerStatesJson: String?,
        alertsJson: String?
    ) {
        try {
            systemStateJson?.let {
                val state = ScanningServiceIpc.gson.fromJson(
                    it,
                    com.flockyou.monitoring.ScannerThreadingMonitor.SystemThreadingState::class.java
                )
                _threadingSystemState.update { state }
            }
            scannerStatesJson?.let {
                val type = object : TypeToken<Map<String, com.flockyou.monitoring.ScannerThreadingMonitor.ScannerThreadState>>() {}.type
                val states: Map<String, com.flockyou.monitoring.ScannerThreadingMonitor.ScannerThreadState> = ScanningServiceIpc.gson.fromJson(it, type)
                _threadingScannerStates.update { states }
            }
            alertsJson?.let {
                val type = object : TypeToken<List<com.flockyou.monitoring.ScannerThreadingMonitor.ThreadingAlert>>() {}.type
                val alerts: List<com.flockyou.monitoring.ScannerThreadingMonitor.ThreadingAlert> = ScanningServiceIpc.gson.fromJson(it, type)
                _threadingAlerts.update { alerts }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse threading data JSON", e)
        }
    }
}

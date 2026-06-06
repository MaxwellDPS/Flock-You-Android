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
import com.flockyou.BuildConfig
import com.flockyou.MainActivity
import com.flockyou.R
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.detection.DetectionRegistry
import com.flockyou.detection.handler.BleDetectionHandler
import com.flockyou.detection.handler.CellularDetectionHandler
import com.flockyou.detection.handler.SatelliteDetectionHandler
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import com.flockyou.worker.BackgroundAnalysisWorker

private const val WAKE_LOCK_TAG = "FlockYou:ScanningWakeLock"

/**
 * Foreground service that continuously scans for surveillance devices
 * using both Bluetooth LE and WiFi.
 *
 * This file contains the service lifecycle, core scan loop, BLE/WiFi scanning,
 * health management, battery monitoring, and nuke handling.
 *
 * Related files (extension functions on this class):
 * - ScanningServiceBroadcaster.kt: All IPC broadcast methods
 * - SubsystemManager.kt: Start/stop logic for 6 subsystems (Cellular, Satellite, Rogue WiFi, RF, Ultrasonic, GNSS)
 * - DetectionProcessor.kt: Detection result handling, alerts, automation broadcasts, handler delegation
 * - ScanningServiceState.kt: Shared state flows (companion object state)
 * - ScanningServiceModels.kt: Data classes (ScanConfig, SeenDevice, ScanStatus, etc.)
 * - ScanningServiceIpc.kt: IPC constants and client-side connection
 */
@AndroidEntryPoint
class ScanningService : Service() {

    companion object {
        private const val TAG = "ScanningService"
        private const val NOTIFICATION_ID = 1001
        internal const val SATELLITE_CONNECTION_NOTIF_ID = 9999
        internal const val CHANNEL_ID = "flockyou_scanning"

        // Delegations to ScanningServiceState for backward compatibility.
        // New code should use ScanningServiceState directly.
        const val ACTION_NUKE_INITIATED = ScanningServiceState.ACTION_NUKE_INITIATED
        const val ACTION_DATABASE_SHUTDOWN = ScanningServiceState.ACTION_DATABASE_SHUTDOWN
        val isDatabaseAvailable get() = ScanningServiceState.isDatabaseAvailable
        val currentSettings get() = ScanningServiceState.currentSettings
        val isScanning get() = ScanningServiceState.isScanning
        val lastDetection get() = ScanningServiceState.lastDetection
        val detectionCount get() = ScanningServiceState.detectionCount
        val scanStatus get() = ScanningServiceState.scanStatus
        val bleStatus get() = ScanningServiceState.bleStatus
        val wifiStatus get() = ScanningServiceState.wifiStatus
        val locationStatus get() = ScanningServiceState.locationStatus
        val cellularStatus get() = ScanningServiceState.cellularStatus
        val errorLog get() = ScanningServiceState.errorLog
        val seenBleDevices get() = ScanningServiceState.seenBleDevices
        val seenWifiNetworks get() = ScanningServiceState.seenWifiNetworks
        val cellStatus get() = ScanningServiceState.cellStatus
        val seenCellTowers get() = ScanningServiceState.seenCellTowers
        val cellularAnomalies get() = ScanningServiceState.cellularAnomalies
        val cellularEvents get() = ScanningServiceState.cellularEvents
        val satelliteState get() = ScanningServiceState.satelliteState
        val satelliteAnomalies get() = ScanningServiceState.satelliteAnomalies
        val satelliteHistory get() = ScanningServiceState.satelliteHistory
        val satelliteStatus get() = ScanningServiceState.satelliteStatus
        val rogueWifiStatus get() = ScanningServiceState.rogueWifiStatus
        val rogueWifiAnomalies get() = ScanningServiceState.rogueWifiAnomalies
        val rogueWifiEvents get() = ScanningServiceState.rogueWifiEvents
        val suspiciousNetworks get() = ScanningServiceState.suspiciousNetworks
        val rfStatus get() = ScanningServiceState.rfStatus
        val rfAnomalies get() = ScanningServiceState.rfAnomalies
        val rfEvents get() = ScanningServiceState.rfEvents
        val detectedDrones get() = ScanningServiceState.detectedDrones
        val ultrasonicStatus get() = ScanningServiceState.ultrasonicStatus
        val ultrasonicAnomalies get() = ScanningServiceState.ultrasonicAnomalies
        val ultrasonicEvents get() = ScanningServiceState.ultrasonicEvents
        val ultrasonicBeacons get() = ScanningServiceState.ultrasonicBeacons
        val gnssStatus get() = ScanningServiceState.gnssStatus
        val gnssSatellites get() = ScanningServiceState.gnssSatellites
        val gnssAnomalies get() = ScanningServiceState.gnssAnomalies
        val gnssEvents get() = ScanningServiceState.gnssEvents
        val gnssMeasurements get() = ScanningServiceState.gnssMeasurements
        val gnssMonitorStatus get() = ScanningServiceState.gnssMonitorStatus
        val detectorHealth get() = ScanningServiceState.detectorHealth
        val currentBatteryMode get() = ScanningServiceState.currentBatteryMode
        val currentBatteryLevel get() = ScanningServiceState.currentBatteryLevel
        val isBatteryCharging get() = ScanningServiceState.isBatteryCharging
        val scanStats get() = ScanningServiceState.scanStats
        val detectionRefreshEvent get() = ScanningServiceState.detectionRefreshEvent
        val learningModeEnabled get() = ScanningServiceState.learningModeEnabled
        val learnedSignatures get() = ScanningServiceState.learnedSignatures
        val highActivityDevices get() = ScanningServiceState.highActivityDevices

        // Constants for health monitoring
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
        private const val DETECTOR_STALE_THRESHOLD_MS = 120_000L
        private const val BLE_HEALTH_UPDATE_THROTTLE_MS = 5_000L
        private const val BLE_WATCHDOG_THRESHOLD_MS = 60_000L
        private const val BLE_WATCHDOG_MAX_FAILURES = 3

        fun clearErrors() = ScanningServiceState.clearErrors()
        fun clearSeenDevices() = ScanningServiceState.clearSeenDevices()
        fun enableLearningMode() = ScanningServiceState.enableLearningMode()
        fun disableLearningMode() = ScanningServiceState.disableLearningMode()
        fun learnDeviceSignature(device: SeenDevice, notes: String? = null) =
            ScanningServiceState.learnDeviceSignature(device, notes)
        fun clearLearnedSignatures() = ScanningServiceState.clearLearnedSignatures()
        fun trackPacket(macAddress: String) = ScanningServiceState.trackPacket(macAddress)
        fun clearCellularHistory() = ScanningServiceState.clearCellularHistory()
        fun clearSatelliteHistory() = ScanningServiceState.clearSatelliteHistory()
        fun updateSettings(
            wifiIntervalSeconds: Int = 35,
            bleDurationSeconds: Int = 10,
            inactiveTimeoutSeconds: Int = 60,
            seenDeviceTimeoutMinutes: Int = 5,
            enableBle: Boolean = true,
            enableWifi: Boolean = true,
            enableCellular: Boolean = true,
            trackSeenDevices: Boolean = true
        ) = ScanningServiceState.updateSettings(
            wifiIntervalSeconds, bleDurationSeconds, inactiveTimeoutSeconds,
            seenDeviceTimeoutMinutes, enableBle, enableWifi, enableCellular, trackSeenDevices
        )
        fun forceStop(context: Context) = ScanningServiceState.forceStop(context)
    }

    // ==================== Injected Dependencies ====================

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

    @Inject
    lateinit var detectionSettingsRepository: com.flockyou.data.DetectionSettingsRepository

    @Inject
    lateinit var threadingMonitor: com.flockyou.monitoring.ScannerThreadingMonitor

    // ==================== Detection Handler System ====================
    //
    // These handlers implement the standardized DetectionHandler interface for
    // protocol-specific detection logic. They replace inline detection logic
    // scattered throughout this service for better separation of concerns,
    // testability, and AI prompt generation.
    //
    // Migration Status:
    // - BLE Detection: MIGRATED - processBleScanResult() delegates to BleDetectionHandler
    // - WiFi Detection: MIGRATED - processWifiScanResults() delegates to WifiDetectionHandler
    //                   which includes SSID/MAC pattern matching and RogueWifiMonitor integration
    // - Cellular Detection: MIGRATED - cellular anomalies use CellularDetectionHandler
    // - Satellite Detection: MIGRATED - satellite anomalies use SatelliteDetectionHandler
    //
    // All handlers are registered with DetectionRegistry for dynamic lookup via:
    // val handler = detectionRegistry.getHandler(DetectionProtocol.WIFI)
    //
    // Additional Handlers:
    // - LearnedSignatureHandler: User-learned device signatures for custom tracking
    //
    // TODO: Future improvements:
    // 1. Add cross-protocol correlation handler
    // 2. Register LearnedSignatureHandler with DetectionRegistry

    @Inject
    lateinit var detectionRegistry: DetectionRegistry

    @Inject
    lateinit var bleDetectionHandler: BleDetectionHandler

    @Inject
    lateinit var cellularDetectionHandler: CellularDetectionHandler

    @Inject
    lateinit var satelliteDetectionHandler: SatelliteDetectionHandler

    @Inject
    lateinit var wifiDetectionHandler: com.flockyou.detection.handler.WifiDetectionHandler

    @Inject
    lateinit var learnedSignatureHandler: com.flockyou.detection.handler.LearnedSignatureHandler

    @Inject
    lateinit var falsePositiveAnalyzer: com.flockyou.ai.FalsePositiveAnalyzer

    @Inject
    lateinit var enrichedDataCache: com.flockyou.ai.EnrichedDataCache

    @Inject
    lateinit var llmEngineManager: com.flockyou.ai.LlmEngineManager

    @Inject
    lateinit var deduplicator: com.flockyou.detection.DetectionDeduplicator

    @Inject
    lateinit var crossDomainAnalyzer: com.flockyou.ai.correlation.CrossDomainAnalyzer

    // ==================== Settings State ====================

    internal var currentBroadcastSettings: com.flockyou.data.BroadcastSettings = com.flockyou.data.BroadcastSettings()

    internal var currentPrivacySettings: com.flockyou.data.PrivacySettings = com.flockyou.data.PrivacySettings()

    internal var currentNotificationSettings: com.flockyou.data.NotificationSettings = com.flockyou.data.NotificationSettings()

    internal var currentDetectionSettings: com.flockyou.data.DetectionSettings = com.flockyou.data.DetectionSettings()

    // Current scan settings for battery-adaptive mode calculations
    internal var currentScanSettings: com.flockyou.data.ScanSettings = com.flockyou.data.ScanSettings()

    // ==================== Service State ====================

    // Screen lock receiver for auto-purge feature (Priority 5)
    private var screenLockReceiver: ScreenLockReceiver? = null

    // Nuke receiver for graceful shutdown during data wipe
    private var nukeReceiver: BroadcastReceiver? = null

    internal val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    // Bluetooth
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isBleScanningActive = false
    private var lastBleHealthUpdateTime: Long = 0L
    private var lastBleScanResultTime: Long = 0L  // Track when we last received any BLE scan result
    private var bleWatchdogFailures: Int = 0  // Count consecutive watchdog failures for BLE

    // WiFi
    private lateinit var wifiManager: WifiManager
    private var wifiScanReceiver: BroadcastReceiver? = null

    // Location

    internal var currentLocation: Location? = null

    // Vibration
    internal lateinit var vibrator: Vibrator

    // Scan job
    private var scanJob: Job? = null

    // Settings collector jobs (for proper lifecycle management)
    private var broadcastSettingsJob: Job? = null
    private var privacySettingsJob: Job? = null
    private var scanSettingsJob: Job? = null
    private var notificationSettingsJob: Job? = null
    private var detectionSettingsJob: Job? = null

    // Location update jobs (for proper lifecycle management)
    internal var cellularLocationJob: Job? = null
    internal var rogueWifiLocationJob: Job? = null
    internal var rfLocationJob: Job? = null
    internal var ultrasonicLocationJob: Job? = null
    internal var gnssLocationJob: Job? = null
    internal var suspiciousNetworksJob: Job? = null

    // Cellular monitor
    internal var cellularMonitor: CellularMonitor? = null

    // Satellite monitor
    internal var satelliteMonitor: com.flockyou.monitoring.SatelliteMonitor? = null

    // Rogue WiFi monitor
    internal var rogueWifiMonitor: RogueWifiMonitor? = null

    // RF signal analyzer
    internal var rfSignalAnalyzer: RfSignalAnalyzer? = null

    // Ultrasonic detector
    internal var ultrasonicDetector: UltrasonicDetector? = null

    // GNSS satellite monitor
    internal var gnssSatelliteMonitor: com.flockyou.monitoring.GnssSatelliteMonitor? = null

    // Shannon SDM diagnostic monitor (OEM only)
    internal var shannonDiagMonitor: com.flockyou.shannon.ShannonDiagMonitor? = null
    internal var shannonStatusJob: Job? = null
    internal var shannonAnomalyJob: Job? = null
    internal val processedShannonAnomalyIds = mutableSetOf<String>()

    // Health check job for monitoring detector health
    private var healthCheckJob: Job? = null

    // Throttle cleanup job for periodic deduplicator cache cleanup
    private var throttleCleanupJob: Job? = null

    // Cross-domain correlation analysis job
    private var correlationAnalysisJob: Job? = null
    private val CORRELATION_ANALYSIS_INTERVAL_MS = 60_000L  // Run correlation analysis every 60 seconds

    // Periodic IPC refresh job - ensures UI stays updated even when no events occur
    private var ipcRefreshJob: Job? = null
    private val IPC_REFRESH_INTERVAL_MS = 5000L  // Refresh every 5 seconds

    // Battery monitoring for adaptive scanning
    private var batteryReceiver: BroadcastReceiver? = null
    internal var currentBatteryPercent: Int = 100
    internal var isCharging: Boolean = false

    // Track processed anomaly IDs to prevent duplicates from StateFlow replays
    // These persist across function calls to prevent duplicates when monitors restart
    internal val processedGnssAnomalyIds = mutableSetOf<String>()
    internal val processedCellularAnomalyIds = mutableSetOf<String>()

    // Subsystem collection jobs (accessed from SubsystemManager.kt extension functions)
    internal var cellularAnomalyJob: Job? = null
    internal var cellularStatusJob: Job? = null
    internal var cellularHistoryJob: Job? = null
    internal var cellularEventsJob: Job? = null
    internal var satelliteStateJob: Job? = null
    internal var satelliteAnomalyJob: Job? = null
    internal var rogueWifiStatusJob: Job? = null
    internal var rogueWifiAnomalyJob: Job? = null
    internal var rogueWifiEventsJob: Job? = null
    internal var rfStatusJob: Job? = null
    internal var rfAnomalyJob: Job? = null
    internal var rfEventsJob: Job? = null
    internal var rfDronesJob: Job? = null
    internal var ultrasonicStatusJob: Job? = null
    internal var ultrasonicAnomalyJob: Job? = null
    internal var ultrasonicEventsJob: Job? = null
    internal var ultrasonicBeaconsJob: Job? = null
    internal var gnssStatusJob: Job? = null
    internal var gnssAnomalyJob: Job? = null
    internal var gnssEventsJob: Job? = null
    internal var gnssSatellitesJob: Job? = null
    internal var gnssMeasurementsJob: Job? = null

    // Detector callback implementation for handling errors and health updates
    internal val detectorCallbackImpl = object : DetectorCallback {
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

    // Android Auto boost mode tracking
    /** Number of Android Auto clients currently connected */
    private val androidAutoClientCount = AtomicInteger(0)

    /** Whether boost mode is active (faster scanning for Android Auto) */
    internal val isBoostModeActive: Boolean
        get() = androidAutoClientCount.get() > 0

    // IPC: Messenger for cross-process communication
    // Using CopyOnWriteArrayList for thread-safe iteration and modification
    internal val ipcClients = java.util.concurrent.CopyOnWriteArrayList<Messenger>()

    // Background HandlerThread for IPC processing to avoid blocking main thread
    // All message handling is wrapped in try-catch to prevent the handler thread from dying
    private val ipcHandlerThread = HandlerThread("ScanningServiceIpc").apply { start() }
    private val ipcHandler = object : Handler(ipcHandlerThread.looper) {
        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    ScanningServiceIpc.MSG_REGISTER_CLIENT -> {
                        msg.replyTo?.let { client ->
                            if (!ipcClients.contains(client)) {
                                ipcClients.add(client)
                                threadingMonitor.updateIpcClientCount(ipcClients.size)
                                Log.d(TAG, "IPC client registered (total: ${ipcClients.size})")
                                // Start IPC refresh if this is the first client
                                if (ipcClients.size == 1 && ipcRefreshJob == null) {
                                    startIpcRefreshJob()
                                }
                            }
                        }
                    }
                    ScanningServiceIpc.MSG_UNREGISTER_CLIENT -> {
                        msg.replyTo?.let { client ->
                            ipcClients.remove(client)
                            threadingMonitor.updateIpcClientCount(ipcClients.size)
                            Log.d(TAG, "IPC client unregistered (total: ${ipcClients.size})")
                            // Stop IPC refresh if no clients remain
                            if (ipcClients.isEmpty()) {
                                stopIpcRefreshJob()
                            }
                        }
                    }
                    ScanningServiceIpc.MSG_REQUEST_STATE -> {
                        Log.d(TAG, "MSG_REQUEST_STATE received, replyTo=${msg.replyTo}")
                        msg.replyTo?.let { client ->
                            Log.d(TAG, "Sending state to client...")
                            sendStateToClient(client)
                            Log.d(TAG, "State sent to client")
                        } ?: Log.w(TAG, "MSG_REQUEST_STATE received but replyTo is null!")
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
                    ScanningServiceIpc.MSG_UPDATE_SCAN_SETTINGS -> {
                        val data = msg.data
                        ScanningServiceState.updateSettings(
                            wifiIntervalSeconds = data.getInt(ScanningServiceIpc.KEY_WIFI_INTERVAL, 35),
                            bleDurationSeconds = data.getInt(ScanningServiceIpc.KEY_BLE_DURATION, 10),
                            enableBle = data.getBoolean(ScanningServiceIpc.KEY_ENABLE_BLE, true),
                            enableWifi = data.getBoolean(ScanningServiceIpc.KEY_ENABLE_WIFI, true),
                            enableCellular = data.getBoolean(ScanningServiceIpc.KEY_ENABLE_CELLULAR, true),
                            trackSeenDevices = data.getBoolean(ScanningServiceIpc.KEY_TRACK_SEEN_DEVICES, true)
                        )
                    }
                    ScanningServiceIpc.MSG_CLEAR_LEARNED_SIGNATURES -> {
                        learnedSignatureHandler.clearSignatures()
                        // Keep companion object in sync for backward compatibility
                        clearLearnedSignatures()
                    }
                    ScanningServiceIpc.MSG_REQUEST_THREADING_DATA -> {
                        msg.replyTo?.let { client ->
                            sendThreadingDataToClient(client)
                        }
                    }
                    ScanningServiceIpc.MSG_ANDROID_AUTO_CONNECTED -> {
                        val count = androidAutoClientCount.incrementAndGet()
                        Log.i(TAG, "Android Auto connected (total: $count) - boost mode ${if (isBoostModeActive) "ACTIVE" else "inactive"}")
                        broadcastBoostStatus()
                    }
                    ScanningServiceIpc.MSG_ANDROID_AUTO_DISCONNECTED -> {
                        val count = androidAutoClientCount.decrementAndGet().coerceAtLeast(0)
                        if (count == 0) {
                            androidAutoClientCount.set(0) // Ensure non-negative
                        }
                        Log.i(TAG, "Android Auto disconnected (remaining: $count) - boost mode ${if (isBoostModeActive) "ACTIVE" else "inactive"}")
                        broadcastBoostStatus()
                    }
                    ScanningServiceIpc.MSG_REQUEST_BOOST_STATUS -> {
                        broadcastBoostStatus()
                    }
                    else -> super.handleMessage(msg)
                }
            } catch (e: Exception) {
                // Log the error but don't let it crash the handler thread
                Log.e(TAG, "Error handling IPC message ${msg.what}: ${e.message}", e)
            }
        }
    }
    private val ipcMessenger = Messenger(ipcHandler)

    // ==================== Service Lifecycle ====================

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


        // Initialize Vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Initialize Cellular Monitor
        cellularMonitor = CellularMonitor(applicationContext, detectorCallbackImpl).also {
            // Set ephemeral mode from current privacy settings (will be updated by settings collector)
            it.setEphemeralMode(currentPrivacySettings.ephemeralModeEnabled)
        }

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

        // Initialize Shannon SDM Diagnostic Monitor (OEM only)
        if (com.flockyou.config.OemFeatureFlags.SHANNON_DIAG_ENABLED) {
            shannonDiagMonitor = com.flockyou.shannon.ShannonDiagMonitor(applicationContext) { name, error ->
                detectorCallbackImpl.onError(name, error, true)
            }
        }

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
        shannonDiagMonitor?.destroy()
        shannonDiagMonitor = null

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

    // ==================== Wake Lock ====================

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

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
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
            .setContentTitle(getString(R.string.notification_scanning_title))
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

    // ==================== Core Scan Loop ====================

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
                    enrichedDataCache.clear() // Also clear enriched data for privacy
                    Log.d(TAG, "Ephemeral mode active - in-memory storage only")
                }

                // Update cellular monitor ephemeral mode
                cellularMonitor?.setEphemeralMode(settings.ephemeralModeEnabled)

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

                // Store current settings for battery-adaptive calculations
                currentScanSettings = settings

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

                // Recalculate effective battery mode when settings change
                updateEffectiveBatteryMode()
            }
        }

        // Collect notification settings for emergency popup feature
        notificationSettingsJob = serviceScope.launch {
            notificationSettingsRepository.settings.collect { settings ->
                currentNotificationSettings = settings
                Log.d(TAG, "Notification settings updated - emergency popup: ${settings.emergencyPopupEnabled}")
            }
        }

        // Collect detection settings for RF anomaly and tracking thresholds
        detectionSettingsJob = serviceScope.launch {
            detectionSettingsRepository.settings.collect { settings ->
                currentDetectionSettings = settings
                // Update RF signal analyzer with hidden network anomaly setting
                rfSignalAnalyzer?.enableHiddenNetworkRfAnomaly = settings.enableHiddenNetworkRfAnomaly
                // Update rogue WiFi monitor with tracking distance threshold
                rogueWifiMonitor?.minTrackingDistanceMeters = settings.wifiThresholds.minTrackingDistanceMeters
                Log.d(TAG, "Detection settings updated - hidden network RF anomaly: ${settings.enableHiddenNetworkRfAnomaly}, min tracking distance: ${settings.wifiThresholds.minTrackingDistanceMeters}m")
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

        // Register battery receiver for adaptive scanning
        registerBatteryReceiver()

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
        broadcastSubsystemStatus()

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

        // Start Shannon SDM diagnostic monitoring (OEM only, after cellular)
        startShannonDiagMonitoring()

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

        // Start periodic throttle cache cleanup for deduplicator
        startThrottleCleanup()

        // Start periodic IPC refresh to keep UI updated
        startIpcRefreshJob()

        // Start cross-domain correlation analysis job
        startCorrelationAnalysisJob()

        // Start threading monitor for scanner performance tracking
        threadingMonitor.startMonitoring()
        threadingMonitor.updateIpcClientCount(ipcClients.size)

        // Warm up the LLM engine in the background for faster FP analysis
        // This is non-blocking and failures are logged but don't crash the service
        warmUpLlmEngine()

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

                // Get battery-adaptive multipliers
                val (batteryIntervalMultiplier, batteryDurationMultiplier) = getBatteryMultipliers()
                val batteryMode = currentBatteryMode.value

                // Boost mode reduces intervals by ~40% for faster detection on Android Auto
                // Battery mode adjusts based on battery level (higher multiplier = longer intervals)
                // Boost takes priority over battery mode
                val boostMultiplier = if (isBoostModeActive) 0.6f else 1.0f

                // Combine boost and battery multipliers:
                // - For duration: boost reduces, battery mode may also reduce (0.5x-1.2x)
                // - For intervals: boost reduces, battery mode increases (0.7x-2.5x)
                val effectiveDurationMultiplier = boostMultiplier * batteryDurationMultiplier
                val effectiveIntervalMultiplier = if (isBoostModeActive) boostMultiplier else batteryIntervalMultiplier

                // Apply multipliers to BLE scan duration
                val effectiveBleScanDuration = (scanConfig.bleScanDuration * effectiveDurationMultiplier).toLong()
                    .coerceAtLeast(5_000L) // Minimum 5 seconds

                // Apply multipliers to BLE cooldown
                val effectiveBleCooldown = (scanConfig.bleCooldown * effectiveIntervalMultiplier).toLong()
                    .coerceAtLeast(2_000L) // Minimum 2 seconds

                if (isBoostModeActive) {
                    Log.d(TAG, "Boost mode active - using ${effectiveBleScanDuration}ms scan, ${effectiveBleCooldown}ms cooldown")
                } else if (batteryMode != com.flockyou.data.BatteryAdaptiveMode.BALANCED) {
                    Log.d(TAG, "Battery mode: ${batteryMode.displayName} (${currentBatteryPercent}%) - " +
                            "${effectiveBleScanDuration}ms scan, ${effectiveBleCooldown}ms cooldown")
                }

                // Refresh wake lock to prevent timeout
                acquireWakeLock()

                try {
                    // === HEARTBEAT ===
                    // Send heartbeat every cycle to prove we're alive
                    ServiceRestartReceiver.recordHeartbeat(this@ScanningService)

                    // === BLE BURST SCAN ===
                    if (scanConfig.enableBle) {
                        try {
                            startBleScan(scanConfig.aggressiveBleMode)
                            delay(effectiveBleScanDuration)
                            stopBleScan()
                            consecutiveBleErrors = 0 // Reset on success

                            // Thermal cooldown period
                            Log.d(TAG, "BLE cooldown: ${effectiveBleCooldown}ms")
                            delay(effectiveBleCooldown)
                        } catch (e: Exception) {
                            consecutiveBleErrors++
                            Log.e(TAG, "BLE scan error (consecutive: $consecutiveBleErrors)", e)
                            logError("BLE", -1, "Scan error: ${e.message}", recoverable = true)

                            if (consecutiveBleErrors >= 3) {
                                Log.w(TAG, "Too many BLE errors, pausing BLE for this cycle")
                                bleStatus.value = SubsystemStatus.Error(-1, "Paused due to errors")
                                delay(effectiveBleCooldown * 2) // Extended cooldown
                            }
                        }
                    }

                    // === WiFi SCAN ===
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
        detectionSettingsJob?.cancel()
        detectionSettingsJob = null

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

        // Stop throttle cleanup job
        stopThrottleCleanup()

        // Stop IPC refresh job
        stopIpcRefreshJob()

        // Stop correlation analysis job
        stopCorrelationAnalysisJob()

        // Stop threading monitor
        threadingMonitor.stopMonitoring()

        // Unregister screen lock receiver
        screenLockReceiver?.let {
            ScreenLockReceiver.unregister(this, it)
            screenLockReceiver = null
        }

        // Unregister nuke receiver
        unregisterNukeReceiver()

        // Unregister battery receiver
        unregisterBatteryReceiver()

        // Reset subsystem statuses
        bleStatus.value = SubsystemStatus.Idle
        wifiStatus.value = SubsystemStatus.Idle
        locationStatus.value = SubsystemStatus.Idle
        cellularStatus.value = SubsystemStatus.Idle
        satelliteStatus.value = SubsystemStatus.Idle
        scanStatus.value = ScanStatus.Idle

        // Broadcast updated statuses to UI
        broadcastSubsystemStatus()

        Log.d(TAG, "Stopped scanning")
    }

    // ==================== Seen Device Management ====================

    private fun cleanupSeenDevices(timeout: Long) {
        val cutoff = System.currentTimeMillis() - timeout
        val bleCountBefore = seenBleDevices.value.size
        val wifiCountBefore = seenWifiNetworks.value.size
        synchronized(ScanningServiceState) {
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

    private fun trackSeenBleDevice(
        macAddress: String,
        deviceName: String?,
        rssi: Int,
        serviceUuids: List<java.util.UUID>,
        manufacturerData: Map<Int, String> = emptyMap(),
        advertisingRate: Float = 0f
    ) {
        // Synchronize to prevent race conditions when multiple scan results arrive concurrently
        synchronized(ScanningServiceState) {
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
                if (currentList.size > 100) {
                    currentList.removeAt(currentList.lastIndex)
                }
            }

            seenBleDevices.value = currentList
            broadcastSeenBleDevices()
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

            if (currentList.size > 100) {
                currentList.removeAt(currentList.lastIndex)
            }
        }

        seenWifiNetworks.value = currentList
        broadcastSeenWifiNetworks()
    }

    // ==================== Status ====================

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

    internal fun logError(subsystem: String, code: Int, message: String, recoverable: Boolean = true) {
        val error = ScanError(
            subsystem = subsystem,
            code = code,
            message = message,
            recoverable = recoverable
        )
        Log.e(TAG, "[$subsystem] Error $code: $message")

        val currentErrors = errorLog.value.toMutableList()
        currentErrors.add(0, error)
        if (currentErrors.size > 50) {
            currentErrors.removeAt(currentErrors.lastIndex)
        }
        errorLog.value = currentErrors
        broadcastErrorLog()
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
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .apply {
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
            // Initialize watchdog timer
            lastBleScanResultTime = System.currentTimeMillis()
            bleWatchdogFailures = 0
            // Update total BLE scan count
            scanStats.value = scanStats.value.copy(totalBleScans = scanStats.value.totalBleScans + 1)
            broadcastScanStats()
            // Update detector health status
            detectorCallbackImpl.onDetectorStarted(DetectorHealthStatus.DETECTOR_BLE)
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
            // Update detector health status
            detectorCallbackImpl.onDetectorStopped(DetectorHealthStatus.DETECTOR_BLE)
            Log.d(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE scan", e)
        }
    }

    /** BLE scan callback - handles scan results for surveillance device detection */
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Update watchdog timestamp - we're receiving results
            lastBleScanResultTime = System.currentTimeMillis()
            bleWatchdogFailures = 0
            serviceScope.launch {
                processBleScanResult(result)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            lastBleScanResultTime = System.currentTimeMillis()
            bleWatchdogFailures = 0
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
                isBleScanningActive = true
                bleStatus.value = SubsystemStatus.Active
                return
            }

            bleStatus.value = SubsystemStatus.Error(errorCode, errorMessage)
            logError("BLE", errorCode, errorMessage, recoverable = errorCode != SCAN_FAILED_FEATURE_UNSUPPORTED)
            isBleScanningActive = false

            // Report error to health check system
            val recoverable = errorCode != SCAN_FAILED_FEATURE_UNSUPPORTED
            detectorCallbackImpl.onError(DetectorHealthStatus.DETECTOR_BLE, errorMessage, recoverable)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun processBleScanResult(result: ScanResult) {
        val device = result.device
        val macAddress = device.address ?: return
        val deviceName = device.name
        val rssi = result.rssi
        val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

        // Extract manufacturer data for detection handlers
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

        // Update scan stats
        scanStats.value = scanStats.value.copy(
            bleDevicesSeen = scanStats.value.bleDevicesSeen + 1,
            lastBleSuccessTime = System.currentTimeMillis()
        )
        broadcastScanStats()

        // Report successful scan for health monitoring (throttled to avoid excessive updates)
        val now = System.currentTimeMillis()
        if (now - lastBleHealthUpdateTime >= BLE_HEALTH_UPDATE_THROTTLE_MS) {
            lastBleHealthUpdateTime = now
            detectorCallbackImpl.onScanSuccess(DetectorHealthStatus.DETECTOR_BLE)
        }

        // ==================== Handler-Based Detection ====================
        val handlerResult = processBleWithHandler(result)
        if (handlerResult != null) {
            // Handler found a detection - process it
            handleDetection(handlerResult.detection)

            // Log AI prompt availability for debugging
            if (BuildConfig.DEBUG && handlerResult.aiPrompt.isNotEmpty()) {
                Log.d(TAG, "BLE detection has AI prompt available (${handlerResult.aiPrompt.length} chars)")
            }

            return
        }

        // ==================== No Detection - Fallback Logic ====================
        // Track as seen device if enabled
        if (currentSettings.value.trackSeenDevices) {
            trackSeenBleDevice(macAddress, deviceName, rssi, serviceUuids, manufacturerData, advertisingRate)
        }

        // ==================== Learned Signature Detection ====================
        if (learnedSignatureHandler.learningModeEnabled.value) {
            checkLearnedSignaturesViaHandler(macAddress, deviceName, rssi, serviceUuids, manufacturerData)
        }
    }

    /**
     * Check a BLE device against learned signatures using LearnedSignatureHandler.
     */
    private suspend fun checkLearnedSignaturesViaHandler(
        macAddress: String,
        deviceName: String?,
        rssi: Int,
        serviceUuids: List<java.util.UUID>,
        manufacturerData: Map<Int, String>
    ) {
        val context = com.flockyou.detection.handler.LearnedSignatureContext.Ble(
            macAddress = macAddress,
            deviceName = deviceName,
            rssi = rssi,
            serviceUuids = serviceUuids,
            manufacturerIds = manufacturerData.keys.toList()
        )

        val detection = learnedSignatureHandler.processBleDevice(context)
        if (detection != null) {
            handleDetection(detection)
        }
    }

    // ==================== WiFi Scanning ====================

    // WiFi scan optimization: track last successful scan to avoid wasted API calls
    private var lastSuccessfulWifiScanTime: Long = 0L
    private var wifiScanAttemptsSinceSuccess: Int = 0

    private val MIN_WIFI_SCAN_INTERVAL_MS = 20_000L
    private val MAX_WIFI_SCAN_INTERVAL_MS = 120_000L

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

        // Optimization: Skip scan if we're within the throttle window
        val now = System.currentTimeMillis()
        val timeSinceLastScan = now - lastSuccessfulWifiScanTime

        val adaptiveInterval = if (wifiScanAttemptsSinceSuccess > 0) {
            (MIN_WIFI_SCAN_INTERVAL_MS * (1 shl wifiScanAttemptsSinceSuccess.coerceAtMost(3)))
                .coerceAtMost(MAX_WIFI_SCAN_INTERVAL_MS)
        } else {
            MIN_WIFI_SCAN_INTERVAL_MS
        }

        if (timeSinceLastScan < adaptiveInterval) {
            val remainingMs = adaptiveInterval - timeSinceLastScan
            Log.d(TAG, "WiFi scan skipped (throttle optimization): ${remainingMs}ms until next allowed scan")
            return
        }

        // Update total scan attempts
        scanStats.value = scanStats.value.copy(
            totalWifiScans = scanStats.value.totalWifiScans + 1
        )
        broadcastScanStats()

        try {
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (started) {
                wifiStatus.value = SubsystemStatus.Active
                Log.d(TAG, "WiFi scan started (attempt ${wifiScanAttemptsSinceSuccess + 1})")
            } else {
                wifiScanAttemptsSinceSuccess++
                Log.d(TAG, "WiFi scan request rejected (throttled, backoff level: $wifiScanAttemptsSinceSuccess)")
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
                        lastSuccessfulWifiScanTime = System.currentTimeMillis()
                        wifiScanAttemptsSinceSuccess = 0

                        wifiStatus.value = SubsystemStatus.Active
                        serviceScope.launch {
                            processWifiScanResults()
                        }

                        val stats = scanStats.value
                        scanStats.value = stats.copy(
                            successfulWifiScans = stats.successfulWifiScans + 1,
                            lastWifiSuccessTime = lastSuccessfulWifiScanTime
                        )
                        broadcastScanStats()

                        detectorCallbackImpl.onScanSuccess(DetectorHealthStatus.DETECTOR_WIFI)

                        Log.d(TAG, "WiFi scan successful, backoff reset")
                    } else {
                        wifiScanAttemptsSinceSuccess++

                        val stats = scanStats.value
                        scanStats.value = stats.copy(
                            throttledWifiScans = stats.throttledWifiScans + 1
                        )
                        broadcastScanStats()

                        val lastThrottle = lastWifiThrottleLogTime
                        val now = System.currentTimeMillis()
                        if (lastThrottle == null || now - lastThrottle > 60000) {
                            lastWifiThrottleLogTime = now
                            val nextAllowedIn = MIN_WIFI_SCAN_INTERVAL_MS * (1 shl wifiScanAttemptsSinceSuccess.coerceAtMost(3))
                            wifiStatus.value = SubsystemStatus.Error(-2, "Throttled (backoff: ${nextAllowedIn/1000}s)")
                            logError("WiFi", -2, "WiFi scan throttled by system (next attempt in ${nextAllowedIn/1000}s)", recoverable = true)
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
        detectorCallbackImpl.onDetectorStarted(DetectorHealthStatus.DETECTOR_WIFI)
    }

    private var lastWifiThrottleLogTime: Long? = null

    private fun unregisterWifiReceiver() {
        wifiScanReceiver?.let {
            try {
                unregisterReceiver(it)
                detectorCallbackImpl.onDetectorStopped(DetectorHealthStatus.DETECTOR_WIFI)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering WiFi receiver", e)
            }
        }
        wifiScanReceiver = null
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
        broadcastScanStats()

        // Feed results to monitors via handler
        processWifiWithHandler(results)

        // Update location on the handler before processing
        currentLocation?.let { location ->
            wifiDetectionHandler.updateLocation(location.latitude, location.longitude)
        }

        // Process all scan results through WifiDetectionHandler
        serviceScope.launch {
            try {
                val detections = wifiDetectionHandler.processData(results)

                // Store enriched WiFi SSID/MAC match data for LLM analysis
                val enrichedResults = wifiDetectionHandler.lastEnrichedResults
                for ((detectionId, enrichedData) in enrichedResults) {
                    enrichedDataCache.putWifiSsidMatch(detectionId, enrichedData)
                    Log.d(TAG, "Stored WiFi SSID match heuristics for detection $detectionId (method: ${enrichedData.detectionMethodName})")
                }

                // Handle each detection
                for (detection in detections) {
                    handleDetection(detection)
                }

                // Track unmatched networks if enabled
                if (currentSettings.value.trackSeenDevices) {
                    val matchedBssids = detections.mapNotNull { it.macAddress }.toSet()

                    for (result in results) {
                        val bssid = result.BSSID ?: continue
                        if (bssid !in matchedBssids) {
                            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                result.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
                            } else {
                                @Suppress("DEPRECATION")
                                result.SSID ?: ""
                            }
                            if (ssid.isNotEmpty()) {
                                trackSeenWifiNetwork(bssid, ssid, result.level)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing WiFi scan results with handler", e)
            }
        }
    }

    // ==================== Nuke Receiver ====================

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
        isDatabaseAvailable.value = false

        serviceScope.launch {
            try {
                Log.w(TAG, "Stopping scanning due to nuke")
                stopScanning()
                closeDatabaseConnection()
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

    private fun handleDatabaseShutdown() {
        isDatabaseAvailable.value = false
        closeDatabaseConnection()
    }

    private fun closeDatabaseConnection() {
        try {
            com.flockyou.data.repository.FlockYouDatabase.getDatabase(applicationContext).close()
            Log.d(TAG, "Database connection closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing database connection", e)
        }
    }

    // ==================== Battery Monitoring ====================

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { updateBatteryState(it) }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        val batteryStatus = registerReceiver(batteryReceiver, intentFilter)
        batteryStatus?.let { updateBatteryState(it) }

        Log.d(TAG, "Battery receiver registered, initial level: $currentBatteryPercent%, charging: $isCharging")
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Battery receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering battery receiver", e)
            }
        }
        batteryReceiver = null
    }

    private fun updateBatteryState(intent: Intent) {
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)

        if (level >= 0 && scale > 0) {
            currentBatteryPercent = (level * 100) / scale
            currentBatteryLevel.value = currentBatteryPercent
        }

        isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                     status == android.os.BatteryManager.BATTERY_STATUS_FULL
        isBatteryCharging.value = isCharging

        updateEffectiveBatteryMode()
    }

    private fun updateEffectiveBatteryMode() {
        val effectiveMode = if (isCharging) {
            com.flockyou.data.BatteryAdaptiveMode.PERFORMANCE
        } else {
            currentScanSettings.getEffectiveMode(currentBatteryPercent)
        }

        if (currentBatteryMode.value != effectiveMode) {
            Log.d(TAG, "Battery mode changed: ${currentBatteryMode.value} -> $effectiveMode " +
                    "(battery: $currentBatteryPercent%, charging: $isCharging)")
            currentBatteryMode.value = effectiveMode
            broadcastBatteryState()
        }
    }

    private fun getBatteryMultipliers(): Pair<Float, Float> {
        val mode = currentBatteryMode.value
        return Pair(mode.intervalMultiplier, mode.durationMultiplier)
    }

    // ==================== Location ====================
    @SuppressLint("MissingPermission")
    private fun updateLocation() {
        if (!hasLocationPermissions()) {
            locationStatus.value = SubsystemStatus.PermissionDenied("ACCESS_FINE_LOCATION")
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        // Try last known first
        val lastKnown = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(android.location.LocationManager.FUSED_PROVIDER)
            ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

        if (lastKnown != null) {
            currentLocation = lastKnown
            locationStatus.value = SubsystemStatus.Active
            return
        }

        // No last known — request a single update
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER
        )

        for (provider in providers) {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestSingleUpdate(
                    provider,
                    object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            currentLocation = location
                            locationStatus.value = SubsystemStatus.Active
                        }
                        override fun onProviderDisabled(provider: String) {}
                        override fun onProviderEnabled(provider: String) {}
                    },
                    android.os.Looper.getMainLooper()
                )
                locationStatus.value = SubsystemStatus.Active
                return
            }
        }

        locationStatus.value = SubsystemStatus.Error(-1, "No location available")
    }


    // ==================== Detector Health Management ====================

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

    private fun stopHealthCheckJob() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        Log.d(TAG, "Health check job stopped")
    }

    private fun startThrottleCleanup() {
        throttleCleanupJob?.cancel()
        throttleCleanupJob = serviceScope.launch {
            while (isActive) {
                delay(30_000)
                deduplicator.cleanup()
            }
        }
        Log.d(TAG, "Throttle cleanup job started")
    }

    private fun stopThrottleCleanup() {
        throttleCleanupJob?.cancel()
        throttleCleanupJob = null
        Log.d(TAG, "Throttle cleanup job stopped")
    }

    private fun startIpcRefreshJob() {
        ipcRefreshJob?.cancel()
        ipcRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(IPC_REFRESH_INTERVAL_MS)
                if (ipcClients.isNotEmpty()) {
                    broadcastAllSubsystemData()
                }
            }
        }
        Log.d(TAG, "IPC refresh job started")
    }

    private fun stopIpcRefreshJob() {
        ipcRefreshJob?.cancel()
        ipcRefreshJob = null
        Log.d(TAG, "IPC refresh job stopped")
    }

    private fun startCorrelationAnalysisJob() {
        correlationAnalysisJob?.cancel()
        correlationAnalysisJob = serviceScope.launch {
            delay(CORRELATION_ANALYSIS_INTERVAL_MS)

            while (isActive && isScanning.value) {
                try {
                    val sinceTimestamp = System.currentTimeMillis() - 10 * 60 * 1000L
                    val recentDetections = if (currentPrivacySettings.ephemeralModeEnabled) {
                        ephemeralRepository.getRecentDetections(sinceTimestamp).first()
                    } else {
                        repository.getRecentDetections(sinceTimestamp).first()
                    }

                    if (recentDetections.size >= 2) {
                        Log.d(TAG, "Running cross-domain correlation analysis on ${recentDetections.size} detections")

                        val result = crossDomainAnalyzer.analyzeCorrelations(
                            recentDetections = recentDetections,
                            timeWindowMs = 10 * 60 * 1000L
                        )

                        if (result.correlatedThreats.isNotEmpty()) {
                            Log.i(TAG, "Correlation analysis found ${result.correlatedThreats.size} correlated threats")

                            result.mostCriticalCorrelation?.let { critical ->
                                if (critical.combinedThreatLevel == ThreatLevel.CRITICAL) {
                                    alertUserOfCorrelation(critical)
                                }
                            }

                            broadcastCorrelationResults(result)
                        } else {
                            Log.d(TAG, "No cross-domain correlations detected")
                        }
                    }

                    crossDomainAnalyzer.cleanup()

                } catch (e: Exception) {
                    Log.e(TAG, "Error in correlation analysis: ${e.message}", e)
                }

                delay(CORRELATION_ANALYSIS_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Correlation analysis job started (interval: ${CORRELATION_ANALYSIS_INTERVAL_MS}ms)")
    }

    private fun stopCorrelationAnalysisJob() {
        correlationAnalysisJob?.cancel()
        correlationAnalysisJob = null
        Log.d(TAG, "Correlation analysis job stopped")
    }

    /**
     * Perform a health check on all detectors and attempt to restart any that are stalled.
     */
    private fun performHealthCheck() {
        val now = System.currentTimeMillis()
        val currentHealth = detectorHealth.value.toMutableMap()

        for ((detectorName, status) in currentHealth) {
            if (!status.isRunning) continue

            val lastSuccess = status.lastSuccessfulScan
            if (lastSuccess != null && (now - lastSuccess) > DETECTOR_STALE_THRESHOLD_MS) {
                Log.w(TAG, "Detector $detectorName appears stalled (no scan in ${(now - lastSuccess) / 1000}s)")

                currentHealth[detectorName] = status.copy(
                    isHealthy = false,
                    consecutiveFailures = status.consecutiveFailures + 1
                )

                if (status.restartCount < MAX_RESTART_ATTEMPTS) {
                    attemptDetectorRestart(detectorName)
                } else {
                    Log.e(TAG, "Detector $detectorName exceeded max restart attempts (${MAX_RESTART_ATTEMPTS})")
                    logError(detectorName, -1, "Detector failed after ${MAX_RESTART_ATTEMPTS} restart attempts", recoverable = false)
                }
            }
        }

        // Additional BLE-specific checks
        val bleHealthStatus = currentHealth[DetectorHealthStatus.DETECTOR_BLE]
        if (bleHealthStatus != null && bleHealthStatus.isRunning) {
            var needsRestart = false
            var reason = ""

            if (!isBleScanningActive) {
                needsRestart = true
                reason = "isBleScanningActive=false but scanner should be running"
            }

            if (!needsRestart && isBleScanningActive && lastBleScanResultTime > 0) {
                val timeSinceLastResult = now - lastBleScanResultTime
                if (timeSinceLastResult > BLE_WATCHDOG_THRESHOLD_MS) {
                    bleWatchdogFailures++
                    if (bleWatchdogFailures <= BLE_WATCHDOG_MAX_FAILURES) {
                        needsRestart = true
                        reason = "No BLE results for ${timeSinceLastResult / 1000}s (watchdog failure ${bleWatchdogFailures}/${BLE_WATCHDOG_MAX_FAILURES})"
                    } else {
                        Log.e(TAG, "BLE watchdog exceeded max failures ($BLE_WATCHDOG_MAX_FAILURES), not restarting")
                    }
                }
            }

            if (needsRestart) {
                Log.w(TAG, "BLE scanner needs restart: $reason")
                if (bleHealthStatus.restartCount < MAX_RESTART_ATTEMPTS) {
                    currentHealth[DetectorHealthStatus.DETECTOR_BLE] = bleHealthStatus.copy(
                        isHealthy = false,
                        consecutiveFailures = bleHealthStatus.consecutiveFailures + 1
                    )
                    attemptDetectorRestart(DetectorHealthStatus.DETECTOR_BLE)
                }
            }
        }

        detectorHealth.value = currentHealth
        broadcastDetectorHealth()

        // === CRITICAL JOB WATCHDOG ===
        checkAndRestartCriticalJobs()
    }

    private fun checkAndRestartCriticalJobs() {
        if (isScanning.value && (scanJob == null || scanJob?.isActive != true)) {
            Log.w(TAG, "WATCHDOG: Main scan job stopped unexpectedly, restarting...")
            restartScanningLoopIfNeeded()
        }

        if (cellularMonitor != null) {
            if (cellularAnomalyJob == null || cellularAnomalyJob?.isActive != true) {
                Log.w(TAG, "WATCHDOG: Cellular anomaly job stopped, restarting...")
                restartCellularMonitoringJobs()
            }
        }

        if (broadcastSettingsJob == null || broadcastSettingsJob?.isActive != true) {
            Log.w(TAG, "WATCHDOG: Broadcast settings job stopped, restarting...")
            restartSettingsCollectionJobs()
        }

        if (ipcRefreshJob == null || ipcRefreshJob?.isActive != true) {
            Log.w(TAG, "WATCHDOG: IPC refresh job stopped, restarting...")
            startIpcRefreshJob()
        }

        if (throttleCleanupJob == null || throttleCleanupJob?.isActive != true) {
            Log.w(TAG, "WATCHDOG: Throttle cleanup job stopped, restarting...")
            startThrottleCleanup()
        }
    }

    private fun restartCellularMonitoringJobs() {
        cellularAnomalyJob?.cancel()
        cellularStatusJob?.cancel()
        cellularHistoryJob?.cancel()
        cellularEventsJob?.cancel()

        try {
            startCellularMonitoring()
            Log.i(TAG, "Cellular monitoring jobs restarted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart cellular monitoring jobs", e)
        }
    }

    private fun restartSettingsCollectionJobs() {
        broadcastSettingsJob?.cancel()
        privacySettingsJob?.cancel()
        scanSettingsJob?.cancel()
        notificationSettingsJob?.cancel()
        detectionSettingsJob?.cancel()

        try {
            broadcastSettingsJob = serviceScope.launch {
                broadcastSettingsRepository.settings.collect { settings ->
                    currentBroadcastSettings = settings
                }
            }

            privacySettingsJob = serviceScope.launch {
                privacySettingsRepository.settings.collect { settings ->
                    currentPrivacySettings = settings
                }
            }

            scanSettingsJob = serviceScope.launch {
                scanSettingsRepository.settings.collect { settings ->
                    currentScanSettings = settings
                }
            }

            notificationSettingsJob = serviceScope.launch {
                notificationSettingsRepository.settings.collect { settings ->
                    currentNotificationSettings = settings
                }
            }

            detectionSettingsJob = serviceScope.launch {
                detectionSettingsRepository.settings.collect { settings ->
                    currentDetectionSettings = settings
                }
            }

            Log.i(TAG, "Settings collection jobs restarted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart settings collection jobs", e)
        }
    }

    private fun restartScanningLoopIfNeeded() {
        if (!isScanning.value) {
            Log.d(TAG, "Scanning is stopped, not restarting loop")
            return
        }

        Log.i(TAG, "Restarting scanning loop via full restart...")

        scanJob?.cancel()
        scanJob = null

        if (isBleScanningActive) {
            stopBleScan()
        }

        isScanning.value = false
        serviceScope.launch {
            delay(1000)
            startScanning()
        }
    }

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

        logError(detectorName, -1, error, recoverable)

        val currentStatus = detectorHealth.value[detectorName]
        if (recoverable && currentStatus != null &&
            currentStatus.consecutiveFailures < MAX_CONSECUTIVE_FAILURES &&
            currentStatus.restartCount < MAX_RESTART_ATTEMPTS) {
            val delayMs = (1000L * (1 shl currentStatus.consecutiveFailures.coerceAtMost(4))).coerceAtMost(30_000L)
            serviceScope.launch {
                delay(delayMs)
                attemptDetectorRestart(detectorName)
            }
        }

        broadcastDetectorHealth()
    }

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

    private fun updateDetectorHealth(detectorName: String, transform: (DetectorHealthStatus) -> DetectorHealthStatus) {
        val current = detectorHealth.value.toMutableMap()
        val existing = current[detectorName] ?: DetectorHealthStatus(name = detectorName)
        current[detectorName] = transform(existing)
        detectorHealth.value = current
    }

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

    private fun attemptDetectorRestart(detectorName: String) {
        Log.i(TAG, "Attempting to restart detector: $detectorName")

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
                Log.i(TAG, "WiFi scanner restart requested (system-triggered)")
            }
        }

        broadcastDetectorHealth()
    }

    // ==================== Utility ====================

    /**
     * Format raw BLE data from ScanRecord for display in advanced mode.
     */
    private fun formatRawBleData(
        scanRecord: android.bluetooth.le.ScanRecord?,
        manufacturerData: Map<Int, String>
    ): String? {
        if (scanRecord == null && manufacturerData.isEmpty()) return null

        val hexParts = mutableListOf<String>()

        manufacturerData.forEach { (id, data) ->
            hexParts.add("%04X".format(id) + data)
        }

        scanRecord?.serviceData?.forEach { (uuid, data) ->
            val uuidShort = uuid.uuid.toString().substring(4, 8).uppercase()
            val dataHex = data.joinToString("") { "%02X".format(it) }
            hexParts.add("SD:$uuidShort:$dataHex")
        }

        if (hexParts.isEmpty()) {
            scanRecord?.bytes?.let { bytes ->
                return bytes.joinToString("") { "%02X".format(it) }
            }
        }

        return if (hexParts.isNotEmpty()) hexParts.joinToString("|") else null
    }

    /**
     * Format raw BLE data from pre-extracted manufacturer data map.
     */
    private fun formatRawBleDataFromMap(manufacturerData: Map<Int, String>): String? {
        if (manufacturerData.isEmpty()) return null

        return manufacturerData.entries.joinToString("|") { (id, data) ->
            "%04X".format(id) + data
        }
    }
}

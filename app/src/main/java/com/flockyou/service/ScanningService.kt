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
import com.flockyou.data.repository.FlockYouDatabase
import com.google.android.gms.location.*
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        
        // Default values (can be overridden by settings)
        // Aggressive burst scan pattern: 25s on, 5s cooldown to prevent thermal throttling
        private const val DEFAULT_WIFI_SCAN_INTERVAL = 30000L  // 30 seconds between WiFi scans
        private const val DEFAULT_BLE_SCAN_DURATION = 25000L   // 25 seconds of low-latency scanning
        private const val DEFAULT_BLE_COOLDOWN = 5000L         // 5 seconds cooldown to prevent thermal throttle
        private const val DEFAULT_INACTIVE_TIMEOUT = 60000L
        private const val DEFAULT_SEEN_DEVICE_TIMEOUT = 300000L
        
        // Current configured values
        val currentSettings = MutableStateFlow(ScanConfig())
        
        val isScanning = MutableStateFlow(false)
        val lastDetection = MutableStateFlow<Detection?>(null)
        val detectionCount = MutableStateFlow(0)
        
        // Status tracking
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
        
        // Scan statistics
        val scanStats = MutableStateFlow(ScanStatistics())

        // Learning mode - for capturing unknown device signatures
        val learningModeEnabled = MutableStateFlow(false)
        val learnedSignatures = MutableStateFlow<List<LearnedSignature>>(emptyList())

        // Packet rate tracking for Signal trigger detection (advertising spike detection)
        private val devicePacketCounts = mutableMapOf<String, MutableList<Long>>()  // MAC -> timestamps
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
            val packets = devicePacketCounts.getOrPut(macAddress) { mutableListOf() }
            packets.add(now)

            // Remove old packets outside the window
            val cutoff = now - PACKET_RATE_WINDOW_MS
            packets.removeAll { it < cutoff }

            // Calculate rate
            val rate = if (packets.size > 1) {
                packets.size.toFloat() / (PACKET_RATE_WINDOW_MS / 1000f)
            } else {
                0f
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
    }
    
    /** Individual subsystem status */
    sealed class SubsystemStatus {
        object Idle : SubsystemStatus()
        object Active : SubsystemStatus()
        object Disabled : SubsystemStatus()
        data class Error(val code: Int, val message: String) : SubsystemStatus()
        data class PermissionDenied(val permission: String) : SubsystemStatus()
    }
    
    /** Error log entry */
    data class ScanError(
        val timestamp: Long = System.currentTimeMillis(),
        val subsystem: String,
        val code: Int,
        val message: String,
        val recoverable: Boolean = true
    )
    
    @Inject
    lateinit var repository: DetectionRepository

    @Inject
    lateinit var broadcastSettingsRepository: com.flockyou.data.BroadcastSettingsRepository

    private var currentBroadcastSettings: com.flockyou.data.BroadcastSettings = com.flockyou.data.BroadcastSettings()

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
    
    // Wake lock for background operation
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var powerManager: PowerManager
    
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
        cellularMonitor = CellularMonitor(applicationContext)

        // Initialize Satellite Monitor
        satelliteMonitor = com.flockyou.monitoring.SatelliteMonitor(applicationContext)

        // Initialize Rogue WiFi Monitor
        rogueWifiMonitor = RogueWifiMonitor(applicationContext)

        // Initialize RF Signal Analyzer
        rfSignalAnalyzer = RfSignalAnalyzer(applicationContext)

        // Initialize Ultrasonic Detector
        ultrasonicDetector = UltrasonicDetector(applicationContext)

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
    
    override fun onBind(intent: Intent?): IBinder? = null
    
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
        serviceScope.launch {
            broadcastSettingsRepository.settings.collect { settings ->
                currentBroadcastSettings = settings
            }
        }
        
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

        // Start ultrasonic detection
        startUltrasonicDetection()

        // Start heartbeat monitoring - sends periodic heartbeats to watchdog
        ServiceRestartReceiver.scheduleHeartbeat(this)
        ServiceRestartReceiver.scheduleJobSchedulerBackup(this)

        // Start continuous scanning with burst pattern (25s on, 5s cooldown)
        scanJob = serviceScope.launch {
            var scanCycleCount = 0

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
                        startBleScan(scanConfig.aggressiveBleMode)
                        delay(scanConfig.bleScanDuration)
                        stopBleScan()

                        // Thermal cooldown period - prevents Android from force-stopping scans
                        Log.d(TAG, "BLE cooldown: ${scanConfig.bleCooldown}ms")
                        delay(scanConfig.bleCooldown)
                    }

                    // === WiFi SCAN ===
                    // Trigger WiFi scan (results come via broadcast receiver)
                    if (scanConfig.enableWifi) {
                        startWifiScan()
                    }

                    // Update location
                    updateLocation()

                    // Mark old detections as inactive
                    val inactiveThreshold = System.currentTimeMillis() - scanConfig.inactiveTimeout
                    repository.markOldInactive(inactiveThreshold)

                    // Clean up old seen devices
                    if (scanConfig.trackSeenDevices) {
                        cleanupSeenDevices(scanConfig.seenDeviceTimeout)
                    }

                    // Update notification with status
                    val statusText = buildStatusText()
                    updateNotification(statusText)

                    scanCycleCount++

                    // Every 10 cycles, re-schedule the watchdog to ensure it stays active
                    if (scanCycleCount % 10 == 0) {
                        ServiceRestartReceiver.scheduleWatchdog(this@ScanningService)
                        Log.d(TAG, "Completed $scanCycleCount scan cycles")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Scanning error", e)
                    logError("Scanner", -1, "Scan cycle error: ${e.message}", recoverable = true)
                }
            }
        }
    }
    
    private fun cleanupSeenDevices(timeout: Long) {
        val cutoff = System.currentTimeMillis() - timeout
        synchronized(seenDevicesLock) {
            seenBleDevices.value = seenBleDevices.value.filter { it.lastSeen > cutoff }
            seenWifiNetworks.value = seenWifiNetworks.value.filter { it.lastSeen > cutoff }
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
    }
    
    private fun stopScanning() {
        scanStatus.value = ScanStatus.Stopping
        isScanning.value = false
        scanJob?.cancel()
        stopBleScan()
        unregisterWifiReceiver()
        stopCellularMonitoring()
        stopSatelliteMonitoring()
        stopRogueWifiMonitoring()
        stopRfSignalAnalysis()
        stopUltrasonicDetection()

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
            }
        }
        
        // Collect seen cell tower history
        cellularHistoryJob = serviceScope.launch {
            cellularMonitor?.seenCellTowers?.collect { towers ->
                seenCellTowers.value = towers
            }
        }
        
        // Collect cellular timeline events
        cellularEventsJob = serviceScope.launch {
            cellularMonitor?.cellularEvents?.collect { events ->
                cellularEvents.value = events
            }
        }
        
        // Collect cellular anomalies and convert to detections
        cellularAnomalyJob = serviceScope.launch {
            cellularMonitor?.anomalies?.collect { anomalies ->
                cellularAnomalies.value = anomalies

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendCellularAnomalyBroadcast(anomaly)

                    // Convert anomaly to detection
                    val detection = cellularMonitor?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Check if we already have this detection (use unique SSID)
                        val existing = det.ssid?.let { repository.getDetectionBySsid(it) }
                        if (existing == null) {
                            repository.insertDetection(det)

                            // Alert and vibrate for high-severity anomalies
                            if (anomaly.severity == ThreatLevel.CRITICAL ||
                                anomaly.severity == ThreatLevel.HIGH) {
                                alertUser(det)
                            }

                            lastDetection.value = det
                            detectionCount.value = repository.getTotalDetectionCount()

                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "CELLULAR ANOMALY: ${anomaly.type.displayName} - ${anomaly.description}")
                            }
                        }
                    }
                }
            }
        }
        
        // Also update cellular location when we get GPS updates
        serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    cellularMonitor?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }
    
    private fun stopCellularMonitoring() {
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
            }
        }

        // Collect suspicious networks
        serviceScope.launch {
            rogueWifiMonitor?.suspiciousNetworks?.collect { networks ->
                Companion.suspiciousNetworks.value = networks
            }
        }

        // Collect events
        rogueWifiEventsJob = serviceScope.launch {
            rogueWifiMonitor?.wifiEvents?.collect { events ->
                Companion.rogueWifiEvents.value = events
            }
        }

        // Collect anomalies and convert to detections
        rogueWifiAnomalyJob = serviceScope.launch {
            rogueWifiMonitor?.anomalies?.collect { anomalies ->
                Companion.rogueWifiAnomalies.value = anomalies

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendWifiAnomalyBroadcast(anomaly)

                    val detection = rogueWifiMonitor?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Check if we already have this detection
                        val existing = det.macAddress?.let { repository.getDetectionByMacAddress(it) }
                            ?: det.ssid?.let { repository.getDetectionBySsid(it) }

                        if (existing == null) {
                            repository.insertDetection(det)

                            if (anomaly.severity == ThreatLevel.CRITICAL ||
                                anomaly.severity == ThreatLevel.HIGH) {
                                alertUser(det)
                            }

                            lastDetection.value = det
                            detectionCount.value = repository.getTotalDetectionCount()

                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "WIFI ANOMALY: ${anomaly.type.displayName} - ${anomaly.description}")
                            }
                        }
                    }
                }
            }
        }

        // Update monitor location when GPS updates
        serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    rogueWifiMonitor?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }

    private fun stopRogueWifiMonitoring() {
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
            }
        }

        // Collect events
        rfEventsJob = serviceScope.launch {
            rfSignalAnalyzer?.rfEvents?.collect { events ->
                Companion.rfEvents.value = events
            }
        }

        // Collect detected drones
        rfDronesJob = serviceScope.launch {
            rfSignalAnalyzer?.dronesDetected?.collect { drones ->
                Companion.detectedDrones.value = drones

                // Convert new drones to detections
                for (drone in drones) {
                    val detection = rfSignalAnalyzer?.droneToDetection(drone)
                    detection?.let { det ->
                        val existing = det.macAddress?.let { repository.getDetectionByMacAddress(it) }
                        if (existing == null) {
                            repository.insertDetection(det)
                            alertUser(det)
                            lastDetection.value = det
                            detectionCount.value = repository.getTotalDetectionCount()
                            Log.w(TAG, "DRONE DETECTED: ${drone.manufacturer} at ${drone.estimatedDistance}")
                        }
                    }
                }
            }
        }

        // Collect anomalies and convert to detections
        rfAnomalyJob = serviceScope.launch {
            rfSignalAnalyzer?.anomalies?.collect { anomalies ->
                Companion.rfAnomalies.value = anomalies

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendRfAnomalyBroadcast(anomaly)

                    val detection = rfSignalAnalyzer?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Use timestamp-based unique ID for RF anomalies
                        val existing = repository.getDetectionBySsid(det.deviceName ?: "")
                        if (existing == null) {
                            repository.insertDetection(det)

                            if (anomaly.severity == ThreatLevel.CRITICAL ||
                                anomaly.severity == ThreatLevel.HIGH) {
                                alertUser(det)
                            }

                            lastDetection.value = det
                            detectionCount.value = repository.getTotalDetectionCount()

                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "RF ANOMALY: ${anomaly.type.displayName} - ${anomaly.description}")
                            }
                        }
                    }
                }
            }
        }

        // Update analyzer location
        serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    rfSignalAnalyzer?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }

    private fun stopRfSignalAnalysis() {
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
        if (!hasAudioPermissions()) {
            Log.w(TAG, "Missing audio permissions for ultrasonic detection")
            return
        }

        Log.d(TAG, "Starting ultrasonic beacon detection")

        ultrasonicDetector?.startMonitoring()

        // Collect status updates
        ultrasonicStatusJob = serviceScope.launch {
            ultrasonicDetector?.status?.collect { status ->
                Companion.ultrasonicStatus.value = status
            }
        }

        // Collect events
        ultrasonicEventsJob = serviceScope.launch {
            ultrasonicDetector?.events?.collect { events ->
                Companion.ultrasonicEvents.value = events
            }
        }

        // Collect active beacons
        ultrasonicBeaconsJob = serviceScope.launch {
            ultrasonicDetector?.beaconsDetected?.collect { beacons ->
                Companion.ultrasonicBeacons.value = beacons
            }
        }

        // Collect anomalies and convert to detections
        ultrasonicAnomalyJob = serviceScope.launch {
            ultrasonicDetector?.anomalies?.collect { anomalies ->
                Companion.ultrasonicAnomalies.value = anomalies

                for (anomaly in anomalies) {
                    // Send broadcast for automation apps
                    sendUltrasonicAnomalyBroadcast(anomaly)

                    val detection = ultrasonicDetector?.anomalyToDetection(anomaly)
                    detection?.let { det ->
                        // Use frequency as unique identifier
                        val existing = det.ssid?.let { repository.getDetectionBySsid(it) }
                        if (existing == null) {
                            repository.insertDetection(det)

                            if (anomaly.severity == ThreatLevel.CRITICAL ||
                                anomaly.severity == ThreatLevel.HIGH) {
                                alertUser(det)
                            }

                            lastDetection.value = det
                            detectionCount.value = repository.getTotalDetectionCount()

                            Log.w(TAG, "ULTRASONIC: ${anomaly.type.displayName} - ${anomaly.frequency}Hz")
                        }
                    }
                }
            }
        }

        // Update detector location
        serviceScope.launch {
            while (isScanning.value) {
                currentLocation?.let { loc ->
                    ultrasonicDetector?.updateLocation(loc.latitude, loc.longitude)
                }
                delay(5000)
            }
        }
    }

    private fun stopUltrasonicDetection() {
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

        if (isBleScanningActive) return

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
            Log.e(TAG, "BLE scan failed with error: $errorCode ($errorMessage)")
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
    }
    
    // ==================== Detection Handling ====================
    
    private suspend fun handleDetection(detection: Detection) {
        // Use upsert - this will update seen count if existing, or insert if new
        val isNew = repository.upsertDetection(detection)
        
        if (isNew) {
            // New detection
            detectionCount.value++
            lastDetection.value = detection
            
            Log.d(TAG, "New detection: ${detection.deviceType} - ${detection.macAddress ?: detection.ssid}")
            
            // Alert user
            alertUser(detection)
        } else {
            // Existing detection - update lastDetection to refresh UI
            lastDetection.value = detection
            Log.d(TAG, "Updated detection: ${detection.deviceType} - ${detection.macAddress ?: detection.ssid}")
        }
    }
    
    private fun alertUser(detection: Detection) {
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
}

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
        private const val DEFAULT_WIFI_SCAN_INTERVAL = 35000L
        private const val DEFAULT_BLE_SCAN_DURATION = 10000L
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
        val errorLog = MutableStateFlow<List<ScanError>>(emptyList())
        
        // Seen but unmatched devices
        val seenBleDevices = MutableStateFlow<List<SeenDevice>>(emptyList())
        val seenWifiNetworks = MutableStateFlow<List<SeenDevice>>(emptyList())
        
        // Scan statistics
        val scanStats = MutableStateFlow(ScanStatistics())
        
        private const val MAX_ERROR_LOG_SIZE = 50
        private const val MAX_SEEN_DEVICES = 100
        
        fun clearErrors() {
            errorLog.value = emptyList()
        }
        
        fun clearSeenDevices() {
            seenBleDevices.value = emptyList()
            seenWifiNetworks.value = emptyList()
        }
        
        fun updateSettings(
            wifiIntervalSeconds: Int = 35,
            bleDurationSeconds: Int = 10,
            inactiveTimeoutSeconds: Int = 60,
            seenDeviceTimeoutMinutes: Int = 5,
            enableBle: Boolean = true,
            enableWifi: Boolean = true,
            trackSeenDevices: Boolean = true
        ) {
            currentSettings.value = ScanConfig(
                wifiScanInterval = wifiIntervalSeconds * 1000L,
                bleScanDuration = bleDurationSeconds * 1000L,
                inactiveTimeout = inactiveTimeoutSeconds * 1000L,
                seenDeviceTimeout = seenDeviceTimeoutMinutes * 60 * 1000L,
                enableBle = enableBle,
                enableWifi = enableWifi,
                trackSeenDevices = trackSeenDevices
            )
        }
    }
    
    /** Runtime scan configuration */
    data class ScanConfig(
        val wifiScanInterval: Long = DEFAULT_WIFI_SCAN_INTERVAL,
        val bleScanDuration: Long = DEFAULT_BLE_SCAN_DURATION,
        val inactiveTimeout: Long = DEFAULT_INACTIVE_TIMEOUT,
        val seenDeviceTimeout: Long = DEFAULT_SEEN_DEVICE_TIMEOUT,
        val enableBle: Boolean = true,
        val enableWifi: Boolean = true,
        val trackSeenDevices: Boolean = true
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
        val serviceUuids: List<String> = emptyList()
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
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
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
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        val notification = createNotification("Initializing...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        startScanning()
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopScanning()
        serviceScope.cancel()
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
        
        // Start continuous scanning
        scanJob = serviceScope.launch {
            while (isActive) {
                val scanConfig = currentSettings.value // Re-read in case settings changed
                try {
                    // Start BLE scan
                    if (scanConfig.enableBle) {
                        startBleScan()
                        delay(scanConfig.bleScanDuration)
                        stopBleScan()
                    }
                    
                    // Start WiFi scan
                    if (scanConfig.enableWifi) {
                        startWifiScan()
                    }
                    
                    // Wait for the remainder of the interval
                    val waitTime = if (scanConfig.enableBle) {
                        scanConfig.wifiScanInterval - scanConfig.bleScanDuration
                    } else {
                        scanConfig.wifiScanInterval
                    }
                    delay(waitTime.coerceAtLeast(5000L))
                    
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
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Scanning error", e)
                    logError("Scanner", -1, "Scan cycle error: ${e.message}", recoverable = true)
                }
            }
        }
    }
    
    private fun cleanupSeenDevices(timeout: Long) {
        val cutoff = System.currentTimeMillis() - timeout
        seenBleDevices.value = seenBleDevices.value.filter { it.lastSeen > cutoff }
        seenWifiNetworks.value = seenWifiNetworks.value.filter { it.lastSeen > cutoff }
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
        
        // Reset subsystem statuses
        bleStatus.value = SubsystemStatus.Idle
        wifiStatus.value = SubsystemStatus.Idle
        locationStatus.value = SubsystemStatus.Idle
        scanStatus.value = ScanStatus.Idle
        
        Log.d(TAG, "Stopped scanning")
    }
    
    // ==================== BLE Scanning ====================
    
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
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
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        
        try {
            bleScanner?.startScan(null, scanSettings, bleScanCallback)
            isBleScanningActive = true
            bleStatus.value = SubsystemStatus.Active
            Log.d(TAG, "BLE scan started")
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
            trackSeenBleDevice(macAddress, deviceName, rssi, serviceUuids)
        }
    }
    
    private fun trackSeenBleDevice(macAddress: String, deviceName: String?, rssi: Int, serviceUuids: List<java.util.UUID>) {
        val currentList = seenBleDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == macAddress }
        
        if (existingIndex >= 0) {
            // Update existing
            val existing = currentList[existingIndex]
            currentList[existingIndex] = existing.copy(
                name = deviceName ?: existing.name,
                rssi = rssi,
                lastSeen = System.currentTimeMillis(),
                seenCount = existing.seenCount + 1
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
                serviceUuids = serviceUuids.map { it.toString() }
            ))
            
            // Limit list size
            if (currentList.size > MAX_SEEN_DEVICES) {
                currentList.removeAt(currentList.lastIndex)
            }
        }
        
        seenBleDevices.value = currentList
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

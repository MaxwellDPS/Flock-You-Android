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
        private const val WIFI_SCAN_INTERVAL = 15000L // 15 seconds
        private const val BLE_SCAN_DURATION = 10000L // 10 seconds
        private const val INACTIVE_TIMEOUT = 60000L // 1 minute
        
        val isScanning = MutableStateFlow(false)
        val lastDetection = MutableStateFlow<Detection?>(null)
        val detectionCount = MutableStateFlow(0)
        
        // Status tracking
        val scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
        val bleStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)
        val wifiStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)
        val locationStatus = MutableStateFlow<SubsystemStatus>(SubsystemStatus.Idle)
        val errorLog = MutableStateFlow<List<ScanError>>(emptyList())
        
        private const val MAX_ERROR_LOG_SIZE = 50
        
        fun clearErrors() {
            errorLog.value = emptyList()
        }
    }
    
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
        registerWifiReceiver()
        
        // Start continuous scanning
        scanJob = serviceScope.launch {
            while (isActive) {
                try {
                    // Start BLE scan
                    startBleScan()
                    delay(BLE_SCAN_DURATION)
                    stopBleScan()
                    
                    // Start WiFi scan
                    startWifiScan()
                    delay(WIFI_SCAN_INTERVAL - BLE_SCAN_DURATION)
                    
                    // Update location
                    updateLocation()
                    
                    // Mark old detections as inactive
                    val inactiveThreshold = System.currentTimeMillis() - INACTIVE_TIMEOUT
                    repository.markOldInactive(inactiveThreshold)
                    
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
    
    private fun buildStatusText(): String {
        val parts = mutableListOf<String>()
        parts.add("Detections: ${detectionCount.value}")
        
        when (val ble = bleStatus.value) {
            is SubsystemStatus.Error -> parts.add("BLE: Error ${ble.code}")
            is SubsystemStatus.PermissionDenied -> parts.add("BLE: No permission")
            is SubsystemStatus.Disabled -> parts.add("BLE: Disabled")
            else -> {}
        }
        
        when (val wifi = wifiStatus.value) {
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
        
        try {
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (started) {
                wifiStatus.value = SubsystemStatus.Active
                Log.d(TAG, "WiFi scan started")
            } else {
                wifiStatus.value = SubsystemStatus.Error(-1, "Scan request rejected")
                logError("WiFi", -1, "WiFi scan request was rejected by the system", recoverable = true)
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
                        // Scan failed or was throttled
                        wifiStatus.value = SubsystemStatus.Error(-2, "Scan throttled")
                        logError("WiFi", -2, "WiFi scan was throttled by the system", recoverable = true)
                    }
                }
            }
        }
        
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
    }
    
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
        
        for (result in results) {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.wifiSsid?.toString()?.removeSurrounding("\"") ?: continue
            } else {
                @Suppress("DEPRECATION")
                result.SSID?.takeIf { it.isNotEmpty() } ?: continue
            }
            
            val bssid = result.BSSID ?: continue
            val rssi = result.level
            
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
                continue
            }
            
            // Check for MAC prefix match
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
            }
        }
    }
    
    // ==================== Detection Handling ====================
    
    private suspend fun handleDetection(detection: Detection) {
        // Check if we've already seen this device recently
        val existing = repository.getDetectionByMacAddress(detection.macAddress ?: "")
        
        if (existing != null && existing.isActive) {
            // Update existing detection with new signal strength and location
            val updated = existing.copy(
                timestamp = System.currentTimeMillis(),
                rssi = detection.rssi,
                signalStrength = detection.signalStrength,
                latitude = detection.latitude ?: existing.latitude,
                longitude = detection.longitude ?: existing.longitude,
                isActive = true
            )
            repository.updateDetection(updated)
            Log.d(TAG, "Updated detection: ${detection.deviceType}")
        } else {
            // New detection
            repository.insertDetection(detection)
            detectionCount.value++
            lastDetection.value = detection
            
            Log.d(TAG, "New detection: ${detection.deviceType} - ${detection.macAddress}")
            
            // Alert user
            alertUser(detection)
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

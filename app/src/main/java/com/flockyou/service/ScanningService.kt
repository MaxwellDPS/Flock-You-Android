package com.flockyou.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.wifi.WifiManager
import android.bluetooth.le.ScanResult as BleScanResult
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
    }
    
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
        
        isScanning.value = true
        Log.d(TAG, "Starting scanning")
        
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
                    
                    // Update notification
                    updateNotification("Detections: ${detectionCount.value}")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Scanning error", e)
                }
            }
        }
    }
    
    private fun stopScanning() {
        isScanning.value = false
        scanJob?.cancel()
        stopBleScan()
        unregisterWifiReceiver()
        Log.d(TAG, "Stopped scanning")
    }
    
    // ==================== BLE Scanning ====================
    
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Missing Bluetooth permissions")
            return
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
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
            Log.d(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
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
    
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: BleScanResult?) {
            result?.let {
                serviceScope.launch {
                    processBleScanResult(it)
                }
            }
        }
        
        override fun onBatchScanResults(results: MutableList<BleScanResult>?) {
            serviceScope.launch {
                results?.filterNotNull()?.forEach { processBleScanResult(it) }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error: $errorCode")
        }
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun processBleScanResult(result: BleScanResult) {
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
                matchedPatterns = gson.toJson(listOf("MAC prefix: ${macPrefix.prefix}"))
            )
            
            handleDetection(detection)
        }
    }
    
    // ==================== WiFi Scanning ====================
    
    @SuppressLint("MissingPermission")
    private fun startWifiScan() {
        if (!hasLocationPermissions()) {
            Log.w(TAG, "Missing location permissions for WiFi scan")
            return
        }
        
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "WiFi is disabled")
            return
        }
        
        try {
            @Suppress("DEPRECATION")
            wifiManager.startScan()
            Log.d(TAG, "WiFi scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WiFi scan", e)
        }
    }
    
    private fun registerWifiReceiver() {
        if (wifiScanReceiver != null) return
        
        wifiScanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    if (success) {
                        serviceScope.launch {
                            processWifiScanResults()
                        }
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
                    matchedPatterns = gson.toJson(listOf("MAC prefix: ${macPrefix.prefix}"))
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
        if (!hasLocationPermissions()) return
        
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            currentLocation = location
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

package com.flockyou.scanner.system

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.WorkSource
import android.util.Log
import androidx.core.content.ContextCompat
import com.flockyou.scanner.BleScanConfig
import com.flockyou.scanner.IBluetoothScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Method

/**
 * System/OEM privileged Bluetooth LE scanner.
 *
 * Capabilities:
 * - Can use BLUETOOTH_PRIVILEGED for continuous scanning
 * - No duty cycling enforcement
 * - Access to real MAC addresses
 * - WorkSource attribution to hide battery usage (optional)
 */
class SystemBluetoothScanner(
    private val context: Context
) : IBluetoothScanner {

    companion object {
        private const val TAG = "SystemBleScanner"

        // System permissions
        private const val PERMISSION_BLUETOOTH_PRIVILEGED = "android.permission.BLUETOOTH_PRIVILEGED"
        private const val PERMISSION_PEERS_MAC = "android.permission.PEERS_MAC_ADDRESS"
        private const val PERMISSION_LOCAL_MAC = "android.permission.LOCAL_MAC_ADDRESS"

        // UID for system attribution (telephony/radio)
        private const val SYSTEM_UID_RADIO = 1001
    }

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private val bleScanner: BluetoothLeScanner? by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    private val _scanResults = MutableSharedFlow<ScanResult>(replay = 0, extraBufferCapacity = 100)
    override val scanResults: Flow<ScanResult> = _scanResults.asSharedFlow()

    private val _batchedResults = MutableSharedFlow<List<ScanResult>>(replay = 0, extraBufferCapacity = 10)
    override val batchedResults: Flow<List<ScanResult>> = _batchedResults.asSharedFlow()

    private var currentConfig = BleScanConfig()
    private var scanCycleJob: Job? = null
    private var isScanningActive = false
    private var useWorkSourceAttribution = false

    // Cache for WorkSource method (thread-safe via synchronized)
    @Volatile
    private var setWorkSourceMethod: Method? = null
    private val methodLock = Any()

    private val scanCallbackImpl = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scope.launch {
                _scanResults.emit(result)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            scope.launch {
                _batchedResults.emit(results)
                results.forEach { _scanResults.emit(it) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Out of hardware resources"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "BLE scan failed: $errorMsg")
            _lastError.value = errorMsg
            isScanningActive = false
        }
    }

    override fun start(): Boolean {
        if (_isActive.value) {
            Log.d(TAG, "Scanner already active")
            return true
        }

        if (bluetoothAdapter == null || bleScanner == null) {
            _lastError.value = "Bluetooth not available"
            Log.e(TAG, "Bluetooth adapter or scanner not available")
            return false
        }

        if (bluetoothAdapter?.isEnabled != true) {
            _lastError.value = "Bluetooth is disabled"
            Log.w(TAG, "Bluetooth is disabled")
            return false
        }

        _isActive.value = true
        _lastError.value = null

        // Check if we have privileged permissions for continuous scanning
        val hasPrivileged = hasPermission(PERMISSION_BLUETOOTH_PRIVILEGED)
        Log.d(TAG, "System Bluetooth scanner started (privileged: $hasPrivileged)")

        startScanCycle()
        return true
    }

    override fun stop() {
        scanCycleJob?.cancel()
        scanCycleJob = null
        stopScan()
        _isActive.value = false
        Log.d(TAG, "System Bluetooth scanner stopped")
    }

    override fun requiresRuntimePermissions(): Boolean {
        // System apps may have permissions pre-granted
        return !hasAllPermissionsGranted()
    }

    override fun getRequiredPermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun configureScan(config: BleScanConfig) {
        val wasActive = _isActive.value
        if (wasActive && isScanningActive) {
            stopScan()
        }
        currentConfig = config
        if (wasActive) {
            startScanCycle()
        }
    }

    override fun supportsContinuousScanning(): Boolean {
        // System scanner can do continuous scanning with BLUETOOTH_PRIVILEGED
        return hasPermission(PERMISSION_BLUETOOTH_PRIVILEGED)
    }

    /**
     * Enable WorkSource attribution to attribute scanning to system UID.
     * This can hide battery usage from the app.
     */
    fun enableWorkSourceAttribution(enable: Boolean) {
        useWorkSourceAttribution = enable
        Log.d(TAG, "WorkSource attribution: $enable")
    }

    @SuppressLint("MissingPermission")
    override fun getRealMacAddress(device: BluetoothDevice): String {
        // With system permissions (PEERS_MAC_ADDRESS or LOCAL_MAC_ADDRESS),
        // the OS returns the real hardware address
        return device.address
    }

    override fun getRealMacAddress(scanResult: ScanResult): String {
        return scanResult.device.address
    }

    private fun startScanCycle() {
        scanCycleJob?.cancel()

        // If we have privileged permissions, we can scan continuously
        if (supportsContinuousScanning()) {
            // Continuous scanning - no duty cycling
            scanCycleJob = scope.launch {
                startScan()
                // Just keep it running until stopped
                while (_isActive.value) {
                    delay(1000)
                    // Periodically check if scan is still active
                    if (!isScanningActive && _isActive.value) {
                        Log.w(TAG, "Scan stopped unexpectedly, restarting")
                        startScan()
                    }
                }
            }
        } else {
            // Fall back to cycled scanning
            scanCycleJob = scope.launch {
                while (_isActive.value) {
                    startScan()
                    delay(currentConfig.scanDurationMillis)
                    stopScan()
                    delay(currentConfig.cooldownMillis)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanningActive) {
            Log.d(TAG, "Scan already in progress")
            return
        }

        val scanner = bleScanner ?: run {
            _lastError.value = "BLE scanner not available"
            return
        }

        try {
            val settingsBuilder = ScanSettings.Builder()
                .setScanMode(currentConfig.scanMode)
                .setCallbackType(currentConfig.callbackType)
                .setMatchMode(currentConfig.matchMode)
                .setNumOfMatches(currentConfig.numOfMatches)
                .setReportDelay(currentConfig.reportDelayMillis)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setLegacy(currentConfig.useLegacyMode)
                settingsBuilder.setPhy(currentConfig.phyMask)
            }

            // Apply WorkSource if enabled and available
            if (useWorkSourceAttribution) {
                applyWorkSource(settingsBuilder)
            }

            val settings = settingsBuilder.build()

            scanner.startScan(null, settings, scanCallbackImpl)
            isScanningActive = true
            Log.d(TAG, "BLE scan started (mode=${currentConfig.scanMode}, privileged=${supportsContinuousScanning()})")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for BLE scan", e)
            _lastError.value = "Permission denied: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan", e)
            _lastError.value = "Error: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanningActive) return

        try {
            bleScanner?.stopScan(scanCallbackImpl)
            isScanningActive = false
            Log.d(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE scan", e)
        }
    }

    /**
     * Apply WorkSource attribution to hide battery usage from this app.
     * Uses reflection to access hidden API.
     */
    private fun applyWorkSource(builder: ScanSettings.Builder) {
        try {
            val method = getSetWorkSourceMethod()
            if (method != null) {
                // Create WorkSource via reflection since constructor requires system permission
                val workSource = createWorkSourceViaReflection(SYSTEM_UID_RADIO)
                if (workSource != null) {
                    method.invoke(builder, workSource)
                    Log.d(TAG, "WorkSource attributed to system UID")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply WorkSource: ${e.message}")
        }
    }

    /**
     * Create WorkSource via reflection since the constructor requires signature permission.
     */
    private fun createWorkSourceViaReflection(uid: Int): WorkSource? {
        return try {
            val constructor = WorkSource::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(uid)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create WorkSource via reflection: ${e.message}")
            null
        }
    }

    /**
     * Get the hidden setWorkSource method via reflection.
     * Caches the method for subsequent calls. Thread-safe.
     */
    private fun getSetWorkSourceMethod(): Method? {
        // Double-checked locking for thread safety
        setWorkSourceMethod?.let { return it }

        synchronized(methodLock) {
            setWorkSourceMethod?.let { return it }

            return try {
                val method = ScanSettings.Builder::class.java.getDeclaredMethod(
                    "setWorkSource",
                    WorkSource::class.java
                )
                method.isAccessible = true
                setWorkSourceMethod = method
                method
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "setWorkSource method not found")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting setWorkSource method", e)
                null
            }
        }
    }

    private fun hasAllPermissionsGranted(): Boolean {
        return getRequiredPermissions().all { hasPermission(it) }
    }

    private fun hasPermission(permission: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}

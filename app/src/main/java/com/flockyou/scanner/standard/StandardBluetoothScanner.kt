package com.flockyou.scanner.standard

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

/**
 * Standard Bluetooth LE scanner for sideloaded apps.
 *
 * Limitations:
 * - Subject to OS duty cycling (scan 2s, idle 3s in balanced mode)
 * - SCAN_MODE_LOW_LATENCY honored for ~10-15 mins then downgraded
 * - MAC addresses may be randomized (especially for bonded devices)
 */
class StandardBluetoothScanner(
    private val context: Context
) : IBluetoothScanner {

    companion object {
        private const val TAG = "StandardBleScanner"
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

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    private val _scanResults = MutableSharedFlow<ScanResult>(replay = 0, extraBufferCapacity = 100)
    override val scanResults: Flow<ScanResult> = _scanResults.asSharedFlow()

    private val _batchedResults = MutableSharedFlow<List<ScanResult>>(replay = 0, extraBufferCapacity = 10)
    override val batchedResults: Flow<List<ScanResult>> = _batchedResults.asSharedFlow()

    private var currentConfig = BleScanConfig()
    private var scanCallback: ScanCallback? = null
    private var scanCycleJob: Job? = null
    private var isScanningActive = false

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

        if (!hasRequiredPermissions()) {
            _lastError.value = "Required permissions not granted"
            Log.e(TAG, "Cannot start: permissions not granted")
            return false
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
        startScanCycle()
        Log.d(TAG, "Standard Bluetooth scanner started")
        return true
    }

    override fun stop() {
        scanCycleJob?.cancel()
        scanCycleJob = null
        stopScan()
        _isActive.value = false
        // Cancel the supervisor job to clean up all coroutines
        supervisorJob.cancel()
        Log.d(TAG, "Standard Bluetooth scanner stopped")
    }

    override fun requiresRuntimePermissions(): Boolean = true

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

    override fun supportsContinuousScanning(): Boolean = false

    @SuppressLint("MissingPermission")
    override fun getRealMacAddress(device: BluetoothDevice): String {
        // In standard mode, we can only return what the OS gives us
        // This may be randomized for privacy
        return device.address
    }

    override fun getRealMacAddress(scanResult: ScanResult): String {
        return scanResult.device.address
    }

    private fun startScanCycle() {
        scanCycleJob?.cancel()
        scanCycleJob = scope.launch {
            while (_isActive.value) {
                startScan()
                delay(currentConfig.scanDurationMillis)
                stopScan()
                delay(currentConfig.cooldownMillis)
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

            val settings = settingsBuilder.build()

            scanner.startScan(null, settings, scanCallbackImpl)
            isScanningActive = true
            Log.d(TAG, "BLE scan started (mode=${currentConfig.scanMode})")
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

    private fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}

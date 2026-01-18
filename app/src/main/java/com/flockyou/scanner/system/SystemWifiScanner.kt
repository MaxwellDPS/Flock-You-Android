package com.flockyou.scanner.system

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.flockyou.scanner.IWifiScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method

/**
 * System/OEM privileged WiFi scanner.
 *
 * Capabilities:
 * - Can disable WiFi scan throttling via hidden API
 * - Access to real BSSID/MAC addresses (with PEERS_MAC_ADDRESS permission)
 * - No scan rate limitations
 */
class SystemWifiScanner(
    private val context: Context
) : IWifiScanner {

    companion object {
        private const val TAG = "SystemWifiScanner"

        // Hidden API method name for disabling throttling
        private const val METHOD_SET_SCAN_THROTTLE = "setScanThrottleEnabled"

        // System permission for MAC address access
        private const val PERMISSION_PEERS_MAC = "android.permission.PEERS_MAC_ADDRESS"
        private const val PERMISSION_LOCAL_MAC = "android.permission.LOCAL_MAC_ADDRESS"
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    override val scanResults: Flow<List<ScanResult>> = _scanResults.asStateFlow()

    private var scanReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false
    private var throttlingDisabled = false

    // Cached method reference for performance (thread-safe via synchronized)
    @Volatile
    private var setThrottleMethod: Method? = null
    private val methodLock = Any()

    override fun start(): Boolean {
        if (_isActive.value) {
            Log.d(TAG, "Scanner already active")
            return true
        }

        // System scanner may have permissions pre-granted
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "WiFi is disabled")
            // Continue anyway - might still work in some cases
        }

        // Register scan results receiver
        registerReceiver()

        // Attempt to disable throttling
        if (canDisableThrottling()) {
            val disabled = disableThrottling()
            Log.d(TAG, "Throttling disable attempt: $disabled")
        }

        _isActive.value = true
        _lastError.value = null
        Log.d(TAG, "System WiFi scanner started")
        return true
    }

    override fun stop() {
        // Re-enable throttling before stopping (be a good citizen)
        if (throttlingDisabled) {
            enableThrottling()
        }
        unregisterReceiver()
        _isActive.value = false
        Log.d(TAG, "System WiFi scanner stopped")
    }

    override fun requiresRuntimePermissions(): Boolean {
        // System apps may have permissions pre-granted
        return !hasAllPermissionsGranted()
    }

    override fun getRequiredPermissions(): List<String> {
        return buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }

    override fun requestScan(): Boolean {
        if (!_isActive.value) {
            Log.w(TAG, "Scanner not active, starting first")
            if (!start()) {
                return false
            }
        }

        return try {
            @Suppress("DEPRECATION")
            val success = wifiManager.startScan()
            if (!success) {
                Log.w(TAG, "WiFi scan request failed")
                _lastError.value = "Scan failed"
            } else {
                Log.d(TAG, "WiFi scan initiated (throttling disabled: $throttlingDisabled)")
            }
            success
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for WiFi scan", e)
            _lastError.value = "Permission denied: ${e.message}"
            false
        }
    }

    override fun canDisableThrottling(): Boolean {
        // Check if we have system-level permissions that would allow throttling control
        return hasPermission(PERMISSION_PEERS_MAC) ||
               hasPermission(PERMISSION_LOCAL_MAC) ||
               hasPermission("android.permission.CONNECTIVITY_INTERNAL") ||
               isThrottleMethodAvailable()
    }

    override fun disableThrottling(): Boolean {
        if (throttlingDisabled) {
            Log.d(TAG, "Throttling already disabled")
            return true
        }

        return try {
            val method = getSetThrottleMethod()
            if (method != null) {
                method.invoke(wifiManager, false)
                throttlingDisabled = true
                Log.i(TAG, "WiFi scan throttling DISABLED via hidden API")
                true
            } else {
                Log.w(TAG, "setScanThrottleEnabled method not available")
                _lastError.value = "Throttle method not available"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable throttling", e)
            _lastError.value = "Failed to disable throttling: ${e.message}"
            false
        }
    }

    /**
     * Re-enable throttling (call when stopping scanner).
     */
    private fun enableThrottling(): Boolean {
        return try {
            val method = getSetThrottleMethod()
            if (method != null) {
                method.invoke(wifiManager, true)
                throttlingDisabled = false
                Log.i(TAG, "WiFi scan throttling re-enabled")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable throttling", e)
            false
        }
    }

    override fun getRealBssid(scanResult: ScanResult): String {
        // With system permissions, we get the real BSSID
        // The OS doesn't mask it for privileged apps
        return scanResult.BSSID
    }

    private fun registerReceiver() {
        if (isReceiverRegistered) return

        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    try {
                        val results = wifiManager.scanResults
                        _scanResults.value = results
                        if (success) {
                            Log.d(TAG, "WiFi scan completed: ${results.size} networks (throttle disabled: $throttlingDisabled)")
                        } else {
                            Log.w(TAG, "WiFi scan failed or throttled")
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied getting scan results", e)
                        _lastError.value = "Permission denied: ${e.message}"
                    }
                }
            }
        }

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scanReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!isReceiverRegistered) return

        try {
            scanReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
        scanReceiver = null
        isReceiverRegistered = false
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

    /**
     * Get the hidden setScanThrottleEnabled method via reflection.
     * Caches the method for subsequent calls. Thread-safe.
     */
    private fun getSetThrottleMethod(): Method? {
        // Double-checked locking for thread safety
        setThrottleMethod?.let { return it }

        synchronized(methodLock) {
            setThrottleMethod?.let { return it }

            return try {
                val method = wifiManager.javaClass.getDeclaredMethod(
                    METHOD_SET_SCAN_THROTTLE,
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                setThrottleMethod = method
                method
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "setScanThrottleEnabled method not found (expected on non-system builds)")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting setScanThrottleEnabled method", e)
                null
            }
        }
    }

    /**
     * Check if the throttle method is available without actually invoking it.
     */
    private fun isThrottleMethodAvailable(): Boolean {
        return getSetThrottleMethod() != null
    }
}

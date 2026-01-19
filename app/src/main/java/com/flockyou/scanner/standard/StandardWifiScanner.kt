package com.flockyou.scanner.standard

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

/**
 * Standard WiFi scanner for sideloaded apps.
 *
 * Limitations:
 * - Subject to scan throttling (4 scans / 2 mins foreground, 1 scan / 30 mins background)
 * - Cannot programmatically disable throttling
 * - BSSID may be masked/randomized in some cases
 */
class StandardWifiScanner(
    private val context: Context
) : IWifiScanner {

    companion object {
        private const val TAG = "StandardWifiScanner"
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

        if (!wifiManager.isWifiEnabled) {
            _lastError.value = "WiFi is disabled"
            Log.w(TAG, "WiFi is disabled")
            // Continue anyway - scan may still work
        }

        // Register scan results receiver
        registerReceiver()

        _isActive.value = true
        _lastError.value = null
        Log.d(TAG, "Standard WiFi scanner started")
        return true
    }

    override fun stop() {
        unregisterReceiver()
        _isActive.value = false
        Log.d(TAG, "Standard WiFi scanner stopped")
    }

    override fun requiresRuntimePermissions(): Boolean = true

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
                Log.w(TAG, "WiFi scan request failed (likely throttled)")
                _lastError.value = "Scan throttled by OS"
            }
            success
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for WiFi scan", e)
            _lastError.value = "Permission denied: ${e.message}"
            false
        }
    }

    override fun canDisableThrottling(): Boolean = false

    override fun disableThrottling(): Boolean {
        Log.w(TAG, "Cannot disable throttling in standard mode")
        _lastError.value = "Throttling control requires system privileges"
        return false
    }

    override fun getRealBssid(scanResult: ScanResult): String {
        // In standard mode, we can only return what the OS gives us
        // This may be randomized in some cases
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
                            Log.d(TAG, "WiFi scan completed: ${results.size} networks found")
                            _lastError.value = null // Clear any previous error
                        } else {
                            Log.w(TAG, "WiFi scan failed or throttled, returning cached results")
                            _lastError.value = "Scan throttled by OS"
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied getting scan results", e)
                        _lastError.value = "Permission revoked: WiFi scan permission denied"
                        // Notify that we need to stop due to permission revocation
                        _isActive.value = false
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

    private fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}

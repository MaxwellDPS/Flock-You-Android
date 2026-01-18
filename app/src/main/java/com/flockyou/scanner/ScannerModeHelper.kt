package com.flockyou.scanner

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.flockyou.BuildConfig
import com.flockyou.privilege.PrivilegeMode
import com.flockyou.privilege.PrivilegeModeDetector
import java.lang.reflect.Method

/**
 * Helper class for ScanningService to leverage system/OEM privileges.
 *
 * This helper provides methods for:
 * - Detecting and reporting the current privilege mode
 * - Disabling WiFi scan throttling (when privileged)
 * - Accessing enhanced BLE scanning capabilities
 * - Getting real MAC addresses (when privileged)
 *
 * The ScanningService can use this helper to enhance its existing
 * scanning logic without requiring a full refactor.
 */
class ScannerModeHelper(private val context: Context) {

    companion object {
        private const val TAG = "ScannerModeHelper"

        // Hidden API method names
        private const val METHOD_SET_SCAN_THROTTLE = "setScanThrottleEnabled"
        private const val METHOD_IS_SCAN_THROTTLE_ENABLED = "isScanThrottleEnabled"
    }

    /**
     * Current privilege mode detected at runtime.
     */
    val privilegeMode: PrivilegeMode by lazy {
        PrivilegeModeDetector.detect(context)
    }

    /**
     * Whether the app is running in privileged mode (system or OEM).
     */
    val isPrivileged: Boolean
        get() = privilegeMode.isPrivileged

    /**
     * Build mode from BuildConfig (sideload, system, or oem).
     */
    val buildMode: String = BuildConfig.BUILD_MODE

    /**
     * Whether this is a system build.
     */
    val isSystemBuild: Boolean = BuildConfig.IS_SYSTEM_BUILD

    /**
     * Whether this is an OEM build.
     */
    val isOemBuild: Boolean = BuildConfig.IS_OEM_BUILD

    // WiFi Manager reference
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    // Cached reflection methods
    private var setThrottleMethod: Method? = null
    private var isThrottleEnabledMethod: Method? = null

    // Track if we've disabled throttling
    private var throttlingDisabled = false

    /**
     * Attempt to disable WiFi scan throttling.
     *
     * This only works when the app has system/OEM privileges.
     * For sideload mode, this will log a warning and return false.
     *
     * @return true if throttling was disabled, false otherwise
     */
    fun disableWifiThrottling(): Boolean {
        if (throttlingDisabled) {
            Log.d(TAG, "WiFi throttling already disabled")
            return true
        }

        if (!privilegeMode.canDisableWifiThrottling) {
            Log.d(TAG, "Cannot disable throttling in ${privilegeMode} mode")
            return false
        }

        return try {
            val method = getSetThrottleMethod()
            if (method != null) {
                method.invoke(wifiManager, false)
                throttlingDisabled = true
                Log.i(TAG, "WiFi scan throttling DISABLED")
                true
            } else {
                Log.w(TAG, "setScanThrottleEnabled method not available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable WiFi throttling", e)
            false
        }
    }

    /**
     * Re-enable WiFi scan throttling.
     *
     * Should be called when the service is stopped to restore normal behavior.
     */
    fun enableWifiThrottling(): Boolean {
        if (!throttlingDisabled) {
            return true
        }

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
            Log.e(TAG, "Failed to re-enable WiFi throttling", e)
            false
        }
    }

    /**
     * Check if WiFi scan throttling is currently enabled.
     */
    fun isWifiThrottlingEnabled(): Boolean? {
        return try {
            val method = getIsThrottleEnabledMethod()
            if (method != null) {
                method.invoke(wifiManager) as? Boolean
            } else {
                null // Unknown
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check throttling state", e)
            null
        }
    }

    /**
     * Get the optimal BLE scan mode based on privilege level.
     *
     * For privileged apps, we can use LOW_LATENCY continuously.
     * For sideload apps, we should use BALANCED to avoid OS intervention.
     *
     * @return ScanSettings.SCAN_MODE_* constant
     */
    fun getOptimalBleScanMode(): Int {
        return if (privilegeMode.hasContinuousBleScan) {
            android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            // Use LOW_LATENCY but expect the OS to downgrade us eventually
            android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
        }
    }

    /**
     * Get the recommended BLE scan duration based on privilege level.
     *
     * Privileged apps can scan longer without OS intervention.
     * Sideload apps should use shorter durations with cooldowns.
     *
     * @return scan duration in milliseconds
     */
    fun getRecommendedBleScanDuration(): Long {
        return if (privilegeMode.hasContinuousBleScan) {
            60_000L // 60 seconds for privileged
        } else {
            25_000L // 25 seconds for sideload
        }
    }

    /**
     * Get the recommended BLE cooldown period based on privilege level.
     *
     * @return cooldown duration in milliseconds
     */
    fun getRecommendedBleCooldown(): Long {
        return if (privilegeMode.hasContinuousBleScan) {
            1_000L // 1 second for privileged (minimal)
        } else {
            5_000L // 5 seconds for sideload (prevent thermal throttling)
        }
    }

    /**
     * Get the recommended WiFi scan interval based on privilege level.
     *
     * Privileged apps can scan more frequently.
     * Sideload apps are throttled to 4 scans / 2 minutes.
     *
     * @return scan interval in milliseconds
     */
    fun getRecommendedWifiScanInterval(): Long {
        return if (privilegeMode.canDisableWifiThrottling) {
            10_000L // 10 seconds for privileged
        } else {
            30_000L // 30 seconds for sideload (to stay within throttle limits)
        }
    }

    /**
     * Get a status summary for logging/display.
     */
    fun getStatusSummary(): String {
        return buildString {
            appendLine("=== Scanner Mode Status ===")
            appendLine("Build Mode: $buildMode")
            appendLine("Privilege Mode: $privilegeMode")
            appendLine("Capabilities:")
            appendLine("  - WiFi Throttle Control: ${privilegeMode.canDisableWifiThrottling}")
            appendLine("  - Real MAC Access: ${privilegeMode.hasRealMacAccess}")
            appendLine("  - Continuous BLE: ${privilegeMode.hasContinuousBleScan}")
            appendLine("  - Privileged Phone: ${privilegeMode.hasPrivilegedPhoneAccess}")
            appendLine("  - Persistent Process: ${privilegeMode.canBePersistent}")
            if (throttlingDisabled) {
                appendLine("WiFi Throttling: DISABLED")
            }
        }
    }

    /**
     * Log the current mode status.
     */
    fun logStatus() {
        Log.i(TAG, getStatusSummary())
    }

    /**
     * Called when the service is starting - apply any privileged enhancements.
     */
    fun onServiceStart() {
        logStatus()

        // Attempt to disable WiFi throttling if we can
        if (privilegeMode.canDisableWifiThrottling) {
            disableWifiThrottling()
        }
    }

    /**
     * Called when the service is stopping - restore normal behavior.
     */
    fun onServiceStop() {
        // Re-enable throttling if we disabled it
        if (throttlingDisabled) {
            enableWifiThrottling()
        }
    }

    private fun getSetThrottleMethod(): Method? {
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
            Log.d(TAG, "$METHOD_SET_SCAN_THROTTLE not found")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting $METHOD_SET_SCAN_THROTTLE", e)
            null
        }
    }

    private fun getIsThrottleEnabledMethod(): Method? {
        isThrottleEnabledMethod?.let { return it }

        return try {
            val method = wifiManager.javaClass.getDeclaredMethod(METHOD_IS_SCAN_THROTTLE_ENABLED)
            method.isAccessible = true
            isThrottleEnabledMethod = method
            method
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "$METHOD_IS_SCAN_THROTTLE_ENABLED not found")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting $METHOD_IS_SCAN_THROTTLE_ENABLED", e)
            null
        }
    }
}

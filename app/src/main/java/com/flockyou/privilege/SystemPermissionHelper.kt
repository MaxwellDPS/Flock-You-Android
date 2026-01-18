package com.flockyou.privilege

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.flockyou.BuildConfig

/**
 * Helper class for managing permissions in both standard and privileged modes.
 *
 * This class handles the difference between:
 * - Sideload mode: All permissions require runtime grants
 * - System/OEM mode: Many permissions are pre-granted
 */
object SystemPermissionHelper {
    private const val TAG = "SystemPermissionHelper"

    // Permission request codes
    const val REQUEST_CODE_PERMISSIONS = 1001
    const val REQUEST_CODE_BACKGROUND_LOCATION = 1002
    const val REQUEST_CODE_BATTERY_OPTIMIZATION = 1003
    const val REQUEST_CODE_NOTIFICATION = 1004

    /**
     * All permissions needed for basic scanning functionality.
     */
    val CORE_PERMISSIONS = buildList {
        // Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // WiFi
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // Location
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Phone state for cellular monitoring
        add(Manifest.permission.READ_PHONE_STATE)
    }

    /**
     * Additional permissions for extended functionality.
     */
    val EXTENDED_PERMISSIONS = buildList {
        // Background location (requires separate request flow)
        add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Audio for ultrasonic detection
        add(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * System/OEM privileged permissions.
     * These are only granted when app is a system app.
     */
    val PRIVILEGED_PERMISSIONS = listOf(
        "android.permission.BLUETOOTH_PRIVILEGED",
        "android.permission.PEERS_MAC_ADDRESS",
        "android.permission.LOCAL_MAC_ADDRESS",
        "android.permission.READ_PRIVILEGED_PHONE_STATE",
        "android.permission.CONNECTIVITY_INTERNAL",
        "android.permission.PERSISTENT_ACTIVITY"
    )

    /**
     * Check if a specific permission is granted.
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Error checking permission $permission", e)
            false
        }
    }

    /**
     * Check if all core permissions are granted.
     */
    fun hasAllCorePermissions(context: Context): Boolean {
        return CORE_PERMISSIONS.all { hasPermission(context, it) }
    }

    /**
     * Get list of missing core permissions.
     */
    fun getMissingCorePermissions(context: Context): List<String> {
        return CORE_PERMISSIONS.filter { !hasPermission(context, it) }
    }

    /**
     * Check if background location is granted.
     */
    fun hasBackgroundLocation(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Check if notification permission is granted (Android 13+).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Not required before Android 13
        }
    }

    /**
     * Request core permissions.
     * In system/OEM mode, most will already be granted.
     */
    fun requestCorePermissions(activity: Activity) {
        val missing = getMissingCorePermissions(activity)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missing.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    /**
     * Request background location permission.
     * Must be requested separately from foreground location (Android 11+).
     */
    fun requestBackgroundLocation(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!hasBackgroundLocation(activity)) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_CODE_BACKGROUND_LOCATION
                )
            }
        }
    }

    /**
     * Request notification permission (Android 13+).
     */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission(activity)) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
            }
        }
    }

    /**
     * Check if battery optimization is disabled for this app.
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Request to disable battery optimization.
     * Opens system settings for the user to grant this.
     */
    @Suppress("BatteryLife")
    fun requestDisableBatteryOptimization(context: Context) {
        if (!isBatteryOptimizationDisabled(context)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open battery optimization settings", e)
                // Fallback to general battery settings
                try {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to open general battery settings", e2)
                }
            }
        }
    }

    /**
     * Open app settings for manual permission grants.
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    /**
     * Check which privileged permissions are granted.
     * These are only available in system/OEM mode.
     */
    fun getGrantedPrivilegedPermissions(context: Context): List<String> {
        return PRIVILEGED_PERMISSIONS.filter { hasPermission(context, it) }
    }

    /**
     * Determine if app should skip runtime permission requests.
     * System apps may have all permissions pre-granted.
     */
    fun shouldSkipPermissionRequests(context: Context): Boolean {
        val mode = PrivilegeModeDetector.detect(context)
        return when (mode) {
            is PrivilegeMode.OEM -> true  // OEM apps have everything pre-granted
            is PrivilegeMode.System -> hasAllCorePermissions(context)  // Check if system install has them
            is PrivilegeMode.Sideload -> false  // Always need to request
        }
    }

    /**
     * Get a summary of the current permission state.
     */
    fun getPermissionSummary(context: Context): PermissionSummary {
        val mode = PrivilegeModeDetector.detect(context)
        val grantedCore = CORE_PERMISSIONS.count { hasPermission(context, it) }
        val grantedPrivileged = getGrantedPrivilegedPermissions(context).size

        return PermissionSummary(
            privilegeMode = mode,
            corePermissionsGranted = grantedCore,
            corePermissionsTotal = CORE_PERMISSIONS.size,
            hasBackgroundLocation = hasBackgroundLocation(context),
            hasNotifications = hasNotificationPermission(context),
            isBatteryOptimizationDisabled = isBatteryOptimizationDisabled(context),
            privilegedPermissionsGranted = grantedPrivileged,
            privilegedPermissionsTotal = PRIVILEGED_PERMISSIONS.size,
            buildMode = BuildConfig.BUILD_MODE,
            isSystemBuild = BuildConfig.IS_SYSTEM_BUILD,
            isOemBuild = BuildConfig.IS_OEM_BUILD
        )
    }

    /**
     * Check if all requirements for scanning are met.
     */
    fun canStartScanning(context: Context): Boolean {
        // Core permissions are required
        if (!hasAllCorePermissions(context)) return false

        // For sideload mode, also need background location for reliable operation
        val mode = PrivilegeModeDetector.detect(context)
        if (mode is PrivilegeMode.Sideload) {
            return hasBackgroundLocation(context)
        }

        return true
    }

    /**
     * Get user-friendly description of what's missing.
     */
    fun getMissingRequirementsDescription(context: Context): List<String> {
        val missing = mutableListOf<String>()

        if (!hasAllCorePermissions(context)) {
            val missingPerms = getMissingCorePermissions(context)
            when {
                missingPerms.any { it.contains("BLUETOOTH") } ->
                    missing.add("Bluetooth permissions for BLE scanning")
                missingPerms.any { it.contains("LOCATION") } ->
                    missing.add("Location permission for WiFi/cellular scanning")
                missingPerms.any { it.contains("WIFI") } ->
                    missing.add("WiFi permissions for network scanning")
                missingPerms.any { it.contains("PHONE") } ->
                    missing.add("Phone state for IMSI catcher detection")
            }
        }

        if (!hasBackgroundLocation(context)) {
            missing.add("Background location for continuous scanning")
        }

        if (!isBatteryOptimizationDisabled(context)) {
            missing.add("Battery optimization exemption for reliable operation")
        }

        if (!hasNotificationPermission(context)) {
            missing.add("Notification permission for alerts")
        }

        return missing
    }
}

/**
 * Summary of the current permission state.
 */
data class PermissionSummary(
    val privilegeMode: PrivilegeMode,
    val corePermissionsGranted: Int,
    val corePermissionsTotal: Int,
    val hasBackgroundLocation: Boolean,
    val hasNotifications: Boolean,
    val isBatteryOptimizationDisabled: Boolean,
    val privilegedPermissionsGranted: Int,
    val privilegedPermissionsTotal: Int,
    val buildMode: String,
    val isSystemBuild: Boolean,
    val isOemBuild: Boolean
) {
    val allCoreGranted: Boolean
        get() = corePermissionsGranted == corePermissionsTotal

    val isReadyForScanning: Boolean
        get() = allCoreGranted && hasBackgroundLocation

    val isFullyConfigured: Boolean
        get() = isReadyForScanning && hasNotifications && isBatteryOptimizationDisabled

    val hasAnyPrivilegedPermissions: Boolean
        get() = privilegedPermissionsGranted > 0

    fun toDisplayString(): String {
        return buildString {
            appendLine("Mode: ${privilegeMode}")
            appendLine("Build: $buildMode (system=$isSystemBuild, oem=$isOemBuild)")
            appendLine("Core: $corePermissionsGranted/$corePermissionsTotal")
            appendLine("Background Location: $hasBackgroundLocation")
            appendLine("Notifications: $hasNotifications")
            appendLine("Battery Exempt: $isBatteryOptimizationDisabled")
            if (privilegedPermissionsGranted > 0) {
                appendLine("Privileged: $privilegedPermissionsGranted/$privilegedPermissionsTotal")
            }
        }
    }
}

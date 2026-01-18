package com.flockyou.privilege

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Represents the privilege level the app is running with.
 *
 * The app can run in three modes:
 * - Sideload: Standard user app with runtime permissions
 * - System: System app with elevated permissions (installed in /system/priv-app)
 * - OEM: OEM-embedded app with maximum privileges (signed with platform key)
 */
sealed class PrivilegeMode {
    /**
     * Standard sideloaded app mode.
     * - Requires runtime permission grants
     * - Subject to battery optimization
     * - WiFi scan throttling applies
     * - BLE duty cycling active
     * - Limited cellular access
     */
    object Sideload : PrivilegeMode() {
        override fun toString() = "Sideload"
    }

    /**
     * System privileged app mode (installed in /system/priv-app).
     * - Pre-granted dangerous permissions
     * - Exempt from some battery restrictions
     * - Can disable WiFi scan throttling
     * - Can bypass BLE duty cycling
     * - Enhanced cellular access
     */
    data class System(
        val hasPrivilegedPermissions: Boolean = false,
        val canDisableThrottling: Boolean = false,
        val hasPeersMacPermission: Boolean = false
    ) : PrivilegeMode() {
        override fun toString() = "System(privileged=$hasPrivilegedPermissions, throttling=$canDisableThrottling, mac=$hasPeersMacPermission)"
    }

    /**
     * OEM embedded app mode (signed with platform certificate).
     * - Full system privileges
     * - Can access hidden APIs directly
     * - Real-time modem access
     * - No scan restrictions
     * - IMEI/IMSI access for Stingray detection
     */
    data class OEM(
        val oemName: String? = null,
        val platformSigned: Boolean = false,
        val hasReadPrivilegedPhoneState: Boolean = false
    ) : PrivilegeMode() {
        override fun toString() = "OEM(name=$oemName, platformSigned=$platformSigned, phoneState=$hasReadPrivilegedPhoneState)"
    }

    /**
     * Check if this mode has elevated privileges.
     */
    val isPrivileged: Boolean
        get() = this !is Sideload

    /**
     * Check if WiFi scan throttling can be disabled.
     */
    val canDisableWifiThrottling: Boolean
        get() = when (this) {
            is Sideload -> false
            is System -> canDisableThrottling
            is OEM -> true
        }

    /**
     * Check if real MAC addresses are available.
     */
    val hasRealMacAccess: Boolean
        get() = when (this) {
            is Sideload -> false
            is System -> hasPeersMacPermission
            is OEM -> true
        }

    /**
     * Check if privileged phone state is available (IMEI/IMSI).
     */
    val hasPrivilegedPhoneAccess: Boolean
        get() = when (this) {
            is Sideload -> false
            is System -> false
            is OEM -> hasReadPrivilegedPhoneState
        }

    /**
     * Check if BLE continuous scanning is available.
     */
    val hasContinuousBleScan: Boolean
        get() = this !is Sideload

    /**
     * Check if process can be marked as persistent.
     */
    val canBePersistent: Boolean
        get() = this is OEM
}

/**
 * Detects the current privilege mode at runtime.
 *
 * Detection hierarchy:
 * 1. Check for OEM mode (platform signature + privileged phone state)
 * 2. Check for System mode (priv-app + specific permissions)
 * 3. Default to Sideload mode
 */
object PrivilegeModeDetector {
    private const val TAG = "PrivilegeModeDetector"

    // System/OEM permissions to check
    private const val PERMISSION_PEERS_MAC_ADDRESS = "android.permission.PEERS_MAC_ADDRESS"
    private const val PERMISSION_READ_PRIVILEGED_PHONE_STATE = "android.permission.READ_PRIVILEGED_PHONE_STATE"
    private const val PERMISSION_BLUETOOTH_PRIVILEGED = "android.permission.BLUETOOTH_PRIVILEGED"
    private const val PERMISSION_LOCAL_MAC_ADDRESS = "android.permission.LOCAL_MAC_ADDRESS"
    private const val PERMISSION_CONNECTIVITY_INTERNAL = "android.permission.CONNECTIVITY_INTERNAL"

    // Cache the detected mode
    @Volatile
    private var cachedMode: PrivilegeMode? = null

    /**
     * Detect the current privilege mode.
     * Result is cached for performance.
     */
    fun detect(context: Context): PrivilegeMode {
        cachedMode?.let { return it }

        synchronized(this) {
            cachedMode?.let { return it }

            val mode = detectInternal(context)
            cachedMode = mode
            Log.i(TAG, "Detected privilege mode: $mode")
            return mode
        }
    }

    /**
     * Force re-detection of privilege mode.
     * Use sparingly - typically only needed after reinstall.
     */
    fun refresh(context: Context): PrivilegeMode {
        synchronized(this) {
            cachedMode = null
            return detect(context)
        }
    }

    private fun detectInternal(context: Context): PrivilegeMode {
        val pm = context.packageManager
        val packageName = context.packageName

        // Check if app is a system app
        val isSystemApp = isSystemApp(context)
        val isPrivilegedApp = isPrivilegedApp(context)
        val isPlatformSigned = isPlatformSigned(context)

        Log.d(TAG, "Detection: isSystemApp=$isSystemApp, isPrivilegedApp=$isPrivilegedApp, isPlatformSigned=$isPlatformSigned")

        // Check for specific permissions
        val hasPeersMac = hasPermission(context, PERMISSION_PEERS_MAC_ADDRESS)
        val hasPrivilegedPhone = hasPermission(context, PERMISSION_READ_PRIVILEGED_PHONE_STATE)
        val hasBluetoothPrivileged = hasPermission(context, PERMISSION_BLUETOOTH_PRIVILEGED)
        val hasLocalMac = hasPermission(context, PERMISSION_LOCAL_MAC_ADDRESS)
        val hasConnectivityInternal = hasPermission(context, PERMISSION_CONNECTIVITY_INTERNAL)

        Log.d(TAG, "Permissions: peersMac=$hasPeersMac, privilegedPhone=$hasPrivilegedPhone, btPrivileged=$hasBluetoothPrivileged")

        // OEM Mode: Platform signed with privileged phone state
        if (isPlatformSigned && hasPrivilegedPhone) {
            val oemName = detectOemName()
            return PrivilegeMode.OEM(
                oemName = oemName,
                platformSigned = true,
                hasReadPrivilegedPhoneState = true
            )
        }

        // System Mode: Privileged app with system permissions
        if (isPrivilegedApp || isSystemApp) {
            val canDisableThrottle = hasConnectivityInternal || hasBluetoothPrivileged
            return PrivilegeMode.System(
                hasPrivilegedPermissions = hasBluetoothPrivileged,
                canDisableThrottling = canDisableThrottle,
                hasPeersMacPermission = hasPeersMac || hasLocalMac
            )
        }

        // Check if any system permission is granted (might be system app without FLAG_SYSTEM)
        if (hasPeersMac || hasBluetoothPrivileged || hasLocalMac) {
            return PrivilegeMode.System(
                hasPrivilegedPermissions = hasBluetoothPrivileged,
                canDisableThrottling = hasBluetoothPrivileged,
                hasPeersMacPermission = hasPeersMac || hasLocalMac
            )
        }

        // Default: Sideload mode
        return PrivilegeMode.Sideload
    }

    /**
     * Check if the app is installed as a system app.
     */
    fun isSystemApp(context: Context): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                0
            )
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to check system app status", e)
            false
        }
    }

    /**
     * Check if the app is installed as a privileged system app.
     * Privileged apps are in /system/priv-app and have more permissions available.
     */
    fun isPrivilegedApp(context: Context): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                0
            )
            // Check both system flag and if installed in priv-app
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val sourceDir = appInfo.sourceDir

            isSystem && (sourceDir?.contains("/priv-app/") == true ||
                        sourceDir?.contains("/system_ext/priv-app/") == true)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to check privileged app status", e)
            false
        }
    }

    /**
     * Check if the app is signed with the platform certificate.
     * Platform-signed apps have the highest privilege level.
     */
    fun isPlatformSigned(context: Context): Boolean {
        return try {
            val pm = context.packageManager

            // Compare signatures with a known platform package (like "android" settings)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val appSignatures = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo

                val platformSignatures = pm.getPackageInfo(
                    "android",
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo

                if (appSignatures != null && platformSignatures != null) {
                    val appSigs = appSignatures.apkContentsSigners
                    val platformSigs = platformSignatures.apkContentsSigners

                    for (appSig in appSigs) {
                        for (platformSig in platformSigs) {
                            if (appSig == platformSig) {
                                return true
                            }
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val appSignatures = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures

                @Suppress("DEPRECATION")
                val platformSignatures = pm.getPackageInfo(
                    "android",
                    PackageManager.GET_SIGNATURES
                ).signatures

                if (appSignatures != null && platformSignatures != null) {
                    for (appSig in appSignatures) {
                        for (platformSig in platformSignatures) {
                            if (appSig == platformSig) {
                                return true
                            }
                        }
                    }
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check platform signature", e)
            false
        }
    }

    /**
     * Check if a specific permission is granted.
     */
    private fun hasPermission(context: Context, permission: String): Boolean {
        return try {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Try to detect the OEM name from system properties.
     */
    private fun detectOemName(): String? {
        return try {
            val manufacturer = Build.MANUFACTURER
            val product = Build.PRODUCT

            // Check for known privacy-focused OS
            when {
                product.contains("graphene", ignoreCase = true) -> "GrapheneOS"
                product.contains("calyx", ignoreCase = true) -> "CalyxOS"
                product.contains("lineage", ignoreCase = true) -> "LineageOS"
                product.contains("/e/", ignoreCase = true) -> "/e/OS"
                else -> manufacturer
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get a human-readable description of the current mode.
     */
    fun getModeDescription(mode: PrivilegeMode): String {
        return when (mode) {
            is PrivilegeMode.Sideload -> "Standard Mode - Running with user permissions. Some features may be limited."
            is PrivilegeMode.System -> "System Mode - Running as privileged system app with enhanced capabilities."
            is PrivilegeMode.OEM -> {
                val oemInfo = mode.oemName?.let { " on $it" } ?: ""
                "OEM Mode$oemInfo - Running with platform privileges. All features available."
            }
        }
    }

    /**
     * Get capabilities summary for the current mode.
     */
    fun getCapabilitiesSummary(mode: PrivilegeMode): List<Pair<String, Boolean>> {
        return listOf(
            "WiFi Throttling Bypass" to mode.canDisableWifiThrottling,
            "Real MAC Addresses" to mode.hasRealMacAccess,
            "Continuous BLE Scanning" to mode.hasContinuousBleScan,
            "Privileged Phone Access (IMEI/IMSI)" to mode.hasPrivilegedPhoneAccess,
            "Persistent Process" to mode.canBePersistent
        )
    }
}

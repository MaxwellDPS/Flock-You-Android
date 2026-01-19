package com.flockyou.scanner

import android.content.Context
import android.util.Log
import com.flockyou.privilege.PrivilegeMode
import com.flockyou.privilege.PrivilegeModeDetector
import com.flockyou.scanner.standard.StandardBluetoothScanner
import com.flockyou.scanner.standard.StandardCellularScanner
import com.flockyou.scanner.standard.StandardWifiScanner
import com.flockyou.scanner.system.SystemBluetoothScanner
import com.flockyou.scanner.system.SystemCellularScanner
import com.flockyou.scanner.system.SystemWifiScanner

/**
 * Factory for creating scanner instances based on the current privilege mode.
 *
 * This factory detects whether the app is running in standard (sideload) mode
 * or system/OEM privileged mode and creates appropriate scanner implementations.
 *
 * Usage:
 * ```kotlin
 * val factory = ScannerFactory(context)
 * val wifiScanner = factory.createWifiScanner()
 * val bleScanner = factory.createBluetoothScanner()
 * val cellularScanner = factory.createCellularScanner()
 * ```
 */
class ScannerFactory(private val context: Context) {

    companion object {
        private const val TAG = "ScannerFactory"

        // Singleton instance for convenience
        @Volatile
        private var instance: ScannerFactory? = null

        fun getInstance(context: Context): ScannerFactory {
            return instance ?: synchronized(this) {
                instance ?: ScannerFactory(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Current privilege mode detected at runtime.
     */
    val privilegeMode: PrivilegeMode by lazy {
        PrivilegeModeDetector.detect(context)
    }

    /**
     * Whether the app is running in privileged (system/OEM) mode.
     */
    val isPrivileged: Boolean
        get() = privilegeMode.isPrivileged

    /**
     * Create a WiFi scanner appropriate for the current privilege mode.
     *
     * - Standard mode: Subject to throttling
     * - System/OEM mode: Can disable throttling
     */
    fun createWifiScanner(): IWifiScanner {
        return when (privilegeMode) {
            is PrivilegeMode.Sideload -> {
                Log.d(TAG, "Creating Standard WiFi scanner")
                StandardWifiScanner(context)
            }
            is PrivilegeMode.System, is PrivilegeMode.OEM -> {
                Log.d(TAG, "Creating System WiFi scanner (privileged)")
                SystemWifiScanner(context)
            }
        }
    }

    /**
     * Create a Bluetooth LE scanner appropriate for the current privilege mode.
     *
     * - Standard mode: Subject to duty cycling
     * - System/OEM mode: Continuous scanning available
     */
    fun createBluetoothScanner(): IBluetoothScanner {
        return when (privilegeMode) {
            is PrivilegeMode.Sideload -> {
                Log.d(TAG, "Creating Standard Bluetooth scanner")
                StandardBluetoothScanner(context)
            }
            is PrivilegeMode.System, is PrivilegeMode.OEM -> {
                Log.d(TAG, "Creating System Bluetooth scanner (privileged)")
                SystemBluetoothScanner(context)
            }
        }
    }

    /**
     * Create a Cellular scanner appropriate for the current privilege mode.
     *
     * - Standard mode: Rate-limited, no IMEI/IMSI
     * - System/OEM mode: Real-time updates, IMEI/IMSI available
     */
    fun createCellularScanner(): ICellularScanner {
        return when (privilegeMode) {
            is PrivilegeMode.Sideload -> {
                Log.d(TAG, "Creating Standard Cellular scanner")
                StandardCellularScanner(context)
            }
            is PrivilegeMode.System, is PrivilegeMode.OEM -> {
                Log.d(TAG, "Creating System Cellular scanner (privileged)")
                SystemCellularScanner(context)
            }
        }
    }

    /**
     * Get the current scanner status summary.
     */
    fun getScannerStatus(
        wifiScanner: IWifiScanner?,
        bleScanner: IBluetoothScanner?,
        cellularScanner: ICellularScanner?
    ): ScannerStatus {
        return ScannerStatus(
            wifiActive = wifiScanner?.isActive?.value ?: false,
            wifiThrottled = !(wifiScanner?.canDisableThrottling() ?: false),
            bleActive = bleScanner?.isActive?.value ?: false,
            bleDutyCycled = !(bleScanner?.supportsContinuousScanning() ?: false),
            cellularActive = cellularScanner?.isActive?.value ?: false,
            cellularRateLimited = !(cellularScanner?.hasPrivilegedAccess() ?: false),
            privilegeMode = privilegeMode.toString()
        )
    }

    /**
     * Get a summary of available capabilities.
     */
    fun getCapabilities(): ScannerCapabilities {
        return ScannerCapabilities(
            canDisableWifiThrottling = privilegeMode.canDisableWifiThrottling,
            hasRealMacAccess = privilegeMode.hasRealMacAccess,
            hasContinuousBleScan = privilegeMode.hasContinuousBleScan,
            hasPrivilegedPhoneAccess = privilegeMode.hasPrivilegedPhoneAccess,
            canBePersistent = privilegeMode.canBePersistent,
            privilegeMode = privilegeMode
        )
    }
}

/**
 * Summary of scanner capabilities based on privilege mode.
 */
data class ScannerCapabilities(
    /** Whether WiFi scan throttling can be disabled */
    val canDisableWifiThrottling: Boolean,
    /** Whether real MAC addresses are available */
    val hasRealMacAccess: Boolean,
    /** Whether continuous BLE scanning is available */
    val hasContinuousBleScan: Boolean,
    /** Whether IMEI/IMSI access is available */
    val hasPrivilegedPhoneAccess: Boolean,
    /** Whether the process can be marked as persistent */
    val canBePersistent: Boolean,
    /** The current privilege mode */
    val privilegeMode: PrivilegeMode
) {
    /**
     * Get a human-readable capability summary.
     */
    fun toDisplayList(): List<Pair<String, String>> {
        return listOf(
            "WiFi Throttling" to if (canDisableWifiThrottling) "Can Disable" else "Enforced",
            "MAC Addresses" to if (hasRealMacAccess) "Real Hardware" else "May Be Masked",
            "BLE Scanning" to if (hasContinuousBleScan) "Continuous" else "Duty Cycled",
            "Phone Access" to if (hasPrivilegedPhoneAccess) "IMEI/IMSI Available" else "Limited",
            "Process Mode" to if (canBePersistent) "Can Be Persistent" else "Standard"
        )
    }

    /**
     * Get the mode description.
     */
    fun getModeDescription(): String {
        return when (privilegeMode) {
            is PrivilegeMode.Sideload -> "Standard (Sideload)"
            is PrivilegeMode.System -> "System Privileged"
            is PrivilegeMode.OEM -> {
                val oem = privilegeMode.oemName
                if (oem != null) "OEM ($oem)" else "OEM Embedded"
            }
        }
    }
}

/**
 * Provides all scanners as a single unit for convenience.
 */
class ScannerBundle(
    val wifiScanner: IWifiScanner,
    val bluetoothScanner: IBluetoothScanner,
    val cellularScanner: ICellularScanner,
    val factory: ScannerFactory
) {
    /**
     * Start all scanners.
     * @return map of scanner type to success status
     */
    fun startAll(): Map<String, Boolean> {
        return mapOf(
            "wifi" to wifiScanner.start(),
            "bluetooth" to bluetoothScanner.start(),
            "cellular" to cellularScanner.start()
        )
    }

    /**
     * Stop all scanners.
     */
    fun stopAll() {
        wifiScanner.stop()
        bluetoothScanner.stop()
        cellularScanner.stop()
    }

    /**
     * Get current status of all scanners.
     */
    fun getStatus(): ScannerStatus {
        return factory.getScannerStatus(wifiScanner, bluetoothScanner, cellularScanner)
    }

    /**
     * Get capabilities summary.
     */
    fun getCapabilities(): ScannerCapabilities {
        return factory.getCapabilities()
    }

    companion object {
        /**
         * Create a scanner bundle using the factory.
         */
        fun create(context: Context): ScannerBundle {
            val factory = ScannerFactory.getInstance(context)
            return ScannerBundle(
                wifiScanner = factory.createWifiScanner(),
                bluetoothScanner = factory.createBluetoothScanner(),
                cellularScanner = factory.createCellularScanner(),
                factory = factory
            )
        }
    }
}

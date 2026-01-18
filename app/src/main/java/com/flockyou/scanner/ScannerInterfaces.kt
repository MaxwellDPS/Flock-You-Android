package com.flockyou.scanner

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.net.wifi.ScanResult as WifiScanResult
import android.telephony.CellInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Scanner abstraction interfaces for dual-mode (Standard/OEM) operation.
 *
 * These interfaces abstract the scanning functionality to allow different
 * implementations for standard sideloaded apps vs system/OEM privileged apps.
 */

/**
 * Common scanner lifecycle interface.
 */
interface IScanner {
    /**
     * Whether this scanner is currently active.
     */
    val isActive: StateFlow<Boolean>

    /**
     * Last error message, if any.
     */
    val lastError: StateFlow<String?>

    /**
     * Start the scanner.
     * @return true if started successfully, false otherwise
     */
    fun start(): Boolean

    /**
     * Stop the scanner.
     */
    fun stop()

    /**
     * Check if this scanner requires runtime permissions.
     */
    fun requiresRuntimePermissions(): Boolean

    /**
     * Get list of required permissions for this scanner.
     */
    fun getRequiredPermissions(): List<String>
}

/**
 * WiFi scanner interface.
 *
 * Standard mode: Subject to scan throttling (4 scans / 2 mins foreground)
 * System/OEM mode: Can disable throttling for real-time scanning
 */
interface IWifiScanner : IScanner {
    /**
     * Flow of WiFi scan results.
     */
    val scanResults: Flow<List<WifiScanResult>>

    /**
     * Request a WiFi scan.
     * @return true if scan was initiated, false if throttled or failed
     */
    fun requestScan(): Boolean

    /**
     * Whether WiFi scan throttling can be disabled.
     */
    fun canDisableThrottling(): Boolean

    /**
     * Attempt to disable WiFi scan throttling.
     * Only works in System/OEM mode.
     * @return true if successfully disabled, false otherwise
     */
    fun disableThrottling(): Boolean

    /**
     * Get the real BSSID/MAC address if available.
     * Standard mode may return randomized addresses.
     */
    fun getRealBssid(scanResult: WifiScanResult): String
}

/**
 * Bluetooth LE scanner interface.
 *
 * Standard mode: Subject to duty cycling, SCAN_MODE_LOW_LATENCY soft-limited
 * System/OEM mode: Can use BLUETOOTH_PRIVILEGED for continuous scanning
 */
interface IBluetoothScanner : IScanner {
    /**
     * Flow of BLE scan results.
     */
    val scanResults: Flow<ScanResult>

    /**
     * Flow of batched scan results (for high-frequency scanning).
     */
    val batchedResults: Flow<List<ScanResult>>

    /**
     * Configure scan settings.
     */
    fun configureScan(config: BleScanConfig)

    /**
     * Whether continuous (non-duty-cycled) scanning is available.
     */
    fun supportsContinuousScanning(): Boolean

    /**
     * Get the real MAC address of a device.
     * Standard mode may return randomized addresses.
     */
    fun getRealMacAddress(device: BluetoothDevice): String

    /**
     * Get the real MAC address from a scan result.
     */
    fun getRealMacAddress(scanResult: ScanResult): String
}

/**
 * BLE scan configuration.
 */
data class BleScanConfig(
    /**
     * Scan mode: LOW_POWER, BALANCED, LOW_LATENCY
     */
    val scanMode: Int = android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY,

    /**
     * Callback type: ALL_MATCHES, FIRST_MATCH, MATCH_LOST
     */
    val callbackType: Int = android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES,

    /**
     * Match mode: AGGRESSIVE, STICKY
     */
    val matchMode: Int = android.bluetooth.le.ScanSettings.MATCH_MODE_AGGRESSIVE,

    /**
     * Number of matches: ONE, FEW, MAX
     */
    val numOfMatches: Int = android.bluetooth.le.ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT,

    /**
     * Report delay in milliseconds (0 for immediate).
     */
    val reportDelayMillis: Long = 0,

    /**
     * Scan duration in milliseconds (0 for indefinite).
     */
    val scanDurationMillis: Long = 25000,

    /**
     * Cooldown between scans in milliseconds.
     */
    val cooldownMillis: Long = 5000,

    /**
     * Use legacy scanning for older devices.
     */
    val useLegacyMode: Boolean = false,

    /**
     * PHY mask for extended scanning (BLE 5.0+).
     */
    val phyMask: Int = android.bluetooth.le.ScanSettings.PHY_LE_ALL_SUPPORTED
)

/**
 * Cellular scanner interface.
 *
 * Standard mode: Rate-limited updates (~10s), limited cell identity info
 * System/OEM mode: Real-time modem access, IMEI/IMSI visible
 */
interface ICellularScanner : IScanner {
    /**
     * Flow of cell info updates.
     */
    val cellInfo: Flow<List<CellInfo>>

    /**
     * Flow of cell info anomalies (potential IMSI catchers).
     */
    val anomalies: Flow<List<CellularAnomaly>>

    /**
     * Request immediate cell info update.
     * Standard mode: May be rate-limited
     * System/OEM mode: Real-time update
     */
    fun requestCellInfoUpdate()

    /**
     * Get the IMEI if available.
     * Only available in OEM mode with READ_PRIVILEGED_PHONE_STATE.
     */
    fun getImei(slotIndex: Int = 0): String?

    /**
     * Get the IMSI if available.
     * Only available in OEM mode with READ_PRIVILEGED_PHONE_STATE.
     */
    fun getImsi(): String?

    /**
     * Whether privileged phone state access is available.
     */
    fun hasPrivilegedAccess(): Boolean
}

/**
 * Cellular anomaly detected by the scanner.
 */
data class CellularAnomaly(
    val timestamp: Long = System.currentTimeMillis(),
    val type: CellularAnomalyType,
    val description: String,
    val cellId: String?,
    val lac: Int?,
    val mcc: Int?,
    val mnc: Int?,
    val signalStrength: Int?,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Types of cellular anomalies that may indicate surveillance.
 */
enum class CellularAnomalyType {
    /** Cell tower changed unexpectedly */
    UNEXPECTED_TOWER_CHANGE,
    /** Encryption downgrade detected (e.g., A5/1 to A5/0) */
    ENCRYPTION_DOWNGRADE,
    /** Cell broadcasting disabled */
    CELL_BROADCAST_DISABLED,
    /** Unusual Location Area Code */
    UNUSUAL_LAC,
    /** Silent SMS detected */
    SILENT_SMS,
    /** IMSI request detected */
    IMSI_CATCHER_SUSPECTED,
    /** Abnormally strong signal */
    SIGNAL_TOO_STRONG,
    /** Tower requesting older protocol */
    PROTOCOL_DOWNGRADE,
    /** Fake base station signature */
    FAKE_BASE_STATION,
    /** Unknown anomaly */
    UNKNOWN
}

/**
 * Combined scanner status for monitoring.
 */
data class ScannerStatus(
    val wifiActive: Boolean = false,
    val wifiThrottled: Boolean = true,
    val bleActive: Boolean = false,
    val bleDutyCycled: Boolean = true,
    val cellularActive: Boolean = false,
    val cellularRateLimited: Boolean = true,
    val privilegeMode: String = "Sideload",
    val lastWifiScan: Long? = null,
    val lastBleScan: Long? = null,
    val lastCellUpdate: Long? = null
)

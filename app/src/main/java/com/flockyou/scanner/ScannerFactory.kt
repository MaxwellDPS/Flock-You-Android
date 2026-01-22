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
import com.flockyou.testmode.TestModeConfig
import com.flockyou.testmode.scanner.MockBleScanner
import com.flockyou.testmode.scanner.MockCellularScanner
import com.flockyou.testmode.scanner.MockWifiScanner

/**
 * Factory for creating scanner instances based on the current privilege mode.
 *
 * This factory detects whether the app is running in standard (sideload) mode
 * or system/OEM privileged mode and creates appropriate scanner implementations.
 *
 * Test Mode Support:
 * When test mode is enabled, this factory can optionally return mock scanners
 * instead of real implementations. This allows for:
 * - Demonstrating app capabilities without hardware
 * - Testing detection algorithms with controlled data
 * - UI testing with predictable scan results
 *
 * Usage:
 * ```kotlin
 * val factory = ScannerFactory(context)
 * val wifiScanner = factory.createWifiScanner()
 * val bleScanner = factory.createBluetoothScanner()
 * val cellularScanner = factory.createCellularScanner()
 *
 * // With test mode:
 * factory.setTestMode(true, mockWifiScanner, mockBleScanner, mockCellularScanner)
 * val mockWifi = factory.createWifiScanner() // Returns mock scanner
 * ```
 */
class ScannerFactory(private val context: Context) {

    companion object {
        private const val TAG = "ScannerFactory"

        /**
         * Singleton instance using lazy delegate for thread-safe initialization.
         * Note: This is initialized on first access after app start.
         */
        @Volatile
        private var applicationContext: Context? = null

        private val lazyInstance = lazy {
            val ctx = applicationContext
                ?: throw IllegalStateException("ScannerFactory.getInstance() must be called with context first")
            ScannerFactory(ctx)
        }

        /**
         * Get or create the singleton ScannerFactory instance.
         * Uses lazy initialization for proper thread safety.
         */
        fun getInstance(context: Context): ScannerFactory {
            // Store application context on first call
            if (applicationContext == null) {
                synchronized(this) {
                    if (applicationContext == null) {
                        applicationContext = context.applicationContext
                    }
                }
            }
            return lazyInstance.value
        }
    }

    // ================================================================
    // Test Mode Support
    // ================================================================

    /**
     * Whether test mode is currently enabled.
     * When enabled, factory methods return mock scanners instead of real ones.
     */
    @Volatile
    private var testModeEnabled: Boolean = false

    /**
     * Current test mode configuration (if any).
     */
    @Volatile
    private var testModeConfig: TestModeConfig? = null

    /**
     * Mock WiFi scanner instance for test mode.
     */
    @Volatile
    private var mockWifiScanner: MockWifiScanner? = null

    /**
     * Mock BLE scanner instance for test mode.
     */
    @Volatile
    private var mockBleScanner: MockBleScanner? = null

    /**
     * Mock Cellular scanner instance for test mode.
     */
    @Volatile
    private var mockCellularScanner: MockCellularScanner? = null

    /**
     * Check if test mode is currently enabled.
     */
    val isTestModeEnabled: Boolean
        get() = testModeEnabled

    /**
     * Enable or disable test mode.
     *
     * When test mode is enabled, the factory will return mock scanners
     * instead of real implementations. This allows testing without
     * hardware dependencies or permissions.
     *
     * @param enabled Whether to enable test mode
     * @param wifiScanner Optional mock WiFi scanner to use
     * @param bleScanner Optional mock BLE scanner to use
     * @param cellularScanner Optional mock Cellular scanner to use
     * @param config Optional test mode configuration
     */
    fun setTestMode(
        enabled: Boolean,
        wifiScanner: MockWifiScanner? = null,
        bleScanner: MockBleScanner? = null,
        cellularScanner: MockCellularScanner? = null,
        config: TestModeConfig? = null
    ) {
        synchronized(this) {
            testModeEnabled = enabled
            testModeConfig = config
            mockWifiScanner = wifiScanner
            mockBleScanner = bleScanner
            mockCellularScanner = cellularScanner
            Log.d(TAG, "Test mode ${if (enabled) "enabled" else "disabled"}")
        }
    }

    /**
     * Update the test mode configuration.
     *
     * @param config The new test mode configuration
     */
    fun updateTestModeConfig(config: TestModeConfig) {
        synchronized(this) {
            testModeConfig = config
            testModeEnabled = config.enabled
            Log.d(TAG, "Test mode config updated: enabled=${config.enabled}")
        }
    }

    /**
     * Get the current test mode configuration.
     */
    fun getTestModeConfig(): TestModeConfig? = testModeConfig

    // ================================================================
    // Privilege Mode Detection
    // ================================================================

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

    // ================================================================
    // Scanner Creation Methods
    // ================================================================

    /**
     * Create a WiFi scanner appropriate for the current mode.
     *
     * Returns:
     * - Test mode: MockWifiScanner (if provided)
     * - Standard mode: Subject to throttling
     * - System/OEM mode: Can disable throttling
     */
    fun createWifiScanner(): IWifiScanner {
        // Check for test mode first
        if (testModeEnabled) {
            mockWifiScanner?.let {
                Log.d(TAG, "Creating Mock WiFi scanner (test mode)")
                return it
            }
            Log.w(TAG, "Test mode enabled but no mock WiFi scanner provided, falling back to real scanner")
        }

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
     * Create a Bluetooth LE scanner appropriate for the current mode.
     *
     * Returns:
     * - Test mode: MockBleScanner (if provided)
     * - Standard mode: Subject to duty cycling
     * - System/OEM mode: Continuous scanning available
     */
    fun createBluetoothScanner(): IBluetoothScanner {
        // Check for test mode first
        if (testModeEnabled) {
            mockBleScanner?.let {
                Log.d(TAG, "Creating Mock Bluetooth scanner (test mode)")
                return it
            }
            Log.w(TAG, "Test mode enabled but no mock BLE scanner provided, falling back to real scanner")
        }

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
     * Create a Cellular scanner appropriate for the current mode.
     *
     * Returns:
     * - Test mode: MockCellularScanner (if provided)
     * - Standard mode: Rate-limited, no IMEI/IMSI
     * - System/OEM mode: Real-time updates, IMEI/IMSI available
     */
    fun createCellularScanner(): ICellularScanner {
        // Check for test mode first
        if (testModeEnabled) {
            mockCellularScanner?.let {
                Log.d(TAG, "Creating Mock Cellular scanner (test mode)")
                return it
            }
            Log.w(TAG, "Test mode enabled but no mock Cellular scanner provided, falling back to real scanner")
        }

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
            privilegeMode = privilegeMode,
            isTestMode = testModeEnabled,
            testModeScenarioId = testModeConfig?.activeScenarioId
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
    val privilegeMode: PrivilegeMode,
    /** Whether test mode is currently active */
    val isTestMode: Boolean = false,
    /** The active test scenario ID (if in test mode) */
    val testModeScenarioId: String? = null
) {
    /**
     * Get a human-readable capability summary.
     */
    fun toDisplayList(): List<Pair<String, String>> {
        val baseList = listOf(
            "WiFi Throttling" to if (canDisableWifiThrottling) "Can Disable" else "Enforced",
            "MAC Addresses" to if (hasRealMacAccess) "Real Hardware" else "May Be Masked",
            "BLE Scanning" to if (hasContinuousBleScan) "Continuous" else "Duty Cycled",
            "Phone Access" to if (hasPrivilegedPhoneAccess) "IMEI/IMSI Available" else "Limited",
            "Process Mode" to if (canBePersistent) "Can Be Persistent" else "Standard"
        )

        return if (isTestMode) {
            baseList + listOf(
                "Test Mode" to "Active",
                "Scenario" to (testModeScenarioId ?: "None")
            )
        } else {
            baseList
        }
    }

    /**
     * Get the mode description.
     */
    fun getModeDescription(): String {
        if (isTestMode) {
            return "Test Mode" + if (testModeScenarioId != null) " ($testModeScenarioId)" else ""
        }

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

    /**
     * Check if this bundle is using mock scanners (test mode).
     */
    fun isTestMode(): Boolean {
        return factory.isTestModeEnabled
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

        /**
         * Create a scanner bundle for test mode.
         *
         * This method sets up the factory for test mode and creates
         * a bundle with the provided mock scanners.
         *
         * @param context Android context
         * @param mockWifiScanner Mock WiFi scanner to use
         * @param mockBleScanner Mock BLE scanner to use
         * @param mockCellularScanner Mock Cellular scanner to use
         * @param config Optional test mode configuration
         * @return ScannerBundle configured for test mode
         */
        fun createForTestMode(
            context: Context,
            mockWifiScanner: MockWifiScanner,
            mockBleScanner: MockBleScanner,
            mockCellularScanner: MockCellularScanner,
            config: TestModeConfig? = null
        ): ScannerBundle {
            val factory = ScannerFactory.getInstance(context)
            factory.setTestMode(
                enabled = true,
                wifiScanner = mockWifiScanner,
                bleScanner = mockBleScanner,
                cellularScanner = mockCellularScanner,
                config = config
            )
            return ScannerBundle(
                wifiScanner = factory.createWifiScanner(),
                bluetoothScanner = factory.createBluetoothScanner(),
                cellularScanner = factory.createCellularScanner(),
                factory = factory
            )
        }
    }
}

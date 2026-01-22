package com.flockyou.testmode.scanner

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.util.Log
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
import kotlin.random.Random

/**
 * Mock BLE device data for test mode.
 *
 * @property name Device name (can be null for unnamed devices)
 * @property address MAC address in format "XX:XX:XX:XX:XX:XX"
 * @property rssi Signal strength in dBm (typically -30 to -100)
 * @property txPower Advertised TX power level
 * @property isConnectable Whether the device is connectable
 * @property deviceType Device type (1=Classic, 2=LE, 3=Dual)
 * @property manufacturerData Raw manufacturer-specific data (key = company ID)
 * @property serviceUuids List of advertised service UUIDs
 * @property serviceData Map of service UUID to service data
 */
data class MockBleDevice(
    val name: String? = null,
    val address: String,
    val rssi: Int = -65,
    val txPower: Int = -59,
    val isConnectable: Boolean = true,
    val deviceType: Int = BluetoothDevice.DEVICE_TYPE_LE,
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap()
)

/**
 * Mock BLE scan result that can be used in test mode.
 *
 * Since android.bluetooth.le.ScanResult cannot be easily instantiated in test code,
 * this wrapper provides a testable representation of BLE scan results.
 *
 * @property device The mock BLE device information
 * @property rssi Signal strength at the time of scan
 * @property timestampNanos Timestamp in nanoseconds since boot
 * @property isConnectable Whether the device is connectable
 * @property txPower Advertised TX power level
 * @property primaryPhy Primary PHY used (1=LE_1M, 2=LE_2M, 3=LE_CODED)
 * @property secondaryPhy Secondary PHY used
 * @property advertisingSid Advertising set ID
 * @property periodicAdvertisingInterval Periodic advertising interval
 */
data class MockBleScanResult(
    val device: MockBleDevice,
    val rssi: Int,
    val timestampNanos: Long = System.nanoTime(),
    val isConnectable: Boolean = true,
    val txPower: Int = Int.MIN_VALUE,
    val primaryPhy: Int = 1,
    val secondaryPhy: Int = 0,
    val advertisingSid: Int = 255,
    val periodicAdvertisingInterval: Int = 0
)

/**
 * Mock Bluetooth LE scanner for test mode.
 *
 * This implementation of IBluetoothScanner provides simulated BLE scan results
 * for testing and demonstration purposes without requiring actual Bluetooth hardware
 * or permissions.
 *
 * Features:
 * - Configurable mock device data
 * - Optional realistic RSSI variations
 * - Configurable emission intervals
 * - Exposes both standard ScanResult flow (with limitations) and mock result flow
 * - Full IBluetoothScanner interface implementation
 *
 * Usage:
 * ```kotlin
 * val mockScanner = MockBleScanner()
 * mockScanner.setMockData(listOf(
 *     MockBleDevice(name = "AirTag", address = "AA:BB:CC:DD:EE:FF", rssi = -55),
 *     MockBleDevice(name = "Tile", address = "11:22:33:44:55:66", rssi = -70)
 * ))
 * mockScanner.start()
 *
 * // For test mode consumers, use mockResults flow:
 * mockScanner.mockResults.collect { result ->
 *     // Handle mock scan result
 * }
 * ```
 */
class MockBleScanner : IBluetoothScanner {

    companion object {
        private const val TAG = "MockBleScanner"
        private const val DEFAULT_EMISSION_INTERVAL_MS = 1000L
        private const val RSSI_VARIATION_RANGE = 5 // +/- 5 dBm
    }

    // Coroutine management
    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.Default + supervisorJob)
    private var emitJob: Job? = null

    // State flows
    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    // Standard interface flows - Note: Creating real ScanResult objects is limited
    // These flows emit what we can create, but mockResults is preferred for test mode
    private val _scanResults = MutableSharedFlow<ScanResult>(replay = 0, extraBufferCapacity = 100)
    override val scanResults: Flow<ScanResult> = _scanResults.asSharedFlow()

    private val _batchedResults = MutableSharedFlow<List<ScanResult>>(replay = 0, extraBufferCapacity = 10)
    override val batchedResults: Flow<List<ScanResult>> = _batchedResults.asSharedFlow()

    // Mock-specific flows for test mode consumers
    private val _mockResults = MutableSharedFlow<MockBleScanResult>(replay = 1, extraBufferCapacity = 100)

    /**
     * Flow of mock BLE scan results.
     *
     * This is the preferred way to consume scan results in test mode,
     * as it provides full mock data without Android framework limitations.
     */
    val mockResults: Flow<MockBleScanResult> = _mockResults.asSharedFlow()

    private val _mockBatchedResults = MutableSharedFlow<List<MockBleScanResult>>(replay = 0, extraBufferCapacity = 10)

    /**
     * Flow of batched mock BLE scan results.
     */
    val mockBatchedResults: Flow<List<MockBleScanResult>> = _mockBatchedResults.asSharedFlow()

    // Mock data configuration
    private var mockDevices: List<MockBleDevice> = emptyList()
    private var emissionIntervalMs: Long = DEFAULT_EMISSION_INTERVAL_MS
    private var enableRssiVariation: Boolean = true
    private var currentConfig = BleScanConfig()

    /**
     * Set the mock BLE devices to emit during scanning.
     *
     * @param devices List of mock BLE devices to simulate
     */
    fun setMockData(devices: List<MockBleDevice>) {
        mockDevices = devices
        Log.d(TAG, "Mock data set: ${devices.size} devices")

        // If already scanning, emit immediately with new data
        if (_isActive.value) {
            scope.launch {
                emitMockResults()
            }
        }
    }

    /**
     * Set the interval between automatic emissions.
     *
     * @param intervalMs Interval in milliseconds between scan result emissions
     */
    fun setEmissionInterval(intervalMs: Long) {
        emissionIntervalMs = intervalMs.coerceAtLeast(50L)
        Log.d(TAG, "Emission interval set: ${emissionIntervalMs}ms")

        // Restart emission job with new interval if active
        if (_isActive.value) {
            startEmissionLoop()
        }
    }

    /**
     * Enable or disable realistic RSSI variations.
     *
     * When enabled, RSSI values will vary by +/- 5 dBm between emissions
     * to simulate real-world signal fluctuations.
     *
     * @param enabled Whether to enable RSSI variation
     */
    fun setRssiVariationEnabled(enabled: Boolean) {
        enableRssiVariation = enabled
        Log.d(TAG, "RSSI variation enabled: $enabled")
    }

    /**
     * Get the current list of mock devices.
     */
    fun getMockData(): List<MockBleDevice> = mockDevices.toList()

    override fun start(): Boolean {
        if (_isActive.value) {
            Log.d(TAG, "Mock scanner already active")
            return true
        }

        // Recreate scope if it was cancelled
        if (!supervisorJob.isActive) {
            supervisorJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.Default + supervisorJob)
        }

        _isActive.value = true
        _lastError.value = null
        startEmissionLoop()
        Log.d(TAG, "Mock BLE scanner started")
        return true
    }

    override fun stop() {
        emitJob?.cancel()
        emitJob = null
        _isActive.value = false
        supervisorJob.cancel()
        Log.d(TAG, "Mock BLE scanner stopped")
    }

    override fun requiresRuntimePermissions(): Boolean = false

    override fun getRequiredPermissions(): List<String> = emptyList()

    override fun configureScan(config: BleScanConfig) {
        val wasActive = _isActive.value
        if (wasActive) {
            emitJob?.cancel()
        }
        currentConfig = config
        Log.d(TAG, "Scan configured: scanMode=${config.scanMode}, duration=${config.scanDurationMillis}ms")
        if (wasActive) {
            startEmissionLoop()
        }
    }

    override fun supportsContinuousScanning(): Boolean = true

    override fun getRealMacAddress(device: BluetoothDevice): String {
        return device.address
    }

    override fun getRealMacAddress(scanResult: ScanResult): String {
        return scanResult.device.address
    }

    /**
     * Get the "real" MAC address from a mock scan result.
     */
    fun getRealMacAddress(mockResult: MockBleScanResult): String {
        return mockResult.device.address
    }

    /**
     * Get the current scan configuration.
     */
    fun getCurrentConfig(): BleScanConfig = currentConfig

    private fun startEmissionLoop() {
        emitJob?.cancel()
        emitJob = scope.launch {
            // Emit immediately on start
            emitMockResults()

            // Then emit at regular intervals
            while (_isActive.value) {
                delay(emissionIntervalMs)
                if (_isActive.value) {
                    emitMockResults()
                }
            }
        }
    }

    private suspend fun emitMockResults() {
        if (mockDevices.isEmpty()) {
            Log.d(TAG, "No mock devices configured")
            return
        }

        val mockResultsList = mutableListOf<MockBleScanResult>()
        val timestamp = System.nanoTime()

        for (device in mockDevices) {
            // Apply RSSI variation if enabled
            val rssiWithVariation = if (enableRssiVariation) {
                val variation = Random.nextInt(-RSSI_VARIATION_RANGE, RSSI_VARIATION_RANGE + 1)
                (device.rssi + variation).coerceIn(-100, -10)
            } else {
                device.rssi
            }

            val mockResult = MockBleScanResult(
                device = device,
                rssi = rssiWithVariation,
                timestampNanos = timestamp,
                isConnectable = device.isConnectable,
                txPower = device.txPower
            )

            mockResultsList.add(mockResult)

            // Emit individual mock result
            _mockResults.emit(mockResult)

            // Try to create and emit a real ScanResult if possible
            try {
                val realScanResult = createMockScanResult(device, rssiWithVariation, timestamp)
                if (realScanResult != null) {
                    _scanResults.emit(realScanResult)
                }
            } catch (e: Exception) {
                // ScanResult creation may fail in non-Android environments
                Log.d(TAG, "Could not create real ScanResult: ${e.message}")
            }
        }

        // Emit batched results
        _mockBatchedResults.emit(mockResultsList)
        Log.d(TAG, "Emitted ${mockResultsList.size} mock BLE scan results")
    }

    /**
     * Attempt to create a real ScanResult object.
     *
     * Note: This is limited by Android framework constraints and may not work
     * in all test environments. Use mockResults flow for reliable test mode operation.
     */
    private fun createMockScanResult(
        device: MockBleDevice,
        rssi: Int,
        timestampNanos: Long
    ): ScanResult? {
        return try {
            // ScanResult construction requires actual Android runtime
            // This will only work when running on Android, not in unit tests
            val constructor = ScanResult::class.java.getDeclaredConstructor(
                BluetoothDevice::class.java,
                Int::class.java, // eventType
                Int::class.java, // primaryPhy
                Int::class.java, // secondaryPhy
                Int::class.java, // advertisingSid
                Int::class.java, // txPower
                Int::class.java, // rssi
                Int::class.java, // periodicAdvertisingInterval
                android.bluetooth.le.ScanRecord::class.java,
                Long::class.java // timestampNanos
            )
            constructor.isAccessible = true

            // We can't easily create a BluetoothDevice or ScanRecord without
            // the Android framework, so this approach has limitations
            null
        } catch (e: Exception) {
            Log.d(TAG, "Cannot create ScanResult via reflection: ${e.message}")
            null
        }
    }

    /**
     * Manually trigger a scan result emission.
     * Useful for testing specific scenarios.
     *
     * @param result The mock scan result to emit
     */
    suspend fun emitResult(result: MockBleScanResult) {
        _mockResults.emit(result)
    }

    /**
     * Manually trigger multiple scan result emissions.
     * Useful for testing batched scenarios.
     *
     * @param results The mock scan results to emit
     */
    suspend fun emitResults(results: List<MockBleScanResult>) {
        for (result in results) {
            _mockResults.emit(result)
        }
        _mockBatchedResults.emit(results)
    }
}

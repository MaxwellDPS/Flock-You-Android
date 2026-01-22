package com.flockyou.testmode.scanner

import android.net.wifi.ScanResult as WifiScanResult
import android.util.Log
import com.flockyou.scanner.IWifiScanner
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
 * Mock WiFi network data for test mode.
 *
 * @property ssid The network SSID (name)
 * @property bssid The MAC address of the access point
 * @property rssi Signal strength in dBm (typically -30 to -90)
 * @property frequency Channel frequency in MHz (e.g., 2412 for channel 1)
 * @property capabilities Security capabilities string (e.g., "[WPA2-PSK-CCMP][ESS]")
 * @property channelWidth Channel width (0=20MHz, 1=40MHz, 2=80MHz, 3=160MHz)
 * @property centerFreq0 Center frequency for VHT operation
 * @property centerFreq1 Secondary center frequency for 80+80 MHz
 * @property timestamp Timestamp in microseconds since boot
 */
data class MockWifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int = -65,
    val frequency: Int = 2437,
    val capabilities: String = "[WPA2-PSK-CCMP][ESS]",
    val channelWidth: Int = 0,
    val centerFreq0: Int = 0,
    val centerFreq1: Int = 0,
    val timestamp: Long = System.currentTimeMillis() * 1000
)

/**
 * Mock WiFi scanner for test mode.
 *
 * This implementation of IWifiScanner provides simulated WiFi scan results
 * for testing and demonstration purposes without requiring actual WiFi hardware
 * or permissions.
 *
 * Features:
 * - Configurable mock network data
 * - Optional realistic signal strength variations
 * - Configurable emission intervals
 * - Full IWifiScanner interface implementation
 *
 * Usage:
 * ```kotlin
 * val mockScanner = MockWifiScanner()
 * mockScanner.setMockData(listOf(
 *     MockWifiNetwork("TestNetwork", "00:11:22:33:44:55", rssi = -60),
 *     MockWifiNetwork("GuestNetwork", "00:11:22:33:44:56", rssi = -75)
 * ))
 * mockScanner.start()
 * mockScanner.scanResults.collect { results ->
 *     // Handle mock scan results
 * }
 * ```
 */
class MockWifiScanner : IWifiScanner {

    companion object {
        private const val TAG = "MockWifiScanner"
        private const val DEFAULT_EMISSION_INTERVAL_MS = 3000L
        private const val SIGNAL_VARIATION_RANGE = 5 // +/- 5 dBm
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

    private val _scanResults = MutableSharedFlow<List<WifiScanResult>>(replay = 1, extraBufferCapacity = 10)
    override val scanResults: Flow<List<WifiScanResult>> = _scanResults.asSharedFlow()

    // Mock data configuration
    private var mockNetworks: List<MockWifiNetwork> = emptyList()
    private var emissionIntervalMs: Long = DEFAULT_EMISSION_INTERVAL_MS
    private var enableSignalVariation: Boolean = true
    private var throttlingDisabled: Boolean = false

    /**
     * Set the mock WiFi networks to emit during scanning.
     *
     * @param networks List of mock WiFi networks to simulate
     */
    fun setMockData(networks: List<MockWifiNetwork>) {
        mockNetworks = networks
        Log.d(TAG, "Mock data set: ${networks.size} networks")

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
        emissionIntervalMs = intervalMs.coerceAtLeast(100L)
        Log.d(TAG, "Emission interval set: ${emissionIntervalMs}ms")

        // Restart emission job with new interval if active
        if (_isActive.value) {
            startEmissionLoop()
        }
    }

    /**
     * Enable or disable realistic signal strength variations.
     *
     * When enabled, RSSI values will vary by +/- 5 dBm between emissions
     * to simulate real-world signal fluctuations.
     *
     * @param enabled Whether to enable signal variation
     */
    fun setSignalVariationEnabled(enabled: Boolean) {
        enableSignalVariation = enabled
        Log.d(TAG, "Signal variation enabled: $enabled")
    }

    /**
     * Get the current list of mock networks.
     */
    fun getMockData(): List<MockWifiNetwork> = mockNetworks.toList()

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
        Log.d(TAG, "Mock WiFi scanner started")
        return true
    }

    override fun stop() {
        emitJob?.cancel()
        emitJob = null
        _isActive.value = false
        supervisorJob.cancel()
        Log.d(TAG, "Mock WiFi scanner stopped")
    }

    override fun requiresRuntimePermissions(): Boolean = false

    override fun getRequiredPermissions(): List<String> = emptyList()

    override fun requestScan(): Boolean {
        if (!_isActive.value) {
            Log.w(TAG, "Scanner not active, starting first")
            if (!start()) {
                return false
            }
        }

        scope.launch {
            emitMockResults()
        }
        return true
    }

    override fun canDisableThrottling(): Boolean = true

    override fun disableThrottling(): Boolean {
        throttlingDisabled = true
        Log.d(TAG, "Mock throttling disabled")
        return true
    }

    override fun getRealBssid(scanResult: WifiScanResult): String {
        return scanResult.BSSID
    }

    /**
     * Check if throttling has been "disabled" in mock mode.
     */
    fun isThrottlingDisabled(): Boolean = throttlingDisabled

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
        if (mockNetworks.isEmpty()) {
            Log.d(TAG, "No mock networks configured, emitting empty list")
            _scanResults.emit(emptyList())
            return
        }

        val results = mockNetworks.map { network ->
            createMockScanResult(network)
        }

        _scanResults.emit(results)
        Log.d(TAG, "Emitted ${results.size} mock WiFi scan results")
    }

    /**
     * Create a mock WifiScanResult from MockWifiNetwork data.
     *
     * Note: Since android.net.wifi.ScanResult cannot be directly instantiated
     * in test code without Android framework, this uses reflection to create
     * and populate the object. In a real test environment, you may need to
     * use a wrapper class or Mockito/MockK instead.
     */
    private fun createMockScanResult(network: MockWifiNetwork): WifiScanResult {
        val result = WifiScanResult()

        // Apply signal variation if enabled
        val rssiWithVariation = if (enableSignalVariation) {
            val variation = Random.nextInt(-SIGNAL_VARIATION_RANGE, SIGNAL_VARIATION_RANGE + 1)
            (network.rssi + variation).coerceIn(-100, -10)
        } else {
            network.rssi
        }

        // Set fields using reflection since ScanResult fields are public but
        // may have restricted access in some contexts
        try {
            result.SSID = network.ssid
            result.BSSID = network.bssid
            result.level = rssiWithVariation
            result.frequency = network.frequency
            result.capabilities = network.capabilities
            result.channelWidth = network.channelWidth
            result.centerFreq0 = network.centerFreq0
            result.centerFreq1 = network.centerFreq1
            result.timestamp = if (network.timestamp > 0) {
                network.timestamp
            } else {
                System.currentTimeMillis() * 1000 // Convert to microseconds
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting ScanResult fields: ${e.message}")
        }

        return result
    }
}

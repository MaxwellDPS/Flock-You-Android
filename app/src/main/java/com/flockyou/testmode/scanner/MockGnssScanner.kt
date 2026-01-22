package com.flockyou.testmode.scanner

import android.util.Log
import com.flockyou.testmode.MockGnssState
import com.flockyou.testmode.MockSatellite
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * GNSS anomaly types detected by the mock scanner.
 */
enum class GnssAnomalyType {
    /** GPS signals being jammed */
    JAMMING,
    /** GPS signals being spoofed */
    SPOOFING_DETECTED,
    /** All satellites showing uniform signal strength */
    UNIFORM_CN0,
    /** GPS affected but GLONASS normal */
    CONSTELLATION_MISMATCH,
    /** Sudden position jump */
    POSITION_JUMP,
    /** Clock drift detected */
    CLOCK_DRIFT,
    /** Loss of navigation fix */
    FIX_LOST
}

/**
 * A detected GNSS anomaly.
 */
data class GnssAnomaly(
    val timestamp: Long = System.currentTimeMillis(),
    val type: GnssAnomalyType,
    val description: String,
    val cn0Average: Double?,
    val satelliteCount: Int,
    val hdop: Double?,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * GNSS status information.
 */
data class GnssStatus(
    val satelliteCount: Int,
    val usedInFix: Int,
    val cn0Average: Double,
    val hdop: Double,
    val isJammed: Boolean,
    val isSpoofed: Boolean
)

/**
 * Mock GNSS scanner for test mode.
 *
 * This scanner simulates GNSS satellite data and anomalies for testing
 * GPS spoofing/jamming detection scenarios.
 */
class MockGnssScanner {

    companion object {
        private const val TAG = "MockGnssScanner"
        private const val DEFAULT_EMISSION_INTERVAL_MS = 3000L
        private const val CN0_UNIFORMITY_THRESHOLD = 3.0  // dB-Hz
    }

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var emissionJob: Job? = null

    // Scanner state
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Mock data flows
    private val _measurements = MutableSharedFlow<List<MockSatellite>>(replay = 1, extraBufferCapacity = 10)
    val measurements: Flow<List<MockSatellite>> = _measurements.asSharedFlow()

    private val _status = MutableSharedFlow<GnssStatus>(replay = 1, extraBufferCapacity = 10)
    val status: Flow<GnssStatus> = _status.asSharedFlow()

    private val _anomalies = MutableSharedFlow<List<GnssAnomaly>>(replay = 1, extraBufferCapacity = 10)
    val anomalies: Flow<List<GnssAnomaly>> = _anomalies.asSharedFlow()

    // Mock state
    private var mockState: MockGnssState? = null
    private val detectedAnomalies = mutableListOf<GnssAnomaly>()
    private var emissionIntervalMs: Long = DEFAULT_EMISSION_INTERVAL_MS

    /**
     * Set the mock GNSS state to simulate.
     */
    fun setMockData(state: MockGnssState) {
        Log.d(TAG, "Setting mock GNSS state: ${state.satellites.size} satellites, jammed=${state.isJammed}, spoofed=${state.isSpoofed}")
        mockState = state

        // If scanner is active, immediately emit the new state
        if (_isActive.value) {
            scope.launch {
                emitMockData()
            }
        }
    }

    /**
     * Configure the emission interval for mock data.
     */
    fun setEmissionInterval(intervalMs: Long) {
        emissionIntervalMs = intervalMs.coerceIn(1000L, 30000L)
    }

    /**
     * Simulate jamming attack.
     */
    fun simulateJamming() {
        mockState = MockGnssState(
            satellites = listOf(
                MockSatellite(1, "GPS", 15.0, 45f, 90f),
                MockSatellite(3, "GPS", 12.0, 30f, 180f)
            ),
            cn0Average = 13.5,
            hdop = 15.0,
            isJammed = true,
            isSpoofed = false
        )
        if (_isActive.value) {
            scope.launch { emitMockData() }
        }
    }

    /**
     * Simulate spoofing attack.
     */
    fun simulateSpoofing() {
        mockState = MockGnssState(
            satellites = listOf(
                MockSatellite(1, "GPS", 48.0, 45f, 90f),
                MockSatellite(3, "GPS", 47.5, 45f, 180f),
                MockSatellite(6, "GPS", 48.2, 45f, 270f),
                MockSatellite(9, "GPS", 47.8, 45f, 360f)
            ),
            cn0Average = 47.9,
            hdop = 0.8,
            isJammed = false,
            isSpoofed = true
        )
        if (_isActive.value) {
            scope.launch { emitMockData() }
        }
    }

    /**
     * Clear all detected anomalies.
     */
    fun clearAnomalies() {
        detectedAnomalies.clear()
        scope.launch {
            _anomalies.emit(emptyList())
        }
    }

    fun start(): Boolean {
        if (_isActive.value) {
            Log.d(TAG, "Mock scanner already active")
            return true
        }

        Log.d(TAG, "Starting mock GNSS scanner")

        // Recreate scope if needed
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }

        _isActive.value = true
        _lastError.value = null

        // Start periodic emission
        emissionJob = scope.launch {
            emitMockData()
            while (isActive) {
                delay(emissionIntervalMs)
                if (_isActive.value) {
                    emitMockData()
                }
            }
        }

        return true
    }

    fun stop() {
        Log.d(TAG, "Stopping mock GNSS scanner")
        emissionJob?.cancel()
        emissionJob = null
        _isActive.value = false
    }

    private suspend fun emitMockData() {
        val state = mockState ?: return

        // Emit satellite measurements
        _measurements.emit(state.satellites)

        // Calculate and emit status
        val usedInFix = state.satellites.count { it.hasGoodSignal() }
        val gnssStatus = GnssStatus(
            satelliteCount = state.satellites.size,
            usedInFix = usedInFix,
            cn0Average = state.cn0Average,
            hdop = state.hdop,
            isJammed = state.isJammed,
            isSpoofed = state.isSpoofed
        )
        _status.emit(gnssStatus)

        // Analyze for anomalies
        analyzeForAnomalies(state)

        // Emit anomalies
        _anomalies.emit(detectedAnomalies.toList())
    }

    private fun analyzeForAnomalies(state: MockGnssState) {
        // Check for jamming
        if (state.isJammed || state.cn0Average < 20.0) {
            addAnomaly(GnssAnomaly(
                type = GnssAnomalyType.JAMMING,
                description = "GPS signal degradation detected - possible jamming (CN0: ${state.cn0Average} dB-Hz)",
                cn0Average = state.cn0Average,
                satelliteCount = state.satellites.size,
                hdop = state.hdop,
                metadata = mapOf("mock" to "true")
            ))
        }

        // Check for spoofing (uniform CN0 values)
        if (state.isSpoofed || isUniformCn0(state.satellites)) {
            addAnomaly(GnssAnomaly(
                type = GnssAnomalyType.SPOOFING_DETECTED,
                description = "Uniform satellite signal levels detected - possible spoofing",
                cn0Average = state.cn0Average,
                satelliteCount = state.satellites.size,
                hdop = state.hdop,
                metadata = mapOf("mock" to "true", "cn0_variance" to "${calculateCn0Variance(state.satellites)}")
            ))
        }

        // Check for HDOP anomaly (too good = suspicious)
        if (state.hdop < 0.5 && state.satellites.isNotEmpty()) {
            addAnomaly(GnssAnomaly(
                type = GnssAnomalyType.UNIFORM_CN0,
                description = "HDOP unusually low (${state.hdop}) - possible spoofing",
                cn0Average = state.cn0Average,
                satelliteCount = state.satellites.size,
                hdop = state.hdop,
                metadata = mapOf("mock" to "true")
            ))
        }
    }

    private fun isUniformCn0(satellites: List<MockSatellite>): Boolean {
        if (satellites.size < 3) return false
        val cn0Values = satellites.map { it.cn0DbHz }
        val variance = calculateCn0Variance(satellites)
        return variance < CN0_UNIFORMITY_THRESHOLD
    }

    private fun calculateCn0Variance(satellites: List<MockSatellite>): Double {
        if (satellites.isEmpty()) return 0.0
        val cn0Values = satellites.map { it.cn0DbHz }
        val mean = cn0Values.average()
        return cn0Values.map { (it - mean) * (it - mean) }.average()
    }

    private fun addAnomaly(anomaly: GnssAnomaly) {
        val recentSameType = detectedAnomalies.any {
            it.type == anomaly.type &&
                    (System.currentTimeMillis() - it.timestamp) < 30000
        }

        if (!recentSameType) {
            detectedAnomalies.add(anomaly)
            while (detectedAnomalies.size > 100) {
                detectedAnomalies.removeAt(0)
            }
            Log.d(TAG, "Added GNSS anomaly: ${anomaly.type} - ${anomaly.description}")
        }
    }
}

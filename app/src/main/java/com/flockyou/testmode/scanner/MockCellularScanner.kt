package com.flockyou.testmode.scanner

import android.telephony.CellInfo
import android.util.Log
import com.flockyou.scanner.CellularAnomaly
import com.flockyou.scanner.CellularAnomalyType
import com.flockyou.scanner.ICellularScanner
import com.flockyou.testmode.MockCellularState
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
 * Mock implementation of ICellularScanner for test mode.
 *
 * This scanner simulates cellular network data and anomalies for testing
 * IMSI catcher detection, protocol downgrade attacks, and other cellular
 * surveillance scenarios without requiring actual network conditions.
 */
class MockCellularScanner : ICellularScanner {

    companion object {
        private const val TAG = "MockCellularScanner"
        private const val DEFAULT_EMISSION_INTERVAL_MS = 3000L
    }

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var emissionJob: Job? = null

    // Scanner state
    private val _isActive = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Note: We emit mock cell data as CellularAnomaly since we can't create real CellInfo objects
    private val _cellInfo = MutableSharedFlow<List<CellInfo>>(replay = 1, extraBufferCapacity = 10)
    override val cellInfo: Flow<List<CellInfo>> = _cellInfo.asSharedFlow()

    private val _anomalies = MutableSharedFlow<List<CellularAnomaly>>(replay = 1, extraBufferCapacity = 10)
    override val anomalies: Flow<List<CellularAnomaly>> = _anomalies.asSharedFlow()

    // Mock state
    private var mockState: MockCellularState? = null
    private val detectedAnomalies = mutableListOf<CellularAnomaly>()
    private var emissionIntervalMs: Long = DEFAULT_EMISSION_INTERVAL_MS
    private var simulateSignalVariation: Boolean = true
    private var simulatePrivilegedAccess: Boolean = false

    /**
     * Set the mock cellular state to simulate.
     */
    fun setMockData(state: MockCellularState) {
        Log.d(TAG, "Setting mock cellular state: cell=${state.cellId}, type=${state.networkType}")
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
     * Enable or disable signal strength variation simulation.
     */
    fun setSignalVariation(enabled: Boolean) {
        simulateSignalVariation = enabled
    }

    /**
     * Trigger a specific cellular anomaly type.
     */
    fun triggerAnomaly(type: CellularAnomalyType) {
        val anomaly = createAnomalyOfType(type)
        addAnomaly(anomaly)
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

    override fun start(): Boolean {
        if (_isActive.value) {
            Log.d(TAG, "Mock scanner already active")
            return true
        }

        Log.d(TAG, "Starting mock cellular scanner")

        // Recreate scope if needed
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }

        _isActive.value = true
        _lastError.value = null

        // Start periodic emission
        emissionJob = scope.launch {
            // Emit initial data
            emitMockData()

            // Then emit periodically
            while (isActive) {
                delay(emissionIntervalMs)
                if (_isActive.value) {
                    emitMockData()
                }
            }
        }

        return true
    }

    override fun stop() {
        Log.d(TAG, "Stopping mock cellular scanner")
        emissionJob?.cancel()
        emissionJob = null
        _isActive.value = false
    }

    override fun requiresRuntimePermissions(): Boolean = false

    override fun getRequiredPermissions(): List<String> = emptyList()

    override fun requestCellInfoUpdate() {
        if (!_isActive.value) {
            Log.w(TAG, "Scanner not active, cannot request update")
            return
        }

        scope.launch {
            emitMockData()
        }
    }

    override fun getImei(slotIndex: Int): String? {
        return if (simulatePrivilegedAccess) "353456789012345" else null
    }

    override fun getImsi(): String? {
        return if (simulatePrivilegedAccess) "310260123456789" else null
    }

    override fun hasPrivilegedAccess(): Boolean = simulatePrivilegedAccess

    private suspend fun emitMockData() {
        // Emit empty CellInfo list (we can't create real CellInfo objects)
        _cellInfo.emit(emptyList())

        // Analyze mock state for anomalies
        val state = mockState ?: return
        analyzeMockCellForAnomalies(state)

        // Emit current anomalies
        _anomalies.emit(detectedAnomalies.toList())
    }

    private fun analyzeMockCellForAnomalies(state: MockCellularState) {
        val signalStrength = if (simulateSignalVariation) {
            state.signalStrength + Random.nextInt(-5, 6)
        } else {
            state.signalStrength
        }

        // Check for specified anomaly type
        state.anomalyType?.let { anomalyType ->
            val cellAnomalyType = when (anomalyType) {
                com.flockyou.testmode.CellularAnomalyType.ENCRYPTION_DOWNGRADE -> CellularAnomalyType.ENCRYPTION_DOWNGRADE
                com.flockyou.testmode.CellularAnomalyType.SUSPICIOUS_NETWORK -> CellularAnomalyType.FAKE_BASE_STATION
                com.flockyou.testmode.CellularAnomalyType.RAPID_TOWER_SWITCHING -> CellularAnomalyType.UNEXPECTED_TOWER_CHANGE
                com.flockyou.testmode.CellularAnomalyType.SIGNAL_SPIKE -> CellularAnomalyType.SIGNAL_TOO_STRONG
                com.flockyou.testmode.CellularAnomalyType.UNEXPECTED_CELL_CHANGE -> CellularAnomalyType.UNEXPECTED_TOWER_CHANGE
                com.flockyou.testmode.CellularAnomalyType.LAC_ANOMALY -> CellularAnomalyType.UNUSUAL_LAC
            }

            addAnomaly(
                CellularAnomaly(
                    type = cellAnomalyType,
                    description = "${anomalyType.description} - ${state.networkType} network",
                    cellId = state.cellId.toString(),
                    lac = state.lac,
                    mcc = state.mcc,
                    mnc = state.mnc,
                    signalStrength = signalStrength,
                    metadata = mapOf("network_type" to state.networkType, "mock" to "true")
                )
            )
        }

        // Check for abnormally strong signal
        if (signalStrength > -50) {
            addAnomaly(
                CellularAnomaly(
                    type = CellularAnomalyType.SIGNAL_TOO_STRONG,
                    description = "${state.networkType} signal unusually strong: $signalStrength dBm",
                    cellId = state.cellId.toString(),
                    lac = state.lac,
                    mcc = state.mcc,
                    mnc = state.mnc,
                    signalStrength = signalStrength,
                    metadata = mapOf("network_type" to state.networkType, "mock" to "true")
                )
            )
        }

        // Check for 2G downgrade
        if (state.networkType == "GSM") {
            addAnomaly(
                CellularAnomaly(
                    type = CellularAnomalyType.PROTOCOL_DOWNGRADE,
                    description = "Connected to 2G GSM network - vulnerable to interception",
                    cellId = state.cellId.toString(),
                    lac = state.lac,
                    mcc = state.mcc,
                    mnc = state.mnc,
                    signalStrength = signalStrength,
                    metadata = mapOf("network_type" to "GSM", "mock" to "true")
                )
            )
        }
    }

    private fun addAnomaly(anomaly: CellularAnomaly) {
        // Avoid duplicate anomalies of the same type within a short window
        val recentSameType = detectedAnomalies.any {
            it.type == anomaly.type &&
                    it.cellId == anomaly.cellId &&
                    (System.currentTimeMillis() - it.timestamp) < 30000
        }

        if (!recentSameType) {
            detectedAnomalies.add(anomaly)
            // Keep only last 100 anomalies
            while (detectedAnomalies.size > 100) {
                detectedAnomalies.removeAt(0)
            }
            Log.d(TAG, "Added mock anomaly: ${anomaly.type} - ${anomaly.description}")
        }
    }

    private fun createAnomalyOfType(type: CellularAnomalyType): CellularAnomaly {
        return CellularAnomaly(
            type = type,
            description = "Mock ${type.name} anomaly triggered for testing",
            cellId = "mock_${Random.nextInt(10000, 99999)}",
            lac = Random.nextInt(1000, 9999),
            mcc = 310,
            mnc = 260,
            signalStrength = -70,
            metadata = mapOf("mock" to "true", "triggered" to "manual")
        )
    }
}

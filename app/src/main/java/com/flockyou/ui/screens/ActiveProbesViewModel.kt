package com.flockyou.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.scanner.flipper.FlipperClient
import com.flockyou.scanner.flipper.FlipperConnectionState
import com.flockyou.scanner.flipper.FlipperScannerManager
import com.flockyou.scanner.probes.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActiveProbesViewModel @Inject constructor(
    private val settingsRepository: ActiveProbeSettingsRepository,
    private val flipperScannerManager: FlipperScannerManager
) : ViewModel() {

    companion object {
        private const val TAG = "ActiveProbesViewModel"
        private const val PROBE_RESULT_DISPLAY_DURATION_MS = 3000L
    }

    val settings: StateFlow<ActiveProbeSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActiveProbeSettings())

    private val _probeExecutionState = MutableStateFlow<ProbeExecutionState>(ProbeExecutionState.Idle)
    val probeExecutionState: StateFlow<ProbeExecutionState> = _probeExecutionState.asStateFlow()

    private val _showAuthorizationDialog = MutableStateFlow(false)
    val showAuthorizationDialog: StateFlow<Boolean> = _showAuthorizationDialog.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    val flipperConnectionState: StateFlow<FlipperConnectionState> = flipperScannerManager.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FlipperConnectionState.DISCONNECTED)

    val isFlipperConnected: Boolean
        get() = flipperConnectionState.value == FlipperConnectionState.READY

    // ========================================================================
    // Master Toggle
    // ========================================================================

    suspend fun setActiveProbesEnabled(enabled: Boolean) {
        if (enabled && settings.value.authorizationNote.isBlank()) {
            _showAuthorizationDialog.value = true
        } else {
            settingsRepository.setActiveProbesEnabled(enabled)
        }
    }

    suspend fun setAuthorizationAndEnable(note: String) {
        settingsRepository.setAuthorizationContext(note)
        settingsRepository.setActiveProbesEnabled(true)
        _showAuthorizationDialog.value = false
    }

    fun dismissAuthorizationDialog() {
        _showAuthorizationDialog.value = false
    }

    // ========================================================================
    // Category Toggles
    // ========================================================================

    suspend fun setCategoryEnabled(category: ProbeCategory, enabled: Boolean) {
        settingsRepository.setCategoryEnabled(category, enabled)
    }

    // ========================================================================
    // Probe Execution
    // ========================================================================

    fun requestProbeExecution(probeId: String) {
        val probe = ProbeCatalog.getById(probeId)
        if (probe == null) {
            viewModelScope.launch { _toastMessage.emit("Unknown probe: $probeId") }
            return
        }

        val result = settingsRepository.isProbeAllowed(settings.value, probeId)
        when (result) {
            is ProbeAllowedResult.Allowed -> executeProbe(probe)
            is ProbeAllowedResult.Denied -> {
                viewModelScope.launch { _toastMessage.emit(result.reason) }
            }
        }
    }

    private fun executeProbe(probe: ProbeDefinition) {
        if (!isFlipperConnected) {
            viewModelScope.launch { _toastMessage.emit("Flipper Zero not connected") }
            return
        }

        val flipperClient = flipperScannerManager.client
        if (flipperClient == null) {
            viewModelScope.launch { _toastMessage.emit("Flipper client not initialized") }
            return
        }

        _probeExecutionState.value = ProbeExecutionState.Executing(probe.id, probe.name)

        val success = when (probe.id) {
            // Public Safety & Fleet
            "tire_kicker" -> flipperClient.sendTpmsWakeupSignal(settings.value.maxLfDurationMs)
            "opticom_verifier" -> flipperClient.sendTrafficInfraTest(
                emergencyPriority = true,
                durationMs = settings.value.maxIrStrobeDurationMs
            )
            "honey_potter" -> flipperClient.sendWifiHoneyPot("NETMOTION")
            "blueforce_handshake" -> flipperClient.sendBleActiveScan(true)

            // Infrastructure
            "zigbee_knocker" -> flipperClient.sendZigbeeBeacon(0)
            "ghost_car" -> flipperClient.sendInductiveLoopPulse(frequencyHz = 40000, durationMs = 500)

            // Physical Access
            "sleep_denial" -> {
                // Requires captured signal data - show message
                viewModelScope.launch { _toastMessage.emit("Sleep Denial requires captured signal data") }
                false
            }
            "replay_injector" -> {
                viewModelScope.launch { _toastMessage.emit("Replay Injector requires captured card data") }
                false
            }
            "magspoof" -> {
                viewModelScope.launch { _toastMessage.emit("MagSpoof requires track data input") }
                false
            }
            "master_key" -> {
                viewModelScope.launch { _toastMessage.emit("Master Key requires iButton key data") }
                false
            }

            // Digital
            "mousejacker" -> {
                viewModelScope.launch { _toastMessage.emit("MouseJacker requires target address and payload") }
                false
            }

            else -> {
                Log.w(TAG, "Probe execution not implemented: ${probe.id}")
                viewModelScope.launch { _toastMessage.emit("Probe not implemented: ${probe.name}") }
                false
            }
        }

        _probeExecutionState.value = if (success) {
            ProbeExecutionState.Success(probe.id, probe.name)
        } else {
            ProbeExecutionState.Error(probe.id, "Failed to execute ${probe.name}")
        }

        // Reset to idle after a delay to ensure success/error state is visible to user
        viewModelScope.launch {
            kotlinx.coroutines.delay(PROBE_RESULT_DISPLAY_DURATION_MS)
            _probeExecutionState.value = ProbeExecutionState.Idle
        }
    }

    // ========================================================================
    // Safety Limits
    // ========================================================================

    suspend fun setSafetyLimits(
        maxLfDurationMs: Int? = null,
        maxIrStrobeDurationMs: Int? = null,
        maxReplayCount: Int? = null
    ) {
        settingsRepository.setSafetyLimits(maxLfDurationMs, maxIrStrobeDurationMs, maxReplayCount)
    }

    suspend fun clearAuthorization() {
        settingsRepository.clearAuthorization()
    }
}

sealed class ProbeExecutionState {
    object Idle : ProbeExecutionState()
    data class Executing(val probeId: String, val probeName: String) : ProbeExecutionState()
    data class Success(val probeId: String, val probeName: String) : ProbeExecutionState()
    data class Error(val probeId: String, val message: String) : ProbeExecutionState()
}

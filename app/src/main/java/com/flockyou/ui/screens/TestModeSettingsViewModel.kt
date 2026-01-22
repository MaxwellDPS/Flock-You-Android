package com.flockyou.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.testmode.TestModeConfig
import com.flockyou.testmode.TestModeConfigRepository
import com.flockyou.testmode.TestModeOrchestrator
import com.flockyou.testmode.TestModeStatus
import com.flockyou.testmode.TestScenario
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Test Mode Settings screen.
 *
 * Provides:
 * - Current test mode configuration
 * - Runtime status of test mode
 * - Available test scenarios
 * - Actions for controlling test mode
 */
@HiltViewModel
class TestModeSettingsViewModel @Inject constructor(
    private val orchestrator: TestModeOrchestrator,
    private val configRepository: TestModeConfigRepository
) : ViewModel() {

    /**
     * Current test mode configuration.
     */
    val config: StateFlow<TestModeConfig> = orchestrator.config
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TestModeConfig()
        )

    /**
     * Current test mode runtime status.
     */
    val status: StateFlow<TestModeStatus> = orchestrator.status
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TestModeStatus()
        )

    /**
     * All available test scenarios.
     */
    val scenarios: List<TestScenario> = orchestrator.getAvailableScenarios()

    /**
     * Enable test mode without starting a specific scenario.
     */
    fun enableTestMode() {
        orchestrator.enableTestMode()
    }

    /**
     * Disable test mode and stop any active scenario.
     */
    fun disableTestMode() {
        orchestrator.disableTestMode()
    }

    /**
     * Start a test scenario by ID.
     *
     * @param scenarioId The ID of the scenario to start
     */
    fun startScenario(scenarioId: String) {
        orchestrator.startScenario(scenarioId)
    }

    /**
     * Stop the currently running scenario without disabling test mode.
     */
    fun stopScenario() {
        orchestrator.stopScenario()
    }

    /**
     * Update the test mode configuration.
     *
     * @param newConfig The new configuration to apply
     */
    fun updateConfig(newConfig: TestModeConfig) {
        orchestrator.updateConfig(newConfig)
        // Also persist to repository
        viewModelScope.launch {
            configRepository.updateConfig(newConfig)
        }
    }
}

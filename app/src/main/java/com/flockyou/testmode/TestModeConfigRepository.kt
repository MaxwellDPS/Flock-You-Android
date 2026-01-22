package com.flockyou.testmode

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore delegate for test mode configuration persistence.
 */
private val Context.testModeDataStore by preferencesDataStore(name = "test_mode_config")

/**
 * Repository for persisting and managing test mode configuration.
 *
 * Uses DataStore Preferences for persistence, following the same pattern as
 * DetectionConfigRepository and other settings repositories in the app.
 *
 * This repository provides:
 * - Reactive configuration updates via Flow
 * - Individual setting updates for granular control
 * - Full configuration updates for bulk changes
 * - Thread-safe persistence operations
 */
@Singleton
class TestModeConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * DataStore preference keys for test mode configuration.
     */
    private object Keys {
        val ENABLED = booleanPreferencesKey("test_mode_enabled")
        val ACTIVE_SCENARIO_ID = stringPreferencesKey("active_scenario_id")
        val AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
        val EMISSION_INTERVAL = longPreferencesKey("emission_interval_ms")
        val SIMULATE_VARIATION = booleanPreferencesKey("simulate_signal_variation")
        val SHOW_BANNER = booleanPreferencesKey("show_test_mode_banner")
    }

    /**
     * Flow of the current test mode configuration.
     * Emits updates whenever any configuration value changes.
     */
    val config: Flow<TestModeConfig> = context.testModeDataStore.data.map { prefs ->
        TestModeConfig(
            enabled = prefs[Keys.ENABLED] ?: false,
            activeScenarioId = prefs[Keys.ACTIVE_SCENARIO_ID],
            autoAdvanceScenario = prefs[Keys.AUTO_ADVANCE] ?: true,
            dataEmissionIntervalMs = prefs[Keys.EMISSION_INTERVAL] ?: TestModeConfig.DEFAULT_EMISSION_INTERVAL_MS,
            simulateSignalVariation = prefs[Keys.SIMULATE_VARIATION] ?: true,
            showTestModeBanner = prefs[Keys.SHOW_BANNER] ?: true
        )
    }

    /**
     * Get the current configuration synchronously.
     * Useful for non-coroutine contexts.
     */
    suspend fun getCurrentConfig(): TestModeConfig {
        return config.first()
    }

    /**
     * Update the entire test mode configuration.
     *
     * @param config The new configuration to persist
     */
    suspend fun updateConfig(config: TestModeConfig) {
        context.testModeDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = config.enabled
            config.activeScenarioId?.let { prefs[Keys.ACTIVE_SCENARIO_ID] = it }
                ?: prefs.remove(Keys.ACTIVE_SCENARIO_ID)
            prefs[Keys.AUTO_ADVANCE] = config.autoAdvanceScenario
            prefs[Keys.EMISSION_INTERVAL] = config.dataEmissionIntervalMs
            prefs[Keys.SIMULATE_VARIATION] = config.simulateSignalVariation
            prefs[Keys.SHOW_BANNER] = config.showTestModeBanner
        }
    }

    /**
     * Enable or disable test mode.
     *
     * @param enabled Whether test mode should be enabled
     */
    suspend fun setEnabled(enabled: Boolean) {
        context.testModeDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = enabled
        }
    }

    /**
     * Set the active test scenario.
     *
     * @param scenarioId The ID of the scenario to activate, or null to clear
     */
    suspend fun setActiveScenario(scenarioId: String?) {
        context.testModeDataStore.edit { prefs ->
            scenarioId?.let { prefs[Keys.ACTIVE_SCENARIO_ID] = it }
                ?: prefs.remove(Keys.ACTIVE_SCENARIO_ID)
        }
    }

    /**
     * Update the auto-advance setting.
     *
     * @param autoAdvance Whether scenarios should auto-advance through stages
     */
    suspend fun setAutoAdvance(autoAdvance: Boolean) {
        context.testModeDataStore.edit { prefs ->
            prefs[Keys.AUTO_ADVANCE] = autoAdvance
        }
    }

    /**
     * Update the data emission interval.
     *
     * @param intervalMs The interval in milliseconds between mock data emissions
     */
    suspend fun setEmissionInterval(intervalMs: Long) {
        val clampedInterval = intervalMs.coerceIn(
            TestModeConfig.MIN_EMISSION_INTERVAL_MS,
            TestModeConfig.MAX_EMISSION_INTERVAL_MS
        )
        context.testModeDataStore.edit { prefs ->
            prefs[Keys.EMISSION_INTERVAL] = clampedInterval
        }
    }

    /**
     * Update the signal variation simulation setting.
     *
     * @param simulate Whether to add realistic variation to signal strengths
     */
    suspend fun setSimulateSignalVariation(simulate: Boolean) {
        context.testModeDataStore.edit { prefs ->
            prefs[Keys.SIMULATE_VARIATION] = simulate
        }
    }

    /**
     * Update the test mode banner visibility setting.
     *
     * @param show Whether to show a visual indicator when test mode is active
     */
    suspend fun setShowBanner(show: Boolean) {
        context.testModeDataStore.edit { prefs ->
            prefs[Keys.SHOW_BANNER] = show
        }
    }

    /**
     * Reset all test mode settings to defaults.
     * Disables test mode and clears any active scenario.
     */
    suspend fun resetToDefaults() {
        context.testModeDataStore.edit { prefs ->
            prefs.clear()
        }
    }

    /**
     * Start a test scenario.
     * Enables test mode and sets the active scenario.
     *
     * @param scenarioId The ID of the scenario to start
     */
    suspend fun startScenario(scenarioId: String) {
        context.testModeDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = true
            prefs[Keys.ACTIVE_SCENARIO_ID] = scenarioId
        }
    }

    /**
     * Stop the current test scenario.
     * Disables test mode and clears the active scenario.
     */
    suspend fun stopScenario() {
        context.testModeDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = false
            prefs.remove(Keys.ACTIVE_SCENARIO_ID)
        }
    }
}

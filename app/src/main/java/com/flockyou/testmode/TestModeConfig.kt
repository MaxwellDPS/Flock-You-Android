package com.flockyou.testmode

/**
 * Configuration for Test Mode functionality.
 *
 * Test Mode allows simulating various surveillance detection scenarios
 * without requiring actual devices to be present. This is useful for:
 * - Demonstrating app capabilities
 * - Testing UI and notification behavior
 * - Training users on threat recognition
 * - Development and QA testing
 */
data class TestModeConfig(
    /** Whether test mode is currently enabled */
    val enabled: Boolean = false,

    /** ID of the currently active test scenario (null = no scenario active) */
    val activeScenarioId: String? = null,

    /** Automatically advance through scenario stages */
    val autoAdvanceScenario: Boolean = true,

    /** How often to emit mock detection data (milliseconds) */
    val dataEmissionIntervalMs: Long = 3000L,

    /** Simulate realistic signal strength variations */
    val simulateSignalVariation: Boolean = true,

    /** Show a visual indicator when test mode is active */
    val showTestModeBanner: Boolean = true
) {
    companion object {
        /** Default configuration with test mode disabled */
        val DEFAULT = TestModeConfig()

        /** Minimum emission interval (1 second) */
        const val MIN_EMISSION_INTERVAL_MS = 1000L

        /** Maximum emission interval (30 seconds) */
        const val MAX_EMISSION_INTERVAL_MS = 30000L

        /** Default emission interval (3 seconds) */
        const val DEFAULT_EMISSION_INTERVAL_MS = 3000L
    }

    /**
     * Check if test mode is actively running a scenario
     */
    fun isActivelyRunning(): Boolean = enabled && activeScenarioId != null

    /**
     * Create a copy with the scenario activated
     */
    fun withScenario(scenarioId: String): TestModeConfig = copy(
        enabled = true,
        activeScenarioId = scenarioId
    )

    /**
     * Create a copy with test mode stopped
     */
    fun stopped(): TestModeConfig = copy(
        enabled = false,
        activeScenarioId = null
    )
}

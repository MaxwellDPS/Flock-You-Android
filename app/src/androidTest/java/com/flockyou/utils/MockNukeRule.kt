package com.flockyou.utils

import com.flockyou.data.NukeSettings
import com.flockyou.security.NukeResult
import com.flockyou.security.NukeTriggerSource
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Test rule that provides a mock NukeManager for testing nuke-related functionality
 * WITHOUT actually executing data destruction operations.
 *
 * This is a CRITICAL SAFETY MECHANISM for testing. Any test that exercises nuke
 * functionality should use this rule to prevent accidental data loss.
 *
 * Usage:
 * ```
 * @get:Rule
 * val mockNukeRule = MockNukeRule()
 *
 * @Test
 * fun myTest() {
 *     // Configure the mock behavior
 *     mockNukeRule.setMockResult(NukeResult(...))
 *
 *     // Trigger some action that would normally nuke
 *     someComponent.triggerNuke()
 *
 *     // Verify the nuke was called with expected parameters
 *     assertTrue(mockNukeRule.wasCalled)
 *     assertEquals(NukeTriggerSource.USB_CONNECTION, mockNukeRule.lastTriggerSource)
 * }
 * ```
 */
class MockNukeRule : TestWatcher() {

    /**
     * List of all nuke calls recorded during the test.
     */
    val nukeCalls = mutableListOf<NukeCall>()

    /**
     * Whether any nuke was triggered during this test.
     */
    val wasCalled: Boolean
        get() = nukeCalls.isNotEmpty()

    /**
     * The trigger source of the last nuke call, or null if no call was made.
     */
    val lastTriggerSource: NukeTriggerSource?
        get() = nukeCalls.lastOrNull()?.triggerSource

    /**
     * The settings used in the last nuke call, or null if no call was made.
     */
    val lastSettings: NukeSettings?
        get() = nukeCalls.lastOrNull()?.settings

    /**
     * Custom result to return from mock nuke executions.
     * If null, a default successful result is returned.
     */
    private var mockResult: NukeResult? = null

    /**
     * Whether the mock should simulate a failure.
     */
    private var shouldFail: Boolean = false

    /**
     * Error message to use when simulating failures.
     */
    private var failureMessage: String = "Mock nuke failure"

    /**
     * Data class representing a single nuke call.
     */
    data class NukeCall(
        val triggerSource: NukeTriggerSource,
        val settings: NukeSettings?,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun starting(description: Description) {
        super.starting(description)
        nukeCalls.clear()
        mockResult = null
        shouldFail = false
        failureMessage = "Mock nuke failure"
    }

    override fun finished(description: Description) {
        super.finished(description)
        nukeCalls.clear()
    }

    /**
     * Record a nuke call for test verification.
     * This is called by the mock implementation instead of the real NukeManager.
     *
     * @param triggerSource The source that triggered the nuke
     * @param settings The settings used for the nuke, or null for defaults
     * @return The mock result
     */
    fun recordNukeCall(
        triggerSource: NukeTriggerSource,
        settings: NukeSettings? = null
    ): NukeResult {
        nukeCalls.add(NukeCall(triggerSource, settings))

        return mockResult ?: if (shouldFail) {
            NukeResult(
                success = false,
                databaseWiped = false,
                settingsWiped = false,
                cacheWiped = false,
                errorMessage = failureMessage,
                triggerSource = triggerSource
            )
        } else {
            NukeResult(
                success = true,
                databaseWiped = settings?.wipeDatabase ?: true,
                settingsWiped = settings?.wipeSettings ?: true,
                cacheWiped = settings?.wipeCache ?: true,
                errorMessage = null,
                triggerSource = triggerSource
            )
        }
    }

    /**
     * Set a custom result to return from mock nuke executions.
     */
    fun setMockResult(result: NukeResult) {
        mockResult = result
    }

    /**
     * Configure the mock to simulate nuke failures.
     *
     * @param fail Whether the mock should fail
     * @param message The error message to use when failing
     */
    fun setSimulateFailure(fail: Boolean, message: String = "Mock nuke failure") {
        shouldFail = fail
        failureMessage = message
    }

    /**
     * Get the number of times nuke was called during this test.
     */
    fun getCallCount(): Int = nukeCalls.size

    /**
     * Get nuke calls filtered by trigger source.
     */
    fun getCallsByTriggerSource(source: NukeTriggerSource): List<NukeCall> {
        return nukeCalls.filter { it.triggerSource == source }
    }

    /**
     * Assert that nuke was called with the expected trigger source.
     */
    fun assertCalledWith(expectedSource: NukeTriggerSource) {
        if (!nukeCalls.any { it.triggerSource == expectedSource }) {
            throw AssertionError(
                "Expected nuke to be called with trigger source $expectedSource, " +
                        "but was called with: ${nukeCalls.map { it.triggerSource }}"
            )
        }
    }

    /**
     * Assert that nuke was never called.
     */
    fun assertNotCalled() {
        if (nukeCalls.isNotEmpty()) {
            throw AssertionError(
                "Expected nuke to not be called, but was called ${nukeCalls.size} times " +
                        "with triggers: ${nukeCalls.map { it.triggerSource }}"
            )
        }
    }

    /**
     * Assert that nuke was called exactly N times.
     */
    fun assertCallCount(expected: Int) {
        if (nukeCalls.size != expected) {
            throw AssertionError(
                "Expected nuke to be called $expected times, but was called ${nukeCalls.size} times"
            )
        }
    }
}

/**
 * Interface for components that can use a mock NukeManager.
 * Implement this to enable testing with MockNukeRule.
 */
interface MockableNukeExecutor {
    /**
     * Execute a nuke operation.
     * In test mode, this should use the MockNukeRule instead of the real NukeManager.
     */
    suspend fun executeNuke(
        triggerSource: NukeTriggerSource,
        settings: NukeSettings? = null
    ): NukeResult
}

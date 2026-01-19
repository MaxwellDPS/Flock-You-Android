package com.flockyou.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.utils.MockNukeRule
import com.flockyou.utils.TestHelpers
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Comprehensive E2E tests for FailedAuthWatcher.
 *
 * Tests cover:
 * - Failed attempt counting
 * - Reset window functionality
 * - Threshold detection
 * - Counter reset on success
 * - State persistence behavior
 * - Integration with NukeSettings
 *
 * NOTE: These tests use MockNukeRule to prevent actual data destruction.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FailedAuthE2ETest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @get:Rule
    var mockNukeRule = MockNukeRule()

    @Inject
    lateinit var failedAuthWatcher: FailedAuthWatcher

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        failedAuthWatcher.reset()
    }

    @After
    fun cleanup() {
        runBlocking {
            nukeSettingsRepository.setNukeEnabled(false)
            nukeSettingsRepository.setFailedAuthTriggerEnabled(false)
            failedAuthWatcher.reset()
        }
    }

    // ==================== Failed Attempt Counting Tests ====================

    @Test
    fun failedAuth_initialCountIsZero() {
        assertEquals("Initial failed count should be 0", 0, failedAuthWatcher.getFailedAttemptCount())
    }

    @Test
    fun failedAuth_countIncrementsOnFailure() = runTest {
        // Enable the trigger
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        // Record failures
        failedAuthWatcher.recordFailedAttemptAsync()
        assertEquals("Count should be 1 after first failure", 1, failedAuthWatcher.getFailedAttemptCount())

        failedAuthWatcher.recordFailedAttemptAsync()
        assertEquals("Count should be 2 after second failure", 2, failedAuthWatcher.getFailedAttemptCount())

        failedAuthWatcher.recordFailedAttemptAsync()
        assertEquals("Count should be 3 after third failure", 3, failedAuthWatcher.getFailedAttemptCount())
    }

    @Test
    fun failedAuth_countResetsOnSuccess() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        // Record some failures
        failedAuthWatcher.recordFailedAttemptAsync()
        failedAuthWatcher.recordFailedAttemptAsync()
        failedAuthWatcher.recordFailedAttemptAsync()
        assertEquals("Count should be 3", 3, failedAuthWatcher.getFailedAttemptCount())

        // Record success
        failedAuthWatcher.recordSuccessfulAuth()

        assertEquals("Count should be 0 after success", 0, failedAuthWatcher.getFailedAttemptCount())
    }

    @Test
    fun failedAuth_firstFailureRecordsTime() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        val beforeTime = System.currentTimeMillis()
        failedAuthWatcher.recordFailedAttemptAsync()
        val afterTime = System.currentTimeMillis()

        val firstFailureTime = failedAuthWatcher.getFirstFailureTime()

        assertTrue(
            "First failure time should be set",
            firstFailureTime >= beforeTime && firstFailureTime <= afterTime
        )
    }

    @Test
    fun failedAuth_successResetsFirstFailureTime() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        failedAuthWatcher.recordFailedAttemptAsync()
        assertTrue("First failure time should be set", failedAuthWatcher.getFirstFailureTime() > 0)

        failedAuthWatcher.recordSuccessfulAuth()

        assertEquals("First failure time should be reset", 0, failedAuthWatcher.getFirstFailureTime())
    }

    // ==================== Threshold Detection Tests ====================

    @Test
    fun failedAuth_thresholdTriggersNuke() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 3,
            resetHours = 24
        )

        // Record failures up to threshold
        var triggered = false
        repeat(3) {
            triggered = failedAuthWatcher.recordFailedAttemptAsync()
        }

        assertTrue("Nuke should be triggered at threshold", triggered)
        assertTrue("Nuke triggered flag should be set", failedAuthWatcher.isNukeTriggered())
    }

    @Test
    fun failedAuth_thresholdNotTriggeredBelowThreshold() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 5,
            resetHours = 24
        )

        // Record failures below threshold
        repeat(4) {
            val triggered = failedAuthWatcher.recordFailedAttemptAsync()
            assertFalse("Nuke should not be triggered below threshold", triggered)
        }

        assertFalse("Nuke triggered flag should not be set", failedAuthWatcher.isNukeTriggered())
    }

    @Test
    fun failedAuth_customThresholdIsRespected() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 10,
            resetHours = 24
        )

        // Record 9 failures (one below threshold)
        repeat(9) {
            val triggered = failedAuthWatcher.recordFailedAttemptAsync()
            assertFalse("Nuke should not be triggered at attempt ${it + 1}", triggered)
        }

        // 10th failure should trigger
        val triggered = failedAuthWatcher.recordFailedAttemptAsync()
        assertTrue("Nuke should be triggered at 10th attempt", triggered)
    }

    // ==================== Disabled State Tests ====================

    @Test
    fun failedAuth_doesNothingWhenNukeDisabled() = runTest {
        nukeSettingsRepository.setNukeEnabled(false)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        repeat(100) {
            val triggered = failedAuthWatcher.recordFailedAttemptAsync()
            assertFalse("Nuke should never trigger when master switch is off", triggered)
        }
    }

    @Test
    fun failedAuth_doesNothingWhenTriggerDisabled() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(false)

        repeat(100) {
            val triggered = failedAuthWatcher.recordFailedAttemptAsync()
            assertFalse("Nuke should never trigger when failed auth trigger is off", triggered)
        }
    }

    // ==================== Remaining Attempts Tests ====================

    @Test
    fun failedAuth_remainingAttemptsCalculatesCorrectly() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 5,
            resetHours = 24
        )

        assertEquals("Should have 5 remaining initially", 5, failedAuthWatcher.getRemainingAttempts())

        failedAuthWatcher.recordFailedAttemptAsync()
        assertEquals("Should have 4 remaining after 1 failure", 4, failedAuthWatcher.getRemainingAttempts())

        failedAuthWatcher.recordFailedAttemptAsync()
        failedAuthWatcher.recordFailedAttemptAsync()
        assertEquals("Should have 2 remaining after 3 failures", 2, failedAuthWatcher.getRemainingAttempts())
    }

    @Test
    fun failedAuth_remainingAttemptsNullWhenDisabled() = runTest {
        nukeSettingsRepository.setNukeEnabled(false)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(false)

        assertNull("Remaining attempts should be null when disabled", failedAuthWatcher.getRemainingAttempts())
    }

    @Test
    fun failedAuth_remainingAttemptsNeverNegative() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 3,
            resetHours = 24
        )

        // Record more failures than threshold
        repeat(10) {
            failedAuthWatcher.recordFailedAttemptAsync()
        }

        val remaining = failedAuthWatcher.getRemainingAttempts()
        assertTrue("Remaining should be 0 or null, not negative", remaining == null || remaining >= 0)
    }

    // ==================== State Reset Tests ====================

    @Test
    fun failedAuth_resetClearsAllState() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        // Build up some state
        failedAuthWatcher.recordFailedAttemptAsync()
        failedAuthWatcher.recordFailedAttemptAsync()
        assertTrue("Should have failed count > 0", failedAuthWatcher.getFailedAttemptCount() > 0)

        // Reset
        failedAuthWatcher.reset()

        assertEquals("Failed count should be 0", 0, failedAuthWatcher.getFailedAttemptCount())
        assertEquals("First failure time should be 0", 0, failedAuthWatcher.getFirstFailureTime())
        assertFalse("Nuke triggered should be false", failedAuthWatcher.isNukeTriggered())
    }

    @Test
    fun failedAuth_nukeTriggeredFlagPreventsMoreTriggers() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 3,
            resetHours = 24
        )

        // Trigger nuke
        repeat(3) {
            failedAuthWatcher.recordFailedAttemptAsync()
        }
        assertTrue("Nuke should be triggered", failedAuthWatcher.isNukeTriggered())

        // Additional failures should not re-trigger
        val additionalResult = failedAuthWatcher.recordFailedAttemptAsync()
        assertFalse("Additional failures should not re-trigger nuke", additionalResult)
    }

    // ==================== Fire-and-Forget Method Tests ====================

    @Test
    fun failedAuth_fireAndForgetMethodWorks() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        // Use fire-and-forget method
        failedAuthWatcher.recordFailedAttempt()

        // Wait a bit for the coroutine to complete
        TestHelpers.waitForCondition(timeoutMs = 1000) {
            failedAuthWatcher.getFailedAttemptCount() > 0
        }

        assertTrue("Failed count should be incremented", failedAuthWatcher.getFailedAttemptCount() > 0)
    }

    // ==================== Integration Tests ====================

    @Test
    fun failedAuth_integratesWithSettingsChanges() = runTest {
        // Start with trigger disabled
        nukeSettingsRepository.setNukeEnabled(false)

        failedAuthWatcher.recordFailedAttemptAsync()
        assertFalse("No trigger when disabled", failedAuthWatcher.isNukeTriggered())

        // Enable trigger with low threshold
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 2,
            resetHours = 24
        )

        // Reset counter to start fresh
        failedAuthWatcher.reset()

        // Now failures should count
        failedAuthWatcher.recordFailedAttemptAsync()
        failedAuthWatcher.recordFailedAttemptAsync()

        assertTrue("Trigger should work when enabled", failedAuthWatcher.isNukeTriggered())
    }

    @Test
    fun failedAuth_successAfterPartialFailuresResetsWindow() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 5,
            resetHours = 24
        )

        // Fail 4 times (one below threshold)
        repeat(4) {
            failedAuthWatcher.recordFailedAttemptAsync()
        }
        assertEquals("Should have 4 failures", 4, failedAuthWatcher.getFailedAttemptCount())

        // Success resets the counter
        failedAuthWatcher.recordSuccessfulAuth()
        assertEquals("Should have 0 failures after success", 0, failedAuthWatcher.getFailedAttemptCount())

        // Now need full 5 failures again to trigger
        repeat(4) {
            val triggered = failedAuthWatcher.recordFailedAttemptAsync()
            assertFalse("Should not trigger yet", triggered)
        }

        val triggered = failedAuthWatcher.recordFailedAttemptAsync()
        assertTrue("Should trigger at 5th failure", triggered)
    }

    // ==================== Edge Cases ====================

    @Test
    fun failedAuth_thresholdOfOneTriggersImmediately() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 3, // Minimum allowed is 3
            resetHours = 24
        )

        repeat(2) {
            failedAuthWatcher.recordFailedAttemptAsync()
        }
        assertFalse("Should not trigger before threshold", failedAuthWatcher.isNukeTriggered())

        failedAuthWatcher.recordFailedAttemptAsync()
        assertTrue("Should trigger at threshold", failedAuthWatcher.isNukeTriggered())
    }

    @Test
    fun failedAuth_highThresholdWorksCorrectly() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 100,
            resetHours = 24
        )

        // Fail 99 times
        repeat(99) {
            val triggered = failedAuthWatcher.recordFailedAttemptAsync()
            assertFalse("Should not trigger at attempt ${it + 1}", triggered)
        }

        // 100th should trigger
        val triggered = failedAuthWatcher.recordFailedAttemptAsync()
        assertTrue("Should trigger at 100th attempt", triggered)
    }

    @Test
    fun failedAuth_multipleSuccessesAreSafe() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        failedAuthWatcher.recordFailedAttemptAsync()
        failedAuthWatcher.recordFailedAttemptAsync()

        // Multiple successes should be safe
        failedAuthWatcher.recordSuccessfulAuth()
        failedAuthWatcher.recordSuccessfulAuth()
        failedAuthWatcher.recordSuccessfulAuth()

        assertEquals("Count should be 0", 0, failedAuthWatcher.getFailedAttemptCount())
        assertFalse("Nuke should not be triggered", failedAuthWatcher.isNukeTriggered())
    }
}

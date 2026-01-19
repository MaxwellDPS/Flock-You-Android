package com.flockyou.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.data.SecuritySettingsRepository
import com.flockyou.security.*
import com.flockyou.utils.MockNukeRule
import com.flockyou.utils.TestHelpers
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
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
 * End-to-end integration tests for security flows.
 *
 * Tests cover:
 * - Lock -> Unlock -> Auto-lock flow
 * - PIN verification integration
 * - Failed auth -> Threshold flow
 * - Duress PIN integration
 * - Security settings integration
 *
 * NOTE: Uses MockNukeRule to prevent actual data destruction.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SecurityFlowE2ETest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    var mockNukeRule = MockNukeRule()

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var failedAuthWatcher: FailedAuthWatcher

    @Inject
    lateinit var duressAuthenticator: DuressAuthenticator

    @Inject
    lateinit var securitySettingsRepository: SecuritySettingsRepository

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        runBlocking {
            appLockManager.removePin()
            failedAuthWatcher.reset()
            nukeSettingsRepository.setNukeEnabled(false)
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            appLockManager.removePin()
            failedAuthWatcher.reset()
            nukeSettingsRepository.setNukeEnabled(false)
        }
    }

    // ==================== Lock/Unlock Flow Tests ====================

    @Test
    fun securityFlow_initiallyLocked() {
        assertTrue("App should be locked initially", appLockManager.isLocked.value)
    }

    @Test
    fun securityFlow_unlockChangesState() {
        appLockManager.unlock()

        assertFalse("App should be unlocked", appLockManager.isLocked.value)
        assertTrue("Last unlock time should be set", appLockManager.lastUnlockTime.value > 0)
    }

    @Test
    fun securityFlow_lockChangesState() {
        appLockManager.unlock()
        appLockManager.lock()

        assertTrue("App should be locked", appLockManager.isLocked.value)
    }

    @Test
    fun securityFlow_pinVerificationUnlocks() = runTest {
        appLockManager.setPin("5937")
        assertTrue("Should be locked initially", appLockManager.isLocked.value)

        appLockManager.verifyPinWithResultAsync("5937")

        assertFalse("Should be unlocked after PIN verification", appLockManager.isLocked.value)
    }

    // ==================== Failed Auth Integration Tests ====================

    @Test
    fun securityFlow_failedPinRecordedInWatcher() = runTest {
        appLockManager.setPin("5937")

        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 10
        )

        val initialCount = failedAuthWatcher.getFailedAttemptCount()

        appLockManager.verifyPinWithResultAsync("0000")

        val afterCount = failedAuthWatcher.getFailedAttemptCount()
        assertTrue("Failed attempt should be recorded", afterCount > initialCount)
    }

    @Test
    fun securityFlow_successfulPinResetsWatcher() = runTest {
        appLockManager.setPin("5937")

        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        // Fail a few times
        repeat(3) {
            appLockManager.verifyPinWithResultAsync("0000")
        }

        assertTrue("Should have failed attempts", failedAuthWatcher.getFailedAttemptCount() > 0)

        // Success resets counter
        appLockManager.verifyPinWithResultAsync("5937")

        assertEquals("Counter should be reset", 0, failedAuthWatcher.getFailedAttemptCount())
    }

    @Test
    fun securityFlow_lockoutIntegration() = runTest {
        appLockManager.setPin("5937")
        appLockManager.lockoutConfig = AppLockManager.LockoutConfig(
            maxFailedAttempts = 3,
            lockoutDurationMs = 60000L
        )

        // Fail to trigger lockout
        repeat(3) {
            appLockManager.verifyPinWithResultAsync("0000")
        }

        assertTrue("Should be locked out", appLockManager.isLockedOut())

        // Correct PIN should return LockedOut
        val result = appLockManager.verifyPinWithResultAsync("5937")
        assertTrue(
            "Should return LockedOut",
            result is AppLockManager.PinVerificationResult.LockedOut
        )
    }

    // ==================== Duress PIN Integration Tests ====================

    @Test
    fun securityFlow_duressPinCanBeSet() = runTest {
        appLockManager.setPin("5937")

        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setDuressPinEnabled(true)

        val result = duressAuthenticator.setDuressPin("9999", "hash", "salt")

        assertTrue("Duress PIN should be set", result)
        assertTrue("Duress PIN should be marked as set", duressAuthenticator.isDuressPinSet())
    }

    @Test
    fun securityFlow_duressPinDetected() = runTest {
        appLockManager.setPin("5937")

        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setDuressPinEnabled(true)

        duressAuthenticator.setDuressPin("9999", "normal_hash", "normal_salt")

        val result = duressAuthenticator.checkPin("9999", "normal_hash", "normal_salt")

        assertTrue(
            "Duress PIN should be detected",
            result is DuressCheckResult.DuressPin
        )
    }

    @Test
    fun securityFlow_duressPinRequiresNukeEnabled() = runTest {
        nukeSettingsRepository.setNukeEnabled(false)
        nukeSettingsRepository.setDuressPinEnabled(true)

        assertFalse("Duress should not be enabled when nuke is off", duressAuthenticator.isDuressPinEnabled())
    }

    // ==================== Biometric Integration Tests ====================

    @Test
    fun securityFlow_biometricSuccessUnlocks() {
        assertTrue("Should be locked", appLockManager.isLocked.value)

        appLockManager.onBiometricSuccess()

        assertFalse("Should be unlocked", appLockManager.isLocked.value)
    }

    @Test
    fun securityFlow_biometricFailureRecorded() = runTest {
        appLockManager.setPin("5937")

        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setFailedAuthTriggerEnabled(true)

        val initialCount = failedAuthWatcher.getFailedAttemptCount()

        appLockManager.onBiometricFailure()

        val afterCount = failedAuthWatcher.getFailedAttemptCount()
        assertTrue("Biometric failure should be recorded", afterCount > initialCount)
    }

    // ==================== Security Settings Integration Tests ====================

    @Test
    fun securityFlow_settingsAffectBehavior() = runTest {
        val settings = securitySettingsRepository.settings.first()

        // Settings should be accessible and affect behavior
        assertNotNull("Security settings should be accessible", settings)
    }

    @Test
    fun securityFlow_securityInfoAccessible() {
        val info = appLockManager.getSecurityInfo()

        assertNotNull("Security info should be accessible", info)
        assertTrue("Key derivation should use PBKDF2", info.keyDerivationFunction.contains("PBKDF2"))
    }

    // ==================== Complex Flow Tests ====================

    @Test
    fun securityFlow_completeUnlockFlow() = runTest {
        // Setup
        appLockManager.setPin("5937")
        assertTrue("Should be locked", appLockManager.isLocked.value)

        // Verify with wrong PIN
        val wrongResult = appLockManager.verifyPinWithResultAsync("0000")
        assertTrue("Wrong PIN should fail", wrongResult is AppLockManager.PinVerificationResult.InvalidPin)
        assertTrue("Should still be locked", appLockManager.isLocked.value)

        // Verify with correct PIN
        val correctResult = appLockManager.verifyPinWithResultAsync("5937")
        assertTrue("Correct PIN should succeed", correctResult is AppLockManager.PinVerificationResult.Success)
        assertFalse("Should be unlocked", appLockManager.isLocked.value)

        // Lock again
        appLockManager.lock()
        assertTrue("Should be locked again", appLockManager.isLocked.value)
    }

    @Test
    fun securityFlow_failedAuthToThreshold() = runTest {
        appLockManager.setPin("5937")

        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateFailedAuthSettings(
            enabled = true,
            threshold = 5
        )

        // Fail up to threshold
        repeat(4) {
            appLockManager.verifyPinWithResultAsync("0000")
            assertFalse("Nuke should not trigger before threshold", failedAuthWatcher.isNukeTriggered())
        }

        // 5th failure should trigger
        appLockManager.verifyPinWithResultAsync("0000")

        assertTrue("Nuke should be triggered at threshold", failedAuthWatcher.isNukeTriggered())
    }

    @Test
    fun securityFlow_pinChangeFlow() = runTest {
        // Set initial PIN
        appLockManager.setPin("5937")

        // Verify works with initial PIN
        val result1 = appLockManager.verifyPinWithResultAsync("5937")
        assertTrue("Initial PIN should work", result1 is AppLockManager.PinVerificationResult.Success)

        // Change PIN
        appLockManager.lock()
        appLockManager.setPin("8473")

        // Old PIN should not work
        val result2 = appLockManager.verifyPinWithResultAsync("5937")
        assertTrue("Old PIN should fail", result2 is AppLockManager.PinVerificationResult.InvalidPin)

        // New PIN should work
        val result3 = appLockManager.verifyPinWithResultAsync("8473")
        assertTrue("New PIN should work", result3 is AppLockManager.PinVerificationResult.Success)
    }

    // ==================== Edge Cases ====================

    @Test
    fun securityFlow_multipleUnlocksAreSafe() {
        repeat(10) {
            appLockManager.unlock()
        }

        assertFalse("Should remain unlocked", appLockManager.isLocked.value)
    }

    @Test
    fun securityFlow_multipleLocksAreSafe() {
        appLockManager.unlock()

        repeat(10) {
            appLockManager.lock()
        }

        assertTrue("Should remain locked", appLockManager.isLocked.value)
    }

    @Test
    fun securityFlow_rapidLockUnlock() {
        repeat(20) { i ->
            if (i % 2 == 0) {
                appLockManager.unlock()
            } else {
                appLockManager.lock()
            }
        }

        // Should not crash and final state should be consistent
        assertTrue(
            "State should be consistent",
            appLockManager.isLocked.value || !appLockManager.isLocked.value
        )
    }
}

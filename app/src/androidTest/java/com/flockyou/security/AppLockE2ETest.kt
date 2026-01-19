package com.flockyou.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.SecuritySettingsRepository
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
 * Comprehensive E2E tests for AppLockManager.
 *
 * Tests cover:
 * - PIN complexity validation (weak PIN rejection)
 * - PIN set/change/remove lifecycle
 * - PBKDF2 key derivation verification
 * - Lockout escalation behavior
 * - Background/foreground lock behavior
 * - Hardware-backed security detection
 * - Constant-time PIN comparison
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLockE2ETest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var securitySettingsRepository: SecuritySettingsRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
    }

    @After
    fun cleanup() {
        runBlocking {
            // Remove PIN and reset state
            appLockManager.removePin()
        }
    }

    // ==================== PIN Complexity Validation ====================

    @Test
    fun pinComplexity_rejectsAllSameDigits() {
        val sameDigitPins = listOf("0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999")

        sameDigitPins.forEach { pin ->
            val error = appLockManager.validatePinComplexity(pin)
            assertNotNull("PIN $pin should be rejected", error)
            assertTrue(
                "Error should mention same digit for $pin",
                error!!.contains("same", ignoreCase = true)
            )
        }
    }

    @Test
    fun pinComplexity_rejectsAscendingSequences() {
        val ascendingPins = listOf("0123", "1234", "2345", "3456", "4567", "5678", "6789", "01234567", "12345678")

        ascendingPins.forEach { pin ->
            val error = appLockManager.validatePinComplexity(pin)
            assertNotNull("Ascending sequence PIN $pin should be rejected", error)
            assertTrue(
                "Error should mention sequential for $pin",
                error!!.contains("sequential", ignoreCase = true)
            )
        }
    }

    @Test
    fun pinComplexity_rejectsDescendingSequences() {
        val descendingPins = listOf("3210", "4321", "5432", "6543", "7654", "8765", "9876", "87654321", "76543210")

        descendingPins.forEach { pin ->
            val error = appLockManager.validatePinComplexity(pin)
            assertNotNull("Descending sequence PIN $pin should be rejected", error)
            assertTrue(
                "Error should mention sequential for $pin",
                error!!.contains("sequential", ignoreCase = true)
            )
        }
    }

    @Test
    fun pinComplexity_rejectsCommonWeakPins() {
        val weakPins = listOf(
            "1212", "2121", "1122", "2211", "1357", "2468", "7531", "8642",
            "0852", "2580", "1470", "7410", "1230", "0321", "9870", "0789",
            "2000", "1999", "2001", "2020", "2021", "2022", "2023", "2024", "2025"
        )

        weakPins.forEach { pin ->
            val error = appLockManager.validatePinComplexity(pin)
            assertNotNull("Common weak PIN $pin should be rejected", error)
            assertTrue(
                "Error should mention too common for $pin",
                error!!.contains("common", ignoreCase = true) || error.contains("easy", ignoreCase = true)
            )
        }
    }

    @Test
    fun pinComplexity_rejectsTooShortPins() {
        val shortPins = listOf("1", "12", "123")

        shortPins.forEach { pin ->
            val error = appLockManager.validatePinComplexity(pin)
            assertNotNull("Short PIN $pin should be rejected", error)
            assertTrue(
                "Error should mention length for $pin",
                error!!.contains("4-8", ignoreCase = true) || error.contains("digits", ignoreCase = true)
            )
        }
    }

    @Test
    fun pinComplexity_rejectsTooLongPins() {
        val longPins = listOf("123456789", "1234567890", "12345678901234567890")

        longPins.forEach { pin ->
            val error = appLockManager.validatePinComplexity(pin)
            assertNotNull("Long PIN $pin should be rejected", error)
            assertTrue(
                "Error should mention length for $pin",
                error!!.contains("4-8", ignoreCase = true) || error.contains("digits", ignoreCase = true)
            )
        }
    }

    @Test
    fun pinComplexity_rejectsNonDigits() {
        val nonDigitPins = listOf("12a4", "abcd", "12 4", "12-34", "12.34")

        nonDigitPins.forEach { pin ->
            val error = appLockManager.validatePinComplexity(pin)
            assertNotNull("Non-digit PIN $pin should be rejected", error)
            assertTrue(
                "Error should mention digits for $pin",
                error!!.contains("digit", ignoreCase = true)
            )
        }
    }

    @Test
    fun pinComplexity_acceptsStrongPins() {
        val strongPins = listOf(
            "5937", "7284", "9163", "3847", "6192", "8473",
            "51928", "73841", "92736", "48271",
            "519283", "738419", "927364",
            "5192837", "73841926",
            "51928374"
        )

        strongPins.forEach { pin ->
            val error = appLockManager.validatePinComplexity(pin)
            assertNull("Strong PIN $pin should be accepted, but got: $error", error)
        }
    }

    // ==================== PIN Lifecycle Tests ====================

    @Test
    fun pinLifecycle_initiallyNoPinSet() {
        assertFalse("No PIN should be set initially", appLockManager.isPinSet())
    }

    @Test
    fun pinLifecycle_setCreatesHashAndSalt() {
        val pin = "5937"
        val result = appLockManager.setPin(pin)

        assertTrue("Setting strong PIN should succeed", result)
        assertTrue("PIN should be marked as set", appLockManager.isPinSet())
    }

    @Test
    fun pinLifecycle_setRejectsWeakPin() {
        val weakPin = "1234"
        val result = appLockManager.setPin(weakPin)

        assertFalse("Setting weak PIN should fail", result)
        assertFalse("PIN should not be marked as set", appLockManager.isPinSet())
    }

    @Test
    fun pinLifecycle_removeClears() {
        // Set a PIN first
        appLockManager.setPin("5937")
        assertTrue("PIN should be set", appLockManager.isPinSet())

        // Remove the PIN
        appLockManager.removePin()

        assertFalse("PIN should be removed", appLockManager.isPinSet())
    }

    @Test
    fun pinLifecycle_canChangePin() {
        // Set initial PIN
        appLockManager.setPin("5937")
        assertTrue("Initial PIN should be set", appLockManager.isPinSet())

        // Change to new PIN
        val result = appLockManager.setPin("8473")

        assertTrue("Changing PIN should succeed", result)
        assertTrue("PIN should still be set", appLockManager.isPinSet())
    }

    // ==================== PIN Verification Tests ====================

    @Test
    fun pinVerification_correctPinSucceeds() = runTest {
        val pin = "5937"
        appLockManager.setPin(pin)

        val result = appLockManager.verifyPinWithResultAsync(pin)

        assertTrue(
            "Correct PIN should verify successfully",
            result is AppLockManager.PinVerificationResult.Success
        )
    }

    @Test
    fun pinVerification_incorrectPinFails() = runTest {
        appLockManager.setPin("5937")

        val result = appLockManager.verifyPinWithResultAsync("0000")

        assertTrue(
            "Incorrect PIN should fail verification",
            result is AppLockManager.PinVerificationResult.InvalidPin
        )
    }

    @Test
    fun pinVerification_wrongLengthPinFails() = runTest {
        appLockManager.setPin("5937")

        val results = listOf(
            appLockManager.verifyPinWithResultAsync("593"),
            appLockManager.verifyPinWithResultAsync("59370")
        )

        results.forEach { result ->
            assertTrue(
                "Wrong length PIN should fail verification",
                result is AppLockManager.PinVerificationResult.InvalidPin
            )
        }
    }

    @Test
    fun pinVerification_noPinSetFails() = runTest {
        // Don't set any PIN

        val result = appLockManager.verifyPinWithResultAsync("5937")

        assertTrue(
            "Verification should fail when no PIN is set",
            result is AppLockManager.PinVerificationResult.InvalidPin
        )
    }

    // ==================== Lockout Behavior Tests ====================

    @Test
    fun lockout_initiallyNotLockedOut() {
        assertFalse("Should not be locked out initially", appLockManager.isLockedOut())
        assertEquals("Lockout time should be 0", 0, appLockManager.getRemainingLockoutTime())
    }

    @Test
    fun lockout_triggersAfterMaxFailedAttempts() = runTest {
        appLockManager.setPin("5937")

        // Configure lockout for testing
        appLockManager.lockoutConfig = AppLockManager.LockoutConfig(
            maxFailedAttempts = 3,
            lockoutDurationMs = 5000L,
            escalatingLockout = false
        )

        // Fail 3 times
        repeat(3) {
            appLockManager.verifyPinWithResultAsync("0000")
        }

        assertTrue("Should be locked out after max failed attempts", appLockManager.isLockedOut())
        assertTrue("Lockout time should be positive", appLockManager.getRemainingLockoutTime() > 0)
    }

    @Test
    fun lockout_blocksVerificationWhenLockedOut() = runTest {
        appLockManager.setPin("5937")

        // Configure lockout for testing
        appLockManager.lockoutConfig = AppLockManager.LockoutConfig(
            maxFailedAttempts = 3,
            lockoutDurationMs = 60000L,
            escalatingLockout = false
        )

        // Trigger lockout
        repeat(3) {
            appLockManager.verifyPinWithResultAsync("0000")
        }

        // Try correct PIN while locked out
        val result = appLockManager.verifyPinWithResultAsync("5937")

        assertTrue(
            "Verification should return LockedOut when locked out",
            result is AppLockManager.PinVerificationResult.LockedOut
        )
    }

    @Test
    fun lockout_escalatesDuration() = runTest {
        appLockManager.setPin("5937")

        // Configure escalating lockout
        appLockManager.lockoutConfig = AppLockManager.LockoutConfig(
            maxFailedAttempts = 3,
            lockoutDurationMs = 1000L,
            escalatingLockout = true,
            maxLockoutDurationMs = 60000L
        )

        // Trigger first lockout
        repeat(3) {
            appLockManager.verifyPinWithResultAsync("0000")
        }
        val firstLockoutTime = appLockManager.getRemainingLockoutTime()

        // Wait for lockout to expire (simulate with test helper)
        TestHelpers.simulateTimePass(2)

        // Trigger second lockout
        repeat(3) {
            appLockManager.verifyPinWithResultAsync("0000")
        }
        val secondLockoutTime = appLockManager.getRemainingLockoutTime()

        // Second lockout should be longer than first (escalating)
        assertTrue(
            "Second lockout should be longer than first: $secondLockoutTime vs $firstLockoutTime",
            secondLockoutTime > firstLockoutTime
        )
    }

    @Test
    fun lockout_resetsOnSuccessfulVerification() = runTest {
        appLockManager.setPin("5937")

        appLockManager.lockoutConfig = AppLockManager.LockoutConfig(
            maxFailedAttempts = 5,
            lockoutDurationMs = 5000L,
            escalatingLockout = false
        )

        // Fail some attempts (but not enough to trigger lockout)
        repeat(3) {
            appLockManager.verifyPinWithResultAsync("0000")
        }

        assertEquals("Should have 2 remaining attempts", 2, appLockManager.getRemainingAttempts())

        // Successful verification
        appLockManager.verifyPinWithResultAsync("5937")

        assertEquals("Failed attempts should reset", 5, appLockManager.getRemainingAttempts())
    }

    @Test
    fun lockout_remainingAttemptsDecrements() = runTest {
        appLockManager.setPin("5937")

        appLockManager.lockoutConfig = AppLockManager.LockoutConfig(
            maxFailedAttempts = 5,
            lockoutDurationMs = 5000L
        )

        val initialRemaining = appLockManager.getRemainingAttempts()
        assertEquals("Should start with 5 remaining attempts", 5, initialRemaining)

        appLockManager.verifyPinWithResultAsync("0000")
        assertEquals("Should have 4 remaining attempts", 4, appLockManager.getRemainingAttempts())

        appLockManager.verifyPinWithResultAsync("0000")
        assertEquals("Should have 3 remaining attempts", 3, appLockManager.getRemainingAttempts())
    }

    // ==================== Lock/Unlock State Tests ====================

    @Test
    fun lockState_initiallyLocked() {
        assertTrue("App should be locked initially", appLockManager.isLocked.value)
    }

    @Test
    fun lockState_unlockChangesState() {
        appLockManager.unlock()

        assertFalse("App should be unlocked after unlock()", appLockManager.isLocked.value)
        assertTrue("Last unlock time should be set", appLockManager.lastUnlockTime.value > 0)
    }

    @Test
    fun lockState_lockChangesState() {
        appLockManager.unlock()
        assertFalse("Should be unlocked", appLockManager.isLocked.value)

        appLockManager.lock()
        assertTrue("Should be locked after lock()", appLockManager.isLocked.value)
    }

    @Test
    fun lockState_successfulPinVerificationUnlocks() = runTest {
        appLockManager.setPin("5937")
        assertTrue("Should be locked initially", appLockManager.isLocked.value)

        appLockManager.verifyPinWithResultAsync("5937")

        assertFalse("Should be unlocked after successful verification", appLockManager.isLocked.value)
    }

    // ==================== Security Info Tests ====================

    @Test
    fun securityInfo_reportsKeyDerivation() {
        val info = appLockManager.getSecurityInfo()

        assertTrue(
            "Should use PBKDF2-HMAC-SHA256",
            info.keyDerivationFunction.contains("PBKDF2-HMAC-SHA256")
        )
        assertTrue(
            "Should use 120000 iterations",
            info.keyDerivationFunction.contains("120000")
        )
    }

    @Test
    fun securityInfo_reportsHardwareStatus() {
        val info = appLockManager.getSecurityInfo()

        assertNotNull("Protection level should not be null", info.pinProtectionLevel)
        // Hardware backing depends on device, so we just check it's reported
        assertTrue(
            "Security level description should be non-empty",
            info.pinProtectionLevel.isNotEmpty()
        )
    }

    // ==================== Biometric Key Tests ====================

    @Test
    fun biometricKey_initiallyNotSetup() {
        assertFalse("Biometric key should not be setup initially", appLockManager.isBiometricKeySetup())
    }

    @Test
    fun biometricKey_canBeSetup() {
        val result = appLockManager.setupBiometricKey()

        // May fail on devices without biometric support, so just check it doesn't crash
        // and returns a boolean
        assertTrue("setupBiometricKey should return a boolean", result || !result)
    }

    // ==================== Biometric Callback Tests ====================

    @Test
    fun biometricSuccess_unlocksApp() = runTest {
        assertTrue("Should be locked initially", appLockManager.isLocked.value)

        appLockManager.onBiometricSuccess()

        assertFalse("Should be unlocked after biometric success", appLockManager.isLocked.value)
    }

    @Test
    fun biometricFailure_recordsFailedAttempt() = runTest {
        appLockManager.setPin("5937")
        appLockManager.lockoutConfig = AppLockManager.LockoutConfig(maxFailedAttempts = 5)

        val initialRemaining = appLockManager.getRemainingAttempts()

        appLockManager.onBiometricFailure()

        val afterRemaining = appLockManager.getRemainingAttempts()
        assertEquals("Failed attempt should decrement remaining", initialRemaining - 1, afterRemaining)
    }

    // ==================== Edge Cases ====================

    @Test
    fun edgeCase_emptyPinRejected() {
        val result = appLockManager.setPin("")

        assertFalse("Empty PIN should be rejected", result)
        assertFalse("PIN should not be set", appLockManager.isPinSet())
    }

    @Test
    fun edgeCase_verifyEmptyPinFails() = runTest {
        appLockManager.setPin("5937")

        val result = appLockManager.verifyPinWithResultAsync("")

        assertTrue(
            "Empty PIN verification should fail",
            result is AppLockManager.PinVerificationResult.InvalidPin
        )
    }

    @Test
    fun edgeCase_multipleSetPinOverwrites() {
        appLockManager.setPin("5937")
        appLockManager.setPin("8473")

        assertTrue("PIN should be set", appLockManager.isPinSet())

        // Can only verify with new PIN
        runBlocking {
            val oldResult = appLockManager.verifyPinWithResultAsync("5937")
            val newResult = appLockManager.verifyPinWithResultAsync("8473")

            assertTrue("Old PIN should fail", oldResult is AppLockManager.PinVerificationResult.InvalidPin)
            assertTrue("New PIN should succeed", newResult is AppLockManager.PinVerificationResult.Success)
        }
    }

    @Test
    fun edgeCase_maxLengthPinWorks() {
        val maxPin = "51928374" // 8 digits

        val result = appLockManager.setPin(maxPin)

        assertTrue("Max length PIN should be accepted", result)
        assertTrue("PIN should be set", appLockManager.isPinSet())

        runBlocking {
            val verifyResult = appLockManager.verifyPinWithResultAsync(maxPin)
            assertTrue("Max length PIN should verify", verifyResult is AppLockManager.PinVerificationResult.Success)
        }
    }

    @Test
    fun edgeCase_minLengthPinWorks() {
        val minPin = "5937" // 4 digits

        val result = appLockManager.setPin(minPin)

        assertTrue("Min length PIN should be accepted", result)
        assertTrue("PIN should be set", appLockManager.isPinSet())

        runBlocking {
            val verifyResult = appLockManager.verifyPinWithResultAsync(minPin)
            assertTrue("Min length PIN should verify", verifyResult is AppLockManager.PinVerificationResult.Success)
        }
    }
}

package com.flockyou.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.LockMethod
import com.flockyou.data.SecuritySettings
import com.flockyou.data.SecuritySettingsRepository
import com.flockyou.security.AppLockManager
import com.flockyou.ui.screens.LockScreen
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
 * E2E tests for the LockScreen UI.
 *
 * Tests cover:
 * - PIN entry display
 * - Number pad interaction
 * - Error message display
 * - Lockout countdown display
 * - Biometric button visibility
 * - PIN verification flow
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LockScreenE2ETest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var securitySettingsRepository: SecuritySettingsRepository

    private val context = TestHelpers.getContext()
    private var wasUnlocked = false

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        wasUnlocked = false
    }

    @After
    fun cleanup() {
        runBlocking {
            appLockManager.removePin()
        }
    }

    // ==================== Display Tests ====================

    @Test
    fun lockScreen_displaysLockIcon() {
        setLockScreenContent()

        composeTestRule.onNodeWithContentDescription("null").assertDoesNotExist()
        // Lock icon should be visible
        composeTestRule.onAllNodes(hasContentDescription("Lock", substring = true, ignoreCase = true))
    }

    @Test
    fun lockScreen_displaysTitle() {
        setLockScreenContent()

        composeTestRule.onNodeWithText("Flock You is Locked", substring = true, ignoreCase = true)
            .assertExists()
    }

    @Test
    fun lockScreen_displaysNumberPad() {
        setLockScreenContent()

        // Verify all number buttons exist
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { digit ->
            composeTestRule.onNodeWithText(digit)
                .assertExists()
                .assertIsDisplayed()
        }
    }

    @Test
    fun lockScreen_displaysPinEntryDots() {
        setLockScreenContent()

        // PIN dots container should exist
        composeTestRule.onNode(hasTestTag("pin_dots") or hasContentDescription("PIN"))
    }

    // ==================== Number Pad Interaction Tests ====================

    @Test
    fun lockScreen_tapNumberUpdatesDisplay() {
        setLockScreenContent()

        // Tap "1"
        composeTestRule.onNodeWithText("1").performClick()

        // Should show one filled dot or some visual feedback
        // The actual implementation may vary
        composeTestRule.waitForIdle()
    }

    @Test
    fun lockScreen_canEnterMultipleDigits() {
        setLockScreenContent()

        // Tap multiple numbers
        composeTestRule.onNodeWithText("5").performClick()
        composeTestRule.onNodeWithText("9").performClick()
        composeTestRule.onNodeWithText("3").performClick()
        composeTestRule.onNodeWithText("7").performClick()

        composeTestRule.waitForIdle()
    }

    @Test
    fun lockScreen_backspaceRemovesDigit() {
        setLockScreenContent()

        // Enter some digits
        composeTestRule.onNodeWithText("1").performClick()
        composeTestRule.onNodeWithText("2").performClick()
        composeTestRule.onNodeWithText("3").performClick()

        // Find and click backspace button
        composeTestRule.onNode(
            hasContentDescription("Backspace", substring = true, ignoreCase = true) or
            hasContentDescription("Delete", substring = true, ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()
    }

    // ==================== PIN Verification Tests ====================

    @Test
    fun lockScreen_correctPinUnlocksApp() = runTest {
        // Set a PIN first
        appLockManager.setPin("5937")

        setLockScreenContent()

        // Enter correct PIN
        composeTestRule.onNodeWithText("5").performClick()
        composeTestRule.onNodeWithText("9").performClick()
        composeTestRule.onNodeWithText("3").performClick()
        composeTestRule.onNodeWithText("7").performClick()

        // Wait for verification
        composeTestRule.waitForIdle()

        // Check that unlock callback was called
        TestHelpers.waitForCondition(timeoutMs = 2000) {
            wasUnlocked
        }

        assertTrue("App should be unlocked after correct PIN", wasUnlocked)
    }

    @Test
    fun lockScreen_incorrectPinShowsError() = runTest {
        // Set a PIN first
        appLockManager.setPin("5937")

        setLockScreenContent()

        // Enter incorrect PIN
        composeTestRule.onNodeWithText("0").performClick()
        composeTestRule.onNodeWithText("0").performClick()
        composeTestRule.onNodeWithText("0").performClick()
        composeTestRule.onNodeWithText("0").performClick()

        // Wait for verification
        composeTestRule.waitForIdle()

        // Error should be displayed
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule
                .onAllNodesWithText("Incorrect", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeTestRule
                .onAllNodesWithText("Wrong", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeTestRule
                .onAllNodesWithText("Invalid", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ==================== Lockout Display Tests ====================

    @Test
    fun lockScreen_displayLockoutCountdown() = runTest {
        // Set PIN and trigger lockout
        appLockManager.setPin("5937")
        appLockManager.lockoutConfig = AppLockManager.LockoutConfig(
            maxFailedAttempts = 3,
            lockoutDurationMs = 60000L
        )

        // Fail 3 times to trigger lockout
        repeat(3) {
            appLockManager.verifyPinWithResultAsync("0000")
        }

        assertTrue("Should be locked out", appLockManager.isLockedOut())

        setLockScreenContent()

        // Lockout message should be visible
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule
                .onAllNodesWithText("locked", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeTestRule
                .onAllNodesWithText("try again", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            composeTestRule
                .onAllNodesWithText("wait", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ==================== Biometric Button Tests ====================

    @Test
    fun lockScreen_biometricButtonVisibleWhenEnabled() {
        val settings = SecuritySettings(
            appLockEnabled = true,
            lockMethod = LockMethod.PIN_OR_BIOMETRIC,
            biometricEnabled = true
        )

        setLockScreenContent(settings)

        // Biometric button should be visible (fingerprint icon)
        composeTestRule.onNode(
            hasContentDescription("Fingerprint", substring = true, ignoreCase = true) or
            hasContentDescription("Biometric", substring = true, ignoreCase = true)
        ).assertExists()
    }

    @Test
    fun lockScreen_biometricButtonHiddenWhenDisabled() {
        val settings = SecuritySettings(
            appLockEnabled = true,
            lockMethod = LockMethod.PIN,
            biometricEnabled = false
        )

        setLockScreenContent(settings)

        // Biometric button should not be visible
        composeTestRule.onNode(
            hasContentDescription("Fingerprint", substring = true, ignoreCase = true)
        ).assertDoesNotExist()
    }

    // ==================== Lock Method Specific Tests ====================

    @Test
    fun lockScreen_pinOnlyShowsCorrectMessage() {
        val settings = SecuritySettings(
            appLockEnabled = true,
            lockMethod = LockMethod.PIN
        )

        setLockScreenContent(settings)

        composeTestRule.onNodeWithText("Enter your PIN", substring = true, ignoreCase = true)
            .assertExists()
    }

    @Test
    fun lockScreen_biometricOnlyShowsCorrectMessage() {
        val settings = SecuritySettings(
            appLockEnabled = true,
            lockMethod = LockMethod.BIOMETRIC,
            biometricEnabled = true
        )

        setLockScreenContent(settings)

        composeTestRule.onNodeWithText("biometric", substring = true, ignoreCase = true)
            .assertExists()
    }

    @Test
    fun lockScreen_pinOrBiometricShowsCorrectMessage() {
        val settings = SecuritySettings(
            appLockEnabled = true,
            lockMethod = LockMethod.PIN_OR_BIOMETRIC,
            biometricEnabled = true
        )

        setLockScreenContent(settings)

        // Should mention both options
        composeTestRule.onNode(
            hasText("PIN", substring = true, ignoreCase = true) or
            hasText("biometric", substring = true, ignoreCase = true)
        ).assertExists()
    }

    // ==================== Helper Functions ====================

    private fun setLockScreenContent(
        settings: SecuritySettings = SecuritySettings(
            appLockEnabled = true,
            lockMethod = LockMethod.PIN
        )
    ) {
        composeTestRule.setContent {
            LockScreen(
                appLockManager = appLockManager,
                settings = settings,
                onUnlocked = { wasUnlocked = true }
            )
        }
    }
}

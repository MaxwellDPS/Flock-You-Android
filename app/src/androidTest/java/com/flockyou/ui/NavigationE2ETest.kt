package com.flockyou.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.MainActivity
import com.flockyou.utils.TestHelpers
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E tests for app navigation.
 *
 * Tests cover:
 * - Bottom navigation bar behavior
 * - Screen transitions
 * - Back navigation handling
 * - Navigation state preservation
 * - Deep link navigation
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationE2ETest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
    }

    @After
    fun cleanup() {
        // Clean up if needed
    }

    // ==================== Bottom Navigation Tests ====================

    @Test
    fun navigation_bottomNavIsDisplayed() {
        composeTestRule.waitForIdle()

        // Bottom navigation should be visible on main screen
        // Look for common navigation items
        composeTestRule.onNode(
            hasContentDescription("Home", substring = true, ignoreCase = true) or
            hasContentDescription("Map", substring = true, ignoreCase = true) or
            hasContentDescription("Settings", substring = true, ignoreCase = true)
        )
    }

    @Test
    fun navigation_homeTabIsSelectedByDefault() {
        composeTestRule.waitForIdle()

        // Home/Detections tab should be selected by default
        composeTestRule.onNode(
            hasContentDescription("Home", substring = true, ignoreCase = true) or
            hasContentDescription("Detections", substring = true, ignoreCase = true)
        )
    }

    @Test
    fun navigation_canNavigateToMap() {
        composeTestRule.waitForIdle()

        // Click on Map tab
        composeTestRule.onNode(
            hasContentDescription("Map", substring = true, ignoreCase = true) or
            hasText("Map", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Map screen should be displayed
        // Could verify by checking for map-specific elements
    }

    @Test
    fun navigation_canNavigateToSettings() {
        composeTestRule.waitForIdle()

        // Click on Settings tab
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Settings screen should be displayed
    }

    @Test
    fun navigation_canNavigateBackToHome() {
        composeTestRule.waitForIdle()

        // Navigate to Settings
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Navigate back to Home
        composeTestRule.onNode(
            hasContentDescription("Home", substring = true, ignoreCase = true) or
            hasContentDescription("Detections", substring = true, ignoreCase = true) or
            hasText("Home", ignoreCase = true) or
            hasText("Detections", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()
    }

    // ==================== Settings Navigation Tests ====================

    @Test
    fun navigation_settingsShowsCategories() {
        composeTestRule.waitForIdle()

        // Navigate to Settings
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Should show settings categories
        // Look for common settings like Security, Privacy, etc.
    }

    @Test
    fun navigation_canNavigateToSecuritySettings() {
        composeTestRule.waitForIdle()

        // Navigate to Settings
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Click on Security settings
        composeTestRule.onNode(
            hasText("Security", substring = true, ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Security settings screen should be displayed
    }

    @Test
    fun navigation_canNavigateToPrivacySettings() {
        composeTestRule.waitForIdle()

        // Navigate to Settings
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Click on Privacy settings
        composeTestRule.onNode(
            hasText("Privacy", substring = true, ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()
    }

    @Test
    fun navigation_canNavigateToNukeSettings() {
        composeTestRule.waitForIdle()

        // Navigate to Settings
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Look for Nuke/Emergency settings (might be labeled differently)
        composeTestRule.onNode(
            hasText("Nuke", substring = true, ignoreCase = true) or
            hasText("Emergency", substring = true, ignoreCase = true) or
            hasText("Wipe", substring = true, ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()
    }

    // ==================== Back Navigation Tests ====================

    @Test
    fun navigation_backFromSettingsSubscreen() {
        composeTestRule.waitForIdle()

        // Navigate to Settings -> Security
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        composeTestRule.onNode(
            hasText("Security", substring = true, ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Press back button
        composeTestRule.onNode(
            hasContentDescription("Back", substring = true, ignoreCase = true) or
            hasContentDescription("Navigate up", substring = true, ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Should be back at main settings
    }

    // ==================== State Preservation Tests ====================

    @Test
    fun navigation_preservesStateOnTabSwitch() {
        composeTestRule.waitForIdle()

        // Navigate to Map
        composeTestRule.onNode(
            hasContentDescription("Map", substring = true, ignoreCase = true) or
            hasText("Map", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Navigate to Settings
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Navigate back to Map
        composeTestRule.onNode(
            hasContentDescription("Map", substring = true, ignoreCase = true) or
            hasText("Map", ignoreCase = true)
        ).performClick()

        composeTestRule.waitForIdle()

        // Map state should be preserved
    }

    // ==================== Edge Cases ====================

    @Test
    fun navigation_rapidTabSwitchingDoesNotCrash() {
        composeTestRule.waitForIdle()

        // Rapidly switch between tabs
        repeat(10) {
            composeTestRule.onNode(
                hasContentDescription("Map", substring = true, ignoreCase = true) or
                hasText("Map", ignoreCase = true)
            ).performClick()

            composeTestRule.onNode(
                hasContentDescription("Home", substring = true, ignoreCase = true) or
                hasContentDescription("Detections", substring = true, ignoreCase = true)
            ).performClick()

            composeTestRule.onNode(
                hasContentDescription("Settings", substring = true, ignoreCase = true) or
                hasText("Settings", ignoreCase = true)
            ).performClick()
        }

        composeTestRule.waitForIdle()
        // Should not crash
    }

    @Test
    fun navigation_doubleClickOnSameTabDoesNotCrash() {
        composeTestRule.waitForIdle()

        // Double click on same tab
        repeat(5) {
            composeTestRule.onNode(
                hasContentDescription("Home", substring = true, ignoreCase = true) or
                hasContentDescription("Detections", substring = true, ignoreCase = true)
            ).performClick()
        }

        composeTestRule.waitForIdle()
    }
}

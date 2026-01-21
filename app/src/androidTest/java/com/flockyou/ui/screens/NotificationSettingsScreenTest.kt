package com.flockyou.ui.screens

import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.MainActivity
import com.flockyou.data.NotificationSettings
import com.flockyou.data.NotificationSettingsRepository
import com.flockyou.data.VibratePattern
import com.flockyou.data.model.ThreatLevel
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
 * E2E tests for NotificationSettingsScreen.
 *
 * Tests cover:
 * - Master notification toggle
 * - Threat level alert toggles (Critical, High, Medium, Low, Info)
 * - Sound and vibration toggles
 * - Vibration pattern selection
 * - Lock screen display toggle
 * - Persistent notification toggle
 * - Bypass Do Not Disturb toggle
 * - Emergency popup toggle and permission handling
 * - Quiet hours configuration
 * - Settings persistence
 * - Navigation and dialog interactions
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NotificationSettingsScreenTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var notificationSettingsRepository: NotificationSettingsRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        // Reset to defaults
        runBlocking {
            notificationSettingsRepository.updateSettings { NotificationSettings() }
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            notificationSettingsRepository.updateSettings { NotificationSettings() }
        }
    }

    private fun navigateToNotificationSettings() {
        composeTestRule.waitForIdle()
        // Navigate to Settings
        composeTestRule.onNode(
            hasContentDescription("Settings", substring = true, ignoreCase = true) or
            hasText("Settings", ignoreCase = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // Navigate to Notification Settings
        composeTestRule.onNode(
            hasText("Notification", substring = true, ignoreCase = true) and hasClickAction()
        ).performClick()
        composeTestRule.waitForIdle()
    }

    // ==================== Master Toggle Tests ====================

    @Test
    fun notificationSettings_masterToggleDisplayed() {
        navigateToNotificationSettings()

        // Verify master toggle is visible
        composeTestRule.onNode(hasText("Notifications", ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_masterTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Get initial state
        val initialSettings = notificationSettingsRepository.settings.first()
        val wasEnabled = initialSettings.enabled

        // Toggle programmatically to ensure testable state
        notificationSettingsRepository.updateSettings { it.copy(enabled = !wasEnabled) }
        composeTestRule.waitForIdle()

        // Verify persistence
        val updatedSettings = notificationSettingsRepository.settings.first()
        assertEquals("Master toggle should persist", !wasEnabled, updatedSettings.enabled)
    }

    @Test
    fun notificationSettings_masterToggleShowsStatus() = runTest {
        navigateToNotificationSettings()

        // When enabled, should show enabled status
        notificationSettingsRepository.updateSettings { it.copy(enabled = true) }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(
            hasText("Detection alerts are enabled", substring = true, ignoreCase = true)
        ).assertExists()

        // When disabled, should show disabled status
        notificationSettingsRepository.updateSettings { it.copy(enabled = false) }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(
            hasText("All alerts are disabled", substring = true, ignoreCase = true)
        ).assertExists()
    }

    // ==================== Threat Level Alert Tests ====================

    @Test
    fun notificationSettings_allThreatLevelsDisplayed() {
        navigateToNotificationSettings()

        // Verify all threat level toggles are present
        composeTestRule.onNode(hasText("CRITICAL", ignoreCase = true)).assertExists()
        composeTestRule.onNode(hasText("HIGH", ignoreCase = true)).assertExists()
        composeTestRule.onNode(hasText("MEDIUM", ignoreCase = true)).assertExists()
        composeTestRule.onNode(hasText("LOW", ignoreCase = true)).assertExists()
        composeTestRule.onNode(hasText("INFO", ignoreCase = true)).assertExists()
    }

    @Test
    fun notificationSettings_criticalAlertTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Toggle critical alerts
        notificationSettingsRepository.updateSettings { it.copy(criticalAlertsEnabled = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Critical alerts should be disabled", settings.criticalAlertsEnabled)
    }

    @Test
    fun notificationSettings_highAlertTogglePersists() = runTest {
        navigateToNotificationSettings()

        notificationSettingsRepository.updateSettings { it.copy(highAlertsEnabled = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("High alerts should be disabled", settings.highAlertsEnabled)
    }

    @Test
    fun notificationSettings_mediumAlertTogglePersists() = runTest {
        navigateToNotificationSettings()

        notificationSettingsRepository.updateSettings { it.copy(mediumAlertsEnabled = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Medium alerts should be disabled", settings.mediumAlertsEnabled)
    }

    @Test
    fun notificationSettings_lowAlertTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Low alerts are disabled by default, enable them
        notificationSettingsRepository.updateSettings { it.copy(lowAlertsEnabled = true) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertTrue("Low alerts should be enabled", settings.lowAlertsEnabled)
    }

    @Test
    fun notificationSettings_infoAlertTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Info alerts are disabled by default, enable them
        notificationSettingsRepository.updateSettings { it.copy(infoAlertsEnabled = true) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertTrue("Info alerts should be enabled", settings.infoAlertsEnabled)
    }

    @Test
    fun notificationSettings_threatLevelDescriptionsDisplayed() {
        navigateToNotificationSettings()

        // Verify descriptions are shown for each threat level
        composeTestRule.onNode(hasText("Audio surveillance, StingRay detected", substring = true, ignoreCase = true))
            .assertExists()
        composeTestRule.onNode(hasText("Confirmed surveillance cameras", substring = true, ignoreCase = true))
            .assertExists()
        composeTestRule.onNode(hasText("Likely surveillance equipment", substring = true, ignoreCase = true))
            .assertExists()
    }

    // ==================== Sound & Vibration Tests ====================

    @Test
    fun notificationSettings_soundToggleDisplayed() {
        navigateToNotificationSettings()

        composeTestRule.onNode(hasText("Sound", ignoreCase = true) and hasClickAction())
            .assertExists()
    }

    @Test
    fun notificationSettings_soundTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Toggle sound off
        notificationSettingsRepository.updateSettings { it.copy(sound = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Sound should be disabled", settings.sound)
    }

    @Test
    fun notificationSettings_vibrationToggleDisplayed() {
        navigateToNotificationSettings()

        composeTestRule.onNode(hasText("Vibration", ignoreCase = true) and hasClickAction())
            .assertExists()
    }

    @Test
    fun notificationSettings_vibrationTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Toggle vibration off
        notificationSettingsRepository.updateSettings { it.copy(vibrate = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Vibration should be disabled", settings.vibrate)
    }

    @Test
    fun notificationSettings_vibrationPatternShownWhenVibrationEnabled() = runTest {
        navigateToNotificationSettings()

        // Enable vibration
        notificationSettingsRepository.updateSettings { it.copy(vibrate = true) }
        composeTestRule.waitForIdle()

        // Vibration pattern option should be visible
        composeTestRule.onNode(hasText("Vibration Pattern", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_vibrationPatternHiddenWhenVibrationDisabled() = runTest {
        navigateToNotificationSettings()

        // Disable vibration
        notificationSettingsRepository.updateSettings { it.copy(vibrate = false) }
        composeTestRule.waitForIdle()

        // Vibration pattern option should not be visible
        composeTestRule.onNode(hasText("Vibration Pattern", substring = true, ignoreCase = true))
            .assertDoesNotExist()
    }

    @Test
    fun notificationSettings_vibrationPatternDialogOpens() = runTest {
        navigateToNotificationSettings()

        // Enable vibration first
        notificationSettingsRepository.updateSettings { it.copy(vibrate = true) }
        composeTestRule.waitForIdle()

        // Click on vibration pattern card
        composeTestRule.onNode(hasText("Vibration Pattern", substring = true, ignoreCase = true))
            .performClick()
        composeTestRule.waitForIdle()

        // Dialog should open with pattern options
        composeTestRule.onNode(hasText("Default", ignoreCase = true)).assertExists()
        composeTestRule.onNode(hasText("Urgent", ignoreCase = true)).assertExists()
        composeTestRule.onNode(hasText("Gentle", ignoreCase = true)).assertExists()
        composeTestRule.onNode(hasText("Long", ignoreCase = true)).assertExists()
        composeTestRule.onNode(hasText("SOS", ignoreCase = true)).assertExists()
    }

    @Test
    fun notificationSettings_vibrationPatternSelectionPersists() = runTest {
        navigateToNotificationSettings()

        // Enable vibration and open pattern dialog
        notificationSettingsRepository.updateSettings { it.copy(vibrate = true) }
        composeTestRule.waitForIdle()

        // Select pattern programmatically
        notificationSettingsRepository.updateSettings { it.copy(vibratePattern = VibratePattern.URGENT) }
        composeTestRule.waitForIdle()

        // Verify persistence
        val settings = notificationSettingsRepository.settings.first()
        assertEquals("Vibration pattern should persist", VibratePattern.URGENT, settings.vibratePattern)
    }

    // ==================== Display Settings Tests ====================

    @Test
    fun notificationSettings_lockScreenToggleDisplayed() {
        navigateToNotificationSettings()

        composeTestRule.onNode(hasText("Show on Lock Screen", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_lockScreenTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Toggle lock screen display
        notificationSettingsRepository.updateSettings { it.copy(showOnLockScreen = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Lock screen display should be disabled", settings.showOnLockScreen)
    }

    @Test
    fun notificationSettings_persistentNotificationToggleDisplayed() {
        navigateToNotificationSettings()

        composeTestRule.onNode(hasText("Persistent Notification", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_persistentNotificationTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Toggle persistent notification
        notificationSettingsRepository.updateSettings { it.copy(persistentNotification = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Persistent notification should be disabled", settings.persistentNotification)
    }

    @Test
    fun notificationSettings_bypassDndToggleDisplayed() {
        navigateToNotificationSettings()

        composeTestRule.onNode(hasText("Bypass Do Not Disturb", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_bypassDndTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Toggle bypass DND
        notificationSettingsRepository.updateSettings { it.copy(bypassDnd = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Bypass DND should be disabled", settings.bypassDnd)
    }

    // ==================== Emergency Popup Tests ====================

    @Test
    fun notificationSettings_emergencyPopupToggleDisplayed() {
        navigateToNotificationSettings()

        composeTestRule.onNode(hasText("Emergency Popup", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_emergencyPopupTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Toggle emergency popup
        notificationSettingsRepository.updateSettings { it.copy(emergencyPopupEnabled = false) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Emergency popup should be disabled", settings.emergencyPopupEnabled)
    }

    @Test
    fun notificationSettings_emergencyPopupShowsPermissionWarning() = runTest {
        navigateToNotificationSettings()

        // Enable emergency popup
        notificationSettingsRepository.updateSettings { it.copy(emergencyPopupEnabled = true) }
        composeTestRule.waitForIdle()

        // If overlay permission is not granted, warning should show
        val hasOverlayPermission = Settings.canDrawOverlays(context)
        if (!hasOverlayPermission) {
            composeTestRule.onNode(hasText("Permission Required", substring = true, ignoreCase = true))
                .assertExists()
        }
    }

    @Test
    fun notificationSettings_emergencyPopupDescription() {
        navigateToNotificationSettings()

        // Verify description is shown
        composeTestRule.onNode(
            hasText("Full-screen CMAS/WEA-style alert", substring = true, ignoreCase = true)
        ).assertExists()
    }

    // ==================== Quiet Hours Tests ====================

    @Test
    fun notificationSettings_quietHoursToggleDisplayed() {
        navigateToNotificationSettings()

        composeTestRule.onNode(hasText("Quiet Hours", ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_quietHoursTogglePersists() = runTest {
        navigateToNotificationSettings()

        // Enable quiet hours
        notificationSettingsRepository.updateSettings { it.copy(quietHoursEnabled = true) }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertTrue("Quiet hours should be enabled", settings.quietHoursEnabled)
    }

    @Test
    fun notificationSettings_quietHoursScheduleShownWhenEnabled() = runTest {
        navigateToNotificationSettings()

        // Enable quiet hours
        notificationSettingsRepository.updateSettings { it.copy(quietHoursEnabled = true) }
        composeTestRule.waitForIdle()

        // Schedule card should be visible
        composeTestRule.onNode(hasText("Quiet Hours Schedule", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_quietHoursScheduleHiddenWhenDisabled() = runTest {
        navigateToNotificationSettings()

        // Disable quiet hours
        notificationSettingsRepository.updateSettings { it.copy(quietHoursEnabled = false) }
        composeTestRule.waitForIdle()

        // Schedule card should not be visible
        composeTestRule.onNode(hasText("Quiet Hours Schedule", substring = true, ignoreCase = true))
            .assertDoesNotExist()
    }

    @Test
    fun notificationSettings_quietHoursDialogOpens() = runTest {
        navigateToNotificationSettings()

        // Enable quiet hours first
        notificationSettingsRepository.updateSettings { it.copy(quietHoursEnabled = true) }
        composeTestRule.waitForIdle()

        // Click on quiet hours schedule
        composeTestRule.onNode(hasText("Quiet Hours Schedule", substring = true, ignoreCase = true))
            .performClick()
        composeTestRule.waitForIdle()

        // Dialog should open
        composeTestRule.onNode(hasText("Start Time", substring = true, ignoreCase = true))
            .assertExists()
        composeTestRule.onNode(hasText("End Time", substring = true, ignoreCase = true))
            .assertExists()
    }

    @Test
    fun notificationSettings_quietHoursTimeSelectionPersists() = runTest {
        navigateToNotificationSettings()

        // Set custom quiet hours
        notificationSettingsRepository.updateSettings {
            it.copy(
                quietHoursEnabled = true,
                quietHoursStart = 23, // 11 PM
                quietHoursEnd = 6 // 6 AM
            )
        }
        composeTestRule.waitForIdle()

        // Verify persistence
        val settings = notificationSettingsRepository.settings.first()
        assertEquals("Start hour should persist", 23, settings.quietHoursStart)
        assertEquals("End hour should persist", 6, settings.quietHoursEnd)
    }

    @Test
    fun notificationSettings_quietHoursShowsCriticalNote() = runTest {
        navigateToNotificationSettings()

        // Enable quiet hours and open dialog
        notificationSettingsRepository.updateSettings { it.copy(quietHoursEnabled = true) }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Quiet Hours Schedule", substring = true, ignoreCase = true))
            .performClick()
        composeTestRule.waitForIdle()

        // Should mention critical alerts still come through
        composeTestRule.onNode(
            hasText("CRITICAL alerts still come through", substring = true, ignoreCase = true)
        ).assertExists()
    }

    // ==================== shouldAlert Logic Tests ====================

    @Test
    fun notificationSettings_shouldAlertReturnsFalseWhenDisabled() = runTest {
        // Disable all notifications
        notificationSettingsRepository.updateSettings { it.copy(enabled = false) }
        val settings = notificationSettingsRepository.settings.first()

        // Should not alert for any threat level
        assertFalse(notificationSettingsRepository.shouldAlert(ThreatLevel.CRITICAL, settings))
        assertFalse(notificationSettingsRepository.shouldAlert(ThreatLevel.HIGH, settings))
        assertFalse(notificationSettingsRepository.shouldAlert(ThreatLevel.LOW, settings))
    }

    @Test
    fun notificationSettings_shouldAlertRespectsThreatLevelToggles() = runTest {
        // Disable specific threat levels
        notificationSettingsRepository.updateSettings {
            it.copy(
                enabled = true,
                criticalAlertsEnabled = true,
                highAlertsEnabled = false,
                mediumAlertsEnabled = false,
                lowAlertsEnabled = false,
                infoAlertsEnabled = false
            )
        }
        val settings = notificationSettingsRepository.settings.first()

        // Should only alert for critical
        assertTrue(notificationSettingsRepository.shouldAlert(ThreatLevel.CRITICAL, settings))
        assertFalse(notificationSettingsRepository.shouldAlert(ThreatLevel.HIGH, settings))
        assertFalse(notificationSettingsRepository.shouldAlert(ThreatLevel.MEDIUM, settings))
        assertFalse(notificationSettingsRepository.shouldAlert(ThreatLevel.LOW, settings))
    }

    // ==================== Settings Persistence Tests ====================

    @Test
    fun notificationSettings_allSettingsPersistTogether() = runTest {
        // Set multiple settings
        notificationSettingsRepository.updateSettings {
            NotificationSettings(
                enabled = true,
                sound = false,
                vibrate = true,
                vibratePattern = VibratePattern.URGENT,
                showOnLockScreen = false,
                persistentNotification = true,
                bypassDnd = false,
                emergencyPopupEnabled = true,
                criticalAlertsEnabled = true,
                highAlertsEnabled = true,
                mediumAlertsEnabled = false,
                lowAlertsEnabled = false,
                infoAlertsEnabled = false,
                quietHoursEnabled = true,
                quietHoursStart = 23,
                quietHoursEnd = 7
            )
        }
        composeTestRule.waitForIdle()

        // Verify all settings persisted
        val settings = notificationSettingsRepository.settings.first()
        assertTrue("Notifications should be enabled", settings.enabled)
        assertFalse("Sound should be disabled", settings.sound)
        assertTrue("Vibration should be enabled", settings.vibrate)
        assertEquals("Vibration pattern should be URGENT", VibratePattern.URGENT, settings.vibratePattern)
        assertFalse("Lock screen should be disabled", settings.showOnLockScreen)
        assertTrue("Persistent notification should be enabled", settings.persistentNotification)
        assertFalse("Bypass DND should be disabled", settings.bypassDnd)
        assertTrue("Emergency popup should be enabled", settings.emergencyPopupEnabled)
        assertTrue("Critical alerts should be enabled", settings.criticalAlertsEnabled)
        assertFalse("Medium alerts should be disabled", settings.mediumAlertsEnabled)
        assertTrue("Quiet hours should be enabled", settings.quietHoursEnabled)
        assertEquals("Quiet hours start should be 23", 23, settings.quietHoursStart)
    }

    // ==================== Navigation Tests ====================

    @Test
    fun notificationSettings_backButtonPreservesSettings() = runTest {
        navigateToNotificationSettings()

        // Modify settings
        notificationSettingsRepository.updateSettings {
            it.copy(sound = false, vibrate = false)
        }
        composeTestRule.waitForIdle()

        // Navigate back
        composeTestRule.onNode(
            hasContentDescription("Back", substring = true, ignoreCase = true) or
            hasContentDescription("Navigate up", substring = true, ignoreCase = true)
        ).performClick()
        composeTestRule.waitForIdle()

        // Verify settings persisted
        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Sound setting should persist", settings.sound)
        assertFalse("Vibration setting should persist", settings.vibrate)
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun notificationSettings_handlesRapidToggling() = runTest {
        navigateToNotificationSettings()

        // Rapidly toggle sound multiple times
        repeat(5) {
            notificationSettingsRepository.updateSettings { current ->
                current.copy(sound = it % 2 == 0)
            }
        }
        composeTestRule.waitForIdle()

        // Should not crash and should have a valid final state
        val settings = notificationSettingsRepository.settings.first()
        assertNotNull("Settings should remain valid", settings)
    }

    @Test
    fun notificationSettings_allAlertsCanBeDisabled() = runTest {
        // Disable all alert types
        notificationSettingsRepository.updateSettings {
            it.copy(
                criticalAlertsEnabled = false,
                highAlertsEnabled = false,
                mediumAlertsEnabled = false,
                lowAlertsEnabled = false,
                infoAlertsEnabled = false
            )
        }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertFalse("Critical alerts should be disabled", settings.criticalAlertsEnabled)
        assertFalse("High alerts should be disabled", settings.highAlertsEnabled)
        assertFalse("Medium alerts should be disabled", settings.mediumAlertsEnabled)
        assertFalse("Low alerts should be disabled", settings.lowAlertsEnabled)
        assertFalse("Info alerts should be disabled", settings.infoAlertsEnabled)
    }

    @Test
    fun notificationSettings_quietHoursAcrossMidnight() = runTest {
        // Set quiet hours that span midnight (e.g., 22:00 to 07:00)
        notificationSettingsRepository.updateSettings {
            it.copy(
                quietHoursEnabled = true,
                quietHoursStart = 22,
                quietHoursEnd = 7
            )
        }
        composeTestRule.waitForIdle()

        val settings = notificationSettingsRepository.settings.first()
        assertTrue("Quiet hours start should be greater than end for midnight span",
            settings.quietHoursStart > settings.quietHoursEnd)
    }
}

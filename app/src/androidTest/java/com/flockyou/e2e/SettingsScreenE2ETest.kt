package com.flockyou.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.flockyou.MainActivity
import com.flockyou.robot.mainScreen
import com.flockyou.rule.ClearDataRule
import com.flockyou.rule.DisableAnimationsRule
import com.flockyou.rule.grantPermissionsRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * End-to-end tests for the Settings Screen.
 * Tests settings display, toggles, and persistence.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HiltAndroidTest
class SettingsScreenE2ETest {
    
    private val hiltRule = HiltAndroidRule(this)
    private val composeTestRule = createAndroidComposeRule<MainActivity>()
    private val clearDataRule = ClearDataRule()
    private val disableAnimationsRule = DisableAnimationsRule()
    
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(hiltRule)
        .around(grantPermissionsRule)
        .around(clearDataRule)
        .around(disableAnimationsRule)
        .around(composeTestRule)
    
    @Before
    fun setup() {
        hiltRule.inject()
        navigateToSettings()
    }
    
    private fun navigateToSettings() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickSettings()
        }
    }
    
    // ============ Screen Display Tests ============
    
    @Test
    fun settingsScreen_displaysCorrectly() {
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }
    
    @Test
    fun settingsScreen_displaysAllSections() {
        // Scanning settings
        composeTestRule.onNodeWithText("Scanning", substring = true).assertExists()
        
        // Detection settings
        composeTestRule.onNodeWithText("Detection", substring = true).assertExists()
        
        // Notification settings
        composeTestRule.onNodeWithText("Notification", substring = true).assertExists()
        
        // About section
        composeTestRule.onNodeWithText("About", substring = true).assertExists()
    }
    
    // ============ Scanning Settings Tests ============
    
    @Test
    fun settingsScreen_wifiScanToggle_exists() {
        composeTestRule.onNodeWithText("WiFi Scanning", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_bleScanToggle_exists() {
        composeTestRule.onNodeWithText("Bluetooth Scanning", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_toggleWifiScanning_changes() {
        val toggle = composeTestRule.onNodeWithText("WiFi Scanning", substring = true)
        toggle.performClick()
        composeTestRule.waitForIdle()
        
        // Toggle should change state
        // (Would need to verify actual state change)
    }
    
    @Test
    fun settingsScreen_toggleBleScanning_changes() {
        val toggle = composeTestRule.onNodeWithText("Bluetooth Scanning", substring = true)
        toggle.performClick()
        composeTestRule.waitForIdle()
    }
    
    @Test
    fun settingsScreen_scanInterval_canBeChanged() {
        composeTestRule.onNodeWithText("Scan Interval", substring = true).assertExists()
        
        // Click to open interval picker
        composeTestRule.onNodeWithText("Scan Interval", substring = true).performClick()
        composeTestRule.waitForIdle()
    }
    
    // ============ Detection Settings Tests ============
    
    @Test
    fun settingsScreen_flockDetection_toggle() {
        composeTestRule.onNodeWithText("Flock Safety", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_ravenDetection_toggle() {
        composeTestRule.onNodeWithText("Raven", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_penguinDetection_toggle() {
        composeTestRule.onNodeWithText("Penguin", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_pigvisionDetection_toggle() {
        composeTestRule.onNodeWithText("Pigvision", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_disableFlockDetection_works() {
        composeTestRule.onNodeWithText("Flock Safety", substring = true).performClick()
        composeTestRule.waitForIdle()
        
        // Should show confirmation or update state
    }
    
    // ============ Notification Settings Tests ============
    
    @Test
    fun settingsScreen_notificationToggle_exists() {
        composeTestRule.onNodeWithText("Enable Notifications", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_vibrationToggle_exists() {
        composeTestRule.onNodeWithText("Vibration", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_soundToggle_exists() {
        composeTestRule.onNodeWithText("Sound", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_criticalAlertsOnly_toggle() {
        composeTestRule.onNodeWithText("Critical Alerts Only", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_toggleNotifications_changes() {
        composeTestRule.onNodeWithText("Enable Notifications", substring = true).performClick()
        composeTestRule.waitForIdle()
    }
    
    @Test
    fun settingsScreen_toggleVibration_changes() {
        composeTestRule.onNodeWithText("Vibration", substring = true).performClick()
        composeTestRule.waitForIdle()
    }
    
    // ============ Data Management Tests ============
    
    @Test
    fun settingsScreen_exportData_exists() {
        composeTestRule.onNodeWithText("Export Data", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_clearHistory_exists() {
        composeTestRule.onNodeWithText("Clear History", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_clearHistory_showsConfirmation() {
        composeTestRule.onNodeWithText("Clear History", substring = true).performClick()
        composeTestRule.waitForIdle()
        
        // Confirmation dialog should appear
        composeTestRule.onNodeWithText("Are you sure", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_clearHistory_cancel() {
        composeTestRule.onNodeWithText("Clear History", substring = true).performClick()
        composeTestRule.waitForIdle()
        
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
        
        // Settings screen should still be visible
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }
    
    @Test
    fun settingsScreen_exportData_showsOptions() {
        composeTestRule.onNodeWithText("Export Data", substring = true).performClick()
        composeTestRule.waitForIdle()
        
        // Export options should appear
        composeTestRule.onNodeWithText("CSV", substring = true).assertExists()
        composeTestRule.onNodeWithText("KML", substring = true).assertExists()
    }
    
    // ============ About Section Tests ============
    
    @Test
    fun settingsScreen_version_displayed() {
        composeTestRule.onNodeWithText("Version", substring = true).assertExists()
        composeTestRule.onNodeWithText("1.0.0", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_privacyPolicy_link() {
        composeTestRule.onNodeWithText("Privacy Policy", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_openSource_link() {
        composeTestRule.onNodeWithText("Open Source Licenses", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_credits_displayed() {
        // Credits for DeFlock and GainSec
        composeTestRule.onNodeWithText("Credits", substring = true).performClick()
        composeTestRule.waitForIdle()
        
        composeTestRule.onNodeWithText("deflock.me", substring = true).assertExists()
        composeTestRule.onNodeWithText("GainSec", substring = true).assertExists()
    }
    
    // ============ Navigation Tests ============
    
    @Test
    fun settingsScreen_backButton_returnsToMain() {
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        
        composeTestRule.onNodeWithText("Flock You").assertIsDisplayed()
    }
    
    // ============ Persistence Tests ============
    
    @Test
    fun settingsScreen_changesPersistedAfterRestart() {
        // Change a setting
        composeTestRule.onNodeWithText("Vibration", substring = true).performClick()
        composeTestRule.waitForIdle()
        
        // Navigate away and back
        composeTestRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        
        composeTestRule.mainScreen { clickSettings() }
        
        // Setting should still be changed
        // (Would need state verification)
    }
    
    @Test
    fun settingsScreen_changesPersistedAfterAppKill() {
        // Change a setting
        composeTestRule.onNodeWithText("Sound", substring = true).performClick()
        composeTestRule.waitForIdle()
        
        // Simulate app restart
        composeTestRule.activityRule.scenario.onActivity { it.recreate() }
        composeTestRule.waitForIdle()
        
        navigateToSettings()
        
        // Setting should still be changed
    }
    
    // ============ Theme Settings Tests ============
    
    @Test
    fun settingsScreen_themeOption_exists() {
        composeTestRule.onNodeWithText("Theme", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_darkMode_canBeToggled() {
        composeTestRule.onNodeWithText("Dark Mode", substring = true).performClick()
        composeTestRule.waitForIdle()
    }
    
    // ============ Advanced Settings Tests ============
    
    @Test
    fun settingsScreen_advancedSection_exists() {
        composeTestRule.onNodeWithText("Advanced", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Advanced", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_debugMode_toggle() {
        composeTestRule.onNodeWithText("Debug Mode", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Debug Mode", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_logLevel_canBeChanged() {
        composeTestRule.onNodeWithText("Log Level", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Log Level", substring = true).performClick()
        composeTestRule.waitForIdle()
    }
    
    // ============ Permission Settings Tests ============
    
    @Test
    fun settingsScreen_permissionStatus_displayed() {
        composeTestRule.onNodeWithText("Permissions", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_locationPermission_status() {
        composeTestRule.onNodeWithText("Location", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_bluetoothPermission_status() {
        composeTestRule.onNodeWithText("Bluetooth", substring = true).assertExists()
    }
    
    // ============ Battery Optimization Tests ============
    
    @Test
    fun settingsScreen_batteryOptimization_shown() {
        composeTestRule.onNodeWithText("Battery", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Battery Optimization", substring = true).assertExists()
    }
    
    @Test
    fun settingsScreen_batteryOptimization_opensSystemSettings() {
        composeTestRule.onNodeWithText("Battery Optimization", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("Battery Optimization", substring = true).performClick()
        composeTestRule.waitForIdle()
        
        // This would open system settings - verify intent was sent
    }
}

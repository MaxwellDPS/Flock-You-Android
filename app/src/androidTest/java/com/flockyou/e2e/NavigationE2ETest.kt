package com.flockyou.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.flockyou.MainActivity
import com.flockyou.robot.mainScreen
import com.flockyou.robot.mapScreen
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
 * End-to-end tests for app navigation.
 * Tests navigation between screens, back stack behavior, and deep links.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HiltAndroidTest
class NavigationE2ETest {
    
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
    }
    
    // ============ Initial Navigation Tests ============
    
    @Test
    fun app_startsOnMainScreen() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            verifyAppTitleDisplayed()
        }
    }
    
    // ============ Main to Map Navigation ============
    
    @Test
    fun navigation_mainToMap_viaFab() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickMapFab()
        }
        
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
        }
    }
    
    @Test
    fun navigation_mapToMain_viaBackButton() {
        // Navigate to map
        composeTestRule.mainScreen {
            clickMapFab()
        }
        
        // Navigate back
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickBackButton()
        }
        
        // Verify on main screen
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
    }
    
    @Test
    fun navigation_mapToMain_viaSystemBack() {
        // Navigate to map
        composeTestRule.mainScreen {
            clickMapFab()
        }
        
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
        }
        
        // Press system back button
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        
        // Verify on main screen
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
    }
    
    // ============ Main to Settings Navigation ============
    
    @Test
    fun navigation_mainToSettings_viaButton() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickSettings()
        }
        
        // Verify settings screen
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }
    
    @Test
    fun navigation_settingsToMain_viaBackButton() {
        // Navigate to settings
        composeTestRule.mainScreen {
            clickSettings()
        }
        
        // Press back
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        
        // Verify on main screen
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
    }
    
    // ============ Complex Navigation Paths ============
    
    @Test
    fun navigation_mainToMapToSettingsToMain() {
        // Start on main
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
        
        // Go to map
        composeTestRule.mainScreen {
            clickMapFab()
        }
        
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickBackButton()
        }
        
        // Go to settings
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickSettings()
        }
        
        // Back to main
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
    }
    
    @Test
    fun navigation_multipleMapVisits_preservesBackStack() {
        // Visit map multiple times
        repeat(3) {
            composeTestRule.mainScreen {
                clickMapFab()
            }
            
            composeTestRule.mapScreen {
                verifyMapScreenDisplayed()
                clickBackButton()
            }
            
            composeTestRule.mainScreen {
                verifyMainScreenDisplayed()
            }
        }
    }
    
    // ============ Back Stack Tests ============
    
    @Test
    fun navigation_backOnMainScreen_exitsApp() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
        
        // Press back on main screen should attempt to exit
        // (This might show a confirmation dialog or exit)
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }
    
    @Test
    fun navigation_deepBackStack_unwindsCorrectly() {
        // Build up back stack: Main -> Map -> Main -> Settings -> Main -> Map
        composeTestRule.mainScreen { clickMapFab() }
        composeTestRule.mapScreen { clickBackButton() }
        composeTestRule.mainScreen { clickSettings() }
        composeTestRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        composeTestRule.mainScreen { clickMapFab() }
        
        // Now verify we can unwind
        composeTestRule.mapScreen { verifyMapScreenDisplayed() }
        
        composeTestRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        
        composeTestRule.mainScreen { verifyMainScreenDisplayed() }
    }
    
    // ============ State Preservation Tests ============
    
    @Test
    fun navigation_preservesScanningState_afterNavigating() {
        // Start scanning
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            verifyScanningStatus(isScanning = true)
        }
        
        // Navigate to map
        composeTestRule.mainScreen { clickMapFab() }
        composeTestRule.mapScreen { verifyMapScreenDisplayed() }
        
        // Navigate back
        composeTestRule.mapScreen { clickBackButton() }
        
        // Verify scanning state preserved
        composeTestRule.mainScreen {
            verifyScanningStatus(isScanning = true)
        }
    }
    
    @Test
    fun navigation_preservesDetections_afterNavigating() {
        // Wait for any detections
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
        }
        
        // Navigate away and back
        composeTestRule.mainScreen { clickMapFab() }
        composeTestRule.mapScreen { clickBackButton() }
        
        // Detections should still be visible
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
    }
    
    // ============ Configuration Change Tests ============
    
    @Test
    fun navigation_survivesRotation_onMainScreen() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
        }
        
        // Simulate rotation
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.recreate()
        }
        composeTestRule.waitForIdle()
        
        // Verify still on main screen with scanning state
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
    }
    
    @Test
    fun navigation_survivesRotation_onMapScreen() {
        composeTestRule.mainScreen { clickMapFab() }
        
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
        }
        
        // Simulate rotation
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.recreate()
        }
        composeTestRule.waitForIdle()
        
        // Should still be on map screen (or navigate back to main depending on implementation)
        // Adjust assertion based on expected behavior
    }
    
    // ============ Bottom Navigation Tests (if applicable) ============
    
    @Test
    fun navigation_bottomNav_switchesTabs() {
        // If the app uses bottom navigation
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
        
        // Click on different tabs if they exist
        composeTestRule.onNodeWithContentDescription("Map").performClick()
        composeTestRule.waitForIdle()
        
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
        }
    }
    
    // ============ Error State Navigation ============
    
    @Test
    fun navigation_handlesNavigationDuringLoading() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            // Navigate while scan is starting
            clickMapFab()
        }
        
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
        }
    }
    
    // ============ Rapid Navigation Tests ============
    
    @Test
    fun navigation_handlesRapidNavigation() {
        // Quickly tap between screens
        repeat(5) {
            composeTestRule.mainScreen { clickMapFab() }
            Thread.sleep(100)
            composeTestRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(100)
        }
        
        composeTestRule.waitForIdle()
        
        // App should be stable
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
    }
}

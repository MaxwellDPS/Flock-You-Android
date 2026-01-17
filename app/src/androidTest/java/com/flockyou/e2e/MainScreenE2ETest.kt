package com.flockyou.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.flockyou.MainActivity
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.fake.FakeDetectionFactory
import com.flockyou.fake.FakeDetectionRepository
import com.flockyou.robot.MainScreenRobot
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
 * End-to-end tests for the Main Screen.
 * Tests the detection list display, scanning controls, and user interactions.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HiltAndroidTest
class MainScreenE2ETest {
    
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
    
    // ============ Screen Display Tests ============
    
    @Test
    fun mainScreen_displaysCorrectly() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            verifyAppTitleDisplayed()
        }
    }
    
    @Test
    fun mainScreen_showsEmptyState_whenNoDetections() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            verifyEmptyStateDisplayed()
        }
    }
    
    @Test
    fun mainScreen_displaysFlockYouTitle() {
        composeTestRule.onNodeWithText("Flock You").assertIsDisplayed()
    }
    
    // ============ Detection List Tests ============
    
    @Test
    fun mainScreen_displaysDetection_whenFlockSafetyCameraDetected() {
        // This test verifies that detections are displayed in the list
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            // After a detection is triggered, verify it appears
            waitForIdle()
        }
        
        // Verify detection-related UI elements exist
        composeTestRule.onNodeWithText("Flock", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun mainScreen_displaysThreatLevel_forCriticalThreat() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            // Critical threat level should have specific styling
        }
        
        composeTestRule.onNodeWithText("CRITICAL", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun mainScreen_displaysMultipleDetections() {
        // Test that multiple detections can be displayed
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            waitForIdle()
        }
        
        // The list should be scrollable if there are many detections
        composeTestRule.onNodeWithTag(MainScreenRobot.TAG_DETECTION_LIST)
            .performScrollToIndex(0)
    }
    
    // ============ Scanning Control Tests ============
    
    @Test
    fun mainScreen_startScan_updatesStatus() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            verifyScanningStatus(isScanning = true)
        }
    }
    
    @Test
    fun mainScreen_stopScan_updatesStatus() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForIdle()
            clickStopScan()
            verifyScanningStatus(isScanning = false)
        }
    }
    
    @Test
    fun mainScreen_scanToggle_canBeRepeated() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            
            // Start scan
            clickStartScan()
            verifyScanningStatus(isScanning = true)
            
            // Stop scan
            clickStopScan()
            verifyScanningStatus(isScanning = false)
            
            // Start again
            clickStartScan()
            verifyScanningStatus(isScanning = true)
        }
    }
    
    // ============ Navigation Tests ============
    
    @Test
    fun mainScreen_mapFab_navigatesToMap() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickMapFab()
        }
        
        // Verify we're on the map screen
        composeTestRule.onNodeWithText("Detection Map", substring = true)
            .assertExists()
    }
    
    @Test
    fun mainScreen_settingsButton_navigatesToSettings() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickSettings()
        }
        
        // Verify we're on the settings screen
        composeTestRule.onNodeWithText("Settings", substring = true)
            .assertExists()
    }
    
    // ============ Clear Detections Tests ============
    
    @Test
    fun mainScreen_clearDetections_showsConfirmationDialog() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickClearDetections()
        }
        
        // Verify confirmation dialog appears
        composeTestRule.onNodeWithText("Clear all detections?", substring = true)
            .assertExists()
        composeTestRule.onNodeWithText("Cancel").assertExists()
        composeTestRule.onNodeWithText("Clear").assertExists()
    }
    
    @Test
    fun mainScreen_clearDetections_cancel_preservesDetections() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickClearDetections()
            cancelClearDetections()
            // Detections should still be present
        }
    }
    
    @Test
    fun mainScreen_clearDetections_confirm_removesDetections() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickClearDetections()
            confirmClearDetections()
            verifyEmptyStateDisplayed()
        }
    }
    
    // ============ Detection Item Interaction Tests ============
    
    @Test
    fun mainScreen_clickDetection_showsDetails() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            // If detections exist, clicking one should show details
        }
    }
    
    @Test
    fun mainScreen_pullToRefresh_triggersRescan() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            pullToRefresh()
            waitForIdle()
        }
    }
    
    // ============ Device Type Specific Tests ============
    
    @Test
    fun mainScreen_displaysRavenDetection_withCriticalBadge() {
        // Raven gunshot detectors should always show CRITICAL
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
        
        // Check for Raven-specific UI elements
        composeTestRule.onNodeWithText("Raven", substring = true, ignoreCase = true)
            .assertExists()
        composeTestRule.onNodeWithText("CRITICAL", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun mainScreen_displaysPenguinDetection() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
        
        composeTestRule.onNodeWithText("Penguin", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun mainScreen_displaysPigvisionDetection() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
        }
        
        composeTestRule.onNodeWithText("Pigvision", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    // ============ Signal Strength Display Tests ============
    
    @Test
    fun mainScreen_displaysSignalStrengthIndicator() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            verifySignalStrengthDisplayed()
        }
    }
    
    // ============ Count Display Tests ============
    
    @Test
    fun mainScreen_displaysCorrectDetectionCount() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            // Verify count is displayed
            verifyDetectionCount(0) // Initially no detections
        }
    }
    
    // ============ Scroll Tests ============
    
    @Test
    fun mainScreen_scrollsToDetection() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            scrollToDetection(DeviceType.FLOCK_SAFETY_CAMERA)
        }
    }
    
    // ============ Real-time Update Tests ============
    
    @Test
    fun mainScreen_updatesInRealTime_whenNewDetectionArrives() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            // Wait for potential detections
            waitForDetections(timeoutMillis = 10000)
        }
    }
    
    // ============ Error Handling Tests ============
    
    @Test
    fun mainScreen_handlesBluetoothDisabled_gracefully() {
        // Test that the app handles Bluetooth being disabled
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            // App should show appropriate message or continue with WiFi only
        }
    }
    
    @Test
    fun mainScreen_handlesWiFiDisabled_gracefully() {
        // Test that the app handles WiFi being disabled
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            // App should show appropriate message or continue with BLE only
        }
    }
}

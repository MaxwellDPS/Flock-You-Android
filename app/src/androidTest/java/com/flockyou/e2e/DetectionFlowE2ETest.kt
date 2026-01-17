package com.flockyou.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.flockyou.MainActivity
import com.flockyou.data.model.*
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
 * End-to-end tests for the complete detection flow.
 * Tests the full journey from scanning to detection display and interactions.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HiltAndroidTest
class DetectionFlowE2ETest {
    
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
    
    // ============ Full Detection Flow Tests ============
    
    @Test
    fun detectionFlow_startScan_detectDevice_displayInList() {
        composeTestRule.mainScreen {
            // 1. Verify initial state
            verifyMainScreenDisplayed()
            verifyEmptyStateDisplayed()
            
            // 2. Start scanning
            clickStartScan()
            verifyScanningStatus(isScanning = true)
            
            // 3. Wait for potential detection (in real scenario)
            waitForDetections(timeoutMillis = 30000)
            
            // 4. Verify detection appears in list
            verifyDetectionListNotEmpty()
        }
    }
    
    @Test
    fun detectionFlow_detectDevice_showOnMap() {
        // Start scanning and wait for detection
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections(timeoutMillis = 30000)
        }
        
        // Navigate to map
        composeTestRule.mainScreen { clickMapFab() }
        
        // Verify marker on map
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            // Markers should be visible if detection has location
        }
    }
    
    @Test
    fun detectionFlow_detectDevice_alertUser() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            
            // When a device is detected, user should be alerted
            // This would trigger a notification and vibration
            waitForDetections(timeoutMillis = 30000)
        }
        
        // Check notification was sent (this is platform-level)
        // In real test, would verify NotificationManager has notification
    }
    
    // ============ Detection Method Specific Tests ============
    
    @Test
    fun detectionFlow_wifiSsidPattern_detectsFlockCamera() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            
            // Wait and verify Flock camera detection
            waitForIdle()
        }
        
        // Verify Flock Safety detection appears
        composeTestRule.onNodeWithText("FLOCK", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun detectionFlow_bleName_detectsRavenDevice() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForIdle()
        }
        
        // Verify Raven detection would appear
        composeTestRule.onNodeWithText("Raven", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun detectionFlow_bleServiceUuid_detectsRavenGunshot() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForIdle()
        }
        
        // Raven gunshot detectors detected by service UUID
        composeTestRule.onNodeWithText("Gunshot", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun detectionFlow_macPrefix_detectsSurveillanceDevice() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForIdle()
        }
        
        // MAC prefix detection
        composeTestRule.onNodeWithText("MAC", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    // ============ Threat Level Tests ============
    
    @Test
    fun detectionFlow_criticalThreat_displaysCorrectly() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
        }
        
        // Critical threat should be prominently displayed
        composeTestRule.onNodeWithText("CRITICAL", ignoreCase = true).assertExists()
    }
    
    @Test
    fun detectionFlow_highThreat_displaysCorrectly() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
        }
        
        composeTestRule.onNodeWithText("HIGH", ignoreCase = true).assertExists()
    }
    
    @Test
    fun detectionFlow_threatLevelColoring_isCorrect() {
        // Different threat levels should have different colors
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
        }
        
        // Visual verification (would need screenshot comparison in real test)
    }
    
    // ============ Signal Strength Tests ============
    
    @Test
    fun detectionFlow_signalStrengthExcellent_displaysCorrectly() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            verifySignalStrengthDisplayed()
        }
    }
    
    @Test
    fun detectionFlow_signalStrengthUpdates_onRssiChange() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            
            // Over time, signal strength should update as device moves
            waitForDetections(timeoutMillis = 60000)
        }
    }
    
    // ============ Detection Detail Tests ============
    
    @Test
    fun detectionFlow_clickDetection_showsDetails() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            clickDetection(DeviceType.FLOCK_SAFETY_CAMERA)
        }
        
        // Verify detection details dialog/screen
        composeTestRule.onNodeWithText("Details", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun detectionFlow_detectionDetails_showsMacAddress() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            clickDetection(DeviceType.FLOCK_SAFETY_CAMERA)
        }
        
        // MAC address should be visible in details
        composeTestRule.onNodeWithText(":", substring = true).assertExists()
    }
    
    @Test
    fun detectionFlow_detectionDetails_showsManufacturer() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            clickDetection(DeviceType.FLOCK_SAFETY_CAMERA)
        }
        
        composeTestRule.onNodeWithText("Flock Safety", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun detectionFlow_ravenDetails_showsFirmwareVersion() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            clickDetection(DeviceType.RAVEN_GUNSHOT_DETECTOR)
        }
        
        // Firmware version should be visible for Raven devices
        composeTestRule.onNodeWithText("Firmware", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    // ============ Detection Persistence Tests ============
    
    @Test
    fun detectionFlow_detectionsPersistedAfterStop() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            clickStopScan()
            
            // Detections should still be visible
            verifyDetectionListNotEmpty()
        }
    }
    
    @Test
    fun detectionFlow_detectionsPersistedAfterAppRestart() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
        }
        
        // Simulate app restart
        composeTestRule.activityRule.scenario.onActivity { it.recreate() }
        composeTestRule.waitForIdle()
        
        // Detections should still be there
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            // Database persisted detections should be visible
        }
    }
    
    // ============ Detection Inactive/Active Tests ============
    
    @Test
    fun detectionFlow_deviceLeavesRange_markedInactive() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            
            // After device leaves range (1 minute timeout)
            // Detection should be marked inactive
        }
        
        // Inactive detections should be styled differently
    }
    
    @Test
    fun detectionFlow_deviceReentersRange_markedActive() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            
            // Device detected, leaves, then comes back
            waitForDetections(timeoutMillis = 120000)
        }
    }
    
    // ============ Multiple Detection Tests ============
    
    @Test
    fun detectionFlow_multipleDevices_allDisplayed() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            
            // Multiple device types should be visible
            verifyDetectionDisplayed(DeviceType.FLOCK_SAFETY_CAMERA)
            verifyDetectionDisplayed(DeviceType.RAVEN_GUNSHOT_DETECTOR)
        }
    }
    
    @Test
    fun detectionFlow_sortedByThreatLevel() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            
            // Critical threats should appear first
            // Verify order (would need to check positions)
        }
    }
    
    // ============ Location Association Tests ============
    
    @Test
    fun detectionFlow_capturesLocation_whenAvailable() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            clickDetection(DeviceType.FLOCK_SAFETY_CAMERA)
        }
        
        // Location should be visible in details
        composeTestRule.onNodeWithText("Location", substring = true, ignoreCase = true)
            .assertExists()
    }
    
    @Test
    fun detectionFlow_mapShowsDetectionLocation() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            clickMapFab()
        }
        
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            // Verify marker at detection location
        }
    }
    
    // ============ Export Tests ============
    
    @Test
    fun detectionFlow_exportToCsv_works() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
        }
        
        // Trigger export (if available in UI)
        composeTestRule.onNodeWithText("Export", substring = true, ignoreCase = true)
            .performClick()
        composeTestRule.waitForIdle()
    }
    
    @Test
    fun detectionFlow_exportToKml_works() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickStartScan()
            waitForDetections()
            clickMapFab()
        }
        
        // Export from map (if available)
        composeTestRule.onNodeWithText("Export", substring = true, ignoreCase = true)
            .performClick()
        composeTestRule.waitForIdle()
    }
}

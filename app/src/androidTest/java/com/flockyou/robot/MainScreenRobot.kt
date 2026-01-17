package com.flockyou.robot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel

/**
 * Robot for interacting with the Main Screen in UI tests.
 */
class MainScreenRobot(composeTestRule: ComposeTestRule) : BaseRobot(composeTestRule) {
    
    // Test tags that should be added to the composables
    companion object {
        const val TAG_MAIN_SCREEN = "main_screen"
        const val TAG_SCANNING_STATUS = "scanning_status"
        const val TAG_START_SCAN_BUTTON = "start_scan_button"
        const val TAG_STOP_SCAN_BUTTON = "stop_scan_button"
        const val TAG_DETECTION_COUNT = "detection_count"
        const val TAG_DETECTION_LIST = "detection_list"
        const val TAG_DETECTION_ITEM = "detection_item"
        const val TAG_EMPTY_STATE = "empty_state"
        const val TAG_MAP_FAB = "map_fab"
        const val TAG_SETTINGS_BUTTON = "settings_button"
        const val TAG_CLEAR_DETECTIONS_BUTTON = "clear_detections_button"
    }
    
    /**
     * Verifies the main screen is displayed.
     */
    fun verifyMainScreenDisplayed(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MAIN_SCREEN).assertIsDisplayed()
        return this
    }
    
    /**
     * Verifies the scanning status text.
     */
    fun verifyScanningStatus(isScanning: Boolean): MainScreenRobot {
        val expectedText = if (isScanning) "Scanning..." else "Scan Stopped"
        composeTestRule.onNodeWithText(expectedText, substring = true).assertExists()
        return this
    }
    
    /**
     * Clicks the start scan button.
     */
    fun clickStartScan(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_START_SCAN_BUTTON).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Clicks the stop scan button.
     */
    fun clickStopScan(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_STOP_SCAN_BUTTON).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Verifies the detection count.
     */
    fun verifyDetectionCount(count: Int): MainScreenRobot {
        composeTestRule.onNodeWithText("$count", substring = true).assertExists()
        return this
    }
    
    /**
     * Verifies a detection is displayed by device type.
     */
    fun verifyDetectionDisplayed(deviceType: DeviceType): MainScreenRobot {
        val displayName = deviceType.name.replace("_", " ")
        composeTestRule.onNodeWithText(displayName, substring = true).assertExists()
        return this
    }
    
    /**
     * Verifies a detection is displayed by MAC address.
     */
    fun verifyDetectionByMacAddress(macAddress: String): MainScreenRobot {
        composeTestRule.onNodeWithText(macAddress).assertExists()
        return this
    }
    
    /**
     * Verifies a detection with threat level badge.
     */
    fun verifyThreatLevelDisplayed(threatLevel: ThreatLevel): MainScreenRobot {
        composeTestRule.onNodeWithText(threatLevel.name).assertExists()
        return this
    }
    
    /**
     * Clicks on a detection item.
     */
    fun clickDetection(deviceType: DeviceType): MainScreenRobot {
        val displayName = deviceType.name.replace("_", " ")
        composeTestRule.onNodeWithText(displayName, substring = true).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Verifies the empty state is displayed.
     */
    fun verifyEmptyStateDisplayed(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_EMPTY_STATE).assertIsDisplayed()
        return this
    }
    
    /**
     * Verifies the detection list is not empty.
     */
    fun verifyDetectionListNotEmpty(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_DETECTION_LIST).assertExists()
        composeTestRule.onAllNodesWithTag(TAG_DETECTION_ITEM).assertCountEquals(0).not()
        return this
    }
    
    /**
     * Clicks the map FAB to open the map.
     */
    fun clickMapFab(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MAP_FAB).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Clicks the settings button.
     */
    fun clickSettings(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_SETTINGS_BUTTON).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Clicks the clear detections button.
     */
    fun clickClearDetections(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_CLEAR_DETECTIONS_BUTTON).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Confirms the clear dialog.
     */
    fun confirmClearDetections(): MainScreenRobot {
        composeTestRule.onNodeWithText("Clear").performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Cancels the clear dialog.
     */
    fun cancelClearDetections(): MainScreenRobot {
        composeTestRule.onNodeWithText("Cancel").performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Scrolls to a specific detection.
     */
    fun scrollToDetection(deviceType: DeviceType): MainScreenRobot {
        val displayName = deviceType.name.replace("_", " ")
        composeTestRule.onNodeWithText(displayName, substring = true).performScrollTo()
        return this
    }
    
    /**
     * Performs pull-to-refresh gesture.
     */
    fun pullToRefresh(): MainScreenRobot {
        composeTestRule.onNodeWithTag(TAG_DETECTION_LIST)
            .performTouchInput { 
                swipeDown(startY = centerY, endY = bottom) 
            }
        waitForIdle()
        return this
    }
    
    /**
     * Waits for detections to load.
     */
    fun waitForDetections(timeoutMillis: Long = 5000): MainScreenRobot {
        composeTestRule.waitUntil(timeoutMillis) {
            composeTestRule.onAllNodesWithTag(TAG_DETECTION_ITEM)
                .fetchSemanticsNodes().isNotEmpty()
        }
        return this
    }
    
    /**
     * Verifies the app title is displayed.
     */
    fun verifyAppTitleDisplayed(): MainScreenRobot {
        composeTestRule.onNodeWithText("Flock You").assertIsDisplayed()
        return this
    }
    
    /**
     * Verifies signal strength indicator is displayed.
     */
    fun verifySignalStrengthDisplayed(): MainScreenRobot {
        composeTestRule.onNodeWithContentDescription("Signal strength").assertExists()
        return this
    }
}

/**
 * DSL function to interact with the MainScreen robot.
 */
fun ComposeTestRule.mainScreen(block: MainScreenRobot.() -> Unit) {
    MainScreenRobot(this).apply(block)
}

package com.flockyou.robot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Robot for interacting with the Map Screen in UI tests.
 */
class MapScreenRobot(composeTestRule: ComposeTestRule) : BaseRobot(composeTestRule) {
    
    companion object {
        const val TAG_MAP_SCREEN = "map_screen"
        const val TAG_MAP_VIEW = "map_view"
        const val TAG_MARKER = "map_marker"
        const val TAG_MY_LOCATION_BUTTON = "my_location_button"
        const val TAG_ZOOM_IN_BUTTON = "zoom_in_button"
        const val TAG_ZOOM_OUT_BUTTON = "zoom_out_button"
        const val TAG_BACK_BUTTON = "back_button"
        const val TAG_FILTER_CHIP = "filter_chip"
        const val TAG_MARKER_INFO_WINDOW = "marker_info_window"
        const val TAG_DETECTION_COUNT_BADGE = "detection_count_badge"
        const val TAG_MAP_LOADING = "map_loading"
    }
    
    /**
     * Verifies the map screen is displayed.
     */
    fun verifyMapScreenDisplayed(): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MAP_SCREEN).assertIsDisplayed()
        return this
    }
    
    /**
     * Verifies the map view is displayed.
     */
    fun verifyMapViewDisplayed(): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MAP_VIEW).assertIsDisplayed()
        return this
    }
    
    /**
     * Clicks the back button to return to main screen.
     */
    fun clickBackButton(): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_BACK_BUTTON).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Clicks the my location button.
     */
    fun clickMyLocationButton(): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MY_LOCATION_BUTTON).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Clicks the zoom in button.
     */
    fun clickZoomIn(): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_ZOOM_IN_BUTTON).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Clicks the zoom out button.
     */
    fun clickZoomOut(): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_ZOOM_OUT_BUTTON).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Verifies markers are displayed on the map.
     */
    fun verifyMarkersDisplayed(expectedCount: Int): MapScreenRobot {
        composeTestRule.onAllNodesWithTag(TAG_MARKER)
            .assertCountEquals(expectedCount)
        return this
    }
    
    /**
     * Clicks on a marker.
     */
    fun clickOnMarker(): MapScreenRobot {
        composeTestRule.onAllNodesWithTag(TAG_MARKER).onFirst().performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Verifies the marker info window is displayed.
     */
    fun verifyMarkerInfoWindowDisplayed(): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MARKER_INFO_WINDOW).assertIsDisplayed()
        return this
    }
    
    /**
     * Closes the marker info window.
     */
    fun closeMarkerInfoWindow(): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MAP_VIEW).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Clicks a filter chip to filter detections by type.
     */
    fun clickFilterChip(filterText: String): MapScreenRobot {
        composeTestRule.onNodeWithText(filterText).performClick()
        waitForIdle()
        return this
    }
    
    /**
     * Verifies the detection count badge shows correct count.
     */
    fun verifyDetectionCountBadge(count: Int): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_DETECTION_COUNT_BADGE)
            .assertTextEquals("$count")
        return this
    }
    
    /**
     * Waits for the map to load.
     */
    fun waitForMapLoaded(timeoutMillis: Long = 10000): MapScreenRobot {
        composeTestRule.waitUntil(timeoutMillis) {
            composeTestRule.onAllNodesWithTag(TAG_MAP_LOADING)
                .fetchSemanticsNodes().isEmpty()
        }
        return this
    }
    
    /**
     * Performs a pan gesture on the map.
     */
    fun panMap(startX: Float, startY: Float, endX: Float, endY: Float): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MAP_VIEW)
            .performTouchInput {
                swipe(
                    start = androidx.compose.ui.geometry.Offset(startX, startY),
                    end = androidx.compose.ui.geometry.Offset(endX, endY),
                    durationMillis = 200
                )
            }
        waitForIdle()
        return this
    }
    
    /**
     * Performs a pinch-to-zoom gesture.
     */
    fun pinchToZoom(zoomIn: Boolean): MapScreenRobot {
        composeTestRule.onNodeWithTag(TAG_MAP_VIEW)
            .performTouchInput {
                if (zoomIn) {
                    pinch(
                        start0 = center - androidx.compose.ui.geometry.Offset(100f, 100f),
                        end0 = center - androidx.compose.ui.geometry.Offset(200f, 200f),
                        start1 = center + androidx.compose.ui.geometry.Offset(100f, 100f),
                        end1 = center + androidx.compose.ui.geometry.Offset(200f, 200f)
                    )
                } else {
                    pinch(
                        start0 = center - androidx.compose.ui.geometry.Offset(200f, 200f),
                        end0 = center - androidx.compose.ui.geometry.Offset(100f, 100f),
                        start1 = center + androidx.compose.ui.geometry.Offset(200f, 200f),
                        end1 = center + androidx.compose.ui.geometry.Offset(100f, 100f)
                    )
                }
            }
        waitForIdle()
        return this
    }
    
    /**
     * Verifies the map title is displayed.
     */
    fun verifyMapTitleDisplayed(): MapScreenRobot {
        composeTestRule.onNodeWithText("Detection Map").assertIsDisplayed()
        return this
    }
    
    /**
     * Verifies no markers state is displayed.
     */
    fun verifyNoMarkersStateDisplayed(): MapScreenRobot {
        composeTestRule.onNodeWithText("No detections to display").assertIsDisplayed()
        return this
    }
}

/**
 * DSL function to interact with the MapScreen robot.
 */
fun ComposeTestRule.mapScreen(block: MapScreenRobot.() -> Unit) {
    MapScreenRobot(this).apply(block)
}

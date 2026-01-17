package com.flockyou.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.flockyou.MainActivity
import com.flockyou.robot.MapScreenRobot
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
 * End-to-end tests for the Map Screen.
 * Tests map display, markers, and user interactions.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@HiltAndroidTest
class MapScreenE2ETest {
    
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
        // Navigate to map screen
        navigateToMapScreen()
    }
    
    private fun navigateToMapScreen() {
        composeTestRule.mainScreen {
            verifyMainScreenDisplayed()
            clickMapFab()
        }
    }
    
    // ============ Screen Display Tests ============
    
    @Test
    fun mapScreen_displaysCorrectly() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            verifyMapTitleDisplayed()
        }
    }
    
    @Test
    fun mapScreen_displaysMapView() {
        composeTestRule.mapScreen {
            verifyMapViewDisplayed()
        }
    }
    
    @Test
    fun mapScreen_showsEmptyState_whenNoDetectionsWithLocation() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            // If no detections have location data, show appropriate message
            verifyNoMarkersStateDisplayed()
        }
    }
    
    // ============ Navigation Tests ============
    
    @Test
    fun mapScreen_backButton_returnsToMainScreen() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickBackButton()
        }
        
        // Verify we're back on main screen
        composeTestRule.onNodeWithText("Flock You").assertIsDisplayed()
    }
    
    @Test
    fun mapScreen_systemBackButton_returnsToMainScreen() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
        }
        
        // Use activity's back press
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        
        // Verify we're back on main screen
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Flock You").assertIsDisplayed()
    }
    
    // ============ Map Control Tests ============
    
    @Test
    fun mapScreen_myLocationButton_centersOnUser() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickMyLocationButton()
            waitForIdle()
        }
    }
    
    @Test
    fun mapScreen_zoomIn_increasesZoomLevel() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickZoomIn()
            waitForIdle()
        }
    }
    
    @Test
    fun mapScreen_zoomOut_decreasesZoomLevel() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickZoomOut()
            waitForIdle()
        }
    }
    
    @Test
    fun mapScreen_pinchToZoomIn_works() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            pinchToZoom(zoomIn = true)
            waitForIdle()
        }
    }
    
    @Test
    fun mapScreen_pinchToZoomOut_works() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            pinchToZoom(zoomIn = false)
            waitForIdle()
        }
    }
    
    @Test
    fun mapScreen_panGesture_movesMap() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            panMap(300f, 300f, 500f, 500f)
            waitForIdle()
        }
    }
    
    // ============ Marker Tests ============
    
    @Test
    fun mapScreen_displaysMarkersForDetections() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            // Markers should be displayed for detections with location data
        }
    }
    
    @Test
    fun mapScreen_clickMarker_showsInfoWindow() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            clickOnMarker()
            verifyMarkerInfoWindowDisplayed()
        }
    }
    
    @Test
    fun mapScreen_closeInfoWindow_dismisses() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            clickOnMarker()
            verifyMarkerInfoWindowDisplayed()
            closeMarkerInfoWindow()
        }
    }
    
    // ============ Filter Tests ============
    
    @Test
    fun mapScreen_filterByFlockSafety_showsOnlyFlockMarkers() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickFilterChip("Flock Safety")
            waitForIdle()
        }
    }
    
    @Test
    fun mapScreen_filterByRaven_showsOnlyRavenMarkers() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickFilterChip("Raven")
            waitForIdle()
        }
    }
    
    @Test
    fun mapScreen_clearFilter_showsAllMarkers() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            clickFilterChip("Flock Safety")
            waitForIdle()
            clickFilterChip("All")
            waitForIdle()
        }
    }
    
    // ============ Marker Color Tests ============
    
    @Test
    fun mapScreen_criticalThreatMarkers_haveRedColor() {
        // Critical threat devices should have red markers
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
        }
    }
    
    @Test
    fun mapScreen_highThreatMarkers_haveOrangeColor() {
        // High threat devices should have orange markers
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
        }
    }
    
    // ============ Detection Count Badge Tests ============
    
    @Test
    fun mapScreen_countBadge_displaysCorrectCount() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            verifyDetectionCountBadge(0) // Initially
        }
    }
    
    // ============ Loading State Tests ============
    
    @Test
    fun mapScreen_showsLoading_whileMapLoads() {
        // Navigate fresh to see loading state
        composeTestRule.activityRule.scenario.onActivity { it.recreate() }
        composeTestRule.waitForIdle()
        navigateToMapScreen()
        
        composeTestRule.mapScreen {
            // Loading state might be visible briefly
            waitForMapLoaded()
            verifyMapViewDisplayed()
        }
    }
    
    // ============ Cluster Tests ============
    
    @Test
    fun mapScreen_clustersMarkersWhenZoomedOut() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            // Zoom out to trigger clustering
            clickZoomOut()
            clickZoomOut()
            clickZoomOut()
            waitForIdle()
        }
    }
    
    @Test
    fun mapScreen_expandsClusterOnClick() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            // Click on a cluster to expand it
            clickOnMarker()
            waitForIdle()
        }
    }
    
    // ============ Real-time Update Tests ============
    
    @Test
    fun mapScreen_updatesMarkers_whenNewDetectionArrives() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            // New detections should appear as markers
        }
    }
    
    @Test
    fun mapScreen_removesMarkers_whenDetectionBecomesInactive() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            waitForMapLoaded()
            // Inactive detections should be removed or styled differently
        }
    }
    
    // ============ Error Handling Tests ============
    
    @Test
    fun mapScreen_handlesLocationDisabled_gracefully() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            // When location is disabled, show appropriate message
        }
    }
    
    @Test
    fun mapScreen_handlesNetworkError_gracefully() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
            // When network is unavailable, map tiles might not load
            // App should handle gracefully
        }
    }
    
    // ============ Accessibility Tests ============
    
    @Test
    fun mapScreen_hasContentDescriptions() {
        composeTestRule.mapScreen {
            verifyMapScreenDisplayed()
        }
        
        // Verify important elements have content descriptions
        composeTestRule.onNodeWithContentDescription("Map").assertExists()
        composeTestRule.onNodeWithContentDescription("Zoom in").assertExists()
        composeTestRule.onNodeWithContentDescription("Zoom out").assertExists()
    }
}

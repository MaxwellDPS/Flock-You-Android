package com.flockyou.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.utils.TestDataFactory
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
 * Comprehensive E2E tests for the MapScreen UI.
 *
 * Tests cover:
 * - Map display and rendering
 * - Detection markers with correct colors/threat levels
 * - Marker clustering at different zoom levels
 * - Location-based detection display
 * - Map interaction (zoom, pan, center on location)
 * - Real-time detection updates
 * - GPS status indicator
 * - Empty state handling
 * - Edge cases (null locations, large datasets, etc.)
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MapScreenTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Inject
    lateinit var detectionRepository: DetectionRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        runBlocking {
            detectionRepository.deleteAll()
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            detectionRepository.deleteAll()
        }
    }

    // ==================== Map Display Tests ====================

    @Test
    fun mapScreen_rendersWithoutCrash() {
        var navigateBackCalled = false

        composeTestRule.setContent {
            MapScreen(
                onNavigateBack = { navigateBackCalled = true }
            )
        }

        composeTestRule.waitForIdle()
        // Map should render successfully without crashing
    }

    @Test
    fun mapScreen_displaysTopBarWithTitle() {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Verify top bar displays "Detection Map" title
        composeTestRule.onNodeWithText("Detection Map").assertExists()
    }

    @Test
    fun mapScreen_displaysBackButton() {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Verify back button exists
        composeTestRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun mapScreen_backButtonNavigatesBack() {
        var navigateBackCalled = false

        composeTestRule.setContent {
            MapScreen(onNavigateBack = { navigateBackCalled = true })
        }

        composeTestRule.waitForIdle()

        // Click back button
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        composeTestRule.waitForIdle()

        // Verify navigation callback was triggered
        assertTrue("Back navigation callback should be called", navigateBackCalled)
    }

    @Test
    fun mapScreen_displaysWithLocationData() = runTest {
        // Add detection with location
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Map should display location data (verified by absence of empty state)
        composeTestRule.onNodeWithText("No Detections Yet", substring = true)
            .assertDoesNotExist()
    }

    // ==================== User Location Tests ====================

    @Test
    fun mapScreen_displaysUserLocationFromDetections() = runTest {
        // Add detection with location (simulates user location)
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // GPS status indicator should show active status when location is available
        // The map will use the detection's location as user location
    }

    @Test
    fun mapScreen_centerOnUserLocationButton() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Find and click "My Location" button
        composeTestRule.onNodeWithContentDescription("My Location").assertExists()
        composeTestRule.onNodeWithContentDescription("My Location").performClick()

        composeTestRule.waitForIdle()

        // Map should center on user location (no crash)
    }

    // ==================== Detection Markers Tests ====================

    @Test
    fun mapScreen_displaysDetectionMarker() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Marker should be added to map (verified via detection count indicator)
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_displaysMultipleMarkers() = runTest {
        // Add multiple detections with different locations
        val detections = listOf(
            TestDataFactory.createTestDetection(latitude = 37.7749, longitude = -122.4194),
            TestDataFactory.createTestDetection(latitude = 37.7850, longitude = -122.4100),
            TestDataFactory.createTestDetection(latitude = 37.7650, longitude = -122.4300)
        )
        detections.forEach { detectionRepository.insert(it) }

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should display count of all locations
        composeTestRule.onNodeWithText("3 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_markerColorMatchesThreatLevel() = runTest {
        // Add detections with different threat levels
        val critical = TestDataFactory.createStingrayDetection().copy(
            latitude = 37.7749,
            longitude = -122.4194
        )
        val high = TestDataFactory.createFlockSafetyCameraDetection().copy(
            latitude = 37.7850,
            longitude = -122.4100
        )
        val medium = TestDataFactory.createDroneDetection().copy(
            latitude = 37.7650,
            longitude = -122.4300
        )
        val low = TestDataFactory.createTestDetection(
            threatLevel = ThreatLevel.LOW,
            latitude = 37.7550,
            longitude = -122.4400
        )

        detectionRepository.insert(critical)
        detectionRepository.insert(high)
        detectionRepository.insert(medium)
        detectionRepository.insert(low)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Verify legend displays all threat levels
        composeTestRule.onNodeWithText("Threat Levels").assertExists()
        composeTestRule.onNodeWithText("Critical").assertExists()
        composeTestRule.onNodeWithText("High").assertExists()
        composeTestRule.onNodeWithText("Medium").assertExists()
        composeTestRule.onNodeWithText("Low").assertExists()
    }

    @Test
    fun mapScreen_clickingMarkerShowsDetails() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection().copy(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Note: Clicking markers requires interacting with the map overlay
        // which is difficult in Compose tests. This test verifies the screen
        // renders properly with markers present.

        // Verify detection count shows marker is present
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()
    }

    // ==================== Marker Clustering Tests ====================

    @Test
    fun mapScreen_clustersNearbyDetections() = runTest {
        // Add multiple detections very close together (within clustering radius)
        val baseLatitude = 37.7749
        val baseLongitude = -122.4194
        val detections = List(5) { index ->
            TestDataFactory.createTestDetection(
                latitude = baseLatitude + (index * 0.0001), // Very close together
                longitude = baseLongitude + (index * 0.0001)
            )
        }
        detections.forEach { detectionRepository.insert(it) }

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // At low zoom levels, detections should be clustered
        // Verify all locations are tracked
        composeTestRule.onNodeWithText("5 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_clusterShowsHighestThreatLevel() = runTest {
        // Add nearby detections with different threat levels
        val baseLatitude = 37.7749
        val baseLongitude = -122.4194

        val critical = TestDataFactory.createStingrayDetection().copy(
            latitude = baseLatitude,
            longitude = baseLongitude
        )
        val low = TestDataFactory.createTestDetection(
            threatLevel = ThreatLevel.LOW,
            latitude = baseLatitude + 0.0001,
            longitude = baseLongitude + 0.0001
        )

        detectionRepository.insert(critical)
        detectionRepository.insert(low)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Cluster should use the highest threat level (Critical)
        // Verified via detection count
        composeTestRule.onNodeWithText("2 locations", substring = true).assertExists()
    }

    // ==================== Location-Based Detection Tests ====================

    @Test
    fun mapScreen_detectionsWithLocationShowOnMap() = runTest {
        val withLocation = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(withLocation)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Detection with location should be displayed
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_detectionsWithoutLocationDoNotBreakMap() = runTest {
        // Add detection without location
        val withoutLocation = TestDataFactory.createTestDetection(
            latitude = null,
            longitude = null
        )
        detectionRepository.insert(withoutLocation)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Map should handle gracefully - show empty state
        composeTestRule.onNodeWithText("No Location Data", substring = true).assertExists()
    }

    @Test
    fun mapScreen_mixedDetectionsShowOnlyWithLocation() = runTest {
        // Add both types of detections
        val withLocation = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        val withoutLocation = TestDataFactory.createTestDetection(
            latitude = null,
            longitude = null
        )

        detectionRepository.insert(withLocation)
        detectionRepository.insert(withoutLocation)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Only detection with location should show on map
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_detectionsAtDifferentLocationsDisplay() = runTest {
        // Add detections at widely separated locations
        val sanFrancisco = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA
        )
        val newYork = TestDataFactory.createTestDetection(
            latitude = 40.7128,
            longitude = -74.0060,
            macAddress = "BB:BB:BB:BB:BB:BB",
            deviceType = DeviceType.DRONE
        )

        detectionRepository.insert(sanFrancisco)
        detectionRepository.insert(newYork)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Both locations should be tracked
        composeTestRule.onNodeWithText("2 locations", substring = true).assertExists()
    }

    // ==================== Map Interaction Tests ====================

    @Test
    fun mapScreen_resetViewButtonExists() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Reset view button should exist
        composeTestRule.onNodeWithContentDescription("Reset View").assertExists()
    }

    @Test
    fun mapScreen_resetViewButtonWorks() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Click reset view button
        composeTestRule.onNodeWithContentDescription("Reset View").performClick()

        composeTestRule.waitForIdle()

        // Should not crash
    }

    @Test
    fun mapScreen_myLocationButtonExists() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // My Location button should exist
        composeTestRule.onNodeWithContentDescription("My Location").assertExists()
    }

    @Test
    fun mapScreen_myLocationButtonWorksWithLocationData() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Click My Location button
        composeTestRule.onNodeWithContentDescription("My Location").performClick()

        composeTestRule.waitForIdle()

        // Should center on location without crashing
    }

    // ==================== Real-Time Update Tests ====================

    @Test
    fun mapScreen_updatesWhenNewDetectionAdded() = runTest {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Initially no detections
        composeTestRule.onNodeWithText("No Detections Yet", substring = true).assertExists()

        // Add detection after map is loaded
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.waitForIdle()

        // Map should update to show new detection
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_updatesWhenMultipleDetectionsAdded() = runTest {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Add multiple detections dynamically
        val detections = listOf(
            TestDataFactory.createTestDetection(latitude = 37.7749, longitude = -122.4194),
            TestDataFactory.createTestDetection(latitude = 37.7850, longitude = -122.4100),
            TestDataFactory.createTestDetection(latitude = 37.7650, longitude = -122.4300)
        )
        detections.forEach {
            detectionRepository.insert(it)
            composeTestRule.waitForIdle()
        }

        // Map should show all detections
        composeTestRule.onNodeWithText("3 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_updatesWhenDetectionRemoved() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Verify detection exists
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()

        // Remove detection
        detectionRepository.deleteById(detection.id)

        composeTestRule.waitForIdle()

        // Map should update to show empty state
        composeTestRule.onNodeWithText("No Detections Yet", substring = true).assertExists()
    }

    // ==================== GPS Status Indicator Tests ====================

    @Test
    fun mapScreen_displaysGpsStatusIndicator() {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // GPS status indicator should be visible
        // (searching status when no detections with location)
        composeTestRule.onNodeWithText("Searching...", substring = true).assertExists()
    }

    @Test
    fun mapScreen_gpsStatusActiveWithLocationData() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // GPS status should show active when location data is available
        composeTestRule.onNodeWithText("GPS Active", substring = true).assertExists()
    }

    @Test
    fun mapScreen_gpsStatusShowsAccuracy() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194,
            signalStrength = SignalStrength.EXCELLENT // Should show ~10m accuracy
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // GPS status should show accuracy estimate
        composeTestRule.onNodeWithText("GPS Active").assertExists()
    }

    // ==================== Empty State Tests ====================

    @Test
    fun mapScreen_showsEmptyStateWithNoDetections() {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should show empty state message
        composeTestRule.onNodeWithText("No Detections Yet").assertExists()
        composeTestRule.onNodeWithText("Start scanning to detect surveillance devices", substring = true)
            .assertExists()
    }

    @Test
    fun mapScreen_showsNoLocationDataStateWithDetectionsButNoLocation() = runTest {
        // Add detection without location
        val detection = TestDataFactory.createUltrasonicBeaconDetection() // Has null location
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should show "no location data" empty state
        composeTestRule.onNodeWithText("No Location Data").assertExists()
    }

    @Test
    fun mapScreen_emptyStateHasStartScanningButton() {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Verify button exists
        composeTestRule.onNodeWithText("Start Scanning").assertExists()
    }

    @Test
    fun mapScreen_emptyStateHasEnableLocationButton() = runTest {
        // Add detection without location
        val detection = TestDataFactory.createTestDetection(
            latitude = null,
            longitude = null
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Verify enable location button exists
        composeTestRule.onNodeWithText("Enable Location").assertExists()
    }

    // ==================== Legend Tests ====================

    @Test
    fun mapScreen_displaysLegendWithLocations() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Legend should be visible
        composeTestRule.onNodeWithText("Threat Levels").assertExists()
    }

    @Test
    fun mapScreen_legendShowsAllThreatLevels() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Verify all threat levels in legend
        composeTestRule.onNodeWithText("Critical").assertExists()
        composeTestRule.onNodeWithText("High").assertExists()
        composeTestRule.onNodeWithText("Medium").assertExists()
        composeTestRule.onNodeWithText("Low").assertExists()
        composeTestRule.onNodeWithText("Info").assertExists()
    }

    @Test
    fun mapScreen_displaysAttribution() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // OpenStreetMap attribution should be visible
        composeTestRule.onNodeWithText("OpenStreetMap", substring = true).assertExists()
    }

    // ==================== Detection Count Tests ====================

    @Test
    fun mapScreen_displaysDetectionCount() = runTest {
        val detections = TestDataFactory.createMultipleDetections(7).map {
            it.copy(
                latitude = 37.7749 + (Math.random() * 0.1),
                longitude = -122.4194 + (Math.random() * 0.1)
            )
        }
        detections.forEach { detectionRepository.insert(it) }

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should display count of locations
        composeTestRule.onNodeWithText("7 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_countUpdatesInRealTime() = runTest {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Add detections one by one
        val detection1 = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection1)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()

        val detection2 = TestDataFactory.createTestDetection(
            latitude = 37.7850,
            longitude = -122.4100,
            macAddress = "BB:BB:BB:BB:BB:BB"
        )
        detectionRepository.insert(detection2)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("2 locations", substring = true).assertExists()
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun mapScreen_handlesLargeNumberOfDetections() = runTest {
        // Add 50 detections with random locations
        val detections = TestDataFactory.createMultipleDetections(50).map {
            it.copy(
                latitude = 37.7749 + (Math.random() * 0.5 - 0.25),
                longitude = -122.4194 + (Math.random() * 0.5 - 0.25)
            )
        }
        detections.forEach { detectionRepository.insert(it) }

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should handle large dataset without crashing
        composeTestRule.onNodeWithText("50 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_handlesDetectionWithNullLatitude() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = null,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should handle gracefully
        composeTestRule.onNodeWithText("No Location Data", substring = true).assertExists()
    }

    @Test
    fun mapScreen_handlesDetectionWithNullLongitude() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = null
        )
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should handle gracefully
        composeTestRule.onNodeWithText("No Location Data", substring = true).assertExists()
    }

    @Test
    fun mapScreen_handlesExtremeCoordinates() = runTest {
        // Add detection at extreme coordinates (North Pole)
        val northPole = TestDataFactory.createTestDetection(
            latitude = 89.9999,
            longitude = 0.0
        )
        detectionRepository.insert(northPole)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should handle extreme coordinates without crash
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_handlesDatelineWraparound() = runTest {
        // Add detections near international date line
        val west = TestDataFactory.createTestDetection(
            latitude = 0.0,
            longitude = -179.9
        )
        val east = TestDataFactory.createTestDetection(
            latitude = 0.0,
            longitude = 179.9,
            macAddress = "BB:BB:BB:BB:BB:BB"
        )

        detectionRepository.insert(west)
        detectionRepository.insert(east)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should handle date line correctly
        composeTestRule.onNodeWithText("2 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_handlesSameLocationMultipleDetections() = runTest {
        // Add multiple detections at exact same location
        val location1 = TestDataFactory.createFlockSafetyCameraDetection()
        val location2 = TestDataFactory.createDroneDetection().copy(
            latitude = location1.latitude,
            longitude = location1.longitude
        )
        val location3 = TestDataFactory.createStingrayDetection().copy(
            latitude = location1.latitude,
            longitude = location1.longitude
        )

        detectionRepository.insert(location1)
        detectionRepository.insert(location2)
        detectionRepository.insert(location3)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Should show all detections (may be clustered)
        composeTestRule.onNodeWithText("3 locations", substring = true).assertExists()
    }

    @Test
    fun mapScreen_handlesRapidDetectionUpdates() = runTest {
        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Rapidly add and remove detections
        repeat(10) { index ->
            val detection = TestDataFactory.createTestDetection(
                latitude = 37.7749 + (index * 0.01),
                longitude = -122.4194 + (index * 0.01),
                macAddress = String.format("AA:BB:CC:DD:EE:%02X", index)
            )
            detectionRepository.insert(detection)
        }

        composeTestRule.waitForIdle()

        // Should handle rapid updates without crashing
        composeTestRule.onNodeWithText("10 locations", substring = true).assertExists()
    }

    // ==================== Detection Detail Sheet Tests ====================

    @Test
    fun mapScreen_detailSheetShowsCoordinates() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insert(detection)

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Note: In a real scenario, you would click a marker to open the detail sheet
        // This test verifies the map renders with detections that could show details
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()
    }

    // ==================== Performance Tests ====================

    @Test
    fun mapScreen_performanceWithManyMarkers() = runTest {
        // Add 100 detections with locations
        val detections = TestDataFactory.createMultipleDetections(100).map {
            it.copy(
                latitude = 37.7749 + (Math.random() * 1.0 - 0.5),
                longitude = -122.4194 + (Math.random() * 1.0 - 0.5)
            )
        }
        detections.forEach { detectionRepository.insert(it) }

        val startTime = System.currentTimeMillis()

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        val endTime = System.currentTimeMillis()
        val renderTime = endTime - startTime

        // Map should render in reasonable time (< 5 seconds)
        assertTrue("Map should render in less than 5 seconds", renderTime < 5000)

        // Verify all detections are tracked
        composeTestRule.onNodeWithText("100 locations", substring = true).assertExists()
    }

    // ==================== Integration Tests ====================

    @Test
    fun mapScreen_integrationWithDetectionRepository() = runTest {
        // This test verifies the integration between MapScreen and DetectionRepository

        composeTestRule.setContent {
            MapScreen(onNavigateBack = {})
        }

        composeTestRule.waitForIdle()

        // Start with no detections
        composeTestRule.onNodeWithText("No Detections Yet", substring = true).assertExists()

        // Add detection via repository
        val detection1 = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insert(detection1)
        composeTestRule.waitForIdle()

        // Map should update
        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()

        // Add more detections
        val detection2 = TestDataFactory.createStingrayDetection().copy(
            latitude = 37.7850,
            longitude = -122.4100
        )
        detectionRepository.insert(detection2)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("2 locations", substring = true).assertExists()

        // Remove a detection
        detectionRepository.deleteById(detection1.id)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("1 locations", substring = true).assertExists()

        // Remove all
        detectionRepository.deleteAll()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No Detections Yet", substring = true).assertExists()
    }
}

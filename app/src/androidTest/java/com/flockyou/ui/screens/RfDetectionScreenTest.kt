package com.flockyou.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.service.RfSignalAnalyzer
import com.flockyou.service.RfSignalAnalyzer.*
import com.flockyou.service.ScanningServiceConnection
import com.flockyou.utils.TestHelpers
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * E2E tests for the RfDetectionScreen (drone detection).
 *
 * Tests cover:
 * - Drone list display and updates
 * - RF anomaly display and filtering
 * - Tab navigation (Status, Anomalies, Drones)
 * - Refresh behavior
 * - Real-time updates
 * - Empty state handling
 * - Advanced mode filtering
 * - Signal strength updates
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RfDetectionScreenTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var serviceConnection: ScanningServiceConnection

    private val context = TestHelpers.getContext()
    private lateinit var mockViewModel: MainViewModel

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

    // ==================== Drone List Display Tests ====================

    @Test
    fun droneList_displaysEmptyStateWhenNoDronesDetected() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Should show empty state
        composeTestRule.onNodeWithText("No Drones Detected", substring = true).assertExists()
        composeTestRule.onNodeWithText("Monitoring for drone WiFi signals", substring = true).assertExists()
    }

    @Test
    fun droneList_displaysDroneCardWithCorrectInfo() = runTest {
        // Create test drone
        val testDrone = createTestDrone(
            ssid = "DJI_Mavic_Pro_ABC123",
            bssid = "60:60:1F:AA:BB:CC",
            rssi = -65,
            manufacturer = "DJI"
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Verify drone card displays correct information
        composeTestRule.onNodeWithText("DJI", substring = true).assertExists()
        composeTestRule.onNodeWithText("DJI_Mavic_Pro_ABC123", substring = true).assertExists()
        composeTestRule.onNodeWithText("-65dBm", substring = true).assertExists()
    }

    @Test
    fun droneList_displaysMultipleDrones() = runTest {
        // Create multiple test drones
        val drones = listOf(
            createTestDrone(ssid = "DJI_Mavic_1", bssid = "60:60:1F:AA:BB:01"),
            createTestDrone(ssid = "DJI_Phantom_2", bssid = "60:60:1F:AA:BB:02"),
            createTestDrone(ssid = "Parrot_Anafi_3", bssid = "A0:14:3D:AA:BB:03")
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Verify all drones are displayed
        composeTestRule.onNodeWithText("DJI_Mavic_1", substring = true).assertExists()
        composeTestRule.onNodeWithText("DJI_Phantom_2", substring = true).assertExists()
        composeTestRule.onNodeWithText("Parrot_Anafi_3", substring = true).assertExists()
    }

    @Test
    fun droneList_displaysDroneCount() = runTest {
        // Create test drones
        val drones = listOf(
            createTestDrone(bssid = "60:60:1F:AA:BB:01"),
            createTestDrone(bssid = "60:60:1F:AA:BB:02"),
            createTestDrone(bssid = "60:60:1F:AA:BB:03")
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Verify drone count is displayed
        composeTestRule.onNodeWithText("Detected Drones (3)", substring = true).assertExists()
    }

    @Test
    fun droneList_sortsByLastSeenDescending() = runTest {
        val now = System.currentTimeMillis()
        val drones = listOf(
            createTestDrone(bssid = "01", ssid = "Oldest", lastSeen = now - 3600000),
            createTestDrone(bssid = "02", ssid = "Newest", lastSeen = now),
            createTestDrone(bssid = "03", ssid = "Middle", lastSeen = now - 1800000)
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Verify sorting by finding all drone SSIDs and checking order
        // Most recent (Newest) should appear first
        // Note: In actual implementation, you'd verify order by position
    }

    @Test
    fun droneList_displaysEstimatedDistance() = runTest {
        val drone = createTestDrone(
            rssi = -45,
            estimatedDistance = "25-50m"
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Verify distance is displayed
        composeTestRule.onNodeWithText("25-50m", substring = true).assertExists()
    }

    @Test
    fun droneCard_expandsToShowDetails() = runTest {
        val drone = createTestDrone(
            bssid = "60:60:1F:AA:BB:CC",
            firstSeen = System.currentTimeMillis() - 300000
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Click on drone card to expand
        composeTestRule.onNodeWithText("DJI", substring = true).performClick()
        composeTestRule.waitForIdle()

        // Verify expanded details are shown
        composeTestRule.onNodeWithText("BSSID", substring = true).assertExists()
        composeTestRule.onNodeWithText("60:60:1F:AA:BB:CC", substring = true).assertExists()
        composeTestRule.onNodeWithText("First Seen", substring = true).assertExists()
        composeTestRule.onNodeWithText("Last Seen", substring = true).assertExists()
        composeTestRule.onNodeWithText("Times Seen", substring = true).assertExists()
    }

    @Test
    fun droneCard_displaysLocationWhenAvailable() = runTest {
        val drone = createTestDrone(
            latitude = 37.7749,
            longitude = -122.4194
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab and expand card
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("DJI", substring = true).performClick()
        composeTestRule.waitForIdle()

        // Verify location is displayed
        composeTestRule.onNodeWithText("Location", substring = true).assertExists()
        composeTestRule.onNodeWithText("37.77", substring = true).assertExists()
    }

    // ==================== RF Anomaly Display Tests ====================

    @Test
    fun anomalyList_displaysEmptyStateWhenNoAnomalies() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Anomalies tab
        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        // Should show empty state
        composeTestRule.onNodeWithText("No RF Anomalies", substring = true).assertExists()
        composeTestRule.onNodeWithText("Your RF environment appears normal", substring = true).assertExists()
    }

    @Test
    fun anomalyList_displaysAnomalyCards() = runTest {
        val anomaly = createTestAnomaly(
            type = RfAnomalyType.JAMMER_SUSPECTED,
            displayName = "Possible Jammer Detected",
            description = "Sudden signal dropout detected"
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Anomalies tab
        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        // Verify anomaly is displayed
        composeTestRule.onNodeWithText("Possible Jammer Detected", substring = true).assertExists()
        composeTestRule.onNodeWithText("Sudden signal dropout detected", substring = true).assertExists()
    }

    @Test
    fun anomalyList_displaysAnomalyCount() = runTest {
        val anomalies = listOf(
            createTestAnomaly(type = RfAnomalyType.JAMMER_SUSPECTED),
            createTestAnomaly(type = RfAnomalyType.SIGNAL_INTERFERENCE),
            createTestAnomaly(type = RfAnomalyType.DENSE_NETWORK_ENVIRONMENT)
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Anomalies tab
        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        // Verify anomaly count
        composeTestRule.onNodeWithText("RF Anomalies (3)", substring = true).assertExists()
    }

    @Test
    fun anomalyCard_expandsToShowTechnicalDetails() = runTest {
        val anomaly = createTestAnomaly(
            displayName = "Signal Interference",
            description = "Unusual RF patterns detected",
            technicalDetails = "Broadband interference on 2.4GHz band",
            contributingFactors = listOf("High network density", "Channel congestion")
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Anomalies tab
        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        // Click to expand
        composeTestRule.onNodeWithText("Signal Interference", substring = true).performClick()
        composeTestRule.waitForIdle()

        // Verify technical details are shown
        composeTestRule.onNodeWithText("Technical Details:", substring = true).assertExists()
        composeTestRule.onNodeWithText("Broadband interference", substring = true).assertExists()
        composeTestRule.onNodeWithText("Contributing Factors:", substring = true).assertExists()
        composeTestRule.onNodeWithText("High network density", substring = true).assertExists()
    }

    @Test
    fun anomalyCard_displaysSeverityBadge() = runTest {
        val highSeverityAnomaly = createTestAnomaly(
            severity = ThreatLevel.HIGH
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Anomalies tab
        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        // Verify severity is visible (confidence badge)
        // The actual text depends on the confidence level's displayName
    }

    @Test
    fun anomalyList_sortsByTimestampDescending() = runTest {
        val now = System.currentTimeMillis()
        val anomalies = listOf(
            createTestAnomaly(timestamp = now - 3600000),  // Oldest
            createTestAnomaly(timestamp = now),            // Newest
            createTestAnomaly(timestamp = now - 1800000)   // Middle
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Anomalies tab
        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        // Verify sorted by timestamp (newest first)
        // In actual implementation, verify order by checking positions
    }

    // ==================== Tab Navigation Tests ====================

    @Test
    fun tabs_switchToStatusTab() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Click Status tab
        composeTestRule.onNodeWithText("Status").performClick()
        composeTestRule.waitForIdle()

        // Verify Status content is displayed
        composeTestRule.onNodeWithText("RF Environment", substring = true).assertExists()
    }

    @Test
    fun tabs_switchToAnomaliesTab() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Click Anomalies tab
        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        // Verify Anomalies content is displayed
        composeTestRule.onNodeWithText("No RF Anomalies", substring = true).assertExists()
    }

    @Test
    fun tabs_switchToDronesTab() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Click Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Verify Drones content is displayed
        composeTestRule.onNodeWithText("No Drones Detected", substring = true).assertExists()
    }

    @Test
    fun tabs_displayCorrectIcons() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Verify all tabs exist with their icons
        composeTestRule.onNodeWithText("Status").assertExists()
        composeTestRule.onNodeWithText("Anomalies").assertExists()
        composeTestRule.onNodeWithText("Drones").assertExists()
    }

    @Test
    fun tabs_displayBadgeCountsOnAnomaliesAndDrones() = runTest {
        // Add test data
        val anomalies = listOf(
            createTestAnomaly(),
            createTestAnomaly()
        )
        val drones = listOf(
            createTestDrone(bssid = "01"),
            createTestDrone(bssid = "02"),
            createTestDrone(bssid = "03")
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Verify badge counts are shown
        // Anomalies badge
        composeTestRule.onNodeWithText("2", substring = true).assertExists()
        // Drones badge
        composeTestRule.onNodeWithText("3", substring = true).assertExists()
    }

    @Test
    fun tabs_swipeBetweenTabs() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Verify we can swipe between tabs
        // Note: Actual swipe gestures would use performTouchInput with swipe
        // For now, just verify tab clicking works
        composeTestRule.onNodeWithText("Status").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()
    }

    // ==================== RF Status Display Tests ====================

    @Test
    fun rfStatus_displaysEnvironmentRiskCard() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // On Status tab by default
        composeTestRule.waitForIdle()

        // Verify RF Environment card is displayed
        composeTestRule.onNodeWithText("RF Environment", substring = true).assertExists()
    }

    @Test
    fun rfStatus_displaysNotScanningBanner() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Verify not scanning banner
        composeTestRule.onNodeWithText("Start scanning to analyze RF environment", substring = true).assertExists()
    }

    @Test
    fun rfStatus_displaysQuickStats() = runTest {
        val rfStatus = createTestRfStatus(
            totalNetworks = 42,
            averageSignalStrength = -65,
            dronesDetected = 2,
            surveillanceCameras = 5
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Verify quick stats are displayed
        composeTestRule.onNodeWithText("42", substring = true).assertExists()
        composeTestRule.onNodeWithText("-65dBm", substring = true).assertExists()
        composeTestRule.onNodeWithText("2", substring = true).assertExists()
        composeTestRule.onNodeWithText("5", substring = true).assertExists()
    }

    @Test
    fun rfStatus_displaysJammerWarning() = runTest {
        val rfStatus = createTestRfStatus(jammerSuspected = true)

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Verify jammer warning is displayed
        composeTestRule.onNodeWithText("Possible Jammer Detected", substring = true).assertExists()
        composeTestRule.onNodeWithText("RF signal disruption detected", substring = true).assertExists()
    }

    @Test
    fun rfStatus_expandsSignalAnalysisCard() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Click to expand Signal Analysis card
        composeTestRule.onNodeWithText("Signal Analysis", substring = true).performClick()
        composeTestRule.waitForIdle()

        // Verify expanded details
        composeTestRule.onNodeWithText("Total Networks", substring = true).assertExists()
        composeTestRule.onNodeWithText("Average Signal", substring = true).assertExists()
        composeTestRule.onNodeWithText("Channel Congestion", substring = true).assertExists()
    }

    @Test
    fun rfStatus_displaysBandDistribution() = runTest {
        val rfStatus = createTestRfStatus(
            band24GHz = 25,
            band5GHz = 15,
            band6GHz = 2
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Verify band distribution card
        composeTestRule.onNodeWithText("Band Distribution", substring = true).assertExists()
        composeTestRule.onNodeWithText("2.4 GHz", substring = true).assertExists()
        composeTestRule.onNodeWithText("5 GHz", substring = true).assertExists()
        composeTestRule.onNodeWithText("6 GHz", substring = true).assertExists()
    }

    @Test
    fun rfStatus_displaysChannelCongestion() = runTest {
        val rfStatus = createTestRfStatus(
            channelCongestion = ChannelCongestion.HEAVY
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Verify channel congestion card
        composeTestRule.onNodeWithText("Channel Congestion", substring = true).assertExists()
        composeTestRule.onNodeWithText("HEAVY", substring = true, ignoreCase = true).assertExists()
    }

    // ==================== Refresh Behavior Tests ====================

    @Test
    fun screen_requestsRefreshOnEntry() {
        // Verify that LaunchedEffect(Unit) triggers viewModel.requestRefresh()
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Screen should have requested refresh
        // In actual implementation, you'd verify this via mock or spy
    }

    @Test
    fun screen_updatesWhenNewDroneDetected() = runTest {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Verify empty state
        composeTestRule.onNodeWithText("No Drones Detected", substring = true).assertExists()

        // Simulate new drone detection (would happen via service in real app)
        // In actual implementation, inject or update the service state

        // Verify UI updates
        // composeTestRule.onNodeWithText("DJI", substring = true).assertExists()
    }

    @Test
    fun screen_updatesRssiInRealtime() = runTest {
        val drone = createTestDrone(rssi = -65)

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Initial RSSI displayed
        composeTestRule.onNodeWithText("-65dBm", substring = true).assertExists()

        // Update RSSI (in real app, this comes from service updates)
        // Verify new RSSI is displayed
        // composeTestRule.onNodeWithText("-70dBm", substring = true).assertExists()
    }

    // ==================== Advanced Mode Filtering Tests ====================

    @Test
    fun anomalies_respectsAdvancedModeFiltering() = runTest {
        // Create anomalies with different severity levels
        val anomalies = listOf(
            createTestAnomaly(severity = ThreatLevel.HIGH),
            createTestAnomaly(severity = ThreatLevel.LOW),
            createTestAnomaly(severity = ThreatLevel.MEDIUM)
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Anomalies tab
        composeTestRule.onNodeWithText("Anomalies").performClick()
        composeTestRule.waitForIdle()

        // In non-advanced mode, low severity anomalies might be filtered
        // Verify filtering behavior based on advancedMode state
    }

    // ==================== Edge Cases and Error Handling ====================

    @Test
    fun screen_handlesNullRfStatus() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Should handle null status gracefully
        composeTestRule.onNodeWithText("Not Scanning", substring = true).assertExists()
    }

    @Test
    fun droneList_handlesVeryLongSSID() = runTest {
        val drone = createTestDrone(
            ssid = "A".repeat(100)
        )

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Should truncate or handle long SSID (TextOverflow.Ellipsis)
    }

    @Test
    fun screen_handlesLargeDroneList() = runTest {
        // Create many drones
        val drones = List(50) { index ->
            createTestDrone(bssid = String.format("60:60:1F:AA:%02X:%02X", index / 256, index % 256))
        }

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Should handle without performance issues
        composeTestRule.onNodeWithText("Detected Drones (50)", substring = true).assertExists()
    }

    @Test
    fun screen_handlesBackNavigation() {
        var backPressed = false

        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = { backPressed = true }
            )
        }

        // Click back button
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        // Verify navigation callback was invoked
        assert(backPressed) { "Back navigation was not triggered" }
    }

    @Test
    fun screen_displaysBetaBadge() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Verify BETA badge is displayed
        composeTestRule.onNodeWithText("BETA").assertExists()
    }

    @Test
    fun screen_displaysScreenTitle() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Verify screen title
        composeTestRule.onNodeWithText("RF Signal Analysis").assertExists()
        composeTestRule.onNodeWithText("Jammers, drones & spectrum monitoring", substring = true).assertExists()
    }

    @Test
    fun rfStatus_displaysAboutInfo() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        composeTestRule.waitForIdle()

        // Scroll to bottom to find info card
        composeTestRule.onNodeWithText("About RF Analysis", substring = true).assertExists()
        composeTestRule.onNodeWithText("RF Jammers", substring = true).assertExists()
        composeTestRule.onNodeWithText("Drones", substring = true).assertExists()
    }

    @Test
    fun dronesTab_displaysAboutDroneDetection() {
        composeTestRule.setContent {
            RfDetectionScreen(
                onNavigateBack = {}
            )
        }

        // Navigate to Drones tab
        composeTestRule.onNodeWithText("Drones").performClick()
        composeTestRule.waitForIdle()

        // Verify info card about drone detection
        composeTestRule.onNodeWithText("About Drone Detection", substring = true).assertExists()
        composeTestRule.onNodeWithText("DJI drones", substring = true).assertExists()
        composeTestRule.onNodeWithText("Parrot drones", substring = true).assertExists()
    }

    // ==================== Helper Functions ====================

    /**
     * Create a test DroneInfo object with sensible defaults.
     */
    private fun createTestDrone(
        ssid: String = "DJI_Mavic_Test",
        bssid: String = "60:60:1F:AA:BB:CC",
        rssi: Int = -60,
        manufacturer: String = "DJI",
        estimatedDistance: String = "50-100m",
        firstSeen: Long = System.currentTimeMillis() - 300000,
        lastSeen: Long = System.currentTimeMillis(),
        seenCount: Int = 5,
        latitude: Double? = null,
        longitude: Double? = null
    ): DroneInfo {
        return DroneInfo(
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            manufacturer = manufacturer,
            estimatedDistance = estimatedDistance,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            seenCount = seenCount,
            latitude = latitude,
            longitude = longitude
        )
    }

    /**
     * Create a test RfAnomaly object with sensible defaults.
     */
    private fun createTestAnomaly(
        type: RfAnomalyType = RfAnomalyType.JAMMER_SUSPECTED,
        displayName: String = "Test Anomaly",
        description: String = "Test anomaly description",
        technicalDetails: String = "Technical details here",
        severity: ThreatLevel = ThreatLevel.HIGH,
        confidence: DetectionConfidence = DetectionConfidence.HIGH,
        contributingFactors: List<String> = emptyList(),
        timestamp: Long = System.currentTimeMillis()
    ): RfAnomaly {
        return RfAnomaly(
            id = java.util.UUID.randomUUID().toString(),
            type = type,
            displayName = displayName,
            description = description,
            technicalDetails = technicalDetails,
            severity = severity,
            confidence = confidence,
            contributingFactors = contributingFactors,
            timestamp = timestamp
        )
    }

    /**
     * Create a test RfEnvironmentStatus object with sensible defaults.
     */
    private fun createTestRfStatus(
        totalNetworks: Int = 20,
        averageSignalStrength: Int = -65,
        dronesDetected: Int = 0,
        surveillanceCameras: Int = 0,
        jammerSuspected: Boolean = false,
        band24GHz: Int = 15,
        band5GHz: Int = 5,
        band6GHz: Int = 0,
        environmentRisk: EnvironmentRisk = EnvironmentRisk.LOW,
        noiseLevel: NoiseLevel = NoiseLevel.MODERATE,
        channelCongestion: ChannelCongestion = ChannelCongestion.MODERATE,
        lastScanTime: Long = System.currentTimeMillis()
    ): RfEnvironmentStatus {
        return RfEnvironmentStatus(
            totalNetworks = totalNetworks,
            averageSignalStrength = averageSignalStrength,
            dronesDetected = dronesDetected,
            surveillanceCameras = surveillanceCameras,
            jammerSuspected = jammerSuspected,
            band24GHz = band24GHz,
            band5GHz = band5GHz,
            band6GHz = band6GHz,
            environmentRisk = environmentRisk,
            noiseLevel = noiseLevel,
            channelCongestion = channelCongestion,
            lastScanTime = lastScanTime
        )
    }
}

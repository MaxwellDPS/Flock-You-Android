package com.flockyou.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.EphemeralDetectionRepository
import com.flockyou.utils.TestDataFactory
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
 * End-to-end integration tests for complete detection flows.
 *
 * Tests cover:
 * - Detection storage and retrieval
 * - Detection protocol handling
 * - Ephemeral mode integration
 * - Location storage settings
 * - Deduplication and updates
 * - Multi-protocol detection handling
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DetectionFlowE2ETest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var ephemeralDetectionRepository: EphemeralDetectionRepository

    @Inject
    lateinit var privacySettingsRepository: PrivacySettingsRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        runBlocking {
            detectionRepository.deleteAll()
            ephemeralDetectionRepository.clear()
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            detectionRepository.deleteAll()
            ephemeralDetectionRepository.clear()
        }
    }

    // ==================== Detection Storage Flow Tests ====================

    @Test
    fun detectionFlow_wifiDetectionStoredAndRetrieved() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()

        assertEquals("Should have 1 detection", 1, detections.size)
        assertEquals("Protocol should be WiFi", DetectionProtocol.WIFI, detections[0].protocol)
    }

    @Test
    fun detectionFlow_cellularDetectionStoredAndRetrieved() = runTest {
        val detection = TestDataFactory.createStingrayDetection()
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()

        assertEquals("Should have 1 detection", 1, detections.size)
        assertEquals("Protocol should be Cellular", DetectionProtocol.CELLULAR, detections[0].protocol)
    }

    @Test
    fun detectionFlow_audioDetectionStoredAndRetrieved() = runTest {
        val detection = TestDataFactory.createUltrasonicBeaconDetection()
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()

        assertEquals("Should have 1 detection", 1, detections.size)
        assertEquals("Protocol should be Audio", DetectionProtocol.AUDIO, detections[0].protocol)
    }

    @Test
    fun detectionFlow_satelliteDetectionStoredAndRetrieved() = runTest {
        val detection = TestDataFactory.createSatelliteDetection()
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()

        assertEquals("Should have 1 detection", 1, detections.size)
        assertEquals("Protocol should be Satellite", DetectionProtocol.SATELLITE, detections[0].protocol)
    }

    // ==================== Multi-Protocol Flow Tests ====================

    @Test
    fun detectionFlow_allProtocolsStoredTogether() = runTest {
        val wifiDetection = TestDataFactory.createFlockSafetyCameraDetection()
        val cellularDetection = TestDataFactory.createStingrayDetection()
        val audioDetection = TestDataFactory.createUltrasonicBeaconDetection()
        val satelliteDetection = TestDataFactory.createSatelliteDetection()
        val droneDetection = TestDataFactory.createDroneDetection()

        detectionRepository.insert(wifiDetection)
        detectionRepository.insert(cellularDetection)
        detectionRepository.insert(audioDetection)
        detectionRepository.insert(satelliteDetection)
        detectionRepository.insert(droneDetection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Should have 5 detections", 5, detections.size)

        val protocols = detections.map { it.protocol }.toSet()
        assertTrue("Should have WiFi", protocols.contains(DetectionProtocol.WIFI))
        assertTrue("Should have Cellular", protocols.contains(DetectionProtocol.CELLULAR))
        assertTrue("Should have Audio", protocols.contains(DetectionProtocol.AUDIO))
        assertTrue("Should have Satellite", protocols.contains(DetectionProtocol.SATELLITE))
    }

    @Test
    fun detectionFlow_filterByProtocol() = runTest {
        val detections = TestDataFactory.createMixedProtocolDetections()
        detections.forEach { detectionRepository.insert(it) }

        val wifiDetections = detectionRepository.getDetectionsByProtocol(DetectionProtocol.WIFI).first()
        val cellularDetections = detectionRepository.getDetectionsByProtocol(DetectionProtocol.CELLULAR).first()

        assertTrue("Should have WiFi detections", wifiDetections.isNotEmpty())
        assertTrue("Should have Cellular detections", cellularDetections.isNotEmpty())
        assertTrue(
            "WiFi and Cellular should be different counts",
            wifiDetections.size != cellularDetections.size || wifiDetections.isEmpty()
        )
    }

    // ==================== Threat Level Flow Tests ====================

    @Test
    fun detectionFlow_filterByThreatLevel() = runTest {
        val criticalDetection = TestDataFactory.createStingrayDetection()
        val highDetection = TestDataFactory.createFlockSafetyCameraDetection()
        val mediumDetection = TestDataFactory.createDroneDetection()
        val lowDetection = TestDataFactory.createTestDetection(threatLevel = ThreatLevel.LOW)

        detectionRepository.insert(criticalDetection)
        detectionRepository.insert(highDetection)
        detectionRepository.insert(mediumDetection)
        detectionRepository.insert(lowDetection)

        val criticalOnly = detectionRepository.getDetectionsByThreatLevel(ThreatLevel.CRITICAL).first()
        assertEquals("Should have 1 critical detection", 1, criticalOnly.size)
    }

    @Test
    fun detectionFlow_threatLevelHierarchy() = runTest {
        val detection = TestDataFactory.createStingrayDetection()
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        val threatLevel = detections[0].threatLevel

        assertEquals("Stingray should be critical", ThreatLevel.CRITICAL, threatLevel)
        assertTrue("Critical should be highest priority", threatLevel.ordinal <= ThreatLevel.HIGH.ordinal)
    }

    // ==================== Ephemeral Mode Flow Tests ====================

    @Test
    fun detectionFlow_ephemeralModeStoresInMemory() = runTest {
        privacySettingsRepository.setEphemeralModeEnabled(true)

        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        ephemeralDetectionRepository.add(detection)

        val ephemeralDetections = ephemeralDetectionRepository.getAll()
        assertEquals("Should have 1 ephemeral detection", 1, ephemeralDetections.size)

        // Persistent database should be empty
        val persistentDetections = detectionRepository.getAllDetections().first()
        assertTrue("Persistent database should be empty", persistentDetections.isEmpty())
    }

    @Test
    fun detectionFlow_ephemeralModeClearsOnClear() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        ephemeralDetectionRepository.add(detection)

        assertEquals("Should have 1 detection", 1, ephemeralDetectionRepository.getAll().size)

        ephemeralDetectionRepository.clear()

        assertEquals("Should have 0 detections after clear", 0, ephemeralDetectionRepository.getAll().size)
    }

    // ==================== Location Flow Tests ====================

    @Test
    fun detectionFlow_detectionWithLocation() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Latitude should be stored", 37.7749, detections[0].latitude!!, 0.0001)
        assertEquals("Longitude should be stored", -122.4194, detections[0].longitude!!, 0.0001)
    }

    @Test
    fun detectionFlow_detectionWithoutLocation() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = null,
            longitude = null
        )
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertNull("Latitude should be null", detections[0].latitude)
        assertNull("Longitude should be null", detections[0].longitude)
    }

    // ==================== Deduplication Flow Tests ====================

    @Test
    fun detectionFlow_updateExistingDetection() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insert(detection)

        val updated = detection.copy(
            seenCount = 5,
            lastSeen = System.currentTimeMillis()
        )
        detectionRepository.update(updated)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Should still have 1 detection", 1, detections.size)
        assertEquals("Seen count should be updated", 5, detections[0].seenCount)
    }

    @Test
    fun detectionFlow_incrementSeenCount() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection().copy(seenCount = 1)
        detectionRepository.insert(detection)

        val updated = detection.copy(seenCount = detection.seenCount + 1)
        detectionRepository.update(updated)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Seen count should be incremented", 2, detections[0].seenCount)
    }

    // ==================== Query Flow Tests ====================

    @Test
    fun detectionFlow_queryActiveDetections() = runTest {
        val activeDetection = TestDataFactory.createTestDetection(isActive = true)
        val inactiveDetection = TestDataFactory.createTestDetection(
            isActive = false,
            macAddress = "11:22:33:44:55:66"
        )

        detectionRepository.insert(activeDetection)
        detectionRepository.insert(inactiveDetection)

        val activeOnly = detectionRepository.getActiveDetections().first()
        assertTrue("Should have at least 1 active detection", activeOnly.isNotEmpty())
        assertTrue("All results should be active", activeOnly.all { it.isActive })
    }

    @Test
    fun detectionFlow_queryByDeviceType() = runTest {
        val camera = TestDataFactory.createFlockSafetyCameraDetection()
        val drone = TestDataFactory.createDroneDetection()

        detectionRepository.insert(camera)
        detectionRepository.insert(drone)

        val cameras = detectionRepository.getDetectionsByDeviceType(DeviceType.FLOCK_SAFETY_CAMERA).first()
        assertEquals("Should have 1 camera", 1, cameras.size)
        assertEquals("Should be camera type", DeviceType.FLOCK_SAFETY_CAMERA, cameras[0].deviceType)
    }

    // ==================== Edge Cases ====================

    @Test
    fun detectionFlow_handleLargeDataset() = runTest {
        val detections = TestDataFactory.createMultipleDetections(200)
        detections.forEach { detectionRepository.insert(it) }

        val allDetections = detectionRepository.getAllDetections().first()
        assertEquals("Should handle 200 detections", 200, allDetections.size)
    }

    @Test
    fun detectionFlow_handleRapidInserts() = runTest {
        repeat(50) { i ->
            val detection = TestDataFactory.createTestDetection(
                macAddress = String.format("AA:BB:CC:DD:EE:%02X", i)
            )
            detectionRepository.insert(detection)
        }

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Should have 50 detections", 50, detections.size)
    }

    @Test
    fun detectionFlow_deleteById() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        val id = detections[0].id

        detectionRepository.deleteById(id)

        val afterDelete = detectionRepository.getAllDetections().first()
        assertTrue("Detection should be deleted", afterDelete.isEmpty())
    }
}

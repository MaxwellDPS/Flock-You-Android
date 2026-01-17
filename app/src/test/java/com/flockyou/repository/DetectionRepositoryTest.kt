package com.flockyou.repository

import app.cash.turbine.test
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionDao
import com.flockyou.data.repository.DetectionRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DetectionRepository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetectionRepositoryTest {
    
    private lateinit var repository: DetectionRepository
    private lateinit var dao: DetectionDao
    
    @Before
    fun setup() {
        dao = mockk(relaxed = true)
        every { dao.getAllDetections() } returns flowOf(emptyList())
        every { dao.getActiveDetections() } returns flowOf(emptyList())
        every { dao.getTotalDetectionCount() } returns flowOf(0)
        every { dao.getHighThreatCount() } returns flowOf(0)
        every { dao.getDetectionsWithLocation() } returns flowOf(emptyList())
        repository = DetectionRepository(dao)
    }
    
    // ============ Get All Detections Tests ============
    
    @Test
    fun `allDetections returns flow from dao`() = runTest {
        val detections = listOf(createTestDetection())
        every { dao.getAllDetections() } returns flowOf(detections)
        repository = DetectionRepository(dao)
        
        repository.allDetections.test {
            assertThat(awaitItem()).isEqualTo(detections)
            awaitComplete()
        }
    }
    
    @Test
    fun `allDetections returns empty flow when no detections`() = runTest {
        every { dao.getAllDetections() } returns flowOf(emptyList())
        repository = DetectionRepository(dao)
        
        repository.allDetections.test {
            assertThat(awaitItem()).isEmpty()
            awaitComplete()
        }
    }
    
    // ============ Get Active Detections Tests ============
    
    @Test
    fun `activeDetections returns only active detections`() = runTest {
        val activeDetections = listOf(
            createTestDetection(id = "1", isActive = true),
            createTestDetection(id = "2", isActive = true)
        )
        every { dao.getActiveDetections() } returns flowOf(activeDetections)
        repository = DetectionRepository(dao)
        
        repository.activeDetections.test {
            val result = awaitItem()
            assertThat(result).hasSize(2)
            assertThat(result.all { it.isActive }).isTrue()
            awaitComplete()
        }
    }
    
    // ============ Get By Device Type Tests ============
    
    @Test
    fun `getDetectionsByDeviceType returns filtered detections`() = runTest {
        val flockDetections = listOf(createTestDetection(deviceType = DeviceType.FLOCK_SAFETY_CAMERA))
        every { dao.getDetectionsByDeviceType(DeviceType.FLOCK_SAFETY_CAMERA) } returns flowOf(flockDetections)
        
        repository.getDetectionsByDeviceType(DeviceType.FLOCK_SAFETY_CAMERA).test {
            val result = awaitItem()
            assertThat(result.all { it.deviceType == DeviceType.FLOCK_SAFETY_CAMERA }).isTrue()
            awaitComplete()
        }
    }
    
    // ============ Get By ID Tests ============
    
    @Test
    fun `getDetectionById returns correct detection`() = runTest {
        val detection = createTestDetection(id = "test-id")
        coEvery { dao.getDetectionById("test-id") } returns detection
        
        val result = repository.getDetectionById("test-id")
        
        assertThat(result).isEqualTo(detection)
    }
    
    @Test
    fun `getDetectionById returns null for non-existent id`() = runTest {
        coEvery { dao.getDetectionById("non-existent") } returns null
        
        val result = repository.getDetectionById("non-existent")
        
        assertThat(result).isNull()
    }
    
    // ============ Get By MAC Address Tests ============
    
    @Test
    fun `getDetectionByMacAddress returns correct detection`() = runTest {
        val detection = createTestDetection(macAddress = "AA:BB:CC:DD:EE:FF")
        coEvery { dao.getDetectionByMacAddress("AA:BB:CC:DD:EE:FF") } returns detection
        
        val result = repository.getDetectionByMacAddress("AA:BB:CC:DD:EE:FF")
        
        assertThat(result).isEqualTo(detection)
    }
    
    @Test
    fun `getDetectionByMacAddress returns null for unknown MAC`() = runTest {
        coEvery { dao.getDetectionByMacAddress("00:00:00:00:00:00") } returns null
        
        val result = repository.getDetectionByMacAddress("00:00:00:00:00:00")
        
        assertThat(result).isNull()
    }
    
    // ============ Insert Tests ============
    
    @Test
    fun `insertDetection calls dao insertDetection`() = runTest {
        val detection = createTestDetection()
        coEvery { dao.insertDetection(detection) } just Runs
        
        repository.insertDetection(detection)
        
        coVerify { dao.insertDetection(detection) }
    }
    
    @Test
    fun `insertDetections calls dao insertDetections`() = runTest {
        val detections = listOf(createTestDetection(), createTestDetection(id = "2"))
        coEvery { dao.insertDetections(detections) } just Runs
        
        repository.insertDetections(detections)
        
        coVerify { dao.insertDetections(detections) }
    }
    
    // ============ Update Tests ============
    
    @Test
    fun `updateDetection calls dao updateDetection`() = runTest {
        val detection = createTestDetection()
        coEvery { dao.updateDetection(detection) } just Runs
        
        repository.updateDetection(detection)
        
        coVerify { dao.updateDetection(detection) }
    }
    
    // ============ Delete Tests ============
    
    @Test
    fun `deleteDetection calls dao deleteDetection`() = runTest {
        val detection = createTestDetection()
        coEvery { dao.deleteDetection(detection) } just Runs
        
        repository.deleteDetection(detection)
        
        coVerify { dao.deleteDetection(detection) }
    }
    
    @Test
    fun `deleteAllDetections calls dao deleteAllDetections`() = runTest {
        coEvery { dao.deleteAllDetections() } just Runs
        
        repository.deleteAllDetections()
        
        coVerify { dao.deleteAllDetections() }
    }
    
    @Test
    fun `deleteOldDetections calls dao deleteOldDetections`() = runTest {
        val threshold = System.currentTimeMillis() - 86400000 // 24 hours ago
        coEvery { dao.deleteOldDetections(threshold) } just Runs
        
        repository.deleteOldDetections(threshold)
        
        coVerify { dao.deleteOldDetections(threshold) }
    }
    
    // ============ Mark Inactive Tests ============
    
    @Test
    fun `markInactive calls dao markInactive`() = runTest {
        val macAddress = "AA:BB:CC:DD:EE:FF"
        coEvery { dao.markInactive(macAddress) } just Runs
        
        repository.markInactive(macAddress)
        
        coVerify { dao.markInactive(macAddress) }
    }
    
    @Test
    fun `markOldInactive calls dao markOldInactive`() = runTest {
        val threshold = System.currentTimeMillis() - 60000
        coEvery { dao.markOldInactive(threshold) } just Runs
        
        repository.markOldInactive(threshold)
        
        coVerify { dao.markOldInactive(threshold) }
    }
    
    // ============ Count Tests ============
    
    @Test
    fun `totalDetectionCount returns dao count flow`() = runTest {
        every { dao.getTotalDetectionCount() } returns flowOf(5)
        repository = DetectionRepository(dao)
        
        repository.totalDetectionCount.test {
            assertThat(awaitItem()).isEqualTo(5)
            awaitComplete()
        }
    }
    
    @Test
    fun `highThreatCount returns dao high threat count flow`() = runTest {
        every { dao.getHighThreatCount() } returns flowOf(3)
        repository = DetectionRepository(dao)
        
        repository.highThreatCount.test {
            assertThat(awaitItem()).isEqualTo(3)
            awaitComplete()
        }
    }
    
    // ============ Location Tests ============
    
    @Test
    fun `detectionsWithLocation returns detections with coordinates`() = runTest {
        val detections = listOf(
            createTestDetection(latitude = 47.6062, longitude = -122.3321)
        )
        every { dao.getDetectionsWithLocation() } returns flowOf(detections)
        repository = DetectionRepository(dao)
        
        repository.detectionsWithLocation.test {
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat(result.first().latitude).isNotNull()
            assertThat(result.first().longitude).isNotNull()
            awaitComplete()
        }
    }
    
    // ============ Recent Detections Tests ============
    
    @Test
    fun `getRecentDetections returns detections since timestamp`() = runTest {
        val since = System.currentTimeMillis() - 3600000 // 1 hour ago
        val detections = listOf(createTestDetection())
        every { dao.getRecentDetections(since) } returns flowOf(detections)
        
        repository.getRecentDetections(since).test {
            assertThat(awaitItem()).hasSize(1)
            awaitComplete()
        }
    }
    
    // ============ Threat Level Tests ============
    
    @Test
    fun `getDetectionsByThreatLevel returns filtered detections`() = runTest {
        val criticalDetections = listOf(createTestDetection(threatScore = 95))
        every { dao.getDetectionsByThreatLevel(ThreatLevel.CRITICAL) } returns flowOf(criticalDetections)
        
        repository.getDetectionsByThreatLevel(ThreatLevel.CRITICAL).test {
            val result = awaitItem()
            assertThat(result.all { it.threatLevel == ThreatLevel.CRITICAL }).isTrue()
            awaitComplete()
        }
    }
    
    // ============ Helper Functions ============
    
    private fun createTestDetection(
        id: String = "test-id",
        macAddress: String = "AA:BB:CC:DD:EE:FF",
        deviceType: DeviceType = DeviceType.FLOCK_SAFETY_CAMERA,
        isActive: Boolean = true,
        threatScore: Int = 95,
        latitude: Double? = 47.6062,
        longitude: Double? = -122.3321
    ) = Detection(
        id = id,
        timestamp = System.currentTimeMillis(),
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType = deviceType,
        deviceName = null,
        macAddress = macAddress,
        ssid = "Flock_Camera_001",
        rssi = -65,
        signalStrength = SignalStrength.MEDIUM,
        latitude = latitude,
        longitude = longitude,
        threatLevel = scoreToThreatLevel(threatScore),
        threatScore = threatScore,
        manufacturer = "Flock Safety",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = null,
        isActive = isActive
    )
}

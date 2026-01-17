package com.flockyou.viewmodel

import app.cash.turbine.test
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.ui.screens.MainViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MainViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    
    private lateinit var viewModel: MainViewModel
    private lateinit var repository: DetectionRepository
    private val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
    }
    
    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
    
    // ============ Initialization Tests ============
    
    @Test
    fun `viewModel initializes with empty detection list`() = runTest {
        every { repository.getAllDetections() } returns flowOf(emptyList())
        every { repository.getActiveDetections() } returns flowOf(emptyList())
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.detections.test {
            assertThat(awaitItem()).isEmpty()
        }
    }
    
    @Test
    fun `viewModel loads detections from repository`() = runTest {
        val detections = listOf(createTestDetection())
        every { repository.getAllDetections() } returns flowOf(detections)
        every { repository.getActiveDetections() } returns flowOf(detections)
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.detections.test {
            assertThat(awaitItem()).hasSize(1)
        }
    }
    
    // ============ Scanning State Tests ============
    
    @Test
    fun `startScanning updates scanning state to true`() = runTest {
        setupViewModel()
        
        viewModel.startScanning()
        advanceUntilIdle()
        
        viewModel.isScanning.test {
            assertThat(awaitItem()).isTrue()
        }
    }
    
    @Test
    fun `stopScanning updates scanning state to false`() = runTest {
        setupViewModel()
        
        viewModel.startScanning()
        advanceUntilIdle()
        viewModel.stopScanning()
        advanceUntilIdle()
        
        viewModel.isScanning.test {
            assertThat(awaitItem()).isFalse()
        }
    }
    
    @Test
    fun `toggleScanning flips scanning state`() = runTest {
        setupViewModel()
        
        // Initially false
        viewModel.isScanning.test {
            assertThat(awaitItem()).isFalse()
        }
        
        // Toggle to true
        viewModel.toggleScanning()
        advanceUntilIdle()
        
        viewModel.isScanning.test {
            assertThat(awaitItem()).isTrue()
        }
        
        // Toggle back to false
        viewModel.toggleScanning()
        advanceUntilIdle()
        
        viewModel.isScanning.test {
            assertThat(awaitItem()).isFalse()
        }
    }
    
    // ============ Detection Count Tests ============
    
    @Test
    fun `detectionCount reflects number of detections`() = runTest {
        val detections = listOf(
            createTestDetection(id = "1"),
            createTestDetection(id = "2"),
            createTestDetection(id = "3")
        )
        every { repository.getAllDetections() } returns flowOf(detections)
        every { repository.getActiveDetections() } returns flowOf(detections)
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.detectionCount.test {
            assertThat(awaitItem()).isEqualTo(3)
        }
    }
    
    @Test
    fun `activeDetectionCount reflects only active detections`() = runTest {
        val allDetections = listOf(
            createTestDetection(id = "1", isActive = true),
            createTestDetection(id = "2", isActive = false),
            createTestDetection(id = "3", isActive = true)
        )
        val activeDetections = allDetections.filter { it.isActive }
        
        every { repository.getAllDetections() } returns flowOf(allDetections)
        every { repository.getActiveDetections() } returns flowOf(activeDetections)
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.activeDetectionCount.test {
            assertThat(awaitItem()).isEqualTo(2)
        }
    }
    
    // ============ Clear Detections Tests ============
    
    @Test
    fun `clearAllDetections calls repository deleteAll`() = runTest {
        setupViewModel()
        coEvery { repository.deleteAllDetections() } just Runs
        
        viewModel.clearAllDetections()
        advanceUntilIdle()
        
        coVerify { repository.deleteAllDetections() }
    }
    
    @Test
    fun `clearAllDetections updates detection list to empty`() = runTest {
        val detections = listOf(createTestDetection())
        every { repository.getAllDetections() } returns flowOf(detections) andThen flowOf(emptyList())
        every { repository.getActiveDetections() } returns flowOf(detections) andThen flowOf(emptyList())
        coEvery { repository.deleteAllDetections() } just Runs
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.clearAllDetections()
        advanceUntilIdle()
    }
    
    // ============ Filter Tests ============
    
    @Test
    fun `filterByDeviceType filters detections correctly`() = runTest {
        val detections = listOf(
            createTestDetection(id = "1", deviceType = DeviceType.FLOCK_SAFETY_CAMERA),
            createTestDetection(id = "2", deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR),
            createTestDetection(id = "3", deviceType = DeviceType.FLOCK_SAFETY_CAMERA)
        )
        every { repository.getAllDetections() } returns flowOf(detections)
        every { repository.getActiveDetections() } returns flowOf(detections)
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.filterByDeviceType(DeviceType.FLOCK_SAFETY_CAMERA)
        advanceUntilIdle()
        
        viewModel.filteredDetections.test {
            val filtered = awaitItem()
            assertThat(filtered).hasSize(2)
            assertThat(filtered.all { it.deviceType == DeviceType.FLOCK_SAFETY_CAMERA }).isTrue()
        }
    }
    
    @Test
    fun `clearFilter shows all detections`() = runTest {
        val detections = listOf(
            createTestDetection(id = "1", deviceType = DeviceType.FLOCK_SAFETY_CAMERA),
            createTestDetection(id = "2", deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR)
        )
        every { repository.getAllDetections() } returns flowOf(detections)
        every { repository.getActiveDetections() } returns flowOf(detections)
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.filterByDeviceType(DeviceType.FLOCK_SAFETY_CAMERA)
        advanceUntilIdle()
        viewModel.clearFilter()
        advanceUntilIdle()
        
        viewModel.filteredDetections.test {
            assertThat(awaitItem()).hasSize(2)
        }
    }
    
    // ============ Sort Tests ============
    
    @Test
    fun `detections sorted by threat level descending by default`() = runTest {
        val detections = listOf(
            createTestDetection(id = "1", threatScore = 50),
            createTestDetection(id = "2", threatScore = 100),
            createTestDetection(id = "3", threatScore = 75)
        )
        every { repository.getAllDetections() } returns flowOf(detections)
        every { repository.getActiveDetections() } returns flowOf(detections)
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.detections.test {
            val sorted = awaitItem()
            assertThat(sorted.map { it.threatScore }).isEqualTo(listOf(100, 75, 50))
        }
    }
    
    @Test
    fun `sortByTimestamp sorts detections by time`() = runTest {
        val now = System.currentTimeMillis()
        val detections = listOf(
            createTestDetection(id = "1", timestamp = now - 3000),
            createTestDetection(id = "2", timestamp = now - 1000),
            createTestDetection(id = "3", timestamp = now - 2000)
        )
        every { repository.getAllDetections() } returns flowOf(detections)
        every { repository.getActiveDetections() } returns flowOf(detections)
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.sortByTimestamp()
        advanceUntilIdle()
        
        viewModel.detections.test {
            val sorted = awaitItem()
            assertThat(sorted.map { it.id }).isEqualTo(listOf("2", "3", "1"))
        }
    }
    
    // ============ Selected Detection Tests ============
    
    @Test
    fun `selectDetection updates selected detection`() = runTest {
        val detection = createTestDetection()
        setupViewModel()
        
        viewModel.selectDetection(detection)
        
        viewModel.selectedDetection.test {
            assertThat(awaitItem()).isEqualTo(detection)
        }
    }
    
    @Test
    fun `clearSelection clears selected detection`() = runTest {
        val detection = createTestDetection()
        setupViewModel()
        
        viewModel.selectDetection(detection)
        viewModel.clearSelection()
        
        viewModel.selectedDetection.test {
            assertThat(awaitItem()).isNull()
        }
    }
    
    // ============ Error Handling Tests ============
    
    @Test
    fun `error state set when repository throws`() = runTest {
        every { repository.getAllDetections() } throws RuntimeException("Database error")
        
        viewModel = MainViewModel(repository)
        advanceUntilIdle()
        
        viewModel.error.test {
            assertThat(awaitItem()).isNotNull()
        }
    }
    
    @Test
    fun `clearError clears error state`() = runTest {
        setupViewModel()
        
        viewModel.clearError()
        
        viewModel.error.test {
            assertThat(awaitItem()).isNull()
        }
    }
    
    // ============ Loading State Tests ============
    
    @Test
    fun `isLoading true while fetching detections`() = runTest {
        every { repository.getAllDetections() } returns flowOf(emptyList())
        every { repository.getActiveDetections() } returns flowOf(emptyList())
        
        viewModel = MainViewModel(repository)
        
        // Initially loading
        viewModel.isLoading.test {
            // After idle, should be false
            advanceUntilIdle()
            assertThat(awaitItem()).isFalse()
        }
    }
    
    // ============ Helper Functions ============
    
    private fun setupViewModel() {
        every { repository.getAllDetections() } returns flowOf(emptyList())
        every { repository.getActiveDetections() } returns flowOf(emptyList())
        viewModel = MainViewModel(repository)
    }
    
    private fun createTestDetection(
        id: String = "test-id",
        deviceType: DeviceType = DeviceType.FLOCK_SAFETY_CAMERA,
        threatScore: Int = 95,
        isActive: Boolean = true,
        timestamp: Long = System.currentTimeMillis()
    ) = Detection(
        id = id,
        timestamp = timestamp,
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType = deviceType,
        deviceName = null,
        macAddress = "AA:BB:CC:DD:EE:FF",
        ssid = "Flock_Camera_001",
        rssi = -65,
        signalStrength = SignalStrength.MEDIUM,
        latitude = 47.6062,
        longitude = -122.3321,
        threatLevel = scoreToThreatLevel(threatScore),
        threatScore = threatScore,
        manufacturer = "Flock Safety",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = null,
        isActive = isActive
    )
}

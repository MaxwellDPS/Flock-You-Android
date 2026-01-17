package com.flockyou.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionDao
import com.flockyou.data.repository.FlockYouDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Database E2E tests.
 * Tests Room database operations for detection storage.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class DatabaseE2ETest {
    
    private lateinit var database: FlockYouDatabase
    private lateinit var detectionDao: DetectionDao
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            FlockYouDatabase::class.java
        ).allowMainThreadQueries().build()
        
        detectionDao = database.detectionDao()
    }
    
    @After
    fun teardown() {
        database.close()
    }
    
    // ============ Insert Tests ============
    
    @Test
    fun insertDetection_savesToDatabase() = runBlocking {
        val detection = createFlockDetection()
        
        detectionDao.insert(detection)
        
        val retrieved = detectionDao.getById(detection.id)
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.id).isEqualTo(detection.id)
    }
    
    @Test
    fun insertMultipleDetections_savesAll() = runBlocking {
        val detections = listOf(
            createFlockDetection(id = "1"),
            createRavenDetection(id = "2"),
            createPenguinDetection(id = "3")
        )
        
        detections.forEach { detectionDao.insert(it) }
        
        val all = detectionDao.getAllDetections().first()
        assertThat(all).hasSize(3)
    }
    
    @Test
    fun insertDuplicateId_replaces() = runBlocking {
        val original = createFlockDetection(id = "1", rssi = -60)
        val updated = createFlockDetection(id = "1", rssi = -50)
        
        detectionDao.insert(original)
        detectionDao.insert(updated)
        
        val retrieved = detectionDao.getById("1")
        assertThat(retrieved?.rssi).isEqualTo(-50)
    }
    
    // ============ Query Tests ============
    
    @Test
    fun getAllDetections_returnsAllDetections() = runBlocking {
        insertTestDetections()
        
        val all = detectionDao.getAllDetections().first()
        
        assertThat(all).isNotEmpty()
    }
    
    @Test
    fun getActiveDetections_returnsOnlyActive() = runBlocking {
        detectionDao.insert(createFlockDetection(id = "1", isActive = true))
        detectionDao.insert(createFlockDetection(id = "2", isActive = false))
        detectionDao.insert(createFlockDetection(id = "3", isActive = true))
        
        val active = detectionDao.getActiveDetections().first()
        
        assertThat(active).hasSize(2)
        assertThat(active.all { it.isActive }).isTrue()
    }
    
    @Test
    fun getByType_returnsMatchingType() = runBlocking {
        insertTestDetections()
        
        val flockDetections = detectionDao.getByType(DeviceType.FLOCK_SAFETY_CAMERA.name).first()
        
        assertThat(flockDetections).isNotEmpty()
        assertThat(flockDetections.all { it.deviceType == DeviceType.FLOCK_SAFETY_CAMERA }).isTrue()
    }
    
    @Test
    fun getByMacAddress_returnsMatchingDevice() = runBlocking {
        val mac = "AA:BB:CC:DD:EE:FF"
        detectionDao.insert(createFlockDetection(macAddress = mac))
        
        val detection = detectionDao.getByMacAddress(mac)
        
        assertThat(detection).isNotNull()
        assertThat(detection?.macAddress).isEqualTo(mac)
    }
    
    @Test
    fun getById_returnsCorrectDetection() = runBlocking {
        val id = "test-id-123"
        detectionDao.insert(createFlockDetection(id = id))
        
        val detection = detectionDao.getById(id)
        
        assertThat(detection).isNotNull()
        assertThat(detection?.id).isEqualTo(id)
    }
    
    @Test
    fun getById_nonExistent_returnsNull() = runBlocking {
        val detection = detectionDao.getById("non-existent-id")
        
        assertThat(detection).isNull()
    }
    
    // ============ Update Tests ============
    
    @Test
    fun updateDetection_modifiesCorrectly() = runBlocking {
        val original = createFlockDetection(id = "1", rssi = -70)
        detectionDao.insert(original)
        
        val updated = original.copy(rssi = -50, signalStrength = SignalStrength.GOOD)
        detectionDao.update(updated)
        
        val retrieved = detectionDao.getById("1")
        assertThat(retrieved?.rssi).isEqualTo(-50)
        assertThat(retrieved?.signalStrength).isEqualTo(SignalStrength.GOOD)
    }
    
    @Test
    fun markInactive_updatesOldDetections() = runBlocking {
        val oldTimestamp = System.currentTimeMillis() - 120000 // 2 minutes ago
        val recentTimestamp = System.currentTimeMillis()
        
        detectionDao.insert(createFlockDetection(id = "old", timestamp = oldTimestamp))
        detectionDao.insert(createFlockDetection(id = "recent", timestamp = recentTimestamp))
        
        detectionDao.markOldInactive(System.currentTimeMillis() - 60000) // 1 minute threshold
        
        val old = detectionDao.getById("old")
        val recent = detectionDao.getById("recent")
        
        assertThat(old?.isActive).isFalse()
        assertThat(recent?.isActive).isTrue()
    }
    
    // ============ Delete Tests ============
    
    @Test
    fun deleteDetection_removesFromDatabase() = runBlocking {
        val detection = createFlockDetection()
        detectionDao.insert(detection)
        
        detectionDao.delete(detection)
        
        val retrieved = detectionDao.getById(detection.id)
        assertThat(retrieved).isNull()
    }
    
    @Test
    fun deleteAllDetections_clearsDatabase() = runBlocking {
        insertTestDetections()
        
        detectionDao.deleteAll()
        
        val all = detectionDao.getAllDetections().first()
        assertThat(all).isEmpty()
    }
    
    // ============ Count Tests ============
    
    @Test
    fun getCount_returnsCorrectTotal() = runBlocking {
        insertTestDetections()
        
        val count = detectionDao.getCount()
        
        assertThat(count).isGreaterThan(0)
    }
    
    @Test
    fun getActiveCount_returnsCorrectActiveCount() = runBlocking {
        detectionDao.insert(createFlockDetection(id = "1", isActive = true))
        detectionDao.insert(createFlockDetection(id = "2", isActive = false))
        detectionDao.insert(createFlockDetection(id = "3", isActive = true))
        
        val activeCount = detectionDao.getActiveCount()
        
        assertThat(activeCount).isEqualTo(2)
    }
    
    // ============ Flow Emission Tests ============
    
    @Test
    fun detectionsFlow_emitsOnInsert() = runBlocking {
        val detection = createFlockDetection()
        
        detectionDao.insert(detection)
        
        val emissions = detectionDao.getAllDetections().first()
        assertThat(emissions).contains(detection)
    }
    
    @Test
    fun detectionsFlow_emitsOnUpdate() = runBlocking {
        val detection = createFlockDetection(id = "flow-test")
        detectionDao.insert(detection)
        
        val updated = detection.copy(rssi = -40)
        detectionDao.update(updated)
        
        val emissions = detectionDao.getAllDetections().first()
        assertThat(emissions.find { it.id == "flow-test" }?.rssi).isEqualTo(-40)
    }
    
    @Test
    fun detectionsFlow_emitsOnDelete() = runBlocking {
        val detection = createFlockDetection(id = "delete-test")
        detectionDao.insert(detection)
        detectionDao.delete(detection)
        
        val emissions = detectionDao.getAllDetections().first()
        assertThat(emissions.find { it.id == "delete-test" }).isNull()
    }
    
    // ============ Complex Query Tests ============
    
    @Test
    fun getDetectionsByThreatLevel_critical() = runBlocking {
        detectionDao.insert(createFlockDetection(id = "1", threatScore = 95)) // CRITICAL
        detectionDao.insert(createFlockDetection(id = "2", threatScore = 75)) // HIGH
        detectionDao.insert(createFlockDetection(id = "3", threatScore = 55)) // MEDIUM
        
        val all = detectionDao.getAllDetections().first()
        val critical = all.filter { it.threatLevel == ThreatLevel.CRITICAL }
        
        assertThat(critical).isNotEmpty()
    }
    
    @Test
    fun getDetectionsSortedByTimestamp() = runBlocking {
        val now = System.currentTimeMillis()
        detectionDao.insert(createFlockDetection(id = "1", timestamp = now - 3000))
        detectionDao.insert(createFlockDetection(id = "2", timestamp = now - 1000))
        detectionDao.insert(createFlockDetection(id = "3", timestamp = now - 2000))
        
        val all = detectionDao.getAllDetections().first()
        
        // Should be sorted by timestamp descending (most recent first)
        assertThat(all.map { it.id }).containsExactly("2", "3", "1").inOrder()
    }
    
    // ============ Raven-Specific Tests ============
    
    @Test
    fun ravenDetection_storesServiceUuids() = runBlocking {
        val serviceUuids = "[\"0000180a-0000-1000-8000-00805f9b34fb\",\"00003100-0000-1000-8000-00805f9b34fb\"]"
        val detection = createRavenDetection(serviceUuids = serviceUuids)
        
        detectionDao.insert(detection)
        
        val retrieved = detectionDao.getById(detection.id)
        assertThat(retrieved?.serviceUuids).isEqualTo(serviceUuids)
    }
    
    @Test
    fun ravenDetection_storesFirmwareVersion() = runBlocking {
        val detection = createRavenDetection(firmwareVersion = "1.3.x (Latest)")
        
        detectionDao.insert(detection)
        
        val retrieved = detectionDao.getById(detection.id)
        assertThat(retrieved?.firmwareVersion).isEqualTo("1.3.x (Latest)")
    }
    
    // ============ Location Tests ============
    
    @Test
    fun detection_storesLocation() = runBlocking {
        val lat = 47.6062
        val lng = -122.3321
        val detection = createFlockDetection(latitude = lat, longitude = lng)
        
        detectionDao.insert(detection)
        
        val retrieved = detectionDao.getById(detection.id)
        assertThat(retrieved?.latitude).isEqualTo(lat)
        assertThat(retrieved?.longitude).isEqualTo(lng)
    }
    
    @Test
    fun detection_handlesNullLocation() = runBlocking {
        val detection = createFlockDetection(latitude = null, longitude = null)
        
        detectionDao.insert(detection)
        
        val retrieved = detectionDao.getById(detection.id)
        assertThat(retrieved?.latitude).isNull()
        assertThat(retrieved?.longitude).isNull()
    }
    
    // ============ Helper Functions ============
    
    private suspend fun insertTestDetections() {
        detectionDao.insert(createFlockDetection(id = "flock-1"))
        detectionDao.insert(createRavenDetection(id = "raven-1"))
        detectionDao.insert(createPenguinDetection(id = "penguin-1"))
    }
    
    private fun createFlockDetection(
        id: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        rssi: Int = -65,
        macAddress: String = "AA:BB:CC:DD:EE:FF",
        isActive: Boolean = true,
        threatScore: Int = 95,
        latitude: Double? = 47.6062,
        longitude: Double? = -122.3321
    ) = Detection(
        id = id,
        timestamp = timestamp,
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
        deviceName = null,
        macAddress = macAddress,
        ssid = "Flock_Camera_001",
        rssi = rssi,
        signalStrength = rssiToSignalStrength(rssi),
        latitude = latitude,
        longitude = longitude,
        threatLevel = scoreToThreatLevel(threatScore),
        threatScore = threatScore,
        manufacturer = "Flock Safety",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = "[\"Flock Safety ALPR Camera\"]",
        isActive = isActive
    )
    
    private fun createRavenDetection(
        id: String = UUID.randomUUID().toString(),
        serviceUuids: String? = "[\"0000180a-0000-1000-8000-00805f9b34fb\"]",
        firmwareVersion: String = "1.3.x (Latest)"
    ) = Detection(
        id = id,
        timestamp = System.currentTimeMillis(),
        protocol = DetectionProtocol.BLUETOOTH_LE,
        detectionMethod = DetectionMethod.RAVEN_SERVICE_UUID,
        deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
        deviceName = "Raven-001",
        macAddress = "11:22:33:44:55:66",
        ssid = null,
        rssi = -72,
        signalStrength = SignalStrength.MEDIUM,
        latitude = 47.6062,
        longitude = -122.3321,
        threatLevel = ThreatLevel.CRITICAL,
        threatScore = 100,
        manufacturer = "SoundThinking/ShotSpotter",
        firmwareVersion = firmwareVersion,
        serviceUuids = serviceUuids,
        matchedPatterns = "[\"Raven Gunshot Detection Device\"]",
        isActive = true
    )
    
    private fun createPenguinDetection(id: String = UUID.randomUUID().toString()) = Detection(
        id = id,
        timestamp = System.currentTimeMillis(),
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType = DeviceType.PENGUIN_SURVEILLANCE,
        deviceName = null,
        macAddress = "DD:EE:FF:00:11:22",
        ssid = "Penguin_Surveillance_01",
        rssi = -58,
        signalStrength = SignalStrength.GOOD,
        latitude = 47.6062,
        longitude = -122.3321,
        threatLevel = ThreatLevel.HIGH,
        threatScore = 85,
        manufacturer = "Penguin",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = "[\"Penguin Surveillance Device\"]",
        isActive = true
    )
}

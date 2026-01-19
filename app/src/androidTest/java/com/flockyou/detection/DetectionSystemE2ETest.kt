package com.flockyou.detection

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
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
 * Comprehensive E2E tests for detection system.
 *
 * Tests cover:
 * - All detection protocols (WiFi, BLE, Cellular, Ultrasonic, Satellite, RF)
 * - Detection methods and pattern matching
 * - Device type classification
 * - Threat level calculation
 * - Signal strength processing
 * - Detection deduplication and updates
 * - Multi-protocol detection support
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DetectionSystemE2ETest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var detectionRepository: DetectionRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
    }

    @After
    fun cleanup() {
        runBlocking {
            detectionRepository.deleteAllDetections()
        }
    }

    // ==================== Protocol Coverage Tests ====================

    @Test
    fun protocols_allProtocolsAreSupported() {
        val protocols = DetectionProtocol.entries

        assertTrue("Should have WIFI", protocols.contains(DetectionProtocol.WIFI))
        assertTrue("Should have BLUETOOTH_LE", protocols.contains(DetectionProtocol.BLUETOOTH_LE))
        assertTrue("Should have CELLULAR", protocols.contains(DetectionProtocol.CELLULAR))
        assertTrue("Should have AUDIO", protocols.contains(DetectionProtocol.AUDIO))
        assertTrue("Should have RF", protocols.contains(DetectionProtocol.RF))
        assertTrue("Should have SATELLITE", protocols.contains(DetectionProtocol.SATELLITE))
    }

    @Test
    fun protocols_haveValidDisplayNames() {
        DetectionProtocol.entries.forEach { protocol ->
            assertTrue(
                "Protocol ${protocol.name} should have display name",
                protocol.displayName.isNotEmpty()
            )
            assertTrue(
                "Protocol ${protocol.name} should have icon",
                protocol.icon.isNotEmpty()
            )
        }
    }

    // ==================== WiFi Detection Tests ====================

    @Test
    fun wifiDetection_flockSafetyCameraIsDetected() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insertDetection(detection)

        val retrieved = detectionRepository.getDetectionById(detection.id)

        assertNotNull("Detection should be stored", retrieved)
        assertEquals("Protocol should be WIFI", DetectionProtocol.WIFI, retrieved?.protocol)
        assertEquals("Device type should be FLOCK_SAFETY_CAMERA", DeviceType.FLOCK_SAFETY_CAMERA, retrieved?.deviceType)
        assertEquals("Threat level should be HIGH", ThreatLevel.HIGH, retrieved?.threatLevel)
        assertNotNull("SSID should be set", retrieved?.ssid)
        assertNotNull("MAC address should be set", retrieved?.macAddress)
    }

    @Test
    fun wifiDetection_ssidPatternsMatchCorrectly() {
        // Test various SSID patterns
        val flockSsids = listOf("Flock-ABC123", "FlockSafety_Device01", "FLOCK_CAM_001")

        flockSsids.forEach { ssid ->
            val result = DetectionPatterns.matchSsidPattern(ssid)
            assertNotNull("Should match Flock SSID: $ssid", result)
            assertEquals("Should classify as FLOCK_SAFETY_CAMERA", DeviceType.FLOCK_SAFETY_CAMERA, result?.deviceType)
        }
    }

    @Test
    fun wifiDetection_droneIsDetected() = runTest {
        val detection = TestDataFactory.createDroneDetection()
        detectionRepository.insertDetection(detection)

        val retrieved = detectionRepository.getDetectionById(detection.id)

        assertNotNull("Detection should be stored", retrieved)
        assertEquals("Device type should be DRONE", DeviceType.DRONE, retrieved?.deviceType)
        assertTrue("SSID should contain DJI", retrieved?.ssid?.contains("DJI") == true)
    }

    // ==================== Cellular Detection Tests ====================

    @Test
    fun cellularDetection_stingrayIsDetected() = runTest {
        val detection = TestDataFactory.createStingrayDetection()
        detectionRepository.insertDetection(detection)

        val retrieved = detectionRepository.getDetectionById(detection.id)

        assertNotNull("Detection should be stored", retrieved)
        assertEquals("Protocol should be CELLULAR", DetectionProtocol.CELLULAR, retrieved?.protocol)
        assertEquals("Device type should be STINGRAY_IMSI", DeviceType.STINGRAY_IMSI, retrieved?.deviceType)
        assertEquals("Method should be CELL_ENCRYPTION_DOWNGRADE", DetectionMethod.CELL_ENCRYPTION_DOWNGRADE, retrieved?.detectionMethod)
        assertEquals("Threat level should be CRITICAL", ThreatLevel.CRITICAL, retrieved?.threatLevel)
    }

    @Test
    fun cellularDetection_hasCellularMethods() {
        val cellularMethods = DetectionMethod.entries.filter { it.name.startsWith("CELL_") }

        assertTrue("Should have CELL_ENCRYPTION_DOWNGRADE",
            cellularMethods.contains(DetectionMethod.CELL_ENCRYPTION_DOWNGRADE))
        assertTrue("Should have CELL_SUSPICIOUS_NETWORK",
            cellularMethods.contains(DetectionMethod.CELL_SUSPICIOUS_NETWORK))
        assertTrue("Should have CELL_TOWER_CHANGE",
            cellularMethods.contains(DetectionMethod.CELL_TOWER_CHANGE))
        assertTrue("Should have CELL_RAPID_SWITCHING",
            cellularMethods.contains(DetectionMethod.CELL_RAPID_SWITCHING))
    }

    // ==================== Ultrasonic Detection Tests ====================

    @Test
    fun ultrasonicDetection_beaconIsDetected() = runTest {
        val detection = TestDataFactory.createUltrasonicBeaconDetection()
        detectionRepository.insertDetection(detection)

        val retrieved = detectionRepository.getDetectionById(detection.id)

        assertNotNull("Detection should be stored", retrieved)
        assertEquals("Protocol should be AUDIO", DetectionProtocol.AUDIO, retrieved?.protocol)
        assertEquals("Device type should be ULTRASONIC_BEACON", DeviceType.ULTRASONIC_BEACON, retrieved?.deviceType)
        assertEquals("Method should be ULTRASONIC_AD_BEACON", DetectionMethod.ULTRASONIC_AD_BEACON, retrieved?.detectionMethod)
        assertNull("MAC address should be null", retrieved?.macAddress)
        assertNull("Latitude should be null", retrieved?.latitude)
        assertNotNull("SSID should contain frequency", retrieved?.ssid)
    }

    @Test
    fun ultrasonicDetection_hasUltrasonicMethods() {
        val ultrasonicMethods = DetectionMethod.entries.filter { it.name.startsWith("ULTRASONIC_") }

        assertTrue("Should have ULTRASONIC_TRACKING_BEACON",
            ultrasonicMethods.contains(DetectionMethod.ULTRASONIC_TRACKING_BEACON))
        assertTrue("Should have ULTRASONIC_AD_BEACON",
            ultrasonicMethods.contains(DetectionMethod.ULTRASONIC_AD_BEACON))
        assertTrue("Should have ULTRASONIC_CROSS_DEVICE",
            ultrasonicMethods.contains(DetectionMethod.ULTRASONIC_CROSS_DEVICE))
    }

    // ==================== Satellite Detection Tests ====================

    @Test
    fun satelliteDetection_ntnIsDetected() = runTest {
        val detection = TestDataFactory.createSatelliteDetection()
        detectionRepository.insertDetection(detection)

        val retrieved = detectionRepository.getDetectionById(detection.id)

        assertNotNull("Detection should be stored", retrieved)
        assertEquals("Protocol should be SATELLITE", DetectionProtocol.SATELLITE, retrieved?.protocol)
        assertEquals("Device type should be SATELLITE_NTN", DeviceType.SATELLITE_NTN, retrieved?.deviceType)
        assertEquals("Method should be SAT_UNEXPECTED_CONNECTION", DetectionMethod.SAT_UNEXPECTED_CONNECTION, retrieved?.detectionMethod)
    }

    @Test
    fun satelliteDetection_hasSatelliteMethods() {
        val satelliteMethods = DetectionMethod.entries.filter { it.name.startsWith("SAT_") }

        assertTrue("Should have SAT_UNEXPECTED_CONNECTION",
            satelliteMethods.contains(DetectionMethod.SAT_UNEXPECTED_CONNECTION))
        assertTrue("Should have SAT_FORCED_HANDOFF",
            satelliteMethods.contains(DetectionMethod.SAT_FORCED_HANDOFF))
        assertTrue("Should have SAT_SUSPICIOUS_NTN",
            satelliteMethods.contains(DetectionMethod.SAT_SUSPICIOUS_NTN))
        assertTrue("Should have SAT_TIMING_ANOMALY",
            satelliteMethods.contains(DetectionMethod.SAT_TIMING_ANOMALY))
        assertTrue("Should have SAT_DOWNGRADE",
            satelliteMethods.contains(DetectionMethod.SAT_DOWNGRADE))
    }

    // ==================== Device Type Tests ====================

    @Test
    fun deviceTypes_coverAllSurveillanceCategories() {
        val types = DeviceType.entries

        // IMSI catchers
        assertTrue("Should have STINGRAY_IMSI", types.contains(DeviceType.STINGRAY_IMSI))
        assertTrue("Should have L3HARRIS_SURVEILLANCE", types.contains(DeviceType.L3HARRIS_SURVEILLANCE))

        // Cameras
        assertTrue("Should have FLOCK_SAFETY_CAMERA", types.contains(DeviceType.FLOCK_SAFETY_CAMERA))
        assertTrue("Should have HIDDEN_CAMERA", types.contains(DeviceType.HIDDEN_CAMERA))

        // Police tech
        assertTrue("Should have RAVEN_GUNSHOT_DETECTOR", types.contains(DeviceType.RAVEN_GUNSHOT_DETECTOR))
        assertTrue("Should have AXON_POLICE_TECH", types.contains(DeviceType.AXON_POLICE_TECH))
        assertTrue("Should have MOTOROLA_POLICE_TECH", types.contains(DeviceType.MOTOROLA_POLICE_TECH))

        // Threats
        assertTrue("Should have ROGUE_AP", types.contains(DeviceType.ROGUE_AP))
        assertTrue("Should have DRONE", types.contains(DeviceType.DRONE))
        assertTrue("Should have RF_JAMMER", types.contains(DeviceType.RF_JAMMER))
        assertTrue("Should have ULTRASONIC_BEACON", types.contains(DeviceType.ULTRASONIC_BEACON))
        assertTrue("Should have SATELLITE_NTN", types.contains(DeviceType.SATELLITE_NTN))
    }

    @Test
    fun deviceTypes_haveDisplayNamesAndEmojis() {
        DeviceType.entries.forEach { type ->
            assertTrue(
                "DeviceType ${type.name} should have display name",
                type.displayName.isNotEmpty()
            )
            assertTrue(
                "DeviceType ${type.name} should have emoji",
                type.emoji.isNotEmpty()
            )
        }
    }

    // ==================== Threat Level Tests ====================

    @Test
    fun threatLevels_areOrderedBySeverity() {
        assertTrue("CRITICAL < HIGH", ThreatLevel.CRITICAL.ordinal < ThreatLevel.HIGH.ordinal)
        assertTrue("HIGH < MEDIUM", ThreatLevel.HIGH.ordinal < ThreatLevel.MEDIUM.ordinal)
        assertTrue("MEDIUM < LOW", ThreatLevel.MEDIUM.ordinal < ThreatLevel.LOW.ordinal)
        assertTrue("LOW < INFO", ThreatLevel.LOW.ordinal < ThreatLevel.INFO.ordinal)
    }

    @Test
    fun threatLevels_scoreConversionWorks() {
        assertEquals("Score 0 = INFO", ThreatLevel.INFO, scoreToThreatLevel(0))
        assertEquals("Score 29 = INFO", ThreatLevel.INFO, scoreToThreatLevel(29))
        assertEquals("Score 30 = LOW", ThreatLevel.LOW, scoreToThreatLevel(30))
        assertEquals("Score 50 = MEDIUM", ThreatLevel.MEDIUM, scoreToThreatLevel(50))
        assertEquals("Score 70 = HIGH", ThreatLevel.HIGH, scoreToThreatLevel(70))
        assertEquals("Score 90 = CRITICAL", ThreatLevel.CRITICAL, scoreToThreatLevel(90))
        assertEquals("Score 100 = CRITICAL", ThreatLevel.CRITICAL, scoreToThreatLevel(100))
    }

    @Test
    fun threatLevels_canQueryByLevel() = runTest {
        // Insert detections with different threat levels
        detectionRepository.insertDetection(
            TestDataFactory.createStingrayDetection() // CRITICAL
        )
        detectionRepository.insertDetection(
            TestDataFactory.createFlockSafetyCameraDetection() // HIGH
        )
        detectionRepository.insertDetection(
            TestDataFactory.createDroneDetection() // MEDIUM
        )

        val highThreats = detectionRepository.getDetectionsByThreatLevel(ThreatLevel.HIGH).first()
        val criticalThreats = detectionRepository.getDetectionsByThreatLevel(ThreatLevel.CRITICAL).first()

        assertEquals("Should have 1 HIGH threat", 1, highThreats.size)
        assertEquals("Should have 1 CRITICAL threat", 1, criticalThreats.size)
    }

    // ==================== Signal Strength Tests ====================

    @Test
    fun signalStrength_conversionWorks() {
        assertEquals("RSSI -30 = EXCELLENT", SignalStrength.EXCELLENT, rssiToSignalStrength(-30))
        assertEquals("RSSI -55 = GOOD", SignalStrength.GOOD, rssiToSignalStrength(-55))
        assertEquals("RSSI -65 = MEDIUM", SignalStrength.MEDIUM, rssiToSignalStrength(-65))
        assertEquals("RSSI -75 = WEAK", SignalStrength.WEAK, rssiToSignalStrength(-75))
        assertEquals("RSSI -90 = VERY_WEAK", SignalStrength.VERY_WEAK, rssiToSignalStrength(-90))
    }

    @Test
    fun signalStrength_hasDisplayNames() {
        SignalStrength.entries.forEach { strength ->
            assertTrue(
                "SignalStrength ${strength.name} should have display name",
                strength.displayName.isNotEmpty()
            )
        }
    }

    // ==================== Detection Deduplication Tests ====================

    @Test
    fun deduplication_sameMACreusesDetection() = runTest {
        val mac = "AA:BB:CC:DD:EE:FF"
        val detection1 = TestDataFactory.createFlockSafetyCameraDetection().copy(
            macAddress = mac,
            seenCount = 1
        )

        // Insert first detection
        val isNew1 = detectionRepository.upsertDetection(detection1)
        assertTrue("First detection should be new", isNew1)

        // Insert same MAC again
        val detection2 = detection1.copy(rssi = -55, seenCount = 1)
        val isNew2 = detectionRepository.upsertDetection(detection2)
        assertFalse("Second detection should update existing", isNew2)

        // Verify only one detection exists
        val count = detectionRepository.getTotalDetectionCount()
        assertEquals("Should have only 1 detection", 1, count)

        // Verify seen count increased
        val retrieved = detectionRepository.getDetectionByMacAddress(mac)
        assertEquals("Seen count should be 2", 2, retrieved?.seenCount)
    }

    @Test
    fun deduplication_sameSSIDreusesDetection() = runTest {
        val ssid = "Flock-ABC123"
        val detection1 = TestDataFactory.createFlockSafetyCameraDetection().copy(
            ssid = ssid,
            macAddress = null, // No MAC
            seenCount = 1
        )

        val isNew1 = detectionRepository.upsertDetection(detection1)
        assertTrue("First detection should be new", isNew1)

        val detection2 = detection1.copy(rssi = -50, seenCount = 1)
        val isNew2 = detectionRepository.upsertDetection(detection2)
        assertFalse("Second detection should update existing", isNew2)

        val count = detectionRepository.getTotalDetectionCount()
        assertEquals("Should have only 1 detection", 1, count)
    }

    // ==================== Detection Queries Tests ====================

    @Test
    fun queries_canGetByDeviceType() = runTest {
        // Insert multiple device types
        detectionRepository.insertDetection(TestDataFactory.createFlockSafetyCameraDetection())
        detectionRepository.insertDetection(TestDataFactory.createStingrayDetection())
        detectionRepository.insertDetection(TestDataFactory.createDroneDetection())
        detectionRepository.insertDetection(TestDataFactory.createFlockSafetyCameraDetection())

        val flockDetections = detectionRepository.getDetectionsByDeviceType(DeviceType.FLOCK_SAFETY_CAMERA).first()
        val droneDetections = detectionRepository.getDetectionsByDeviceType(DeviceType.DRONE).first()

        assertEquals("Should have 2 Flock detections", 2, flockDetections.size)
        assertEquals("Should have 1 drone detection", 1, droneDetections.size)
    }

    @Test
    fun queries_canGetRecentDetections() = runTest {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - (60 * 60 * 1000L)
        val twoDaysAgo = now - (2 * 24 * 60 * 60 * 1000L)

        // Insert detections with different timestamps
        detectionRepository.insertDetection(
            TestDataFactory.createFlockSafetyCameraDetection().copy(timestamp = twoDaysAgo)
        )
        detectionRepository.insertDetection(
            TestDataFactory.createStingrayDetection().copy(timestamp = oneHourAgo)
        )
        detectionRepository.insertDetection(
            TestDataFactory.createDroneDetection().copy(timestamp = now)
        )

        // Query detections from last day
        val oneDayAgo = now - (24 * 60 * 60 * 1000L)
        val recentDetections = detectionRepository.getRecentDetections(oneDayAgo).first()

        assertEquals("Should have 2 recent detections", 2, recentDetections.size)
    }

    @Test
    fun queries_canGetActiveDetections() = runTest {
        // Insert active and inactive detections
        detectionRepository.insertDetection(
            TestDataFactory.createFlockSafetyCameraDetection().copy(isActive = true)
        )
        detectionRepository.insertDetection(
            TestDataFactory.createStingrayDetection().copy(isActive = true)
        )
        detectionRepository.insertDetection(
            TestDataFactory.createDroneDetection().copy(isActive = false)
        )

        val activeDetections = detectionRepository.activeDetections.first()

        assertEquals("Should have 2 active detections", 2, activeDetections.size)
        assertTrue("All should be active", activeDetections.all { it.isActive })
    }

    // ==================== Multi-Protocol Integration Tests ====================

    @Test
    fun integration_allProtocolsCanBeStored() = runTest {
        val mixedDetections = TestDataFactory.createMixedProtocolDetections()

        mixedDetections.forEach { detectionRepository.insertDetection(it) }

        val allDetections = detectionRepository.getAllDetectionsSnapshot()

        assertEquals("Should have all detections", mixedDetections.size, allDetections.size)

        // Verify each protocol is represented
        val protocols = allDetections.map { it.protocol }.toSet()
        assertTrue("Should have WIFI", protocols.contains(DetectionProtocol.WIFI))
        assertTrue("Should have CELLULAR", protocols.contains(DetectionProtocol.CELLULAR))
        assertTrue("Should have AUDIO", protocols.contains(DetectionProtocol.AUDIO))
        assertTrue("Should have SATELLITE", protocols.contains(DetectionProtocol.SATELLITE))
    }

    @Test
    fun integration_highThreatCountWorks() = runTest {
        // Insert mix of threat levels
        detectionRepository.insertDetection(TestDataFactory.createStingrayDetection()) // CRITICAL
        detectionRepository.insertDetection(TestDataFactory.createFlockSafetyCameraDetection()) // HIGH
        detectionRepository.insertDetection(TestDataFactory.createUltrasonicBeaconDetection()) // HIGH
        detectionRepository.insertDetection(TestDataFactory.createDroneDetection()) // MEDIUM

        val highThreatCount = detectionRepository.highThreatCount.first()

        assertEquals("Should have 3 high/critical threats", 3, highThreatCount)
    }

    @Test
    fun integration_canMarkInactive() = runTest {
        val mac = "AA:BB:CC:DD:EE:FF"
        val detection = TestDataFactory.createFlockSafetyCameraDetection().copy(
            macAddress = mac,
            isActive = true
        )

        detectionRepository.insertDetection(detection)

        var retrieved = detectionRepository.getDetectionByMacAddress(mac)
        assertTrue("Should be active", retrieved?.isActive == true)

        // Mark inactive
        detectionRepository.markInactive(mac)

        retrieved = detectionRepository.getDetectionByMacAddress(mac)
        assertFalse("Should be inactive", retrieved?.isActive == true)
    }
}

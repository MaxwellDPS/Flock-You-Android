package com.flockyou.detection

import com.flockyou.data.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for Detection model and helper functions.
 */
class DetectionModelTest {
    
    // ============ Signal Strength Conversion Tests ============
    
    @Test
    fun `rssiToSignalStrength returns EXCELLENT for strong signal`() {
        assertThat(rssiToSignalStrength(-45)).isEqualTo(SignalStrength.EXCELLENT)
        assertThat(rssiToSignalStrength(-30)).isEqualTo(SignalStrength.EXCELLENT)
        assertThat(rssiToSignalStrength(-49)).isEqualTo(SignalStrength.EXCELLENT)
    }
    
    @Test
    fun `rssiToSignalStrength returns GOOD for good signal`() {
        assertThat(rssiToSignalStrength(-50)).isEqualTo(SignalStrength.GOOD)
        assertThat(rssiToSignalStrength(-55)).isEqualTo(SignalStrength.GOOD)
        assertThat(rssiToSignalStrength(-59)).isEqualTo(SignalStrength.GOOD)
    }
    
    @Test
    fun `rssiToSignalStrength returns MEDIUM for medium signal`() {
        assertThat(rssiToSignalStrength(-60)).isEqualTo(SignalStrength.MEDIUM)
        assertThat(rssiToSignalStrength(-65)).isEqualTo(SignalStrength.MEDIUM)
        assertThat(rssiToSignalStrength(-69)).isEqualTo(SignalStrength.MEDIUM)
    }
    
    @Test
    fun `rssiToSignalStrength returns WEAK for weak signal`() {
        assertThat(rssiToSignalStrength(-70)).isEqualTo(SignalStrength.WEAK)
        assertThat(rssiToSignalStrength(-75)).isEqualTo(SignalStrength.WEAK)
        assertThat(rssiToSignalStrength(-79)).isEqualTo(SignalStrength.WEAK)
    }
    
    @Test
    fun `rssiToSignalStrength returns VERY_WEAK for very weak signal`() {
        assertThat(rssiToSignalStrength(-80)).isEqualTo(SignalStrength.VERY_WEAK)
        assertThat(rssiToSignalStrength(-90)).isEqualTo(SignalStrength.VERY_WEAK)
        assertThat(rssiToSignalStrength(-100)).isEqualTo(SignalStrength.VERY_WEAK)
    }
    
    // ============ Threat Level Conversion Tests ============
    
    @Test
    fun `scoreToThreatLevel returns CRITICAL for 90-100`() {
        assertThat(scoreToThreatLevel(90)).isEqualTo(ThreatLevel.CRITICAL)
        assertThat(scoreToThreatLevel(95)).isEqualTo(ThreatLevel.CRITICAL)
        assertThat(scoreToThreatLevel(100)).isEqualTo(ThreatLevel.CRITICAL)
    }
    
    @Test
    fun `scoreToThreatLevel returns HIGH for 70-89`() {
        assertThat(scoreToThreatLevel(70)).isEqualTo(ThreatLevel.HIGH)
        assertThat(scoreToThreatLevel(80)).isEqualTo(ThreatLevel.HIGH)
        assertThat(scoreToThreatLevel(89)).isEqualTo(ThreatLevel.HIGH)
    }
    
    @Test
    fun `scoreToThreatLevel returns MEDIUM for 50-69`() {
        assertThat(scoreToThreatLevel(50)).isEqualTo(ThreatLevel.MEDIUM)
        assertThat(scoreToThreatLevel(60)).isEqualTo(ThreatLevel.MEDIUM)
        assertThat(scoreToThreatLevel(69)).isEqualTo(ThreatLevel.MEDIUM)
    }
    
    @Test
    fun `scoreToThreatLevel returns LOW for 30-49`() {
        assertThat(scoreToThreatLevel(30)).isEqualTo(ThreatLevel.LOW)
        assertThat(scoreToThreatLevel(40)).isEqualTo(ThreatLevel.LOW)
        assertThat(scoreToThreatLevel(49)).isEqualTo(ThreatLevel.LOW)
    }
    
    @Test
    fun `scoreToThreatLevel returns INFO for 0-29`() {
        assertThat(scoreToThreatLevel(0)).isEqualTo(ThreatLevel.INFO)
        assertThat(scoreToThreatLevel(15)).isEqualTo(ThreatLevel.INFO)
        assertThat(scoreToThreatLevel(29)).isEqualTo(ThreatLevel.INFO)
    }
    
    // ============ Detection Creation Tests ============
    
    @Test
    fun `Detection initializes with correct defaults`() {
        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            deviceName = null,
            macAddress = "AA:BB:CC:DD:EE:FF",
            ssid = "Flock_Test",
            rssi = -65,
            signalStrength = SignalStrength.MEDIUM,
            latitude = null,
            longitude = null,
            threatLevel = ThreatLevel.CRITICAL,
            threatScore = 95,
            manufacturer = "Flock Safety",
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = null
        )
        
        assertThat(detection.isActive).isTrue()
        assertThat(detection.id).isNotEmpty()
        assertThat(detection.timestamp).isGreaterThan(0)
    }
    
    @Test
    fun `Detection copy updates only specified fields`() {
        val original = createTestDetection()
        val updated = original.copy(rssi = -50, isActive = false)
        
        assertThat(updated.id).isEqualTo(original.id)
        assertThat(updated.rssi).isEqualTo(-50)
        assertThat(updated.isActive).isFalse()
        assertThat(updated.macAddress).isEqualTo(original.macAddress)
    }
    
    // ============ Enum Value Tests ============
    
    @Test
    fun `DetectionProtocol contains expected values`() {
        assertThat(DetectionProtocol.values()).asList()
            .containsExactly(DetectionProtocol.WIFI, DetectionProtocol.BLUETOOTH_LE)
    }
    
    @Test
    fun `DetectionMethod contains expected values`() {
        assertThat(DetectionMethod.values()).asList()
            .containsExactly(
                DetectionMethod.SSID_PATTERN,
                DetectionMethod.MAC_PREFIX,
                DetectionMethod.BLE_DEVICE_NAME,
                DetectionMethod.BLE_SERVICE_UUID,
                DetectionMethod.RAVEN_SERVICE_UUID,
                DetectionMethod.PROBE_REQUEST,
                DetectionMethod.BEACON_FRAME
            )
    }
    
    @Test
    fun `DeviceType contains expected values`() {
        assertThat(DeviceType.values()).asList()
            .containsExactly(
                DeviceType.FLOCK_SAFETY_CAMERA,
                DeviceType.PENGUIN_SURVEILLANCE,
                DeviceType.PIGVISION_SYSTEM,
                DeviceType.RAVEN_GUNSHOT_DETECTOR,
                DeviceType.UNKNOWN_SURVEILLANCE
            )
    }
    
    @Test
    fun `SignalStrength contains expected values in order`() {
        assertThat(SignalStrength.values()).asList()
            .containsExactly(
                SignalStrength.EXCELLENT,
                SignalStrength.GOOD,
                SignalStrength.MEDIUM,
                SignalStrength.WEAK,
                SignalStrength.VERY_WEAK
            ).inOrder()
    }
    
    @Test
    fun `ThreatLevel contains expected values in order`() {
        assertThat(ThreatLevel.values()).asList()
            .containsExactly(
                ThreatLevel.CRITICAL,
                ThreatLevel.HIGH,
                ThreatLevel.MEDIUM,
                ThreatLevel.LOW,
                ThreatLevel.INFO
            ).inOrder()
    }
    
    // ============ Pattern Type Tests ============
    
    @Test
    fun `PatternType contains expected values`() {
        assertThat(PatternType.values()).asList()
            .containsExactly(
                PatternType.SSID_REGEX,
                PatternType.MAC_PREFIX,
                PatternType.BLE_NAME_REGEX,
                PatternType.BLE_SERVICE_UUID
            )
    }
    
    // ============ Detection Pattern Tests ============
    
    @Test
    fun `DetectionPattern stores all fields correctly`() {
        val pattern = DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^flock.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Test description"
        )
        
        assertThat(pattern.type).isEqualTo(PatternType.SSID_REGEX)
        assertThat(pattern.pattern).isEqualTo("(?i)^flock.*")
        assertThat(pattern.deviceType).isEqualTo(DeviceType.FLOCK_SAFETY_CAMERA)
        assertThat(pattern.manufacturer).isEqualTo("Flock Safety")
        assertThat(pattern.threatScore).isEqualTo(95)
        assertThat(pattern.description).isEqualTo("Test description")
    }
    
    @Test
    fun `DetectionPattern allows null manufacturer`() {
        val pattern = DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^alpr.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 80,
            description = "Unknown ALPR"
        )
        
        assertThat(pattern.manufacturer).isNull()
    }
    
    // ============ Edge Cases ============
    
    @Test
    fun `rssiToSignalStrength handles boundary values`() {
        // At exactly the boundary between categories
        assertThat(rssiToSignalStrength(-50)).isEqualTo(SignalStrength.GOOD)  // Boundary
        assertThat(rssiToSignalStrength(-60)).isEqualTo(SignalStrength.MEDIUM) // Boundary
        assertThat(rssiToSignalStrength(-70)).isEqualTo(SignalStrength.WEAK)   // Boundary
        assertThat(rssiToSignalStrength(-80)).isEqualTo(SignalStrength.VERY_WEAK) // Boundary
    }
    
    @Test
    fun `scoreToThreatLevel handles boundary values`() {
        assertThat(scoreToThreatLevel(90)).isEqualTo(ThreatLevel.CRITICAL) // Boundary
        assertThat(scoreToThreatLevel(70)).isEqualTo(ThreatLevel.HIGH)     // Boundary
        assertThat(scoreToThreatLevel(50)).isEqualTo(ThreatLevel.MEDIUM)   // Boundary
        assertThat(scoreToThreatLevel(30)).isEqualTo(ThreatLevel.LOW)      // Boundary
    }
    
    @Test
    fun `Detection handles all nullable fields as null`() {
        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            deviceName = null,
            macAddress = null,
            ssid = null,
            rssi = -70,
            signalStrength = SignalStrength.WEAK,
            latitude = null,
            longitude = null,
            threatLevel = ThreatLevel.MEDIUM,
            threatScore = 60,
            manufacturer = null,
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = null
        )
        
        assertThat(detection.deviceName).isNull()
        assertThat(detection.macAddress).isNull()
        assertThat(detection.ssid).isNull()
        assertThat(detection.latitude).isNull()
        assertThat(detection.longitude).isNull()
        assertThat(detection.manufacturer).isNull()
        assertThat(detection.firmwareVersion).isNull()
        assertThat(detection.serviceUuids).isNull()
        assertThat(detection.matchedPatterns).isNull()
    }
    
    // ============ Helper Functions ============
    
    private fun createTestDetection() = Detection(
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
        deviceName = null,
        macAddress = "AA:BB:CC:DD:EE:FF",
        ssid = "Flock_Camera_001",
        rssi = -65,
        signalStrength = SignalStrength.MEDIUM,
        latitude = 47.6062,
        longitude = -122.3321,
        threatLevel = ThreatLevel.CRITICAL,
        threatScore = 95,
        manufacturer = "Flock Safety",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = "[\"Flock Safety ALPR Camera\"]"
    )
}

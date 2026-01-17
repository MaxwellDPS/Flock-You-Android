package com.flockyou.detection

import com.flockyou.data.model.DetectionPatterns
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.PatternType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for DetectionPatterns matching logic.
 * Tests SSID patterns, BLE name patterns, MAC prefixes, and Raven service UUIDs.
 */
class DetectionPatternsTest {
    
    // ============ SSID Pattern Tests ============
    
    @Test
    fun `matchSsidPattern detects Flock_Camera SSID`() {
        val result = DetectionPatterns.matchSsidPattern("Flock_Camera_001")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.FLOCK_SAFETY_CAMERA)
        assertThat(result?.manufacturer).isEqualTo("Flock Safety")
    }
    
    @Test
    fun `matchSsidPattern detects flock lowercase SSID`() {
        val result = DetectionPatterns.matchSsidPattern("flock_test_camera")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.FLOCK_SAFETY_CAMERA)
    }
    
    @Test
    fun `matchSsidPattern detects FLOCK uppercase SSID`() {
        val result = DetectionPatterns.matchSsidPattern("FLOCK_SURVEILLANCE")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.FLOCK_SAFETY_CAMERA)
    }
    
    @Test
    fun `matchSsidPattern detects FS_ prefix SSID`() {
        val result = DetectionPatterns.matchSsidPattern("FS_Camera_42")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.FLOCK_SAFETY_CAMERA)
    }
    
    @Test
    fun `matchSsidPattern detects Penguin SSID`() {
        val result = DetectionPatterns.matchSsidPattern("Penguin_Surveillance_01")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.PENGUIN_SURVEILLANCE)
        assertThat(result?.manufacturer).isEqualTo("Penguin")
    }
    
    @Test
    fun `matchSsidPattern detects Pigvision SSID`() {
        val result = DetectionPatterns.matchSsidPattern("Pigvision_Cam_01")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.PIGVISION_SYSTEM)
        assertThat(result?.manufacturer).isEqualTo("Pigvision")
    }
    
    @Test
    fun `matchSsidPattern detects ALPR SSID`() {
        val result = DetectionPatterns.matchSsidPattern("ALPR_System_001")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.UNKNOWN_SURVEILLANCE)
    }
    
    @Test
    fun `matchSsidPattern detects LPR_Cam SSID`() {
        val result = DetectionPatterns.matchSsidPattern("LPR_Camera_System")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.UNKNOWN_SURVEILLANCE)
    }
    
    @Test
    fun `matchSsidPattern returns null for regular WiFi`() {
        val result = DetectionPatterns.matchSsidPattern("MyHomeNetwork")
        
        assertThat(result).isNull()
    }
    
    @Test
    fun `matchSsidPattern returns null for partial match`() {
        val result = DetectionPatterns.matchSsidPattern("NotFlockCamera")
        
        assertThat(result).isNull()
    }
    
    @Test
    fun `matchSsidPattern returns null for empty SSID`() {
        val result = DetectionPatterns.matchSsidPattern("")
        
        assertThat(result).isNull()
    }
    
    // ============ BLE Name Pattern Tests ============
    
    @Test
    fun `matchBleNamePattern detects Flock device`() {
        val result = DetectionPatterns.matchBleNamePattern("Flock_BLE_Device")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.FLOCK_SAFETY_CAMERA)
    }
    
    @Test
    fun `matchBleNamePattern detects Raven device`() {
        val result = DetectionPatterns.matchBleNamePattern("Raven-Device-001")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.RAVEN_GUNSHOT_DETECTOR)
        assertThat(result?.manufacturer).isEqualTo("SoundThinking/ShotSpotter")
    }
    
    @Test
    fun `matchBleNamePattern detects ShotSpotter device`() {
        val result = DetectionPatterns.matchBleNamePattern("ShotSpotter_Sensor_01")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.RAVEN_GUNSHOT_DETECTOR)
    }
    
    @Test
    fun `matchBleNamePattern detects Penguin BLE device`() {
        val result = DetectionPatterns.matchBleNamePattern("Penguin_BT_01")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.PENGUIN_SURVEILLANCE)
    }
    
    @Test
    fun `matchBleNamePattern detects Pigvision BLE device`() {
        val result = DetectionPatterns.matchBleNamePattern("Pigvision_BLE")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.PIGVISION_SYSTEM)
    }
    
    @Test
    fun `matchBleNamePattern case insensitive`() {
        val result1 = DetectionPatterns.matchBleNamePattern("RAVEN_DEVICE")
        val result2 = DetectionPatterns.matchBleNamePattern("raven_device")
        val result3 = DetectionPatterns.matchBleNamePattern("Raven_Device")
        
        assertThat(result1?.deviceType).isEqualTo(DeviceType.RAVEN_GUNSHOT_DETECTOR)
        assertThat(result2?.deviceType).isEqualTo(DeviceType.RAVEN_GUNSHOT_DETECTOR)
        assertThat(result3?.deviceType).isEqualTo(DeviceType.RAVEN_GUNSHOT_DETECTOR)
    }
    
    @Test
    fun `matchBleNamePattern returns null for unknown device`() {
        val result = DetectionPatterns.matchBleNamePattern("Unknown_Bluetooth_Device")
        
        assertThat(result).isNull()
    }
    
    // ============ MAC Prefix Tests ============
    
    @Test
    fun `matchMacPrefix detects Flock MAC prefix`() {
        val result = DetectionPatterns.matchMacPrefix("00:1A:2B:CC:DD:EE")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.FLOCK_SAFETY_CAMERA)
    }
    
    @Test
    fun `matchMacPrefix detects Penguin MAC prefix`() {
        val result = DetectionPatterns.matchMacPrefix("00:1B:2C:AA:BB:CC")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.PENGUIN_SURVEILLANCE)
    }
    
    @Test
    fun `matchMacPrefix detects Pigvision MAC prefix`() {
        val result = DetectionPatterns.matchMacPrefix("00:1C:2D:11:22:33")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.PIGVISION_SYSTEM)
    }
    
    @Test
    fun `matchMacPrefix detects SoundThinking MAC prefix`() {
        val result = DetectionPatterns.matchMacPrefix("00:1D:2E:44:55:66")
        
        assertThat(result).isNotNull()
        assertThat(result?.deviceType).isEqualTo(DeviceType.RAVEN_GUNSHOT_DETECTOR)
    }
    
    @Test
    fun `matchMacPrefix handles lowercase MAC`() {
        val result = DetectionPatterns.matchMacPrefix("00:1a:2b:cc:dd:ee")
        
        assertThat(result).isNotNull()
    }
    
    @Test
    fun `matchMacPrefix handles hyphen separator`() {
        val result = DetectionPatterns.matchMacPrefix("00-1A-2B-CC-DD-EE")
        
        assertThat(result).isNotNull()
    }
    
    @Test
    fun `matchMacPrefix returns null for unknown prefix`() {
        val result = DetectionPatterns.matchMacPrefix("FF:FF:FF:00:00:00")
        
        assertThat(result).isNull()
    }
    
    // ============ Raven Service UUID Tests ============
    
    @Test
    fun `isRavenDevice detects Device Information Service`() {
        val serviceUuids = listOf(
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isTrue()
    }
    
    @Test
    fun `isRavenDevice detects GPS Location Service`() {
        val serviceUuids = listOf(
            UUID.fromString("00003100-0000-1000-8000-00805f9b34fb")
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isTrue()
    }
    
    @Test
    fun `isRavenDevice detects Power Management Service`() {
        val serviceUuids = listOf(
            UUID.fromString("00003200-0000-1000-8000-00805f9b34fb")
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isTrue()
    }
    
    @Test
    fun `isRavenDevice detects Network Status Service`() {
        val serviceUuids = listOf(
            UUID.fromString("00003300-0000-1000-8000-00805f9b34fb")
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isTrue()
    }
    
    @Test
    fun `isRavenDevice detects Upload Statistics Service`() {
        val serviceUuids = listOf(
            UUID.fromString("00003400-0000-1000-8000-00805f9b34fb")
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isTrue()
    }
    
    @Test
    fun `isRavenDevice detects Error Service`() {
        val serviceUuids = listOf(
            UUID.fromString("00003500-0000-1000-8000-00805f9b34fb")
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isTrue()
    }
    
    @Test
    fun `isRavenDevice detects Legacy Health Service`() {
        val serviceUuids = listOf(
            UUID.fromString("00001809-0000-1000-8000-00805f9b34fb")
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isTrue()
    }
    
    @Test
    fun `isRavenDevice detects Legacy Location Service`() {
        val serviceUuids = listOf(
            UUID.fromString("00001819-0000-1000-8000-00805f9b34fb")
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isTrue()
    }
    
    @Test
    fun `isRavenDevice returns false for unknown UUIDs`() {
        val serviceUuids = listOf(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), // Generic Access
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")  // Generic Attribute
        )
        
        val result = DetectionPatterns.isRavenDevice(serviceUuids)
        
        assertThat(result).isFalse()
    }
    
    @Test
    fun `isRavenDevice returns false for empty list`() {
        val result = DetectionPatterns.isRavenDevice(emptyList())
        
        assertThat(result).isFalse()
    }
    
    // ============ Raven Firmware Version Tests ============
    
    @Test
    fun `estimateRavenFirmwareVersion returns 1_3_x for latest services`() {
        val serviceUuids = listOf(
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00003200-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00003400-0000-1000-8000-00805f9b34fb") // Upload Stats (1.3.x only)
        )
        
        val version = DetectionPatterns.estimateRavenFirmwareVersion(serviceUuids)
        
        assertThat(version).isEqualTo("1.3.x (Latest)")
    }
    
    @Test
    fun `estimateRavenFirmwareVersion returns 1_3_x for error service`() {
        val serviceUuids = listOf(
            UUID.fromString("00003500-0000-1000-8000-00805f9b34fb") // Error/Failure (1.3.x only)
        )
        
        val version = DetectionPatterns.estimateRavenFirmwareVersion(serviceUuids)
        
        assertThat(version).isEqualTo("1.3.x (Latest)")
    }
    
    @Test
    fun `estimateRavenFirmwareVersion returns 1_2_x for GPS and Power`() {
        val serviceUuids = listOf(
            UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"), // GPS
            UUID.fromString("00003200-0000-1000-8000-00805f9b34fb")  // Power
        )
        
        val version = DetectionPatterns.estimateRavenFirmwareVersion(serviceUuids)
        
        assertThat(version).isEqualTo("1.2.x")
    }
    
    @Test
    fun `estimateRavenFirmwareVersion returns 1_1_x for legacy health`() {
        val serviceUuids = listOf(
            UUID.fromString("00001809-0000-1000-8000-00805f9b34fb") // Legacy Health
        )
        
        val version = DetectionPatterns.estimateRavenFirmwareVersion(serviceUuids)
        
        assertThat(version).isEqualTo("1.1.x (Legacy)")
    }
    
    @Test
    fun `estimateRavenFirmwareVersion returns 1_1_x for legacy location`() {
        val serviceUuids = listOf(
            UUID.fromString("00001819-0000-1000-8000-00805f9b34fb") // Legacy Location
        )
        
        val version = DetectionPatterns.estimateRavenFirmwareVersion(serviceUuids)
        
        assertThat(version).isEqualTo("1.1.x (Legacy)")
    }
    
    @Test
    fun `estimateRavenFirmwareVersion returns Unknown for unrecognized`() {
        val serviceUuids = listOf(
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb") // Device Info only
        )
        
        val version = DetectionPatterns.estimateRavenFirmwareVersion(serviceUuids)
        
        assertThat(version).isEqualTo("Unknown")
    }
    
    // ============ Match Raven Services Tests ============
    
    @Test
    fun `matchRavenServices returns all matching services`() {
        val serviceUuids = listOf(
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00003200-0000-1000-8000-00805f9b34fb")
        )
        
        val matches = DetectionPatterns.matchRavenServices(serviceUuids)
        
        assertThat(matches).hasSize(3)
    }
    
    @Test
    fun `matchRavenServices returns empty for no matches`() {
        val serviceUuids = listOf(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        )
        
        val matches = DetectionPatterns.matchRavenServices(serviceUuids)
        
        assertThat(matches).isEmpty()
    }
    
    // ============ Threat Score Tests ============
    
    @Test
    fun `Raven devices have threat score 100`() {
        val ravenPattern = DetectionPatterns.bleNamePatterns.find { 
            it.deviceType == DeviceType.RAVEN_GUNSHOT_DETECTOR 
        }
        
        assertThat(ravenPattern?.threatScore).isEqualTo(100)
    }
    
    @Test
    fun `Flock devices have threat score 95`() {
        val flockPattern = DetectionPatterns.ssidPatterns.find { 
            it.deviceType == DeviceType.FLOCK_SAFETY_CAMERA && it.pattern.contains("flock")
        }
        
        assertThat(flockPattern?.threatScore).isEqualTo(95)
    }
    
    @Test
    fun `Penguin devices have threat score 85`() {
        val penguinPattern = DetectionPatterns.ssidPatterns.find { 
            it.deviceType == DeviceType.PENGUIN_SURVEILLANCE 
        }
        
        assertThat(penguinPattern?.threatScore).isEqualTo(85)
    }
}

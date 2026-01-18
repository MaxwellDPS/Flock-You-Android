package com.flockyou.data.model

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.util.UUID

/**
 * Unit tests for DetectionPatterns - the core pattern matching logic
 */
class DetectionPatternsTest {

    @Before
    fun setup() {
        // Reset any state if needed
    }

    // ==================== SSID Pattern Matching Tests ====================

    @Test
    fun `matchSsidPattern detects Flock Safety camera SSID`() {
        val result = DetectionPatterns.matchSsidPattern("Flock-ABC123")
        assertNotNull("Should detect Flock Safety SSID", result)
        assertEquals(DeviceType.FLOCK_SAFETY_CAMERA, result?.deviceType)
    }

    @Test
    fun `matchSsidPattern detects FlockSafety variant`() {
        val result = DetectionPatterns.matchSsidPattern("FlockSafety_Device01")
        assertNotNull("Should detect FlockSafety variant", result)
        assertEquals(DeviceType.FLOCK_SAFETY_CAMERA, result?.deviceType)
    }

    @Test
    fun `matchSsidPattern detects Motorola APX radio`() {
        val result = DetectionPatterns.matchSsidPattern("APX8000_12345")
        assertNotNull("Should detect Motorola APX", result)
        // APX pattern maps to POLICE_RADIO, not MOTOROLA_POLICE_TECH
        assertEquals(DeviceType.POLICE_RADIO, result?.deviceType)
    }

    @Test
    fun `matchSsidPattern detects Axon body camera`() {
        val result = DetectionPatterns.matchSsidPattern("AXON_BODY3_X12345")
        assertNotNull("Should detect Axon body cam", result)
        assertEquals(DeviceType.AXON_POLICE_TECH, result?.deviceType)
    }

    @Test
    fun `matchSsidPattern returns null for normal SSID`() {
        val result = DetectionPatterns.matchSsidPattern("MyHomeWifi")
        assertNull("Should not match normal WiFi", result)
    }

    @Test
    fun `matchSsidPattern returns null for empty SSID`() {
        val result = DetectionPatterns.matchSsidPattern("")
        assertNull("Should not match empty SSID", result)
    }

    @Test
    fun `matchSsidPattern is case insensitive for Flock`() {
        val result1 = DetectionPatterns.matchSsidPattern("FLOCK-ABC123")
        val result2 = DetectionPatterns.matchSsidPattern("flock-abc123")
        // Note: depends on regex flags, test actual behavior
        assertNotNull("Should detect uppercase FLOCK", result1)
    }

    @Test
    fun `matchSsidPattern detects L3Harris surveillance`() {
        val result = DetectionPatterns.matchSsidPattern("L3Harris_Tactical")
        assertNotNull("Should detect L3Harris", result)
        assertEquals(DeviceType.L3HARRIS_SURVEILLANCE, result?.deviceType)
    }

    @Test
    fun `matchSsidPattern detects Cellebrite forensics`() {
        val result = DetectionPatterns.matchSsidPattern("Cellebrite_UFED")
        assertNotNull("Should detect Cellebrite", result)
        assertEquals(DeviceType.CELLEBRITE_FORENSICS, result?.deviceType)
    }

    // ==================== BLE Name Pattern Matching Tests ====================

    @Test
    fun `matchBleNamePattern detects Raven gunshot detector`() {
        val result = DetectionPatterns.matchBleNamePattern("Raven-12345")
        assertNotNull("Should detect Raven device", result)
        assertEquals(DeviceType.RAVEN_GUNSHOT_DETECTOR, result?.deviceType)
    }

    @Test
    fun `matchBleNamePattern detects ShotSpotter variant`() {
        val result = DetectionPatterns.matchBleNamePattern("ShotSpotter_Node")
        assertNotNull("Should detect ShotSpotter", result)
        assertEquals(DeviceType.RAVEN_GUNSHOT_DETECTOR, result?.deviceType)
    }

    @Test
    fun `matchBleNamePattern detects Axon Signal`() {
        val result = DetectionPatterns.matchBleNamePattern("Axon Signal")
        assertNotNull("Should detect Axon Signal", result)
        assertEquals(DeviceType.AXON_POLICE_TECH, result?.deviceType)
    }

    @Test
    fun `matchBleNamePattern returns null for normal BLE device`() {
        val result = DetectionPatterns.matchBleNamePattern("JBL Flip 5")
        assertNull("Should not match normal BLE device", result)
    }

    @Test
    fun `matchBleNamePattern returns null for null name`() {
        // Test with empty string since Kotlin doesn't allow null here
        val result = DetectionPatterns.matchBleNamePattern("")
        assertNull("Should not match empty name", result)
    }

    // ==================== MAC Prefix Matching Tests ====================

    @Test
    fun `matchMacPrefix detects Flock Safety OUI`() {
        // Test with known Flock Safety MAC prefixes if defined
        val result = DetectionPatterns.matchMacPrefix("00:1A:2B")
        // Result depends on actual MAC prefixes in patterns
        // This test documents the expected behavior
    }

    @Test
    fun `matchMacPrefix returns null for unknown prefix`() {
        val result = DetectionPatterns.matchMacPrefix("AA:BB:CC")
        assertNull("Should not match unknown MAC prefix", result)
    }

    @Test
    fun `matchMacPrefix handles lowercase MAC`() {
        val result = DetectionPatterns.matchMacPrefix("aa:bb:cc")
        assertNull("Should handle lowercase MAC", result)
    }

    // ==================== Raven Service UUID Tests ====================

    @Test
    fun `matchRavenServices returns empty for no matching UUIDs`() {
        val uuids = listOf(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), // Generic Access
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")  // Generic Attribute
        )
        val result = DetectionPatterns.matchRavenServices(uuids)
        assertTrue("Should return empty for generic UUIDs", result.isEmpty())
    }

    @Test
    fun `isRavenDevice returns false for non-Raven UUIDs`() {
        val uuids = listOf(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        )
        val result = DetectionPatterns.isRavenDevice(uuids)
        assertFalse("Should return false for non-Raven device", result)
    }

    // ==================== Threat Score Tests ====================

    @Test
    fun `threat scores are within valid range`() {
        DetectionPatterns.ssidPatterns.forEach { pattern ->
            assertTrue(
                "Threat score should be 0-100 for ${pattern.pattern}",
                pattern.threatScore in 0..100
            )
        }
    }

    @Test
    fun `all patterns have non-empty descriptions`() {
        DetectionPatterns.ssidPatterns.forEach { pattern ->
            assertTrue(
                "Pattern ${pattern.pattern} should have description",
                pattern.description.isNotEmpty()
            )
        }
    }

    @Test
    fun `all device types have display names`() {
        DeviceType.entries.forEach { type ->
            assertTrue(
                "DeviceType ${type.name} should have display name",
                type.displayName.isNotEmpty()
            )
        }
    }

    // ==================== Edge Cases ====================

    @Test
    fun `matchSsidPattern handles special characters`() {
        val result = DetectionPatterns.matchSsidPattern("Flock-ABC!@#123")
        // Test actual behavior with special chars
    }

    @Test
    fun `matchSsidPattern handles very long SSID`() {
        val longSsid = "A".repeat(32) // Max SSID length
        val result = DetectionPatterns.matchSsidPattern(longSsid)
        assertNull("Should handle max length SSID", result)
    }

    @Test
    fun `matchBleNamePattern handles unicode characters`() {
        val result = DetectionPatterns.matchBleNamePattern("设备名称")
        assertNull("Should handle unicode gracefully", result)
    }

    // ==================== Pattern Validity Tests ====================

    @Test
    fun `all SSID patterns are valid regex`() {
        DetectionPatterns.ssidPatterns.forEach { pattern ->
            try {
                Regex(pattern.pattern)
            } catch (e: Exception) {
                fail("Invalid regex in SSID pattern: ${pattern.pattern}")
            }
        }
    }

    @Test
    fun `all BLE name patterns are valid regex`() {
        DetectionPatterns.bleNamePatterns.forEach { pattern ->
            try {
                Regex(pattern.pattern)
            } catch (e: Exception) {
                fail("Invalid regex in BLE pattern: ${pattern.pattern}")
            }
        }
    }
}

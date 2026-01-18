package com.flockyou.data.model

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for Detection model and related utilities
 */
class DetectionTest {

    // ==================== ThreatLevel Tests ====================

    @Test
    fun `ThreatLevel CRITICAL has highest priority`() {
        val levels = ThreatLevel.entries.sortedByDescending { it.ordinal }
        assertEquals(ThreatLevel.INFO, levels.first())
        assertEquals(ThreatLevel.CRITICAL, levels.last())
    }

    @Test
    fun `ThreatLevel has valid display names`() {
        ThreatLevel.entries.forEach { level ->
            assertTrue(
                "ThreatLevel ${level.name} should have display name",
                level.displayName.isNotEmpty()
            )
            assertTrue(
                "ThreatLevel ${level.name} should have description",
                level.description.isNotEmpty()
            )
        }
    }

    // ==================== SignalStrength Tests ====================

    @Test
    fun `rssiToSignalStrength returns EXCELLENT for strong signal`() {
        assertEquals(SignalStrength.EXCELLENT, rssiToSignalStrength(-40))
        assertEquals(SignalStrength.EXCELLENT, rssiToSignalStrength(-49))
    }

    @Test
    fun `rssiToSignalStrength returns GOOD for good signal`() {
        assertEquals(SignalStrength.GOOD, rssiToSignalStrength(-50))
        assertEquals(SignalStrength.GOOD, rssiToSignalStrength(-59))
    }

    @Test
    fun `rssiToSignalStrength returns MEDIUM for medium signal`() {
        assertEquals(SignalStrength.MEDIUM, rssiToSignalStrength(-60))
        assertEquals(SignalStrength.MEDIUM, rssiToSignalStrength(-69))
    }

    @Test
    fun `rssiToSignalStrength returns WEAK for weak signal`() {
        assertEquals(SignalStrength.WEAK, rssiToSignalStrength(-70))
        assertEquals(SignalStrength.WEAK, rssiToSignalStrength(-79))
    }

    @Test
    fun `rssiToSignalStrength returns VERY_WEAK for very weak signal`() {
        assertEquals(SignalStrength.VERY_WEAK, rssiToSignalStrength(-80))
        assertEquals(SignalStrength.VERY_WEAK, rssiToSignalStrength(-100))
    }

    @Test
    fun `SignalStrength has valid display names`() {
        SignalStrength.entries.forEach { strength ->
            assertTrue(
                "SignalStrength ${strength.name} should have display name",
                strength.displayName.isNotEmpty()
            )
            assertTrue(
                "SignalStrength ${strength.name} should have description",
                strength.description.isNotEmpty()
            )
        }
    }

    // ==================== DeviceType Tests ====================

    @Test
    fun `DeviceType has valid display names and emojis`() {
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

    @Test
    fun `DeviceType includes STINGRAY_IMSI for cell site simulators`() {
        assertTrue(
            "Should have STINGRAY_IMSI type",
            DeviceType.entries.any { it == DeviceType.STINGRAY_IMSI }
        )
    }

    @Test
    fun `DeviceType includes UNKNOWN_SURVEILLANCE as fallback`() {
        assertTrue(
            "Should have UNKNOWN_SURVEILLANCE type",
            DeviceType.entries.any { it == DeviceType.UNKNOWN_SURVEILLANCE }
        )
    }

    // ==================== DetectionProtocol Tests ====================

    @Test
    fun `DetectionProtocol has all required types`() {
        val protocols = DetectionProtocol.entries
        assertTrue("Should have WIFI", protocols.contains(DetectionProtocol.WIFI))
        assertTrue("Should have BLUETOOTH_LE", protocols.contains(DetectionProtocol.BLUETOOTH_LE))
        assertTrue("Should have CELLULAR", protocols.contains(DetectionProtocol.CELLULAR))
    }

    @Test
    fun `DetectionProtocol has valid display names`() {
        DetectionProtocol.entries.forEach { protocol ->
            assertTrue(
                "DetectionProtocol ${protocol.name} should have display name",
                protocol.displayName.isNotEmpty()
            )
        }
    }

    // ==================== DetectionMethod Tests ====================

    @Test
    fun `DetectionMethod has valid descriptions`() {
        DetectionMethod.entries.forEach { method ->
            assertTrue(
                "DetectionMethod ${method.name} should have display name",
                method.displayName.isNotEmpty()
            )
            assertTrue(
                "DetectionMethod ${method.name} should have description",
                method.description.isNotEmpty()
            )
        }
    }

    // ==================== Detection Entity Tests ====================

    @Test
    fun `Detection has unique ID by default`() {
        val detection1 = Detection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            threatLevel = ThreatLevel.HIGH,
            rssi = -60,
            signalStrength = SignalStrength.GOOD
        )
        val detection2 = Detection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            threatLevel = ThreatLevel.HIGH,
            rssi = -60,
            signalStrength = SignalStrength.GOOD
        )
        assertNotEquals("Should have unique IDs", detection1.id, detection2.id)
    }

    @Test
    fun `Detection timestamp defaults to current time`() {
        val before = System.currentTimeMillis()
        val detection = Detection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            threatLevel = ThreatLevel.HIGH,
            rssi = -60,
            signalStrength = SignalStrength.GOOD
        )
        val after = System.currentTimeMillis()

        assertTrue(
            "Timestamp should be between before and after",
            detection.timestamp in before..after
        )
    }

    @Test
    fun `Detection seenCount defaults to 1`() {
        val detection = Detection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            threatLevel = ThreatLevel.HIGH,
            rssi = -60,
            signalStrength = SignalStrength.GOOD
        )
        assertEquals(1, detection.seenCount)
    }

    @Test
    fun `Detection isActive defaults to true`() {
        val detection = Detection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            threatLevel = ThreatLevel.HIGH,
            rssi = -60,
            signalStrength = SignalStrength.GOOD
        )
        assertTrue(detection.isActive)
    }

    @Test
    fun `Detection can store location coordinates`() {
        val detection = Detection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            threatLevel = ThreatLevel.HIGH,
            rssi = -60,
            signalStrength = SignalStrength.GOOD,
            latitude = 47.6062,
            longitude = -122.3321
        )
        assertEquals(47.6062, detection.latitude!!, 0.0001)
        assertEquals(-122.3321, detection.longitude!!, 0.0001)
    }

    @Test
    fun `Detection can store MAC address`() {
        val detection = Detection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.MAC_PREFIX,
            threatLevel = ThreatLevel.HIGH,
            rssi = -60,
            signalStrength = SignalStrength.GOOD,
            macAddress = "AA:BB:CC:DD:EE:FF"
        )
        assertEquals("AA:BB:CC:DD:EE:FF", detection.macAddress)
    }

    @Test
    fun `Detection can store SSID`() {
        val detection = Detection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            threatLevel = ThreatLevel.HIGH,
            rssi = -60,
            signalStrength = SignalStrength.GOOD,
            ssid = "Flock-ABC123"
        )
        assertEquals("Flock-ABC123", detection.ssid)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `Detection handles null optional fields`() {
        val detection = Detection(
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.BLE_DEVICE_NAME,
            threatLevel = ThreatLevel.INFO,
            rssi = -80,
            signalStrength = SignalStrength.WEAK
        )
        assertNull(detection.macAddress)
        assertNull(detection.ssid)
        assertNull(detection.latitude)
        assertNull(detection.longitude)
        assertNull(detection.deviceName)
        assertNull(detection.manufacturer)
    }

    @Test
    fun `rssiToSignalStrength handles extreme values`() {
        // Very strong signal (unrealistic but should handle)
        assertEquals(SignalStrength.EXCELLENT, rssiToSignalStrength(0))
        assertEquals(SignalStrength.EXCELLENT, rssiToSignalStrength(-10))
        
        // Very weak signal
        assertEquals(SignalStrength.VERY_WEAK, rssiToSignalStrength(-120))
        assertEquals(SignalStrength.VERY_WEAK, rssiToSignalStrength(-200))
    }
}

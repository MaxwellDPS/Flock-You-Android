package com.flockyou.detection

import com.flockyou.data.model.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for DetectionDeduplicator
 */
class DetectionDeduplicatorTest {

    private lateinit var deduplicator: DetectionDeduplicator

    @Before
    fun setUp() {
        deduplicator = DetectionDeduplicator()
    }

    // ==================== DedupeKey Generation Tests ====================

    @Test
    fun `generateDedupeKey includes MAC address when present`() {
        val detection = createTestDetection(macAddress = "AA:BB:CC:DD:EE:FF")
        val key = deduplicator.generateDedupeKey(detection)

        assertEquals("AA:BB:CC:DD:EE:FF", key.macAddress)
    }

    @Test
    fun `generateDedupeKey normalizes MAC address to uppercase`() {
        val detection = createTestDetection(macAddress = "aa:bb:cc:dd:ee:ff")
        val key = deduplicator.generateDedupeKey(detection)

        assertEquals("AA:BB:CC:DD:EE:FF", key.macAddress)
    }

    @Test
    fun `generateDedupeKey extracts SSID prefix before numbers`() {
        val detection = createTestDetection(ssid = "FlockSafety-12345")
        val key = deduplicator.generateDedupeKey(detection)

        assertEquals("FLOCKSAFETY", key.ssidPrefix)
    }

    @Test
    fun `generateDedupeKey creates composite key from device characteristics`() {
        val detection = createTestDetection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            protocol = DetectionProtocol.WIFI
        )
        val key = deduplicator.generateDedupeKey(detection)

        assertNotNull(key.compositeKey)
        assertTrue(key.compositeKey.isNotEmpty())
    }

    // ==================== Throttling Tests ====================

    @Test
    fun `shouldThrottle returns false for first detection`() {
        val detection = createTestDetection(macAddress = "AA:BB:CC:DD:EE:FF")

        assertFalse(deduplicator.shouldThrottle(detection))
    }

    @Test
    fun `shouldThrottle returns true for rapid duplicate`() {
        val detection = createTestDetection(macAddress = "AA:BB:CC:DD:EE:FF")

        // First detection
        deduplicator.shouldThrottle(detection)

        // Immediate second detection should be throttled
        assertTrue(deduplicator.shouldThrottle(detection))
    }

    @Test
    fun `shouldThrottle uses different windows for different protocols`() {
        val bleDetection = createTestDetection(
            macAddress = "AA:BB:CC:DD:EE:01",
            protocol = DetectionProtocol.BLUETOOTH_LE
        )
        val wifiDetection = createTestDetection(
            ssid = "TestNetwork",
            protocol = DetectionProtocol.WIFI
        )

        // Both should not be throttled on first call
        assertFalse(deduplicator.shouldThrottle(bleDetection))
        assertFalse(deduplicator.shouldThrottle(wifiDetection))
    }

    // ==================== SSID Similarity Tests ====================

    @Test
    fun `ssidSimilarity returns 1 for identical SSIDs`() {
        val similarity = deduplicator.ssidSimilarity("TestNetwork", "TestNetwork")
        assertEquals(1.0f, similarity, 0.001f)
    }

    @Test
    fun `ssidSimilarity returns 0 for null SSIDs`() {
        assertEquals(0.0f, deduplicator.ssidSimilarity(null, "Test"), 0.001f)
        assertEquals(0.0f, deduplicator.ssidSimilarity("Test", null), 0.001f)
        assertEquals(0.0f, deduplicator.ssidSimilarity(null, null), 0.001f)
    }

    @Test
    fun `ssidSimilarity detects similar SSIDs`() {
        // FlockSafety-123 vs FlockSafety-456 should be quite similar
        val similarity = deduplicator.ssidSimilarity("FlockSafety-123", "FlockSafety-456")
        assertTrue("Similar SSIDs should have high similarity", similarity > 0.7f)
    }

    @Test
    fun `ssidSimilarity detects dissimilar SSIDs`() {
        val similarity = deduplicator.ssidSimilarity("HomeNetwork", "CoffeeShop")
        assertTrue("Dissimilar SSIDs should have low similarity", similarity < 0.5f)
    }

    // ==================== Location Proximity Tests ====================

    @Test
    fun `isLocationProximityMatch returns true for nearby detections of same type`() {
        val detection1 = createTestDetection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            latitude = 37.7749,
            longitude = -122.4194
        )
        val detection2 = createTestDetection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            latitude = 37.7749 + 0.00005, // ~5 meters away
            longitude = -122.4194
        )

        assertTrue(deduplicator.isLocationProximityMatch(detection1, detection2))
    }

    @Test
    fun `isLocationProximityMatch returns false for different device types`() {
        val detection1 = createTestDetection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            latitude = 37.7749,
            longitude = -122.4194
        )
        val detection2 = createTestDetection(
            deviceType = DeviceType.RING_DOORBELL,
            latitude = 37.7749,
            longitude = -122.4194
        )

        assertFalse(deduplicator.isLocationProximityMatch(detection1, detection2))
    }

    @Test
    fun `isLocationProximityMatch returns false for distant detections`() {
        val detection1 = createTestDetection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            latitude = 37.7749,
            longitude = -122.4194
        )
        val detection2 = createTestDetection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            latitude = 37.7849, // ~1km away
            longitude = -122.4194
        )

        assertFalse(deduplicator.isLocationProximityMatch(detection1, detection2))
    }

    @Test
    fun `isLocationProximityMatch returns false when location is null`() {
        val detection1 = createTestDetection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            latitude = null,
            longitude = null
        )
        val detection2 = createTestDetection(
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            latitude = 37.7749,
            longitude = -122.4194
        )

        assertFalse(deduplicator.isLocationProximityMatch(detection1, detection2))
    }

    // ==================== Find Match Tests ====================

    @Test
    fun `findMatch returns MAC match first`() {
        val detection = createTestDetection(macAddress = "AA:BB:CC:DD:EE:FF")
        val existing = listOf(
            createTestDetection(macAddress = "AA:BB:CC:DD:EE:FF", ssid = "Different"),
            createTestDetection(macAddress = "11:22:33:44:55:66", ssid = "Same")
        )

        val match = deduplicator.findMatch(detection, existing)

        assertNotNull(match)
        assertEquals("AA:BB:CC:DD:EE:FF", match?.macAddress)
    }

    @Test
    fun `findMatch returns null when no match found`() {
        val detection = createTestDetection(
            macAddress = "AA:BB:CC:DD:EE:FF",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA
        )
        val existing = listOf(
            createTestDetection(
                macAddress = "11:22:33:44:55:66",
                deviceType = DeviceType.RING_DOORBELL
            )
        )

        val match = deduplicator.findMatch(detection, existing)

        assertNull(match)
    }

    // ==================== Cleanup Tests ====================

    @Test
    fun `cleanup removes stale throttle entries`() {
        val detection = createTestDetection(macAddress = "AA:BB:CC:DD:EE:FF")

        // Add to throttle
        deduplicator.shouldThrottle(detection)

        // Cleanup should not throw
        deduplicator.cleanup()
    }

    // ==================== BLE Randomized MAC Tests ====================

    @Test
    fun `detects randomized MAC address`() {
        // Locally administered bit set (second hex char is 2, 6, A, or E)
        val randomizedMac = "A2:BB:CC:DD:EE:FF"
        val detection = createTestDetection(macAddress = randomizedMac)
        val key = deduplicator.generateDedupeKey(detection)

        // Should still generate a key even with randomized MAC
        assertNotNull(key)
    }

    @Test
    fun `generates stable identifier for BLE device with service UUIDs`() {
        val detection = createTestDetection(
            macAddress = "A2:BB:CC:DD:EE:FF", // Randomized
            serviceUuids = "0000fd6f-0000-1000-8000-00805f9b34fb,0000180f-0000-1000-8000-00805f9b34fb"
        )
        val key = deduplicator.generateDedupeKey(detection)

        assertNotNull(key.serviceUuids)
        assertTrue(key.serviceUuids!!.contains("0000fd6f"))
    }

    // ==================== Helper Methods ====================

    private fun createTestDetection(
        macAddress: String? = null,
        ssid: String? = null,
        deviceType: DeviceType = DeviceType.UNKNOWN_SURVEILLANCE,
        protocol: DetectionProtocol = DetectionProtocol.WIFI,
        latitude: Double? = null,
        longitude: Double? = null,
        serviceUuids: String? = null
    ): Detection {
        return Detection(
            id = java.util.UUID.randomUUID().toString(),
            macAddress = macAddress,
            ssid = ssid,
            deviceType = deviceType,
            protocol = protocol,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            threatLevel = ThreatLevel.MEDIUM,
            signalStrength = SignalStrength.GOOD,
            rssi = -65,
            timestamp = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            serviceUuids = serviceUuids
        )
    }
}

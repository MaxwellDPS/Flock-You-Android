package com.flockyou

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.flockyou.data.model.*

/**
 * Comprehensive E2E unit tests for the Flock-You detection system.
 * These tests validate the core detection logic, data models, and conversions.
 */
class ExampleUnitTest {

    // ==================== Detection Model E2E Tests ====================

    @Test
    fun `Detection can be created with all required fields`() {
        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            rssi = -60,
            signalStrength = SignalStrength.MEDIUM,
            threatLevel = ThreatLevel.HIGH
        )

        assertNotNull(detection.id)
        assertTrue(detection.isActive)
        assertEquals(1, detection.seenCount)
        assertEquals(DetectionProtocol.WIFI, detection.protocol)
    }

    @Test
    fun `scoreToThreatLevel maps scores correctly`() {
        assertEquals(ThreatLevel.INFO, scoreToThreatLevel(0))
        assertEquals(ThreatLevel.INFO, scoreToThreatLevel(29))
        assertEquals(ThreatLevel.LOW, scoreToThreatLevel(30))
        assertEquals(ThreatLevel.LOW, scoreToThreatLevel(49))
        assertEquals(ThreatLevel.MEDIUM, scoreToThreatLevel(50))
        assertEquals(ThreatLevel.MEDIUM, scoreToThreatLevel(69))
        assertEquals(ThreatLevel.HIGH, scoreToThreatLevel(70))
        assertEquals(ThreatLevel.HIGH, scoreToThreatLevel(89))
        assertEquals(ThreatLevel.CRITICAL, scoreToThreatLevel(90))
        assertEquals(ThreatLevel.CRITICAL, scoreToThreatLevel(100))
    }

    @Test
    fun `rssiToDistance provides meaningful estimates`() {
        assertEquals("< 1m", rssiToDistance(-30))
        assertEquals("~1-5m", rssiToDistance(-45))
        assertEquals("~5-15m", rssiToDistance(-55))
        assertEquals("~15-30m", rssiToDistance(-65))
        assertEquals("~30-50m", rssiToDistance(-75))
        assertEquals("~50-100m", rssiToDistance(-85))
        assertEquals("> 100m", rssiToDistance(-95))
    }

    // ==================== Protocol Coverage Tests ====================

    @Test
    fun `all detection protocols have unique display names`() {
        val displayNames = DetectionProtocol.entries.map { it.displayName }
        assertEquals(displayNames.size, displayNames.toSet().size)
    }

    @Test
    fun `all protocols are represented in detection methods`() {
        assertTrue(DetectionMethod.entries.any { it.name.startsWith("CELL_") })
        assertTrue(DetectionMethod.entries.any { it.name.startsWith("WIFI_") })
        assertTrue(DetectionMethod.entries.any { it.name.startsWith("SAT_") })
        assertTrue(DetectionMethod.entries.any { it.name.startsWith("RF_") })
        assertTrue(DetectionMethod.entries.any { it.name.startsWith("ULTRASONIC_") })
    }

    // ==================== Threat Level Hierarchy Tests ====================

    @Test
    fun `threat levels are ordered correctly by severity`() {
        // Enum is ordered: CRITICAL(0), HIGH(1), MEDIUM(2), LOW(3), INFO(4)
        // Higher severity has lower ordinal
        val levels = listOf(
            ThreatLevel.CRITICAL,
            ThreatLevel.HIGH,
            ThreatLevel.MEDIUM,
            ThreatLevel.LOW,
            ThreatLevel.INFO
        )

        for (i in 0 until levels.size - 1) {
            assertTrue(
                "${levels[i]} should have lower ordinal than ${levels[i + 1]}",
                levels[i].ordinal < levels[i + 1].ordinal
            )
        }
    }
}

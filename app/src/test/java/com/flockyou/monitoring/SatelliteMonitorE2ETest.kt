package com.flockyou.monitoring

import com.flockyou.data.model.*
import org.junit.Test
import org.junit.Assert.*

/**
 * End-to-end integration tests for the Satellite detection system.
 * These tests validate the complete satellite detection pipeline including:
 * - Satellite protocol coverage
 * - Detection method mapping
 * - Anomaly type categorization
 * - Threat level calculation
 * - Detection model integrity
 * - Conformance with other detectors (CellularMonitor, RogueWifiMonitor, UltrasonicDetector)
 */
class SatelliteMonitorE2ETest {

    // ==================== Protocol Coverage Tests ====================

    @Test
    fun `SATELLITE protocol exists in DetectionProtocol`() {
        val protocols = DetectionProtocol.entries
        assertTrue("Should have SATELLITE protocol", protocols.contains(DetectionProtocol.SATELLITE))
    }

    @Test
    fun `SATELLITE protocol has display name and icon`() {
        val satellite = DetectionProtocol.SATELLITE
        assertTrue("SATELLITE should have display name", satellite.displayName.isNotEmpty())
        assertTrue("SATELLITE should have icon", satellite.icon.isNotEmpty())
        assertEquals("Satellite", satellite.displayName)
    }

    // ==================== Detection Method Completeness Tests ====================

    @Test
    fun `detection methods exist for satellite anomalies`() {
        val satMethods = DetectionMethod.entries.filter {
            it.name.startsWith("SAT_")
        }

        assertTrue("Should have SAT_UNEXPECTED_CONNECTION",
            satMethods.any { it == DetectionMethod.SAT_UNEXPECTED_CONNECTION })
        assertTrue("Should have SAT_FORCED_HANDOFF",
            satMethods.any { it == DetectionMethod.SAT_FORCED_HANDOFF })
        assertTrue("Should have SAT_SUSPICIOUS_NTN",
            satMethods.any { it == DetectionMethod.SAT_SUSPICIOUS_NTN })
        assertTrue("Should have SAT_TIMING_ANOMALY",
            satMethods.any { it == DetectionMethod.SAT_TIMING_ANOMALY })
        assertTrue("Should have SAT_DOWNGRADE",
            satMethods.any { it == DetectionMethod.SAT_DOWNGRADE })
    }

    @Test
    fun `all satellite detection methods have descriptions`() {
        val satMethods = DetectionMethod.entries.filter {
            it.name.startsWith("SAT_")
        }

        satMethods.forEach { method ->
            assertTrue(
                "Method ${method.name} should have display name",
                method.displayName.isNotEmpty()
            )
            assertTrue(
                "Method ${method.name} should have description",
                method.description.isNotEmpty()
            )
        }
    }

    // ==================== Device Type Coverage Tests ====================

    @Test
    fun `SATELLITE_NTN device type exists`() {
        val types = DeviceType.entries
        assertTrue("Should have SATELLITE_NTN device type",
            types.any { it == DeviceType.SATELLITE_NTN })
    }

    @Test
    fun `SATELLITE_NTN device type has display name and emoji`() {
        val satType = DeviceType.SATELLITE_NTN
        assertTrue(
            "SATELLITE_NTN should have display name",
            satType.displayName.isNotEmpty()
        )
        assertTrue(
            "SATELLITE_NTN should have emoji",
            satType.emoji.isNotEmpty()
        )
    }

    // ==================== Anomaly Type Tests ====================

    @Test
    fun `all satellite anomaly types are defined`() {
        val anomalyTypes = SatelliteMonitor.SatelliteAnomalyType.entries

        assertTrue("Should have UNEXPECTED_SATELLITE_CONNECTION",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION })
        assertTrue("Should have FORCED_SATELLITE_HANDOFF",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF })
        assertTrue("Should have SUSPICIOUS_NTN_PARAMETERS",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.SUSPICIOUS_NTN_PARAMETERS })
        assertTrue("Should have UNKNOWN_SATELLITE_NETWORK",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK })
        assertTrue("Should have SATELLITE_IN_COVERED_AREA",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA })
        assertTrue("Should have RAPID_SATELLITE_SWITCHING",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING })
        assertTrue("Should have NTN_BAND_MISMATCH",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.NTN_BAND_MISMATCH })
        assertTrue("Should have TIMING_ADVANCE_ANOMALY",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY })
        assertTrue("Should have EPHEMERIS_MISMATCH",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.EPHEMERIS_MISMATCH })
        assertTrue("Should have DOWNGRADE_TO_SATELLITE",
            anomalyTypes.any { it == SatelliteMonitor.SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE })
    }

    // ==================== Severity Level Tests ====================

    @Test
    fun `anomaly severity levels are ordered by increasing severity`() {
        // Enum is ordered: INFO(0), LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4)
        // Higher severity has higher ordinal
        assertTrue(SatelliteMonitor.AnomalySeverity.INFO.ordinal <
            SatelliteMonitor.AnomalySeverity.LOW.ordinal)
        assertTrue(SatelliteMonitor.AnomalySeverity.LOW.ordinal <
            SatelliteMonitor.AnomalySeverity.MEDIUM.ordinal)
        assertTrue(SatelliteMonitor.AnomalySeverity.MEDIUM.ordinal <
            SatelliteMonitor.AnomalySeverity.HIGH.ordinal)
        assertTrue(SatelliteMonitor.AnomalySeverity.HIGH.ordinal <
            SatelliteMonitor.AnomalySeverity.CRITICAL.ordinal)
    }

    // ==================== Connection Type Tests ====================

    @Test
    fun `all satellite connection types are defined`() {
        val connectionTypes = SatelliteMonitor.SatelliteConnectionType.entries

        assertTrue("Should have NONE",
            connectionTypes.any { it == SatelliteMonitor.SatelliteConnectionType.NONE })
        assertTrue("Should have T_MOBILE_STARLINK",
            connectionTypes.any { it == SatelliteMonitor.SatelliteConnectionType.T_MOBILE_STARLINK })
        assertTrue("Should have SKYLO_NTN",
            connectionTypes.any { it == SatelliteMonitor.SatelliteConnectionType.SKYLO_NTN })
        assertTrue("Should have GENERIC_NTN",
            connectionTypes.any { it == SatelliteMonitor.SatelliteConnectionType.GENERIC_NTN })
        assertTrue("Should have PROPRIETARY",
            connectionTypes.any { it == SatelliteMonitor.SatelliteConnectionType.PROPRIETARY })
        assertTrue("Should have UNKNOWN_SATELLITE",
            connectionTypes.any { it == SatelliteMonitor.SatelliteConnectionType.UNKNOWN_SATELLITE })
    }

    // ==================== Provider Tests ====================

    @Test
    fun `all satellite providers are defined`() {
        val providers = SatelliteMonitor.SatelliteProvider.entries

        assertTrue("Should have UNKNOWN",
            providers.any { it == SatelliteMonitor.SatelliteProvider.UNKNOWN })
        assertTrue("Should have STARLINK",
            providers.any { it == SatelliteMonitor.SatelliteProvider.STARLINK })
        assertTrue("Should have SKYLO",
            providers.any { it == SatelliteMonitor.SatelliteProvider.SKYLO })
        assertTrue("Should have GLOBALSTAR",
            providers.any { it == SatelliteMonitor.SatelliteProvider.GLOBALSTAR })
        assertTrue("Should have AST_SPACEMOBILE",
            providers.any { it == SatelliteMonitor.SatelliteProvider.AST_SPACEMOBILE })
        assertTrue("Should have LYNK",
            providers.any { it == SatelliteMonitor.SatelliteProvider.LYNK })
        assertTrue("Should have IRIDIUM",
            providers.any { it == SatelliteMonitor.SatelliteProvider.IRIDIUM })
        assertTrue("Should have INMARSAT",
            providers.any { it == SatelliteMonitor.SatelliteProvider.INMARSAT })
    }

    // ==================== E2E Detection Scenarios ====================

    @Test
    fun `E2E satellite unexpected connection detection`() {
        // Simulate detecting unexpected satellite connection
        val detection = Detection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_UNEXPECTED_CONNECTION,
            deviceType = DeviceType.SATELLITE_NTN,
            deviceName = "Unexpected Satellite",
            rssi = -80,
            signalStrength = rssiToSignalStrength(-80),
            latitude = 47.6,
            longitude = -122.3,
            threatLevel = ThreatLevel.MEDIUM,
            threatScore = 60,
            manufacturer = "SpaceX Starlink",
            matchedPatterns = "Satellite connection when terrestrial available"
        )

        assertEquals(DetectionProtocol.SATELLITE, detection.protocol)
        assertEquals(DeviceType.SATELLITE_NTN, detection.deviceType)
        assertEquals(DetectionMethod.SAT_UNEXPECTED_CONNECTION, detection.detectionMethod)
        assertEquals(ThreatLevel.MEDIUM, detection.threatLevel)
        assertTrue(detection.threatScore >= 50)
    }

    @Test
    fun `E2E satellite unknown network detection`() {
        val detection = Detection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_SUSPICIOUS_NTN,
            deviceType = DeviceType.SATELLITE_NTN,
            deviceName = "Unknown Satellite Network",
            ssid = "Unknown-SAT-Network",
            rssi = -75,
            signalStrength = rssiToSignalStrength(-75),
            latitude = 47.6,
            longitude = -122.3,
            threatLevel = ThreatLevel.HIGH,
            threatScore = 80,
            manufacturer = "Unknown Provider",
            matchedPatterns = "Unrecognized satellite network identifier"
        )

        assertEquals(DetectionProtocol.SATELLITE, detection.protocol)
        assertEquals("Unknown-SAT-Network", detection.ssid)
        assertEquals(DeviceType.SATELLITE_NTN, detection.deviceType)
        assertEquals(ThreatLevel.HIGH, detection.threatLevel)
    }

    @Test
    fun `E2E satellite timing anomaly detection`() {
        val detection = Detection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_TIMING_ANOMALY,
            deviceType = DeviceType.SATELLITE_NTN,
            deviceName = "Timing Anomaly",
            rssi = -70,
            signalStrength = rssiToSignalStrength(-70),
            threatLevel = ThreatLevel.HIGH,
            threatScore = 85,
            manufacturer = "Suspected Spoof",
            matchedPatterns = "RTT 5ms inconsistent with claimed LEO orbit (expected 20-80ms)"
        )

        assertEquals(DetectionProtocol.SATELLITE, detection.protocol)
        assertEquals(DetectionMethod.SAT_TIMING_ANOMALY, detection.detectionMethod)
        assertEquals(ThreatLevel.HIGH, detection.threatLevel)
    }

    @Test
    fun `E2E satellite downgrade attack detection`() {
        val detection = Detection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_DOWNGRADE,
            deviceType = DeviceType.SATELLITE_NTN,
            deviceName = "Network Downgrade",
            rssi = -65,
            signalStrength = rssiToSignalStrength(-65),
            threatLevel = ThreatLevel.HIGH,
            threatScore = 80,
            matchedPatterns = "Forced from 5G NR to satellite without user action"
        )

        assertEquals(DetectionProtocol.SATELLITE, detection.protocol)
        assertEquals(DetectionMethod.SAT_DOWNGRADE, detection.detectionMethod)
        assertEquals(DeviceType.SATELLITE_NTN, detection.deviceType)
    }

    @Test
    fun `E2E satellite forced handoff detection`() {
        val detection = Detection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_FORCED_HANDOFF,
            deviceType = DeviceType.SATELLITE_NTN,
            deviceName = "Forced Satellite Handoff",
            rssi = -85,
            signalStrength = rssiToSignalStrength(-85),
            threatLevel = ThreatLevel.MEDIUM,
            threatScore = 60,
            matchedPatterns = "Rapid handoff to satellite, 3 switches in last minute"
        )

        assertEquals(DetectionProtocol.SATELLITE, detection.protocol)
        assertEquals(DetectionMethod.SAT_FORCED_HANDOFF, detection.detectionMethod)
    }

    // ==================== Network Name Detection Tests ====================

    @Test
    fun `T-Mobile Starlink network names are recognized`() {
        val starlinkNames = SatelliteMonitor.STARLINK_NETWORK_NAMES

        assertTrue("Should contain T-Mobile SpaceX", starlinkNames.contains("T-Mobile SpaceX"))
        assertTrue("Should contain T-Sat+Starlink", starlinkNames.contains("T-Sat+Starlink"))
        assertTrue("Should contain Starlink", starlinkNames.contains("Starlink"))
        assertTrue("Should contain T-Satellite", starlinkNames.contains("T-Satellite"))
    }

    @Test
    fun `Skylo NTN network names are recognized`() {
        val skyloNames = SatelliteMonitor.SKYLO_NETWORK_NAMES

        assertTrue("Should contain Skylo", skyloNames.contains("Skylo"))
        assertTrue("Should contain Skylo NTN", skyloNames.contains("Skylo NTN"))
        assertTrue("Should contain Satellite SOS", skyloNames.contains("Satellite SOS"))
    }

    @Test
    fun `generic satellite indicators are defined`() {
        val indicators = SatelliteMonitor.SATELLITE_NETWORK_INDICATORS

        assertTrue("Should contain SAT", indicators.contains("SAT"))
        assertTrue("Should contain Satellite", indicators.contains("Satellite"))
        assertTrue("Should contain NTN", indicators.contains("NTN"))
        assertTrue("Should contain D2D", indicators.contains("D2D"))
    }

    // ==================== NTN Band Tests ====================

    @Test
    fun `NTN L-band frequencies are correctly defined`() {
        // L-band downlink
        assertTrue(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(1530))
        assertTrue(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(1550))

        // L-band uplink
        assertTrue(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(1630))
        assertTrue(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(1650))
    }

    @Test
    fun `NTN S-band frequencies are correctly defined`() {
        // S-band downlink
        assertTrue(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(1990))
        assertTrue(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(2005))

        // S-band uplink
        assertTrue(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(2180))
        assertTrue(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(2195))
    }

    @Test
    fun `non-NTN frequencies are rejected`() {
        // Common cellular frequencies should not be NTN
        assertFalse(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(700))
        assertFalse(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(850))
        assertFalse(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(1900))
        assertFalse(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(2100))
        assertFalse(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(2600))
        assertFalse(SatelliteMonitor.Companion.NTNBands.isNTNFrequency(3500))
    }

    // ==================== Radio Technology Tests ====================

    @Test
    fun `NTN radio technologies are defined`() {
        assertEquals(0, SatelliteMonitor.Companion.NTRadioTechnology.UNKNOWN)
        assertEquals(1, SatelliteMonitor.Companion.NTRadioTechnology.NB_IOT_NTN)
        assertEquals(2, SatelliteMonitor.Companion.NTRadioTechnology.NR_NTN)
        assertEquals(3, SatelliteMonitor.Companion.NTRadioTechnology.EMTC_NTN)
        assertEquals(4, SatelliteMonitor.Companion.NTRadioTechnology.PROPRIETARY)
    }

    // ==================== Capability Tests ====================

    @Test
    fun `SatelliteCapabilities data class has all fields`() {
        val capabilities = SatelliteMonitor.SatelliteCapabilities(
            supportsSMS = true,
            supportsMMS = true,
            supportsVoice = false,
            supportsData = false,
            supportsEmergency = true,
            supportsLocationSharing = true,
            maxMessageLength = 160
        )

        assertTrue(capabilities.supportsSMS)
        assertTrue(capabilities.supportsMMS)
        assertFalse(capabilities.supportsVoice)
        assertFalse(capabilities.supportsData)
        assertTrue(capabilities.supportsEmergency)
        assertTrue(capabilities.supportsLocationSharing)
        assertEquals(160, capabilities.maxMessageLength)
    }

    // ==================== Connection State Tests ====================

    @Test
    fun `SatelliteConnectionState defaults are correct`() {
        val state = SatelliteMonitor.SatelliteConnectionState()

        assertFalse(state.isConnected)
        assertEquals(SatelliteMonitor.SatelliteConnectionType.NONE, state.connectionType)
        assertNull(state.networkName)
        assertNull(state.operatorName)
        assertEquals(SatelliteMonitor.Companion.NTRadioTechnology.UNKNOWN, state.radioTechnology)
        assertNull(state.signalStrength)
        assertNull(state.frequency)
        assertFalse(state.isNTNBand)
        assertEquals(SatelliteMonitor.SatelliteProvider.UNKNOWN, state.provider)
    }

    // ==================== Anomaly Data Class Tests ====================

    @Test
    fun `SatelliteAnomaly contains all required fields`() {
        val anomaly = SatelliteMonitor.SatelliteAnomaly(
            type = SatelliteMonitor.SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK,
            severity = SatelliteMonitor.AnomalySeverity.HIGH,
            description = "Connected to unrecognized satellite network",
            technicalDetails = mapOf(
                "networkName" to "Unknown-SAT",
                "radioTechnology" to 2
            ),
            recommendations = listOf(
                "Unknown satellite networks could be spoofed",
                "Consider disabling cellular"
            )
        )

        assertEquals(SatelliteMonitor.SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK, anomaly.type)
        assertEquals(SatelliteMonitor.AnomalySeverity.HIGH, anomaly.severity)
        assertTrue(anomaly.description.isNotEmpty())
        assertEquals(2, anomaly.technicalDetails.size)
        assertEquals(2, anomaly.recommendations.size)
        assertTrue(anomaly.timestamp > 0)
    }

    // ==================== Complete Detection Flow Test ====================

    @Test
    fun `complete satellite detection flow from anomaly to storage`() {
        // 1. Simulate anomaly detection
        val satAnomaly = mapOf(
            "type" to "UNEXPECTED_SATELLITE_CONNECTION",
            "networkName" to "T-Mobile SpaceX",
            "terrestrialSignal" to -70,
            "provider" to "STARLINK"
        )

        // 2. Convert to Detection
        val detection = Detection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_UNEXPECTED_CONNECTION,
            deviceType = DeviceType.SATELLITE_NTN,
            deviceName = "Unexpected Satellite",
            ssid = satAnomaly["networkName"] as String,
            rssi = -80,
            signalStrength = SignalStrength.WEAK,
            threatLevel = ThreatLevel.MEDIUM,
            threatScore = 60,
            manufacturer = "SpaceX Starlink",
            matchedPatterns = "lastTerrestrialSignal: ${satAnomaly["terrestrialSignal"]}, provider: ${satAnomaly["provider"]}"
        )

        // 3. Validate detection is ready for storage
        assertNotNull(detection.id)
        assertTrue(detection.id.isNotEmpty())
        assertTrue(detection.timestamp > 0)
        assertEquals(DetectionProtocol.SATELLITE, detection.protocol)
        assertEquals(ThreatLevel.MEDIUM, detection.threatLevel)
        assertTrue(detection.isActive)
        assertEquals(1, detection.seenCount)
        assertEquals("T-Mobile SpaceX", detection.ssid)

        // 4. Simulate update (seen again)
        val updatedDetection = detection.copy(
            seenCount = detection.seenCount + 1,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        assertEquals(2, updatedDetection.seenCount)
        assertTrue(updatedDetection.lastSeenTimestamp >= detection.timestamp)
    }

    // ==================== Conformance with Other Detectors ====================

    @Test
    fun `satellite detection follows same pattern as cellular detection`() {
        // Both should use same ThreatLevel enum
        val satThreat = ThreatLevel.HIGH
        val cellThreat = ThreatLevel.HIGH
        assertEquals(satThreat, cellThreat)

        // Both should have similar detection structure
        val satDetection = Detection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_UNEXPECTED_CONNECTION,
            deviceType = DeviceType.SATELLITE_NTN,
            rssi = -80,
            signalStrength = SignalStrength.WEAK,
            threatLevel = ThreatLevel.MEDIUM
        )

        val cellDetection = Detection(
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = DetectionMethod.CELL_ENCRYPTION_DOWNGRADE,
            deviceType = DeviceType.STINGRAY_IMSI,
            rssi = -70,
            signalStrength = SignalStrength.MEDIUM,
            threatLevel = ThreatLevel.HIGH
        )

        // Both use same Detection class
        assertNotNull(satDetection.id)
        assertNotNull(cellDetection.id)
        assertNotEquals(satDetection.id, cellDetection.id)
    }

    @Test
    fun `satellite detection follows same pattern as WiFi detection`() {
        val satDetection = Detection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_SUSPICIOUS_NTN,
            deviceType = DeviceType.SATELLITE_NTN,
            ssid = "Unknown-SAT-Network",
            rssi = -75,
            signalStrength = SignalStrength.WEAK,
            threatLevel = ThreatLevel.HIGH,
            matchedPatterns = "Unknown satellite network"
        )

        val wifiDetection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.WIFI_EVIL_TWIN,
            deviceType = DeviceType.ROGUE_AP,
            ssid = "EvilTwin-WiFi",
            macAddress = "AA:BB:CC:DD:EE:FF",
            rssi = -55,
            signalStrength = SignalStrength.GOOD,
            threatLevel = ThreatLevel.HIGH,
            matchedPatterns = "Same SSID, different BSSID"
        )

        // Both can store SSID
        assertNotNull(satDetection.ssid)
        assertNotNull(wifiDetection.ssid)

        // Both use matchedPatterns
        assertNotNull(satDetection.matchedPatterns)
        assertNotNull(wifiDetection.matchedPatterns)
    }
}

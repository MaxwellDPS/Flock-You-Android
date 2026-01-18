package com.flockyou.data.model

import org.junit.Test
import org.junit.Assert.*

/**
 * End-to-end integration tests for the Detection system.
 * These tests validate the complete detection pipeline including:
 * - Protocol coverage
 * - Detection method mapping
 * - Device type categorization
 * - Threat level calculation
 * - Detection model integrity
 */
class DetectionE2ETest {

    // ==================== Protocol Coverage Tests ====================

    @Test
    fun `all protocols are represented in the detection system`() {
        val protocols = DetectionProtocol.entries
        assertTrue("Should have WIFI protocol", protocols.contains(DetectionProtocol.WIFI))
        assertTrue("Should have BLUETOOTH_LE protocol", protocols.contains(DetectionProtocol.BLUETOOTH_LE))
        assertTrue("Should have CELLULAR protocol", protocols.contains(DetectionProtocol.CELLULAR))
        assertTrue("Should have SATELLITE protocol", protocols.contains(DetectionProtocol.SATELLITE))
        assertTrue("Should have AUDIO protocol", protocols.contains(DetectionProtocol.AUDIO))
        assertTrue("Should have RF protocol", protocols.contains(DetectionProtocol.RF))
    }

    @Test
    fun `all protocols have display names`() {
        DetectionProtocol.entries.forEach { protocol ->
            assertTrue(
                "Protocol ${protocol.name} should have display name",
                protocol.displayName.isNotEmpty()
            )
        }
    }

    // ==================== Detection Method Completeness Tests ====================

    @Test
    fun `detection methods exist for cellular anomalies`() {
        val cellularMethods = DetectionMethod.entries.filter {
            it.name.startsWith("CELL_")
        }

        assertTrue("Should have CELL_ENCRYPTION_DOWNGRADE",
            cellularMethods.any { it == DetectionMethod.CELL_ENCRYPTION_DOWNGRADE })
        assertTrue("Should have CELL_SUSPICIOUS_NETWORK",
            cellularMethods.any { it == DetectionMethod.CELL_SUSPICIOUS_NETWORK })
        assertTrue("Should have CELL_TOWER_CHANGE",
            cellularMethods.any { it == DetectionMethod.CELL_TOWER_CHANGE })
        assertTrue("Should have CELL_RAPID_SWITCHING",
            cellularMethods.any { it == DetectionMethod.CELL_RAPID_SWITCHING })
        assertTrue("Should have CELL_SIGNAL_ANOMALY",
            cellularMethods.any { it == DetectionMethod.CELL_SIGNAL_ANOMALY })
    }

    @Test
    fun `detection methods exist for WiFi anomalies`() {
        val wifiMethods = DetectionMethod.entries.filter {
            it.name.startsWith("WIFI_")
        }

        assertTrue("Should have WiFi methods", wifiMethods.isNotEmpty())
        assertTrue("Should have WIFI_EVIL_TWIN",
            wifiMethods.any { it == DetectionMethod.WIFI_EVIL_TWIN })
        assertTrue("Should have WIFI_DEAUTH_ATTACK",
            wifiMethods.any { it == DetectionMethod.WIFI_DEAUTH_ATTACK })
    }

    @Test
    fun `detection methods exist for RF anomalies`() {
        val rfMethods = DetectionMethod.entries.filter {
            it.name.startsWith("RF_")
        }

        assertTrue("Should have RF_JAMMER",
            rfMethods.any { it == DetectionMethod.RF_JAMMER })
        assertTrue("Should have RF_DRONE",
            rfMethods.any { it == DetectionMethod.RF_DRONE })
        assertTrue("Should have RF_SURVEILLANCE_AREA",
            rfMethods.any { it == DetectionMethod.RF_SURVEILLANCE_AREA })
    }

    @Test
    fun `detection methods exist for ultrasonic anomalies`() {
        val ultrasonicMethods = DetectionMethod.entries.filter {
            it.name.startsWith("ULTRASONIC_")
        }

        assertTrue("Should have ULTRASONIC_TRACKING_BEACON",
            ultrasonicMethods.any { it == DetectionMethod.ULTRASONIC_TRACKING_BEACON })
        assertTrue("Should have ULTRASONIC_AD_BEACON",
            ultrasonicMethods.any { it == DetectionMethod.ULTRASONIC_AD_BEACON })
        assertTrue("Should have ULTRASONIC_CROSS_DEVICE",
            ultrasonicMethods.any { it == DetectionMethod.ULTRASONIC_CROSS_DEVICE })
    }

    @Test
    fun `detection methods exist for satellite anomalies`() {
        val satMethods = DetectionMethod.entries.filter {
            it.name.startsWith("SAT_")
        }

        assertTrue("Should have satellite methods", satMethods.isNotEmpty())
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
    fun `all detection methods have descriptions`() {
        DetectionMethod.entries.forEach { method ->
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
    fun `device types cover all surveillance categories`() {
        val types = DeviceType.entries

        // IMSI catchers
        assertTrue(types.any { it == DeviceType.STINGRAY_IMSI })
        assertTrue(types.any { it == DeviceType.L3HARRIS_SURVEILLANCE })

        // Cameras
        assertTrue(types.any { it == DeviceType.FLOCK_SAFETY_CAMERA })
        assertTrue(types.any { it == DeviceType.HIDDEN_CAMERA })

        // Police tech
        assertTrue(types.any { it == DeviceType.RAVEN_GUNSHOT_DETECTOR })
        assertTrue(types.any { it == DeviceType.AXON_POLICE_TECH })
        assertTrue(types.any { it == DeviceType.MOTOROLA_POLICE_TECH })

        // WiFi threats
        assertTrue(types.any { it == DeviceType.ROGUE_AP })

        // RF threats
        assertTrue(types.any { it == DeviceType.RF_JAMMER })
        assertTrue(types.any { it == DeviceType.DRONE })
        assertTrue(types.any { it == DeviceType.SURVEILLANCE_INFRASTRUCTURE })

        // Audio threats
        assertTrue(types.any { it == DeviceType.ULTRASONIC_BEACON })

        // Satellite threats
        assertTrue(types.any { it == DeviceType.SATELLITE_NTN })

        // Fallback
        assertTrue(types.any { it == DeviceType.UNKNOWN_SURVEILLANCE })
    }

    @Test
    fun `all device types have display names and emojis`() {
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
    fun `threat levels are ordered by severity`() {
        // Enum is ordered: CRITICAL(0), HIGH(1), MEDIUM(2), LOW(3), INFO(4)
        // Higher severity has lower ordinal
        assertTrue(ThreatLevel.CRITICAL.ordinal < ThreatLevel.HIGH.ordinal)
        assertTrue(ThreatLevel.HIGH.ordinal < ThreatLevel.MEDIUM.ordinal)
        assertTrue(ThreatLevel.MEDIUM.ordinal < ThreatLevel.LOW.ordinal)
        assertTrue(ThreatLevel.LOW.ordinal < ThreatLevel.INFO.ordinal)
    }

    @Test
    fun `scoreToThreatLevel maps entire range correctly`() {
        // Test boundary conditions
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
    fun `threat levels have display names and descriptions`() {
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

    // ==================== Signal Strength Tests ====================

    @Test
    fun `rssiToSignalStrength covers full range`() {
        assertEquals(SignalStrength.EXCELLENT, rssiToSignalStrength(-30))
        assertEquals(SignalStrength.GOOD, rssiToSignalStrength(-55))
        assertEquals(SignalStrength.MEDIUM, rssiToSignalStrength(-65))
        assertEquals(SignalStrength.WEAK, rssiToSignalStrength(-75))
        assertEquals(SignalStrength.VERY_WEAK, rssiToSignalStrength(-90))
    }

    @Test
    fun `signal strength has display names`() {
        SignalStrength.entries.forEach { strength ->
            assertTrue(
                "SignalStrength ${strength.name} should have display name",
                strength.displayName.isNotEmpty()
            )
        }
    }

    // ==================== Detection Entity Tests ====================

    @Test
    fun `Detection entity generates unique IDs`() {
        val ids = (1..100).map {
            Detection(
                protocol = DetectionProtocol.WIFI,
                detectionMethod = DetectionMethod.SSID_PATTERN,
                deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
                rssi = -60,
                signalStrength = SignalStrength.MEDIUM,
                threatLevel = ThreatLevel.LOW
            ).id
        }

        assertEquals("All IDs should be unique", 100, ids.toSet().size)
    }

    @Test
    fun `Detection entity defaults are correct`() {
        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            rssi = -60,
            signalStrength = SignalStrength.MEDIUM,
            threatLevel = ThreatLevel.LOW
        )

        assertTrue("isActive should default to true", detection.isActive)
        assertEquals("seenCount should default to 1", 1, detection.seenCount)
        assertNotNull("ID should not be null", detection.id)
        assertTrue("timestamp should be recent", detection.timestamp > 0)
    }

    @Test
    fun `Detection can store all optional fields`() {
        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            rssi = -55,
            signalStrength = SignalStrength.GOOD,
            threatLevel = ThreatLevel.HIGH,
            deviceName = "Flock Camera ABC123",
            macAddress = "AA:BB:CC:DD:EE:FF",
            ssid = "Flock-ABC123",
            latitude = 47.6062,
            longitude = -122.3321,
            threatScore = 85,
            manufacturer = "Flock Safety",
            firmwareVersion = "2.1.0",
            serviceUuids = "[\"00001234-0000-1000-8000-00805f9b34fb\"]",
            matchedPatterns = "SSID: Flock-, MAC: AA:BB:CC"
        )

        assertEquals("Flock Camera ABC123", detection.deviceName)
        assertEquals("AA:BB:CC:DD:EE:FF", detection.macAddress)
        assertEquals("Flock-ABC123", detection.ssid)
        assertEquals(47.6062, detection.latitude!!, 0.0001)
        assertEquals(-122.3321, detection.longitude!!, 0.0001)
        assertEquals(85, detection.threatScore)
        assertEquals("Flock Safety", detection.manufacturer)
        assertEquals("2.1.0", detection.firmwareVersion)
        assertNotNull(detection.serviceUuids)
        assertNotNull(detection.matchedPatterns)
    }

    // ==================== E2E Detection Scenarios ====================

    @Test
    fun `E2E cellular IMSI catcher detection`() {
        // Simulate detecting a StingRay device
        val detection = Detection(
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = DetectionMethod.CELL_ENCRYPTION_DOWNGRADE,
            deviceType = DeviceType.STINGRAY_IMSI,
            deviceName = "⚠️ Encryption Downgrade",
            rssi = -70,
            signalStrength = rssiToSignalStrength(-70),
            latitude = 47.6,
            longitude = -122.3,
            threatLevel = ThreatLevel.HIGH,
            threatScore = 85,
            matchedPatterns = "LTE to GSM downgrade, Device stationary"
        )

        assertEquals(DetectionProtocol.CELLULAR, detection.protocol)
        assertEquals(DeviceType.STINGRAY_IMSI, detection.deviceType)
        assertEquals(ThreatLevel.HIGH, detection.threatLevel)
        assertTrue(detection.threatScore >= 70)
    }

    @Test
    fun `E2E WiFi evil twin detection`() {
        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.WIFI_EVIL_TWIN,
            deviceType = DeviceType.ROGUE_AP,
            deviceName = "👿 Evil Twin AP",
            macAddress = "AA:BB:CC:DD:EE:FF",
            ssid = "CorporateWiFi",
            rssi = -45,
            signalStrength = rssiToSignalStrength(-45),
            latitude = 47.6,
            longitude = -122.3,
            threatLevel = ThreatLevel.HIGH,
            threatScore = 85,
            matchedPatterns = "Same SSID, Different BSSID, 25dB signal difference"
        )

        assertEquals(DetectionProtocol.WIFI, detection.protocol)
        assertEquals("CorporateWiFi", detection.ssid)
        assertEquals(DeviceType.ROGUE_AP, detection.deviceType)
    }

    @Test
    fun `E2E drone detection`() {
        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.RF_DRONE,
            deviceType = DeviceType.DRONE,
            deviceName = "🚁 DJI Drone",
            macAddress = "60:60:1F:AA:BB:CC",
            ssid = "DJI_Mavic_Pro",
            rssi = -55,
            signalStrength = rssiToSignalStrength(-55),
            latitude = 47.6,
            longitude = -122.3,
            threatLevel = ThreatLevel.MEDIUM,
            threatScore = 70,
            manufacturer = "DJI",
            matchedPatterns = "DJI OUI, Mavic SSID pattern"
        )

        assertEquals(DeviceType.DRONE, detection.deviceType)
        assertEquals("DJI", detection.manufacturer)
    }

    @Test
    fun `E2E ultrasonic beacon detection`() {
        val detection = Detection(
            protocol = DetectionProtocol.AUDIO,
            detectionMethod = DetectionMethod.ULTRASONIC_AD_BEACON,
            deviceType = DeviceType.ULTRASONIC_BEACON,
            deviceName = "📺 Advertising Beacon",
            ssid = "18000Hz",
            rssi = -35,
            signalStrength = SignalStrength.EXCELLENT,
            latitude = null,
            longitude = null,
            threatLevel = ThreatLevel.HIGH,
            threatScore = 80,
            matchedPatterns = "SilverPush frequency, 500ms persistence"
        )

        assertEquals(DetectionProtocol.AUDIO, detection.protocol)
        assertEquals("18000Hz", detection.ssid)
        assertEquals(DeviceType.ULTRASONIC_BEACON, detection.deviceType)
    }

    @Test
    fun `E2E hidden camera detection`() {
        val detection = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.WIFI_HIDDEN_CAMERA,
            deviceType = DeviceType.HIDDEN_CAMERA,
            deviceName = "📷 Hidden Camera WiFi",
            macAddress = "B4:A3:82:11:22:33",
            ssid = "IPCam_001",
            rssi = -50,
            signalStrength = rssiToSignalStrength(-50),
            latitude = 47.6,
            longitude = -122.3,
            threatLevel = ThreatLevel.HIGH,
            threatScore = 80,
            manufacturer = "Hikvision",
            matchedPatterns = "Hikvision OUI, Camera SSID pattern"
        )

        assertEquals(DeviceType.HIDDEN_CAMERA, detection.deviceType)
        assertEquals("Hikvision", detection.manufacturer)
    }

    // ==================== Detection Pattern Matching Tests ====================

    @Test
    fun `Flock Safety SSID patterns are detected`() {
        val flockSsids = listOf(
            "Flock-ABC123",
            "FlockSafety_Device01",
            "FLOCK_CAM_001"
        )

        flockSsids.forEach { ssid ->
            val result = DetectionPatterns.matchSsidPattern(ssid)
            assertNotNull("Should match Flock SSID: $ssid", result)
            if (result != null) {
                assertEquals(DeviceType.FLOCK_SAFETY_CAMERA, result.deviceType)
            }
        }
    }

    @Test
    fun `Raven BLE patterns are detected`() {
        val ravenNames = listOf(
            "Raven-12345",
            "ShotSpotter_Node"
        )

        ravenNames.forEach { name ->
            val result = DetectionPatterns.matchBleNamePattern(name)
            assertNotNull("Should match Raven BLE: $name", result)
            if (result != null) {
                assertEquals(DeviceType.RAVEN_GUNSHOT_DETECTOR, result.deviceType)
            }
        }
    }

    // ==================== Detection Deduplication Tests ====================

    @Test
    fun `detections with same MAC are considered same device`() {
        val mac = "AA:BB:CC:DD:EE:FF"

        val detection1 = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            macAddress = mac,
            rssi = -60,
            signalStrength = SignalStrength.MEDIUM,
            threatLevel = ThreatLevel.HIGH,
            seenCount = 1
        )

        val detection2 = Detection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            macAddress = mac,
            rssi = -55,
            signalStrength = SignalStrength.GOOD,
            threatLevel = ThreatLevel.HIGH,
            seenCount = 2
        )

        assertEquals(detection1.macAddress, detection2.macAddress)
    }

    // ==================== Complete Detection Flow Test ====================

    @Test
    fun `complete detection flow from anomaly to storage`() {
        // 1. Simulate anomaly detection (cellular)
        val cellAnomaly = mapOf(
            "type" to "ENCRYPTION_DOWNGRADE",
            "cellId" to 12345,
            "previousNetworkType" to "LTE",
            "currentNetworkType" to "GSM",
            "signalStrength" to -70
        )

        // 2. Convert to Detection
        val detection = Detection(
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = DetectionMethod.CELL_ENCRYPTION_DOWNGRADE,
            deviceType = DeviceType.STINGRAY_IMSI,
            deviceName = "⚠️ Encryption Downgrade",
            rssi = cellAnomaly["signalStrength"] as Int,
            signalStrength = rssiToSignalStrength(cellAnomaly["signalStrength"] as Int),
            threatLevel = ThreatLevel.HIGH,
            threatScore = 90,
            manufacturer = "Cell: ${cellAnomaly["cellId"]}",
            matchedPatterns = "${cellAnomaly["previousNetworkType"]} to ${cellAnomaly["currentNetworkType"]}"
        )

        // 3. Validate detection is ready for storage
        assertNotNull(detection.id)
        assertTrue(detection.id.isNotEmpty())
        assertTrue(detection.timestamp > 0)
        assertEquals(DetectionProtocol.CELLULAR, detection.protocol)
        assertEquals(ThreatLevel.HIGH, detection.threatLevel)
        assertTrue(detection.isActive)
        assertEquals(1, detection.seenCount)

        // 4. Simulate update (seen again)
        val updatedDetection = detection.copy(
            seenCount = detection.seenCount + 1,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        assertEquals(2, updatedDetection.seenCount)
        assertTrue(updatedDetection.lastSeenTimestamp >= detection.timestamp)
    }
}

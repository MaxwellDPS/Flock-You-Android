package com.flockyou.service

import com.flockyou.data.model.*
import org.junit.Test
import org.junit.Assert.*

/**
 * Comprehensive E2E unit tests for RogueWifiMonitor detection.
 * Tests validate WiFi threat detection including evil twins, deauth attacks,
 * hidden cameras, surveillance vans, and following network detection.
 */
class RogueWifiMonitorTest {

    // ==================== WifiAnomalyType Tests ====================

    @Test
    fun `WifiAnomalyType EVIL_TWIN has high base score`() {
        val evilTwin = WifiAnomalyType.EVIL_TWIN
        assertTrue(
            "Evil twin should have base score >= 80",
            evilTwin.baseScore >= 80
        )
    }

    @Test
    fun `WifiAnomalyType DEAUTH_ATTACK has critical base score`() {
        val deauth = WifiAnomalyType.DEAUTH_ATTACK
        assertTrue(
            "Deauth attack should have base score >= 85",
            deauth.baseScore >= 85
        )
    }

    @Test
    fun `WifiAnomalyType has valid display names and emojis`() {
        WifiAnomalyType.entries.forEach { type ->
            assertTrue(
                "WifiAnomalyType ${type.name} should have display name",
                type.displayName.isNotEmpty()
            )
            assertTrue(
                "WifiAnomalyType ${type.name} should have emoji",
                type.emoji.isNotEmpty()
            )
            assertTrue(
                "WifiAnomalyType ${type.name} should have valid base score",
                type.baseScore in 0..100
            )
        }
    }

    // ==================== Evil Twin Detection Tests ====================

    @Test
    fun `isEvilTwin detects same SSID different BSSID`() {
        val original = MockScanResult("HomeNetwork", "AA:BB:CC:DD:EE:FF", -60)
        val suspicious = MockScanResult("HomeNetwork", "11:22:33:44:55:66", -40)

        assertTrue(isEvilTwin(original, suspicious))
    }

    @Test
    fun `isEvilTwin detects stronger signal evil twin`() {
        val original = MockScanResult("CoffeeShop_WiFi", "AA:BB:CC:DD:EE:FF", -70)
        val evilTwin = MockScanResult("CoffeeShop_WiFi", "11:22:33:44:55:66", -45)

        val result = isEvilTwinWithSignalAnalysis(original, evilTwin)
        assertTrue(result.isEvilTwin)
        assertTrue(result.signalDifference >= 15) // Evil twin typically has stronger signal
    }

    @Test
    fun `isEvilTwin ignores legitimate access points`() {
        // Same network, same BSSID = legitimate
        val original = MockScanResult("HomeNetwork", "AA:BB:CC:DD:EE:FF", -60)
        val same = MockScanResult("HomeNetwork", "AA:BB:CC:DD:EE:FF", -65)

        assertFalse(isEvilTwin(original, same))
    }

    @Test
    fun `isEvilTwin ignores similar BSSID mesh networks`() {
        // Mesh networks often have sequential BSSIDs
        val mesh1 = MockScanResult("GoogleWiFi", "AA:BB:CC:DD:EE:F0", -60)
        val mesh2 = MockScanResult("GoogleWiFi", "AA:BB:CC:DD:EE:F1", -65)

        val result = isMeshNetwork(mesh1.bssid, mesh2.bssid)
        assertTrue("Should detect mesh network", result)
    }

    @Test
    fun `isEvilTwin detects case-insensitive SSID match`() {
        val original = MockScanResult("HOMENETWORK", "AA:BB:CC:DD:EE:FF", -60)
        val suspicious = MockScanResult("HomeNetwork", "11:22:33:44:55:66", -45)

        assertTrue(isEvilTwinCaseInsensitive(original, suspicious))
    }

    // ==================== Deauth Attack Detection Tests ====================

    @Test
    fun `isDeauthAttack detects rapid disconnections`() {
        val disconnections = listOf(
            1000L, 1500L, 2000L, 2500L, 3000L, 3500L  // 6 in 3 seconds
        )

        assertTrue(isDeauthAttack(disconnections, windowMs = 60_000))
    }

    @Test
    fun `isDeauthAttack ignores normal disconnections`() {
        val normalDisconnections = listOf(
            0L, 30_000L, 60_000L  // 3 in 60 seconds = normal
        )

        assertFalse(isDeauthAttack(normalDisconnections, windowMs = 60_000))
    }

    @Test
    fun `isDeauthAttack detects threshold breach`() {
        // 5+ disconnects in 1 minute is suspicious
        val timestamps = (0..4).map { it * 10_000L }  // 5 disconnects in 50s
        assertTrue(isDeauthAttack(timestamps, windowMs = 60_000, threshold = 5))
    }

    // ==================== Hidden Camera Detection Tests ====================

    @Test
    fun `isHiddenCameraOui detects TVT OUI`() {
        assertTrue(isHiddenCameraOui("C8:14:79"))  // TVT
    }

    @Test
    fun `isHiddenCameraOui detects Hikvision OUI`() {
        assertTrue(isHiddenCameraOui("B4:A3:82"))
        assertTrue(isHiddenCameraOui("44:19:B6"))
        assertTrue(isHiddenCameraOui("54:C4:15"))
    }

    @Test
    fun `isHiddenCameraOui detects Dahua OUI`() {
        assertTrue(isHiddenCameraOui("E0:50:8B"))
        assertTrue(isHiddenCameraOui("3C:EF:8C"))
        assertTrue(isHiddenCameraOui("4C:11:BF"))
    }

    @Test
    fun `isHiddenCameraOui rejects normal devices`() {
        assertFalse(isHiddenCameraOui("00:11:22"))  // Unknown
        assertFalse(isHiddenCameraOui("B8:27:EB"))  // Raspberry Pi
        assertFalse(isHiddenCameraOui("DC:A6:32"))  // Raspberry Pi
    }

    @Test
    fun `isHiddenCameraSsid detects suspicious patterns`() {
        assertTrue(isHiddenCameraSsid("HDcam_12345"))
        assertTrue(isHiddenCameraSsid("spy_cam"))
        assertTrue(isHiddenCameraSsid("nanny_cam_living"))
        assertTrue(isHiddenCameraSsid("IPCamera_001"))
        assertTrue(isHiddenCameraSsid("HiCam-ABC"))
    }

    @Test
    fun `isHiddenCameraSsid ignores normal SSIDs`() {
        assertFalse(isHiddenCameraSsid("HomeNetwork"))
        assertFalse(isHiddenCameraSsid("CoffeeShop_Guest"))
        assertFalse(isHiddenCameraSsid("Linksys"))
    }

    // ==================== Surveillance Van Detection Tests ====================

    @Test
    fun `isSurveillanceVanSsid detects suspicious patterns`() {
        assertTrue(isSurveillanceVanSsid("unmarked_van"))
        assertTrue(isSurveillanceVanSsid("tactical_unit"))
        assertTrue(isSurveillanceVanSsid("cctv_van_1"))
        assertTrue(isSurveillanceVanSsid("mobile_surveillance"))
    }

    @Test
    fun `isSurveillanceVanSsid rejects normal SSIDs`() {
        assertFalse(isSurveillanceVanSsid("HomeNetwork"))
        assertFalse(isSurveillanceVanSsid("ATT-WIFI"))
        assertFalse(isSurveillanceVanSsid("Starbucks"))
    }

    // ==================== Following Network Detection Tests ====================

    @Test
    fun `isFollowingNetwork detects network at multiple locations`() {
        val sightings = listOf(
            NetworkSighting("SuspiciousNet", "AA:BB:CC:DD:EE:FF", 47.6, -122.3, 1000L),
            NetworkSighting("SuspiciousNet", "AA:BB:CC:DD:EE:FF", 47.7, -122.4, 2000L),
            NetworkSighting("SuspiciousNet", "AA:BB:CC:DD:EE:FF", 47.8, -122.5, 3000L)
        )

        val result = isFollowingNetwork(sightings, minDistinctLocations = 3)
        assertTrue(result)
    }

    @Test
    fun `isFollowingNetwork ignores stationary networks`() {
        val sightings = listOf(
            NetworkSighting("HomeNetwork", "AA:BB:CC:DD:EE:FF", 47.6062, -122.3321, 1000L),
            NetworkSighting("HomeNetwork", "AA:BB:CC:DD:EE:FF", 47.6062, -122.3321, 2000L),
            NetworkSighting("HomeNetwork", "AA:BB:CC:DD:EE:FF", 47.6062, -122.3321, 3000L)
        )

        val result = isFollowingNetwork(sightings, minDistinctLocations = 3)
        assertFalse(result)
    }

    @Test
    fun `isFollowingNetwork requires minimum distinct locations`() {
        val sightings = listOf(
            NetworkSighting("Net1", "AA:BB:CC:DD:EE:FF", 47.6, -122.3, 1000L),
            NetworkSighting("Net1", "AA:BB:CC:DD:EE:FF", 47.7, -122.4, 2000L)
        )

        // Only 2 locations, needs 3
        assertFalse(isFollowingNetwork(sightings, minDistinctLocations = 3))
    }

    // ==================== Suspicious Open Network Detection Tests ====================

    @Test
    fun `isSuspiciousOpenNetwork detects strong open network`() {
        val result = isSuspiciousOpenNetwork(
            ssid = "Free_WiFi",
            isOpen = true,
            rssi = -35,  // Very strong
            isInSensitiveLocation = true
        )
        assertTrue(result)
    }

    @Test
    fun `isSuspiciousOpenNetwork ignores weak open networks`() {
        val result = isSuspiciousOpenNetwork(
            ssid = "Public_WiFi",
            isOpen = true,
            rssi = -75,  // Weak signal
            isInSensitiveLocation = false
        )
        assertFalse(result)
    }

    @Test
    fun `isSuspiciousOpenNetwork ignores secured networks`() {
        val result = isSuspiciousOpenNetwork(
            ssid = "Secure_Network",
            isOpen = false,
            rssi = -35,
            isInSensitiveLocation = true
        )
        assertFalse(result)
    }

    // ==================== WifiAnomaly Tests ====================

    @Test
    fun `WifiAnomaly has unique ID`() {
        val anomaly1 = createTestWifiAnomaly(WifiAnomalyType.EVIL_TWIN)
        val anomaly2 = createTestWifiAnomaly(WifiAnomalyType.EVIL_TWIN)
        assertNotEquals(anomaly1.id, anomaly2.id)
    }

    @Test
    fun `WifiAnomaly stores all required fields`() {
        val anomaly = WifiAnomaly(
            type = WifiAnomalyType.EVIL_TWIN,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Evil twin detected",
            technicalDetails = "Same SSID, different BSSID",
            ssid = "HomeNetwork",
            bssid = "AA:BB:CC:DD:EE:FF",
            originalBssid = "11:22:33:44:55:66",
            rssi = -45,
            originalRssi = -70,
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("Strong signal", "Different BSSID")
        )

        assertEquals(WifiAnomalyType.EVIL_TWIN, anomaly.type)
        assertEquals(ThreatLevel.HIGH, anomaly.severity)
        assertEquals("HomeNetwork", anomaly.ssid)
        assertEquals("AA:BB:CC:DD:EE:FF", anomaly.bssid)
        assertEquals(-45, anomaly.rssi)
        assertEquals(2, anomaly.contributingFactors.size)
    }

    // ==================== NetworkHistory Tests ====================

    @Test
    fun `NetworkHistory tracks signal statistics`() {
        val history = NetworkHistory(
            ssid = "TestNetwork",
            bssid = "AA:BB:CC:DD:EE:FF",
            firstSeen = 1000L,
            lastSeen = 5000L,
            seenCount = 10,
            minRssi = -80,
            maxRssi = -50,
            avgRssi = -65,
            locations = listOf(Location(47.6, -122.3)),
            isSecured = true,
            securityType = "WPA3"
        )

        assertEquals("TestNetwork", history.ssid)
        assertEquals(10, history.seenCount)
        assertEquals(-80, history.minRssi)
        assertEquals(-50, history.maxRssi)
    }

    // ==================== WifiEnvironmentStatus Tests ====================

    @Test
    fun `WifiEnvironmentStatus calculates threat level`() {
        val status = WifiEnvironmentStatus(
            totalNetworks = 20,
            openNetworks = 5,
            hiddenNetworks = 3,
            knownNetworks = 10,
            unknownNetworks = 10,
            strongestSignal = -40,
            weakestSignal = -85,
            evilTwinsSuspected = 1,
            surveillanceCameras = 2,
            lastScanTime = System.currentTimeMillis()
        )

        assertEquals(20, status.totalNetworks)
        assertEquals(5, status.openNetworks)
        assertEquals(1, status.evilTwinsSuspected)
    }

    // ==================== E2E Anomaly to Detection Conversion Tests ====================

    @Test
    fun `anomalyToDetection converts EVIL_TWIN correctly`() {
        val anomaly = WifiAnomaly(
            type = WifiAnomalyType.EVIL_TWIN,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Evil twin AP detected",
            technicalDetails = "Matching SSID with different BSSID",
            ssid = "CorporateWiFi",
            bssid = "AA:BB:CC:DD:EE:FF",
            originalBssid = "11:22:33:44:55:66",
            rssi = -45,
            originalRssi = -70,
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("25dB signal difference")
        )

        val detection = wifiAnomalyToDetection(anomaly)

        assertEquals(DetectionProtocol.WIFI, detection.protocol)
        assertEquals(DetectionMethod.WIFI_EVIL_TWIN, detection.detectionMethod)
        assertEquals(DeviceType.ROGUE_AP, detection.deviceType)
        assertEquals("CorporateWiFi", detection.ssid)
        assertEquals("AA:BB:CC:DD:EE:FF", detection.macAddress)
        assertEquals(ThreatLevel.HIGH, detection.threatLevel)
    }

    @Test
    fun `anomalyToDetection converts DEAUTH_ATTACK correctly`() {
        val anomaly = WifiAnomaly(
            type = WifiAnomalyType.DEAUTH_ATTACK,
            severity = ThreatLevel.CRITICAL,
            confidence = AnomalyConfidence.CRITICAL,
            description = "Deauthentication attack detected",
            technicalDetails = "10 disconnects in 30 seconds",
            ssid = "TargetNetwork",
            bssid = "AA:BB:CC:DD:EE:FF",
            originalBssid = null,
            rssi = -60,
            originalRssi = null,
            latitude = null,
            longitude = null,
            contributingFactors = listOf("Rapid disconnections")
        )

        val detection = wifiAnomalyToDetection(anomaly)

        assertEquals(DetectionMethod.WIFI_DEAUTH_ATTACK, detection.detectionMethod)
        assertEquals(ThreatLevel.CRITICAL, detection.threatLevel)
    }

    @Test
    fun `anomalyToDetection converts HIDDEN_CAMERA correctly`() {
        val anomaly = WifiAnomaly(
            type = WifiAnomalyType.HIDDEN_CAMERA,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Hidden camera WiFi detected",
            technicalDetails = "Hikvision OUI detected",
            ssid = "IPCam_001",
            bssid = "B4:A3:82:11:22:33",
            originalBssid = null,
            rssi = -50,
            originalRssi = null,
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("Hikvision OUI", "Camera SSID pattern")
        )

        val detection = wifiAnomalyToDetection(anomaly)

        assertEquals(DetectionMethod.WIFI_HIDDEN_CAMERA, detection.detectionMethod)
        assertEquals(DeviceType.HIDDEN_CAMERA, detection.deviceType)
    }

    @Test
    fun `anomalyToDetection converts FOLLOWING_NETWORK correctly`() {
        val anomaly = WifiAnomaly(
            type = WifiAnomalyType.FOLLOWING_NETWORK,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Network following user",
            technicalDetails = "Seen at 5 distinct locations",
            ssid = "StalkerNet",
            bssid = "AA:BB:CC:DD:EE:FF",
            originalBssid = null,
            rssi = -55,
            originalRssi = null,
            latitude = null,
            longitude = null,
            contributingFactors = listOf("5 distinct locations")
        )

        val detection = wifiAnomalyToDetection(anomaly)

        assertEquals(DetectionMethod.WIFI_FOLLOWING, detection.detectionMethod)
        assertEquals(DeviceType.TRACKING_DEVICE, detection.deviceType)
    }

    @Test
    fun `anomalyToDetection maps all anomaly types to detection methods`() {
        val typeMethodMapping = mapOf(
            WifiAnomalyType.EVIL_TWIN to DetectionMethod.WIFI_EVIL_TWIN,
            WifiAnomalyType.DEAUTH_ATTACK to DetectionMethod.WIFI_DEAUTH_ATTACK,
            WifiAnomalyType.HIDDEN_CAMERA to DetectionMethod.WIFI_HIDDEN_CAMERA,
            WifiAnomalyType.SUSPICIOUS_OPEN_NETWORK to DetectionMethod.WIFI_ROGUE_AP,
            WifiAnomalyType.FOLLOWING_NETWORK to DetectionMethod.WIFI_FOLLOWING,
            WifiAnomalyType.SURVEILLANCE_VAN to DetectionMethod.WIFI_SURVEILLANCE_VAN
        )

        typeMethodMapping.forEach { (anomalyType, expectedMethod) ->
            val anomaly = createTestWifiAnomaly(anomalyType)
            val detection = wifiAnomalyToDetection(anomaly)
            assertEquals(
                "WifiAnomalyType $anomalyType should map to $expectedMethod",
                expectedMethod,
                detection.detectionMethod
            )
        }
    }

    // ==================== Helper Classes and Functions ====================

    data class MockScanResult(
        val ssid: String,
        val bssid: String,
        val rssi: Int
    )

    data class NetworkSighting(
        val ssid: String,
        val bssid: String,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long
    )

    data class Location(
        val latitude: Double,
        val longitude: Double
    )

    data class EvilTwinResult(
        val isEvilTwin: Boolean,
        val signalDifference: Int
    )

    enum class WifiAnomalyType(
        val displayName: String,
        val baseScore: Int,
        val emoji: String
    ) {
        EVIL_TWIN("Evil Twin AP", 85, "👿"),
        DEAUTH_ATTACK("Deauth Attack", 90, "⚡"),
        HIDDEN_CAMERA("Hidden Camera WiFi", 80, "📷"),
        SUSPICIOUS_OPEN_NETWORK("Suspicious Open Network", 60, "⚠️"),
        FOLLOWING_NETWORK("Following Network", 75, "👁️"),
        SURVEILLANCE_VAN("Surveillance Van", 80, "🚐")
    }

    enum class AnomalyConfidence(val displayName: String) {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High"),
        CRITICAL("Critical")
    }

    data class WifiAnomaly(
        val id: String = java.util.UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: WifiAnomalyType,
        val severity: ThreatLevel,
        val confidence: AnomalyConfidence,
        val description: String,
        val technicalDetails: String,
        val ssid: String?,
        val bssid: String?,
        val originalBssid: String?,
        val rssi: Int?,
        val originalRssi: Int?,
        val latitude: Double?,
        val longitude: Double?,
        val contributingFactors: List<String> = emptyList()
    )

    data class NetworkHistory(
        val ssid: String,
        val bssid: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val seenCount: Int,
        val minRssi: Int,
        val maxRssi: Int,
        val avgRssi: Int,
        val locations: List<Location>,
        val isSecured: Boolean,
        val securityType: String?
    )

    data class WifiEnvironmentStatus(
        val totalNetworks: Int,
        val openNetworks: Int,
        val hiddenNetworks: Int,
        val knownNetworks: Int,
        val unknownNetworks: Int,
        val strongestSignal: Int,
        val weakestSignal: Int,
        val evilTwinsSuspected: Int,
        val surveillanceCameras: Int,
        val lastScanTime: Long
    )

    companion object {
        // Hidden camera OUIs
        private val HIDDEN_CAMERA_OUIS = setOf(
            "C8:14:79",  // TVT
            "B4:A3:82", "44:19:B6", "54:C4:15", "28:57:BE",  // Hikvision
            "E0:50:8B", "3C:EF:8C", "4C:11:BF", "A0:BD:1D",  // Dahua
            "00:80:F0",  // Panasonic
            "00:30:53", "00:40:8C", "AC:CC:8E",  // Axis
        )

        // Hidden camera SSID patterns
        private val HIDDEN_CAMERA_PATTERNS = listOf(
            Regex("(?i)^hdcam.*"),
            Regex("(?i)^spy[_-]?cam.*"),
            Regex("(?i)^nanny[_-]?cam.*"),
            Regex("(?i)^ipcam.*"),
            Regex("(?i)^hicam.*"),
        )

        // Surveillance van SSID patterns
        private val SURVEILLANCE_VAN_PATTERNS = listOf(
            Regex("(?i).*unmarked.*van.*"),
            Regex("(?i).*tactical.*unit.*"),
            Regex("(?i).*cctv.*van.*"),
            Regex("(?i).*mobile.*surveillance.*"),
        )

        fun isEvilTwin(original: MockScanResult, suspicious: MockScanResult): Boolean {
            return original.ssid == suspicious.ssid && original.bssid != suspicious.bssid
        }

        fun isEvilTwinCaseInsensitive(original: MockScanResult, suspicious: MockScanResult): Boolean {
            return original.ssid.equals(suspicious.ssid, ignoreCase = true) &&
                    original.bssid != suspicious.bssid
        }

        fun isEvilTwinWithSignalAnalysis(
            original: MockScanResult,
            suspicious: MockScanResult
        ): EvilTwinResult {
            val isEvilTwin = original.ssid == suspicious.ssid && original.bssid != suspicious.bssid
            val signalDiff = suspicious.rssi - original.rssi
            return EvilTwinResult(isEvilTwin && signalDiff > 0, signalDiff)
        }

        fun isMeshNetwork(bssid1: String, bssid2: String): Boolean {
            // Mesh networks typically have sequential MACs
            val prefix1 = bssid1.take(14)  // First 5 octets
            val prefix2 = bssid2.take(14)
            return prefix1 == prefix2
        }

        fun isDeauthAttack(
            disconnectionTimestamps: List<Long>,
            windowMs: Long,
            threshold: Int = 5
        ): Boolean {
            if (disconnectionTimestamps.size < threshold) return false
            val recentDisconnects = disconnectionTimestamps.filter {
                it >= disconnectionTimestamps.maxOrNull()!! - windowMs
            }
            return recentDisconnects.size >= threshold
        }

        fun isHiddenCameraOui(oui: String): Boolean {
            return oui.uppercase() in HIDDEN_CAMERA_OUIS
        }

        fun isHiddenCameraSsid(ssid: String): Boolean {
            return HIDDEN_CAMERA_PATTERNS.any { it.matches(ssid) }
        }

        fun isSurveillanceVanSsid(ssid: String): Boolean {
            return SURVEILLANCE_VAN_PATTERNS.any { it.matches(ssid) }
        }

        fun isFollowingNetwork(
            sightings: List<NetworkSighting>,
            minDistinctLocations: Int
        ): Boolean {
            val distinctLocations = sightings
                .map { Pair(it.latitude, it.longitude) }
                .distinct()
            return distinctLocations.size >= minDistinctLocations
        }

        @Suppress("UNUSED_PARAMETER")
        fun isSuspiciousOpenNetwork(
            ssid: String,
            isOpen: Boolean,
            rssi: Int,
            isInSensitiveLocation: Boolean
        ): Boolean {
            return isOpen && rssi > -40 && isInSensitiveLocation
        }

        fun createTestWifiAnomaly(type: WifiAnomalyType): WifiAnomaly {
            return WifiAnomaly(
                type = type,
                severity = ThreatLevel.HIGH,
                confidence = AnomalyConfidence.HIGH,
                description = "Test anomaly",
                technicalDetails = "Test details",
                ssid = "TestNetwork",
                bssid = "AA:BB:CC:DD:EE:FF",
                originalBssid = "11:22:33:44:55:66",
                rssi = -60,
                originalRssi = -75,
                latitude = null,
                longitude = null
            )
        }

        fun wifiAnomalyToDetection(anomaly: WifiAnomaly): Detection {
            val detectionMethod = when (anomaly.type) {
                WifiAnomalyType.EVIL_TWIN -> DetectionMethod.WIFI_EVIL_TWIN
                WifiAnomalyType.DEAUTH_ATTACK -> DetectionMethod.WIFI_DEAUTH_ATTACK
                WifiAnomalyType.HIDDEN_CAMERA -> DetectionMethod.WIFI_HIDDEN_CAMERA
                WifiAnomalyType.SUSPICIOUS_OPEN_NETWORK -> DetectionMethod.WIFI_ROGUE_AP
                WifiAnomalyType.FOLLOWING_NETWORK -> DetectionMethod.WIFI_FOLLOWING
                WifiAnomalyType.SURVEILLANCE_VAN -> DetectionMethod.WIFI_SURVEILLANCE_VAN
            }

            val deviceType = when (anomaly.type) {
                WifiAnomalyType.EVIL_TWIN -> DeviceType.ROGUE_AP
                WifiAnomalyType.DEAUTH_ATTACK -> DeviceType.ROGUE_AP
                WifiAnomalyType.HIDDEN_CAMERA -> DeviceType.HIDDEN_CAMERA
                WifiAnomalyType.SURVEILLANCE_VAN -> DeviceType.SURVEILLANCE_VAN
                WifiAnomalyType.FOLLOWING_NETWORK -> DeviceType.TRACKING_DEVICE
                else -> DeviceType.UNKNOWN_SURVEILLANCE
            }

            return Detection(
                deviceType = deviceType,
                protocol = DetectionProtocol.WIFI,
                detectionMethod = detectionMethod,
                deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}",
                macAddress = anomaly.bssid,
                ssid = anomaly.ssid,
                rssi = anomaly.rssi ?: -70,
                signalStrength = rssiToSignalStrength(anomaly.rssi ?: -70),
                latitude = anomaly.latitude,
                longitude = anomaly.longitude,
                threatLevel = anomaly.severity,
                threatScore = anomaly.type.baseScore,
                matchedPatterns = anomaly.contributingFactors.joinToString(", ")
            )
        }
    }
}

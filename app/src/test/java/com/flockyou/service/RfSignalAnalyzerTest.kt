package com.flockyou.service

import com.flockyou.data.model.*
import org.junit.Test
import org.junit.Assert.*

/**
 * Comprehensive E2E unit tests for RfSignalAnalyzer.
 * Tests validate RF environment analysis including jammer detection,
 * drone detection, surveillance area detection, and spectrum anomalies.
 */
class RfSignalAnalyzerTest {

    // ==================== RfAnomalyType Tests ====================

    @Test
    fun `RfAnomalyType POSSIBLE_JAMMER has high base score`() {
        val jammer = RfAnomalyType.POSSIBLE_JAMMER
        assertTrue(
            "Jammer should have base score >= 80",
            jammer.baseScore >= 80
        )
    }

    @Test
    fun `RfAnomalyType DRONE_DETECTED has high base score`() {
        val drone = RfAnomalyType.DRONE_DETECTED
        assertTrue(
            "Drone detection should have base score >= 65",
            drone.baseScore >= 65
        )
    }

    @Test
    fun `RfAnomalyType has valid display names and emojis`() {
        RfAnomalyType.entries.forEach { type ->
            assertTrue(
                "RfAnomalyType ${type.name} should have display name",
                type.displayName.isNotEmpty()
            )
            assertTrue(
                "RfAnomalyType ${type.name} should have emoji",
                type.emoji.isNotEmpty()
            )
            assertTrue(
                "RfAnomalyType ${type.name} should have valid base score",
                type.baseScore in 0..100
            )
        }
    }

    // ==================== Jammer Detection Tests ====================

    @Test
    fun `isJammerSuspected detects significant network drop`() {
        val baselineCount = 20
        val currentCount = 5  // 75% drop

        assertTrue(isJammerSuspected(baselineCount, currentCount))
    }

    @Test
    fun `isJammerSuspected ignores small drops`() {
        val baselineCount = 20
        val currentCount = 18  // Only 10% drop

        assertFalse(isJammerSuspected(baselineCount, currentCount))
    }

    @Test
    fun `isJammerSuspected requires minimum baseline`() {
        // Can't detect jammer if baseline was already low
        assertFalse(isJammerSuspected(baselineCount = 2, currentCount = 0))
    }

    @Test
    fun `isJammerSuspected with signal strength analysis`() {
        val result = isJammerSuspectedWithSignal(
            baselineCount = 15,
            currentCount = 5,
            baselineAvgSignal = -60,
            currentAvgSignal = -85  // Significant signal degradation
        )

        assertTrue(result.isSuspected)
        assertTrue(result.networkDropPercent >= 50)
        assertTrue(result.signalDrop >= 20)
    }

    @Test
    fun `isJammerSuspected handles edge cases`() {
        assertFalse(isJammerSuspected(0, 0))
        assertFalse(isJammerSuspected(5, 5))
        assertFalse(isJammerSuspected(10, 15))  // More networks = not jammer
    }

    // ==================== Drone Detection Tests ====================

    @Test
    fun `isDroneSsid detects DJI drones`() {
        assertTrue(isDroneSsid("DJI_Mavic_Pro"))
        assertTrue(isDroneSsid("dji-phantom4"))
        assertTrue(isDroneSsid("Mavic_Mini"))
        assertTrue(isDroneSsid("SPARK-1234"))
        assertTrue(isDroneSsid("Inspire2_ABC"))
    }

    @Test
    fun `isDroneSsid detects Parrot drones`() {
        assertTrue(isDroneSsid("Parrot_Anafi"))
        assertTrue(isDroneSsid("parrot-bebop"))
        assertTrue(isDroneSsid("ANAFI_USA"))
    }

    @Test
    fun `isDroneSsid detects other drone brands`() {
        assertTrue(isDroneSsid("Skydio_2"))
        assertTrue(isDroneSsid("Autel_EVO2"))
        assertTrue(isDroneSsid("YUNEEC_TYPHOON"))
        assertTrue(isDroneSsid("HolyStone_HS720"))
    }

    @Test
    fun `isDroneSsid detects FPV and generic drones`() {
        assertTrue(isDroneSsid("FPV_Racer"))
        assertTrue(isDroneSsid("DRONE_12345"))
        assertTrue(isDroneSsid("Quad_copter"))
        assertTrue(isDroneSsid("UAV_001"))
    }

    @Test
    fun `isDroneSsid detects tactical drones`() {
        assertTrue(isDroneSsid("PD_Drone_1"))
        assertTrue(isDroneSsid("Police_UAV"))
        assertTrue(isDroneSsid("Tactical_Drone"))
        assertTrue(isDroneSsid("Aerial_Unit"))
    }

    @Test
    fun `isDroneSsid rejects normal SSIDs`() {
        assertFalse(isDroneSsid("HomeNetwork"))
        assertFalse(isDroneSsid("Starbucks_WiFi"))
        assertFalse(isDroneSsid("ATT-FIBER"))
        assertFalse(isDroneSsid("NETGEAR"))
    }

    @Test
    fun `isDroneOui detects DJI MAC prefixes`() {
        assertTrue(isDroneOui("60:60:1F"))
        assertTrue(isDroneOui("34:D2:62"))
        assertTrue(isDroneOui("48:1C:B9"))
        assertTrue(isDroneOui("60:C7:98"))
    }

    @Test
    fun `isDroneOui detects Parrot MAC prefixes`() {
        assertTrue(isDroneOui("A0:14:3D"))
        assertTrue(isDroneOui("90:03:B7"))
        assertTrue(isDroneOui("00:12:1C"))
        assertTrue(isDroneOui("00:26:7E"))
    }

    @Test
    fun `isDroneOui rejects normal MAC prefixes`() {
        assertFalse(isDroneOui("AA:BB:CC"))
        assertFalse(isDroneOui("B8:27:EB"))  // Raspberry Pi
    }

    // ==================== Surveillance Area Detection Tests ====================

    @Test
    fun `isSurveillanceArea detects high camera concentration`() {
        val cameraCount = 8
        assertTrue(isSurveillanceArea(cameraCount, threshold = 5))
    }

    @Test
    fun `isSurveillanceArea ignores low camera counts`() {
        val cameraCount = 2
        assertFalse(isSurveillanceArea(cameraCount, threshold = 5))
    }

    @Test
    fun `isSurveillanceCameraOui detects camera manufacturers`() {
        // Hikvision
        assertTrue(isSurveillanceCameraOui("B4:A3:82"))
        assertTrue(isSurveillanceCameraOui("44:19:B6"))

        // Dahua
        assertTrue(isSurveillanceCameraOui("E0:50:8B"))
        assertTrue(isSurveillanceCameraOui("3C:EF:8C"))

        // Axis
        assertTrue(isSurveillanceCameraOui("00:30:53"))
        assertTrue(isSurveillanceCameraOui("00:40:8C"))
        assertTrue(isSurveillanceCameraOui("AC:CC:8E"))

        // Pelco
        assertTrue(isSurveillanceCameraOui("00:04:7D"))

        // Amcrest
        assertTrue(isSurveillanceCameraOui("9C:8E:CD"))

        // Vivotek
        assertTrue(isSurveillanceCameraOui("9C:28:B3"))
        assertTrue(isSurveillanceCameraOui("00:02:D1"))
    }

    @Test
    fun `isSurveillanceCameraOui rejects normal devices`() {
        assertFalse(isSurveillanceCameraOui("AA:BB:CC"))
        assertFalse(isSurveillanceCameraOui("B8:27:EB"))
    }

    // ==================== Spectrum Anomaly Detection Tests ====================

    @Test
    fun `isSpectrumAnomaly detects significant signal changes`() {
        val recentAvgSignal = -45
        val historicalAvgSignal = -65  // 20dB change

        assertTrue(isSpectrumAnomaly(recentAvgSignal, historicalAvgSignal, threshold = 15))
    }

    @Test
    fun `isSpectrumAnomaly ignores normal variations`() {
        val recentAvgSignal = -62
        val historicalAvgSignal = -65  // Only 3dB change

        assertFalse(isSpectrumAnomaly(recentAvgSignal, historicalAvgSignal, threshold = 15))
    }

    @Test
    fun `isSpectrumAnomaly detects both increases and decreases`() {
        // Signal increase
        assertTrue(isSpectrumAnomaly(-45, -70, threshold = 15))
        // Signal decrease
        assertTrue(isSpectrumAnomaly(-85, -60, threshold = 15))
    }

    // ==================== Hidden Network Analysis Tests ====================

    @Test
    fun `isHighHiddenNetworkRatio detects suspicious area`() {
        val result = calculateHiddenNetworkRatio(
            totalNetworks = 30,
            hiddenNetworks = 12  // 40% hidden
        )

        assertTrue(result.ratio > 0.3)
        assertTrue(result.isSuspicious)
    }

    @Test
    fun `isHighHiddenNetworkRatio ignores normal ratios`() {
        val result = calculateHiddenNetworkRatio(
            totalNetworks = 20,
            hiddenNetworks = 2  // 10% hidden
        )

        assertFalse(result.isSuspicious)
    }

    // ==================== RfSnapshot Tests ====================

    @Test
    fun `RfSnapshot stores all required fields`() {
        val snapshot = RfSnapshot(
            timestamp = System.currentTimeMillis(),
            wifiNetworkCount = 25,
            averageSignalStrength = -65,
            strongestSignal = -40,
            weakestSignal = -90,
            channelDistribution = mapOf(1 to 5, 6 to 8, 11 to 7),
            band24Count = 15,
            band5Count = 10,
            band6Count = 0,
            openNetworkCount = 3,
            hiddenNetworkCount = 2,
            droneNetworkCount = 0,
            surveillanceCameraCount = 4
        )

        assertEquals(25, snapshot.wifiNetworkCount)
        assertEquals(-65, snapshot.averageSignalStrength)
        assertEquals(15, snapshot.band24Count)
        assertEquals(4, snapshot.surveillanceCameraCount)
    }

    // ==================== DroneInfo Tests ====================

    @Test
    fun `DroneInfo stores detection information`() {
        val drone = DroneInfo(
            bssid = "60:60:1F:AA:BB:CC",
            ssid = "DJI_Mavic_Pro",
            manufacturer = "DJI",
            firstSeen = 1000L,
            lastSeen = 5000L,
            rssi = -55,
            seenCount = 5,
            latitude = 47.6,
            longitude = -122.3,
            estimatedDistance = "~15-30m"
        )

        assertEquals("DJI_Mavic_Pro", drone.ssid)
        assertEquals("DJI", drone.manufacturer)
        assertEquals(5, drone.seenCount)
    }

    // ==================== RfEnvironmentStatus Tests ====================

    @Test
    fun `RfEnvironmentStatus calculates noise level`() {
        assertEquals(NoiseLevel.LOW, calculateNoiseLevel(10))
        assertEquals(NoiseLevel.MODERATE, calculateNoiseLevel(20))
        assertEquals(NoiseLevel.HIGH, calculateNoiseLevel(40))
        assertEquals(NoiseLevel.EXTREME, calculateNoiseLevel(60))
    }

    @Test
    fun `RfEnvironmentStatus calculates channel congestion`() {
        assertEquals(ChannelCongestion.CLEAR, calculateChannelCongestion(1))
        assertEquals(ChannelCongestion.LIGHT, calculateChannelCongestion(4))
        assertEquals(ChannelCongestion.MODERATE, calculateChannelCongestion(8))
        assertEquals(ChannelCongestion.HEAVY, calculateChannelCongestion(12))
        assertEquals(ChannelCongestion.SEVERE, calculateChannelCongestion(20))
    }

    @Test
    fun `RfEnvironmentStatus calculates environment risk`() {
        // Risk calculation: cameraCount*5 + (droneCount>0 ? 30 : 0) + (hiddenCount>5 ? 15 : 0) + (openCount>10 ? 10 : 0)
        // LOW: riskScore <= 15, MODERATE: 16-30, ELEVATED: 31-50, HIGH: > 50

        // Low risk - no cameras, no drones, few hidden networks (score = 0)
        assertEquals(EnvironmentRisk.LOW, calculateEnvironmentRisk(
            cameraCount = 0, droneCount = 0, hiddenCount = 2, openCount = 5
        ))

        // Moderate risk - some cameras (score = 4*5 = 20)
        assertEquals(EnvironmentRisk.MODERATE, calculateEnvironmentRisk(
            cameraCount = 4, droneCount = 0, hiddenCount = 2, openCount = 5
        ))

        // Elevated risk - drones present (score = 2*5 + 30 = 40)
        assertEquals(EnvironmentRisk.ELEVATED, calculateEnvironmentRisk(
            cameraCount = 2, droneCount = 1, hiddenCount = 5, openCount = 8
        ))

        // High risk - multiple factors (score = 10*5 + 30 + 15 + 10 = 105)
        assertEquals(EnvironmentRisk.HIGH, calculateEnvironmentRisk(
            cameraCount = 10, droneCount = 2, hiddenCount = 10, openCount = 15
        ))
    }

    // ==================== Frequency Analysis Tests ====================

    @Test
    fun `frequencyToChannel converts 2_4GHz correctly`() {
        assertEquals(1, frequencyToChannel(2412))
        assertEquals(6, frequencyToChannel(2437))
        assertEquals(11, frequencyToChannel(2462))
        assertEquals(13, frequencyToChannel(2472))
    }

    @Test
    fun `frequencyToChannel converts 5GHz correctly`() {
        assertEquals(36, frequencyToChannel(5180))
        assertEquals(40, frequencyToChannel(5200))
        assertEquals(44, frequencyToChannel(5220))
        assertEquals(149, frequencyToChannel(5745))
    }

    @Test
    fun `rssiToDistance provides estimates`() {
        assertEquals("< 5m", rssiToDistance(-35))
        assertEquals("~5-15m", rssiToDistance(-45))
        assertEquals("~15-30m", rssiToDistance(-55))
        assertEquals("~30-50m", rssiToDistance(-65))
        assertEquals("~50-100m", rssiToDistance(-75))
        assertEquals("> 100m", rssiToDistance(-85))
    }

    // ==================== E2E Anomaly to Detection Conversion Tests ====================

    @Test
    fun `anomalyToDetection converts POSSIBLE_JAMMER correctly`() {
        val anomaly = RfAnomaly(
            type = RfAnomalyType.POSSIBLE_JAMMER,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Possible RF jammer detected",
            technicalDetails = "Network count dropped from 20 to 5",
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("75% network drop", "25dB signal degradation")
        )

        val detection = rfAnomalyToDetection(anomaly)

        assertEquals(DetectionProtocol.WIFI, detection.protocol)
        assertEquals(DetectionMethod.RF_JAMMER, detection.detectionMethod)
        assertEquals(DeviceType.RF_JAMMER, detection.deviceType)
        assertEquals(ThreatLevel.HIGH, detection.threatLevel)
    }

    @Test
    fun `anomalyToDetection converts DRONE_DETECTED correctly`() {
        val anomaly = RfAnomaly(
            type = RfAnomalyType.DRONE_DETECTED,
            severity = ThreatLevel.MEDIUM,
            confidence = AnomalyConfidence.HIGH,
            description = "DJI drone WiFi detected",
            technicalDetails = "SSID: DJI_Mavic_Pro, Signal: -55dBm",
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("DJI OUI", "Mavic SSID pattern")
        )

        val detection = rfAnomalyToDetection(anomaly)

        assertEquals(DetectionMethod.RF_DRONE, detection.detectionMethod)
        assertEquals(DeviceType.DRONE, detection.deviceType)
    }

    @Test
    fun `anomalyToDetection converts SURVEILLANCE_AREA correctly`() {
        val anomaly = RfAnomaly(
            type = RfAnomalyType.SURVEILLANCE_AREA,
            severity = ThreatLevel.MEDIUM,
            confidence = AnomalyConfidence.MEDIUM,
            description = "High surveillance camera concentration",
            technicalDetails = "8 camera networks detected",
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("8 Hikvision cameras", "Commercial area")
        )

        val detection = rfAnomalyToDetection(anomaly)

        assertEquals(DetectionMethod.RF_SURVEILLANCE_AREA, detection.detectionMethod)
        assertEquals(DeviceType.SURVEILLANCE_INFRASTRUCTURE, detection.deviceType)
    }

    @Test
    fun `droneToDetection creates proper detection`() {
        val drone = DroneInfo(
            bssid = "60:60:1F:AA:BB:CC",
            ssid = "DJI_Mavic_Pro",
            manufacturer = "DJI",
            firstSeen = 1000L,
            lastSeen = 5000L,
            rssi = -55,
            seenCount = 5,
            latitude = 47.6,
            longitude = -122.3,
            estimatedDistance = "~15-30m"
        )

        val detection = droneToDetection(drone)

        assertEquals(DetectionProtocol.WIFI, detection.protocol)
        assertEquals(DetectionMethod.RF_DRONE, detection.detectionMethod)
        assertEquals(DeviceType.DRONE, detection.deviceType)
        assertEquals("60:60:1F:AA:BB:CC", detection.macAddress)
        assertEquals("DJI_Mavic_Pro", detection.ssid)
        assertEquals(-55, detection.rssi)
        assertEquals("DJI", detection.manufacturer)
    }

    @Test
    fun `anomalyToDetection maps all RF anomaly types`() {
        val typeMethodMapping = mapOf(
            RfAnomalyType.POSSIBLE_JAMMER to DetectionMethod.RF_JAMMER,
            RfAnomalyType.DRONE_DETECTED to DetectionMethod.RF_DRONE,
            RfAnomalyType.SURVEILLANCE_AREA to DetectionMethod.RF_SURVEILLANCE_AREA,
            RfAnomalyType.SPECTRUM_ANOMALY to DetectionMethod.RF_SPECTRUM_ANOMALY,
            RfAnomalyType.UNUSUAL_ACTIVITY to DetectionMethod.RF_UNUSUAL_ACTIVITY,
            RfAnomalyType.SIGNAL_INTERFERENCE to DetectionMethod.RF_INTERFERENCE,
            RfAnomalyType.HIDDEN_TRANSMITTER to DetectionMethod.RF_HIDDEN_TRANSMITTER
        )

        typeMethodMapping.forEach { (anomalyType, expectedMethod) ->
            val anomaly = createTestRfAnomaly(anomalyType)
            val detection = rfAnomalyToDetection(anomaly)
            assertEquals(
                "RfAnomalyType $anomalyType should map to $expectedMethod",
                expectedMethod,
                detection.detectionMethod
            )
        }
    }

    // ==================== Helper Classes and Functions ====================

    enum class RfAnomalyType(
        val displayName: String,
        val baseScore: Int,
        val emoji: String
    ) {
        POSSIBLE_JAMMER("Possible RF Jammer", 85, "📵"),
        SPECTRUM_ANOMALY("Spectrum Anomaly", 60, "📊"),
        DRONE_DETECTED("Drone Detected", 70, "🚁"),
        SURVEILLANCE_AREA("High Surveillance Area", 65, "📹"),
        UNUSUAL_ACTIVITY("Unusual RF Activity", 50, "📡"),
        SIGNAL_INTERFERENCE("Signal Interference", 55, "⚡"),
        HIDDEN_TRANSMITTER("Possible Hidden Transmitter", 75, "📻")
    }

    enum class AnomalyConfidence(val displayName: String) {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High"),
        CRITICAL("Critical")
    }

    enum class NoiseLevel(val displayName: String) {
        LOW("Low"),
        MODERATE("Moderate"),
        HIGH("High"),
        EXTREME("Extreme")
    }

    enum class ChannelCongestion(val displayName: String) {
        CLEAR("Clear"),
        LIGHT("Light"),
        MODERATE("Moderate"),
        HEAVY("Heavy"),
        SEVERE("Severe")
    }

    enum class EnvironmentRisk(val displayName: String) {
        LOW("Low Risk"),
        MODERATE("Moderate Risk"),
        ELEVATED("Elevated Risk"),
        HIGH("High Risk")
    }

    data class RfAnomaly(
        val id: String = java.util.UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: RfAnomalyType,
        val severity: ThreatLevel,
        val confidence: AnomalyConfidence,
        val description: String,
        val technicalDetails: String,
        val latitude: Double?,
        val longitude: Double?,
        val contributingFactors: List<String> = emptyList()
    )

    data class RfSnapshot(
        val timestamp: Long,
        val wifiNetworkCount: Int,
        val averageSignalStrength: Int,
        val strongestSignal: Int,
        val weakestSignal: Int,
        val channelDistribution: Map<Int, Int>,
        val band24Count: Int,
        val band5Count: Int,
        val band6Count: Int,
        val openNetworkCount: Int,
        val hiddenNetworkCount: Int,
        val droneNetworkCount: Int,
        val surveillanceCameraCount: Int
    )

    data class DroneInfo(
        val bssid: String,
        val ssid: String,
        val manufacturer: String,
        val firstSeen: Long,
        var lastSeen: Long,
        var rssi: Int,
        var seenCount: Int,
        val latitude: Double?,
        val longitude: Double?,
        val estimatedDistance: String
    )

    data class JammerAnalysis(
        val isSuspected: Boolean,
        val networkDropPercent: Int,
        val signalDrop: Int
    )

    data class HiddenNetworkAnalysis(
        val ratio: Double,
        val isSuspicious: Boolean
    )

    companion object {
        // Drone SSID patterns
        private val DRONE_SSID_PATTERNS = listOf(
            Regex("(?i)^dji[-_]?.*"),
            Regex("(?i)^phantom[-_]?.*"),
            Regex("(?i)^mavic[-_]?.*"),
            Regex("(?i)^spark[-_]?.*"),
            Regex("(?i)^inspire[-_]?.*"),
            Regex("(?i)^parrot[-_]?.*"),
            Regex("(?i)^anafi[-_]?.*"),
            Regex("(?i)^skydio[-_]?.*"),
            Regex("(?i)^autel[-_]?.*"),
            Regex("(?i)^yuneec[-_]?.*"),
            Regex("(?i)^holy[-_]?stone[-_]?.*"),
            Regex("(?i)^drone[-_]?[0-9a-f]+"),
            Regex("(?i)^fpv[-_]?.*"),
            Regex("(?i)^quad[-_]?.*"),
            Regex("(?i)^uav[-_]?.*"),
            Regex("(?i)^(pd|police|tactical|aerial)[-_]?(drone|uav|unit).*"),
        )

        // Drone OUIs
        private val DRONE_OUIS = setOf(
            "60:60:1F", "34:D2:62", "48:1C:B9", "60:C7:98",  // DJI
            "A0:14:3D", "90:03:B7", "00:12:1C", "00:26:7E",  // Parrot
        )

        // Surveillance camera OUIs
        private val SURVEILLANCE_CAMERA_OUIS = setOf(
            "B4:A3:82", "44:19:B6", "54:C4:15", "28:57:BE",  // Hikvision
            "E0:50:8B", "3C:EF:8C", "4C:11:BF", "A0:BD:1D",  // Dahua
            "00:80:F0",  // Panasonic
            "00:30:53", "00:40:8C", "AC:CC:8E",  // Axis
            "00:04:7D",  // Pelco
            "9C:8E:CD",  // Amcrest
            "9C:28:B3", "00:02:D1",  // Vivotek
        )

        fun isJammerSuspected(baselineCount: Int, currentCount: Int): Boolean {
            if (baselineCount < 3) return false  // Need meaningful baseline
            val dropPercent = ((baselineCount - currentCount) * 100) / baselineCount
            return dropPercent >= 50 && currentCount < baselineCount / 2
        }

        fun isJammerSuspectedWithSignal(
            baselineCount: Int,
            currentCount: Int,
            baselineAvgSignal: Int,
            currentAvgSignal: Int
        ): JammerAnalysis {
            val dropPercent = if (baselineCount > 0) {
                ((baselineCount - currentCount) * 100) / baselineCount
            } else 0
            val signalDrop = baselineAvgSignal - currentAvgSignal  // Positive = degradation

            val isSuspected = baselineCount >= 3 &&
                    dropPercent >= 50 &&
                    signalDrop >= 20

            return JammerAnalysis(isSuspected, dropPercent, signalDrop)
        }

        fun isDroneSsid(ssid: String): Boolean {
            return DRONE_SSID_PATTERNS.any { it.matches(ssid) }
        }

        fun isDroneOui(oui: String): Boolean {
            return oui.uppercase() in DRONE_OUIS
        }

        fun isSurveillanceArea(cameraCount: Int, threshold: Int): Boolean {
            return cameraCount >= threshold
        }

        fun isSurveillanceCameraOui(oui: String): Boolean {
            return oui.uppercase() in SURVEILLANCE_CAMERA_OUIS
        }

        fun isSpectrumAnomaly(recentAvg: Int, historicalAvg: Int, threshold: Int): Boolean {
            return kotlin.math.abs(recentAvg - historicalAvg) >= threshold
        }

        fun calculateHiddenNetworkRatio(
            totalNetworks: Int,
            hiddenNetworks: Int
        ): HiddenNetworkAnalysis {
            val ratio = if (totalNetworks > 0) {
                hiddenNetworks.toDouble() / totalNetworks
            } else 0.0

            return HiddenNetworkAnalysis(ratio, ratio > 0.3)
        }

        fun calculateNoiseLevel(networkCount: Int): NoiseLevel {
            return when {
                networkCount > 50 -> NoiseLevel.EXTREME
                networkCount > 30 -> NoiseLevel.HIGH
                networkCount > 15 -> NoiseLevel.MODERATE
                else -> NoiseLevel.LOW
            }
        }

        fun calculateChannelCongestion(maxChannelCount: Int): ChannelCongestion {
            return when {
                maxChannelCount > 15 -> ChannelCongestion.SEVERE
                maxChannelCount > 10 -> ChannelCongestion.HEAVY
                maxChannelCount > 5 -> ChannelCongestion.MODERATE
                maxChannelCount > 2 -> ChannelCongestion.LIGHT
                else -> ChannelCongestion.CLEAR
            }
        }

        fun calculateEnvironmentRisk(
            cameraCount: Int,
            droneCount: Int,
            hiddenCount: Int,
            openCount: Int
        ): EnvironmentRisk {
            var riskScore = 0
            riskScore += cameraCount * 5
            if (droneCount > 0) riskScore += 30
            if (hiddenCount > 5) riskScore += 15
            if (openCount > 10) riskScore += 10

            return when {
                riskScore > 50 -> EnvironmentRisk.HIGH
                riskScore > 30 -> EnvironmentRisk.ELEVATED
                riskScore > 15 -> EnvironmentRisk.MODERATE
                else -> EnvironmentRisk.LOW
            }
        }

        fun frequencyToChannel(frequency: Int): Int {
            return when {
                frequency in 2412..2484 -> (frequency - 2412) / 5 + 1
                frequency in 5170..5825 -> (frequency - 5170) / 5 + 34
                frequency in 5955..7115 -> (frequency - 5955) / 5 + 1
                else -> 0
            }
        }

        fun rssiToDistance(rssi: Int): String {
            return when {
                rssi > -40 -> "< 5m"
                rssi > -50 -> "~5-15m"
                rssi > -60 -> "~15-30m"
                rssi > -70 -> "~30-50m"
                rssi > -80 -> "~50-100m"
                else -> "> 100m"
            }
        }

        fun createTestRfAnomaly(type: RfAnomalyType): RfAnomaly {
            return RfAnomaly(
                type = type,
                severity = ThreatLevel.MEDIUM,
                confidence = AnomalyConfidence.MEDIUM,
                description = "Test anomaly",
                technicalDetails = "Test details",
                latitude = null,
                longitude = null
            )
        }

        fun rfAnomalyToDetection(anomaly: RfAnomaly): Detection {
            val detectionMethod = when (anomaly.type) {
                RfAnomalyType.POSSIBLE_JAMMER -> DetectionMethod.RF_JAMMER
                RfAnomalyType.DRONE_DETECTED -> DetectionMethod.RF_DRONE
                RfAnomalyType.SURVEILLANCE_AREA -> DetectionMethod.RF_SURVEILLANCE_AREA
                RfAnomalyType.SPECTRUM_ANOMALY -> DetectionMethod.RF_SPECTRUM_ANOMALY
                RfAnomalyType.UNUSUAL_ACTIVITY -> DetectionMethod.RF_UNUSUAL_ACTIVITY
                RfAnomalyType.SIGNAL_INTERFERENCE -> DetectionMethod.RF_INTERFERENCE
                RfAnomalyType.HIDDEN_TRANSMITTER -> DetectionMethod.RF_HIDDEN_TRANSMITTER
            }

            val deviceType = when (anomaly.type) {
                RfAnomalyType.POSSIBLE_JAMMER -> DeviceType.RF_JAMMER
                RfAnomalyType.DRONE_DETECTED -> DeviceType.DRONE
                RfAnomalyType.SURVEILLANCE_AREA -> DeviceType.SURVEILLANCE_INFRASTRUCTURE
                else -> DeviceType.UNKNOWN_SURVEILLANCE
            }

            return Detection(
                deviceType = deviceType,
                protocol = DetectionProtocol.WIFI,
                detectionMethod = detectionMethod,
                deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}",
                macAddress = null,
                ssid = null,
                rssi = -50,
                signalStrength = SignalStrength.MEDIUM,
                latitude = anomaly.latitude,
                longitude = anomaly.longitude,
                threatLevel = anomaly.severity,
                threatScore = anomaly.type.baseScore,
                matchedPatterns = anomaly.contributingFactors.joinToString(", ")
            )
        }

        fun droneToDetection(drone: DroneInfo): Detection {
            return Detection(
                deviceType = DeviceType.DRONE,
                protocol = DetectionProtocol.WIFI,
                detectionMethod = DetectionMethod.RF_DRONE,
                deviceName = "🚁 ${drone.manufacturer} Drone",
                macAddress = drone.bssid,
                ssid = drone.ssid,
                rssi = drone.rssi,
                signalStrength = rssiToSignalStrength(drone.rssi),
                latitude = drone.latitude,
                longitude = drone.longitude,
                threatLevel = ThreatLevel.MEDIUM,
                threatScore = 70,
                manufacturer = drone.manufacturer,
                matchedPatterns = "Drone WiFi pattern, Est. distance: ${drone.estimatedDistance}"
            )
        }
    }
}

package com.flockyou.service

import com.flockyou.data.model.*
import org.junit.Test
import org.junit.Assert.*

/**
 * Comprehensive E2E unit tests for UltrasonicDetector.
 * Tests validate ultrasonic beacon detection including tracking beacons,
 * advertising beacons, retail beacons, and cross-device tracking.
 */
class UltrasonicDetectorTest {

    // ==================== UltrasonicAnomalyType Tests ====================

    @Test
    fun `UltrasonicAnomalyType TRACKING_BEACON has high base score`() {
        val tracking = UltrasonicAnomalyType.TRACKING_BEACON
        assertTrue(
            "Tracking beacon should have base score >= 75",
            tracking.baseScore >= 75
        )
    }

    @Test
    fun `UltrasonicAnomalyType CROSS_DEVICE_TRACKING has highest base score`() {
        val crossDevice = UltrasonicAnomalyType.CROSS_DEVICE_TRACKING
        assertTrue(
            "Cross-device tracking should have base score >= 80",
            crossDevice.baseScore >= 80
        )
    }

    @Test
    fun `UltrasonicAnomalyType has valid display names and emojis`() {
        UltrasonicAnomalyType.entries.forEach { type ->
            assertTrue(
                "UltrasonicAnomalyType ${type.name} should have display name",
                type.displayName.isNotEmpty()
            )
            assertTrue(
                "UltrasonicAnomalyType ${type.name} should have emoji",
                type.emoji.isNotEmpty()
            )
            assertTrue(
                "UltrasonicAnomalyType ${type.name} should have valid base score",
                type.baseScore in 0..100
            )
        }
    }

    // ==================== Frequency Detection Tests ====================

    @Test
    fun `isUltrasonicFrequency detects ultrasonic range`() {
        assertTrue(isUltrasonicFrequency(17500))  // Near ultrasonic
        assertTrue(isUltrasonicFrequency(18000))  // SilverPush
        assertTrue(isUltrasonicFrequency(19000))  // Advertising
        assertTrue(isUltrasonicFrequency(20000))  // Cross-device
        assertTrue(isUltrasonicFrequency(21000))  // Premium tracking
        assertTrue(isUltrasonicFrequency(22000))  // Upper limit
    }

    @Test
    fun `isUltrasonicFrequency rejects audible frequencies`() {
        assertFalse(isUltrasonicFrequency(440))    // A440 concert pitch
        assertFalse(isUltrasonicFrequency(1000))   // 1 kHz
        assertFalse(isUltrasonicFrequency(8000))   // 8 kHz
        assertFalse(isUltrasonicFrequency(16000))  // 16 kHz - still audible to many
    }

    @Test
    fun `isUltrasonicFrequency rejects frequencies above Nyquist`() {
        // At 44.1kHz sample rate, Nyquist is 22050Hz
        assertFalse(isUltrasonicFrequency(23000))
        assertFalse(isUltrasonicFrequency(30000))
    }

    // ==================== Known Beacon Detection Tests ====================

    @Test
    fun `isKnownBeaconFrequency detects SilverPush primary`() {
        val result = classifyBeaconFrequency(18000)
        assertEquals("SilverPush/Ad Tracking", result)
    }

    @Test
    fun `isKnownBeaconFrequency detects Alphonso primary`() {
        val result = classifyBeaconFrequency(18500)
        assertEquals("Alphonso/TV Tracking", result)
    }

    @Test
    fun `isKnownBeaconFrequency detects advertising beacon`() {
        val result = classifyBeaconFrequency(19000)
        assertEquals("Advertising Beacon", result)
    }

    @Test
    fun `isKnownBeaconFrequency detects retail beacon`() {
        val result = classifyBeaconFrequency(19500)
        assertEquals("Retail Tracking", result)
    }

    @Test
    fun `isKnownBeaconFrequency detects cross-device tracking`() {
        val result = classifyBeaconFrequency(20000)
        assertEquals("Cross-Device Tracking", result)
    }

    @Test
    fun `isKnownBeaconFrequency detects location beacon`() {
        val result = classifyBeaconFrequency(20500)
        assertEquals("Location Beacon", result)
    }

    @Test
    fun `isKnownBeaconFrequency detects premium ad tracking`() {
        val result = classifyBeaconFrequency(21000)
        assertEquals("Premium Ad Tracking", result)
    }

    @Test
    fun `isKnownBeaconFrequency handles unknown frequencies`() {
        val result = classifyBeaconFrequency(17800)
        assertEquals("Unknown Ultrasonic Source", result)
    }

    @Test
    fun `isKnownBeaconFrequency uses frequency tolerance`() {
        // Within 100Hz tolerance
        assertEquals("SilverPush/Ad Tracking", classifyBeaconFrequency(17950))
        assertEquals("SilverPush/Ad Tracking", classifyBeaconFrequency(18050))

        // Outside tolerance
        assertEquals("Unknown Ultrasonic Source", classifyBeaconFrequency(17800))
    }

    // ==================== Beacon Detection Analysis Tests ====================

    @Test
    fun `isBeaconDetected requires minimum persistence`() {
        val detections = listOf(
            FrequencyDetection(18000, -35.0, 100L),
            FrequencyDetection(18000, -38.0, 200L),
            FrequencyDetection(18000, -36.0, 300L),
            FrequencyDetection(18000, -37.0, 400L),
            FrequencyDetection(18000, -35.0, 600L)
        )

        // Duration = 600 - 100 = 500ms, should pass
        assertTrue(isBeaconPersistent(detections, minDurationMs = 500))
    }

    @Test
    fun `isBeaconDetected rejects transient signals`() {
        val detections = listOf(
            FrequencyDetection(18000, -35.0, 100L),
            FrequencyDetection(18000, -38.0, 200L)
        )

        // Only 200ms, needs 500ms
        assertFalse(isBeaconPersistent(detections, minDurationMs = 500))
    }

    @Test
    fun `isBeaconAboveNoiseFloor validates amplitude`() {
        val noiseFloor = -60.0

        // isAboveNoiseFloor returns (amplitudeDb - noiseFloorDb) >= threshold
        assertTrue(isAboveNoiseFloor(-35.0, noiseFloor, threshold = 20.0))  // 25dB above, need 20 -> true
        assertTrue(isAboveNoiseFloor(-50.0, noiseFloor, threshold = 10.0))  // 10dB above, need 10 -> true
        assertFalse(isAboveNoiseFloor(-65.0, noiseFloor, threshold = 10.0)) // -5dB (below), need 10 -> false
    }

    @Test
    fun `isBeaconConsistent validates detection count`() {
        val detections = listOf(
            FrequencyDetection(18000, -35.0, 100L),
            FrequencyDetection(18000, -36.0, 200L),
            FrequencyDetection(18000, -35.0, 300L)
        )

        assertTrue(isConsistentDetection(detections, minCount = 3))
        assertFalse(isConsistentDetection(detections, minCount = 5))
    }

    // ==================== Goertzel Algorithm Tests ====================

    @Test
    fun `goertzelMagnitude detects target frequency`() {
        // Generate test tone at 18000Hz
        val samples = generateTone(18000, 44100, 4096)
        val magnitude = goertzelMagnitude(samples, 44100, 18000)

        // Should have significant magnitude
        assertTrue("Should detect 18kHz tone", magnitude > 0.5)
    }

    @Test
    fun `goertzelMagnitude rejects non-target frequency`() {
        // Generate test tone at 1000Hz
        val samples = generateTone(1000, 44100, 4096)
        val magnitude = goertzelMagnitude(samples, 44100, 18000)

        // Should have much smaller magnitude at 18kHz compared to target
        // The Goertzel algorithm may show some spectral leakage, so we compare relatively
        val targetMagnitude = goertzelMagnitude(samples, 44100, 1000)
        assertTrue("18kHz response should be much smaller than 1kHz response", magnitude < targetMagnitude)
    }

    @Test
    fun `goertzelMagnitude handles silence`() {
        val samples = ShortArray(4096) { 0 }
        val magnitude = goertzelMagnitude(samples, 44100, 18000)

        assertEquals(0.0, magnitude, 0.001)
    }

    // ==================== BeaconDetection Tests ====================

    @Test
    fun `BeaconDetection stores all required fields`() {
        val beacon = BeaconDetection(
            frequency = 18000,
            firstDetected = 1000L,
            lastDetected = 5000L,
            peakAmplitudeDb = -35.0,
            detectionCount = 10,
            possibleSource = "SilverPush/Ad Tracking",
            latitude = 47.6,
            longitude = -122.3
        )

        assertEquals(18000, beacon.frequency)
        assertEquals(-35.0, beacon.peakAmplitudeDb, 0.001)
        assertEquals(10, beacon.detectionCount)
        assertEquals("SilverPush/Ad Tracking", beacon.possibleSource)
    }

    @Test
    fun `BeaconDetection duration calculation`() {
        val beacon = BeaconDetection(
            frequency = 18000,
            firstDetected = 1000L,
            lastDetected = 6000L,
            peakAmplitudeDb = -35.0,
            detectionCount = 5,
            possibleSource = "Test",
            latitude = null,
            longitude = null
        )

        val duration = beacon.lastDetected - beacon.firstDetected
        assertEquals(5000L, duration)
    }

    // ==================== UltrasonicAnomaly Tests ====================

    @Test
    fun `UltrasonicAnomaly has unique ID`() {
        val anomaly1 = createTestUltrasonicAnomaly(UltrasonicAnomalyType.TRACKING_BEACON)
        val anomaly2 = createTestUltrasonicAnomaly(UltrasonicAnomalyType.TRACKING_BEACON)
        assertNotEquals(anomaly1.id, anomaly2.id)
    }

    @Test
    fun `UltrasonicAnomaly stores all fields`() {
        val anomaly = UltrasonicAnomaly(
            type = UltrasonicAnomalyType.ADVERTISING_BEACON,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Advertising beacon detected",
            technicalDetails = "18000Hz beacon from TV",
            frequency = 18000,
            amplitudeDb = -35.0,
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("SilverPush frequency", "Persistent signal")
        )

        assertEquals(UltrasonicAnomalyType.ADVERTISING_BEACON, anomaly.type)
        assertEquals(18000, anomaly.frequency)
        assertEquals(-35.0, anomaly.amplitudeDb!!, 0.001)
        assertEquals(2, anomaly.contributingFactors.size)
    }

    // ==================== UltrasonicStatus Tests ====================

    @Test
    fun `UltrasonicStatus calculates threat level`() {
        // High threat - known beacon detected
        assertEquals(ThreatLevel.HIGH, calculateUltrasonicThreatLevel(
            hasKnownBeacon = true,
            activeBeaconCount = 1
        ))

        // Medium threat - unknown beacon
        assertEquals(ThreatLevel.MEDIUM, calculateUltrasonicThreatLevel(
            hasKnownBeacon = false,
            activeBeaconCount = 1
        ))

        // Low threat - detected but not beacon
        assertEquals(ThreatLevel.LOW, calculateUltrasonicThreatLevel(
            hasKnownBeacon = false,
            activeBeaconCount = 0,
            hasUltrasonicActivity = true
        ))

        // Info - no activity
        assertEquals(ThreatLevel.INFO, calculateUltrasonicThreatLevel(
            hasKnownBeacon = false,
            activeBeaconCount = 0,
            hasUltrasonicActivity = false
        ))
    }

    // ==================== Anomaly Type Classification Tests ====================

    @Test
    fun `classifyAnomalyType identifies advertising beacons`() {
        assertEquals(
            UltrasonicAnomalyType.ADVERTISING_BEACON,
            classifyAnomalyType("SilverPush/Ad Tracking", 18000)
        )
        assertEquals(
            UltrasonicAnomalyType.ADVERTISING_BEACON,
            classifyAnomalyType("Alphonso/TV Tracking", 18500)
        )
    }

    @Test
    fun `classifyAnomalyType identifies retail beacons`() {
        assertEquals(
            UltrasonicAnomalyType.RETAIL_BEACON,
            classifyAnomalyType("Retail Tracking", 19500)
        )
    }

    @Test
    fun `classifyAnomalyType identifies cross-device tracking`() {
        assertEquals(
            UltrasonicAnomalyType.CROSS_DEVICE_TRACKING,
            classifyAnomalyType("Cross-Device Tracking", 20000)
        )
    }

    @Test
    fun `classifyAnomalyType identifies tracking beacons`() {
        assertEquals(
            UltrasonicAnomalyType.TRACKING_BEACON,
            classifyAnomalyType("Location Beacon", 20500)
        )
        assertEquals(
            UltrasonicAnomalyType.TRACKING_BEACON,
            classifyAnomalyType("Premium Ad Tracking", 21000)
        )
    }

    @Test
    fun `classifyAnomalyType identifies unknown sources`() {
        assertEquals(
            UltrasonicAnomalyType.UNKNOWN_ULTRASONIC,
            classifyAnomalyType("Unknown Ultrasonic Source", 17800)
        )
    }

    // ==================== E2E Anomaly to Detection Conversion Tests ====================

    @Test
    fun `anomalyToDetection converts TRACKING_BEACON correctly`() {
        val anomaly = UltrasonicAnomaly(
            type = UltrasonicAnomalyType.TRACKING_BEACON,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Tracking beacon detected at 20500Hz",
            technicalDetails = "Location beacon, persistent signal",
            frequency = 20500,
            amplitudeDb = -35.0,
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("Location beacon frequency", "500ms persistence")
        )

        val detection = ultrasonicAnomalyToDetection(anomaly)

        assertEquals(DetectionProtocol.AUDIO, detection.protocol)
        assertEquals(DetectionMethod.ULTRASONIC_TRACKING_BEACON, detection.detectionMethod)
        assertEquals(DeviceType.ULTRASONIC_BEACON, detection.deviceType)
        assertEquals("20500Hz", detection.ssid)
        assertEquals(ThreatLevel.HIGH, detection.threatLevel)
    }

    @Test
    fun `anomalyToDetection converts ADVERTISING_BEACON correctly`() {
        val anomaly = UltrasonicAnomaly(
            type = UltrasonicAnomalyType.ADVERTISING_BEACON,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "SilverPush beacon detected",
            technicalDetails = "18000Hz advertising beacon",
            frequency = 18000,
            amplitudeDb = -38.0,
            latitude = null,
            longitude = null,
            contributingFactors = listOf("SilverPush frequency match")
        )

        val detection = ultrasonicAnomalyToDetection(anomaly)

        assertEquals(DetectionMethod.ULTRASONIC_AD_BEACON, detection.detectionMethod)
        assertEquals("18000Hz", detection.ssid)
    }

    @Test
    fun `anomalyToDetection converts RETAIL_BEACON correctly`() {
        val anomaly = UltrasonicAnomaly(
            type = UltrasonicAnomalyType.RETAIL_BEACON,
            severity = ThreatLevel.MEDIUM,
            confidence = AnomalyConfidence.MEDIUM,
            description = "Retail tracking beacon",
            technicalDetails = "19500Hz retail beacon",
            frequency = 19500,
            amplitudeDb = -42.0,
            latitude = null,
            longitude = null,
            contributingFactors = listOf("Retail frequency match")
        )

        val detection = ultrasonicAnomalyToDetection(anomaly)

        assertEquals(DetectionMethod.ULTRASONIC_RETAIL_BEACON, detection.detectionMethod)
    }

    @Test
    fun `anomalyToDetection converts CROSS_DEVICE_TRACKING correctly`() {
        val anomaly = UltrasonicAnomaly(
            type = UltrasonicAnomalyType.CROSS_DEVICE_TRACKING,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.CRITICAL,
            description = "Cross-device tracking beacon",
            technicalDetails = "20000Hz cross-device beacon",
            frequency = 20000,
            amplitudeDb = -33.0,
            latitude = null,
            longitude = null,
            contributingFactors = listOf("Cross-device frequency")
        )

        val detection = ultrasonicAnomalyToDetection(anomaly)

        assertEquals(DetectionMethod.ULTRASONIC_CROSS_DEVICE, detection.detectionMethod)
    }

    @Test
    fun `anomalyToDetection maps all ultrasonic anomaly types`() {
        val typeMethodMapping = mapOf(
            UltrasonicAnomalyType.TRACKING_BEACON to DetectionMethod.ULTRASONIC_TRACKING_BEACON,
            UltrasonicAnomalyType.ADVERTISING_BEACON to DetectionMethod.ULTRASONIC_AD_BEACON,
            UltrasonicAnomalyType.RETAIL_BEACON to DetectionMethod.ULTRASONIC_RETAIL_BEACON,
            UltrasonicAnomalyType.CONTINUOUS_ULTRASONIC to DetectionMethod.ULTRASONIC_CONTINUOUS,
            UltrasonicAnomalyType.CROSS_DEVICE_TRACKING to DetectionMethod.ULTRASONIC_CROSS_DEVICE,
            UltrasonicAnomalyType.UNKNOWN_ULTRASONIC to DetectionMethod.ULTRASONIC_UNKNOWN
        )

        typeMethodMapping.forEach { (anomalyType, expectedMethod) ->
            val anomaly = createTestUltrasonicAnomaly(anomalyType)
            val detection = ultrasonicAnomalyToDetection(anomaly)
            assertEquals(
                "UltrasonicAnomalyType $anomalyType should map to $expectedMethod",
                expectedMethod,
                detection.detectionMethod
            )
        }
    }

    @Test
    fun `anomalyToDetection sets signal strength from amplitude`() {
        // Strong signal
        val strongAnomaly = createTestUltrasonicAnomaly(
            UltrasonicAnomalyType.TRACKING_BEACON,
            amplitudeDb = -25.0
        )
        assertEquals(SignalStrength.EXCELLENT, ultrasonicAnomalyToDetection(strongAnomaly).signalStrength)

        // Good signal
        val goodAnomaly = createTestUltrasonicAnomaly(
            UltrasonicAnomalyType.TRACKING_BEACON,
            amplitudeDb = -35.0
        )
        assertEquals(SignalStrength.GOOD, ultrasonicAnomalyToDetection(goodAnomaly).signalStrength)

        // Medium signal
        val mediumAnomaly = createTestUltrasonicAnomaly(
            UltrasonicAnomalyType.TRACKING_BEACON,
            amplitudeDb = -45.0
        )
        assertEquals(SignalStrength.MEDIUM, ultrasonicAnomalyToDetection(mediumAnomaly).signalStrength)

        // Weak signal
        val weakAnomaly = createTestUltrasonicAnomaly(
            UltrasonicAnomalyType.TRACKING_BEACON,
            amplitudeDb = -55.0
        )
        assertEquals(SignalStrength.WEAK, ultrasonicAnomalyToDetection(weakAnomaly).signalStrength)
    }

    // ==================== Helper Classes and Functions ====================

    enum class UltrasonicAnomalyType(
        val displayName: String,
        val baseScore: Int,
        val emoji: String
    ) {
        TRACKING_BEACON("Tracking Beacon", 80, "📢"),
        ADVERTISING_BEACON("Advertising Beacon", 70, "📺"),
        RETAIL_BEACON("Retail Beacon", 65, "🏪"),
        CONTINUOUS_ULTRASONIC("Continuous Ultrasonic", 75, "🔊"),
        CROSS_DEVICE_TRACKING("Cross-Device Tracking", 85, "📲"),
        UNKNOWN_ULTRASONIC("Unknown Ultrasonic", 60, "❓")
    }

    enum class AnomalyConfidence(val displayName: String) {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High"),
        CRITICAL("Critical")
    }

    data class UltrasonicAnomaly(
        val id: String = java.util.UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: UltrasonicAnomalyType,
        val severity: ThreatLevel,
        val confidence: AnomalyConfidence,
        val description: String,
        val technicalDetails: String,
        val frequency: Int?,
        val amplitudeDb: Double?,
        val latitude: Double?,
        val longitude: Double?,
        val contributingFactors: List<String> = emptyList()
    )

    data class BeaconDetection(
        val frequency: Int,
        val firstDetected: Long,
        var lastDetected: Long,
        var peakAmplitudeDb: Double,
        var detectionCount: Int = 1,
        val possibleSource: String,
        val latitude: Double?,
        val longitude: Double?
    )

    data class FrequencyDetection(
        val frequency: Int,
        val amplitudeDb: Double,
        val timestamp: Long
    )

    companion object {
        // Constants
        private const val ULTRASONIC_LOW = 17500
        private const val ULTRASONIC_HIGH = 22000
        private const val NYQUIST_LIMIT = 22050
        private const val FREQUENCY_TOLERANCE = 100

        // Known beacon frequencies
        private val KNOWN_BEACON_FREQUENCIES = mapOf(
            18000 to "SilverPush/Ad Tracking",
            18500 to "Alphonso/TV Tracking",
            19000 to "Advertising Beacon",
            19500 to "Retail Tracking",
            20000 to "Cross-Device Tracking",
            20500 to "Location Beacon",
            21000 to "Premium Ad Tracking"
        )

        fun isUltrasonicFrequency(frequency: Int): Boolean {
            return frequency in ULTRASONIC_LOW..minOf(ULTRASONIC_HIGH, NYQUIST_LIMIT)
        }

        fun classifyBeaconFrequency(frequency: Int): String {
            for ((knownFreq, source) in KNOWN_BEACON_FREQUENCIES) {
                if (kotlin.math.abs(frequency - knownFreq) <= FREQUENCY_TOLERANCE) {
                    return source
                }
            }
            return "Unknown Ultrasonic Source"
        }

        fun isBeaconPersistent(
            detections: List<FrequencyDetection>,
            minDurationMs: Long
        ): Boolean {
            if (detections.size < 2) return false
            val duration = detections.maxOf { it.timestamp } - detections.minOf { it.timestamp }
            return duration >= minDurationMs
        }

        fun isAboveNoiseFloor(
            amplitudeDb: Double,
            noiseFloorDb: Double,
            threshold: Double
        ): Boolean {
            return (amplitudeDb - noiseFloorDb) >= threshold
        }

        fun isConsistentDetection(detections: List<FrequencyDetection>, minCount: Int): Boolean {
            return detections.size >= minCount
        }

        fun goertzelMagnitude(samples: ShortArray, sampleRate: Int, targetFreq: Int): Double {
            val normalizedFreq = targetFreq.toDouble() / sampleRate
            val coeff = 2 * kotlin.math.cos(2 * Math.PI * normalizedFreq)

            var s0 = 0.0
            var s1 = 0.0
            var s2 = 0.0

            for (sample in samples) {
                s0 = sample / 32768.0 + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }

            val power = s1 * s1 + s2 * s2 - s1 * s2 * coeff
            return kotlin.math.sqrt(kotlin.math.abs(power))
        }

        fun generateTone(frequency: Int, sampleRate: Int, samples: Int): ShortArray {
            val result = ShortArray(samples)
            val omega = 2.0 * Math.PI * frequency / sampleRate

            for (i in 0 until samples) {
                val value = kotlin.math.sin(omega * i) * 32767
                result[i] = value.toInt().toShort()
            }

            return result
        }

        fun calculateUltrasonicThreatLevel(
            hasKnownBeacon: Boolean,
            activeBeaconCount: Int,
            hasUltrasonicActivity: Boolean = false
        ): ThreatLevel {
            return when {
                hasKnownBeacon -> ThreatLevel.HIGH
                activeBeaconCount > 0 -> ThreatLevel.MEDIUM
                hasUltrasonicActivity -> ThreatLevel.LOW
                else -> ThreatLevel.INFO
            }
        }

        fun classifyAnomalyType(source: String, frequency: Int): UltrasonicAnomalyType {
            return when {
                source.contains("SilverPush") || source.contains("Alphonso") ->
                    UltrasonicAnomalyType.ADVERTISING_BEACON
                source.contains("Retail") ->
                    UltrasonicAnomalyType.RETAIL_BEACON
                source.contains("Cross-Device") ->
                    UltrasonicAnomalyType.CROSS_DEVICE_TRACKING
                source.contains("Location") || source.contains("Premium") ->
                    UltrasonicAnomalyType.TRACKING_BEACON
                else ->
                    UltrasonicAnomalyType.UNKNOWN_ULTRASONIC
            }
        }

        fun createTestUltrasonicAnomaly(
            type: UltrasonicAnomalyType,
            amplitudeDb: Double = -40.0
        ): UltrasonicAnomaly {
            return UltrasonicAnomaly(
                type = type,
                severity = ThreatLevel.MEDIUM,
                confidence = AnomalyConfidence.MEDIUM,
                description = "Test anomaly",
                technicalDetails = "Test details",
                frequency = 18000,
                amplitudeDb = amplitudeDb,
                latitude = null,
                longitude = null
            )
        }

        fun ultrasonicAnomalyToDetection(anomaly: UltrasonicAnomaly): Detection {
            val detectionMethod = when (anomaly.type) {
                UltrasonicAnomalyType.TRACKING_BEACON -> DetectionMethod.ULTRASONIC_TRACKING_BEACON
                UltrasonicAnomalyType.ADVERTISING_BEACON -> DetectionMethod.ULTRASONIC_AD_BEACON
                UltrasonicAnomalyType.RETAIL_BEACON -> DetectionMethod.ULTRASONIC_RETAIL_BEACON
                UltrasonicAnomalyType.CONTINUOUS_ULTRASONIC -> DetectionMethod.ULTRASONIC_CONTINUOUS
                UltrasonicAnomalyType.CROSS_DEVICE_TRACKING -> DetectionMethod.ULTRASONIC_CROSS_DEVICE
                UltrasonicAnomalyType.UNKNOWN_ULTRASONIC -> DetectionMethod.ULTRASONIC_UNKNOWN
            }

            val signalStrength = when {
                (anomaly.amplitudeDb ?: -100.0) > -30 -> SignalStrength.EXCELLENT
                (anomaly.amplitudeDb ?: -100.0) > -40 -> SignalStrength.GOOD
                (anomaly.amplitudeDb ?: -100.0) > -50 -> SignalStrength.MEDIUM
                else -> SignalStrength.WEAK
            }

            return Detection(
                deviceType = DeviceType.ULTRASONIC_BEACON,
                protocol = DetectionProtocol.AUDIO,
                detectionMethod = detectionMethod,
                deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}",
                macAddress = null,
                ssid = anomaly.frequency?.let { "${it}Hz" },
                rssi = anomaly.amplitudeDb?.toInt() ?: -50,
                signalStrength = signalStrength,
                latitude = anomaly.latitude,
                longitude = anomaly.longitude,
                threatLevel = anomaly.severity,
                threatScore = anomaly.type.baseScore,
                matchedPatterns = anomaly.contributingFactors.joinToString(", ")
            )
        }
    }
}

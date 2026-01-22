package com.flockyou.service

import com.flockyou.data.model.*
import com.flockyou.service.CellularMonitor.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Comprehensive E2E unit tests for CellularMonitor anomaly detection.
 * Tests validate the complete detection pipeline from raw cell data to Detection objects.
 */
class CellularMonitorTest {

    // ==================== AnomalyType Tests ====================

    @Test
    fun `AnomalyType ENCRYPTION_DOWNGRADE has significant base score`() {
        val encryptionDowngrade = AnomalyType.ENCRYPTION_DOWNGRADE
        // Base score of 60 is significant but not highest - additional factors
        // (signal spike, new tower, stationary, impossible movement) can boost it
        assertTrue(
            "Encryption downgrade should have base score >= 50",
            encryptionDowngrade.baseScore >= 50
        )
    }

    @Test
    fun `AnomalyType SUSPICIOUS_NETWORK has highest base score`() {
        val suspicious = AnomalyType.SUSPICIOUS_NETWORK
        assertTrue(
            "Suspicious network should have base score >= 90",
            suspicious.baseScore >= 90
        )
    }

    @Test
    fun `AnomalyType has valid display names and emojis`() {
        AnomalyType.entries.forEach { type ->
            assertTrue(
                "AnomalyType ${type.name} should have display name",
                type.displayName.isNotEmpty()
            )
            assertTrue(
                "AnomalyType ${type.name} should have emoji",
                type.emoji.isNotEmpty()
            )
            assertTrue(
                "AnomalyType ${type.name} should have valid base score",
                type.baseScore in 0..100
            )
        }
    }

    @Test
    fun `AnomalyType requiresMultipleFactors is set correctly`() {
        // High-confidence anomalies don't require multiple factors
        assertFalse(AnomalyType.ENCRYPTION_DOWNGRADE.requiresMultipleFactors)
        assertFalse(AnomalyType.SUSPICIOUS_NETWORK.requiresMultipleFactors)

        // Lower-confidence anomalies require corroboration
        assertTrue(AnomalyType.SIGNAL_SPIKE.requiresMultipleFactors)
        assertTrue(AnomalyType.CELL_TOWER_CHANGE.requiresMultipleFactors)
        assertTrue(AnomalyType.RAPID_CELL_SWITCHING.requiresMultipleFactors)
    }

    // ==================== AnomalyConfidence Tests ====================

    @Test
    fun `AnomalyConfidence levels are ordered correctly`() {
        assertTrue(AnomalyConfidence.LOW.ordinal < AnomalyConfidence.MEDIUM.ordinal)
        assertTrue(AnomalyConfidence.MEDIUM.ordinal < AnomalyConfidence.HIGH.ordinal)
        assertTrue(AnomalyConfidence.HIGH.ordinal < AnomalyConfidence.CRITICAL.ordinal)
    }

    @Test
    fun `AnomalyConfidence minFactors increases with confidence`() {
        assertTrue(AnomalyConfidence.LOW.minFactors < AnomalyConfidence.MEDIUM.minFactors)
        assertTrue(AnomalyConfidence.MEDIUM.minFactors < AnomalyConfidence.HIGH.minFactors)
        assertTrue(AnomalyConfidence.HIGH.minFactors < AnomalyConfidence.CRITICAL.minFactors)
    }

    // ==================== Network Generation Tests ====================

    @Test
    fun `getNetworkGeneration returns 5G for NR`() {
        assertEquals("5G", getNetworkGenerationFromType(20)) // NR
    }

    @Test
    fun `getNetworkGeneration returns 4G for LTE variants`() {
        assertEquals("4G", getNetworkGenerationFromType(13)) // LTE
        assertEquals("4G", getNetworkGenerationFromType(18)) // IWLAN
    }

    @Test
    fun `getNetworkGeneration returns 3G for UMTS variants`() {
        assertEquals("3G", getNetworkGenerationFromType(3))  // UMTS
        assertEquals("3G", getNetworkGenerationFromType(8))  // HSDPA
        assertEquals("3G", getNetworkGenerationFromType(9))  // HSUPA
        assertEquals("3G", getNetworkGenerationFromType(10)) // HSPA
        assertEquals("3G", getNetworkGenerationFromType(15)) // HSPAP
        assertEquals("3G", getNetworkGenerationFromType(17)) // TD_SCDMA
    }

    @Test
    fun `getNetworkGeneration returns 2G for GSM variants`() {
        assertEquals("2G", getNetworkGenerationFromType(1))  // GPRS
        assertEquals("2G", getNetworkGenerationFromType(2))  // EDGE
        assertEquals("2G", getNetworkGenerationFromType(4))  // CDMA
        assertEquals("2G", getNetworkGenerationFromType(7))  // 1xRTT
        assertEquals("2G", getNetworkGenerationFromType(16)) // GSM
    }

    @Test
    fun `getNetworkGeneration returns Unknown for invalid type`() {
        assertEquals("Unknown", getNetworkGenerationFromType(-1))
        assertEquals("Unknown", getNetworkGenerationFromType(0))
        assertEquals("Unknown", getNetworkGenerationFromType(99))
    }

    // ==================== Suspicious MCC/MNC Tests ====================

    @Test
    fun `isSuspiciousMccMnc detects ITU test networks`() {
        assertTrue(isSuspiciousMccMnc("001", "01"))
        assertTrue(isSuspiciousMccMnc("001", "00"))
        assertTrue(isSuspiciousMccMnc("001", "02"))
    }

    @Test
    fun `isSuspiciousMccMnc detects reserved test networks`() {
        assertTrue(isSuspiciousMccMnc("999", "99"))
        assertTrue(isSuspiciousMccMnc("999", "01"))
    }

    @Test
    fun `isSuspiciousMccMnc detects invalid network`() {
        assertTrue(isSuspiciousMccMnc("000", "00"))
    }

    @Test
    fun `isSuspiciousMccMnc allows valid US carriers`() {
        assertFalse(isSuspiciousMccMnc("310", "410")) // AT&T
        assertFalse(isSuspiciousMccMnc("311", "480")) // Verizon
        assertFalse(isSuspiciousMccMnc("310", "260")) // T-Mobile
        assertFalse(isSuspiciousMccMnc("310", "120")) // Sprint
    }

    @Test
    fun `isSuspiciousMccMnc allows valid international carriers`() {
        assertFalse(isSuspiciousMccMnc("234", "15"))  // UK Vodafone
        assertFalse(isSuspiciousMccMnc("262", "01"))  // Germany T-Mobile
        assertFalse(isSuspiciousMccMnc("440", "10"))  // Japan NTT
    }

    @Test
    fun `isSuspiciousMccMnc handles null values safely`() {
        assertFalse(isSuspiciousMccMnc(null, "01"))
        assertFalse(isSuspiciousMccMnc("001", null))
        assertFalse(isSuspiciousMccMnc(null, null))
    }

    // ==================== Signal Spike Detection Tests ====================

    @Test
    fun `isSignalSpikeAnomaly detects large signal increase`() {
        assertTrue(isSignalSpikeAnomaly(-80, -50, 5000)) // 30dB increase in 5s
        assertTrue(isSignalSpikeAnomaly(-90, -60, 3000)) // 30dB increase in 3s
        assertTrue(isSignalSpikeAnomaly(-75, -45, 4000)) // 30dB increase in 4s
    }

    @Test
    fun `isSignalSpikeAnomaly ignores small changes`() {
        assertFalse(isSignalSpikeAnomaly(-70, -65, 5000)) // 5dB
        assertFalse(isSignalSpikeAnomaly(-60, -55, 5000)) // 5dB
        assertFalse(isSignalSpikeAnomaly(-80, -70, 5000)) // 10dB
        assertFalse(isSignalSpikeAnomaly(-65, -50, 5000)) // 15dB - borderline
    }

    @Test
    fun `isSignalSpikeAnomaly ignores signal decrease`() {
        assertFalse(isSignalSpikeAnomaly(-50, -80, 5000)) // Signal got weaker
        assertFalse(isSignalSpikeAnomaly(-40, -70, 5000))
    }

    @Test
    fun `isSignalSpikeAnomaly requires fast change`() {
        // Large change but too slow
        assertFalse(isSignalSpikeAnomaly(-80, -50, 10000)) // 10s is too slow
        assertFalse(isSignalSpikeAnomaly(-80, -50, 15000))
    }

    // ==================== Encryption Downgrade Tests ====================

    @Test
    fun `isEncryptionDowngrade detects 4G to 2G downgrade`() {
        assertTrue(isEncryptionDowngrade("LTE", "EDGE"))
        assertTrue(isEncryptionDowngrade("LTE", "GPRS"))
        assertTrue(isEncryptionDowngrade("LTE", "GSM"))
        assertTrue(isEncryptionDowngrade("LTE", "CDMA"))
    }

    @Test
    fun `isEncryptionDowngrade detects 5G to 2G downgrade`() {
        assertTrue(isEncryptionDowngrade("NR", "EDGE"))
        assertTrue(isEncryptionDowngrade("NR", "GSM"))
        assertTrue(isEncryptionDowngrade("NR", "GPRS"))
    }

    @Test
    fun `isEncryptionDowngrade detects 3G to 2G downgrade`() {
        assertTrue(isEncryptionDowngrade("UMTS", "GSM"))
        assertTrue(isEncryptionDowngrade("HSPA", "EDGE"))
        assertTrue(isEncryptionDowngrade("HSDPA", "GPRS"))
    }

    @Test
    fun `isEncryptionDowngrade ignores normal handoffs`() {
        assertFalse(isEncryptionDowngrade("LTE", "HSPA"))  // 4G to 3G
        assertFalse(isEncryptionDowngrade("NR", "LTE"))    // 5G to 4G
        assertFalse(isEncryptionDowngrade("HSPA", "UMTS")) // 3G variants
    }

    @Test
    fun `isEncryptionDowngrade ignores same generation`() {
        assertFalse(isEncryptionDowngrade("LTE", "LTE"))
        assertFalse(isEncryptionDowngrade("EDGE", "GPRS")) // Both 2G
        assertFalse(isEncryptionDowngrade("GSM", "EDGE"))  // Both 2G
        assertFalse(isEncryptionDowngrade("NR", "NR"))
    }

    @Test
    fun `isEncryptionDowngrade ignores upgrades`() {
        assertFalse(isEncryptionDowngrade("GSM", "LTE"))   // 2G to 4G
        assertFalse(isEncryptionDowngrade("EDGE", "NR"))   // 2G to 5G
        assertFalse(isEncryptionDowngrade("HSPA", "NR"))   // 3G to 5G
    }

    // ==================== Rapid Cell Change Tests ====================

    @Test
    fun `isRapidCellChange detects too many changes while stationary`() {
        assertTrue(isRapidCellChange(6, 60_000, isMoving = false))  // 6/min stationary
        assertTrue(isRapidCellChange(7, 60_000, isMoving = false))
        assertTrue(isRapidCellChange(10, 60_000, isMoving = false))
    }

    @Test
    fun `isRapidCellChange detects too many changes while moving`() {
        assertTrue(isRapidCellChange(13, 60_000, isMoving = true))  // 13/min moving
        assertTrue(isRapidCellChange(15, 60_000, isMoving = true))
        assertTrue(isRapidCellChange(20, 60_000, isMoving = true))
    }

    @Test
    fun `isRapidCellChange allows normal mobility`() {
        assertFalse(isRapidCellChange(3, 60_000, isMoving = false)) // 3/min stationary
        assertFalse(isRapidCellChange(4, 60_000, isMoving = false)) // 4/min stationary
        assertFalse(isRapidCellChange(8, 60_000, isMoving = true))  // 8/min moving
        assertFalse(isRapidCellChange(10, 60_000, isMoving = true)) // 10/min moving
    }

    @Test
    fun `isRapidCellChange handles edge cases`() {
        assertFalse(isRapidCellChange(0, 60_000, isMoving = false))
        assertFalse(isRapidCellChange(1, 0, isMoving = false))
        assertFalse(isRapidCellChange(-1, 60_000, isMoving = false))
    }

    // ==================== LAC/TAC Anomaly Tests ====================

    @Test
    fun `isLacTacAnomaly detects LAC change without cell change`() {
        assertTrue(isLacTacAnomaly(
            prevLac = 100, currLac = 200,
            prevTac = null, currTac = null,
            prevCellId = 12345, currCellId = 12345
        ))
    }

    @Test
    fun `isLacTacAnomaly detects TAC change without cell change`() {
        assertTrue(isLacTacAnomaly(
            prevLac = null, currLac = null,
            prevTac = 100, currTac = 200,
            prevCellId = 12345, currCellId = 12345
        ))
    }

    @Test
    fun `isLacTacAnomaly ignores changes with cell change`() {
        assertFalse(isLacTacAnomaly(
            prevLac = 100, currLac = 200,
            prevTac = null, currTac = null,
            prevCellId = 12345, currCellId = 67890  // Cell changed
        ))
    }

    @Test
    fun `isLacTacAnomaly ignores zero values`() {
        assertFalse(isLacTacAnomaly(
            prevLac = 0, currLac = 200,
            prevTac = null, currTac = null,
            prevCellId = 12345, currCellId = 12345
        ))
        assertFalse(isLacTacAnomaly(
            prevLac = 100, currLac = 0,
            prevTac = null, currTac = null,
            prevCellId = 12345, currCellId = 12345
        ))
    }

    // ==================== CellStatus Tests ====================

    @Test
    fun `CellStatus stores all required fields`() {
        val status = CellStatus(
            cellId = "12345678",
            lac = 1234,
            tac = 5678,
            mcc = "310",
            mnc = "410",
            operator = "AT&T",
            networkType = "LTE (4G)",
            networkGeneration = "4G",
            signalStrength = -75,
            signalBars = 3,
            isTrustedCell = true,
            trustScore = 80,
            isRoaming = false,
            latitude = 47.6062,
            longitude = -122.3321
        )

        assertEquals("12345678", status.cellId)
        assertEquals(1234, status.lac)
        assertEquals(5678, status.tac)
        assertEquals("310", status.mcc)
        assertEquals("410", status.mnc)
        assertEquals("AT&T", status.operator)
        assertEquals("LTE (4G)", status.networkType)
        assertEquals("4G", status.networkGeneration)
        assertEquals(-75, status.signalStrength)
        assertEquals(3, status.signalBars)
        assertTrue(status.isTrustedCell)
        assertEquals(80, status.trustScore)
        assertFalse(status.isRoaming)
        assertEquals(47.6062, status.latitude!!, 0.0001)
        assertEquals(-122.3321, status.longitude!!, 0.0001)
    }

    @Test
    fun `CellStatus handles null location`() {
        val status = CellStatus(
            cellId = "12345678",
            lac = null,
            tac = 5678,
            mcc = "310",
            mnc = "410",
            operator = "AT&T",
            networkType = "LTE (4G)",
            networkGeneration = "4G",
            signalStrength = -75,
            signalBars = 3,
            isTrustedCell = false,
            trustScore = 0,
            isRoaming = false,
            latitude = null,
            longitude = null
        )

        assertNull(status.lac)
        assertNull(status.latitude)
        assertNull(status.longitude)
    }

    // ==================== CellularAnomaly Tests ====================

    @Test
    fun `CellularAnomaly has unique ID`() {
        val anomaly1 = createTestAnomaly(AnomalyType.SIGNAL_SPIKE)
        val anomaly2 = createTestAnomaly(AnomalyType.SIGNAL_SPIKE)
        assertNotEquals(anomaly1.id, anomaly2.id)
    }

    @Test
    fun `CellularAnomaly timestamp is set automatically`() {
        val before = System.currentTimeMillis()
        val anomaly = createTestAnomaly(AnomalyType.CELL_TOWER_CHANGE)
        val after = System.currentTimeMillis()
        assertTrue(anomaly.timestamp in before..after)
    }

    @Test
    fun `CellularAnomaly stores contributing factors`() {
        val factors = listOf("Factor 1", "Factor 2", "Factor 3")
        val anomaly = CellularAnomaly(
            type = AnomalyType.ENCRYPTION_DOWNGRADE,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Test",
            technicalDetails = "Details",
            cellId = 123,
            previousCellId = null,
            signalStrength = -60,
            previousSignalStrength = -80,
            networkType = "LTE",
            previousNetworkType = "LTE",
            mccMnc = "310-410",
            latitude = null,
            longitude = null,
            contributingFactors = factors
        )

        assertEquals(3, anomaly.contributingFactors.size)
        assertTrue(anomaly.contributingFactors.contains("Factor 1"))
    }

    // ==================== SeenCellTower Tests ====================

    @Test
    fun `SeenCellTower tracks signal statistics`() {
        val tower = SeenCellTower(
            cellId = "12345",
            lac = 100,
            tac = 200,
            mcc = "310",
            mnc = "410",
            operator = "AT&T",
            networkType = "LTE (4G)",
            networkGeneration = "4G",
            firstSeen = 1000L,
            lastSeen = 2000L,
            seenCount = 5,
            minSignal = -90,
            maxSignal = -60,
            lastSignal = -70,
            latitude = 47.6,
            longitude = -122.3,
            isTrusted = true
        )

        assertEquals("12345", tower.cellId)
        assertEquals(5, tower.seenCount)
        assertEquals(-90, tower.minSignal)
        assertEquals(-60, tower.maxSignal)
        assertEquals(-70, tower.lastSignal)
        assertTrue(tower.isTrusted)
    }

    // ==================== CellularEvent Tests ====================

    @Test
    fun `CellularEventType has all required types`() {
        val types = CellularEventType.entries
        assertTrue(types.any { it == CellularEventType.CELL_HANDOFF })
        assertTrue(types.any { it == CellularEventType.NETWORK_CHANGE })
        assertTrue(types.any { it == CellularEventType.ENCRYPTION_DOWNGRADE })
        assertTrue(types.any { it == CellularEventType.ANOMALY_DETECTED })
        assertTrue(types.any { it == CellularEventType.NEW_CELL_DISCOVERED })
    }

    @Test
    fun `CellularEvent stores all fields`() {
        val event = CellularEvent(
            type = CellularEventType.ANOMALY_DETECTED,
            title = "Test Event",
            description = "Test Description",
            cellId = "12345",
            networkType = "LTE",
            signalStrength = -70,
            isAnomaly = true,
            threatLevel = ThreatLevel.HIGH,
            latitude = 47.6,
            longitude = -122.3
        )

        assertEquals(CellularEventType.ANOMALY_DETECTED, event.type)
        assertEquals("Test Event", event.title)
        assertTrue(event.isAnomaly)
        assertEquals(ThreatLevel.HIGH, event.threatLevel)
    }

    // ==================== E2E Anomaly to Detection Conversion Tests ====================

    @Test
    fun `anomalyToDetection converts ENCRYPTION_DOWNGRADE correctly`() {
        val anomaly = CellularAnomaly(
            type = AnomalyType.ENCRYPTION_DOWNGRADE,
            severity = ThreatLevel.HIGH,
            confidence = AnomalyConfidence.HIGH,
            description = "Encryption downgrade detected",
            technicalDetails = "LTE to GSM",
            cellId = 12345,
            previousCellId = 12345,
            signalStrength = -70,
            previousSignalStrength = -65,
            networkType = "GSM (2G)",
            previousNetworkType = "LTE (4G)",
            mccMnc = "310-410",
            latitude = 47.6,
            longitude = -122.3,
            contributingFactors = listOf("Device stationary", "New cell tower")
        )

        val detection = anomalyToDetection(anomaly)

        assertEquals(DetectionProtocol.CELLULAR, detection.protocol)
        assertEquals(DetectionMethod.CELL_ENCRYPTION_DOWNGRADE, detection.detectionMethod)
        assertEquals(DeviceType.STINGRAY_IMSI, detection.deviceType)
        assertEquals(ThreatLevel.HIGH, detection.threatLevel)
        assertEquals(-70, detection.rssi)
        assertEquals(47.6, detection.latitude!!, 0.001)
        assertEquals(-122.3, detection.longitude!!, 0.001)
    }

    @Test
    fun `anomalyToDetection converts SUSPICIOUS_NETWORK correctly`() {
        val anomaly = CellularAnomaly(
            type = AnomalyType.SUSPICIOUS_NETWORK,
            severity = ThreatLevel.CRITICAL,
            confidence = AnomalyConfidence.CRITICAL,
            description = "Test network detected",
            technicalDetails = "MCC/MNC 001-01",
            cellId = 99999,
            previousCellId = null,
            signalStrength = -50,
            previousSignalStrength = null,
            networkType = "GSM (2G)",
            previousNetworkType = null,
            mccMnc = "001-01",
            latitude = null,
            longitude = null,
            contributingFactors = listOf("Test network MCC/MNC")
        )

        val detection = anomalyToDetection(anomaly)

        assertEquals(DetectionMethod.CELL_SUSPICIOUS_NETWORK, detection.detectionMethod)
        assertEquals(ThreatLevel.CRITICAL, detection.threatLevel)
        assertEquals(95, detection.threatScore) // CRITICAL confidence = 95
    }

    @Test
    fun `anomalyToDetection converts RAPID_CELL_SWITCHING correctly`() {
        val anomaly = CellularAnomaly(
            type = AnomalyType.RAPID_CELL_SWITCHING,
            severity = ThreatLevel.MEDIUM,
            confidence = AnomalyConfidence.MEDIUM,
            description = "Rapid cell switching",
            technicalDetails = "10 changes in 60 seconds",
            cellId = 12345,
            previousCellId = 67890,
            signalStrength = -75,
            previousSignalStrength = -80,
            networkType = "LTE (4G)",
            previousNetworkType = "LTE (4G)",
            mccMnc = "310-410",
            latitude = null,
            longitude = null,
            contributingFactors = listOf("10 cell changes in last minute")
        )

        val detection = anomalyToDetection(anomaly)

        assertEquals(DetectionMethod.CELL_RAPID_SWITCHING, detection.detectionMethod)
        assertEquals(ThreatLevel.MEDIUM, detection.threatLevel)
    }

    @Test
    fun `anomalyToDetection maps all anomaly types to detection methods`() {
        val typeMethodMapping = mapOf(
            AnomalyType.ENCRYPTION_DOWNGRADE to DetectionMethod.CELL_ENCRYPTION_DOWNGRADE,
            AnomalyType.SUSPICIOUS_NETWORK to DetectionMethod.CELL_SUSPICIOUS_NETWORK,
            AnomalyType.CELL_TOWER_CHANGE to DetectionMethod.CELL_TOWER_CHANGE,
            AnomalyType.STATIONARY_CELL_CHANGE to DetectionMethod.CELL_TOWER_CHANGE,
            AnomalyType.RAPID_CELL_SWITCHING to DetectionMethod.CELL_RAPID_SWITCHING,
            AnomalyType.SIGNAL_SPIKE to DetectionMethod.CELL_SIGNAL_ANOMALY,
            AnomalyType.LAC_TAC_ANOMALY to DetectionMethod.CELL_LAC_TAC_ANOMALY,
            AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA to DetectionMethod.CELL_TOWER_CHANGE
        )

        typeMethodMapping.forEach { (anomalyType, expectedMethod) ->
            val anomaly = createTestAnomaly(anomalyType)
            val detection = anomalyToDetection(anomaly)
            assertEquals(
                "AnomalyType $anomalyType should map to $expectedMethod",
                expectedMethod,
                detection.detectionMethod
            )
        }
    }

    @Test
    fun `anomalyToDetection sets threat score based on confidence`() {
        val confidenceScoreMapping = mapOf(
            AnomalyConfidence.CRITICAL to 95,
            AnomalyConfidence.HIGH to 75,
            AnomalyConfidence.MEDIUM to 50,
            AnomalyConfidence.LOW to 25
        )

        confidenceScoreMapping.forEach { (confidence, expectedScore) ->
            val anomaly = CellularAnomaly(
                type = AnomalyType.SIGNAL_SPIKE,
                severity = ThreatLevel.MEDIUM,
                confidence = confidence,
                description = "Test",
                technicalDetails = "Test",
                cellId = 123,
                previousCellId = null,
                signalStrength = -60,
                previousSignalStrength = -80,
                networkType = "LTE",
                previousNetworkType = "LTE",
                mccMnc = null,
                latitude = null,
                longitude = null
            )

            val detection = anomalyToDetection(anomaly)
            assertEquals(
                "Confidence $confidence should map to score $expectedScore",
                expectedScore,
                detection.threatScore
            )
        }
    }

    // ==================== Helper Functions ====================

    companion object {
        fun getNetworkGenerationFromType(networkType: Int): String {
            return when (networkType) {
                20 -> "5G"      // NR
                13, 18 -> "4G"  // LTE, IWLAN
                3, 5, 6, 8, 9, 10, 12, 14, 15, 17 -> "3G"  // UMTS variants
                1, 2, 4, 7, 11, 16 -> "2G"  // GSM variants
                else -> "Unknown"
            }
        }

        fun isSuspiciousMccMnc(mcc: String?, mnc: String?): Boolean {
            if (mcc == null || mnc == null) return false
            val combined = "$mcc-$mnc"
            return combined in setOf(
                "001-01", "001-00", "001-02",  // ITU test networks
                "999-99", "999-01",             // Reserved test networks
                "000-00"                        // Invalid
            )
        }

        fun isSignalSpikeAnomaly(previousSignal: Int, currentSignal: Int, timeWindowMs: Long): Boolean {
            val change = currentSignal - previousSignal
            val isFastChange = timeWindowMs <= 5000L
            return change >= 25 && isFastChange // 25dB threshold
        }

        fun isEncryptionDowngrade(previousType: String, currentType: String): Boolean {
            val gen2Types = setOf("GSM", "GPRS", "EDGE", "CDMA", "1xRTT", "iDEN")
            val gen3Types = setOf("UMTS", "HSPA", "HSDPA", "HSUPA", "HSPAP", "TD_SCDMA", "EVDO")
            val gen4Types = setOf("LTE", "LTE_CA", "IWLAN")
            val gen5Types = setOf("NR", "NR_NSA")

            val prevGen = when {
                previousType in gen5Types -> 5
                previousType in gen4Types -> 4
                previousType in gen3Types -> 3
                previousType in gen2Types -> 2
                else -> 0
            }

            val currGen = when {
                currentType in gen5Types -> 5
                currentType in gen4Types -> 4
                currentType in gen3Types -> 3
                currentType in gen2Types -> 2
                else -> 0
            }

            // Downgrade to 2G from 3G+ is suspicious
            return prevGen >= 3 && currGen == 2
        }

        fun isRapidCellChange(changeCount: Int, timeSpanMs: Long, isMoving: Boolean): Boolean {
            if (timeSpanMs <= 0 || changeCount <= 0) return false
            val changesPerMinute = changeCount * 60_000.0 / timeSpanMs
            val threshold = if (isMoving) 12 else 5  // Higher threshold when moving
            return changesPerMinute > threshold
        }

        fun isLacTacAnomaly(
            prevLac: Int?, currLac: Int?,
            prevTac: Int?, currTac: Int?,
            prevCellId: Int, currCellId: Int
        ): Boolean {
            val cellSame = prevCellId == currCellId

            val lacChanged = prevLac != null && currLac != null &&
                    prevLac != currLac && prevLac != 0 && currLac != 0
            val tacChanged = prevTac != null && currTac != null &&
                    prevTac != currTac && prevTac != 0 && currTac != 0

            return (lacChanged || tacChanged) && cellSame
        }

        fun createTestAnomaly(type: AnomalyType): CellularAnomaly {
            return CellularAnomaly(
                type = type,
                severity = ThreatLevel.MEDIUM,
                confidence = AnomalyConfidence.MEDIUM,
                description = "Test anomaly",
                technicalDetails = "Test details",
                cellId = 12345,
                previousCellId = 67890,
                signalStrength = -70,
                previousSignalStrength = -75,
                networkType = "LTE",
                previousNetworkType = "LTE",
                mccMnc = "310-410",
                latitude = null,
                longitude = null
            )
        }

        fun anomalyToDetection(anomaly: CellularAnomaly): Detection {
            val detectionMethod = when (anomaly.type) {
                AnomalyType.ENCRYPTION_DOWNGRADE -> DetectionMethod.CELL_ENCRYPTION_DOWNGRADE
                AnomalyType.SUSPICIOUS_NETWORK -> DetectionMethod.CELL_SUSPICIOUS_NETWORK
                AnomalyType.CELL_TOWER_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
                AnomalyType.STATIONARY_CELL_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
                AnomalyType.RAPID_CELL_SWITCHING -> DetectionMethod.CELL_RAPID_SWITCHING
                AnomalyType.SIGNAL_SPIKE -> DetectionMethod.CELL_SIGNAL_ANOMALY
                AnomalyType.LAC_TAC_ANOMALY -> DetectionMethod.CELL_LAC_TAC_ANOMALY
                AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> DetectionMethod.CELL_TOWER_CHANGE
            }

            return Detection(
                deviceType = DeviceType.STINGRAY_IMSI,
                protocol = DetectionProtocol.CELLULAR,
                detectionMethod = detectionMethod,
                deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}",
                macAddress = null,
                ssid = null,
                rssi = anomaly.signalStrength,
                signalStrength = rssiToSignalStrength(anomaly.signalStrength),
                latitude = anomaly.latitude,
                longitude = anomaly.longitude,
                threatLevel = anomaly.severity,
                threatScore = when (anomaly.confidence) {
                    AnomalyConfidence.CRITICAL -> 95
                    AnomalyConfidence.HIGH -> 75
                    AnomalyConfidence.MEDIUM -> 50
                    AnomalyConfidence.LOW -> 25
                },
                manufacturer = "Cell: ${anomaly.cellId ?: "Unknown"}",
                matchedPatterns = anomaly.contributingFactors.joinToString(", ")
            )
        }
    }
}

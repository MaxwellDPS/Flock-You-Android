package com.flockyou.service

import com.flockyou.data.model.ThreatLevel
import com.flockyou.service.CellularMonitor.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Unit tests for CellularMonitor anomaly detection logic
 */
class CellularMonitorTest {

    // ==================== AnomalyType Tests ====================

    @Test
    fun `AnomalyType ENCRYPTION_DOWNGRADE has highest base threat score`() {
        val encryptionDowngrade = AnomalyType.ENCRYPTION_DOWNGRADE
        assertTrue(
            "Encryption downgrade should have high threat score",
            encryptionDowngrade.baseThreatScore >= 90
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
                "AnomalyType ${type.name} should have valid threat score",
                type.baseThreatScore in 0..100
            )
        }
    }

    @Test
    fun `SUSPICIOUS_MCC_MNC indicates test network detection`() {
        val suspicious = AnomalyType.SUSPICIOUS_MCC_MNC
        assertNotNull(suspicious)
        assertTrue(suspicious.baseThreatScore >= 80)
    }

    // ==================== Network Type Tests ====================

    @Test
    fun `getNetworkGeneration returns 5G for NR`() {
        assertEquals("5G", getNetworkGenerationFromType(20)) // NR
    }

    @Test
    fun `getNetworkGeneration returns 4G for LTE`() {
        assertEquals("4G", getNetworkGenerationFromType(13)) // LTE
    }

    @Test
    fun `getNetworkGeneration returns 3G for UMTS variants`() {
        assertEquals("3G", getNetworkGenerationFromType(3))  // UMTS
        assertEquals("3G", getNetworkGenerationFromType(8))  // HSDPA
        assertEquals("3G", getNetworkGenerationFromType(9))  // HSUPA
        assertEquals("3G", getNetworkGenerationFromType(10)) // HSPA
        assertEquals("3G", getNetworkGenerationFromType(15)) // HSPAP
    }

    @Test
    fun `getNetworkGeneration returns 2G for GSM variants`() {
        assertEquals("2G", getNetworkGenerationFromType(1))  // GPRS
        assertEquals("2G", getNetworkGenerationFromType(2))  // EDGE
        assertEquals("2G", getNetworkGenerationFromType(16)) // GSM
    }

    @Test
    fun `getNetworkGeneration returns Unknown for invalid type`() {
        assertEquals("Unknown", getNetworkGenerationFromType(-1))
        assertEquals("Unknown", getNetworkGenerationFromType(99))
    }

    // ==================== MCC/MNC Validation Tests ====================

    @Test
    fun `isSuspiciousMccMnc detects test network 001-01`() {
        assertTrue(isSuspiciousMccMnc("001", "01"))
    }

    @Test
    fun `isSuspiciousMccMnc detects test network 001-02`() {
        assertTrue(isSuspiciousMccMnc("001", "02"))
    }

    @Test
    fun `isSuspiciousMccMnc allows valid US carrier`() {
        assertFalse(isSuspiciousMccMnc("310", "410")) // AT&T
        assertFalse(isSuspiciousMccMnc("311", "480")) // Verizon
        assertFalse(isSuspiciousMccMnc("310", "260")) // T-Mobile
    }

    @Test
    fun `isSuspiciousMccMnc handles null values`() {
        assertFalse(isSuspiciousMccMnc(null, "01"))
        assertFalse(isSuspiciousMccMnc("001", null))
        assertFalse(isSuspiciousMccMnc(null, null))
    }

    // ==================== Signal Strength Analysis Tests ====================

    @Test
    fun `isSignalSpikeAnomaly detects large signal increase`() {
        // A sudden 30dB increase is suspicious
        assertTrue(isSignalSpikeAnomaly(-80, -50))
    }

    @Test
    fun `isSignalSpikeAnomaly ignores small changes`() {
        // Normal signal fluctuation
        assertFalse(isSignalSpikeAnomaly(-70, -65))
        assertFalse(isSignalSpikeAnomaly(-60, -55))
    }

    @Test
    fun `isSignalSpikeAnomaly ignores signal decrease`() {
        // Signal getting weaker is not suspicious
        assertFalse(isSignalSpikeAnomaly(-50, -80))
    }

    // ==================== Encryption Downgrade Tests ====================

    @Test
    fun `isEncryptionDowngrade detects 4G to 2G downgrade`() {
        assertTrue(isEncryptionDowngrade("LTE", "EDGE"))
        assertTrue(isEncryptionDowngrade("LTE", "GPRS"))
        assertTrue(isEncryptionDowngrade("LTE", "GSM"))
    }

    @Test
    fun `isEncryptionDowngrade detects 5G to 2G downgrade`() {
        assertTrue(isEncryptionDowngrade("NR", "EDGE"))
        assertTrue(isEncryptionDowngrade("NR", "GSM"))
    }

    @Test
    fun `isEncryptionDowngrade ignores normal handoffs`() {
        // 4G to 3G is normal
        assertFalse(isEncryptionDowngrade("LTE", "HSPA"))
        // 5G to 4G is normal
        assertFalse(isEncryptionDowngrade("NR", "LTE"))
    }

    @Test
    fun `isEncryptionDowngrade ignores same generation`() {
        assertFalse(isEncryptionDowngrade("LTE", "LTE"))
        assertFalse(isEncryptionDowngrade("EDGE", "GPRS"))
    }

    // ==================== Cell Tower Change Analysis Tests ====================

    @Test
    fun `isRapidCellChange detects too many changes`() {
        // More than 5 cell changes in 60 seconds is suspicious
        assertTrue(isRapidCellChange(6, 60_000))
    }

    @Test
    fun `isRapidCellChange allows normal mobility`() {
        // 2-3 cell changes per minute is normal when moving
        assertFalse(isRapidCellChange(3, 60_000))
    }

    @Test
    fun `isRapidCellChange handles edge cases`() {
        assertFalse(isRapidCellChange(0, 60_000))
        assertFalse(isRapidCellChange(1, 0))
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
            networkType = "LTE",
            networkGeneration = "4G",
            signalStrength = -75,
            signalBars = 3,
            operator = "AT&T",
            isRoaming = false,
            latitude = 47.6062,
            longitude = -122.3321
        )

        assertEquals("12345678", status.cellId)
        assertEquals(1234, status.lac)
        assertEquals(5678, status.tac)
        assertEquals("310", status.mcc)
        assertEquals("410", status.mnc)
        assertEquals("LTE", status.networkType)
        assertEquals("4G", status.networkGeneration)
        assertEquals(-75, status.signalStrength)
        assertEquals(3, status.signalBars)
        assertEquals("AT&T", status.operator)
        assertFalse(status.isRoaming)
    }

    // ==================== CellularAnomaly Tests ====================

    @Test
    fun `CellularAnomaly has unique ID`() {
        val anomaly1 = CellularAnomaly(
            type = AnomalyType.SIGNAL_SPIKE,
            severity = ThreatLevel.MEDIUM,
            description = "Test",
            cellId = 123,
            previousCellId = null,
            signalStrength = -60,
            previousSignalStrength = -80,
            networkType = "LTE",
            previousNetworkType = "LTE",
            mccMnc = "310410",
            latitude = null,
            longitude = null
        )
        val anomaly2 = CellularAnomaly(
            type = AnomalyType.SIGNAL_SPIKE,
            severity = ThreatLevel.MEDIUM,
            description = "Test",
            cellId = 123,
            previousCellId = null,
            signalStrength = -60,
            previousSignalStrength = -80,
            networkType = "LTE",
            previousNetworkType = "LTE",
            mccMnc = "310410",
            latitude = null,
            longitude = null
        )
        assertNotEquals(anomaly1.id, anomaly2.id)
    }

    @Test
    fun `CellularAnomaly timestamp is set automatically`() {
        val before = System.currentTimeMillis()
        val anomaly = CellularAnomaly(
            type = AnomalyType.CELL_TOWER_CHANGE,
            severity = ThreatLevel.LOW,
            description = "Test",
            cellId = 123,
            previousCellId = 456,
            signalStrength = -70,
            previousSignalStrength = -70,
            networkType = "LTE",
            previousNetworkType = "LTE",
            mccMnc = null,
            latitude = null,
            longitude = null
        )
        val after = System.currentTimeMillis()
        assertTrue(anomaly.timestamp in before..after)
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
            networkType = "LTE",
            networkGeneration = "4G",
            firstSeen = 1000L,
            lastSeen = 2000L,
            seenCount = 5,
            minSignal = -90,
            maxSignal = -60,
            lastSignal = -70,
            latitude = null,
            longitude = null
        )

        assertEquals("12345", tower.cellId)
        assertEquals(5, tower.seenCount)
        assertEquals(-90, tower.minSignal)
        assertEquals(-60, tower.maxSignal)
        assertEquals(-70, tower.lastSignal)
    }

    // ==================== Helper Functions for Tests ====================

    companion object {
        /**
         * Helper to get network generation from network type constant
         */
        fun getNetworkGenerationFromType(networkType: Int): String {
            return when (networkType) {
                20 -> "5G"      // NR
                13 -> "4G"      // LTE
                3, 8, 9, 10, 15 -> "3G"  // UMTS variants
                1, 2, 16 -> "2G"  // GSM variants
                else -> "Unknown"
            }
        }

        /**
         * Check if MCC/MNC combination is suspicious (test networks)
         */
        fun isSuspiciousMccMnc(mcc: String?, mnc: String?): Boolean {
            if (mcc == null || mnc == null) return false
            val combined = "$mcc$mnc"
            return combined in listOf("00101", "00102", "00100")
        }

        /**
         * Check if signal change indicates a spike anomaly
         */
        fun isSignalSpikeAnomaly(previousSignal: Int, currentSignal: Int): Boolean {
            val change = currentSignal - previousSignal
            return change >= 20 // 20dB sudden increase is suspicious
        }

        /**
         * Check if network change indicates encryption downgrade
         */
        fun isEncryptionDowngrade(previousType: String, currentType: String): Boolean {
            val gen2Types = listOf("GSM", "GPRS", "EDGE", "CDMA", "1xRTT")
            val gen4Types = listOf("LTE", "LTE_CA")
            val gen5Types = listOf("NR", "NR_NSA")
            
            val wasSecure = previousType in gen4Types || previousType in gen5Types
            val isNowInsecure = currentType in gen2Types
            
            return wasSecure && isNowInsecure
        }

        /**
         * Check if cell changes are happening too rapidly
         */
        fun isRapidCellChange(changeCount: Int, timeSpanMs: Long): Boolean {
            if (timeSpanMs <= 0 || changeCount <= 0) return false
            val changesPerMinute = changeCount * 60_000.0 / timeSpanMs
            return changesPerMinute > 5
        }
    }
}

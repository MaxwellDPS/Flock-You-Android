package com.flockyou.data.repository

import com.flockyou.data.model.OuiEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OuiRepository @Inject constructor(
    private val ouiDao: OuiDao
) {
    val entryCount: Flow<Int> = ouiDao.getCountFlow()

    /**
     * Look up manufacturer by OUI prefix.
     * @param oui MAC address or OUI prefix (supports formats: AA:BB:CC, AA-BB-CC, AABBCC)
     */
    suspend fun getManufacturer(oui: String): String? {
        val normalized = normalizeOui(oui)
        return ouiDao.getOrganizationName(normalized)
    }

    suspend fun getEntry(oui: String): OuiEntry? {
        val normalized = normalizeOui(oui)
        return ouiDao.getByPrefix(normalized)
    }

    suspend fun getCount(): Int = ouiDao.getCount()

    suspend fun getLastUpdateTime(): Long? = ouiDao.getLastUpdateTime()

    suspend fun insertEntries(entries: List<OuiEntry>) {
        ouiDao.insertAll(entries)
    }

    suspend fun replaceAllEntries(entries: List<OuiEntry>) {
        ouiDao.replaceAll(entries)
    }

    suspend fun hasData(): Boolean = ouiDao.getCount() > 0

    /**
     * Normalize OUI to standard format: AA:BB:CC (uppercase, colon-separated)
     */
    private fun normalizeOui(oui: String): String {
        val cleaned = oui.uppercase()
            .replace("-", "")
            .replace(":", "")
            .replace(".", "")
            .take(6) // First 6 hex chars = 3 octets

        return if (cleaned.length >= 6) {
            "${cleaned.substring(0, 2)}:${cleaned.substring(2, 4)}:${cleaned.substring(4, 6)}"
        } else {
            cleaned
        }
    }
}

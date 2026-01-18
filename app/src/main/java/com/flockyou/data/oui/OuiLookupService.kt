package com.flockyou.data.oui

import com.flockyou.data.model.DetectionPatterns
import com.flockyou.data.repository.OuiRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for OUI manufacturer lookup.
 * Falls back to hardcoded patterns if database is empty or lookup fails.
 */
@Singleton
class OuiLookupService @Inject constructor(
    private val ouiRepository: OuiRepository
) {
    /**
     * Look up manufacturer by MAC address or OUI prefix.
     * Priority:
     * 1. Database lookup (IEEE OUI data)
     * 2. Fallback to hardcoded ouiManufacturers map in DetectionPatterns
     * 3. Fallback to macPrefixes in DetectionPatterns
     */
    suspend fun getManufacturer(oui: String): String? {
        // Normalize input
        val normalizedOui = oui.uppercase().replace("-", ":").take(8)

        // Try database first
        val dbResult = try {
            ouiRepository.getManufacturer(normalizedOui)
        } catch (e: Exception) {
            null
        }

        if (!dbResult.isNullOrBlank()) {
            return dbResult
        }

        // Fallback to hardcoded patterns
        return DetectionPatterns.getManufacturerFromOui(normalizedOui)
    }

    /**
     * Check if database has been populated with OUI data.
     */
    suspend fun isDatabasePopulated(): Boolean {
        return try {
            ouiRepository.hasData()
        } catch (e: Exception) {
            false
        }
    }
}

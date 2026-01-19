package com.flockyou.utils

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Common helper functions for E2E tests.
 */
object TestHelpers {

    /**
     * Get the test application context.
     */
    fun getContext(): Context {
        return InstrumentationRegistry.getInstrumentation().targetContext
    }

    /**
     * Wait for a condition to become true with timeout.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @param intervalMs Check interval in milliseconds
     * @param condition The condition to wait for
     * @return true if condition became true, false if timeout
     */
    suspend fun waitForCondition(
        timeoutMs: Long = 5000L,
        intervalMs: Long = 100L,
        condition: suspend () -> Boolean
    ): Boolean {
        return try {
            withTimeout(timeoutMs) {
                while (!condition()) {
                    delay(intervalMs)
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear all app data for a fresh test state.
     */
    fun clearAppData(context: Context) {
        // Clear databases
        context.databaseList().forEach { dbName ->
            context.deleteDatabase(dbName)
        }

        // Clear shared preferences
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (prefsDir.exists() && prefsDir.isDirectory) {
            prefsDir.listFiles()?.forEach { it.delete() }
        }

        // Clear DataStore
        val datastoreDir = File(context.filesDir, "datastore")
        if (datastoreDir.exists()) {
            datastoreDir.deleteRecursively()
        }

        // Clear cache
        context.cacheDir.deleteRecursively()
    }

    /**
     * Check if a file exists and has content.
     */
    fun fileExistsWithContent(file: File): Boolean {
        return file.exists() && file.isFile && file.length() > 0
    }

    /**
     * Verify database encryption by checking if file is not plain text.
     * SQLCipher encrypted databases start with specific magic bytes.
     */
    fun isDatabaseEncrypted(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() < 16) return false

        return dbFile.inputStream().use { input ->
            val header = ByteArray(16)
            val read = input.read(header)
            if (read < 16) return false

            // SQLCipher encrypted databases have specific header
            // Plain SQLite databases start with "SQLite format 3\u0000"
            val sqliteHeader = "SQLite format 3\u0000".toByteArray()
            !header.take(sqliteHeader.size).toByteArray().contentEquals(sqliteHeader)
        }
    }

    /**
     * Calculate the distance between two geographic coordinates in meters.
     */
    fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val earthRadius = 6371000f // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c.toFloat()
    }

    /**
     * Simulate time passing for testing time-based features.
     */
    suspend fun simulateTimePass(seconds: Long) {
        delay(seconds * 1000)
    }

    /**
     * Generate a random MAC address for testing.
     */
    fun generateRandomMacAddress(): String {
        return (0..5).joinToString(":") {
            String.format("%02X", (0..255).random())
        }
    }

    /**
     * Generate a random SSID for testing.
     */
    fun generateRandomSsid(): String {
        val prefixes = listOf("TestNet", "Network", "WiFi", "AP")
        val suffix = (1000..9999).random()
        return "${prefixes.random()}-$suffix"
    }
}

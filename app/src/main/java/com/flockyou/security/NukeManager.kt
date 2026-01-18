package com.flockyou.security

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.flockyou.data.NukeSettings
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.data.repository.FlockYouDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trigger source for nuke operations - used for logging and analytics.
 */
enum class NukeTriggerSource {
    USB_CONNECTION,
    ADB_DETECTED,
    FAILED_AUTH,
    DEAD_MAN_SWITCH,
    NETWORK_ISOLATION,
    SIM_REMOVAL,
    RAPID_REBOOT,
    GEOFENCE,
    DURESS_PIN,
    MANUAL
}

/**
 * Result of a nuke operation.
 */
data class NukeResult(
    val success: Boolean,
    val databaseWiped: Boolean,
    val settingsWiped: Boolean,
    val cacheWiped: Boolean,
    val errorMessage: String? = null,
    val triggerSource: NukeTriggerSource
)

/**
 * Manages secure data destruction (nuke) operations.
 *
 * This class handles the complete secure wipe of app data including:
 * - Detection database (SQLCipher encrypted)
 * - App settings/preferences (DataStore)
 * - Cached files
 *
 * When secure wipe is enabled, data is overwritten multiple times before deletion.
 */
@Singleton
class NukeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nukeSettingsRepository: NukeSettingsRepository
) {
    companion object {
        private const val TAG = "NukeManager"

        // Database file names
        private const val DATABASE_NAME = "flockyou_database_encrypted"
        private const val DATABASE_NAME_WAL = "flockyou_database_encrypted-wal"
        private const val DATABASE_NAME_SHM = "flockyou_database_encrypted-shm"
        private const val DATABASE_NAME_JOURNAL = "flockyou_database_encrypted-journal"
    }

    private val secureRandom = SecureRandom()

    /**
     * Execute a nuke operation with the current settings.
     *
     * @param triggerSource The source that triggered the nuke
     * @param settings Optional settings override (uses current settings if null)
     * @return NukeResult with details of what was wiped
     */
    suspend fun executeNuke(
        triggerSource: NukeTriggerSource,
        settings: NukeSettings? = null
    ): NukeResult = withContext(Dispatchers.IO) {
        Log.w(TAG, "NUKE INITIATED - Trigger: $triggerSource")

        val nukeSettings = settings ?: nukeSettingsRepository.settings.first()

        var databaseWiped = false
        var settingsWiped = false
        var cacheWiped = false
        var errorMessage: String? = null

        try {
            // Cancel any pending work
            cancelAllPendingWork()

            // Wipe database
            if (nukeSettings.wipeDatabase) {
                databaseWiped = wipeDatabase(nukeSettings.secureWipe, nukeSettings.secureWipePasses)
            }

            // Wipe cache
            if (nukeSettings.wipeCache) {
                cacheWiped = wipeCache(nukeSettings.secureWipe, nukeSettings.secureWipePasses)
            }

            // Wipe settings (do this last as it contains the nuke settings themselves)
            if (nukeSettings.wipeSettings) {
                settingsWiped = wipeSettings(nukeSettings.secureWipe, nukeSettings.secureWipePasses)
            }

            Log.w(TAG, "NUKE COMPLETE - DB:$databaseWiped, Settings:$settingsWiped, Cache:$cacheWiped")

        } catch (e: Exception) {
            Log.e(TAG, "NUKE ERROR", e)
            errorMessage = e.message
        }

        NukeResult(
            success = databaseWiped || settingsWiped || cacheWiped,
            databaseWiped = databaseWiped,
            settingsWiped = settingsWiped,
            cacheWiped = cacheWiped,
            errorMessage = errorMessage,
            triggerSource = triggerSource
        )
    }

    /**
     * Cancel all pending WorkManager jobs.
     */
    private fun cancelAllPendingWork() {
        try {
            WorkManager.getInstance(context).cancelAllWork()
            Log.d(TAG, "Cancelled all pending work")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel pending work", e)
        }
    }

    /**
     * Wipe the SQLCipher encrypted database.
     * Includes verification that database is properly closed before deletion.
     */
    private suspend fun wipeDatabase(secureWipe: Boolean, passes: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // First, try to close the database properly with verification
            var closedSuccessfully = false
            val maxRetries = 3
            val retryDelayMs = 100L

            for (attempt in 1..maxRetries) {
                try {
                    FlockYouDatabase.getDatabase(context).close()
                    closedSuccessfully = true
                    Log.d(TAG, "Database closed successfully on attempt $attempt")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Database close attempt $attempt failed: ${e.message}")
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(retryDelayMs)
                    }
                }
            }

            if (!closedSuccessfully) {
                Log.w(TAG, "Could not verify database closure after $maxRetries attempts - proceeding with wipe anyway")
            }

            // Give the system a moment to release file handles
            kotlinx.coroutines.delay(50)

            // List of all database-related files
            val databaseFiles = listOf(
                context.getDatabasePath(DATABASE_NAME),
                context.getDatabasePath(DATABASE_NAME_WAL),
                context.getDatabasePath(DATABASE_NAME_SHM),
                context.getDatabasePath(DATABASE_NAME_JOURNAL)
            )

            var success = true
            var filesDeleted = 0
            var filesFailed = 0

            for (file in databaseFiles) {
                if (file.exists()) {
                    val fileDeleted = if (secureWipe) {
                        secureDeleteFile(file, passes)
                    } else {
                        file.delete()
                    }

                    if (fileDeleted) {
                        filesDeleted++
                    } else {
                        filesFailed++
                        success = false
                        Log.e(TAG, "Failed to delete database file: ${file.name}")
                    }
                }
            }

            // Verify files are actually deleted
            val remainingFiles = databaseFiles.filter { it.exists() }
            if (remainingFiles.isNotEmpty()) {
                Log.e(TAG, "Database files still exist after wipe: ${remainingFiles.map { it.name }}")
                success = false
            }

            Log.i(TAG, "Database wipe complete (secure=$secureWipe, passes=$passes): " +
                    "deleted=$filesDeleted, failed=$filesFailed, verified=${remainingFiles.isEmpty()}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wipe database", e)
            false
        }
    }

    /**
     * Wipe all app settings/preferences.
     */
    private suspend fun wipeSettings(secureWipe: Boolean, passes: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val dataDir = context.filesDir.parentFile ?: return@withContext false
            val sharedPrefsDir = File(dataDir, "shared_prefs")
            val datastoreDir = File(context.filesDir, "datastore")

            var success = true

            // Wipe SharedPreferences
            if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
                sharedPrefsDir.listFiles()?.forEach { file ->
                    if (secureWipe) {
                        success = secureDeleteFile(file, passes) && success
                    } else {
                        success = file.delete() && success
                    }
                }
            }

            // Wipe DataStore
            if (datastoreDir.exists() && datastoreDir.isDirectory) {
                success = deleteDirectory(datastoreDir, secureWipe, passes) && success
            }

            Log.i(TAG, "Settings wipe complete (secure=$secureWipe): $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wipe settings", e)
            false
        }
    }

    /**
     * Wipe cache directories.
     */
    private suspend fun wipeCache(secureWipe: Boolean, passes: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            var success = true

            // Internal cache
            context.cacheDir?.let { cacheDir ->
                if (cacheDir.exists()) {
                    success = deleteDirectory(cacheDir, secureWipe, passes) && success
                }
            }

            // External cache (if available)
            context.externalCacheDir?.let { externalCacheDir ->
                if (externalCacheDir.exists()) {
                    success = deleteDirectory(externalCacheDir, secureWipe, passes) && success
                }
            }

            Log.i(TAG, "Cache wipe complete (secure=$secureWipe): $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wipe cache", e)
            false
        }
    }

    /**
     * Recursively delete a directory and its contents.
     */
    private fun deleteDirectory(directory: File, secureWipe: Boolean, passes: Int): Boolean {
        if (!directory.exists()) return true

        var success = true
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                success = deleteDirectory(file, secureWipe, passes) && success
            } else {
                if (secureWipe) {
                    success = secureDeleteFile(file, passes) && success
                } else {
                    success = file.delete() && success
                }
            }
        }

        // Delete the directory itself
        return directory.delete() && success
    }

    /**
     * Securely delete a file by overwriting with random data multiple times.
     *
     * This provides defense against data recovery from storage media.
     * Uses multiple passes with random data and zeros.
     */
    private fun secureDeleteFile(file: File, passes: Int): Boolean {
        if (!file.exists()) return true
        if (!file.isFile) return false

        try {
            val fileLength = file.length()
            if (fileLength == 0L) {
                return file.delete()
            }

            RandomAccessFile(file, "rws").use { raf ->
                val buffer = ByteArray(4096)

                for (pass in 0 until passes) {
                    raf.seek(0)
                    var remaining = fileLength

                    while (remaining > 0) {
                        val toWrite = minOf(buffer.size.toLong(), remaining).toInt()

                        // Alternate between random data and zeros
                        if (pass % 2 == 0) {
                            secureRandom.nextBytes(buffer)
                        } else {
                            buffer.fill(0)
                        }

                        raf.write(buffer, 0, toWrite)
                        remaining -= toWrite
                    }

                    // Sync to disk
                    raf.fd.sync()
                }

                // Final pass with zeros
                raf.seek(0)
                buffer.fill(0)
                var remaining = fileLength
                while (remaining > 0) {
                    val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
                    raf.write(buffer, 0, toWrite)
                    remaining -= toWrite
                }
                raf.fd.sync()
            }

            // Clear the buffer from memory
            return file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to securely delete file: ${file.name}", e)
            // Fall back to simple delete
            return file.delete()
        }
    }

    /**
     * Check if nuke is enabled and should be armed.
     */
    suspend fun isNukeArmed(): Boolean {
        val settings = nukeSettingsRepository.settings.first()
        return settings.nukeEnabled && settings.hasAnyTriggerEnabled()
    }

    /**
     * Quick check to see if nuke system is enabled.
     */
    suspend fun isNukeEnabled(): Boolean {
        return nukeSettingsRepository.settings.first().nukeEnabled
    }
}

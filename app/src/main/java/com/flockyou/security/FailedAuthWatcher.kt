package com.flockyou.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.worker.NukeWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors failed authentication attempts and triggers a nuke after
 * the threshold is exceeded.
 *
 * This provides protection against:
 * - Brute-force PIN attacks
 * - Automated unlock attempts
 * - Forensic tools attempting multiple PINs
 *
 * The counter resets after a configurable time period to prevent
 * accidental nukes from accumulated failed attempts.
 */
@Singleton
class FailedAuthWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nukeSettingsRepository: NukeSettingsRepository,
    private val nukeManager: NukeManager
) {
    companion object {
        private const val TAG = "FailedAuthWatcher"
        private const val PREFS_NAME = "failed_auth_secure_prefs"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_FIRST_FAILURE_TIME = "first_failure_time"
        private const val KEY_NUKE_TRIGGERED = "nuke_triggered"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Encrypted SharedPreferences for storing security-sensitive failed auth state.
     * Uses hardware-backed encryption when available.
     */
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to regular prefs", e)
            // Fallback - this shouldn't happen but better than crashing
            context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    /**
     * Record a failed authentication attempt (fire-and-forget version).
     * Call this whenever PIN/biometric verification fails.
     * This version does not block but also cannot report if a nuke was triggered.
     * Use recordFailedAttemptAsync() if you need to know the result.
     */
    fun recordFailedAttempt() {
        scope.launch {
            recordFailedAttemptInternal()
        }
    }

    /**
     * Record a failed authentication attempt (suspend version).
     * Call this whenever PIN/biometric verification fails.
     *
     * @return true if a nuke was triggered
     */
    suspend fun recordFailedAttemptAsync(): Boolean {
        return recordFailedAttemptInternal()
    }

    /**
     * Internal implementation for recording failed attempts.
     * Performs all checks synchronously within the coroutine.
     */
    private suspend fun recordFailedAttemptInternal(): Boolean {
        val prefs = encryptedPrefs

        // Check if nuke was already triggered (shouldn't happen but safety check)
        if (prefs.getBoolean(KEY_NUKE_TRIGGERED, false)) {
            Log.w(TAG, "Nuke already triggered - ignoring failed attempt")
            return false
        }

        val settings = nukeSettingsRepository.settings.first()

        if (!settings.nukeEnabled || !settings.failedAuthTriggerEnabled) {
            Log.d(TAG, "Failed auth trigger is disabled")
            return false
        }

        val currentTime = System.currentTimeMillis()
        var failedCount = prefs.getInt(KEY_FAILED_COUNT, 0)
        val firstFailureTime = prefs.getLong(KEY_FIRST_FAILURE_TIME, 0)

        // Check if we should reset the counter (time window expired)
        val resetWindowMs = settings.failedAuthResetHours * 3600 * 1000L
        if (firstFailureTime > 0 && currentTime - firstFailureTime > resetWindowMs) {
            Log.d(TAG, "Reset window expired - resetting failed attempt counter")
            prefs.edit()
                .putInt(KEY_FAILED_COUNT, 1)
                .putLong(KEY_FIRST_FAILURE_TIME, currentTime)
                .apply()
            return false
        }

        // Increment counter
        failedCount++
        Log.d(TAG, "Failed auth attempt #$failedCount (threshold: ${settings.failedAuthThreshold})")

        if (firstFailureTime == 0L) {
            // First failure in this window
            prefs.edit()
                .putInt(KEY_FAILED_COUNT, failedCount)
                .putLong(KEY_FIRST_FAILURE_TIME, currentTime)
                .apply()
        } else {
            prefs.edit()
                .putInt(KEY_FAILED_COUNT, failedCount)
                .apply()
        }

        // Check if threshold exceeded
        if (failedCount >= settings.failedAuthThreshold) {
            Log.w(TAG, "FAILED AUTH THRESHOLD EXCEEDED: $failedCount >= ${settings.failedAuthThreshold}")
            prefs.edit()
                .putBoolean(KEY_NUKE_TRIGGERED, true)
                .apply()

            triggerNuke()
            return true
        }

        return false
    }

    /**
     * Record a successful authentication.
     * This resets the failed attempt counter.
     */
    fun recordSuccessfulAuth() {
        val prefs = encryptedPrefs

        // Only reset if nuke hasn't been triggered
        if (!prefs.getBoolean(KEY_NUKE_TRIGGERED, false)) {
            prefs.edit()
                .putInt(KEY_FAILED_COUNT, 0)
                .putLong(KEY_FIRST_FAILURE_TIME, 0)
                .apply()
            Log.d(TAG, "Successful auth - reset failed attempt counter")
        }
    }

    /**
     * Get the current failed attempt count.
     */
    fun getFailedAttemptCount(): Int {
        val prefs = encryptedPrefs
        return prefs.getInt(KEY_FAILED_COUNT, 0)
    }

    /**
     * Get the time of the first failure in the current window.
     */
    fun getFirstFailureTime(): Long {
        val prefs = encryptedPrefs
        return prefs.getLong(KEY_FIRST_FAILURE_TIME, 0)
    }

    /**
     * Check if a nuke has already been triggered.
     */
    fun isNukeTriggered(): Boolean {
        val prefs = encryptedPrefs
        return prefs.getBoolean(KEY_NUKE_TRIGGERED, false)
    }

    /**
     * Reset all state (use with caution - for testing or admin override).
     */
    fun reset() {
        val prefs = encryptedPrefs
        prefs.edit().clear().apply()
        Log.i(TAG, "Failed auth watcher state reset")
    }

    private fun triggerNuke() {
        Log.w(TAG, "Triggering nuke due to failed auth threshold exceeded")
        NukeWorker.scheduleFailedAuthNuke(context)
    }

    /**
     * Get remaining attempts before nuke (if enabled).
     * Returns null if feature is disabled, or the remaining count.
     */
    suspend fun getRemainingAttempts(): Int? {
        val settings = nukeSettingsRepository.settings.first()

        if (!settings.nukeEnabled || !settings.failedAuthTriggerEnabled) {
            return null
        }

        val currentCount = getFailedAttemptCount()
        return maxOf(0, settings.failedAuthThreshold - currentCount)
    }
}

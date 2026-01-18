package com.flockyou.security

import android.content.Context
import android.util.Log
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
        private const val PREFS_NAME = "failed_auth_prefs"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_FIRST_FAILURE_TIME = "first_failure_time"
        private const val KEY_NUKE_TRIGGERED = "nuke_triggered"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Record a failed authentication attempt.
     * Call this whenever PIN/biometric verification fails.
     *
     * @return true if a nuke was triggered
     */
    fun recordFailedAttempt(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check if nuke was already triggered (shouldn't happen but safety check)
        if (prefs.getBoolean(KEY_NUKE_TRIGGERED, false)) {
            Log.w(TAG, "Nuke already triggered - ignoring failed attempt")
            return false
        }

        val currentTime = System.currentTimeMillis()
        var failedCount = prefs.getInt(KEY_FAILED_COUNT, 0)
        val firstFailureTime = prefs.getLong(KEY_FIRST_FAILURE_TIME, 0)

        // Check settings synchronously for immediate response
        var shouldTrigger = false

        scope.launch {
            val settings = nukeSettingsRepository.settings.first()

            if (!settings.nukeEnabled || !settings.failedAuthTriggerEnabled) {
                Log.d(TAG, "Failed auth trigger is disabled")
                return@launch
            }

            // Check if we should reset the counter (time window expired)
            val resetWindowMs = settings.failedAuthResetHours * 3600 * 1000L
            if (firstFailureTime > 0 && currentTime - firstFailureTime > resetWindowMs) {
                Log.d(TAG, "Reset window expired - resetting failed attempt counter")
                prefs.edit()
                    .putInt(KEY_FAILED_COUNT, 1)
                    .putLong(KEY_FIRST_FAILURE_TIME, currentTime)
                    .apply()
                return@launch
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
            }
        }

        return shouldTrigger
    }

    /**
     * Record a successful authentication.
     * This resets the failed attempt counter.
     */
    fun recordSuccessfulAuth() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_FAILED_COUNT, 0)
    }

    /**
     * Get the time of the first failure in the current window.
     */
    fun getFirstFailureTime(): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_FIRST_FAILURE_TIME, 0)
    }

    /**
     * Check if a nuke has already been triggered.
     */
    fun isNukeTriggered(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NUKE_TRIGGERED, false)
    }

    /**
     * Reset all state (use with caution - for testing or admin override).
     */
    fun reset() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

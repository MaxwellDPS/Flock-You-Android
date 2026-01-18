package com.flockyou.service.nuke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.security.NukeManager
import com.flockyou.security.NukeTriggerSource
import com.flockyou.worker.NukeWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver that monitors device boot events and detects
 * suspicious rapid reboot patterns.
 *
 * Forensic tools may force reboots to:
 * - Boot into recovery/fastboot mode
 * - Bypass lock screen timing
 * - Exploit boot-time vulnerabilities
 * - Extract data during early boot
 *
 * Multiple rapid reboots in a short time window indicate potential forensic manipulation.
 */
@AndroidEntryPoint
class BootWatcher : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootWatcher"
        private const val PREFS_NAME = "boot_watcher_prefs"
        private const val KEY_BOOT_TIMES = "boot_times"
        private const val KEY_SUSPICIOUS_COUNT = "suspicious_count"

        /**
         * Initialize boot tracking on app startup.
         */
        fun initialize(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Record current boot time if not already recorded this boot
            val currentBoot = android.os.SystemClock.elapsedRealtime()
            Log.d(TAG, "Initialized boot watcher at uptime: $currentBoot")
        }

        /**
         * Get the current suspicious boot count.
         */
        fun getSuspiciousBootCount(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_SUSPICIOUS_COUNT, 0)
        }

        /**
         * Reset the suspicious boot count.
         */
        fun resetSuspiciousBootCount(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_SUSPICIOUS_COUNT, 0)
                .apply()
            Log.d(TAG, "Reset suspicious boot count")
        }
    }

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    @Inject
    lateinit var nukeManager: NukeManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Boot event received: ${intent.action}")
                // Use goAsync() for proper async handling in BroadcastReceiver
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        handleBootEvent(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private suspend fun handleBootEvent(context: Context) {
        val settings = nukeSettingsRepository.settings.first()

        // Check if rapid reboot trigger is enabled
        if (!settings.nukeEnabled || !settings.rapidRebootTriggerEnabled) {
            Log.d(TAG, "Rapid reboot trigger is disabled")
            recordBootTime(context)
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentBootTime = System.currentTimeMillis()

        // Get previous boot times
        val bootTimesStr = prefs.getString(KEY_BOOT_TIMES, "") ?: ""
        val bootTimes = bootTimesStr.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.toLongOrNull() }
            .toMutableList()

        // Calculate time window
        val windowMs = settings.rapidRebootWindowMinutes * 60 * 1000L
        val windowStart = currentBootTime - windowMs

        // Filter boot times within the window
        val recentBootTimes = bootTimes.filter { it > windowStart }

        Log.d(TAG, "Boot analysis - Current: $currentBootTime, Window: ${settings.rapidRebootWindowMinutes}min, " +
                "Recent boots: ${recentBootTimes.size}, Threshold: ${settings.rapidRebootCount}")

        // Check if we have suspicious rapid reboots
        if (recentBootTimes.size >= settings.rapidRebootCount - 1) {
            // This boot makes it exceed the threshold
            val suspiciousCount = prefs.getInt(KEY_SUSPICIOUS_COUNT, 0) + 1
            prefs.edit()
                .putInt(KEY_SUSPICIOUS_COUNT, suspiciousCount)
                .apply()

            Log.w(TAG, "RAPID REBOOT DETECTED! ${recentBootTimes.size + 1} reboots in ${settings.rapidRebootWindowMinutes} minutes")

            // Trigger the nuke
            triggerNuke(context)
        }

        // Record this boot time
        recordBootTime(context)
    }

    private fun recordBootTime(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentBootTime = System.currentTimeMillis()

        // Get existing boot times
        val bootTimesStr = prefs.getString(KEY_BOOT_TIMES, "") ?: ""
        val bootTimes = bootTimesStr.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.toLongOrNull() }
            .toMutableList()

        // Add current boot time
        bootTimes.add(currentBootTime)

        // Keep only the last 20 boot times (prevent unbounded growth)
        val trimmedBootTimes = bootTimes.takeLast(20)

        // Save back
        prefs.edit()
            .putString(KEY_BOOT_TIMES, trimmedBootTimes.joinToString(","))
            .apply()

        Log.d(TAG, "Recorded boot time: $currentBootTime (total tracked: ${trimmedBootTimes.size})")
    }

    private fun triggerNuke(context: Context) {
        Log.w(TAG, "Executing nuke due to rapid reboot pattern")
        NukeWorker.scheduleRebootNuke(context)
    }
}

package com.flockyou.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.*
import com.flockyou.MainActivity
import com.flockyou.R
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.security.NukeManager
import com.flockyou.security.NukeTriggerSource
import com.flockyou.util.NotificationChannelIds
import com.flockyou.util.NotificationIds
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker that implements the "Dead Man's Switch" - if the user hasn't
 * authenticated within a configured time period, trigger a nuke.
 *
 * This runs periodically to check if the last authentication time has
 * exceeded the configured threshold.
 */
@HiltWorker
class DeadManSwitchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val nukeManager: NukeManager,
    private val nukeSettingsRepository: NukeSettingsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "DeadManSwitchWorker"
        const val WORK_NAME = "dead_man_switch_check"

        // Use centralized notification IDs to prevent conflicts
        private val NOTIFICATION_CHANNEL_ID = NotificationChannelIds.DEAD_MAN_SWITCH
        private val NOTIFICATION_ID = NotificationIds.DEAD_MAN_SWITCH_WARNING

        // Shared preferences keys for tracking authentication time
        private const val PREFS_NAME = "dead_man_switch_prefs_encrypted"
        private const val KEY_LAST_AUTH_TIME = "last_auth_time"
        private const val KEY_WARNING_SHOWN = "warning_shown"
        private const val KEY_AUTH_HMAC = "auth_time_hmac" // HMAC for tamper detection

        /**
         * Get encrypted SharedPreferences for secure storage of dead man's switch data.
         * Throws SecurityException if encryption fails - falling back to unencrypted storage
         * would be a security vulnerability allowing attackers to manipulate the auth time.
         */
        private fun getSecurePrefs(context: Context): SharedPreferences {
            return try {
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
                Log.e(TAG, "CRITICAL: Failed to create EncryptedSharedPreferences", e)
                // Do NOT fall back to unencrypted storage - this would allow attackers
                // to manipulate the last auth time and bypass the dead man's switch
                throw SecurityException(
                    "Dead man's switch requires encrypted storage but encryption failed: ${e.message}",
                    e
                )
            }
        }

        /**
         * Schedule periodic dead man's switch checks.
         */
        fun schedule(context: Context, checkIntervalMinutes: Int = 30) {
            val workRequest = PeriodicWorkRequestBuilder<DeadManSwitchWorker>(
                checkIntervalMinutes.toLong(), TimeUnit.MINUTES,
                15, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false) // Important - must run even on low battery
                        .build()
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            Log.d(TAG, "Scheduled dead man's switch check every $checkIntervalMinutes minutes")
        }

        /**
         * Cancel the dead man's switch.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled dead man's switch")
        }

        /**
         * Record that the user has authenticated.
         * Call this from the lock screen or app unlock.
         * Uses encrypted SharedPreferences for tamper resistance.
         */
        fun recordAuthentication(context: Context) {
            val prefs = getSecurePrefs(context)
            prefs.edit()
                .putLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis())
                .putBoolean(KEY_WARNING_SHOWN, false)
                .apply()
            Log.d(TAG, "Recorded authentication at ${System.currentTimeMillis()}")

            // Dismiss any existing warning notification
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        }

        /**
         * Get the last authentication time.
         * Uses encrypted SharedPreferences for tamper resistance.
         */
        fun getLastAuthTime(context: Context): Long {
            val prefs = getSecurePrefs(context)
            return prefs.getLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis())
        }

        /**
         * Initialize the last auth time if not set.
         * Uses encrypted SharedPreferences for tamper resistance.
         */
        fun initializeIfNeeded(context: Context) {
            val prefs = getSecurePrefs(context)
            if (!prefs.contains(KEY_LAST_AUTH_TIME)) {
                prefs.edit()
                    .putLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis())
                    .apply()
                Log.d(TAG, "Initialized last auth time")
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Dead man's switch check running")

        val settings = nukeSettingsRepository.settings.first()

        // Check if dead man's switch is enabled
        if (!settings.nukeEnabled || !settings.deadManSwitchEnabled) {
            Log.d(TAG, "Dead man's switch is disabled")
            return@withContext Result.success()
        }

        val prefs = getSecurePrefs(applicationContext)
        val lastAuthTime = prefs.getLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis())
        val warningShown = prefs.getBoolean(KEY_WARNING_SHOWN, false)

        val maxAgeMs = settings.deadManSwitchHours * 3600 * 1000L
        val warningAgeMs = (settings.deadManSwitchHours - settings.deadManSwitchWarningHours) * 3600 * 1000L
        val elapsedMs = System.currentTimeMillis() - lastAuthTime

        Log.d(TAG, "Last auth: $lastAuthTime, elapsed: ${elapsedMs / 1000 / 60} minutes, max: ${settings.deadManSwitchHours} hours")

        // Check if we should trigger the nuke
        if (elapsedMs >= maxAgeMs) {
            Log.w(TAG, "DEAD MAN'S SWITCH TRIGGERED - No authentication for ${settings.deadManSwitchHours} hours")

            // Execute the nuke
            val result = nukeManager.executeNuke(NukeTriggerSource.DEAD_MAN_SWITCH)

            if (result.success) {
                Log.w(TAG, "Dead man's switch nuke completed")
            } else {
                Log.e(TAG, "Dead man's switch nuke failed: ${result.errorMessage}")
            }

            return@withContext Result.success()
        }

        // Check if we should show a warning
        if (settings.deadManSwitchWarningEnabled && !warningShown && elapsedMs >= warningAgeMs) {
            val hoursRemaining = ((maxAgeMs - elapsedMs) / (3600 * 1000)).toInt()
            showWarningNotification(hoursRemaining)
            prefs.edit().putBoolean(KEY_WARNING_SHOWN, true).apply()
        }

        Result.success()
    }

    private fun showWarningNotification(hoursRemaining: Int) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required for Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Dead Man's Switch Warning",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Warning before automatic data wipe"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create intent to open the app
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Authentication Required")
            .setContentText("Open the app within $hoursRemaining hours or data will be wiped")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Your dead man's switch will trigger in approximately $hoursRemaining hours. " +
                            "Open the app and authenticate to reset the timer.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Showed warning notification - $hoursRemaining hours remaining")
    }
}

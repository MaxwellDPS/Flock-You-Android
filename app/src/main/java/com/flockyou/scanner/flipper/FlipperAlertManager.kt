package com.flockyou.scanner.flipper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.flockyou.MainActivity
import com.flockyou.R
import com.flockyou.data.model.Detection
import com.flockyou.data.model.ThreatLevel
import com.flockyou.util.NotificationChannelIds
import com.flockyou.util.NotificationGroupKeys
import com.flockyou.util.NotificationIds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages alerts, notifications, haptic feedback, and sounds for Flipper Zero detections.
 *
 * Features:
 * - Configurable haptic feedback with severity-based vibration patterns
 * - Detection severity-based alert sounds
 * - Quick action buttons in notifications (View Details, Dismiss, Mark False Positive)
 * - Respects system sound/vibration settings (silent mode, DND)
 * - Notification grouping for multiple detections
 */
@Singleton
class FlipperAlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: FlipperSettingsRepository
) {
    companion object {
        private const val TAG = "FlipperAlertManager"

        // Intent actions for notification quick actions
        const val ACTION_VIEW_DETECTION = "com.flockyou.ACTION_VIEW_FLIPPER_DETECTION"
        const val ACTION_DISMISS_DETECTION = "com.flockyou.ACTION_DISMISS_FLIPPER_DETECTION"
        const val ACTION_MARK_FALSE_POSITIVE = "com.flockyou.ACTION_MARK_FLIPPER_FP"

        // Intent extras
        const val EXTRA_DETECTION_ID = "extra_detection_id"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Cached settings for quick access
    private var currentSettings = FlipperSettings()

    // Track active notifications for grouping
    private val _activeNotificationIds = MutableStateFlow<Set<Int>>(emptySet())
    val activeNotificationIds: StateFlow<Set<Int>> = _activeNotificationIds.asStateFlow()

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        // Create notification channels
        createNotificationChannels()

        // Observe settings changes
        scope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
            }
        }
    }

    /**
     * Create notification channels for Flipper detection alerts.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Standard detection channel
            val detectionChannel = NotificationChannel(
                NotificationChannelIds.FLIPPER_DETECTION,
                "Flipper Detection Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for surveillance devices detected by Flipper Zero"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }

            // Critical detection channel (can bypass DND)
            val criticalChannel = NotificationChannel(
                NotificationChannelIds.FLIPPER_CRITICAL,
                "Flipper Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical surveillance threat alerts from Flipper Zero"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                setBypassDnd(true)
            }

            notificationManager.createNotificationChannel(detectionChannel)
            notificationManager.createNotificationChannel(criticalChannel)
            Log.d(TAG, "Flipper notification channels created")
        }
    }

    /**
     * Handle a new detection from Flipper Zero.
     * Triggers haptic feedback, plays sound, and shows notification based on settings.
     *
     * @param detection The detection to alert about
     * @param isNew True if this is a newly discovered device, false if it's an update
     */
    fun onDetection(detection: Detection, isNew: Boolean) {
        if (!isNew) return // Only alert on new detections

        scope.launch {
            val settings = settingsRepository.settings.first()

            // Trigger haptic feedback
            if (settings.hapticFeedbackEnabled && shouldHapticForSeverity(detection.threatLevel, settings)) {
                triggerHapticFeedback(detection.threatLevel)
            }

            // Play alert sound
            if (settings.alertSoundsEnabled && !isSilentMode(settings)) {
                playAlertSound(detection.threatLevel, settings)
            }

            // Show notification
            if (settings.notificationsEnabled) {
                showDetectionNotification(detection, settings)
            }
        }
    }

    /**
     * Check if haptic feedback should trigger for the given severity level.
     */
    private fun shouldHapticForSeverity(threatLevel: ThreatLevel, settings: FlipperSettings): Boolean {
        return when (threatLevel) {
            ThreatLevel.CRITICAL -> settings.hapticForCriticalSeverity
            ThreatLevel.HIGH -> settings.hapticForHighSeverity
            ThreatLevel.MEDIUM -> settings.hapticForMediumSeverity
            ThreatLevel.LOW -> settings.hapticForLowSeverity
            ThreatLevel.INFO -> false // No haptic for INFO level
        }
    }

    /**
     * Trigger haptic feedback with a pattern based on severity level.
     */
    private fun triggerHapticFeedback(threatLevel: ThreatLevel) {
        val pattern = when (threatLevel) {
            ThreatLevel.CRITICAL -> FlipperHapticPattern.CRITICAL_LONG
            ThreatLevel.HIGH -> FlipperHapticPattern.HIGH_TRIPLE_BUZZ
            ThreatLevel.MEDIUM -> FlipperHapticPattern.MEDIUM_DOUBLE_BUZZ
            ThreatLevel.LOW -> FlipperHapticPattern.LOW_SINGLE_BUZZ
            ThreatLevel.INFO -> FlipperHapticPattern.LOW_SINGLE_BUZZ
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use amplitudes for more nuanced haptic feedback
                val amplitudes = IntArray(pattern.pattern.size) { index ->
                    if (index % 2 == 0) 0 else when (threatLevel) {
                        ThreatLevel.CRITICAL -> 255
                        ThreatLevel.HIGH -> 220
                        ThreatLevel.MEDIUM -> 180
                        else -> 150
                    }
                }
                val effect = VibrationEffect.createWaveform(pattern.pattern, amplitudes, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern.pattern, -1)
            }
            Log.d(TAG, "Triggered haptic feedback: ${pattern.displayName} for ${threatLevel.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger haptic feedback", e)
        }
    }

    /**
     * Check if the device is in silent or vibrate mode.
     */
    private fun isSilentMode(settings: FlipperSettings): Boolean {
        if (!settings.respectSilentMode) return false

        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT,
            AudioManager.RINGER_MODE_VIBRATE -> true
            else -> false
        }
    }

    /**
     * Play alert sound based on severity level.
     */
    private fun playAlertSound(threatLevel: ThreatLevel, settings: FlipperSettings) {
        val soundSetting = when (threatLevel) {
            ThreatLevel.CRITICAL -> settings.soundForCriticalSeverity
            ThreatLevel.HIGH -> settings.soundForHighSeverity
            ThreatLevel.MEDIUM -> settings.soundForMediumSeverity
            ThreatLevel.LOW -> settings.soundForLowSeverity
            ThreatLevel.INFO -> FlipperAlertSound.SILENT
        }

        if (soundSetting == FlipperAlertSound.SILENT) return

        try {
            val soundUri = when (soundSetting) {
                FlipperAlertSound.SYSTEM_ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                FlipperAlertSound.SYSTEM_NOTIFICATION -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                FlipperAlertSound.SYSTEM_DEFAULT -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                FlipperAlertSound.SILENT -> return
            }

            val ringtone = RingtoneManager.getRingtone(context, soundUri)
            ringtone?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.isLooping = false
                }
                it.play()
                Log.d(TAG, "Playing alert sound: ${soundSetting.displayName} for ${threatLevel.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alert sound", e)
        }
    }

    /**
     * Show a notification for the detection with quick actions.
     */
    private fun showDetectionNotification(detection: Detection, settings: FlipperSettings) {
        val channelId = if (detection.threatLevel == ThreatLevel.CRITICAL) {
            NotificationChannelIds.FLIPPER_CRITICAL
        } else {
            NotificationChannelIds.FLIPPER_DETECTION
        }

        // Create content intent to open detection details
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DETECTION_ID, detection.id)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            detection.id.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(getNotificationTitle(detection))
            .setContentText(getNotificationText(detection))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getNotificationBigText(detection)))
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(getNotificationPriority(detection.threatLevel))
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        // Add quick actions if enabled
        if (settings.showQuickActions) {
            // View Details action
            val viewIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_VIEW_DETECTION
                putExtra(EXTRA_DETECTION_ID, detection.id)
            }
            val viewPendingIntent = PendingIntent.getActivity(
                context,
                detection.id.hashCode() + 1,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_radar,
                "View Details",
                viewPendingIntent
            )

            // Dismiss action (marks as reviewed)
            val dismissIntent = Intent(ACTION_DISMISS_DETECTION).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_DETECTION_ID, detection.id)
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                detection.id.hashCode() + 2,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_warning,
                "Dismiss",
                dismissPendingIntent
            )

            // Mark False Positive action
            val fpIntent = Intent(ACTION_MARK_FALSE_POSITIVE).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_DETECTION_ID, detection.id)
            }
            val fpPendingIntent = PendingIntent.getBroadcast(
                context,
                detection.id.hashCode() + 3,
                fpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_warning,
                "False Positive",
                fpPendingIntent
            )
        }

        // Apply grouping if enabled
        if (settings.groupNotifications) {
            builder.setGroup(NotificationGroupKeys.FLIPPER_DETECTIONS)

            // Update group summary if needed
            updateGroupSummary()
        }

        val notificationId = NotificationIds.FLIPPER_DETECTION_BASE + (detection.id.hashCode() and 0x7FFFFFFF) % 999

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            _activeNotificationIds.value = _activeNotificationIds.value + notificationId
            Log.d(TAG, "Showed notification for detection: ${detection.id}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing notification permission", e)
        }
    }

    /**
     * Update the group summary notification.
     */
    private fun updateGroupSummary() {
        val activeCount = _activeNotificationIds.value.size
        if (activeCount < 2) return

        val summaryBuilder = NotificationCompat.Builder(context, NotificationChannelIds.FLIPPER_DETECTION)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("Flipper Detection Alerts")
            .setContentText("$activeCount surveillance devices detected")
            .setGroup(NotificationGroupKeys.FLIPPER_DETECTIONS)
            .setGroupSummary(true)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(
                NotificationIds.FLIPPER_DETECTION_SUMMARY,
                summaryBuilder.build()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing notification permission for summary", e)
        }
    }

    /**
     * Get notification title based on detection.
     */
    private fun getNotificationTitle(detection: Detection): String {
        val emoji = when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> "🚨"
            ThreatLevel.HIGH -> "⚠️"
            ThreatLevel.MEDIUM -> "🔶"
            ThreatLevel.LOW -> "📡"
            ThreatLevel.INFO -> "ℹ️"
        }
        return "$emoji Flipper: ${detection.threatLevel.displayName} Threat"
    }

    /**
     * Get notification text based on detection.
     */
    private fun getNotificationText(detection: Detection): String {
        val deviceName = detection.deviceName ?: detection.ssid ?: detection.macAddress ?: "Unknown"
        return "${detection.deviceType.displayName}: $deviceName"
    }

    /**
     * Get expanded notification text.
     */
    private fun getNotificationBigText(detection: Detection): String {
        val parts = mutableListOf<String>()
        parts.add("Type: ${detection.deviceType.displayName}")
        detection.deviceName?.let { parts.add("Name: $it") }
        detection.ssid?.let { parts.add("SSID: $it") }
        detection.macAddress?.let { parts.add("MAC: $it") }
        parts.add("Signal: ${detection.signalStrength.displayName} (${detection.rssi} dBm)")
        parts.add("Source: ${detection.detectionSource.displayName}")
        return parts.joinToString("\n")
    }

    /**
     * Get notification priority based on threat level.
     */
    private fun getNotificationPriority(threatLevel: ThreatLevel): Int {
        return when (threatLevel) {
            ThreatLevel.CRITICAL -> NotificationCompat.PRIORITY_MAX
            ThreatLevel.HIGH -> NotificationCompat.PRIORITY_HIGH
            ThreatLevel.MEDIUM -> NotificationCompat.PRIORITY_DEFAULT
            ThreatLevel.LOW -> NotificationCompat.PRIORITY_LOW
            ThreatLevel.INFO -> NotificationCompat.PRIORITY_MIN
        }
    }

    /**
     * Dismiss a notification for a specific detection.
     */
    fun dismissNotification(detectionId: String) {
        val notificationId = NotificationIds.FLIPPER_DETECTION_BASE + (detectionId.hashCode() and 0x7FFFFFFF) % 999
        notificationManager.cancel(notificationId)
        _activeNotificationIds.value = _activeNotificationIds.value - notificationId

        // Update or remove group summary
        if (_activeNotificationIds.value.size < 2) {
            notificationManager.cancel(NotificationIds.FLIPPER_DETECTION_SUMMARY)
        }
    }

    /**
     * Clear all Flipper detection notifications.
     */
    fun clearAllNotifications() {
        _activeNotificationIds.value.forEach { id ->
            notificationManager.cancel(id)
        }
        notificationManager.cancel(NotificationIds.FLIPPER_DETECTION_SUMMARY)
        _activeNotificationIds.value = emptySet()
        Log.d(TAG, "Cleared all Flipper detection notifications")
    }

    /**
     * Test haptic feedback with a specific pattern.
     * Useful for settings UI to let users preview patterns.
     */
    fun testHapticPattern(pattern: FlipperHapticPattern) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern.pattern, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern.pattern, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to test haptic pattern", e)
        }
    }

    /**
     * Test alert sound with a specific sound type.
     */
    fun testAlertSound(sound: FlipperAlertSound) {
        if (sound == FlipperAlertSound.SILENT) return

        try {
            val soundUri = when (sound) {
                FlipperAlertSound.SYSTEM_ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                FlipperAlertSound.SYSTEM_NOTIFICATION -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                FlipperAlertSound.SYSTEM_DEFAULT -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                FlipperAlertSound.SILENT -> return
            }

            val ringtone = RingtoneManager.getRingtone(context, soundUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to test alert sound", e)
        }
    }
}

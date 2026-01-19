package com.flockyou.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flockyou.MainActivity
import com.flockyou.data.model.ThreatLevel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Full-screen emergency alert activity styled after CMAS/WEA (Wireless Emergency Alerts).
 *
 * This activity displays above the lock screen and keyguard, similar to how emergency
 * alerts from the government are displayed. It features:
 * - Pulsating red/orange background
 * - Flashing warning icon
 * - Loud alert tone (respects user settings)
 * - Strong vibration pattern
 * - Cannot be dismissed by back button (must tap OK)
 *
 * Reference: AOSP CellBroadcastAlertDialog
 */
class EmergencyAlertActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_DEVICE_TYPE = "extra_device_type"
        const val EXTRA_THREAT_LEVEL = "extra_threat_level"
        const val EXTRA_DETECTION_ID = "extra_detection_id"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
        const val EXTRA_PLAY_SOUND = "extra_play_sound"
        const val EXTRA_VIBRATE = "extra_vibrate"

        fun createIntent(
            context: Context,
            title: String,
            message: String,
            deviceType: String,
            threatLevel: ThreatLevel,
            detectionId: String,
            playSound: Boolean = true,
            vibrate: Boolean = true
        ): Intent {
            return Intent(context, EmergencyAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_DEVICE_TYPE, deviceType)
                putExtra(EXTRA_THREAT_LEVEL, threatLevel.name)
                putExtra(EXTRA_DETECTION_ID, detectionId)
                putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                putExtra(EXTRA_PLAY_SOUND, playSound)
                putExtra(EXTRA_VIBRATE, vibrate)
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var backgroundAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure window to show above lock screen
        configureWindow()

        // Start alert sound and vibration
        val playSound = intent.getBooleanExtra(EXTRA_PLAY_SOUND, true)
        val vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, true)

        if (playSound) startAlertSound()
        if (vibrate) startVibration()

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "SURVEILLANCE ALERT"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "A surveillance device has been detected."
        val deviceType = intent.getStringExtra(EXTRA_DEVICE_TYPE) ?: "Unknown Device"
        val threatLevelStr = intent.getStringExtra(EXTRA_THREAT_LEVEL) ?: ThreatLevel.CRITICAL.name
        val threatLevel = try { ThreatLevel.valueOf(threatLevelStr) } catch (e: Exception) { ThreatLevel.CRITICAL }
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())

        setContent {
            EmergencyAlertScreen(
                title = title,
                message = message,
                deviceType = deviceType,
                threatLevel = threatLevel,
                timestamp = timestamp,
                onDismiss = { dismissAlert() },
                onViewDetails = { viewDetails() }
            )
        }
    }

    private fun configureWindow() {
        // Show above lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Keep screen on and fullscreen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
    }

    private fun startAlertSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@EmergencyAlertActivity, alarmUri)
                isLooping = true

                // Set volume to max for emergency
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Emergency vibration pattern: long-short-long-short repeating
        val pattern = longArrayOf(0, 1000, 200, 1000, 200, 1000, 500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopAlertSound() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
    }

    private fun stopVibration() {
        vibrator?.cancel()
    }

    private fun dismissAlert() {
        stopAlertSound()
        stopVibration()
        finish()
    }

    private fun viewDetails() {
        stopAlertSound()
        stopVibration()

        // Navigate to main app
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlertSound()
        stopVibration()
        backgroundAnimator?.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent dismissal via back button - user must tap OK
        // This matches CMAS/WEA behavior
    }
}

@Composable
private fun EmergencyAlertScreen(
    title: String,
    message: String,
    deviceType: String,
    threatLevel: ThreatLevel,
    timestamp: Long,
    onDismiss: () -> Unit,
    onViewDetails: () -> Unit
) {
    // Pulsating background animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Flashing icon animation
    val iconAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "iconAlpha"
    )

    val backgroundColor = when (threatLevel) {
        ThreatLevel.CRITICAL -> Color(0xFFB71C1C) // Dark red
        ThreatLevel.HIGH -> Color(0xFFD32F2F) // Red
        ThreatLevel.MEDIUM -> Color(0xFFFF6F00) // Orange
        else -> Color(0xFFE65100) // Deep orange
    }

    val highlightColor = when (threatLevel) {
        ThreatLevel.CRITICAL -> Color(0xFFFF1744) // Bright red
        ThreatLevel.HIGH -> Color(0xFFFF5252) // Light red
        ThreatLevel.MEDIUM -> Color(0xFFFFAB00) // Amber
        else -> Color(0xFFFF9100) // Orange
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = pulseAlpha),
                        Color.Black.copy(alpha = 0.9f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Emergency header bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = highlightColor,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "EMERGENCY ALERT",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Flashing warning icon
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(highlightColor.copy(alpha = iconAlpha * 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    modifier = Modifier.size(80.dp),
                    tint = Color.White.copy(alpha = iconAlpha)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Alert title
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center,
                lineHeight = 32.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Timestamp
            Text(
                text = formatTimestamp(timestamp),
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Device type badge
            Surface(
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = deviceType,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = highlightColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Message body
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(20.dp),
                    fontSize = 18.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Threat level indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Threat Level: ",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Surface(
                    color = highlightColor,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = threatLevel.name,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // View Details button
                OutlinedButton(
                    onClick = onViewDetails,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.horizontalGradient(
                            listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.5f))
                        )
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "VIEW DETAILS",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Dismiss button (OK)
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = backgroundColor
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "OK",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Footer
            Text(
                text = "Flock You - Surveillance Detection",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.4f)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.flockyou.MainActivity
import com.flockyou.R
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
    val infiniteTransition = rememberInfiniteTransition(label = "alerts")

    // Smooth breathing pulse for background
    val breathePulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Subtle glow ring animation
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring"
    )

    // Icon pulse - gentler than before
    val iconPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon"
    )

    // Modern color palette based on threat level
    val accentColor = when (threatLevel) {
        ThreatLevel.CRITICAL -> Color(0xFFFF3D71)
        ThreatLevel.HIGH -> Color(0xFFFF6B6B)
        ThreatLevel.MEDIUM -> Color(0xFFFFAA00)
        else -> Color(0xFFFF9500)
    }

    val accentGlow = when (threatLevel) {
        ThreatLevel.CRITICAL -> Color(0xFFFF1744)
        ThreatLevel.HIGH -> Color(0xFFFF5252)
        ThreatLevel.MEDIUM -> Color(0xFFFFD740)
        else -> Color(0xFFFFAB40)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0F))
    ) {
        // Animated gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.15f + (breathePulse * 0.1f)),
                            Color.Transparent
                        ),
                        radius = 800f
                    )
                )
        )

        // Top accent line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            accentColor,
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Minimal header badge
            Surface(
                color = accentColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Text(
                        text = "ALERT",
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Animated icon with glow rings
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(140.dp)
            ) {
                // Outer glow ring
                Box(
                    modifier = Modifier
                        .size(140.dp * ringScale)
                        .clip(CircleShape)
                        .background(accentGlow.copy(alpha = 0.08f * (2f - ringScale)))
                )
                // Middle ring
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f))
                )
                // Inner circle
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.25f),
                                    accentColor.copy(alpha = 0.1f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = Color.White.copy(alpha = iconPulse)
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Title with better typography
            Text(
                text = title.uppercase(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
                lineHeight = 28.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Timestamp - more subtle
            Text(
                text = formatTimestamp(timestamp),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Device type chip
            Surface(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = deviceType,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Message card - glassmorphism style
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = message,
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Threat level inline
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Threat Level",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                        Surface(
                            color = accentColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = threatLevel.name,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons - modern style
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Primary dismiss button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "Dismiss",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                // Secondary view details button
                OutlinedButton(
                    onClick = onViewDetails,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White.copy(alpha = 0.9f)
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "View Details",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Minimal footer
            Text(
                text = stringResource(R.string.app_title_full_spaced),
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.25f),
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

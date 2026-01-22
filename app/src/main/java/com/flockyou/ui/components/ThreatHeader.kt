package com.flockyou.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.ui.theme.*

/**
 * Get gradient colors for threat level.
 * Returns a pair of (primary color, secondary color) for the gradient.
 */
@Composable
private fun ThreatLevel.toGradientColors(): Pair<Color, Color> = when (this) {
    ThreatLevel.CRITICAL -> Pair(AppColors.ThreatCritical, Color(0xFFB71C1C))
    ThreatLevel.HIGH -> Pair(AppColors.ThreatHigh, Color(0xFFBF360C))
    ThreatLevel.MEDIUM -> Pair(AppColors.ThreatMedium, Color(0xFFFF6F00))
    ThreatLevel.LOW -> Pair(AppColors.ThreatLow, Color(0xFF558B2F))
    ThreatLevel.INFO -> Pair(AppColors.ThreatInfo, Color(0xFF1565C0))
}

/**
 * Get threat context text based on threat level and device type.
 */
private fun getThreatContextText(threatLevel: ThreatLevel, deviceType: DeviceType): String {
    val severityText = when (threatLevel) {
        ThreatLevel.CRITICAL -> "Critical severity"
        ThreatLevel.HIGH -> "High severity"
        ThreatLevel.MEDIUM -> "Medium severity"
        ThreatLevel.LOW -> "Low severity"
        ThreatLevel.INFO -> "Informational"
    }

    val deviceContext = when {
        deviceType in listOf(
            DeviceType.STINGRAY_IMSI,
            DeviceType.CELLEBRITE_FORENSICS,
            DeviceType.GRAYKEY_DEVICE
        ) -> "cell interception device detected"
        deviceType in listOf(
            DeviceType.FLOCK_SAFETY_CAMERA,
            DeviceType.LICENSE_PLATE_READER,
            DeviceType.PENGUIN_SURVEILLANCE,
            DeviceType.PIGVISION_SYSTEM
        ) -> "surveillance camera detected"
        deviceType in listOf(
            DeviceType.RAVEN_GUNSHOT_DETECTOR,
            DeviceType.SHOTSPOTTER,
            DeviceType.ULTRASONIC_BEACON
        ) -> "acoustic sensor detected"
        deviceType in listOf(
            DeviceType.AIRTAG,
            DeviceType.TILE_TRACKER,
            DeviceType.SAMSUNG_SMARTTAG,
            DeviceType.GENERIC_BLE_TRACKER,
            DeviceType.TRACKING_DEVICE
        ) -> "tracking device detected"
        deviceType in listOf(
            DeviceType.FLIPPER_ZERO,
            DeviceType.FLIPPER_ZERO_SPAM,
            DeviceType.WIFI_PINEAPPLE,
            DeviceType.HACKRF_SDR
        ) -> "hacking tool detected"
        deviceType in listOf(
            DeviceType.DRONE,
            DeviceType.HIDDEN_CAMERA,
            DeviceType.HIDDEN_TRANSMITTER
        ) -> "covert surveillance detected"
        deviceType in listOf(
            DeviceType.GNSS_SPOOFER,
            DeviceType.GNSS_JAMMER,
            DeviceType.RF_JAMMER
        ) -> "signal manipulation detected"
        deviceType in listOf(
            DeviceType.RING_DOORBELL,
            DeviceType.NEST_CAMERA,
            DeviceType.WYZE_CAMERA,
            DeviceType.ARLO_CAMERA
        ) -> "smart home camera nearby"
        deviceType == DeviceType.ROGUE_AP -> "rogue access point detected"
        deviceType == DeviceType.MAN_IN_MIDDLE -> "network attack detected"
        else -> "surveillance device detected"
    }

    return "$severityText - $deviceContext"
}

/**
 * Full-width gradient header for the detection info page displaying threat information.
 *
 * Features:
 * - Gradient background based on threat level (red for critical/high, orange for medium, green for low/info)
 * - Large threat badge with icon matching the device type
 * - Threat score prominently displayed (e.g., "85/100")
 * - Threat context text describing the severity and device type
 * - Pulsing animation for active critical detections
 * - Device type name and emoji
 *
 * @param threatLevel The severity level of the threat
 * @param threatScore The calculated threat score (0-100)
 * @param deviceType The type of detected device
 * @param isActive Whether the detection is currently active (enables pulsing animation for critical threats)
 * @param modifier Modifier for the composable
 */
@Composable
fun ThreatHeader(
    threatLevel: ThreatLevel,
    threatScore: Int,
    deviceType: DeviceType,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val (primaryColor, secondaryColor) = threatLevel.toGradientColors()
    val isCritical = threatLevel == ThreatLevel.CRITICAL
    val shouldPulse = isActive && isCritical

    // Pulsing animation for active critical detections
    val infiniteTransition = rememberInfiniteTransition(label = "threatPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val effectiveAlpha = if (shouldPulse) pulseAlpha else 1f
    val contextText = getThreatContextText(threatLevel, deviceType)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.4f * effectiveAlpha),
                        secondaryColor.copy(alpha = 0.2f * effectiveAlpha),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            )
            .semantics {
                contentDescription = "Threat header showing ${threatLevel.displayName} threat level with score $threatScore out of 100 for ${deviceType.displayName}"
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Device type emoji and name row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = deviceType.emoji,
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = deviceType.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Large threat badge with icon
            ThreatBadgeLarge(
                threatLevel = threatLevel,
                deviceType = deviceType,
                isActive = isActive,
                shouldPulse = shouldPulse,
                pulseScale = pulseScale,
                primaryColor = primaryColor
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Threat score display
            ThreatScoreDisplay(
                threatScore = threatScore,
                primaryColor = primaryColor,
                shouldPulse = shouldPulse,
                pulseAlpha = pulseAlpha
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Threat context text
            Text(
                text = contextText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = primaryColor.copy(alpha = 0.9f),
                modifier = Modifier.semantics { heading() }
            )

            // Active indicator for critical threats
            if (isActive && isCritical) {
                Spacer(modifier = Modifier.height(8.dp))
                ActiveThreatIndicator(
                    pulseAlpha = pulseAlpha,
                    primaryColor = primaryColor
                )
            }
        }
    }
}

/**
 * Large threat badge with device icon and threat level text.
 */
@Composable
private fun ThreatBadgeLarge(
    threatLevel: ThreatLevel,
    deviceType: DeviceType,
    isActive: Boolean,
    shouldPulse: Boolean,
    pulseScale: Float,
    primaryColor: Color
) {
    val icon = deviceType.toIcon()
    val effectiveScale = if (shouldPulse) pulseScale else 1f

    Surface(
        modifier = Modifier
            .size((80 * effectiveScale).dp),
        shape = CircleShape,
        color = primaryColor.copy(alpha = 0.2f),
        shadowElevation = if (isActive) 8.dp else 4.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            // Inner circle with icon
            Surface(
                modifier = Modifier.size((60 * effectiveScale).dp),
                shape = CircleShape,
                color = primaryColor.copy(alpha = 0.3f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "${deviceType.displayName} icon",
                        tint = primaryColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Threat level text badge
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = primaryColor.copy(alpha = 0.25f),
        shadowElevation = 2.dp
    ) {
        Text(
            text = threatLevel.displayName.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = primaryColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

/**
 * Prominent threat score display.
 */
@Composable
private fun ThreatScoreDisplay(
    threatScore: Int,
    primaryColor: Color,
    shouldPulse: Boolean,
    pulseAlpha: Float
) {
    val scoreAlpha = if (shouldPulse) pulseAlpha else 1f

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$threatScore",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = primaryColor.copy(alpha = scoreAlpha)
        )
        Text(
            text = "/100",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = primaryColor.copy(alpha = 0.6f * scoreAlpha),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    Text(
        text = "Threat Score",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Active threat indicator with pulsing dot.
 */
@Composable
private fun ActiveThreatIndicator(
    pulseAlpha: Float,
    primaryColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Pulsing dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = pulseAlpha))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "ACTIVE THREAT",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = primaryColor.copy(alpha = pulseAlpha),
            letterSpacing = 1.sp
        )
    }
}

// ==================== PREVIEWS ====================

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ThreatHeaderCriticalActivePreview() {
    FlockYouTheme {
        ThreatHeader(
            threatLevel = ThreatLevel.CRITICAL,
            threatScore = 95,
            deviceType = DeviceType.STINGRAY_IMSI,
            isActive = true
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ThreatHeaderHighPreview() {
    FlockYouTheme {
        ThreatHeader(
            threatLevel = ThreatLevel.HIGH,
            threatScore = 78,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            isActive = true
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ThreatHeaderMediumPreview() {
    FlockYouTheme {
        ThreatHeader(
            threatLevel = ThreatLevel.MEDIUM,
            threatScore = 55,
            deviceType = DeviceType.HIDDEN_CAMERA,
            isActive = false
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ThreatHeaderLowPreview() {
    FlockYouTheme {
        ThreatHeader(
            threatLevel = ThreatLevel.LOW,
            threatScore = 35,
            deviceType = DeviceType.RING_DOORBELL,
            isActive = true
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ThreatHeaderInfoPreview() {
    FlockYouTheme {
        ThreatHeader(
            threatLevel = ThreatLevel.INFO,
            threatScore = 15,
            deviceType = DeviceType.BLUETOOTH_BEACON,
            isActive = false
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ThreatHeaderFlipperZeroPreview() {
    FlockYouTheme {
        ThreatHeader(
            threatLevel = ThreatLevel.HIGH,
            threatScore = 72,
            deviceType = DeviceType.FLIPPER_ZERO_SPAM,
            isActive = true
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun ThreatHeaderTrackerPreview() {
    FlockYouTheme {
        ThreatHeader(
            threatLevel = ThreatLevel.MEDIUM,
            threatScore = 60,
            deviceType = DeviceType.AIRTAG,
            isActive = true
        )
    }
}

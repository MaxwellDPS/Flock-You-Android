package com.flockyou.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flockyou.data.model.*
import com.flockyou.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Get color for threat level
 */
@Composable
fun ThreatLevel.toColor(): Color = when (this) {
    ThreatLevel.CRITICAL -> ThreatCritical
    ThreatLevel.HIGH -> ThreatHigh
    ThreatLevel.MEDIUM -> ThreatMedium
    ThreatLevel.LOW -> ThreatLow
    ThreatLevel.INFO -> ThreatInfo
}

/**
 * Get color for signal strength
 */
@Composable
fun SignalStrength.toColor(): Color = when (this) {
    SignalStrength.EXCELLENT -> SignalExcellent
    SignalStrength.GOOD -> SignalGood
    SignalStrength.MEDIUM -> SignalMedium
    SignalStrength.WEAK -> SignalWeak
    SignalStrength.VERY_WEAK -> SignalVeryWeak
}

/**
 * Get icon for device type
 */
fun DeviceType.toIcon(): ImageVector = when (this) {
    DeviceType.FLOCK_SAFETY_CAMERA -> Icons.Default.CameraAlt
    DeviceType.PENGUIN_SURVEILLANCE -> Icons.Default.Videocam
    DeviceType.PIGVISION_SYSTEM -> Icons.Default.RemoveRedEye
    DeviceType.RAVEN_GUNSHOT_DETECTOR -> Icons.Default.Mic
    DeviceType.UNKNOWN_SURVEILLANCE -> Icons.Default.QuestionMark
}

/**
 * Get icon for detection protocol
 */
fun DetectionProtocol.toIcon(): ImageVector = when (this) {
    DetectionProtocol.WIFI -> Icons.Default.Wifi
    DetectionProtocol.BLUETOOTH_LE -> Icons.Default.Bluetooth
}

/**
 * Animated scanning radar effect
 */
@Composable
fun ScanningRadar(
    isScanning: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )
    
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (isScanning) {
            // Animated rings
            repeat(3) { index ->
                val delay = index * 500
                val ringScale by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing, delayMillis = delay),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "ringScale$index"
                )
                val ringAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing, delayMillis = delay),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "ringAlpha$index"
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxSize(ringScale)
                        .clip(CircleShape)
                        .border(2.dp, color.copy(alpha = ringAlpha), CircleShape)
                )
            }
        }
        
        // Center dot
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (isScanning) color else Color.Gray)
        )
    }
}

/**
 * Status card showing scanning status
 */
@Composable
fun StatusCard(
    isScanning: Boolean,
    totalDetections: Int,
    highThreatCount: Int,
    onToggleScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isScanning) "SCANNING" else "IDLE",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isScanning) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                    Text(
                        text = "Surveillance Detection System",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Box(
                    modifier = Modifier.size(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ScanningRadar(isScanning = isScanning)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "TOTAL",
                    value = totalDetections.toString(),
                    color = MaterialTheme.colorScheme.primary
                )
                StatItem(
                    label = "HIGH THREAT",
                    value = highThreatCount.toString(),
                    color = ThreatHigh
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onToggleScan,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isScanning) "STOP SCANNING" else "START SCANNING",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Detection list item card
 */
@Composable
fun DetectionCard(
    detection: Detection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val threatColor = detection.threatLevel.toColor()
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Threat indicator
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(threatColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = detection.deviceType.toIcon(),
                    contentDescription = null,
                    tint = threatColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = detection.deviceType.name.replace("_", " "),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ThreatBadge(threatLevel = detection.threatLevel)
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = detection.protocol.toIcon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = detection.macAddress ?: detection.ssid ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                if (detection.manufacturer != null) {
                    Text(
                        text = detection.manufacturer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = dateFormat.format(Date(detection.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                SignalIndicator(
                    rssi = detection.rssi,
                    signalStrength = detection.signalStrength
                )
            }
        }
    }
}

@Composable
fun ThreatBadge(
    threatLevel: ThreatLevel,
    modifier: Modifier = Modifier
) {
    val color = threatLevel.toColor()
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = threatLevel.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SignalIndicator(
    rssi: Int,
    signalStrength: SignalStrength,
    modifier: Modifier = Modifier
) {
    val color = signalStrength.toColor()
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Signal bars
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val barCount = when (signalStrength) {
                SignalStrength.EXCELLENT -> 4
                SignalStrength.GOOD -> 3
                SignalStrength.MEDIUM -> 2
                SignalStrength.WEAK -> 1
                SignalStrength.VERY_WEAK -> 1
            }
            
            repeat(4) { index ->
                val height = (8 + index * 4).dp
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(height)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index < barCount) color else color.copy(alpha = 0.2f)
                        )
                )
            }
        }
        
        Spacer(modifier = Modifier.width(4.dp))
        
        Text(
            text = "${rssi}dBm",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color
        )
    }
}

/**
 * Empty state when no detections
 */
@Composable
fun EmptyState(
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isScanning) Icons.Outlined.RadarOutlined else Icons.Outlined.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isScanning) "Scanning for devices..." else "No detections yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (isScanning) 
                "Surveillance devices will appear here when detected" 
            else 
                "Start scanning to detect surveillance devices",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// Add RadarOutlined icon since it doesn't exist in material icons
private val Icons.Outlined.RadarOutlined: ImageVector
    get() = Icons.Default.Radar

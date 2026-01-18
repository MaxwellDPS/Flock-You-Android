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
import com.flockyou.service.ScanningService
import com.flockyou.ui.theme.*
import com.flockyou.ui.theme.StatusActive
import com.flockyou.ui.theme.StatusError
import com.flockyou.ui.theme.StatusWarning
import com.flockyou.ui.theme.StatusDisabled
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
    SignalStrength.UNKNOWN -> Color.Gray
}

/**
 * Get icon for device type
 */
fun DeviceType.toIcon(): ImageVector = when (this) {
    DeviceType.FLOCK_SAFETY_CAMERA -> Icons.Default.CameraAlt
    DeviceType.PENGUIN_SURVEILLANCE -> Icons.Default.Videocam
    DeviceType.PIGVISION_SYSTEM -> Icons.Default.RemoveRedEye
    DeviceType.RAVEN_GUNSHOT_DETECTOR -> Icons.Default.Mic
    DeviceType.MOTOROLA_POLICE_TECH -> Icons.Default.SettingsInputAntenna
    DeviceType.AXON_POLICE_TECH -> Icons.Default.ElectricBolt
    DeviceType.L3HARRIS_SURVEILLANCE -> Icons.Default.SatelliteAlt
    DeviceType.CELLEBRITE_FORENSICS -> Icons.Default.PhoneAndroid
    DeviceType.BODY_CAMERA -> Icons.Default.Videocam
    DeviceType.POLICE_RADIO -> Icons.Default.Radio
    DeviceType.POLICE_VEHICLE -> Icons.Default.LocalPolice
    DeviceType.FLEET_VEHICLE -> Icons.Default.DirectionsCar
    DeviceType.STINGRAY_IMSI -> Icons.Default.CellTower
    DeviceType.ROGUE_AP -> Icons.Default.WifiOff
    DeviceType.HIDDEN_CAMERA -> Icons.Default.Visibility
    DeviceType.SURVEILLANCE_VAN -> Icons.Default.DirectionsCar
    DeviceType.TRACKING_DEVICE -> Icons.Default.LocationOn
    DeviceType.RF_JAMMER -> Icons.Default.SignalCellularOff
    DeviceType.DRONE -> Icons.Default.FlightTakeoff
    DeviceType.SURVEILLANCE_INFRASTRUCTURE -> Icons.Default.Business
    DeviceType.ULTRASONIC_BEACON -> Icons.Default.Hearing
    DeviceType.SATELLITE_NTN -> Icons.Default.SatelliteAlt
    DeviceType.UNKNOWN_SURVEILLANCE -> Icons.Default.QuestionMark
}

/**
 * Get icon for detection protocol
 */
fun DetectionProtocol.toIcon(): ImageVector = when (this) {
    DetectionProtocol.WIFI -> Icons.Default.Wifi
    DetectionProtocol.BLUETOOTH_LE -> Icons.Default.Bluetooth
    DetectionProtocol.CELLULAR -> Icons.Default.CellTower
    DetectionProtocol.SATELLITE -> Icons.Default.SatelliteAlt
    DetectionProtocol.AUDIO -> Icons.Default.Hearing
    DetectionProtocol.RF -> Icons.Default.SettingsInputAntenna
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
    modifier: Modifier = Modifier,
    scanStatus: ScanningService.ScanStatus = ScanningService.ScanStatus.Idle,
    bleStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    wifiStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    locationStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    cellularStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    satelliteStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    recentErrors: List<ScanningService.ScanError> = emptyList(),
    onClearErrors: () -> Unit = {}
) {
    var showErrorDetails by remember { mutableStateOf(false) }
    
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
                        text = when (scanStatus) {
                            is ScanningService.ScanStatus.Idle -> "IDLE"
                            is ScanningService.ScanStatus.Starting -> "STARTING..."
                            is ScanningService.ScanStatus.Active -> "SCANNING"
                            is ScanningService.ScanStatus.Stopping -> "STOPPING..."
                            is ScanningService.ScanStatus.Error -> "ERROR"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = when (scanStatus) {
                            is ScanningService.ScanStatus.Active -> MaterialTheme.colorScheme.primary
                            is ScanningService.ScanStatus.Error -> MaterialTheme.colorScheme.error
                            is ScanningService.ScanStatus.Starting,
                            is ScanningService.ScanStatus.Stopping -> MaterialTheme.colorScheme.tertiary
                            else -> Color.Gray
                        }
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
            
            // Subsystem status indicators
            if (isScanning) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SubsystemIndicator(
                        name = "BLE",
                        status = bleStatus,
                        modifier = Modifier.weight(1f)
                    )
                    SubsystemIndicator(
                        name = "WiFi",
                        status = wifiStatus,
                        modifier = Modifier.weight(1f)
                    )
                    SubsystemIndicator(
                        name = "GPS",
                        status = locationStatus,
                        modifier = Modifier.weight(1f)
                    )
                    SubsystemIndicator(
                        name = "Cell",
                        status = cellularStatus,
                        modifier = Modifier.weight(1f)
                    )
                    SubsystemIndicator(
                        name = "Sat",
                        status = satelliteStatus,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            // Error banner
            if (recentErrors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showErrorDetails = !showErrorDetails },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${recentErrors.size} recent error${if (recentErrors.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = recentErrors.firstOrNull()?.message ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = if (showErrorDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle error details",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                
                // Expandable error details
                AnimatedVisibility(visible = showErrorDetails) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        recentErrors.take(5).forEach { error ->
                            ErrorLogItem(error = error)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        TextButton(
                            onClick = onClearErrors,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Clear Errors")
                        }
                    }
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
fun SubsystemIndicator(
    name: String,
    status: ScanningService.SubsystemStatus,
    modifier: Modifier = Modifier
) {
    val (color, icon, _) = when (status) {
        is ScanningService.SubsystemStatus.Active -> Triple(
            StatusActive,
            Icons.Default.CheckCircle,
            "Active"
        )
        is ScanningService.SubsystemStatus.Idle -> Triple(
            Color.Gray,
            Icons.Default.RadioButtonUnchecked,
            "Idle"
        )
        is ScanningService.SubsystemStatus.Disabled -> Triple(
            StatusDisabled,
            Icons.Default.Block,
            "Disabled"
        )
        is ScanningService.SubsystemStatus.Error -> Triple(
            StatusError,
            Icons.Default.Error,
            "Error"
        )
        is ScanningService.SubsystemStatus.PermissionDenied -> Triple(
            StatusWarning,
            Icons.Default.Lock,
            "No Perm"
        )
    }
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun ErrorLogItem(
    error: ScanningService.ScanError,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateFormat.format(Date(error.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (error.recoverable) 
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                else 
                    MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
            ) {
                Text(
                    text = error.subsystem,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (error.recoverable)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionCard(
    detection: Detection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    advancedMode: Boolean = false
) {
    val threatColor = detection.threatLevel.toColor()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    
    // Calculate relative time
    val relativeTime = remember(detection.timestamp) {
        val now = System.currentTimeMillis()
        val diff = now - detection.timestamp
        when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> dateFormat.format(Date(detection.timestamp))
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    // Secondary info - different for cellular vs WiFi/BLE
                    if (detection.protocol == DetectionProtocol.CELLULAR) {
                        // For cellular: show detection method (anomaly type)
                        Text(
                            text = detection.detectionMethod.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        // For WiFi/BLE: show manufacturer
                        detection.manufacturer?.let { mfr ->
                            Text(
                                text = mfr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Identifier row - different for cellular vs WiFi/BLE
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
                            text = when (detection.protocol) {
                                DetectionProtocol.CELLULAR -> {
                                    // Show network type and cell ID
                                    val networkType = detection.manufacturer ?: "Unknown"
                                    val cellId = detection.firmwareVersion?.removePrefix("Cell ID: ") ?: "?"
                                    "$networkType • Cell $cellId"
                                }
                                else -> detection.macAddress ?: detection.ssid ?: "Unknown"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    // Relative time (more prominent)
                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (relativeTime == "Just now") threatColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Exact time
                    Text(
                        text = timeFormat.format(Date(detection.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    SignalIndicator(
                        rssi = detection.rssi,
                        signalStrength = detection.signalStrength
                    )
                }
            }
            
            // Location and metadata row
            if (detection.latitude != null || detection.seenCount > 1 || detection.isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Location
                    if (detection.latitude != null && detection.longitude != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "%.4f, %.4f".format(detection.latitude, detection.longitude),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Seen count
                    if (detection.seenCount > 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${detection.seenCount}x",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Active indicator
                    if (detection.isActive) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = StatusActive.copy(alpha = 0.2f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(StatusActive)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusActive
                                )
                            }
                        }
                    }
                }
            }

            // Advanced mode: Show additional technical details
            if (advancedMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Detection method and protocol
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Method: ${detection.detectionMethod.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Score: ${detection.threatScore}/100",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = threatColor
                            )
                        }

                        // Service UUIDs if present
                        detection.serviceUuids?.let { uuids ->
                            if (uuids.isNotEmpty() && uuids != "[]") {
                                Text(
                                    text = "UUIDs: $uuids",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Matched patterns if present
                        detection.matchedPatterns?.let { patterns ->
                            if (patterns.isNotEmpty() && patterns != "[]") {
                                Text(
                                    text = "Patterns: $patterns",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // ID for debugging
                        Text(
                            text = "ID: ${detection.id.take(8)}...",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
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
                SignalStrength.UNKNOWN -> 0
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

/**
 * Shared section header component for settings screens
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(vertical = 8.dp)
    )
}

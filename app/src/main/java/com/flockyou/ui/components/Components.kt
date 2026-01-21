package com.flockyou.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
    DeviceType.RF_INTERFERENCE -> Icons.Default.SettingsInputAntenna
    DeviceType.RF_ANOMALY -> Icons.Default.Insights
    DeviceType.HIDDEN_TRANSMITTER -> Icons.Default.SpeakerPhone
    DeviceType.ULTRASONIC_BEACON -> Icons.Default.Hearing
    DeviceType.SATELLITE_NTN -> Icons.Default.SatelliteAlt
    DeviceType.GNSS_SPOOFER -> Icons.Default.GpsOff
    DeviceType.GNSS_JAMMER -> Icons.Default.GpsOff
    // Smart home/IoT devices
    DeviceType.RING_DOORBELL -> Icons.Default.Doorbell
    DeviceType.NEST_CAMERA -> Icons.Default.CameraOutdoor
    DeviceType.AMAZON_SIDEWALK -> Icons.Default.SettingsInputAntenna
    DeviceType.WYZE_CAMERA -> Icons.Default.CameraAlt
    DeviceType.ARLO_CAMERA -> Icons.Default.Videocam
    DeviceType.EUFY_CAMERA -> Icons.Default.Home
    DeviceType.BLINK_CAMERA -> Icons.Default.RemoveRedEye
    DeviceType.SIMPLISAFE_DEVICE -> Icons.Default.Shield
    DeviceType.ADT_DEVICE -> Icons.Default.Security
    DeviceType.VIVINT_DEVICE -> Icons.Default.Home
    // Retail/commercial tracking
    DeviceType.BLUETOOTH_BEACON -> Icons.Default.Bluetooth
    DeviceType.RETAIL_TRACKER -> Icons.Default.Store
    DeviceType.CROWD_ANALYTICS -> Icons.Default.Groups
    DeviceType.FACIAL_RECOGNITION -> Icons.Default.Face
    // AirTag/tracker devices
    DeviceType.AIRTAG -> Icons.Default.LocationOn
    DeviceType.TILE_TRACKER -> Icons.Default.LocationSearching
    DeviceType.SAMSUNG_SMARTTAG -> Icons.Default.LocationOn
    DeviceType.GENERIC_BLE_TRACKER -> Icons.Default.BluetoothSearching
    // Traffic enforcement
    DeviceType.SPEED_CAMERA -> Icons.Default.Speed
    DeviceType.RED_LIGHT_CAMERA -> Icons.Default.Traffic
    DeviceType.TOLL_READER -> Icons.Default.CreditCard
    DeviceType.TRAFFIC_SENSOR -> Icons.Default.Sensors
    // Law enforcement specific
    DeviceType.SHOTSPOTTER -> Icons.Default.Mic
    DeviceType.CLEARVIEW_AI -> Icons.Default.Face
    DeviceType.PALANTIR_DEVICE -> Icons.Default.Hub
    DeviceType.GRAYKEY_DEVICE -> Icons.Default.PhonelinkLock
    // Network surveillance
    DeviceType.WIFI_PINEAPPLE -> Icons.Default.Router
    DeviceType.PACKET_SNIFFER -> Icons.Default.NetworkCheck
    DeviceType.MAN_IN_MIDDLE -> Icons.Default.SwapHoriz
    // Hacking tools
    DeviceType.FLIPPER_ZERO -> Icons.Default.SmartToy
    DeviceType.FLIPPER_ZERO_SPAM -> Icons.Default.WifiTethering
    DeviceType.HACKRF_SDR -> Icons.Default.SettingsInputAntenna
    DeviceType.PROXMARK -> Icons.Default.CreditCard
    DeviceType.USB_RUBBER_DUCKY -> Icons.Default.Usb
    DeviceType.LAN_TURTLE -> Icons.Default.Lan
    DeviceType.BASH_BUNNY -> Icons.Default.Terminal
    DeviceType.KEYCROC -> Icons.Default.Keyboard
    DeviceType.SHARK_JACK -> Icons.Default.Cable
    DeviceType.SCREEN_CRAB -> Icons.Default.ScreenShare
    DeviceType.GENERIC_HACKING_TOOL -> Icons.Default.Build
    // Misc surveillance
    DeviceType.LICENSE_PLATE_READER -> Icons.Default.DirectionsCar
    DeviceType.CCTV_CAMERA -> Icons.Default.Videocam
    DeviceType.PTZ_CAMERA -> Icons.Default.CameraAlt
    DeviceType.THERMAL_CAMERA -> Icons.Default.Thermostat
    DeviceType.NIGHT_VISION -> Icons.Default.NightsStay
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
    DetectionProtocol.GNSS -> Icons.Default.GpsFixed
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
                            contentDescription = "Warning: ${recentErrors.size} recent error${if (recentErrors.size > 1) "s" else ""}",
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
                    contentDescription = if (isScanning) "Stop scanning" else "Start scanning",
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
    val (color, icon, statusDescription) = when (status) {
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
        modifier = modifier.semantics { stateDescription = "$name subsystem: $statusDescription" },
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
                contentDescription = "$name: $statusDescription",
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
 * Enrichment status row showing AI analysis state and prioritize button
 */
@Composable
private fun EnrichmentStatusRow(
    detection: Detection,
    isAnalyzing: Boolean,
    isEnrichmentPending: Boolean,
    onPrioritizeEnrichment: ((Detection) -> Unit)?
) {
    // Determine enrichment state
    val hasBasicEnrichment = detection.fpScore != null && detection.analyzedAt != null
    val isLlmEnriched = detection.llmAnalyzed
    // Detection needs LLM enrichment if it hasn't been LLM analyzed yet
    // (even if it has basic rule-based analysis)
    val needsLlmEnrichment = !isLlmEnriched

    // FP thresholds (matching FalsePositiveAnalyzer)
    val isFalsePositive = hasBasicEnrichment && (detection.fpScore ?: 0f) >= 0.4f
    val fpConfidenceLevel = when {
        (detection.fpScore ?: 0f) >= 0.8f -> "High confidence"
        (detection.fpScore ?: 0f) >= 0.6f -> "Likely"
        (detection.fpScore ?: 0f) >= 0.4f -> "Possibly"
        else -> null
    }

    // Only show this row if there's something to display
    // Show analyzed state when LLM enriched AND not currently processing
    if (!needsLlmEnrichment && !isAnalyzing && !isEnrichmentPending) {
        Spacer(modifier = Modifier.height(4.dp))

        // Show FP indicator if flagged as false positive
        if (isFalsePositive && fpConfidenceLevel != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = "False positive",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$fpConfidenceLevel false positive",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    detection.fpReason?.let { reason ->
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "• ${reason.take(40)}${if (reason.length > 40) "..." else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (isLlmEnriched) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI analyzed",
                            tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            return
        }

        // Show a subtle "analyzed" indicator when LLM was used (not FP)
        if (isLlmEnriched || hasBasicEnrichment) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isLlmEnriched) Icons.Default.AutoAwesome else Icons.Default.CheckCircle,
                    contentDescription = "Analyzed",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isLlmEnriched) "AI analyzed" else "Analyzed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                // Show FP score if analyzed but not flagged as FP
                detection.fpScore?.let { score ->
                    if (score > 0f && score < 0.4f) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "• FP: ${(score * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
        return
    }

    Spacer(modifier = Modifier.height(8.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = when {
            isAnalyzing || isEnrichmentPending -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            needsLlmEnrichment -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                isAnalyzing || isEnrichmentPending -> {
                    // Currently being analyzed or queued for analysis
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isAnalyzing) "Analyzing..." else "Queued for analysis",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                needsLlmEnrichment -> {
                    // Missing enrichment - show warning icon and prioritize button
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Pending analysis",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pending AI analysis",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (onPrioritizeEnrichment != null) {
                        TextButton(
                            onClick = { onPrioritizeEnrichment(detection) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerticalAlignTop,
                                contentDescription = "Prioritize",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Prioritize",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
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
    advancedMode: Boolean = false,
    onAnalyzeClick: ((Detection) -> Unit)? = null,
    isAnalyzing: Boolean = false,
    onPrioritizeEnrichment: ((Detection) -> Unit)? = null,
    isEnrichmentPending: Boolean = false,
    ouiLookupViewModel: OuiLookupViewModel = hiltViewModel()
) {
    val threatColor = detection.threatLevel.toColor()
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

    // State for expanding/collapsing the analysis section (collapsed by default)
    var showAnalysis by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        onClick = onClick
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Colored left border based on threat level (4dp width)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(threatColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
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
                            contentDescription = "${detection.deviceType.displayName}, Threat level: ${detection.threatLevel.name}",
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

                    // Standardized display: "Device Type / Category • Primary Identifier"
                    // Format: Cellular: "Network Type • Cell ID"
                    //         WiFi/BLE: "Manufacturer • MAC"
                    // Collect OUI lookup results once, before the when block
                    val lookupResults by ouiLookupViewModel.lookupResults.collectAsState()

                    // Trigger OUI lookup for WIFI and BLUETOOTH_LE protocols if manufacturer is unknown
                    val macAddress = detection.macAddress
                    LaunchedEffect(macAddress, detection.protocol) {
                        if ((detection.protocol == DetectionProtocol.WIFI || detection.protocol == DetectionProtocol.BLUETOOTH_LE)
                            && detection.manufacturer == null && macAddress != null) {
                            ouiLookupViewModel.lookupManufacturer(macAddress)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = detection.protocol.toIcon(),
                            contentDescription = "${detection.protocol.displayName} network",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        // Standardized format for all protocols
                        val displayText = when (detection.protocol) {
                            DetectionProtocol.CELLULAR -> {
                                // Cellular: "Network Type • Cell ID"
                                val networkType = detection.manufacturer ?: "Unknown Network"
                                val cellId = detection.firmwareVersion?.removePrefix("Cell ID: ") ?: "?"
                                "$networkType • Cell $cellId"
                            }
                            DetectionProtocol.WIFI -> {
                                // WiFi: "Manufacturer • MAC" or "SSID • MAC"
                                val resolvedManufacturer = detection.manufacturer
                                    ?: macAddress?.let { ouiLookupViewModel.getCachedManufacturer(it) }
                                val identifier = detection.macAddress ?: detection.ssid ?: "Unknown"
                                if (resolvedManufacturer != null) {
                                    "$resolvedManufacturer • $identifier"
                                } else {
                                    detection.ssid?.let { "$it • $identifier" } ?: identifier
                                }
                            }
                            DetectionProtocol.BLUETOOTH_LE -> {
                                // BLE: "Manufacturer • MAC"
                                val resolvedManufacturer = detection.manufacturer
                                    ?: macAddress?.let { ouiLookupViewModel.getCachedManufacturer(it) }
                                val identifier = detection.macAddress ?: "Unknown"
                                if (resolvedManufacturer != null) {
                                    "$resolvedManufacturer • $identifier"
                                } else {
                                    identifier
                                }
                            }
                            else -> {
                                // Other protocols: show whatever identifier is available
                                detection.macAddress ?: detection.ssid ?: detection.manufacturer ?: "Unknown"
                            }
                        }

                        Text(
                            text = displayText,
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
                    // Relative time only (removed exact timestamp to reduce clutter)
                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (relativeTime == "Just now") threatColor else MaterialTheme.colorScheme.onSurfaceVariant
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
                                contentDescription = "Location: %.4f, %.4f".format(detection.latitude, detection.longitude),
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
                                contentDescription = "Seen ${detection.seenCount} times",
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

            // Enrichment status indicator
            EnrichmentStatusRow(
                detection = detection,
                isAnalyzing = isAnalyzing,
                isEnrichmentPending = isEnrichmentPending,
                onPrioritizeEnrichment = onPrioritizeEnrichment
            )

            // Advanced mode: Show additional technical details
            if (advancedMode) {
                var showRawData by remember { mutableStateOf(false) }

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

                        // Raw data frame toggle and display
                        detection.rawData?.let { rawData ->
                            if (rawData.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Divider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )

                                // Raw data toggle header
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showRawData = !showRawData }
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Code,
                                            contentDescription = "Raw data frame",
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Raw Data Frame",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "(${rawData.length / 2} bytes)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    Icon(
                                        imageVector = if (showRawData) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (showRawData) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Expandable raw data content
                                AnimatedVisibility(
                                    visible = showRawData,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        // Format hex data with spacing for readability
                                        val formattedHex = rawData.chunked(2).joinToString(" ")
                                        val formattedWithLineBreaks = formattedHex.chunked(48).joinToString("\n") // 16 bytes per line

                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surface
                                        ) {
                                            SelectionContainer {
                                                Text(
                                                    text = formattedWithLineBreaks,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        }

                                        // ASCII representation
                                        val asciiRepresentation = rawData.chunked(2).mapNotNull { hex ->
                                            try {
                                                val byte = hex.toInt(16)
                                                if (byte in 32..126) byte.toChar() else '.'
                                            } catch (e: Exception) {
                                                null
                                            }
                                        }.joinToString("")

                                        if (asciiRepresentation.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "ASCII:",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.surface
                                            ) {
                                                SelectionContainer {
                                                    Text(
                                                        text = asciiRepresentation.chunked(32).joinToString("\n"),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

                // Collapsible Analysis Section - collapsed by default to reduce density
                Spacer(modifier = Modifier.height(8.dp))

                // Toggle button to show/hide analysis
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAnalysis = !showAnalysis },
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = "Analysis and Tips",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Analysis & Tips",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = if (showAnalysis) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showAnalysis) "Collapse analysis" else "Expand analysis",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Expandable analysis content
                AnimatedVisibility(
                    visible = showAnalysis,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        InlineAnalysisSection(
                            detection = detection,
                            isAnalyzing = isAnalyzing,
                            onAnalyzeClick = onAnalyzeClick
                        )
                    }
                }
            }
        }
    }
}

/**
 * Inline AI analysis section that shows contextual, actionable insights
 * directly in the detection card without requiring user interaction.
 */
@Composable
private fun InlineAnalysisSection(
    detection: Detection,
    isAnalyzing: Boolean,
    onAnalyzeClick: ((Detection) -> Unit)?
) {
    val insight = generateContextualInsight(detection)
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = insight.backgroundColor
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            // Main insight row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Insight expanded" else "Insight collapsed" },
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = insight.icon,
                    contentDescription = insight.headline,
                    tint = insight.iconColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = insight.headline,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = insight.textColor
                    )
                    Text(
                        text = insight.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = insight.textColor.copy(alpha = 0.85f)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = insight.textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Expanded details
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Divider(color = insight.textColor.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(8.dp))

                    // Action items
                    insight.actions.forEach { action ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "→",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = insight.iconColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = action,
                                style = MaterialTheme.typography.bodySmall,
                                color = insight.textColor.copy(alpha = 0.9f)
                            )
                        }
                    }

                    // Technical details
                    if (insight.technicalDetails.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = insight.technicalDetails,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = insight.textColor.copy(alpha = 0.6f)
                        )
                    }

                    // FP analysis if available
                    detection.fpReason?.let { reason ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Analysis reason",
                                tint = insight.textColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = insight.textColor.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Data class for contextual insights
 */
private data class ContextualInsight(
    val headline: String,
    val summary: String,
    val actions: List<String>,
    val technicalDetails: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val backgroundColor: androidx.compose.ui.graphics.Color,
    val iconColor: androidx.compose.ui.graphics.Color,
    val textColor: androidx.compose.ui.graphics.Color
)

/**
 * Generate contextual, actionable insights based on detection characteristics
 */
@Composable
private fun generateContextualInsight(detection: Detection): ContextualInsight {
    val isFalsePositive = detection.fpScore != null && detection.fpScore >= 0.4f
    val fpConfidence = detection.fpScore ?: 0f

    // Determine insight based on threat level and device characteristics
    return when {
        // High confidence false positive
        isFalsePositive && fpConfidence >= 0.7f -> {
            ContextualInsight(
                headline = "Likely Safe",
                summary = getFpSummary(detection),
                actions = listOf(
                    "No immediate action needed",
                    "Common in residential/commercial areas",
                    "Dismiss or mark as safe if seen regularly"
                ),
                technicalDetails = "FP Score: ${(fpConfidence * 100).toInt()}% • ${detection.detectionMethod.displayName}",
                icon = Icons.Default.CheckCircle,
                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                iconColor = MaterialTheme.colorScheme.tertiary,
                textColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }

        // Critical threat
        detection.threatLevel == ThreatLevel.CRITICAL -> {
            ContextualInsight(
                headline = "Active Surveillance",
                summary = getCriticalSummary(detection),
                actions = listOf(
                    "Leave the area if possible",
                    "Disable WiFi/Bluetooth temporarily",
                    "Note location and time for records",
                    "Consider if this is expected (police station, courthouse)"
                ),
                technicalDetails = "Threat: ${detection.threatScore}/100 • Signal: ${detection.rssi}dBm (${detection.signalStrength.description})",
                icon = Icons.Default.Warning,
                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                iconColor = MaterialTheme.colorScheme.error,
                textColor = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        // High threat
        detection.threatLevel == ThreatLevel.HIGH -> {
            ContextualInsight(
                headline = "Surveillance Device",
                summary = getHighThreatSummary(detection),
                actions = getHighThreatActions(detection),
                technicalDetails = "Threat: ${detection.threatScore}/100 • ${detection.protocol.displayName} • ${detection.signalStrength.description}",
                icon = Icons.Default.Shield,
                backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                iconColor = MaterialTheme.colorScheme.error,
                textColor = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        // Medium threat
        detection.threatLevel == ThreatLevel.MEDIUM -> {
            ContextualInsight(
                headline = "Possible Surveillance",
                summary = getMediumThreatSummary(detection),
                actions = listOf(
                    "Monitor if this device follows you",
                    "Check if seen in multiple locations",
                    if (detection.seenCount > 1) "Seen ${detection.seenCount}x - pattern emerging" else "First sighting - may be transient"
                ),
                technicalDetails = "Threat: ${detection.threatScore}/100 • ${detection.detectionMethod.displayName}",
                icon = Icons.Default.Visibility,
                backgroundColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                iconColor = MaterialTheme.colorScheme.secondary,
                textColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        // Low threat / Info
        else -> {
            ContextualInsight(
                headline = "Device of Interest",
                summary = getLowThreatSummary(detection),
                actions = listOf(
                    "Likely benign but worth noting",
                    if (detection.seenCount == 1) "Single detection - probably passing device" else "Seen ${detection.seenCount}x at this location"
                ),
                technicalDetails = "${detection.deviceType.displayName} • ${detection.protocol.displayName}",
                icon = Icons.Default.Info,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getFpSummary(detection: Detection): String {
    return when (detection.fpCategory) {
        "BENIGN_DEVICE" -> "This matches a known consumer device pattern - likely a neighbor's router, smart device, or personal electronics."
        "CONSUMER_SMART_HOME" -> "Consumer smart home device (Ring, Nest, etc.) - common in residential areas, not surveillance."
        "PUBLIC_INFRASTRUCTURE" -> "Public WiFi or business network infrastructure - expected in commercial areas."
        "HOME_LOCATION" -> "Detected near your home - likely your own device or a neighbor's."
        "WORK_LOCATION" -> "Normal workplace infrastructure - expected security/networking equipment."
        "WEAK_SIGNAL" -> "Very weak signal from a distant device - not targeting you specifically."
        "TRANSIENT" -> "Brief one-time detection - likely a passing vehicle or pedestrian's device."
        else -> "Analysis suggests this is a common device, not targeted surveillance."
    }
}

private fun getCriticalSummary(detection: Detection): String {
    return when (detection.deviceType) {
        DeviceType.RAVEN_GUNSHOT_DETECTOR -> "Acoustic gunshot detection system - actively listening to audio in the area."
        DeviceType.STINGRAY_IMSI -> "Cell site simulator detected - can intercept calls and track phones."
        DeviceType.SHOTSPOTTER -> "ShotSpotter acoustic sensor - monitors audio for gunfire detection."
        DeviceType.FACIAL_RECOGNITION -> "Facial recognition system - capturing and analyzing faces."
        else -> "Active surveillance system capable of capturing audio, video, or communications."
    }
}

private fun getHighThreatSummary(detection: Detection): String {
    return when (detection.deviceType) {
        DeviceType.FLOCK_SAFETY_CAMERA -> "License plate reader capturing all passing vehicles. Your plate is being logged."
        DeviceType.RING_DOORBELL, DeviceType.NEST_CAMERA -> "Smart camera with cloud recording. Video may be shared with police."
        DeviceType.BODY_CAMERA -> "Law enforcement body camera - you may be recorded."
        DeviceType.CCTV_CAMERA, DeviceType.PTZ_CAMERA -> "Surveillance camera with ${if (detection.rssi > -60) "clear view of you" else "coverage of this area"}."
        DeviceType.WIFI_PINEAPPLE -> "Network attack device - do not connect to unknown WiFi here."
        DeviceType.DRONE -> "Drone with camera detected ${detection.signalStrength.description.lowercase()}."
        else -> "${detection.deviceType.displayName} confirmed. Recording or monitoring likely active."
    }
}

private fun getMediumThreatSummary(detection: Detection): String {
    return when (detection.detectionMethod) {
        DetectionMethod.WIFI_EVIL_TWIN -> "Possible fake WiFi network mimicking a legitimate one. Don't connect."
        DetectionMethod.WIFI_FOLLOWING -> "This network has appeared at multiple locations you've visited."
        DetectionMethod.TRACKER_FOLLOWING -> "Bluetooth tracker detected multiple times - may be following you."
        DetectionMethod.CELL_ENCRYPTION_DOWNGRADE -> "Your phone was forced to weaker encryption - potential interception."
        else -> "${detection.deviceType.displayName} - monitoring capabilities unconfirmed."
    }
}

private fun getLowThreatSummary(detection: Detection): String {
    return when {
        detection.deviceType in listOf(DeviceType.BLUETOOTH_BEACON, DeviceType.RETAIL_TRACKER) ->
            "Retail tracking beacon - stores use these for analytics and targeted ads."
        detection.deviceType == DeviceType.AMAZON_SIDEWALK ->
            "Amazon Sidewalk mesh network node - helps Amazon devices connect but also tracks."
        detection.protocol == DetectionProtocol.WIFI && detection.threatLevel == ThreatLevel.INFO ->
            "WiFi network with surveillance-like name but may be legitimate."
        else -> "Flagged due to ${detection.detectionMethod.displayName} but threat level is low."
    }
}

private fun getHighThreatActions(detection: Detection): List<String> {
    return when (detection.deviceType) {
        DeviceType.FLOCK_SAFETY_CAMERA -> listOf(
            "Your license plate is being recorded",
            "Data shared with law enforcement",
            "Common near neighborhoods, parking lots"
        )
        DeviceType.WIFI_PINEAPPLE -> listOf(
            "Do NOT connect to any WiFi here",
            "Disable auto-connect on your phone",
            "Use cellular data only in this area"
        )
        DeviceType.STINGRAY_IMSI -> listOf(
            "Phone calls may be intercepted",
            "Consider airplane mode",
            "Use encrypted messaging apps only"
        )
        else -> listOf(
            "Be aware of your surroundings",
            "Limit sensitive conversations",
            if (detection.rssi > -60) "Device is very close (${detection.signalStrength.description.lowercase()})" else "Device is ${detection.signalStrength.description.lowercase()}"
        )
    }
}

/**
 * Enhanced threat badge with more prominent styling for CRITICAL/HIGH threats.
 * Uses 14sp font size with more padding for better visibility.
 */
@Composable
fun ThreatBadge(
    threatLevel: ThreatLevel,
    modifier: Modifier = Modifier
) {
    val color = threatLevel.toColor()
    val isCriticalOrHigh = threatLevel == ThreatLevel.CRITICAL || threatLevel == ThreatLevel.HIGH

    Surface(
        modifier = modifier.semantics { stateDescription = "Threat level: ${threatLevel.name}" },
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = if (isCriticalOrHigh) 0.3f else 0.2f),
        shadowElevation = if (isCriticalOrHigh) 2.dp else 0.dp
    ) {
        Text(
            text = threatLevel.name,
            fontSize = 14.sp,
            fontWeight = if (isCriticalOrHigh) FontWeight.ExtraBold else FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Intuitive signal indicator showing text label (Strong/Medium/Weak) with simple icon.
 * More user-friendly than technical dBm values.
 */
@Composable
fun SignalIndicator(
    rssi: Int,
    signalStrength: SignalStrength,
    modifier: Modifier = Modifier
) {
    val color = signalStrength.toColor()

    // Human-readable signal label
    val signalLabel = when (signalStrength) {
        SignalStrength.EXCELLENT -> "Strong"
        SignalStrength.GOOD -> "Strong"
        SignalStrength.MEDIUM -> "Medium"
        SignalStrength.WEAK -> "Weak"
        SignalStrength.VERY_WEAK -> "Weak"
        SignalStrength.UNKNOWN -> "Unknown"
    }

    // Simple icon based on signal strength (using available material icons)
    val signalIcon = when (signalStrength) {
        SignalStrength.EXCELLENT, SignalStrength.GOOD -> Icons.Default.NetworkWifi
        SignalStrength.MEDIUM -> Icons.Default.SignalCellularAlt
        SignalStrength.WEAK, SignalStrength.VERY_WEAK -> Icons.Default.SignalCellularAlt
        SignalStrength.UNKNOWN -> Icons.Default.SignalCellularOff
    }

    Row(
        modifier = modifier.semantics {
            stateDescription = "Signal strength: $signalLabel, $rssi dBm"
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = signalIcon,
            contentDescription = "$signalLabel signal",
            tint = color,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = signalLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

/**
 * Empty state when no detections with optional action button and last scan time
 */
@Composable
fun EmptyState(
    isScanning: Boolean,
    modifier: Modifier = Modifier,
    onStartScanning: (() -> Unit)? = null,
    lastScanTime: Long? = null
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isScanning) Icons.Outlined.RadarOutlined else Icons.Outlined.SearchOff,
            contentDescription = if (isScanning) "Scanning for surveillance devices" else "No detections found",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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

        // Show last scan time if available
        lastScanTime?.let { timestamp ->
            if (timestamp > 0 && !isScanning) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last scan: ${dateFormat.format(Date(timestamp))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        // Show start scanning button if not scanning and callback provided
        if (!isScanning && onStartScanning != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStartScanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Scanning")
            }
        }
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
        modifier = modifier
            .padding(vertical = 8.dp)
            .semantics { heading() }
    )
}

/**
 * Expandable detection card with action buttons for marking reviewed/false positive.
 *
 * Features:
 * - Tap to expand: Shows full details (in advanced mode)
 * - Tap to open detail sheet (in simple mode)
 * - Action buttons for mark reviewed/false positive when expanded
 * - Respects simple/advanced mode for info density
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableDetectionCard(
    detection: Detection,
    onClick: () -> Unit,
    onMarkReviewed: (Detection) -> Unit,
    onMarkFalsePositive: (Detection) -> Unit,
    modifier: Modifier = Modifier,
    advancedMode: Boolean = false,
    isExpanded: Boolean = false,
    onExpandToggle: () -> Unit = {},
    onAnalyzeClick: ((Detection) -> Unit)? = null,
    isAnalyzing: Boolean = false,
    onPrioritizeEnrichment: ((Detection) -> Unit)? = null,
    isEnrichmentPending: Boolean = false,
    ouiLookupViewModel: OuiLookupViewModel = hiltViewModel()
) {
    val threatColor = detection.threatLevel.toColor()
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

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
        onClick = {
            if (advancedMode) {
                // In advanced mode, tap expands details
                onExpandToggle()
            } else {
                // In simple mode, tap opens detail sheet
                onClick()
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Colored left border based on threat level
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .then(
                            if (isExpanded) Modifier.fillMaxHeight()
                            else Modifier.height(80.dp)
                        )
                        .background(threatColor)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(12.dp)
                ) {
                    // Compact header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Threat indicator icon
                        Box(
                            modifier = Modifier
                                .size(if (advancedMode) 48.dp else 40.dp)
                                .clip(RoundedCornerShape(if (advancedMode) 12.dp else 10.dp))
                                .background(threatColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = detection.deviceType.toIcon(),
                                contentDescription = "${detection.deviceType.displayName}, Threat level: ${detection.threatLevel.name}",
                                tint = threatColor,
                                modifier = Modifier.size(if (advancedMode) 28.dp else 24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Main content
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // In simple mode, show friendly name; advanced shows raw type
                                Text(
                                    text = if (advancedMode) {
                                        detection.deviceType.name.replace("_", " ")
                                    } else {
                                        detection.deviceType.displayName
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                ThreatBadge(threatLevel = detection.threatLevel)
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Simple mode: friendly description
                            // Advanced mode: technical details
                            if (!advancedMode) {
                                // Simple mode - show friendly description
                                Text(
                                    text = getSimpleDescription(detection),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                // Advanced mode - show protocol and identifier
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = detection.protocol.toIcon(),
                                        contentDescription = "${detection.protocol.displayName} network",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = getAdvancedIdentifier(detection),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Right side - time and signal
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = relativeTime,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (relativeTime == "Just now") threatColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            if (advancedMode) {
                                // Show RSSI in advanced mode
                                Text(
                                    text = "${detection.rssi} dBm",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = detection.signalStrength.toColor()
                                )
                            } else {
                                // Show friendly signal indicator in simple mode
                                SignalIndicator(
                                    rssi = detection.rssi,
                                    signalStrength = detection.signalStrength
                                )
                            }

                            // Expand indicator when in advanced mode
                            if (advancedMode) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Expanded details section
                    AnimatedVisibility(
                        visible = isExpanded && advancedMode,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Divider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Technical details grid
                            ExpandedTechnicalDetails(
                                detection = detection,
                                timeFormat = timeFormat,
                                dateFormat = dateFormat
                            )

                            // Enrichment status
                            EnrichmentStatusRowPublic(
                                detection = detection,
                                isAnalyzing = isAnalyzing,
                                isEnrichmentPending = isEnrichmentPending,
                                onPrioritizeEnrichment = onPrioritizeEnrichment
                            )

                            // Action buttons when expanded
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onMarkReviewed(detection) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reviewed", style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = { onMarkFalsePositive(detection) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("False Positive", style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(onClick = onClick) {
                                    Icon(
                                        imageVector = Icons.Default.OpenInFull,
                                        contentDescription = "View full details"
                                    )
                                }
                            }
                        }
                    }

                    // Non-expanded: show quick metadata in simple mode
                    if (!isExpanded && !advancedMode) {
                        // Show seen count if > 1
                        if (detection.seenCount > 1) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = "Seen ${detection.seenCount} times",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Seen ${detection.seenCount} times",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Expanded technical details for detection card
 */
@Composable
private fun ExpandedTechnicalDetails(
    detection: Detection,
    timeFormat: SimpleDateFormat,
    dateFormat: SimpleDateFormat
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // MAC Address
            detection.macAddress?.let { mac ->
                DetailRow(label = "MAC Address", value = mac, isMonospace = true)
            }

            // SSID
            detection.ssid?.let { ssid ->
                DetailRow(label = "SSID/Name", value = ssid)
            }

            // Protocol and Method
            DetailRow(
                label = "Protocol",
                value = "${detection.protocol.displayName} / ${detection.detectionMethod.displayName}"
            )

            // Signal details
            DetailRow(
                label = "Signal",
                value = "${detection.rssi} dBm (${detection.signalStrength.displayName})"
            )

            // Threat score
            DetailRow(
                label = "Threat Score",
                value = "${detection.threatScore}/100"
            )

            // Location if available
            if (detection.latitude != null && detection.longitude != null) {
                DetailRow(
                    label = "Location",
                    value = "%.5f, %.5f".format(detection.latitude, detection.longitude),
                    isMonospace = true
                )
            }

            // Timestamps
            val fullTimestamp = "${dateFormat.format(Date(detection.timestamp))} ${timeFormat.format(Date(detection.timestamp))}"
            DetailRow(label = "First Seen", value = fullTimestamp)

            if (detection.lastSeenTimestamp != detection.timestamp) {
                val lastSeenFull = "${dateFormat.format(Date(detection.lastSeenTimestamp))} ${timeFormat.format(Date(detection.lastSeenTimestamp))}"
                DetailRow(label = "Last Seen", value = lastSeenFull)
            }

            // Seen count
            if (detection.seenCount > 1) {
                DetailRow(label = "Seen Count", value = "${detection.seenCount}x")
            }

            // Service UUIDs
            detection.serviceUuids?.let { uuids ->
                if (uuids.isNotEmpty() && uuids != "[]") {
                    DetailRow(label = "Service UUIDs", value = uuids, isMonospace = true, maxLines = 2)
                }
            }

            // Matched patterns
            detection.matchedPatterns?.let { patterns ->
                if (patterns.isNotEmpty() && patterns != "[]") {
                    DetailRow(label = "Matched Patterns", value = patterns, maxLines = 2)
                }
            }

            // Detection source
            DetailRow(label = "Source", value = detection.detectionSource.displayName)

            // ID for debugging
            DetailRow(label = "ID", value = detection.id, isMonospace = true, maxLines = 1)
        }
    }
}

/**
 * Helper row for detail display
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    isMonospace: Boolean = false,
    maxLines: Int = 1
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Public version of EnrichmentStatusRow for use in SwipeableDetectionCard
 */
@Composable
fun EnrichmentStatusRowPublic(
    detection: Detection,
    isAnalyzing: Boolean,
    isEnrichmentPending: Boolean,
    onPrioritizeEnrichment: ((Detection) -> Unit)?
) {
    val hasBasicEnrichment = detection.fpScore != null && detection.analyzedAt != null
    val isLlmEnriched = detection.llmAnalyzed
    val needsLlmEnrichment = !isLlmEnriched

    // Only show if there's something to display
    if (!needsLlmEnrichment && !isAnalyzing && !isEnrichmentPending) {
        // Show analyzed state
        if (hasBasicEnrichment) {
            val isFalsePositive = (detection.fpScore ?: 0f) >= 0.4f
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isLlmEnriched) Icons.Default.AutoAwesome else Icons.Default.CheckCircle,
                    contentDescription = "Analyzed",
                    tint = if (isFalsePositive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isLlmEnriched) "AI analyzed" else "Analyzed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                detection.fpScore?.let { score ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FP: ${(score * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (score >= 0.4f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
        return
    }

    Spacer(modifier = Modifier.height(8.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = when {
            isAnalyzing || isEnrichmentPending -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            needsLlmEnrichment -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                isAnalyzing || isEnrichmentPending -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isAnalyzing) "Analyzing..." else "Queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                needsLlmEnrichment -> {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Pending analysis",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pending AI analysis",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (onPrioritizeEnrichment != null) {
                        TextButton(
                            onClick = { onPrioritizeEnrichment(detection) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerticalAlignTop,
                                contentDescription = "Prioritize",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Prioritize",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Get a simple, user-friendly description for a detection (used in simple mode)
 */
private fun getSimpleDescription(detection: Detection): String {
    return when {
        detection.fpCategory == "USER_REVIEWED" -> "Reviewed - ${detection.deviceType.displayName}"
        detection.fpCategory == "USER_MARKED_FP" -> "False positive - safe"
        (detection.fpScore ?: 0f) >= 0.7f -> "Likely safe - ${detection.fpReason ?: "common device"}"
        detection.threatLevel == ThreatLevel.CRITICAL -> "Active surveillance detected nearby"
        detection.threatLevel == ThreatLevel.HIGH -> "Surveillance device confirmed"
        detection.threatLevel == ThreatLevel.MEDIUM -> "Possible surveillance equipment"
        detection.isActive -> "Currently active nearby"
        detection.seenCount > 3 -> "Seen ${detection.seenCount} times in this area"
        else -> detection.deviceType.displayName
    }
}

/**
 * Get technical identifier string for advanced mode
 */
private fun getAdvancedIdentifier(detection: Detection): String {
    return when (detection.protocol) {
        DetectionProtocol.CELLULAR -> {
            val networkType = detection.manufacturer ?: "Unknown"
            val cellId = detection.firmwareVersion?.removePrefix("Cell ID: ") ?: "?"
            "$networkType / Cell $cellId"
        }
        DetectionProtocol.WIFI -> {
            detection.macAddress ?: detection.ssid ?: "Unknown"
        }
        DetectionProtocol.BLUETOOTH_LE -> {
            detection.macAddress ?: detection.deviceName ?: "Unknown"
        }
        else -> {
            detection.macAddress ?: detection.ssid ?: detection.manufacturer ?: "Unknown"
        }
    }
}

/**
 * Advanced mode toggle button for app bar
 */
@Composable
fun AdvancedModeToggle(
    advancedMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
    ) {
        Icon(
            imageVector = if (advancedMode) Icons.Default.Code else Icons.Default.Visibility,
            contentDescription = if (advancedMode) "Switch to simple mode" else "Switch to advanced mode",
            tint = if (advancedMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Advanced mode toggle chip for inline use
 */
@Composable
fun AdvancedModeChip(
    advancedMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = advancedMode,
        onClick = onToggle,
        label = {
            Text(
                text = if (advancedMode) "Advanced" else "Simple",
                style = MaterialTheme.typography.labelSmall
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (advancedMode) Icons.Default.Code else Icons.Default.Visibility,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = modifier
    )
}

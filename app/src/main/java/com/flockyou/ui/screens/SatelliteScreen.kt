package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flockyou.monitoring.SatelliteDetectionHeuristics
import com.flockyou.monitoring.SatelliteMonitor
import com.flockyou.monitoring.SatelliteMonitor.*
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Satellite Monitoring Screen
 * 
 * Displays satellite connection status, anomaly alerts, and detection heuristics
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteScreen(
    satelliteState: StateFlow<SatelliteConnectionState>,
    anomalies: List<SatelliteAnomaly>,
    statusSummary: SatelliteStatusSummary?,
    onClearAnomalies: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by satelliteState.collectAsState()
    
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Connection Status Card
        item {
            SatelliteConnectionCard(
                state = state,
                statusSummary = statusSummary
            )
        }
        
        // Device Capabilities Card
        item {
            DeviceCapabilitiesCard(
                statusSummary = statusSummary
            )
        }
        
        // Anomalies Section
        if (anomalies.isNotEmpty()) {
            item {
                AnomaliesHeader(
                    count = anomalies.size,
                    onClear = onClearAnomalies
                )
            }
            
            items(anomalies.sortedByDescending { it.timestamp }) { anomaly ->
                SatelliteAnomalyCard(anomaly = anomaly)
            }
        } else {
            item {
                NoAnomaliesCard()
            }
        }
        
        // Info Section
        item {
            SatelliteInfoCard()
        }
        
        // Detection Rules Status
        item {
            DetectionRulesCard()
        }
    }
}

@Composable
fun SatelliteConnectionCard(
    state: SatelliteConnectionState,
    statusSummary: SatelliteStatusSummary?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Satellite Icon with status indicator
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Satellite,
                            contentDescription = "Satellite",
                            modifier = Modifier.size(40.dp),
                            tint = if (state.isConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        
                        // Status dot
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.isConnected) Color(0xFF4CAF50)
                                    else Color(0xFF9E9E9E)
                                )
                        )
                    }
                    
                    Column {
                        Text(
                            text = if (state.isConnected) "Satellite Connected" else "Terrestrial",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Text(
                            text = if (state.isConnected) {
                                state.networkName ?: "Unknown Network"
                            } else {
                                "Using cellular/WiFi"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Signal indicator
                if (state.isConnected && state.signalStrength != null) {
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Icon(
                            imageVector = when {
                                state.signalStrength >= 3 -> Icons.Default.SignalCellular4Bar
                                state.signalStrength >= 2 -> Icons.Default.SignalCellular3Bar
                                state.signalStrength >= 1 -> Icons.Default.SignalCellular2Bar
                                else -> Icons.Default.SignalCellular1Bar
                            },
                            contentDescription = "Signal",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Level ${state.signalStrength}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            
            // Connection details when connected
            if (state.isConnected) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))
                
                // Connection type and provider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ConnectionDetail(
                        label = "Type",
                        value = formatConnectionType(state.connectionType)
                    )
                    ConnectionDetail(
                        label = "Provider",
                        value = formatProvider(state.provider)
                    )
                    ConnectionDetail(
                        label = "Technology",
                        value = formatRadioTech(state.radioTechnology)
                    )
                }
                
                // Capabilities
                Spacer(modifier = Modifier.height(12.dp))
                CapabilitiesRow(capabilities = state.capabilities)
            }
        }
    }
}

@Composable
fun ConnectionDetail(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun CapabilitiesRow(capabilities: SatelliteCapabilities) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (capabilities.supportsSMS) {
            CapabilityChip(icon = Icons.Default.Sms, label = "SMS")
        }
        if (capabilities.supportsMMS) {
            CapabilityChip(icon = Icons.Default.Image, label = "MMS")
        }
        if (capabilities.supportsEmergency) {
            CapabilityChip(icon = Icons.Default.Emergency, label = "911")
        }
        if (capabilities.supportsLocationSharing) {
            CapabilityChip(icon = Icons.Default.LocationOn, label = "Location")
        }
        if (capabilities.supportsVoice) {
            CapabilityChip(icon = Icons.Default.Call, label = "Voice")
        }
    }
}

@Composable
fun CapabilityChip(
    icon: ImageVector,
    label: String
) {
    AssistChip(
        onClick = { },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = Modifier.height(28.dp)
    )
}

@Composable
fun DeviceCapabilitiesCard(statusSummary: SatelliteStatusSummary?) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null
                )
                Text(
                    text = "Device Satellite Support",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val deviceSupported = statusSummary?.deviceSupported ?: false
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (deviceSupported) {
                        "Your device supports satellite connectivity"
                    } else {
                        "Satellite features may be limited on this device"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Icon(
                    imageVector = if (deviceSupported) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = if (deviceSupported) {
                        Color(0xFF4CAF50)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            
            // T-Mobile Starlink / Skylo support
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Supported Services:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "• T-Mobile Starlink (T-Satellite): 60+ compatible devices\n" +
                       "• Skylo SOS: Pixel 9/10 series, Pixel Watch 4\n" +
                       "• Emergency SOS: Most modern smartphones",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AnomaliesHeader(
    count: Int,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = "Satellite Anomalies ($count)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        TextButton(onClick = onClear) {
            Text("Clear All")
        }
    }
}

@Composable
fun SatelliteAnomalyCard(anomaly: SatelliteAnomaly) {
    val severityColor = when (anomaly.severity) {
        AnomalySeverity.CRITICAL -> Color(0xFFD32F2F)
        AnomalySeverity.HIGH -> Color(0xFFF44336)
        AnomalySeverity.MEDIUM -> Color(0xFFFF9800)
        AnomalySeverity.LOW -> Color(0xFFFFC107)
        AnomalySeverity.INFO -> Color(0xFF2196F3)
    }
    
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = severityColor.copy(alpha = 0.1f)
        ),
        onClick = { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(severityColor)
                    )
                    
                    Column {
                        Text(
                            text = formatAnomalyType(anomaly.type),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatTimestamp(anomaly.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                SuggestionChip(
                    onClick = { },
                    label = { 
                        Text(
                            anomaly.severity.name,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = severityColor.copy(alpha = 0.2f),
                        labelColor = severityColor
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = anomaly.description,
                style = MaterialTheme.typography.bodyMedium
            )
            
            // Expandable details
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Technical details
                    if (anomaly.technicalDetails.isNotEmpty()) {
                        Text(
                            text = "Technical Details:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        anomaly.technicalDetails.forEach { (key, value) ->
                            Text(
                                text = "• $key: $value",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Recommendations
                    if (anomaly.recommendations.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Recommendations:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        anomaly.recommendations.forEach { rec ->
                            Text(
                                text = "• $rec",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // Expand indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun NoAnomaliesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4CAF50)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "No satellite anomalies detected",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF4CAF50)
            )
        }
    }
}

@Composable
fun SatelliteInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "About Satellite Monitoring",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "This module monitors satellite connectivity to detect potential surveillance activity:",
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val infoItems = listOf(
                "T-Mobile Starlink: 650+ LEO satellites, 3GPP Release 17",
                "Skylo NTN: Pixel 9/10 emergency satellite via NB-IoT",
                "Detects unexpected satellite connections",
                "Monitors for network downgrade attacks",
                "Identifies unknown satellite networks"
            )
            
            infoItems.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DetectionRulesCard() {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Rule,
                        contentDescription = null
                    )
                    Text(
                        text = "Detection Rules",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    SatelliteDetectionHeuristics.DetectionRules.RULES.forEach { rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rule.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = rule.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            val severityColor = when (rule.severity) {
                                "CRITICAL" -> Color(0xFFD32F2F)
                                "HIGH" -> Color(0xFFF44336)
                                "MEDIUM" -> Color(0xFFFF9800)
                                else -> Color(0xFF4CAF50)
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(severityColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper functions
private fun formatConnectionType(type: SatelliteConnectionType): String {
    return when (type) {
        SatelliteConnectionType.T_MOBILE_STARLINK -> "T-Mobile Starlink"
        SatelliteConnectionType.SKYLO_NTN -> "Skylo NTN"
        SatelliteConnectionType.GENERIC_NTN -> "Generic NTN"
        SatelliteConnectionType.PROPRIETARY -> "Proprietary"
        SatelliteConnectionType.UNKNOWN_SATELLITE -> "Unknown"
        SatelliteConnectionType.NONE -> "None"
    }
}

private fun formatProvider(provider: SatelliteProvider): String {
    return when (provider) {
        SatelliteProvider.STARLINK -> "SpaceX"
        SatelliteProvider.SKYLO -> "Skylo"
        SatelliteProvider.GLOBALSTAR -> "Globalstar"
        SatelliteProvider.AST_SPACEMOBILE -> "AST"
        SatelliteProvider.LYNK -> "Lynk"
        SatelliteProvider.IRIDIUM -> "Iridium"
        SatelliteProvider.INMARSAT -> "Inmarsat"
        SatelliteProvider.UNKNOWN -> "Unknown"
    }
}

private fun formatRadioTech(tech: Int): String {
    return when (tech) {
        SatelliteMonitor.Companion.NTRadioTechnology.NB_IOT_NTN -> "NB-IoT"
        SatelliteMonitor.Companion.NTRadioTechnology.NR_NTN -> "5G NR"
        SatelliteMonitor.Companion.NTRadioTechnology.EMTC_NTN -> "eMTC"
        SatelliteMonitor.Companion.NTRadioTechnology.PROPRIETARY -> "Proprietary"
        else -> "Unknown"
    }
}

private fun formatAnomalyType(type: SatelliteAnomalyType): String {
    return when (type) {
        SatelliteAnomalyType.UNEXPECTED_SATELLITE_CONNECTION -> "Unexpected Satellite"
        SatelliteAnomalyType.FORCED_SATELLITE_HANDOFF -> "Forced Handoff"
        SatelliteAnomalyType.SUSPICIOUS_NTN_PARAMETERS -> "Suspicious Parameters"
        SatelliteAnomalyType.UNKNOWN_SATELLITE_NETWORK -> "Unknown Network"
        SatelliteAnomalyType.SATELLITE_IN_COVERED_AREA -> "Satellite in Coverage"
        SatelliteAnomalyType.RAPID_SATELLITE_SWITCHING -> "Rapid Switching"
        SatelliteAnomalyType.NTN_BAND_MISMATCH -> "Band Mismatch"
        SatelliteAnomalyType.TIMING_ADVANCE_ANOMALY -> "Timing Anomaly"
        SatelliteAnomalyType.EPHEMERIS_MISMATCH -> "Ephemeris Mismatch"
        SatelliteAnomalyType.DOWNGRADE_TO_SATELLITE -> "Network Downgrade"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

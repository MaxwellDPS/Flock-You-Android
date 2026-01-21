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

        // Technical Details Card (when connected)
        if (state.isConnected) {
            item {
                SatelliteTechnicalDetailsCard(state = state)
            }
        }

        // Device Capabilities Card
        item {
            DeviceCapabilitiesCard(
                statusSummary = statusSummary
            )
        }

        // Network Coverage Card
        item {
            SatelliteNetworkCoverageCard()
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

@Suppress("UNUSED_PARAMETER")
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
                                state.signalStrength >= 3 -> Icons.Default.NetworkCell
                                state.signalStrength >= 2 -> Icons.Default.SignalCellularAlt
                                state.signalStrength >= 1 -> Icons.Default.SignalCellularAlt
                                else -> Icons.Default.SignalCellularAlt
                            },
                            contentDescription = "Signal",
                            tint = when {
                                state.signalStrength >= 3 -> MaterialTheme.colorScheme.primary
                                state.signalStrength >= 2 -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.error
                            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteTechnicalDetailsCard(state: SatelliteConnectionState) {
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
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Technical Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            // Always show key info
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TechDetailItem(
                    icon = Icons.Default.Speed,
                    label = "Latency",
                    value = when (state.provider) {
                        SatelliteProvider.STARLINK -> "~30ms (LEO)"
                        SatelliteProvider.SKYLO -> "~50ms (LEO)"
                        else -> "Variable"
                    }
                )
                TechDetailItem(
                    icon = Icons.Default.Public,
                    label = "Orbit",
                    value = when (state.provider) {
                        SatelliteProvider.STARLINK -> "540km LEO"
                        SatelliteProvider.IRIDIUM -> "780km LEO"
                        SatelliteProvider.INMARSAT -> "35,786km GEO"
                        else -> "LEO/MEO"
                    }
                )
                TechDetailItem(
                    icon = Icons.Default.Router,
                    label = "Standard",
                    value = "3GPP R17"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Detailed technical info
                    Text(
                        text = "Connection Parameters",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Network info
                    TechDetailRow("Network Name", state.networkName ?: "Unknown")
                    TechDetailRow("Operator", state.operatorName ?: "Unknown")
                    TechDetailRow("Radio Technology", formatRadioTech(state.radioTechnology))
                    TechDetailRow("NTN Band", if (state.isNTNBand) "Yes (L/S-band)" else "Checking...")
                    state.frequency?.let { freq ->
                        TechDetailRow("Frequency", "${freq} MHz")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Provider-specific info
                    Text(
                        text = "Provider Information",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    when (state.provider) {
                        SatelliteProvider.STARLINK -> {
                            TechDetailRow("Constellation", "~6,000+ satellites")
                            TechDetailRow("D2D Sats", "~650 (as of Jan 2026)")
                            TechDetailRow("Orbital Speed", "17,000 mph")
                            TechDetailRow("Pass Duration", "~10-15 min overhead")
                            TechDetailRow("HARQ Processes", "32 (NTN extended)")
                        }
                        SatelliteProvider.SKYLO -> {
                            TechDetailRow("Technology", "NB-IoT NTN")
                            TechDetailRow("Modem", "Exynos 5400 / MT T900")
                            TechDetailRow("Emergency Partner", "Garmin Response")
                            TechDetailRow("Free Period", "2 years included")
                        }
                        SatelliteProvider.GLOBALSTAR -> {
                            TechDetailRow("Constellation", "24 satellites")
                            TechDetailRow("Coverage", "Emergency SOS only")
                            TechDetailRow("Partner", "Apple iPhone")
                        }
                        else -> {
                            TechDetailRow("Status", "Monitoring connection...")
                        }
                    }

                    // Message capabilities
                    if (state.capabilities.supportsSMS) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Messaging Limits",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        state.capabilities.maxMessageLength?.let { len ->
                            TechDetailRow("Max Message", "$len characters")
                        }
                        TechDetailRow("Send Time", "~10-60 seconds typical")
                    }
                }
            }
        }
    }
}

@Composable
fun TechDetailItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun TechDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteNetworkCoverageCard() {
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
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Network Coverage & Carriers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Direct-to-Cell satellite is available in most of the continental US with expanding international coverage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Active D2D Services (Jan 2026)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    CarrierCoverageItem(
                        carrier = "T-Mobile + Starlink",
                        region = "USA (500,000 sq mi)",
                        status = "Active",
                        features = "SMS, MMS, Location, 911"
                    )

                    CarrierCoverageItem(
                        carrier = "One NZ + Starlink",
                        region = "New Zealand",
                        status = "Active",
                        features = "SMS, Emergency"
                    )

                    CarrierCoverageItem(
                        carrier = "Verizon + Skylo",
                        region = "USA",
                        status = "Active",
                        features = "Emergency SOS"
                    )

                    CarrierCoverageItem(
                        carrier = "Orange + Skylo",
                        region = "France",
                        status = "Active",
                        features = "Emergency SOS"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Coming Soon",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val upcomingCarriers = listOf(
                        "Telstra (Australia)",
                        "Rogers (Canada)",
                        "KDDI (Japan)",
                        "Salt (Switzerland)",
                        "VMO2 (UK)",
                        "AT&T + AST SpaceMobile (USA)"
                    )

                    upcomingCarriers.forEach { carrier ->
                        Text(
                            text = "• $carrier",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Skylo SOS regions
                    Text(
                        text = "Skylo Emergency SOS Regions",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "USA, Canada, UK, France, Germany, Spain, Switzerland, Australia",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun CarrierCoverageItem(
    carrier: String,
    region: String,
    status: String,
    features: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (status) {
                        "Active" -> Color(0xFF4CAF50)
                        "Testing" -> Color(0xFFFF9800)
                        else -> Color(0xFF9E9E9E)
                    }
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = carrier,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$region • $features",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
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
                        imageVector = Icons.Default.Checklist,
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
        // Core anomaly types
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
        SatelliteAnomalyType.RTT_ORBIT_MISMATCH -> "RTT/Orbit Mismatch"
        SatelliteAnomalyType.UNEXPECTED_MODEM_STATE -> "Modem State Anomaly"
        SatelliteAnomalyType.CAPABILITY_MISMATCH -> "Capability Mismatch"
        SatelliteAnomalyType.NRARFCN_NTN_BAND_INVALID -> "Invalid NTN Band"

        // Timing & Latency anomalies
        SatelliteAnomalyType.DOPPLER_SHIFT_MISMATCH -> "Doppler Mismatch"
        SatelliteAnomalyType.PROPAGATION_DELAY_VARIANCE_WRONG -> "Propagation Delay"
        SatelliteAnomalyType.TIMING_ADVANCE_TOO_SMALL -> "TA Too Small"
        SatelliteAnomalyType.HARQ_RETRANSMISSION_TIMING_WRONG -> "HARQ Timing"
        SatelliteAnomalyType.HANDOVER_TIMING_IMPOSSIBLE -> "Handover Timing"
        SatelliteAnomalyType.MESSAGE_LATENCY_WRONG -> "Message Latency"
        SatelliteAnomalyType.ACK_TIMING_TERRESTRIAL -> "ACK Timing"

        // Orbital & Ephemeris anomalies
        SatelliteAnomalyType.SATELLITE_BELOW_HORIZON -> "Below Horizon"
        SatelliteAnomalyType.WRONG_ORBITAL_PLANE -> "Orbital Plane"
        SatelliteAnomalyType.PASS_DURATION_EXCEEDED -> "Pass Duration"
        SatelliteAnomalyType.ELEVATION_ANGLE_IMPOSSIBLE -> "Elevation Angle"
        SatelliteAnomalyType.TLE_POSITION_MISMATCH -> "TLE Position"
        SatelliteAnomalyType.CARRIER_FREQUENCY_DRIFT_WRONG -> "Frequency Drift"
        SatelliteAnomalyType.GNSS_NTN_TIME_CONFLICT -> "Time Conflict"
        SatelliteAnomalyType.TIME_OF_DAY_VISIBILITY_ANOMALY -> "Visibility"

        // Signal & RF anomalies
        SatelliteAnomalyType.SIGNAL_TOO_STRONG -> "Signal Too Strong"
        SatelliteAnomalyType.WRONG_POLARIZATION -> "Polarization"
        SatelliteAnomalyType.BANDWIDTH_MISMATCH -> "Bandwidth"
        SatelliteAnomalyType.MULTIPATH_IN_CLEAR_SKY -> "Multipath"
        SatelliteAnomalyType.SUBCARRIER_SPACING_WRONG -> "Subcarrier Spacing"

        // Protocol & Network anomalies
        SatelliteAnomalyType.MIB_SIB_INCONSISTENT -> "MIB/SIB"
        SatelliteAnomalyType.PLMN_NOT_NTN_REGISTERED -> "PLMN Invalid"
        SatelliteAnomalyType.CELL_ID_FORMAT_WRONG -> "Cell ID Format"
        SatelliteAnomalyType.PAGING_CYCLE_TERRESTRIAL -> "Paging Cycle"
        SatelliteAnomalyType.DRX_TOO_SHORT -> "DRX Too Short"
        SatelliteAnomalyType.RACH_PROCEDURE_WRONG -> "RACH Procedure"
        SatelliteAnomalyType.MEASUREMENT_GAP_MISSING -> "Measurement Gap"
        SatelliteAnomalyType.GNSS_ASSISTANCE_REJECTED -> "GNSS Rejected"

        // Security anomalies
        SatelliteAnomalyType.ENCRYPTION_DOWNGRADE -> "Encryption Downgrade"
        SatelliteAnomalyType.IDENTITY_REQUEST_FLOOD -> "Identity Flood"
        SatelliteAnomalyType.REPLAY_ATTACK_DETECTED -> "Replay Attack"
        SatelliteAnomalyType.CERTIFICATE_MISMATCH -> "Certificate"
        SatelliteAnomalyType.NULL_CIPHER_OFFERED -> "Null Cipher"
        SatelliteAnomalyType.AUTH_REJECT_LOOP -> "Auth Reject Loop"
        SatelliteAnomalyType.SUPI_CONCEALMENT_DISABLED -> "SUPI Exposed"

        // Coverage & Location anomalies
        SatelliteAnomalyType.NTN_IN_FULL_TERRESTRIAL_COVERAGE -> "NTN in Coverage"
        SatelliteAnomalyType.COVERAGE_HOLE_IMPOSSIBLE -> "Coverage Hole"
        SatelliteAnomalyType.GEOFENCE_VIOLATION -> "Geofence"
        SatelliteAnomalyType.INDOOR_SATELLITE_CONNECTION -> "Indoor Connection"
        SatelliteAnomalyType.ALTITUDE_INCOMPATIBLE -> "Altitude"
        SatelliteAnomalyType.URBAN_CANYON_SATELLITE -> "Urban Canyon"
        SatelliteAnomalyType.GNSS_POSITION_COVERAGE_MISMATCH -> "Position Mismatch"

        // Cross-system anomalies
        SatelliteAnomalyType.SIMULTANEOUS_GNSS_JAMMING -> "GNSS Jamming"
        SatelliteAnomalyType.CELLULAR_NTN_GEOMETRY_IMPOSSIBLE -> "Cell Geometry"
        SatelliteAnomalyType.WIFI_SATELLITE_CONFLICT -> "WiFi Conflict"

        // Behavioral anomalies
        SatelliteAnomalyType.NTN_CAMPING_PERSISTENT -> "Persistent Camping"
        SatelliteAnomalyType.FORCED_NTN_AFTER_CALL -> "Forced After Call"
        SatelliteAnomalyType.HANDOVER_BACK_BLOCKED -> "Handover Blocked"
        SatelliteAnomalyType.NTN_TRACKING_PATTERN -> "Tracking Pattern"
        SatelliteAnomalyType.SELECTIVE_NTN_ROUTING -> "Selective Routing"
        SatelliteAnomalyType.PEER_DEVICE_DIVERGENCE -> "Peer Divergence"

        // Hardware/Modem anomalies
        SatelliteAnomalyType.MODEM_STATE_TRANSITION_IMPOSSIBLE -> "Modem Transition"
        SatelliteAnomalyType.CAPABILITY_ANNOUNCEMENT_WRONG -> "Capability Wrong"
        SatelliteAnomalyType.BASEBAND_FIRMWARE_TAMPERED -> "Firmware Tampered"
        SatelliteAnomalyType.ANTENNA_CONFIGURATION_WRONG -> "Antenna Config"
        SatelliteAnomalyType.POWER_CLASS_MISMATCH -> "Power Class"
        SatelliteAnomalyType.SIMULTANEOUS_BAND_CONFLICT -> "Band Conflict"

        // Provider-specific anomalies
        SatelliteAnomalyType.STARLINK_ORBITAL_PARAMS_WRONG -> "Starlink Params"
        SatelliteAnomalyType.SKYLO_MODEM_MISSING -> "Skylo Missing"
        SatelliteAnomalyType.IRIDIUM_CONSTELLATION_MISMATCH -> "Iridium Mismatch"
        SatelliteAnomalyType.GLOBALSTAR_BAND_WRONG -> "Globalstar Band"
        SatelliteAnomalyType.AST_SPACEMOBILE_PREMATURE -> "AST Premature"
        SatelliteAnomalyType.PROVIDER_CAPABILITY_MISMATCH -> "Provider Capability"

        // Message/Data anomalies
        SatelliteAnomalyType.SMS_ROUTING_SUSPICIOUS -> "SMS Routing"
        SatelliteAnomalyType.DATAGRAM_SIZE_EXCEEDED -> "Datagram Size"
        SatelliteAnomalyType.STORE_FORWARD_MISSING -> "Store/Forward"
        SatelliteAnomalyType.SATELLITE_ID_REUSE -> "Satellite ID Reuse"

        // Emergency anomalies
        SatelliteAnomalyType.SOS_REDIRECT_SUSPICIOUS -> "SOS Redirect"
        SatelliteAnomalyType.E911_LOCATION_INJECTION -> "E911 Injection"
        SatelliteAnomalyType.EMERGENCY_CALL_BLOCKED -> "Emergency Blocked"
        SatelliteAnomalyType.FAKE_EMERGENCY_ALERT -> "Fake Alert"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

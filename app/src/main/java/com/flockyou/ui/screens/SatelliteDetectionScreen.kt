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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.monitoring.SatelliteDetectionHeuristics
import com.flockyou.monitoring.SatelliteMonitor
import com.flockyou.monitoring.SatelliteMonitor.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Standalone Satellite Detection Screen
 *
 * Full-featured satellite monitoring page with:
 * - Connection status and details
 * - Anomaly detection and alerts
 * - Technical specifications
 * - Network coverage information
 * - Detection rules and heuristics
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteDetectionScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val satelliteState = uiState.satelliteState
    val satelliteAnomalies = uiState.satelliteAnomalies
    val satelliteStatus = uiState.satelliteStatus
    val isScanning = uiState.isScanning

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Status", "Anomalies", "Coverage", "Rules")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Satellite Monitoring")
                        Text(
                            text = "NTN & Direct-to-Cell detection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearSatelliteHistory() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when (index) {
                                        0 -> Icons.Outlined.Satellite
                                        1 -> Icons.Default.Warning
                                        2 -> Icons.Default.Map
                                        else -> Icons.Default.Checklist
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(title)
                                when (index) {
                                    1 -> if (satelliteAnomalies.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ) { Text(satelliteAnomalies.size.toString()) }
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Scanning status banner
            if (!isScanning) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start scanning to monitor satellite connections",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            // Tab content
            when (selectedTab) {
                0 -> SatelliteStatusContent(
                    satelliteState = satelliteState,
                    satelliteStatus = satelliteStatus,
                    isScanning = isScanning
                )
                1 -> SatelliteAnomaliesContent(
                    anomalies = satelliteAnomalies,
                    onClear = { viewModel.clearSatelliteAnomalies() }
                )
                2 -> SatelliteCoverageContent()
                3 -> SatelliteRulesContent()
            }
        }
    }
}

@Composable
private fun SatelliteStatusContent(
    satelliteState: SatelliteConnectionState?,
    satelliteStatus: com.flockyou.service.ScanningService.SubsystemStatus,
    isScanning: Boolean
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main connection status card
        item {
            SatelliteMainConnectionCard(
                state = satelliteState,
                status = satelliteStatus,
                isScanning = isScanning
            )
        }

        // Technical details when connected
        if (satelliteState?.isConnected == true) {
            item {
                SatelliteFullTechnicalCard(state = satelliteState)
            }

            item {
                SatelliteCapabilitiesCard(capabilities = satelliteState.capabilities)
            }
        }

        // Device support card
        item {
            SatelliteDeviceSupportCard()
        }

        // Info card
        item {
            SatelliteInfoCard()
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SatelliteMainConnectionCard(
    state: SatelliteConnectionState?,
    status: com.flockyou.service.ScanningService.SubsystemStatus,
    isScanning: Boolean
) {
    val isConnected = state?.isConnected == true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
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
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Satellite,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = if (isConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isConnected -> Color(0xFF4CAF50)
                                        isScanning -> Color(0xFFFFC107)
                                        else -> Color(0xFF9E9E9E)
                                    }
                                )
                        )
                    }

                    Column {
                        Text(
                            text = if (isConnected) "Satellite Connected" else "Terrestrial Network",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                isConnected -> state?.networkName ?: "Direct-to-Cell"
                                isScanning -> "Monitoring for satellite connections..."
                                else -> "Using cellular/WiFi"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Signal indicator when connected
                if (isConnected && state?.signalStrength != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = when {
                                state.signalStrength >= 3 -> Icons.Default.SignalCellular4Bar
                                state.signalStrength >= 2 -> Icons.Default.SignalCellularAlt
                                else -> Icons.Default.SignalCellularAlt
                            },
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = when {
                                state.signalStrength >= 3 -> Color(0xFF4CAF50)
                                state.signalStrength >= 2 -> Color(0xFFFFC107)
                                else -> Color(0xFFF44336)
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
            if (isConnected && state != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SatelliteConnectionDetail(
                        label = "Type",
                        value = formatConnectionType(state.connectionType)
                    )
                    SatelliteConnectionDetail(
                        label = "Provider",
                        value = formatProvider(state.provider)
                    )
                    SatelliteConnectionDetail(
                        label = "Technology",
                        value = formatRadioTech(state.radioTechnology)
                    )
                }
            }

            // Status indicator for monitoring
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = when (status) {
                    is com.flockyou.service.ScanningService.SubsystemStatus.Active ->
                        Color(0xFF4CAF50).copy(alpha = 0.1f)
                    is com.flockyou.service.ScanningService.SubsystemStatus.PermissionDenied ->
                        Color(0xFFF44336).copy(alpha = 0.1f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (status) {
                            is com.flockyou.service.ScanningService.SubsystemStatus.Active ->
                                Icons.Default.CheckCircle
                            is com.flockyou.service.ScanningService.SubsystemStatus.PermissionDenied ->
                                Icons.Default.Error
                            else -> Icons.Default.Pause
                        },
                        contentDescription = null,
                        tint = when (status) {
                            is com.flockyou.service.ScanningService.SubsystemStatus.Active ->
                                Color(0xFF4CAF50)
                            is com.flockyou.service.ScanningService.SubsystemStatus.PermissionDenied ->
                                Color(0xFFF44336)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (status) {
                            is com.flockyou.service.ScanningService.SubsystemStatus.Active ->
                                "Satellite monitoring active"
                            is com.flockyou.service.ScanningService.SubsystemStatus.PermissionDenied ->
                                "Permission required for monitoring"
                            else -> "Satellite monitoring paused"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun SatelliteConnectionDetail(
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
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatelliteFullTechnicalCard(state: SatelliteConnectionState) {
    var expanded by remember { mutableStateOf(true) }

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

            Spacer(modifier = Modifier.height(12.dp))

            // Key metrics always visible
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SatelliteTechMetric(
                    icon = Icons.Default.Speed,
                    label = "Latency",
                    value = when (state.provider) {
                        SatelliteProvider.STARLINK -> "~30ms"
                        SatelliteProvider.SKYLO -> "~50ms"
                        else -> "Variable"
                    }
                )
                SatelliteTechMetric(
                    icon = Icons.Default.Public,
                    label = "Orbit",
                    value = when (state.provider) {
                        SatelliteProvider.STARLINK -> "540km"
                        SatelliteProvider.IRIDIUM -> "780km"
                        SatelliteProvider.INMARSAT -> "35,786km"
                        else -> "LEO"
                    }
                )
                SatelliteTechMetric(
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

                    Text(
                        text = "Connection Parameters",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    SatelliteTechRow("Network Name", state.networkName ?: "Unknown")
                    SatelliteTechRow("Operator", state.operatorName ?: "Unknown")
                    SatelliteTechRow("Radio Technology", formatRadioTech(state.radioTechnology))
                    SatelliteTechRow("NTN Band", if (state.isNTNBand) "Valid (L/S-band)" else "Checking...")
                    state.frequency?.let { freq ->
                        SatelliteTechRow("Frequency", "${freq} MHz")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Provider Information",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    when (state.provider) {
                        SatelliteProvider.STARLINK -> {
                            SatelliteTechRow("Constellation", "~6,000+ satellites")
                            SatelliteTechRow("D2D Satellites", "~650 (Jan 2026)")
                            SatelliteTechRow("Orbital Speed", "17,000 mph")
                            SatelliteTechRow("Pass Duration", "~10-15 min")
                        }
                        SatelliteProvider.SKYLO -> {
                            SatelliteTechRow("Technology", "NB-IoT NTN")
                            SatelliteTechRow("Modem", "Exynos 5400 / MT T900")
                            SatelliteTechRow("Partner", "Garmin Response")
                            SatelliteTechRow("Free Period", "2 years included")
                        }
                        SatelliteProvider.GLOBALSTAR -> {
                            SatelliteTechRow("Constellation", "24 satellites")
                            SatelliteTechRow("Coverage", "Emergency SOS")
                            SatelliteTechRow("Partner", "Apple iPhone")
                        }
                        else -> {
                            SatelliteTechRow("Status", "Monitoring...")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SatelliteTechMetric(
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
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SatelliteTechRow(label: String, value: String) {
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
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SatelliteCapabilitiesCard(capabilities: SatelliteCapabilities) {
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
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Capabilities",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (capabilities.supportsSMS) {
                    SatelliteCapabilityChip(icon = Icons.Default.Sms, label = "SMS", enabled = true)
                }
                if (capabilities.supportsMMS) {
                    SatelliteCapabilityChip(icon = Icons.Default.Image, label = "MMS", enabled = true)
                }
                if (capabilities.supportsVoice) {
                    SatelliteCapabilityChip(icon = Icons.Default.Call, label = "Voice", enabled = true)
                }
                if (capabilities.supportsData) {
                    SatelliteCapabilityChip(icon = Icons.Default.CloudDownload, label = "Data", enabled = true)
                }
                if (capabilities.supportsEmergency) {
                    SatelliteCapabilityChip(icon = Icons.Default.Emergency, label = "911/SOS", enabled = true)
                }
                if (capabilities.supportsLocationSharing) {
                    SatelliteCapabilityChip(icon = Icons.Default.LocationOn, label = "Location", enabled = true)
                }
            }

            capabilities.maxMessageLength?.let { len ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Max message length: $len characters",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SatelliteCapabilityChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean
) {
    AssistChip(
        onClick = { },
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (enabled) {
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            leadingIconContentColor = if (enabled) {
                Color(0xFF4CAF50)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    )
}

@Composable
private fun SatelliteDeviceSupportCard() {
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
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Device Support",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "T-Mobile Starlink (T-Satellite)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "60+ compatible devices including Samsung Galaxy S24/S25, " +
                    "iPhone 14+, Google Pixel 8+, and most flagship phones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Skylo Emergency SOS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Pixel 9, Pixel 9 Pro, Pixel 9 Pro XL, Pixel 9 Pro Fold, " +
                    "Pixel 10 series, Pixel Watch 4.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Apple Emergency SOS (Globalstar)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "iPhone 14, 15, 16 series, Apple Watch Ultra.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SatelliteInfoCard() {
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
                text = "This module monitors satellite connectivity for potential surveillance activity:",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            val items = listOf(
                "Detects unexpected satellite connections",
                "Monitors for forced satellite handoffs",
                "Identifies unknown NTN networks",
                "Alerts on satellite in covered areas",
                "Checks for NTN parameter anomalies"
            )

            items.forEach { item ->
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
private fun SatelliteAnomaliesContent(
    anomalies: List<SatelliteAnomaly>,
    onClear: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (anomalies.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Satellite Anomalies",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF4CAF50)
                        )
                        Text(
                            text = "Satellite connections appear normal",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Satellite Anomalies (${anomalies.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(onClick = onClear) {
                        Text("Clear All")
                    }
                }
            }

            items(
                items = anomalies.sortedByDescending { it.timestamp },
                key = { "${it.type}-${it.timestamp}" }
            ) { anomaly ->
                SatelliteAnomalyFullCard(anomaly = anomaly, dateFormat = dateFormat)
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatelliteAnomalyFullCard(
    anomaly: SatelliteAnomaly,
    dateFormat: SimpleDateFormat
) {
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
                            .size(10.dp)
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
                            text = dateFormat.format(Date(anomaly.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = severityColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = anomaly.severity.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = severityColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = anomaly.description,
                style = MaterialTheme.typography.bodyMedium
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SatelliteCoverageContent() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
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
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Active D2D Services (Jan 2026)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SatelliteCarrierItem(
                        carrier = "T-Mobile + Starlink",
                        region = "USA (500,000 sq mi)",
                        status = "Active",
                        features = "SMS, MMS, Location, 911"
                    )
                    SatelliteCarrierItem(
                        carrier = "One NZ + Starlink",
                        region = "New Zealand",
                        status = "Active",
                        features = "SMS, Emergency"
                    )
                    SatelliteCarrierItem(
                        carrier = "Verizon + Skylo",
                        region = "USA",
                        status = "Active",
                        features = "Emergency SOS"
                    )
                    SatelliteCarrierItem(
                        carrier = "Orange + Skylo",
                        region = "France",
                        status = "Active",
                        features = "Emergency SOS"
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Coming Soon",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val upcomingCarriers = listOf(
                        "Telstra (Australia)",
                        "Rogers (Canada)",
                        "KDDI (Japan)",
                        "Salt (Switzerland)",
                        "VMO2 (UK)",
                        "AT&T + AST SpaceMobile (USA)"
                    )

                    upcomingCarriers.forEach { carrier ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFC107))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = carrier,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Skylo Emergency SOS Regions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "USA, Canada, UK, France, Germany, Spain, Switzerland, Australia",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SatelliteCarrierItem(
    carrier: String,
    region: String,
    status: String,
    features: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when (status) {
                        "Active" -> Color(0xFF4CAF50)
                        "Testing" -> Color(0xFFFF9800)
                        else -> Color(0xFF9E9E9E)
                    }
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = carrier,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
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
private fun SatelliteRulesContent() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
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
                            imageVector = Icons.Default.Checklist,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Detection Rules",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "These heuristics detect potential satellite-based surveillance:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(SatelliteDetectionHeuristics.DetectionRules.RULES) { rule ->
            SatelliteRuleCard(rule = rule)
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SatelliteRuleCard(rule: SatelliteDetectionHeuristics.DetectionRules.DetectionRule) {
    val severityColor = when (rule.severity) {
        "CRITICAL" -> Color(0xFFD32F2F)
        "HIGH" -> Color(0xFFF44336)
        "MEDIUM" -> Color(0xFFFF9800)
        else -> Color(0xFF4CAF50)
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(severityColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = rule.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = severityColor.copy(alpha = 0.1f)
            ) {
                Text(
                    text = rule.severity,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = severityColor
                )
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

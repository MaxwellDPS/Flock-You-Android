@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.flockyou.monitoring.GnssSatelliteMonitor
import com.flockyou.monitoring.GnssSatelliteMonitor.*
import com.flockyou.monitoring.SatelliteDetectionHeuristics
import com.flockyou.monitoring.SatelliteMonitor
import com.flockyou.monitoring.SatelliteMonitor.*
import kotlinx.coroutines.launch
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

    // GNSS satellite monitoring data
    val gnssStatus = uiState.gnssStatus
    val gnssSatellites = uiState.gnssSatellites
    val gnssAnomalies = uiState.gnssAnomalies
    val gnssEvents = uiState.gnssEvents
    val gnssMeasurements = uiState.gnssMeasurements

    val tabs = listOf("Status", "GNSS", "Anomalies", "Coverage", "Rules")

    // Pager state for swipe navigation between tabs
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { tabs.size }
    )
    val coroutineScope = rememberCoroutineScope()

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
                    // Clear button removed - no clear function in viewModel
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
                                        1 -> Icons.Default.GpsFixed
                                        2 -> Icons.Default.Warning
                                        3 -> Icons.Default.Map
                                        else -> Icons.Default.Checklist
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(title)
                                when (index) {
                                    1 -> if (gnssAnomalies.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ) { Text(gnssAnomalies.size.toString()) }
                                    }
                                    2 -> if (satelliteAnomalies.isNotEmpty()) {
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
                1 -> GnssStatusContent(
                    gnssStatus = gnssStatus,
                    gnssSatellites = gnssSatellites,
                    gnssAnomalies = gnssAnomalies,
                    gnssEvents = gnssEvents,
                    gnssMeasurements = gnssMeasurements,
                    isScanning = isScanning
                )
                2 -> SatelliteAnomaliesContent(
                    anomalies = satelliteAnomalies,
                    onClear = { /* No clear function available */ }
                )
                3 -> SatelliteCoverageContent()
                4 -> SatelliteRulesContent()
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
            SatelliteDetectionInfoCard()
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GnssStatusContent(
    gnssStatus: GnssEnvironmentStatus?,
    gnssSatellites: List<SatelliteInfo>,
    gnssAnomalies: List<GnssAnomaly>,
    gnssEvents: List<GnssEvent>,
    gnssMeasurements: GnssMeasurementData?,
    isScanning: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main GNSS status card
        item {
            GnssMainStatusCard(
                gnssStatus = gnssStatus,
                isScanning = isScanning
            )
        }

        // Satellite constellation breakdown
        if (gnssStatus != null && gnssStatus.constellationCounts.isNotEmpty()) {
            item {
                GnssConstellationCard(gnssStatus = gnssStatus)
            }
        }

        // Individual satellites
        if (gnssSatellites.isNotEmpty()) {
            item {
                Text(
                    text = "VISIBLE SATELLITES (${gnssSatellites.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            itemsIndexed(
                items = gnssSatellites.sortedByDescending { it.cn0DbHz },
                key = { index, satellite -> "${satellite.constellation.code}${satellite.svid}_$index" }
            ) { _, satellite ->
                GnssSatelliteCard(satellite = satellite)
            }
        }

        // GNSS Anomalies
        if (gnssAnomalies.isNotEmpty()) {
            item {
                Text(
                    text = "GNSS ANOMALIES (${gnssAnomalies.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(
                items = gnssAnomalies.sortedByDescending { it.timestamp },
                key = { it.id }
            ) { anomaly ->
                GnssAnomalyCard(anomaly = anomaly, dateFormat = dateFormat)
            }
        }

        // Raw measurements
        if (gnssMeasurements != null) {
            item {
                GnssMeasurementsCard(measurements = gnssMeasurements)
            }
        }

        // Recent events
        if (gnssEvents.isNotEmpty()) {
            item {
                Text(
                    text = "RECENT EVENTS",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(
                items = gnssEvents.take(10),
                key = { it.id }
            ) { event ->
                GnssEventCard(event = event, dateFormat = dateFormat)
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GnssMainStatusCard(
    gnssStatus: GnssEnvironmentStatus?,
    isScanning: Boolean
) {
    val hasFix = gnssStatus?.hasFix == true
    val spoofingRisk = gnssStatus?.spoofingRiskLevel ?: SpoofingRiskLevel.UNKNOWN

    val riskColor = when (spoofingRisk) {
        SpoofingRiskLevel.NONE -> Color(0xFF4CAF50)
        SpoofingRiskLevel.LOW -> Color(0xFF8BC34A)
        SpoofingRiskLevel.MEDIUM -> Color(0xFFFF9800)
        SpoofingRiskLevel.HIGH -> Color(0xFFF44336)
        SpoofingRiskLevel.CRITICAL -> Color(0xFFD32F2F)
        SpoofingRiskLevel.UNKNOWN -> Color(0xFF9E9E9E)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasFix) {
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
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = if (hasFix) {
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
                                        hasFix -> Color(0xFF4CAF50)
                                        isScanning -> Color(0xFFFFC107)
                                        else -> Color(0xFF9E9E9E)
                                    }
                                )
                        )
                    }

                    Column {
                        Text(
                            text = if (hasFix) "GNSS Fix Active" else "No GNSS Fix",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                hasFix -> "${gnssStatus?.satellitesUsedInFix ?: 0} satellites in fix"
                                isScanning -> "Acquiring satellites..."
                                else -> "GNSS monitoring inactive"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Spoofing risk indicator
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = riskColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = spoofingRisk.displayName,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = riskColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (gnssStatus?.jammingDetected == true) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFD32F2F).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "JAMMING",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFD32F2F),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (gnssStatus != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    GnssMetric(
                        label = "Satellites",
                        value = "${gnssStatus.totalSatellites}"
                    )
                    GnssMetric(
                        label = "In Fix",
                        value = "${gnssStatus.satellitesUsedInFix}"
                    )
                    GnssMetric(
                        label = "Avg C/N0",
                        value = "${String.format("%.1f", gnssStatus.averageCn0DbHz)} dB"
                    )
                    GnssMetric(
                        label = "Raw Data",
                        value = if (gnssStatus.hasRawMeasurements) "Yes" else "No"
                    )
                }
            }
        }
    }
}

@Composable
private fun GnssMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GnssConstellationCard(gnssStatus: GnssEnvironmentStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Satellite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Constellations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gnssStatus.constellationCounts.forEach { (constellation, count) ->
                    AssistChip(
                        onClick = { },
                        label = { Text("${constellation.displayName}: $count") },
                        leadingIcon = {
                            Text(
                                text = constellation.code,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    )
                }
            }

            // DOP values if available
            if (gnssStatus.hdop != null || gnssStatus.vdop != null || gnssStatus.pdop != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    gnssStatus.hdop?.let {
                        GnssMetric(label = "HDOP", value = String.format("%.1f", it))
                    }
                    gnssStatus.vdop?.let {
                        GnssMetric(label = "VDOP", value = String.format("%.1f", it))
                    }
                    gnssStatus.pdop?.let {
                        GnssMetric(label = "PDOP", value = String.format("%.1f", it))
                    }
                    gnssStatus.fixAccuracyMeters?.let {
                        GnssMetric(label = "Accuracy", value = "${String.format("%.1f", it)}m")
                    }
                }
            }
        }
    }
}

@Composable
private fun GnssSatelliteCard(satellite: SatelliteInfo) {
    val signalColor = when {
        satellite.cn0DbHz >= 40 -> Color(0xFF4CAF50)
        satellite.cn0DbHz >= 25 -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (satellite.usedInFix) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Constellation indicator
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = satellite.constellation.code,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${satellite.constellation.displayName} ${satellite.svid}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (satellite.usedInFix) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "IN FIX",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }

                Text(
                    text = "El: ${String.format("%.0f", satellite.elevationDegrees)}° Az: ${String.format("%.0f", satellite.azimuthDegrees)}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (satellite.hasEphemeris) {
                        Text(
                            text = "EPH",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    if (satellite.hasAlmanac) {
                        Text(
                            text = "ALM",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2196F3)
                        )
                    }
                    satellite.carrierFrequencyHz?.let {
                        Text(
                            text = "${String.format("%.0f", it / 1_000_000)} MHz",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Signal strength
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${String.format("%.1f", satellite.cn0DbHz)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = signalColor
                )
                Text(
                    text = "dB-Hz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GnssAnomalyCard(
    anomaly: GnssAnomaly,
    dateFormat: SimpleDateFormat
) {
    val severityColor = when (anomaly.severity) {
        com.flockyou.data.model.ThreatLevel.CRITICAL -> Color(0xFFD32F2F)
        com.flockyou.data.model.ThreatLevel.HIGH -> Color(0xFFF44336)
        com.flockyou.data.model.ThreatLevel.MEDIUM -> Color(0xFFFF9800)
        com.flockyou.data.model.ThreatLevel.LOW -> Color(0xFFFFC107)
        com.flockyou.data.model.ThreatLevel.INFO -> Color(0xFF2196F3)
    }

    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = severityColor.copy(alpha = 0.1f)
        ),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                    Text(
                        text = anomaly.type.emoji,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Column {
                        Text(
                            text = anomaly.type.displayName,
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
                        text = anomaly.confidence.displayName,
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

                    Text(
                        text = "Technical Details:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = anomaly.technicalDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )

                    if (anomaly.contributingFactors.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Contributing Factors:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        anomaly.contributingFactors.forEach { factor ->
                            Text(
                                text = "• $factor",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (anomaly.affectedConstellations.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Affected: ${anomaly.affectedConstellations.joinToString { it.displayName }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
private fun GnssMeasurementsCard(measurements: GnssMeasurementData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DataUsage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Raw GNSS Measurements",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                GnssMetric(label = "Measurements", value = "${measurements.measurementCount}")
                GnssMetric(
                    label = "Pseudorange",
                    value = if (measurements.hasPseudorange) "✓" else "✗"
                )
                GnssMetric(
                    label = "Carrier Phase",
                    value = if (measurements.hasCarrierPhase) "✓" else "✗"
                )
                GnssMetric(
                    label = "Doppler",
                    value = if (measurements.hasDoppler) "✓" else "✗"
                )
            }

            if (measurements.clockBiasNs != null || measurements.clockDriftNsPerSec != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                measurements.clockBiasNs?.let {
                    Text(
                        text = "Clock Bias: ${String.format("%.2f", it / 1_000_000)} ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                measurements.clockDriftNsPerSec?.let {
                    Text(
                        text = "Clock Drift: ${String.format("%.2f", it)} ns/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                measurements.clockDiscontinuityCount?.let {
                    Text(
                        text = "Discontinuities: $it",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Multipath indicator
            val multipathCount = measurements.multipathIndicators.count { it == 1 }
            if (multipathCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFFF9800).copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "⚠️ Multipath detected on $multipathCount satellites",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
            }
        }
    }
}

@Composable
private fun GnssEventCard(
    event: GnssEvent,
    dateFormat: SimpleDateFormat
) {
    val eventColor = if (event.isAnomaly) {
        when (event.threatLevel) {
            com.flockyou.data.model.ThreatLevel.CRITICAL -> Color(0xFFD32F2F)
            com.flockyou.data.model.ThreatLevel.HIGH -> Color(0xFFF44336)
            com.flockyou.data.model.ThreatLevel.MEDIUM -> Color(0xFFFF9800)
            com.flockyou.data.model.ThreatLevel.LOW -> Color(0xFFFFC107)
            com.flockyou.data.model.ThreatLevel.INFO -> Color(0xFF2196F3)
        }
    } else {
        MaterialTheme.colorScheme.primary
    }

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
                .background(eventColor)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (event.isAnomaly) FontWeight.Bold else FontWeight.Normal,
                color = if (event.isAnomaly) eventColor else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = dateFormat.format(Date(event.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

@OptIn(ExperimentalLayoutApi::class)
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
private fun SatelliteDetectionInfoCard() {
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
private fun SatelliteRuleCard(rule: SatelliteDetectionHeuristics.DetectionRules.SatelliteDetectionRule) {
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

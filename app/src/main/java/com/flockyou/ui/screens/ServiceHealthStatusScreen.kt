package com.flockyou.ui.screens

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.service.CellularMonitor
import com.flockyou.service.ScanningService
import com.flockyou.service.ScanningService.DetectorHealthStatus
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service Health Status Screen - Displays the health status of all background detectors
 * and subsystems. Shows real-time monitoring of detector health, errors, and restart counts.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServiceHealthStatusScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val detectorHealth = uiState.detectorHealth
    val isScanning = uiState.isScanning
    val scanStatus = uiState.scanStatus
    val isBound by viewModel.serviceConnectionBound.collectAsState()

    // Log state changes for debugging
    LaunchedEffect(detectorHealth, isScanning, scanStatus, isBound) {
        Log.d("ServiceHealthScreen", "State update: isBound=$isBound, isScanning=$isScanning, scanStatus=$scanStatus, detectors=${detectorHealth.size}")
    }

    val tabs = listOf("Overview", "Live Activity", "Detectors", "Threading", "Errors")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Service Health")
                        Text(
                            text = "Detector & subsystem monitoring",
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
                    // Manual refresh button
                    IconButton(onClick = { viewModel.requestRefresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    // Refresh indicator when scanning
                    if (isScanning) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse_alpha"
                        )
                        Icon(
                            Icons.Default.FiberManualRecord,
                            contentDescription = "Scanning Active",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                            modifier = Modifier.padding(end = 16.dp)
                        )
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
            // Status banner
            ServiceStatusBanner(
                isScanning = isScanning,
                scanStatus = scanStatus,
                detectorHealth = detectorHealth
            )

            // Tab row
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 0.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(title) },
                        icon = {
                            when (index) {
                                0 -> Icon(Icons.Default.Dashboard, contentDescription = null)
                                1 -> Icon(Icons.Default.Radar, contentDescription = null)
                                2 -> Icon(Icons.Default.Sensors, contentDescription = null)
                                3 -> Icon(Icons.Default.Memory, contentDescription = null)
                                4 -> Icon(Icons.Default.Warning, contentDescription = null)
                            }
                        }
                    )
                }
            }

            // Swipeable tab content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> HealthOverviewContent(
                        detectorHealth = detectorHealth,
                        scanStatus = scanStatus,
                        isScanning = isScanning,
                        isBound = isBound,
                        uiState = uiState
                    )
                    1 -> LiveActivityContent(
                        uiState = uiState,
                        isScanning = isScanning,
                        isBound = isBound
                    )
                    2 -> DetectorHealthContent(detectorHealth = detectorHealth)
                    3 -> ThreadingMonitorContent(uiState = uiState)
                    4 -> ErrorsContent(
                        detectorHealth = detectorHealth,
                        recentErrors = uiState.recentErrors
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusBanner(
    isScanning: Boolean,
    scanStatus: ScanningService.ScanStatus,
    detectorHealth: Map<String, DetectorHealthStatus>
) {
    val healthyCount = detectorHealth.values.count { it.isHealthy && it.isRunning }
    val unhealthyCount = detectorHealth.values.count { !it.isHealthy && it.isRunning }
    val totalRunning = detectorHealth.values.count { it.isRunning }

    val bannerColor = when {
        !isScanning -> MaterialTheme.colorScheme.surfaceVariant
        unhealthyCount > 0 -> MaterialTheme.colorScheme.errorContainer
        healthyCount == totalRunning && totalRunning > 0 -> Color(0xFF1B5E20).copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    val animatedColor by animateColorAsState(
        targetValue = bannerColor,
        animationSpec = tween(300),
        label = "banner_color"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = animatedColor
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        !isScanning -> Icons.Default.PauseCircle
                        unhealthyCount > 0 -> Icons.Default.Error
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    tint = when {
                        !isScanning -> MaterialTheme.colorScheme.onSurfaceVariant
                        unhealthyCount > 0 -> MaterialTheme.colorScheme.error
                        else -> Color(0xFF4CAF50)
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = when {
                            !isScanning -> "Service Idle"
                            unhealthyCount > 0 -> "$unhealthyCount Detector${if (unhealthyCount > 1) "s" else ""} Unhealthy"
                            else -> "All Systems Operational"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$healthyCount of $totalRunning detectors healthy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Scan status badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ) {
                Text(
                    text = when (scanStatus) {
                        is ScanningService.ScanStatus.Idle -> "Idle"
                        is ScanningService.ScanStatus.Starting -> "Starting"
                        is ScanningService.ScanStatus.Active -> "Active"
                        is ScanningService.ScanStatus.Stopping -> "Stopping"
                        is ScanningService.ScanStatus.Error -> "Error"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun HealthOverviewContent(
    detectorHealth: Map<String, DetectorHealthStatus>,
    scanStatus: ScanningService.ScanStatus,
    isScanning: Boolean,
    isBound: Boolean,
    uiState: MainUiState
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // IPC Connection Status Card (for debugging)
        item {
            IpcConnectionStatusCard(isBound = isBound, isScanning = isScanning, scanStatus = scanStatus)
        }

        // Quick stats card
        item {
            QuickStatsCard(detectorHealth = detectorHealth, isScanning = isScanning, scanStats = uiState.scanStats)
        }

        // Subsystem status section
        item {
            Text(
                text = "Subsystem Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        item {
            SubsystemStatusCard(uiState = uiState)
        }

        // Detector overview grid
        item {
            Text(
                text = "Detector Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        item {
            DetectorOverviewGrid(detectorHealth = detectorHealth)
        }
    }
}

@Composable
private fun QuickStatsCard(
    detectorHealth: Map<String, DetectorHealthStatus>,
    isScanning: Boolean,
    scanStats: ScanningService.ScanStatistics
) {
    val totalDetectors = if (detectorHealth.isEmpty()) 8 else detectorHealth.size // Default to 8 if not loaded
    val runningDetectors = detectorHealth.values.count { it.isRunning }
    val healthyDetectors = detectorHealth.values.count { it.isHealthy && it.isRunning }
    val totalRestarts = detectorHealth.values.sumOf { it.restartCount }
    val totalFailures = detectorHealth.values.sumOf { it.consecutiveFailures }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // First row: Detector stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.Sensors,
                    value = "$runningDetectors/$totalDetectors",
                    label = "Running",
                    color = if (runningDetectors > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                StatItem(
                    icon = Icons.Default.CheckCircle,
                    value = healthyDetectors.toString(),
                    label = "Healthy",
                    color = Color(0xFF4CAF50)
                )
                StatItem(
                    icon = Icons.Default.Refresh,
                    value = totalRestarts.toString(),
                    label = "Restarts",
                    color = MaterialTheme.colorScheme.tertiary
                )
                StatItem(
                    icon = Icons.Default.ErrorOutline,
                    value = totalFailures.toString(),
                    label = "Failures",
                    color = if (totalFailures > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }

            // Divider
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // Second row: Scan stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.Bluetooth,
                    value = scanStats.totalBleScans.toString(),
                    label = "BLE Scans",
                    color = MaterialTheme.colorScheme.primary
                )
                StatItem(
                    icon = Icons.Default.Wifi,
                    value = scanStats.successfulWifiScans.toString(),
                    label = "WiFi Scans",
                    color = MaterialTheme.colorScheme.primary
                )
                StatItem(
                    icon = Icons.Default.DevicesOther,
                    value = scanStats.bleDevicesSeen.toString(),
                    label = "BLE Seen",
                    color = MaterialTheme.colorScheme.secondary
                )
                StatItem(
                    icon = Icons.Default.NetworkCheck,
                    value = scanStats.wifiNetworksSeen.toString(),
                    label = "WiFi Seen",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SubsystemStatusCard(uiState: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SubsystemRow("BLE Scanner", uiState.bleStatus)
            SubsystemRow("WiFi Scanner", uiState.wifiStatus)
            SubsystemRow("Location", uiState.locationStatus)
            SubsystemRow("Cellular", uiState.cellularStatus)
            SubsystemRow("Satellite", uiState.satelliteStatus)
        }
    }
}

@Composable
private fun SubsystemRow(
    name: String,
    status: ScanningService.SubsystemStatus
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            val (statusText, statusColor) = when (status) {
                ScanningService.SubsystemStatus.Idle -> "Idle" to MaterialTheme.colorScheme.outline
                ScanningService.SubsystemStatus.Active -> "Active" to Color(0xFF4CAF50)
                ScanningService.SubsystemStatus.Disabled -> "Disabled" to MaterialTheme.colorScheme.outline
                is ScanningService.SubsystemStatus.Error -> "Error" to MaterialTheme.colorScheme.error
                is ScanningService.SubsystemStatus.PermissionDenied -> "No Permission" to MaterialTheme.colorScheme.error
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor
            )
        }
    }
}

@Composable
private fun DetectorOverviewGrid(detectorHealth: Map<String, DetectorHealthStatus>) {
    val detectors = listOf(
        DetectorHealthStatus.DETECTOR_BLE to "BLE",
        DetectorHealthStatus.DETECTOR_WIFI to "WiFi",
        DetectorHealthStatus.DETECTOR_ULTRASONIC to "Ultrasonic",
        DetectorHealthStatus.DETECTOR_ROGUE_WIFI to "Rogue WiFi",
        DetectorHealthStatus.DETECTOR_RF_SIGNAL to "RF Signal",
        DetectorHealthStatus.DETECTOR_CELLULAR to "Cellular",
        DetectorHealthStatus.DETECTOR_GNSS to "GNSS",
        DetectorHealthStatus.DETECTOR_SATELLITE to "Satellite"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detectors.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (detectorId, displayName) ->
                    val health = detectorHealth[detectorId]
                    DetectorMiniCard(
                        name = displayName,
                        health = health,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill empty space if odd number
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DetectorMiniCard(
    name: String,
    health: DetectorHealthStatus?,
    modifier: Modifier = Modifier
) {
    val isRunning = health?.isRunning == true
    val isHealthy = health?.isHealthy == true

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = when {
                !isRunning -> MaterialTheme.colorScheme.surfaceVariant
                !isHealthy -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !isRunning -> MaterialTheme.colorScheme.outline
                                !isHealthy -> MaterialTheme.colorScheme.error
                                else -> Color(0xFF4CAF50)
                            }
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isRunning && health != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (health.restartCount > 0) "${health.restartCount} restarts" else "OK",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (health.restartCount > 0)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
private fun DetectorHealthContent(detectorHealth: Map<String, DetectorHealthStatus>) {
    val detectors = listOf(
        DetectorHealthStatus.DETECTOR_BLE to ("BLE Scanner" to Icons.Default.Bluetooth),
        DetectorHealthStatus.DETECTOR_WIFI to ("WiFi Scanner" to Icons.Default.Wifi),
        DetectorHealthStatus.DETECTOR_ULTRASONIC to ("Ultrasonic" to Icons.Default.Hearing),
        DetectorHealthStatus.DETECTOR_ROGUE_WIFI to ("Rogue WiFi" to Icons.Default.WifiFind),
        DetectorHealthStatus.DETECTOR_RF_SIGNAL to ("RF Signal" to Icons.Default.SignalCellularAlt),
        DetectorHealthStatus.DETECTOR_CELLULAR to ("Cellular" to Icons.Default.CellTower),
        DetectorHealthStatus.DETECTOR_GNSS to ("GNSS" to Icons.Default.GpsFixed),
        DetectorHealthStatus.DETECTOR_SATELLITE to ("Satellite" to Icons.Default.SatelliteAlt)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(detectors) { (detectorId, info) ->
            val (displayName, icon) = info
            val health = detectorHealth[detectorId]
            DetectorHealthCard(
                name = displayName,
                icon = icon,
                health = health
            )
        }
    }
}

@Composable
private fun DetectorHealthCard(
    name: String,
    icon: ImageVector,
    health: DetectorHealthStatus?
) {
    val isRunning = health?.isRunning == true
    val isHealthy = health?.isHealthy == true
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = when {
                            !isRunning -> MaterialTheme.colorScheme.outline
                            !isHealthy -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Status badge
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = when {
                        !isRunning -> MaterialTheme.colorScheme.surfaceVariant
                        !isHealthy -> MaterialTheme.colorScheme.errorContainer
                        else -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                    }
                ) {
                    Text(
                        text = when {
                            !isRunning -> "Stopped"
                            !isHealthy -> "Unhealthy"
                            else -> "Healthy"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            !isRunning -> MaterialTheme.colorScheme.onSurfaceVariant
                            !isHealthy -> MaterialTheme.colorScheme.error
                            else -> Color(0xFF4CAF50)
                        }
                    )
                }
            }

            if (health != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Stats grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailColumn(
                        label = "Last Success",
                        value = health.lastSuccessfulScan?.let { dateFormat.format(Date(it)) } ?: "--"
                    )
                    DetailColumn(
                        label = "Failures",
                        value = health.consecutiveFailures.toString(),
                        valueColor = if (health.consecutiveFailures > 0) MaterialTheme.colorScheme.error else null
                    )
                    DetailColumn(
                        label = "Restarts",
                        value = health.restartCount.toString(),
                        valueColor = if (health.restartCount > 0) MaterialTheme.colorScheme.tertiary else null
                    )
                }

                // Error message if present
                health.lastError?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Last Error",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                health.lastErrorTime?.let { time ->
                                    Text(
                                        text = dateFormat.format(Date(time)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No health data available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailColumn(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorsContent(
    detectorHealth: Map<String, DetectorHealthStatus>,
    recentErrors: List<ScanningService.ScanError>
) {
    val detectorErrors = detectorHealth.values
        .filter { it.lastError != null }
        .sortedByDescending { it.lastErrorTime }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Detector errors
        if (detectorErrors.isNotEmpty()) {
            item {
                Text(
                    text = "Detector Errors",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(detectorErrors) { health ->
                ErrorCard(
                    source = health.name,
                    message = health.lastError ?: "",
                    timestamp = health.lastErrorTime,
                    isRecoverable = health.consecutiveFailures < 5
                )
            }
        }

        // Recent service errors
        if (recentErrors.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Service Errors",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(recentErrors) { error ->
                ErrorCard(
                    source = error.subsystem,
                    message = error.message,
                    timestamp = error.timestamp,
                    isRecoverable = error.recoverable
                )
            }
        }

        // Empty state
        if (detectorErrors.isEmpty() && recentErrors.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Errors",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "All systems operating normally",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    source: String,
    message: String,
    timestamp: Long?,
    isRecoverable: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (isRecoverable) Icons.Default.Warning else Icons.Default.Error,
                contentDescription = null,
                tint = if (isRecoverable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    timestamp?.let {
                        Text(
                            text = dateFormat.format(Date(it)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isRecoverable) "Auto-recovery enabled" else "Manual restart may be required",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRecoverable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * IPC Connection Status Card - Shows the current IPC connection state and service status.
 * This is crucial for debugging why data might not be flowing.
 */
@Composable
private fun IpcConnectionStatusCard(
    isBound: Boolean,
    isScanning: Boolean,
    scanStatus: ScanningService.ScanStatus
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !isBound -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                isScanning -> Color(0xFF1B5E20).copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isBound) Icons.Default.Link else Icons.Default.LinkOff,
                    contentDescription = null,
                    tint = if (isBound) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "IPC Connection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isBound) "Connected to scanning service" else "NOT CONNECTED - Data will not update!",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isBound) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
            }

            if (isBound) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scanning: ${if (isScanning) "Active" else "Inactive"}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(
                        text = "Status: ${scanStatus.javaClass.simpleName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Live Activity Content - Shows real-time scan activity with detailed statistics.
 * In advanced mode, shows raw data from all subsystems.
 */
@Composable
private fun LiveActivityContent(
    uiState: MainUiState,
    isScanning: Boolean,
    isBound: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection warning if not bound
        if (!isBound) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Service Not Connected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "IPC connection to scanning service is not established. Try restarting the app.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // Scan Statistics Card
        item {
            Text(
                text = "Scan Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            ScanStatisticsCard(scanStats = uiState.scanStats, dateFormat = dateFormat)
        }

        // Seen Devices Summary
        item {
            Text(
                text = "Seen Devices (Live)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            SeenDevicesSummaryCard(
                bleDevices = uiState.seenBleDevices,
                wifiNetworks = uiState.seenWifiNetworks,
                cellTowers = uiState.seenCellTowers
            )
        }

        // Advanced Mode: Raw Subsystem Data
        if (uiState.advancedMode) {
            item {
                Text(
                    text = "Raw Subsystem Data (Advanced)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // RF Signal Data
            item {
                RawDataCard(
                    title = "RF Signal Analysis",
                    icon = Icons.Default.SignalCellularAlt,
                    data = buildString {
                        appendLine("Total Networks: ${uiState.rfStatus?.totalNetworks ?: 0}")
                        appendLine("Anomalies: ${uiState.rfAnomalies.size}")
                        appendLine("Drones Detected: ${uiState.detectedDrones.size}")
                        if (uiState.rfStatus != null) {
                            appendLine("2.4GHz: ${uiState.rfStatus.band24GHz}, 5GHz: ${uiState.rfStatus.band5GHz}")
                            appendLine("Jammer Suspected: ${uiState.rfStatus.jammerSuspected}")
                        }
                    }
                )
            }

            // Ultrasonic Data
            item {
                RawDataCard(
                    title = "Ultrasonic Detection",
                    icon = Icons.Default.Hearing,
                    data = buildString {
                        appendLine("Scanning: ${uiState.ultrasonicStatus?.isScanning ?: false}")
                        appendLine("Anomalies: ${uiState.ultrasonicAnomalies.size}")
                        appendLine("Beacons: ${uiState.ultrasonicBeacons.size}")
                        if (uiState.ultrasonicStatus != null) {
                            appendLine("Noise Floor: ${String.format("%.1f", uiState.ultrasonicStatus.noiseFloorDb)} dB")
                            appendLine("Activity Detected: ${uiState.ultrasonicStatus.ultrasonicActivityDetected}")
                        }
                    }
                )
            }

            // GNSS Data
            item {
                RawDataCard(
                    title = "GNSS Satellite Monitor",
                    icon = Icons.Default.GpsFixed,
                    data = buildString {
                        appendLine("Total Satellites: ${uiState.gnssStatus?.totalSatellites ?: 0}")
                        appendLine("Satellites Used: ${uiState.gnssStatus?.satellitesUsedInFix ?: 0}")
                        appendLine("Has Fix: ${uiState.gnssStatus?.hasFix ?: false}")
                        appendLine("Anomalies: ${uiState.gnssAnomalies.size}")
                        appendLine("Events: ${uiState.gnssEvents.size}")
                        if (uiState.gnssMeasurements != null) {
                            appendLine("Has Raw Measurements: Yes")
                        }
                    }
                )
            }

            // Cellular Data
            item {
                RawDataCard(
                    title = "Cellular Monitor",
                    icon = Icons.Default.CellTower,
                    data = buildString {
                        appendLine("Cell Towers Seen: ${uiState.seenCellTowers.size}")
                        appendLine("Anomalies: ${uiState.cellularAnomalies.size}")
                        appendLine("Events: ${uiState.cellularEvents.size}")
                        if (uiState.cellStatus != null) {
                            appendLine("Network Type: ${uiState.cellStatus.networkType}")
                        }
                    }
                )
            }

            // Rogue WiFi Data
            item {
                RawDataCard(
                    title = "Rogue WiFi Detection",
                    icon = Icons.Default.WifiFind,
                    data = buildString {
                        appendLine("Total Networks: ${uiState.rogueWifiStatus?.totalNetworks ?: 0}")
                        appendLine("Suspicious Networks: ${uiState.suspiciousNetworks.size}")
                        appendLine("Anomalies: ${uiState.rogueWifiAnomalies.size}")
                        if (uiState.rogueWifiStatus != null) {
                            appendLine("Open Networks: ${uiState.rogueWifiStatus.openNetworks}")
                            appendLine("Potential Evil Twins: ${uiState.rogueWifiStatus.potentialEvilTwins}")
                        }
                    }
                )
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Enable Advanced Mode in Settings to see raw subsystem data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanStatisticsCard(
    scanStats: ScanningService.ScanStatistics,
    dateFormat: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatColumn(
                    label = "BLE Scans",
                    value = scanStats.totalBleScans.toString(),
                    subValue = scanStats.lastBleSuccessTime?.let { "Last: ${dateFormat.format(Date(it))}" }
                )
                StatColumn(
                    label = "WiFi Scans",
                    value = "${scanStats.successfulWifiScans}/${scanStats.totalWifiScans}",
                    subValue = if (scanStats.throttledWifiScans > 0) "Throttled: ${scanStats.throttledWifiScans}" else null
                )
                StatColumn(
                    label = "BLE Devices",
                    value = scanStats.bleDevicesSeen.toString(),
                    subValue = null
                )
                StatColumn(
                    label = "WiFi Networks",
                    value = scanStats.wifiNetworksSeen.toString(),
                    subValue = scanStats.lastWifiSuccessTime?.let { "Last: ${dateFormat.format(Date(it))}" }
                )
            }
        }
    }
}

@Composable
private fun StatColumn(
    label: String,
    value: String,
    subValue: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        subValue?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SeenDevicesSummaryCard(
    bleDevices: List<ScanningService.SeenDevice>,
    wifiNetworks: List<ScanningService.SeenDevice>,
    cellTowers: List<CellularMonitor.SeenCellTower>
) {
    // Consider devices "active" if seen in the last 60 seconds
    val currentTime = System.currentTimeMillis()
    val activeThreshold = 60000L

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // BLE Devices
            DeviceCountRow(
                icon = Icons.Default.Bluetooth,
                label = "BLE Devices",
                count = bleDevices.size,
                activeCount = bleDevices.count { currentTime - it.lastSeen < activeThreshold }
            )

            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            // WiFi Networks
            DeviceCountRow(
                icon = Icons.Default.Wifi,
                label = "WiFi Networks",
                count = wifiNetworks.size,
                activeCount = wifiNetworks.count { currentTime - it.lastSeen < activeThreshold }
            )

            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            // Cell Towers
            DeviceCountRow(
                icon = Icons.Default.CellTower,
                label = "Cell Towers",
                count = cellTowers.size,
                activeCount = cellTowers.count { currentTime - it.lastSeen < activeThreshold }
            )
        }
    }
}

@Composable
private fun DeviceCountRow(
    icon: ImageVector,
    label: String,
    count: Int,
    activeCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$count total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (activeCount > 0) Color(0xFF4CAF50).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "$activeCount active",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activeCount > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun RawDataCard(
    title: String,
    icon: ImageVector,
    data: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = data.trim(),
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Threading Monitor Content - Shows scanner threading metrics and performance data.
 */
@Composable
private fun ThreadingMonitorContent(uiState: MainUiState) {
    val systemState = uiState.threadingSystemState
    val scannerStates = uiState.threadingScannerStates
    val alerts = uiState.threadingAlerts

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // System Overview Card
        item {
            Text(
                text = "System Overview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            ThreadingSystemCard(systemState = systemState)
        }

        // Health Score Card
        item {
            HealthScoreCard(healthScore = systemState?.healthScore ?: 100f)
        }

        // Threading Alerts
        if (alerts.isNotEmpty()) {
            item {
                Text(
                    text = "Threading Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(alerts) { alert ->
                ThreadingAlertCard(alert = alert)
            }
        }

        // Scanner States
        item {
            Text(
                text = "Scanner Performance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        val sortedScanners = scannerStates.entries.sortedBy { it.key }
        items(sortedScanners) { (scannerId, state) ->
            ScannerThreadCard(scannerId = scannerId, state = state)
        }

        // Empty state
        if (systemState == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Threading Data",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Start scanning to see threading metrics",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadingSystemCard(systemState: com.flockyou.monitoring.ScannerThreadingMonitor.SystemThreadingState?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Thread counts row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.Memory,
                    value = systemState?.processThreadCount?.toString() ?: "--",
                    label = "Threads",
                    color = MaterialTheme.colorScheme.primary
                )
                StatItem(
                    icon = Icons.Default.Pending,
                    value = systemState?.totalActiveJobs?.toString() ?: "--",
                    label = "Jobs",
                    color = MaterialTheme.colorScheme.secondary
                )
                StatItem(
                    icon = Icons.Default.Speed,
                    value = String.format("%.0f", systemState?.totalExecutionsPerMinute ?: 0.0),
                    label = "/min",
                    color = MaterialTheme.colorScheme.tertiary
                )
                StatItem(
                    icon = Icons.Default.CheckCircle,
                    value = String.format("%.0f%%", (systemState?.averageSuccessRate ?: 1.0) * 100),
                    label = "Success",
                    color = Color(0xFF4CAF50)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // Memory and IPC row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.Storage,
                    value = "${systemState?.heapUsedMb ?: 0}MB",
                    label = "Heap",
                    color = MaterialTheme.colorScheme.primary
                )
                StatItem(
                    icon = Icons.Default.Link,
                    value = systemState?.ipcConnectedClients?.toString() ?: "0",
                    label = "IPC",
                    color = MaterialTheme.colorScheme.secondary
                )
                StatItem(
                    icon = Icons.Default.Send,
                    value = String.format("%.0f", systemState?.ipcMessagesSentPerMinute ?: 0.0),
                    label = "Sent/m",
                    color = MaterialTheme.colorScheme.tertiary
                )
                StatItem(
                    icon = Icons.Default.Timer,
                    value = String.format("%.0fms", systemState?.ipcAverageLatencyMs ?: 0.0),
                    label = "Latency",
                    color = if ((systemState?.ipcAverageLatencyMs ?: 0.0) > 100) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun HealthScoreCard(healthScore: Float) {
    val scoreColor = when {
        healthScore >= 80 -> Color(0xFF4CAF50)
        healthScore >= 60 -> Color(0xFFFFC107)
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scoreColor.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        healthScore >= 80 -> Icons.Default.CheckCircle
                        healthScore >= 60 -> Icons.Default.Warning
                        else -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = scoreColor,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Health Score",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            healthScore >= 80 -> "System operating normally"
                            healthScore >= 60 -> "Some performance degradation"
                            else -> "Performance issues detected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "${healthScore.toInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = scoreColor
            )
        }
    }
}

@Composable
private fun ThreadingAlertCard(alert: com.flockyou.monitoring.ScannerThreadingMonitor.ThreadingAlert) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val alertColor = when (alert.severity) {
        com.flockyou.monitoring.ScannerThreadingMonitor.AlertSeverity.ERROR -> MaterialTheme.colorScheme.error
        com.flockyou.monitoring.ScannerThreadingMonitor.AlertSeverity.WARNING -> Color(0xFFFFC107)
        com.flockyou.monitoring.ScannerThreadingMonitor.AlertSeverity.INFO -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = alertColor.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = when (alert.severity) {
                    com.flockyou.monitoring.ScannerThreadingMonitor.AlertSeverity.ERROR -> Icons.Default.Error
                    com.flockyou.monitoring.ScannerThreadingMonitor.AlertSeverity.WARNING -> Icons.Default.Warning
                    com.flockyou.monitoring.ScannerThreadingMonitor.AlertSeverity.INFO -> Icons.Default.Info
                },
                contentDescription = null,
                tint = alertColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = alert.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = dateFormat.format(Date(alert.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = alert.message,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ScannerThreadCard(
    scannerId: String,
    state: com.flockyou.monitoring.ScannerThreadingMonitor.ScannerThreadState
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val displayName = scannerId.replace("_", " ")
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                state.isStalled -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                state.successRate < 0.8 && state.totalExecutions > 10 -> Color(0xFFFFC107).copy(alpha = 0.15f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.isStalled -> MaterialTheme.colorScheme.error
                                    state.activeJobCount > 0 -> Color(0xFF4CAF50)
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        state.isStalled -> MaterialTheme.colorScheme.errorContainer
                        state.successRate < 0.8 -> Color(0xFFFFC107).copy(alpha = 0.3f)
                        else -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                    }
                ) {
                    Text(
                        text = when {
                            state.isStalled -> "Stalled"
                            state.activeJobCount > 0 -> "Active"
                            else -> "Idle"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            state.isStalled -> MaterialTheme.colorScheme.error
                            state.activeJobCount > 0 -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Executions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.totalExecutions} (${String.format("%.1f", state.executionsPerMinute)}/m)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Success",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${String.format("%.0f", state.successRate * 100)}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (state.successRate < 0.8) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Avg Time",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.avgExecutionTimeMs.toLong()}ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Last execution time
            if (state.lastExecutionTime > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last: ${dateFormat.format(Date(state.lastExecutionTime))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Last error
            state.lastErrorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

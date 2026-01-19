package com.flockyou.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.service.ScanningService
import com.flockyou.service.ScanningService.DetectorHealthStatus
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service Health Status Screen - Displays the health status of all background detectors
 * and subsystems. Shows real-time monitoring of detector health, errors, and restart counts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceHealthStatusScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val detectorHealth = uiState.detectorHealth
    val isScanning = uiState.isScanning
    val scanStatus = uiState.scanStatus

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "Detectors", "Errors")

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
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                        icon = {
                            when (index) {
                                0 -> Icon(Icons.Default.Dashboard, contentDescription = null)
                                1 -> Icon(Icons.Default.Sensors, contentDescription = null)
                                2 -> Icon(Icons.Default.Warning, contentDescription = null)
                            }
                        }
                    )
                }
            }

            // Tab content
            when (selectedTab) {
                0 -> HealthOverviewContent(
                    detectorHealth = detectorHealth,
                    scanStatus = scanStatus,
                    isScanning = isScanning,
                    uiState = uiState
                )
                1 -> DetectorHealthContent(detectorHealth = detectorHealth)
                2 -> ErrorsContent(
                    detectorHealth = detectorHealth,
                    recentErrors = uiState.recentErrors
                )
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
    uiState: MainUiState
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quick stats card
        item {
            QuickStatsCard(detectorHealth = detectorHealth, isScanning = isScanning)
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
    isScanning: Boolean
) {
    val totalDetectors = detectorHealth.size
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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

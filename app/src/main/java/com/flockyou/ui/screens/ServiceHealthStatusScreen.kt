package com.flockyou.ui.screens

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.service.CellularMonitor
import com.flockyou.service.ScanningService
import com.flockyou.service.ScanningService.DetectorHealthStatus
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service Health Status Screen - Displays the health status of all background detectors
 * and subsystems. Shows real-time monitoring of detector health, errors, and restart counts.
 *
 * Consolidated tabs: Dashboard | Detectors | Diagnostics
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
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

    // Track refreshing state
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.requestRefresh()
        }
    )

    // Reset refreshing state after data updates
    LaunchedEffect(detectorHealth, uiState.scanStats) {
        if (isRefreshing) {
            isRefreshing = false
        }
    }

    // Calculate error counts for badges
    val detectorErrorCount = detectorHealth.values.count { !it.isHealthy && it.isRunning }
    val totalErrorCount = detectorErrorCount + uiState.recentErrors.size

    // Last updated timestamp
    var lastUpdated by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(detectorHealth, uiState.scanStats) {
        lastUpdated = System.currentTimeMillis()
    }

    // Log state changes for debugging
    LaunchedEffect(detectorHealth, isScanning, scanStatus, isBound) {
        Log.d("ServiceHealthScreen", "State update: isBound=$isBound, isScanning=$isScanning, scanStatus=$scanStatus, detectors=${detectorHealth.size}")
    }

    // Consolidated tabs: Dashboard | Detectors | Diagnostics
    val tabs = listOf("Dashboard", "Detectors", "Diagnostics")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Service Health")
                        LastUpdatedText(timestamp = lastUpdated)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Manual refresh button
                    IconButton(onClick = {
                        isRefreshing = true
                        viewModel.requestRefresh()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    // Refresh indicator when scanning
                    if (isScanning) {
                        PulsingStatusIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pullRefresh(pullRefreshState)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Alert Banner - shows when there are errors or unhealthy detectors
                AlertBanner(
                    detectorHealth = detectorHealth,
                    recentErrors = uiState.recentErrors,
                    onNavigateToDetectors = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    },
                    onNavigateToDiagnostics = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(2)
                        }
                    }
                )

                // Tab row with badges
                TabRow(
                    selectedTabIndex = pagerState.currentPage
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
                                val badgeCount = when (index) {
                                    1 -> detectorErrorCount  // Detectors tab
                                    2 -> totalErrorCount     // Diagnostics tab
                                    else -> 0
                                }
                                TabIconWithBadge(
                                    icon = when (index) {
                                        0 -> Icons.Default.Dashboard
                                        1 -> Icons.Default.Sensors
                                        2 -> Icons.Default.BugReport
                                        else -> Icons.Default.Info
                                    },
                                    badgeCount = badgeCount
                                )
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
                        0 -> DashboardContent(
                            detectorHealth = detectorHealth,
                            scanStatus = scanStatus,
                            isScanning = isScanning,
                            isBound = isBound,
                            uiState = uiState,
                            onNavigateToErrors = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(2)
                                }
                            },
                            onNavigateToUnhealthy = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            }
                        )
                        1 -> DetectorHealthContent(
                            detectorHealth = detectorHealth,
                            onNavigateToError = { detectorId ->
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(2)
                                }
                            }
                        )
                        2 -> DiagnosticsContent(
                            detectorHealth = detectorHealth,
                            recentErrors = uiState.recentErrors,
                            uiState = uiState,
                            onNavigateToDetector = { detectorId ->
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            }
                        )
                    }
                }
            }

            // Pull refresh indicator
            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ============================================================================
// NEW COMPOSABLES
// ============================================================================

/**
 * Last Updated Text - Shows timestamp with auto-refresh indicator
 */
@Composable
private fun LastUpdatedText(timestamp: Long) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Text(
        text = "Updated ${dateFormat.format(Date(timestamp))}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Pulsing Status Indicator - Animated dot for active states
 */
@Composable
fun PulsingStatusIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    size: Int = 12
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .size(size.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

/**
 * Animated Stat Value - Slide-in transitions when values change
 */
@Composable
fun AnimatedStatValue(
    value: Int,
    label: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInVertically { -it } + fadeIn() togetherWith slideOutVertically { it } + fadeOut()
                } else {
                    slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut()
                }
            },
            label = "stat_value"
        ) { targetValue ->
            Text(
                text = targetValue.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Tab Icon With Badge - Shows error count badge on tab icons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabIconWithBadge(
    icon: ImageVector,
    badgeCount: Int
) {
    if (badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Text(
                        text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        ) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}

/**
 * Alert Banner - Tappable banner showing critical issues
 */
@Composable
private fun AlertBanner(
    detectorHealth: Map<String, DetectorHealthStatus>,
    recentErrors: List<ScanningService.ScanError>,
    onNavigateToDetectors: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    val unhealthyCount = detectorHealth.values.count { !it.isHealthy && it.isRunning }
    val errorCount = recentErrors.size

    if (unhealthyCount == 0 && errorCount == 0) return

    val infiniteTransition = rememberInfiniteTransition(label = "alert_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alert_alpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (unhealthyCount > 0) onNavigateToDetectors()
                else onNavigateToDiagnostics()
            },
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = alpha)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        if (unhealthyCount > 0) append("$unhealthyCount unhealthy detector${if (unhealthyCount > 1) "s" else ""}")
                        if (unhealthyCount > 0 && errorCount > 0) append(" • ")
                        if (errorCount > 0) append("$errorCount error${if (errorCount > 1) "s" else ""}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Tap to view details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * Collapsible Section - Expandable area for less critical info
 */
@Composable
fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column {
            // Header (always visible, clickable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            // Expandable content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * Skeleton Loading Card - Shimmer placeholder during initial load
 */
@Composable
fun SkeletonLoadingCard(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = shimmerAlpha)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = shimmerAlpha))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = shimmerAlpha * 0.7f))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = shimmerAlpha * 0.7f))
            )
        }
    }
}

// ============================================================================
// DASHBOARD TAB (merged Overview + Live Activity)
// ============================================================================

@Composable
private fun DashboardContent(
    detectorHealth: Map<String, DetectorHealthStatus>,
    scanStatus: ScanningService.ScanStatus,
    isScanning: Boolean,
    isBound: Boolean,
    uiState: MainUiState,
    onNavigateToErrors: () -> Unit,
    onNavigateToUnhealthy: () -> Unit
) {
    val totalDetectors = if (detectorHealth.isEmpty()) 8 else detectorHealth.size
    val healthyCount = detectorHealth.values.count { it.isHealthy && it.isRunning }
    val unhealthyCount = detectorHealth.values.count { !it.isHealthy && it.isRunning }
    val errorCount = uiState.recentErrors.size

    // Show skeleton loading if no data yet
    if (detectorHealth.isEmpty() && !isBound) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(4) {
                SkeletonLoadingCard()
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. System Health Score - Large circular indicator
        item {
            SystemHealthScoreCard(
                healthyCount = healthyCount,
                totalDetectors = totalDetectors,
                isScanning = isScanning
            )
        }

        // 2. Quick Actions Row
        item {
            QuickActionsRow(
                errorCount = errorCount,
                unhealthyCount = unhealthyCount,
                onViewErrors = onNavigateToErrors,
                onViewUnhealthy = onNavigateToUnhealthy
            )
        }

        // 3. Subsystem Status Card
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

        // 4. Live Scan Activity
        item {
            Text(
                text = "Live Scan Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        item {
            LiveScanActivityCard(scanStats = uiState.scanStats, isScanning = isScanning)
        }

        // 5. Collapsible Seen Devices
        item {
            CollapsibleSection(
                title = "Seen Devices",
                icon = Icons.Default.DevicesOther,
                initiallyExpanded = false
            ) {
                SeenDevicesSummaryContent(
                    bleDevices = uiState.seenBleDevices,
                    wifiNetworks = uiState.seenWifiNetworks,
                    cellTowers = uiState.seenCellTowers
                )
            }
        }

        // 6. Collapsible IPC Debug Info (demoted)
        item {
            CollapsibleSection(
                title = "Debug Info",
                icon = Icons.Default.BugReport,
                initiallyExpanded = false
            ) {
                IpcDebugContent(isBound = isBound, isScanning = isScanning, scanStatus = scanStatus)
            }
        }
    }
}

/**
 * System Health Score Card - Large circular health indicator
 */
@Composable
private fun SystemHealthScoreCard(
    healthyCount: Int,
    totalDetectors: Int,
    isScanning: Boolean
) {
    val healthPercentage = if (totalDetectors > 0) healthyCount.toFloat() / totalDetectors else 0f
    val scoreColor = when {
        !isScanning -> MaterialTheme.colorScheme.outline
        healthPercentage >= 0.8f -> Color(0xFF4CAF50)
        healthPercentage >= 0.5f -> Color(0xFFFFC107)
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scoreColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Circular health indicator
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .border(8.dp, scoreColor, CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$healthyCount",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor
                    )
                    Text(
                        text = "of $totalDetectors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status text
            Column {
                Text(
                    text = when {
                        !isScanning -> "Service Idle"
                        healthPercentage >= 1f -> "All Healthy"
                        healthPercentage >= 0.8f -> "Mostly Healthy"
                        healthPercentage >= 0.5f -> "Some Issues"
                        else -> "Needs Attention"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isScanning) "Detectors running" else "Start scan to monitor",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Quick Actions Row - Chips for quick navigation
 */
@Composable
private fun QuickActionsRow(
    errorCount: Int,
    unhealthyCount: Int,
    onViewErrors: () -> Unit,
    onViewUnhealthy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (errorCount > 0) {
            AssistChip(
                onClick = onViewErrors,
                label = { Text("$errorCount Error${if (errorCount > 1) "s" else ""}") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            )
        }

        if (unhealthyCount > 0) {
            AssistChip(
                onClick = onViewUnhealthy,
                label = { Text("$unhealthyCount Unhealthy") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFFFFC107).copy(alpha = 0.2f)
                )
            )
        }

        if (errorCount == 0 && unhealthyCount == 0) {
            AssistChip(
                onClick = { },
                label = { Text("All Systems OK") },
                leadingIcon = {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f)
                )
            )
        }
    }
}

/**
 * Live Scan Activity Card - Scan stats with animated values
 */
@Composable
private fun LiveScanActivityCard(
    scanStats: ScanningService.ScanStatistics,
    isScanning: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AnimatedStatValue(
                    value = scanStats.totalBleScans,
                    label = "BLE Scans",
                    icon = Icons.Default.Bluetooth,
                    color = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                AnimatedStatValue(
                    value = scanStats.successfulWifiScans,
                    label = "WiFi Scans",
                    icon = Icons.Default.Wifi,
                    color = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                AnimatedStatValue(
                    value = scanStats.bleDevicesSeen,
                    label = "BLE Seen",
                    icon = Icons.Default.DevicesOther,
                    color = MaterialTheme.colorScheme.secondary
                )
                AnimatedStatValue(
                    value = scanStats.wifiNetworksSeen,
                    label = "WiFi Seen",
                    icon = Icons.Default.NetworkCheck,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

/**
 * Seen Devices Summary Content - For collapsible section
 */
@Composable
private fun SeenDevicesSummaryContent(
    bleDevices: List<ScanningService.SeenDevice>,
    wifiNetworks: List<ScanningService.SeenDevice>,
    cellTowers: List<CellularMonitor.SeenCellTower>
) {
    val currentTime = System.currentTimeMillis()
    val activeThreshold = 60000L

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DeviceCountRow(
            icon = Icons.Default.Bluetooth,
            label = "BLE Devices",
            count = bleDevices.size,
            activeCount = bleDevices.count { currentTime - it.lastSeen < activeThreshold }
        )
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        DeviceCountRow(
            icon = Icons.Default.Wifi,
            label = "WiFi Networks",
            count = wifiNetworks.size,
            activeCount = wifiNetworks.count { currentTime - it.lastSeen < activeThreshold }
        )
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        DeviceCountRow(
            icon = Icons.Default.CellTower,
            label = "Cell Towers",
            count = cellTowers.size,
            activeCount = cellTowers.count { currentTime - it.lastSeen < activeThreshold }
        )
    }
}

/**
 * IPC Debug Content - For collapsible section
 */
@Composable
private fun IpcDebugContent(
    isBound: Boolean,
    isScanning: Boolean,
    scanStatus: ScanningService.ScanStatus
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("IPC Connection", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isBound) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBound) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isBound) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Scan Status", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = scanStatus.javaClass.simpleName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Scanning Active", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (isScanning) "Yes" else "No",
                style = MaterialTheme.typography.bodySmall,
                color = if (isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ============================================================================
// DETECTORS TAB (enhanced)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectorHealthContent(
    detectorHealth: Map<String, DetectorHealthStatus>,
    onNavigateToError: (String) -> Unit
) {
    // Filter state
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Running", "Unhealthy", "Errors")

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

    // Filter detectors based on selection
    val filteredDetectors = detectors.filter { (detectorId, _) ->
        val health = detectorHealth[detectorId]
        when (selectedFilter) {
            "Running" -> health?.isRunning == true
            "Unhealthy" -> health?.isRunning == true && health.isHealthy == false
            "Errors" -> health?.lastError != null
            else -> true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Filter chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter) },
                        leadingIcon = if (selectedFilter == filter) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }

        // Detector cards
        items(filteredDetectors) { (detectorId, info) ->
            val (displayName, icon) = info
            val health = detectorHealth[detectorId]
            DetectorHealthCard(
                name = displayName,
                icon = icon,
                health = health,
                onNavigateToError = { onNavigateToError(detectorId) }
            )
        }

        // Empty state for filters
        if (filteredDetectors.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No detectors match filter",
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
private fun DetectorHealthCard(
    name: String,
    icon: ImageVector,
    health: DetectorHealthStatus?,
    onNavigateToError: () -> Unit
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

                // Status badge with pulsing indicator for active
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning && isHealthy) {
                        PulsingStatusIndicator(
                            color = Color(0xFF4CAF50),
                            size = 8
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
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

                // Error message if present - with jump action
                health.lastError?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToError() },
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
                            Column(modifier = Modifier.weight(1f)) {
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
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "View in diagnostics",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
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

// ============================================================================
// DIAGNOSTICS TAB (merged Threading + Errors)
// ============================================================================

@Composable
private fun DiagnosticsContent(
    detectorHealth: Map<String, DetectorHealthStatus>,
    recentErrors: List<ScanningService.ScanError>,
    uiState: MainUiState,
    onNavigateToDetector: (String) -> Unit
) {
    val detectorErrors = detectorHealth.values
        .filter { it.lastError != null }
        .sortedByDescending { it.lastErrorTime }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ERRORS SECTION (prioritized first)
        item {
            Text(
                text = "Errors",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Detector errors
        if (detectorErrors.isNotEmpty()) {
            item {
                Text(
                    text = "Detector Errors",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(detectorErrors) { health ->
                ErrorCard(
                    source = health.name,
                    message = health.lastError ?: "",
                    timestamp = health.lastErrorTime,
                    isRecoverable = health.consecutiveFailures < 5,
                    onNavigateToSource = {
                        // Find detector ID from name
                        val detectorId = detectorHealth.entries.find { it.value.name == health.name }?.key
                        detectorId?.let { onNavigateToDetector(it) }
                    }
                )
            }
        }

        // Recent service errors
        if (recentErrors.isNotEmpty()) {
            item {
                Text(
                    text = "Service Errors",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(recentErrors) { error ->
                ErrorCard(
                    source = error.subsystem,
                    message = error.message,
                    timestamp = error.timestamp,
                    isRecoverable = error.recoverable,
                    onNavigateToSource = null
                )
            }
        }

        // Empty state for errors
        if (detectorErrors.isEmpty() && recentErrors.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "No Errors",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "All systems operating normally",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // THREADING SECTION (collapsible)
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            CollapsibleSection(
                title = "Threading Monitor",
                icon = Icons.Default.Memory,
                initiallyExpanded = false
            ) {
                ThreadingContent(uiState = uiState)
            }
        }
    }
}

/**
 * Threading Content - For collapsible section in diagnostics
 */
@Composable
private fun ThreadingContent(uiState: MainUiState) {
    val systemState = uiState.threadingSystemState
    val scannerStates = uiState.threadingScannerStates
    val alerts = uiState.threadingAlerts

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // System overview
        if (systemState != null) {
            ThreadingSystemCard(systemState = systemState)
            HealthScoreCard(healthScore = systemState.healthScore)
        }

        // Threading alerts
        if (alerts.isNotEmpty()) {
            Text(
                text = "Alerts",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            alerts.forEach { alert ->
                ThreadingAlertCard(alert = alert)
            }
        }

        // Scanner states summary
        if (scannerStates.isNotEmpty()) {
            Text(
                text = "Scanner Performance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            scannerStates.entries.sortedBy { it.key }.forEach { (scannerId, state) ->
                ScannerThreadCard(scannerId = scannerId, state = state)
            }
        }

        // Empty state
        if (systemState == null) {
            Text(
                text = "Start scanning to see threading metrics",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(
    source: String,
    message: String,
    timestamp: Long?,
    isRecoverable: Boolean,
    onNavigateToSource: (() -> Unit)?
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onNavigateToSource != null) Modifier.clickable { onNavigateToSource() } else Modifier),
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
            if (onNavigateToSource != null) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "View detector",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================================
// SHARED/HELPER COMPOSABLES
// ============================================================================

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

            // Use pulsing indicator for active status
            if (status == ScanningService.SubsystemStatus.Active) {
                PulsingStatusIndicator(color = statusColor, size = 8)
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
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
                .padding(12.dp)
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
                    tint = scoreColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Health Score",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "${healthScore.toInt()}%",
                style = MaterialTheme.typography.titleMedium,
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
                    // Use pulsing indicator for active scanners
                    if (state.activeJobCount > 0 && !state.isStalled) {
                        PulsingStatusIndicator(color = Color(0xFF4CAF50), size = 10)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        state.isStalled -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                        )
                    }
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
        }
    }
}

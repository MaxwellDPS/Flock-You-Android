@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.flockyou.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.model.*
import com.flockyou.data.FlipperUiSettings
import com.flockyou.data.FlipperViewMode
import com.flockyou.scanner.flipper.FlipperClient
import com.flockyou.scanner.flipper.FlipperConnectionState
import com.flockyou.scanner.flipper.FlipperOnboardingSettings
import com.flockyou.service.CellularMonitor
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import com.flockyou.service.ScanningService
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.flockyou.ui.components.*
import com.flockyou.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import kotlinx.coroutines.delay
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.graphics.drawable.GradientDrawable
import com.flockyou.detection.ThreatScoring

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateToMap: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToNearby: () -> Unit = {},
    onNavigateToRfDetection: () -> Unit = {},
    onNavigateToUltrasonicDetection: () -> Unit = {},
    onNavigateToSatelliteDetection: () -> Unit = {},
    onNavigateToWifiSecurity: () -> Unit = {},
    onNavigateToServiceHealth: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val prioritizedEnrichmentIds by viewModel.prioritizedEnrichmentIds.collectAsState()
    val flipperUiSettings by viewModel.flipperUiSettings.collectAsState()
    val relatedDetections by viewModel.relatedDetections.collectAsState()

    // Filtered anomalies (excludes FP-marked detections)
    val filteredCellularAnomalies = remember(uiState.cellularAnomalies, uiState.detections, uiState.hideFalsePositives, uiState.fpFilterThreshold) {
        viewModel.getFilteredCellularAnomalies()
    }
    val filteredSatelliteAnomalies = remember(uiState.satelliteAnomalies, uiState.detections, uiState.hideFalsePositives, uiState.fpFilterThreshold) {
        viewModel.getFilteredSatelliteAnomalies()
    }
    val filteredRogueWifiAnomalies = remember(uiState.rogueWifiAnomalies, uiState.detections, uiState.hideFalsePositives, uiState.fpFilterThreshold) {
        viewModel.getFilteredRogueWifiAnomalies()
    }

    val context = LocalContext.current
    var showFilterSheet by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var selectedDetection by remember { mutableStateOf<Detection?>(null) }

    // Snackbar host state for showing refresh completion toast
    val snackbarHostState = remember { SnackbarHostState() }

    // Pager state for swipe navigation between tabs
    // Use pagerState as single source of truth for tab position
    val pagerState = rememberPagerState(
        initialPage = uiState.selectedTab,
        pageCount = { 4 }  // Home, History, Cellular, Flipper
    )
    val coroutineScope = rememberCoroutineScope()

    // Track detection count before refresh for delta calculation
    var preRefreshCount by remember { mutableStateOf(0) }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            preRefreshCount = uiState.totalCount
            isRefreshing = true
            viewModel.requestRefresh()
            coroutineScope.launch {
                delay(1500) // Give time for data to refresh
                isRefreshing = false
                // Show completion snackbar with detection delta
                // Read current value directly from viewModel to avoid stale closure
                val currentCount = viewModel.uiState.value.totalCount
                val newDetections = currentCount - preRefreshCount
                val message = when {
                    newDetections > 0 -> "Updated - $newDetections new detection${if (newDetections > 1) "s" else ""}"
                    newDetections < 0 -> "Updated - Removed ${-newDetections} detection${if (-newDetections > 1) "s" else ""}"
                    else -> "Updated - No new detections"
                }
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }
    )

    // Track if we're currently animating from a programmatic navigation
    // This prevents the pager from fighting with the ViewModel during animations
    var isNavigatingProgrammatically by remember { mutableStateOf(false) }

    // Sync ViewModel state when pager settles after user swipe
    // Use snapshotFlow with settledPage to only trigger when page is fully settled
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collectLatest { settledPage ->
            // Only update ViewModel if this wasn't a programmatic navigation
            // and the values are actually different
            if (!isNavigatingProgrammatically && uiState.selectedTab != settledPage) {
                viewModel.selectTab(settledPage)
            }
            isNavigatingProgrammatically = false
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "FLOCK",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = " YOU",
                            fontWeight = FontWeight.Light
                        )
                    }
                },
                actions = {
                    // Only show filter button on history tab
                    if (uiState.selectedTab == 1) {
                        IconButton(onClick = { showFilterSheet = true }) {
                            Badge(
                                containerColor = if (uiState.filterThreatLevel != null || uiState.filterDeviceTypes.isNotEmpty())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surface
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filter"
                                )
                            }
                        }
                    }
                    // Service health shortcut on home tab
                    if (uiState.selectedTab == 0) {
                        IconButton(onClick = onNavigateToServiceHealth) {
                            Icon(
                                imageVector = Icons.Default.MonitorHeart,
                                contentDescription = "Service Health"
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToNearby) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = "Nearby Devices"
                        )
                    }
                    IconButton(onClick = onNavigateToMap) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = "Map"
                        )
                    }
                    // Simple/Advanced mode toggle - visible on home and history tabs
                    if (uiState.selectedTab == 0 || uiState.selectedTab == 1) {
                        AdvancedModeToggle(
                            advancedMode = uiState.advancedMode,
                            onToggle = { viewModel.setAdvancedMode(!uiState.advancedMode) }
                        )
                    }
                    // Export debug info button - only shown in advanced mode
                    if (uiState.advancedMode) {
                        IconButton(
                            onClick = {
                                val debugInfo = viewModel.exportAllDebugInfo()
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Flock-You Debug Export")
                                    putExtra(Intent.EXTRA_TEXT, debugInfo)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Export Debug Info"))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = "Export Debug Info"
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            // Helper function to navigate to a page with debounce protection
            val navigateToPage: (Int) -> Unit = { targetPage ->
                // Skip if we're already on this page or animation is in progress
                if (pagerState.currentPage != targetPage && !pagerState.isScrollInProgress) {
                    isNavigatingProgrammatically = true
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(targetPage)
                    }
                }
            }

            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = pagerState.currentPage == 0,
                    onClick = { navigateToPage(0) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") },
                    selected = pagerState.currentPage == 1,
                    onClick = { navigateToPage(1) }
                )
                NavigationBarItem(
                    icon = {
                        BadgedBox(
                            badge = {
                                if (filteredCellularAnomalies.isNotEmpty()) {
                                    Badge { Text(filteredCellularAnomalies.size.toString()) }
                                } else {
                                    // Show checkmark badge when no anomalies
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.CellTower, contentDescription = "Cellular")
                        }
                    },
                    label = { Text("Cellular") },
                    selected = pagerState.currentPage == 2,
                    onClick = { navigateToPage(2) }
                )
                NavigationBarItem(
                    icon = {
                        BadgedBox(
                            badge = {
                                when (uiState.flipperConnectionState) {
                                    FlipperConnectionState.READY -> {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.tertiary
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                    FlipperConnectionState.CONNECTING,
                                    FlipperConnectionState.CONNECTED,
                                    FlipperConnectionState.DISCOVERING_SERVICES -> {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        ) {
                                            Icon(
                                                Icons.Default.Sync,
                                                contentDescription = null,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                    FlipperConnectionState.ERROR -> {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ) {
                                            Icon(
                                                Icons.Default.Warning,
                                                contentDescription = null,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                    else -> {} // No badge when disconnected
                                }
                            }
                        ) {
                            Icon(Icons.Default.Usb, contentDescription = "Flipper")
                        }
                    },
                    label = { Text("Flipper") },
                    selected = pagerState.currentPage == 3,
                    onClick = { navigateToPage(3) }
                )
            }
        }
    ) { paddingValues ->
        // Swipeable HorizontalPager for tab navigation with pull-to-refresh
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState)
            ) { page ->
                when (page) {
                    0 -> {
                        // Home tab - Status and modules only (no errors, no recent detections)
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                        // Status card without errors
                        item(key = "status_card") {
                            StatusCard(
                                isScanning = uiState.isScanning,
                                totalDetections = uiState.totalCount,
                                highThreatCount = uiState.highThreatCount,
                                onToggleScan = { viewModel.toggleScanning() },
                                scanStatus = uiState.scanStatus,
                                bleStatus = uiState.bleStatus,
                                wifiStatus = uiState.wifiStatus,
                                locationStatus = uiState.locationStatus,
                                cellularStatus = uiState.cellularStatus,
                                satelliteStatus = uiState.satelliteStatus,
                                recentErrors = emptyList(), // Don't show errors on home
                                onClearErrors = { }
                            )
                        }

                        // Cellular status card (show when scanning or has anomalies)
                        if (uiState.isScanning || filteredCellularAnomalies.isNotEmpty()) {
                            item(key = "cellular_status_card") {
                                CellularStatusCard(
                                    cellStatus = uiState.cellStatus,
                                    anomalies = filteredCellularAnomalies,
                                    isMonitoring = uiState.cellularStatus == ScanningService.SubsystemStatus.Active
                                )
                            }
                        }

                        // Detection Modules section
                        item(key = "detection_modules_header") {
                            Text(
                                text = "DETECTION MODULES",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        item(key = "detection_modules_grid") {
                            DetectionModulesGrid(
                                onNavigateToRfDetection = onNavigateToRfDetection,
                                onNavigateToUltrasonicDetection = onNavigateToUltrasonicDetection,
                                onNavigateToSatelliteDetection = onNavigateToSatelliteDetection,
                                onNavigateToWifiSecurity = onNavigateToWifiSecurity,
                                wifiAnomalyCount = filteredRogueWifiAnomalies.size,
                                rfAnomalyCount = viewModel.getFilteredRfAnomalies().size,
                                ultrasonicBeaconCount = uiState.ultrasonicBeacons.size,
                                satelliteAnomalyCount = filteredSatelliteAnomalies.size
                            )
                        }

                        // Service Health shortcut card
                        item(key = "service_health_shortcut") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                ),
                                onClick = onNavigateToServiceHealth
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MonitorHeart,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Service Health",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "View detector status and errors",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Go",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        }
                    }
                    1 -> {
                        // History tab - Detection list with filters
                        val filteredDetections = viewModel.getFilteredDetections()

                        // Track expanded detection IDs (persists during scroll)
                        val expandedDetectionIds = remember { mutableStateMapOf<String, Boolean>() }

                        // Track last action for undo
                        var lastMarkedDetection by remember { mutableStateOf<Detection?>(null) }
                        var lastActionType by remember { mutableStateOf<String?>(null) }

                        // Handle undo action
                        LaunchedEffect(lastMarkedDetection, lastActionType) {
                            if (lastMarkedDetection != null && lastActionType != null) {
                                val result = snackbarHostState.showSnackbar(
                                    message = when (lastActionType) {
                                        "reviewed" -> "Marked as reviewed"
                                        "false_positive" -> "Marked as false positive"
                                        else -> "Updated"
                                    },
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    lastMarkedDetection?.let { viewModel.undoMarkDetection(it) }
                                }
                                lastMarkedDetection = null
                                lastActionType = null
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                        // Filter chips if filters active (only on history tab)
                        if (uiState.filterThreatLevel != null || uiState.filterDeviceTypes.isNotEmpty()) {
                            item(key = "filter_chips") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Filter mode indicator
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Filter mode:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        FilterChip(
                                            selected = uiState.filterMatchAll,
                                            onClick = { viewModel.setFilterMatchAll(!uiState.filterMatchAll) },
                                            label = { Text(if (uiState.filterMatchAll) "Match ALL" else "Match ANY") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (uiState.filterMatchAll) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        )
                                    }

                                    // Active filter chips
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        uiState.filterThreatLevel?.let { level ->
                                            FilterChip(
                                                selected = true,
                                                onClick = { viewModel.setThreatFilter(null) },
                                                label = { Text(level.name) },
                                                trailingIcon = {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Remove filter",
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    // Device type filter chips (can have multiple now)
                                    if (uiState.filterDeviceTypes.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            uiState.filterDeviceTypes.take(3).forEach { type ->
                                                FilterChip(
                                                    selected = true,
                                                    onClick = { viewModel.removeDeviceTypeFilter(type) },
                                                    label = { Text(type.name.replace("_", " ")) },
                                                    trailingIcon = {
                                                        Icon(
                                                            Icons.Default.Close,
                                                            contentDescription = "Remove filter",
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                )
                                            }
                                            if (uiState.filterDeviceTypes.size > 3) {
                                                FilterChip(
                                                    selected = true,
                                                    onClick = { showFilterSheet = true },
                                                    label = { Text("+${uiState.filterDeviceTypes.size - 3} more") }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // FP filter toggle and section header
                        item(key = "section_header") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "DETECTION HISTORY",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    // FP filter toggle
                                    val fpCount = viewModel.getFalsePositiveCount()
                                    if (fpCount > 0 || !uiState.hideFalsePositives) {
                                        FilterChip(
                                            selected = !uiState.hideFalsePositives,
                                            onClick = { viewModel.toggleHideFalsePositives() },
                                            label = {
                                                Text(
                                                    text = if (uiState.hideFalsePositives)
                                                        "Show FPs ($fpCount hidden)"
                                                    else
                                                        "Showing all",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (uiState.hideFalsePositives)
                                                        Icons.Default.VisibilityOff
                                                    else
                                                        Icons.Default.Visibility,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        when {
                            uiState.isLoading -> {
                                item(key = "loading_state") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator()
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = "Loading detections...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                            filteredDetections.isEmpty() -> {
                                item(key = "empty_state") {
                                    EmptyState(
                                        isScanning = uiState.isScanning,
                                        onStartScanning = { viewModel.toggleScanning() }
                                    )
                                }
                            }
                            else -> {
                                items(
                                    items = filteredDetections,
                                    key = { it.id }
                                ) { detection ->
                                    SwipeableDetectionCard(
                                        detection = detection,
                                        onClick = { selectedDetection = detection },
                                        onMarkReviewed = { det ->
                                            viewModel.markAsReviewed(det)
                                            lastMarkedDetection = det
                                            lastActionType = "reviewed"
                                        },
                                        onMarkFalsePositive = { det ->
                                            viewModel.markAsFalsePositive(det)
                                            lastMarkedDetection = det
                                            lastActionType = "false_positive"
                                        },
                                        advancedMode = uiState.advancedMode,
                                        isExpanded = expandedDetectionIds[detection.id] == true,
                                        onExpandToggle = {
                                            expandedDetectionIds[detection.id] = !(expandedDetectionIds[detection.id] ?: false)
                                        },
                                        onAnalyzeClick = if (viewModel.isAiAnalysisAvailable()) {
                                            { viewModel.analyzeDetection(it) }
                                        } else null,
                                        isAnalyzing = uiState.analyzingDetectionId == detection.id,
                                        onPrioritizeEnrichment = { viewModel.prioritizeEnrichment(it) },
                                        isEnrichmentPending = prioritizedEnrichmentIds.contains(detection.id)
                                    )
                                }
                            }
                        }
                        }
                    }
                    2 -> {
                        // Cellular tab content
                        CellularTabContent(
                            modifier = Modifier.fillMaxSize(),
                            cellStatus = uiState.cellStatus,
                            cellularStatus = uiState.cellularStatus,
                            cellularAnomalies = filteredCellularAnomalies,
                            seenCellTowers = uiState.seenCellTowers,
                            cellularEvents = uiState.cellularEvents,
                            satelliteState = uiState.satelliteState,
                            satelliteAnomalies = filteredSatelliteAnomalies,
                            isScanning = uiState.isScanning,
                            onToggleScan = { viewModel.toggleScanning() },
                            onClearCellularHistory = { viewModel.clearCellularHistory() },
                            onClearSatelliteHistory = { viewModel.clearSatelliteHistory() }
                        )
                    }
                    3 -> {
                        // Flipper Zero tab content
                        FlipperTabContent(
                            modifier = Modifier.fillMaxSize(),
                            connectionState = uiState.flipperConnectionState,
                            connectionType = uiState.flipperConnectionType,
                            flipperStatus = uiState.flipperStatus,
                            isScanning = uiState.flipperIsScanning,
                            detectionCount = uiState.flipperDetectionCount,
                            wipsAlertCount = uiState.flipperWipsAlertCount,
                            lastError = uiState.flipperLastError,
                            advancedMode = uiState.advancedMode,
                            scanSchedulerStatus = uiState.flipperScanSchedulerStatus,
                            // UX improvement parameters
                            autoReconnectState = uiState.flipperAutoReconnectState,
                            discoveredDevices = uiState.flipperDiscoveredDevices,
                            recentDevices = uiState.flipperRecentDevices,
                            isScanningForDevices = uiState.flipperIsScanningForDevices,
                            connectionRssi = uiState.flipperConnectionRssi,
                            showDevicePicker = uiState.flipperShowDevicePicker,
                            // Callbacks
                            flipperUiSettings = flipperUiSettings,
                            onConnect = { viewModel.showFlipperDevicePicker() },
                            onDisconnect = { viewModel.disconnectFlipper() },
                            onTogglePause = { viewModel.toggleFlipperPause() },
                            onTriggerManualScan = { scanType -> viewModel.triggerFlipperManualScan(scanType) },
                            onViewModeChange = { viewModel.setFlipperViewMode(it) },
                            onStatusCardExpandedChange = { viewModel.setFlipperStatusCardExpanded(it) },
                            onSchedulerCardExpandedChange = { viewModel.setFlipperSchedulerCardExpanded(it) },
                            onStatsCardExpandedChange = { viewModel.setFlipperStatsCardExpanded(it) },
                            onCapabilitiesCardExpandedChange = { viewModel.setFlipperCapabilitiesCardExpanded(it) },
                            onAdvancedCardExpandedChange = { viewModel.setFlipperAdvancedCardExpanded(it) },
                            // Device picker callbacks
                            onShowDevicePicker = { viewModel.showFlipperDevicePicker() },
                            onHideDevicePicker = { viewModel.hideFlipperDevicePicker() },
                            onStartDeviceScan = { viewModel.startFlipperDeviceScan() },
                            onStopDeviceScan = { viewModel.stopFlipperDeviceScan() },
                            onSelectDiscoveredDevice = { viewModel.connectToDiscoveredFlipper(it) },
                            onSelectRecentDevice = { viewModel.connectToRecentFlipper(it) },
                            onRemoveRecentDevice = { viewModel.removeFlipperFromHistory(it) },
                            onCancelAutoReconnect = { viewModel.cancelFlipperAutoReconnect() }
                        )
                    }
                }
            }

            // Pull-to-refresh indicator with text
            Column(
                modifier = Modifier.align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullRefreshState,
                    contentColor = MaterialTheme.colorScheme.primary
                )
                // Show refreshing text below the indicator
                AnimatedVisibility(
                    visible = isRefreshing,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        modifier = Modifier.padding(top = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Refreshing detections...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
    
    // Filter bottom sheet
    if (showFilterSheet) {
        FilterBottomSheet(
            currentThreatFilter = uiState.filterThreatLevel,
            currentTypeFilters = uiState.filterDeviceTypes,
            filterMatchAll = uiState.filterMatchAll,
            onThreatFilterChange = { viewModel.setThreatFilter(it) },
            onTypeFilterToggle = { viewModel.toggleDeviceTypeFilter(it) },
            onMatchAllChange = { viewModel.setFilterMatchAll(it) },
            onClearFilters = { viewModel.clearFilters() },
            onDismiss = { showFilterSheet = false }
        )
    }
    
    // Clear all confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text("Clear All Detections?") },
            text = { Text("This will permanently delete all detection history. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllDetections()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Detection detail sheet
    selectedDetection?.let { detection ->
        // Load related detections when the sheet is shown
        LaunchedEffect(detection.id) {
            viewModel.loadRelatedDetections(detection)
        }

        DetectionDetailSheet(
            detection = detection,
            onDismiss = {
                viewModel.clearRelatedDetections()
                selectedDetection = null
            },
            onDelete = {
                viewModel.deleteDetection(detection)
                viewModel.clearRelatedDetections()
                selectedDetection = null
            },
            onMarkSafe = {
                viewModel.markAsFalsePositive(detection)
                viewModel.clearRelatedDetections()
                selectedDetection = null
            },
            onMarkThreat = {
                viewModel.markAsConfirmedThreat(detection)
                viewModel.clearRelatedDetections()
                selectedDetection = null
            },
            onShare = {
                val shareText = buildDetectionShareText(detection)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Flock You Detection: ${detection.deviceType.displayName}")
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Detection"))
            },
            onNavigate = if (detection.latitude != null && detection.longitude != null) {
                {
                    val geoUri = Uri.parse("geo:${detection.latitude},${detection.longitude}?q=${detection.latitude},${detection.longitude}(${detection.deviceType.displayName})")
                    val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                    if (mapIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(mapIntent)
                    }
                }
            } else null,
            onAddNote = { note ->
                viewModel.updateUserNote(detection, note)
            },
            onExport = {
                val exportText = buildDetectionExportJson(detection)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, "Flock You Detection Export")
                    putExtra(Intent.EXTRA_TEXT, exportText)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Detection"))
            },
            advancedMode = uiState.advancedMode,
            relatedDetections = relatedDetections,
            onRelatedDetectionClick = { relatedDetection ->
                // Select the clicked related detection
                selectedDetection = relatedDetection
                viewModel.onRelatedDetectionSelected(relatedDetection)
            },
            onSeeAllRelatedClick = {
                // For now, just scroll within the list - could navigate to a full screen later
                // The RelatedDetectionsSection already shows a "See All" card
            }
        )
    }

    // AI Analysis result dialog
    uiState.analysisResult?.let { result ->
        AiAnalysisResultDialog(
            result = result,
            onDismiss = { viewModel.clearAnalysisResult() }
        )
    }
}

@Composable
fun AiAnalysisResultDialog(
    result: com.flockyou.data.AiAnalysisResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Column {
                Text("AI Analysis")
                Text(
                    text = "Model: ${result.modelUsed} | ${result.processingTimeMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (result.success) {
                    // Main analysis
                    result.analysis?.let { analysis ->
                        Text(
                            text = analysis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Threat assessment
                    result.threatAssessment?.let { threat ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Threat Assessment",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = threat,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Recommendations
                    if (result.recommendations.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Recommendations",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        result.recommendations.forEach { rec ->
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text("• ", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = rec,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Confidence indicator
                    if (result.confidence > 0f) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Confidence: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                progress = result.confidence,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = when {
                                    result.confidence > 0.8f -> Color(0xFF4CAF50)
                                    result.confidence > 0.5f -> Color(0xFFFFC107)
                                    else -> Color(0xFFF44336)
                                }
                            )
                            Text(
                                text = " ${(result.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Error state
                    Text(
                        text = result.error ?: "Analysis failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastDetectionAlert(
    detection: Detection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val threatColor = detection.threatLevel.toColor()
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = threatColor.copy(alpha = 0.1f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.NotificationsActive,
                contentDescription = null,
                tint = threatColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LATEST DETECTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = threatColor
                )
                Text(
                    text = detection.deviceType.name.replace("_", " "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            ThreatBadge(threatLevel = detection.threatLevel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBottomSheet(
    currentThreatFilter: ThreatLevel?,
    currentTypeFilters: Set<DeviceType>,
    filterMatchAll: Boolean,
    onThreatFilterChange: (ThreatLevel?) -> Unit,
    onTypeFilterToggle: (DeviceType) -> Unit,
    onMatchAllChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            Text(
                text = "Filter Detections",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // AND/OR toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Filter Logic",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = filterMatchAll,
                        onClick = { onMatchAllChange(true) },
                        label = { Text("AND") },
                        leadingIcon = if (filterMatchAll) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = !filterMatchAll,
                        onClick = { onMatchAllChange(false) },
                        label = { Text("OR") },
                        leadingIcon = if (!filterMatchAll) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            Text(
                text = if (filterMatchAll) "Show detections matching ALL selected filters"
                       else "Show detections matching ANY selected filter",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Threat Level",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ThreatLevel.entries.forEach { level ->
                    FilterChip(
                        selected = currentThreatFilter == level,
                        onClick = {
                            onThreatFilterChange(if (currentThreatFilter == level) null else level)
                        },
                        label = { Text(level.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = level.toColor().copy(alpha = 0.2f),
                            selectedLabelColor = level.toColor()
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device Type",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentTypeFilters.isNotEmpty()) {
                    Text(
                        text = "${currentTypeFilters.size} selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DeviceType.entries.chunked(2).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { type ->
                            FilterChip(
                                selected = type in currentTypeFilters,
                                onClick = { onTypeFilterToggle(type) },
                                label = { Text(type.name.replace("_", " ")) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = type.toIcon(),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onClearFilters()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear All")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Apply")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionDetailSheet(
    detection: Detection,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onMarkSafe: () -> Unit = {},
    onMarkThreat: () -> Unit = {},
    onShare: () -> Unit = {},
    onNavigate: (() -> Unit)? = null,
    onAddNote: (String?) -> Unit = {},
    onExport: () -> Unit = {},
    advancedMode: Boolean = false,
    relatedDetections: List<Detection> = emptyList(),
    onRelatedDetectionClick: (Detection) -> Unit = {},
    onSeeAllRelatedClick: () -> Unit = {}
) {
    val threatColor = detection.threatLevel.toColor()
    val deviceInfo = DetectionPatterns.getDeviceTypeInfo(detection.deviceType)
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()) }

    // State for the Add Note dialog
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf(detection.userNote ?: "") }

    // Note Dialog
    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            icon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
            title = { Text(if (detection.userNote != null) "Edit Note" else "Add Note") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note") },
                    placeholder = { Text("Add your notes about this detection...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddNote(noteText.takeIf { it.isNotBlank() })
                        showNoteDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            )
        ) {
            // New ThreatHeader component at the top
            item {
                ThreatHeader(
                    threatLevel = detection.threatLevel,
                    threatScore = detection.threatScore,
                    deviceType = detection.deviceType,
                    isActive = detection.isActive,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Detection Timeline
            item {
                DetectionTimeline(
                    firstSeen = detection.timestamp,
                    lastSeen = if (detection.lastSeenTimestamp != detection.timestamp) detection.lastSeenTimestamp else null,
                    events = if (detection.seenCount > 1) {
                        listOf(
                            TimelineEvent(timestamp = detection.timestamp, signalStrength = detection.rssi, isActive = false),
                            TimelineEvent(timestamp = detection.lastSeenTimestamp, signalStrength = detection.rssi, isActive = detection.isActive)
                        )
                    } else {
                        listOf(TimelineEvent(timestamp = detection.timestamp, signalStrength = detection.rssi, isActive = detection.isActive))
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Device Description - using CollapsibleSection
            item {
                CollapsibleSection(
                    title = "About This Device",
                    icon = Icons.Default.Info,
                    defaultExpanded = true,
                    persistKey = "detection_about_device"
                ) {
                    Text(
                        text = deviceInfo.fullDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // AI Analysis Section (show if any FP analysis was performed - LLM or rule-based)
            if (detection.fpScore != null) {
                item {
                    CollapsibleSection(
                        title = if (detection.llmAnalyzed) "AI Analysis" else "Analysis",
                        icon = if (detection.llmAnalyzed) Icons.Default.AutoAwesome else Icons.Default.Psychology,
                        defaultExpanded = true,
                        persistKey = "detection_ai_analysis"
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // Analysis type badge
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (detection.llmAnalyzed) Icons.Default.AutoAwesome else Icons.Default.Rule,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = if (detection.llmAnalyzed) "On-device LLM" else "Rule-based",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    // Show "Pending LLM" indicator if not yet LLM analyzed
                                    if (!detection.llmAnalyzed) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                text = "LLM pending",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                // FP verdict (fpScore is guaranteed non-null in this block)
                                Spacer(modifier = Modifier.height(10.dp))
                                val fpPercent = (detection.fpScore!! * 100).toInt()
                                val verdictText = when {
                                    fpPercent >= 70 -> "Likely false positive"
                                    fpPercent >= 40 -> "Possibly false positive"
                                    else -> "Likely genuine threat"
                                }
                                val verdictColor = when {
                                    fpPercent >= 70 -> MaterialTheme.colorScheme.tertiary
                                    fpPercent >= 40 -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.error
                                }
                                val verdictIcon = when {
                                    fpPercent >= 70 -> Icons.Default.CheckCircle
                                    fpPercent >= 40 -> Icons.Default.Help
                                    else -> Icons.Default.Warning
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = verdictIcon,
                                        contentDescription = null,
                                        tint = verdictColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = verdictText,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = verdictColor
                                        )
                                        Text(
                                            text = "$fpPercent% confidence",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = verdictColor.copy(alpha = 0.8f)
                                        )
                                    }
                                }

                                // Confidence bar
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = detection.fpScore!!,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = verdictColor,
                                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                                )

                                // AI-generated reason
                                detection.fpReason?.let { reason ->
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Analysis:",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = reason,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                // Category
                                detection.fpCategory?.let { category ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Label,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Category: ${category.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                // Analysis method and timestamp
                                detection.analyzedAt?.let { timestamp ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (detection.llmAnalyzed) Icons.Default.AutoAwesome else Icons.Default.Rule,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (detection.llmAnalyzed) "AI analysis" else "Rule-based",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "• ${dateFormat.format(Date(timestamp))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Technical Details
            item {
                CollapsibleSection(
                    title = "Detection Details",
                    icon = Icons.Default.List,
                    defaultExpanded = true,
                    persistKey = "detection_details"
                ) {
                    Column {
                        DetailRow(
                            label = "First Detected",
                            value = dateFormat.format(Date(detection.timestamp))
                        )

                        if (detection.lastSeenTimestamp != detection.timestamp) {
                            DetailRow(
                                label = "Last Seen",
                                value = dateFormat.format(Date(detection.lastSeenTimestamp))
                            )
                        }

                        if (detection.seenCount > 1) {
                            DetailRow(
                                label = "Times Seen",
                                value = "${detection.seenCount}x"
                            )
                        }

                        DetailRow(
                            label = "Status",
                            value = if (detection.isActive) "Active" else "Inactive"
                        )

                        DetailRow(
                            label = "Protocol",
                            value = "${detection.protocol.icon} ${detection.protocol.displayName}"
                        )

                        DetailRow(
                            label = "Method",
                            value = detection.detectionMethod.displayName
                        )

                        // Show different fields based on protocol
                        if (detection.protocol == DetectionProtocol.CELLULAR) {
                            // Cellular-specific fields
                            detection.firmwareVersion?.let { cellId ->
                                DetailRow(label = "Cell Info", value = cellId)
                            }

                            detection.macAddress?.let { mccMnc ->
                                DetailRow(label = "MCC-MNC", value = mccMnc)
                            }

                            detection.manufacturer?.let { networkType ->
                                DetailRow(label = "Network Type", value = networkType)
                            }
                        } else {
                            // WiFi/BLE fields
                            detection.macAddress?.let { mac ->
                                DetailRow(label = "MAC Address", value = mac)
                            }

                            detection.ssid?.let { ssid ->
                                DetailRow(label = "SSID", value = ssid)
                            }

                            detection.manufacturer?.let { mfr ->
                                DetailRow(label = "Manufacturer", value = mfr)
                            }

                            detection.firmwareVersion?.let { fw ->
                                DetailRow(label = "Firmware", value = fw)
                            }
                        }

                        detection.deviceName?.let { name ->
                            DetailRow(label = "Device Name", value = name)
                        }

                        DetailRow(
                            label = "Signal",
                            value = "${detection.rssi} dBm (${detection.signalStrength.displayName})"
                        )

                        DetailRow(
                            label = "Est. Distance",
                            value = rssiToDistance(detection.rssi)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Location Section with Embedded Map
            if (detection.latitude != null && detection.longitude != null) {
                item {
                    val context = LocalContext.current

                    // Initialize osmdroid config
                    LaunchedEffect(Unit) {
                        Configuration.getInstance().apply {
                            userAgentValue = context.packageName
                        }
                    }

                    CollapsibleSection(
                        title = "Location",
                        icon = Icons.Default.LocationOn,
                        defaultExpanded = false,
                        persistKey = "detection_location"
                    ) {
                        Column {
                            // Coordinates display
                            Text(
                                text = "%.6f, %.6f".format(detection.latitude, detection.longitude),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Embedded OSM Map
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                val lat = detection.latitude!!
                                val lon = detection.longitude!!

                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        MapView(ctx).apply {
                                            setTileSource(DETAIL_SHEET_TILE_SOURCE)
                                            setMultiTouchControls(true)
                                            controller.setZoom(16.0)
                                            controller.setCenter(GeoPoint(lat, lon))

                                            // Add marker for detection location
                                            val marker = Marker(this).apply {
                                                position = GeoPoint(lat, lon)
                                                title = detection.deviceType.displayName
                                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                                icon = createDetailMapMarkerDrawable(detection.threatLevel)
                                            }
                                            overlays.add(marker)
                                        }
                                    },
                                    update = { map ->
                                        map.controller.setCenter(GeoPoint(lat, lon))
                                        map.invalidate()
                                    }
                                )

                                // OSM Attribution overlay
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                ) {
                                    Text(
                                        text = "© OpenStreetMap",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Capabilities Section
            if (deviceInfo.capabilities.isNotEmpty()) {
                item {
                    CollapsibleSection(
                        title = "Known Capabilities",
                        icon = Icons.Default.Build,
                        defaultExpanded = false,
                        persistKey = "detection_capabilities",
                        badge = "${deviceInfo.capabilities.size}"
                    ) {
                        Column {
                            deviceInfo.capabilities.forEach { capability ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = capability,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Privacy Concerns Section
            if (deviceInfo.privacyConcerns.isNotEmpty()) {
                item {
                    CollapsibleSection(
                        title = "Privacy Concerns",
                        icon = Icons.Default.PrivacyTip,
                        defaultExpanded = false,
                        persistKey = "detection_privacy_concerns",
                        badge = "${deviceInfo.privacyConcerns.size}"
                    ) {
                        Column {
                            deviceInfo.privacyConcerns.forEach { concern ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = concern,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Recommendations Section
            if (deviceInfo.recommendations.isNotEmpty()) {
                item {
                    CollapsibleSection(
                        title = "What You Can Do",
                        icon = Icons.Default.Lightbulb,
                        defaultExpanded = false,
                        persistKey = "detection_recommendations",
                        badge = "${deviceInfo.recommendations.size}"
                    ) {
                        Column {
                            deviceInfo.recommendations.forEach { recommendation ->
                                Row(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = recommendation,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Advanced Mode: Heuristics & Scoring Analysis Section
            if (advancedMode) {
                item {
                    CollapsibleSection(
                        title = "Heuristics Analysis",
                        icon = Icons.Default.Analytics,
                        defaultExpanded = false,
                        persistKey = "detection_heuristics"
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                            // Impact Factor Analysis
                            val impactFactor = remember(detection.deviceType) {
                                ThreatScoring.getImpactFactor(detection.deviceType)
                            }
                            val impactDescription = when {
                                impactFactor >= 2.0 -> "Critical - Can intercept all communications"
                                impactFactor >= 1.8 -> "Severe - Can cause physical harm"
                                impactFactor >= 1.5 -> "High - Stalking/tracking risk"
                                impactFactor >= 1.2 -> "Moderate - Privacy violation"
                                impactFactor >= 1.0 -> "Standard - Known surveillance"
                                impactFactor >= 0.7 -> "Low - Consumer IoT device"
                                else -> "Minimal - Infrastructure/Traffic"
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Impact Factor",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = when {
                                            impactFactor >= 1.8 -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                            impactFactor >= 1.2 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        }
                                    ) {
                                        Text(
                                            text = "%.1fx".format(impactFactor),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = when {
                                                impactFactor >= 1.8 -> MaterialTheme.colorScheme.error
                                                impactFactor >= 1.2 -> MaterialTheme.colorScheme.tertiary
                                                else -> MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = impactDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }

                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Signal Confidence Analysis
                            val signalConfidenceData = remember(detection.rssi) {
                                when {
                                    detection.rssi > -50 -> Pair("Excellent", "+10%")
                                    detection.rssi > -60 -> Pair("Good", "+5%")
                                    detection.rssi > -80 -> Pair("Medium", "±0%")
                                    detection.rssi > -90 -> Pair("Weak", "-10%")
                                    else -> Pair("Very Weak", "-20%")
                                }
                            }
                            val signalConfidenceColor = when {
                                detection.rssi > -60 -> MaterialTheme.colorScheme.primary
                                detection.rssi > -80 -> MaterialTheme.colorScheme.onSurfaceVariant
                                detection.rssi > -90 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Signal Confidence",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "${signalConfidenceData.first} (${signalConfidenceData.second})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = signalConfidenceColor
                                    )
                                }
                                Text(
                                    text = "Based on RSSI: ${detection.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }

                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Persistence Analysis
                            val persistenceBonus = remember(detection.seenCount) {
                                when {
                                    detection.seenCount >= 10 -> Triple("High Persistence", "+20%", "Seen frequently - increased confidence")
                                    detection.seenCount >= 5 -> Triple("Moderate Persistence", "+10%", "Multiple sightings confirm presence")
                                    detection.seenCount >= 2 -> Triple("Low Persistence", "+5%", "Seen more than once")
                                    else -> Triple("Single Detection", "-20%", "Only seen once - lower confidence")
                                }
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Persistence Factor",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "${persistenceBonus.first} (${persistenceBonus.second})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (detection.seenCount > 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                Text(
                                    text = "${persistenceBonus.third} (seen ${detection.seenCount}x)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }

                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Detection Method Analysis
                            Column {
                                Text(
                                    text = "Detection Method",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = detection.detectionMethod.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = detection.detectionMethod.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Matched Patterns (if available)
                            detection.matchedPatterns?.let { patterns ->
                                if (patterns.isNotEmpty() && patterns != "[]" && patterns != "null") {
                                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    Column {
                                        Text(
                                            text = "Matched Patterns",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = patterns,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }

                            // Score Calculation Summary
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Column {
                                Text(
                                    text = "Score Calculation",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "threat_score = base_likelihood × impact_factor × confidence",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Final Score:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "${detection.threatScore}/100 → ${detection.threatLevel.displayName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = threatColor
                                    )
                                }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Advanced Mode: Raw Technical Data Section
                item {
                    CollapsibleSection(
                        title = "Raw Technical Data",
                        icon = Icons.Default.Code,
                        defaultExpanded = false,
                        persistKey = "detection_raw_data"
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Detection ID
                                Text(
                                    text = "Detection ID:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = detection.id,
                                    style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Protocol and Method
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Protocol:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = detection.protocol.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Method:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = detection.detectionMethod.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Device Type
                            Text(
                                text = "Device Type:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = detection.deviceType.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )

                            // Threat Score
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Threat Level:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = detection.threatLevel.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = threatColor
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Threat Score:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${detection.threatScore}/100",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = threatColor
                                    )
                                }
                            }

                            // Signal Strength Raw
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "RSSI:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${detection.rssi} dBm",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Signal Category:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = detection.signalStrength.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Timestamps
                            Text(
                                text = "First Seen (Unix):",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = detection.timestamp.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )

                            Text(
                                text = "Last Seen (Unix):",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = detection.lastSeenTimestamp.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )

                            // Seen Count and Active Status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Seen Count:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = detection.seenCount.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Is Active:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = detection.isActive.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Service UUIDs if present
                            detection.serviceUuids?.let { uuids ->
                                if (uuids.isNotEmpty() && uuids != "[]") {
                                    Text(
                                        text = "Service UUIDs:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = uuids,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Matched Patterns if present
                            detection.matchedPatterns?.let { patterns ->
                                if (patterns.isNotEmpty() && patterns != "[]") {
                                    Text(
                                        text = "Matched Patterns:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = patterns,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                                // Location coordinates if present
                                if (detection.latitude != null && detection.longitude != null) {
                                    Text(
                                        text = "Raw Coordinates:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "lat=${detection.latitude}, lng=${detection.longitude}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Related Detections Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                RelatedDetectionsSection(
                    relatedDetections = relatedDetections,
                    onDetectionClick = onRelatedDetectionClick,
                    onSeeAllClick = onSeeAllRelatedClick
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Sticky Action Bar at the bottom
        DetectionActionBar(
            onMarkSafe = onMarkSafe,
            onMarkThreat = onMarkThreat,
            onShare = onShare,
            onNavigate = onNavigate,
            onAddNote = { showNoteDialog = true },
            onExport = onExport,
            isSafeEnabled = detection.fpCategory != "USER_MARKED_FP",
            isThreatEnabled = !detection.confirmedThreat
        )
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (label.contains("MAC") || label.contains("SSID")) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellularTabContent(
    modifier: Modifier = Modifier,
    cellStatus: CellularMonitor.CellStatus?,
    cellularStatus: ScanningService.SubsystemStatus,
    cellularAnomalies: List<CellularMonitor.CellularAnomaly>,
    seenCellTowers: List<CellularMonitor.SeenCellTower>,
    cellularEvents: List<CellularMonitor.CellularEvent>,
    satelliteState: com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState?,
    satelliteAnomalies: List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly>,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    onClearCellularHistory: () -> Unit,
    onClearSatelliteHistory: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var showTimelineSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Timeline Bottom Sheet
    if (showTimelineSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTimelineSheet = false },
            sheetState = sheetState,
            modifier = Modifier.fillMaxHeight(0.9f)
        ) {
            CellularTimelineScreen(
                events = cellularEvents,
                seenTowers = seenCellTowers,
                cellStatus = cellStatus,
                onClearHistory = onClearCellularHistory
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (cellularStatus) {
                        is ScanningService.SubsystemStatus.Active -> 
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        is ScanningService.SubsystemStatus.PermissionDenied -> 
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CellTower,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cellular Monitoring",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = onToggleScan,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isScanning) 
                                    MaterialTheme.colorScheme.error 
                                else 
                                    MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (isScanning) "Stop" else "Start")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when (cellularStatus) {
                            is ScanningService.SubsystemStatus.Active -> StatusActive
                            is ScanningService.SubsystemStatus.PermissionDenied -> StatusError
                            else -> StatusInactive
                        }
                    ) {
                        Text(
                            text = when (cellularStatus) {
                                is ScanningService.SubsystemStatus.Active -> "🟢 Active"
                                is ScanningService.SubsystemStatus.PermissionDenied -> "⛔ No Permission"
                                is ScanningService.SubsystemStatus.Error -> "⚠️ Error"
                                else -> "⚪ Idle"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    
                    if (cellularStatus is ScanningService.SubsystemStatus.PermissionDenied) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠️ READ_PHONE_STATE permission required for IMSI catcher detection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PermissionRecoveryButton()
                    }
                }
            }
        }
        
        // Current cell info
        if (cellStatus != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "📶 Current Cell Tower",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (cellStatus.networkGeneration) {
                                    "5G" -> Network5G
                                    "4G" -> Network4G
                                    "3G" -> Network3G
                                    "2G" -> Network2G
                                    else -> StatusInactive
                                }
                            ) {
                                Text(
                                    text = cellStatus.networkGeneration,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cellStatus.networkType,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                cellStatus.operator?.let { op ->
                                    Text(
                                        text = op,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${cellStatus.signalStrength} dBm",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${cellStatus.signalBars}/4 bars",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Cell ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(cellStatus.cellId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            cellStatus.mcc?.let {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("MCC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                            cellStatus.mnc?.let {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("MNC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Anomalies section
        if (cellularAnomalies.isNotEmpty()) {
            item {
                Text(
                    text = "⚠️ Detected Anomalies (${cellularAnomalies.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            items(
                items = cellularAnomalies.take(10),
                key = { it.id }
            ) { anomaly ->
                CellularAnomalyCard(anomaly = anomaly, dateFormat = dateFormat)
            }
        }
        
        // Cell tower history
        if (seenCellTowers.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🗼 Cell Tower History (${seenCellTowers.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showTimelineSheet = true }) {
                            Icon(
                                Icons.Default.Timeline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Timeline")
                        }
                        TextButton(onClick = onClearCellularHistory) {
                            Text("Clear")
                        }
                    }
                }
            }

            items(
                items = seenCellTowers.take(5),
                key = { "${it.mcc}-${it.mnc}-${it.lac}-${it.cellId}" }
            ) { tower ->
                CellTowerHistoryCard(tower = tower, dateFormat = dateFormat)
            }

            // Show "View All" if there are more towers
            if (seenCellTowers.size > 5) {
                item {
                    TextButton(
                        onClick = { showTimelineSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View all ${seenCellTowers.size} towers →")
                    }
                }
            }
        }

        // Satellite status card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        satelliteState?.isConnected == true -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.SatelliteAlt,
                                contentDescription = null,
                                tint = if (satelliteState?.isConnected == true) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "🛰️ Satellite Status",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                val satState = satelliteState
                                Text(
                                    text = when {
                                        satState?.isConnected == true -> 
                                            "Connected: ${satState.connectionType.name.replace("_", " ")}"
                                        isScanning -> "Monitoring"
                                        else -> "Not connected"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (satelliteState?.isConnected == true) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = StatusActive.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "CONNECTED",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusActive,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    
                    satelliteState?.let { satState ->
                        if (satState.isConnected && satState.provider != com.flockyou.monitoring.SatelliteMonitor.SatelliteProvider.UNKNOWN) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Provider: ${satState.provider.name} | Network: ${satState.networkName ?: "Unknown"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Satellite anomalies
        if (satelliteAnomalies.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🛰️ Satellite Anomalies (${satelliteAnomalies.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onClearSatelliteHistory) {
                        Text("Clear")
                    }
                }
            }
            
            items(
                items = satelliteAnomalies.take(10),
                key = { "${it.type}-${it.timestamp}-${it.hashCode()}" }
            ) { anomaly ->
                SatelliteAnomalyHistoryCard(anomaly = anomaly, dateFormat = dateFormat)
            }
        }
        
        // Info card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ℹ️ About IMSI Catcher Detection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This monitors for signs of cell site simulators (StingRay, Hailstorm, etc.):\n" +
                            "• Encryption downgrades (4G/5G → 2G)\n" +
                            "• Suspicious network identifiers\n" +
                            "• Unexpected cell tower changes\n" +
                            "• Signal anomalies",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CellularAnomalyCard(
    anomaly: CellularMonitor.CellularAnomaly,
    dateFormat: SimpleDateFormat
) {
    val severityColor = when (anomaly.severity) {
        ThreatLevel.CRITICAL -> ThreatCritical
        ThreatLevel.HIGH -> ThreatHigh
        ThreatLevel.MEDIUM -> ThreatMedium
        else -> StatusInactive
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = severityColor.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(anomaly.type.emoji, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = anomaly.type.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = severityColor
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = severityColor
                ) {
                    Text(
                        text = anomaly.severity.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = anomaly.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dateFormat.format(Date(anomaly.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${anomaly.signalStrength} dBm • ${anomaly.networkType}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellTowerHistoryCard(
    tower: CellularMonitor.SeenCellTower,
    dateFormat: SimpleDateFormat
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (tower.networkGeneration) {
                            "5G" -> Network5G
                            "4G" -> Network4G
                            "3G" -> Network3G
                            "2G" -> Network2G
                            else -> StatusInactive
                        }
                    ) {
                        Text(
                            text = tower.networkGeneration,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = tower.operator ?: "Unknown",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Cell ${tower.cellId}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${tower.lastSignal} dBm",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${tower.seenCount}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        tower.mcc?.let {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("MCC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        tower.mnc?.let {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("MNC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        tower.lac?.let {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("LAC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(it.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "First: ${dateFormat.format(Date(tower.firstSeen))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Last: ${dateFormat.format(Date(tower.lastSeen))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SatelliteAnomalyHistoryCard(
    anomaly: com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly,
    dateFormat: SimpleDateFormat
) {
    val severityColor = when (anomaly.severity) {
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.CRITICAL -> ThreatCritical
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.HIGH -> ThreatHigh
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.MEDIUM -> ThreatMedium
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.LOW -> ThreatLow
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.INFO -> ThreatInfo
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = severityColor.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SatelliteAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = severityColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = anomaly.type.name.replace("_", " "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
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
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = severityColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = anomaly.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Detection modules grid with quick access to specialized detection screens
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectionModulesGrid(
    onNavigateToRfDetection: () -> Unit,
    onNavigateToUltrasonicDetection: () -> Unit,
    onNavigateToSatelliteDetection: () -> Unit,
    onNavigateToWifiSecurity: () -> Unit,
    wifiAnomalyCount: Int,
    rfAnomalyCount: Int,
    ultrasonicBeaconCount: Int,
    satelliteAnomalyCount: Int
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // First row: WiFi Security & RF Analysis
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DetectionModuleCard(
                modifier = Modifier.weight(1f),
                title = "WiFi Security",
                description = "Evil twin & rogue AP detection",
                icon = Icons.Default.Wifi,
                badgeCount = wifiAnomalyCount,
                iconTint = Color(0xFF2196F3),
                onClick = onNavigateToWifiSecurity
            )
            DetectionModuleCard(
                modifier = Modifier.weight(1f),
                title = "RF Analysis",
                description = "Jammers, drones & spectrum",
                icon = Icons.Default.Radio,
                badgeCount = rfAnomalyCount,
                iconTint = Color(0xFF9C27B0),
                onClick = onNavigateToRfDetection
            )
        }

        // Second row: Ultrasonic & Satellite
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DetectionModuleCard(
                modifier = Modifier.weight(1f),
                title = "Ultrasonic",
                description = "Audio tracking beacons",
                icon = Icons.Default.GraphicEq,
                badgeCount = ultrasonicBeaconCount,
                iconTint = Color(0xFFFF9800),
                onClick = onNavigateToUltrasonicDetection
            )
            DetectionModuleCard(
                modifier = Modifier.weight(1f),
                title = "Satellite",
                description = "NTN & Direct-to-Cell",
                icon = Icons.Default.SatelliteAlt,
                badgeCount = satelliteAnomalyCount,
                iconTint = Color(0xFF4CAF50),
                onClick = onNavigateToSatelliteDetection
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectionModuleCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeCount: Int,
    iconTint: Color,
    onClick: () -> Unit
) {
    val hasAnomalies = badgeCount > 0
    // Animate scale for emphasis when there are anomalies
    val scale by animateFloatAsState(
        targetValue = if (hasAnomalies) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "module_scale"
    )

    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (hasAnomalies) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else Modifier
            ),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (hasAnomalies) {
                iconTint.copy(alpha = 0.2f)
            } else {
                iconTint.copy(alpha = 0.1f)
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (hasAnomalies) 4.dp else 0.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(if (hasAnomalies) 32.dp else 28.dp)
                )
                if (hasAnomalies) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Text(
                            text = badgeCount.toString(),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (hasAnomalies) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = if (hasAnomalies) "$badgeCount anomal${if (badgeCount > 1) "ies" else "y"} detected" else description,
                style = MaterialTheme.typography.labelSmall,
                color = if (hasAnomalies) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (hasAnomalies) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Permission recovery button that opens app settings
 * Used when a permission is denied and needs to be granted manually
 */
@Composable
fun PermissionRecoveryButton(
    modifier: Modifier = Modifier,
    text: String = "Grant Permission"
) {
    val context = LocalContext.current

    OutlinedButton(
        onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        },
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

// ============================================================================
// Flipper Zero Tab Content
// ============================================================================

@Composable
fun FlipperTabContent(
    modifier: Modifier = Modifier,
    connectionState: FlipperConnectionState,
    connectionType: FlipperClient.ConnectionType,
    flipperStatus: com.flockyou.scanner.flipper.FlipperStatusResponse?,
    isScanning: Boolean,
    detectionCount: Int,
    wipsAlertCount: Int,
    lastError: String?,
    advancedMode: Boolean = false,
    scanSchedulerStatus: com.flockyou.scanner.flipper.ScanSchedulerStatus = com.flockyou.scanner.flipper.ScanSchedulerStatus(),
    onboardingSettings: FlipperOnboardingSettings = FlipperOnboardingSettings(),
    showSetupWizard: Boolean = false,
    // UX improvement parameters
    autoReconnectState: com.flockyou.scanner.flipper.AutoReconnectState = com.flockyou.scanner.flipper.AutoReconnectState(),
    discoveredDevices: List<com.flockyou.scanner.flipper.DiscoveredFlipperDevice> = emptyList(),
    recentDevices: List<com.flockyou.scanner.flipper.RecentFlipperDevice> = emptyList(),
    isScanningForDevices: Boolean = false,
    connectionRssi: Int? = null,
    showDevicePicker: Boolean = false,
    // Callbacks
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onTriggerManualScan: (com.flockyou.scanner.flipper.FlipperScanType) -> Unit = {},
    onShowSetupWizard: () -> Unit = {},
    onDismissSetupWizard: () -> Unit = {},
    onCompleteSetupWizard: () -> Unit = {},
    onLearnMore: () -> Unit = {},
    onTroubleshooting: () -> Unit = {},
    // Device picker callbacks
    onShowDevicePicker: () -> Unit = {},
    onHideDevicePicker: () -> Unit = {},
    onStartDeviceScan: () -> Unit = {},
    onStopDeviceScan: () -> Unit = {},
    onSelectDiscoveredDevice: (com.flockyou.scanner.flipper.DiscoveredFlipperDevice) -> Unit = {},
    onSelectRecentDevice: (com.flockyou.scanner.flipper.RecentFlipperDevice) -> Unit = {},
    onRemoveRecentDevice: (String) -> Unit = {},
    onCancelAutoReconnect: () -> Unit = {},
    // Additional UI settings parameters (for compatibility)
    flipperUiSettings: com.flockyou.data.FlipperUiSettings = com.flockyou.data.FlipperUiSettings(),
    onViewModeChange: (com.flockyou.data.FlipperViewMode) -> Unit = {},
    onStatusCardExpandedChange: (Boolean) -> Unit = {},
    onSchedulerCardExpandedChange: (Boolean) -> Unit = {},
    onStatsCardExpandedChange: (Boolean) -> Unit = {},
    onCapabilitiesCardExpandedChange: (Boolean) -> Unit = {},
    onAdvancedCardExpandedChange: (Boolean) -> Unit = {}
) {
    // Show setup wizard for first-time users
    if (showSetupWizard && !onboardingSettings.hasCompletedSetupWizard && connectionState == FlipperConnectionState.DISCONNECTED) {
        FlipperSetupWizard(
            onComplete = onCompleteSetupWizard,
            onDismiss = onDismissSetupWizard,
            onConnect = onConnect,
            modifier = modifier
        )
        return
    }

    // Device picker bottom sheet
    if (showDevicePicker) {
        FlipperDevicePickerBottomSheet(
            discoveredDevices = discoveredDevices,
            recentDevices = recentDevices,
            isScanning = isScanningForDevices,
            onDismiss = onHideDevicePicker,
            onStartScan = onStartDeviceScan,
            onStopScan = onStopDeviceScan,
            onSelectDiscovered = onSelectDiscoveredDevice,
            onSelectRecent = onSelectRecentDevice,
            onRemoveRecent = onRemoveRecentDevice
        )
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection Status Card with controls
        item(key = "flipper_connection") {
            FlipperConnectionCardEnhanced(
                connectionState = connectionState,
                connectionType = connectionType,
                lastError = lastError,
                autoReconnectState = autoReconnectState,
                connectionRssi = connectionRssi,
                onConnect = onShowDevicePicker,
                onDisconnect = onDisconnect,
                onCancelAutoReconnect = onCancelAutoReconnect
            )
        }

        // Show content based on connection state
        when (connectionState) {
            FlipperConnectionState.READY -> {
                // Flipper Status Card
                item(key = "flipper_status") {
                    FlipperStatusCard(
                        flipperStatus = flipperStatus,
                        isScanning = isScanning
                    )
                }

                // Scan Scheduler Status Card
                item(key = "flipper_scheduler") {
                    FlipperScanSchedulerCard(
                        scanSchedulerStatus = scanSchedulerStatus
                    )
                }

                // Scan Statistics Card
                item(key = "flipper_stats") {
                    FlipperScanStatsCard(
                        detectionCount = detectionCount,
                        wipsAlertCount = wipsAlertCount,
                        flipperStatus = flipperStatus
                    )
                }

                // Capabilities Card
                item(key = "flipper_capabilities") {
                    FlipperCapabilitiesCard(
                        flipperStatus = flipperStatus
                    )
                }

                // Advanced Mode: Detection Scheduler & Raw Data
                if (advancedMode) {
                    item(key = "flipper_advanced") {
                        FlipperAdvancedInfoCard(
                            flipperStatus = flipperStatus
                        )
                    }
                }
            }
            FlipperConnectionState.DISCONNECTED -> {
                // Show enhanced disconnected card with helpful empty state
                item(key = "flipper_disconnected") {
                    FlipperDisconnectedCardEnhanced(
                        hasEverConnected = onboardingSettings.hasEverConnected,
                        onConnect = onConnect,
                        onShowSetupWizard = onShowSetupWizard,
                        onLearnMore = onLearnMore,
                        onTroubleshooting = onTroubleshooting
                    )
                }
            }
            FlipperConnectionState.CONNECTING,
            FlipperConnectionState.CONNECTED,
            FlipperConnectionState.DISCOVERING_SERVICES,
            FlipperConnectionState.LAUNCHING_FAP -> {
                // Show connecting state
                item(key = "flipper_connecting") {
                    FlipperConnectingCard()
                }
            }
            FlipperConnectionState.ERROR -> {
                // Show error state with retry option
                item(key = "flipper_error") {
                    FlipperErrorCard(lastError = lastError, onRetry = onConnect)
                }
            }
        }
    }
}

@Composable
private fun FlipperConnectionCard(
    connectionState: FlipperConnectionState,
    connectionType: FlipperClient.ConnectionType,
    lastError: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                FlipperConnectionState.READY -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                FlipperConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                FlipperConnectionState.CONNECTING,
                FlipperConnectionState.CONNECTED,
                FlipperConnectionState.DISCOVERING_SERVICES -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (connectionState) {
                        FlipperConnectionState.READY -> Icons.Default.CheckCircle
                        FlipperConnectionState.ERROR -> Icons.Default.Error
                        FlipperConnectionState.CONNECTING,
                        FlipperConnectionState.CONNECTED,
                        FlipperConnectionState.DISCOVERING_SERVICES -> Icons.Default.Sync
                        else -> Icons.Default.UsbOff
                    },
                    contentDescription = null,
                    tint = when (connectionState) {
                        FlipperConnectionState.READY -> MaterialTheme.colorScheme.primary
                        FlipperConnectionState.ERROR -> MaterialTheme.colorScheme.error
                        FlipperConnectionState.CONNECTING,
                        FlipperConnectionState.CONNECTED,
                        FlipperConnectionState.DISCOVERING_SERVICES -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Flipper Zero",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (connectionState) {
                            FlipperConnectionState.READY -> "Connected via ${connectionType.name}"
                            FlipperConnectionState.CONNECTING -> "Connecting..."
                            FlipperConnectionState.CONNECTED -> "Handshaking..."
                            FlipperConnectionState.DISCOVERING_SERVICES -> "Discovering services..."
                            FlipperConnectionState.LAUNCHING_FAP -> "Launching Flock Bridge app..."
                            FlipperConnectionState.ERROR -> lastError ?: "Connection error"
                            FlipperConnectionState.DISCONNECTED -> "Not connected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Connection type badge when connected
                if (connectionState == FlipperConnectionState.READY) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (connectionType == FlipperClient.ConnectionType.USB)
                                    Icons.Default.Usb else Icons.Default.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = connectionType.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Connection control buttons
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (connectionState) {
                    FlipperConnectionState.READY -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disconnect")
                        }
                    }
                    FlipperConnectionState.DISCONNECTED,
                    FlipperConnectionState.ERROR -> {
                        Button(onClick = onConnect) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect")
                        }
                    }
                    else -> {
                        // Connecting states - show a loading indicator
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlipperStatusCard(
    flipperStatus: com.flockyou.scanner.flipper.FlipperStatusResponse?,
    isScanning: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "DEVICE STATUS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (flipperStatus != null) {
                // Battery row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when {
                                flipperStatus.batteryPercent > 80 -> Icons.Default.BatteryFull
                                flipperStatus.batteryPercent > 50 -> Icons.Default.Battery5Bar
                                flipperStatus.batteryPercent > 20 -> Icons.Default.Battery3Bar
                                else -> Icons.Default.Battery1Bar
                            },
                            contentDescription = null,
                            tint = when {
                                flipperStatus.batteryPercent > 20 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Battery")
                    }
                    Text(
                        text = "${flipperStatus.batteryPercent}%",
                        fontWeight = FontWeight.Bold,
                        color = if (flipperStatus.batteryPercent > 20)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // Uptime row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Uptime")
                    }
                    Text(
                        text = formatUptime(flipperStatus.uptimeSeconds),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // Scanning status row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning")
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isScanning)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (isScanning) "ACTIVE" else "IDLE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = "Waiting for status update...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FlipperScanStatsCard(
    detectionCount: Int,
    wipsAlertCount: Int,
    flipperStatus: com.flockyou.scanner.flipper.FlipperStatusResponse?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "SCAN STATISTICS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Stats grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Detections",
                    value = detectionCount.toString(),
                    icon = Icons.Default.Sensors
                )
                StatItem(
                    label = "WIPS Alerts",
                    value = wipsAlertCount.toString(),
                    icon = Icons.Default.Warning,
                    valueColor = if (wipsAlertCount > 0) MaterialTheme.colorScheme.error else null
                )
            }

            if (flipperStatus != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Detailed stats from Flipper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MiniStatItem("WiFi", flipperStatus.wifiScanCount.toString())
                    MiniStatItem("Sub-GHz", flipperStatus.subGhzDetectionCount.toString())
                    MiniStatItem("BLE", flipperStatus.bleScanCount.toString())
                    MiniStatItem("NFC", flipperStatus.nfcDetectionCount.toString())
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueColor: Color? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = valueColor ?: MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
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
private fun MiniStatItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Format a timestamp as relative time (e.g., "12s ago", "2m ago")
 */
@Composable
private fun formatRelativeTime(timestampMs: Long?): String {
    if (timestampMs == null) return "Never"

    // Use remember with a key that updates every second
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val diff = now - timestampMs
    return when {
        diff < 1000 -> "Just now"
        diff < 60_000 -> "${diff / 1000}s ago"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> "${diff / 86400_000}d ago"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlipperScanSchedulerCard(
    scanSchedulerStatus: com.flockyou.scanner.flipper.ScanSchedulerStatus,
    onTogglePause: () -> Unit = {},
    onTriggerManualScan: (com.flockyou.scanner.flipper.FlipperScanType) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (scanSchedulerStatus.isPaused)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            else
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with pause/resume button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (scanSchedulerStatus.isPaused)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (scanSchedulerStatus.isPaused) "SCANNING PAUSED" else "SCAN SCHEDULER",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (scanSchedulerStatus.isPaused)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.secondary
                        )
                        if (scanSchedulerStatus.isPaused) {
                            Text(
                                text = "Connection active, scans stopped",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Pause/Resume button
                IconButton(
                    onClick = onTogglePause,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (scanSchedulerStatus.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (scanSchedulerStatus.isPaused) "Resume scanning" else "Pause scanning",
                        tint = if (scanSchedulerStatus.isPaused)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Active scan loops with scan now buttons and timestamps
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // WiFi scan
                ScanLoopChipWithControls(
                    label = "WiFi",
                    isActive = scanSchedulerStatus.wifiScanActive && !scanSchedulerStatus.isPaused,
                    isScanning = scanSchedulerStatus.isWifiScanning,
                    intervalSeconds = scanSchedulerStatus.wifiScanIntervalSeconds,
                    lastScanTime = scanSchedulerStatus.lastWifiScanTime,
                    cooldownUntil = scanSchedulerStatus.wifiScanCooldownUntil,
                    isPaused = scanSchedulerStatus.isPaused,
                    onScanNow = { onTriggerManualScan(com.flockyou.scanner.flipper.FlipperScanType.WIFI) }
                )
                // Sub-GHz scan
                ScanLoopChipWithControls(
                    label = "Sub-GHz",
                    isActive = scanSchedulerStatus.subGhzScanActive && !scanSchedulerStatus.isPaused,
                    isScanning = scanSchedulerStatus.isSubGhzScanning,
                    intervalSeconds = scanSchedulerStatus.subGhzScanIntervalSeconds,
                    lastScanTime = scanSchedulerStatus.lastSubGhzScanTime,
                    cooldownUntil = scanSchedulerStatus.subGhzScanCooldownUntil,
                    isPaused = scanSchedulerStatus.isPaused,
                    onScanNow = { onTriggerManualScan(com.flockyou.scanner.flipper.FlipperScanType.SUB_GHZ) }
                )
                // BLE scan
                ScanLoopChipWithControls(
                    label = "BLE",
                    isActive = scanSchedulerStatus.bleScanActive && !scanSchedulerStatus.isPaused,
                    isScanning = scanSchedulerStatus.isBleScanning,
                    intervalSeconds = scanSchedulerStatus.bleScanIntervalSeconds,
                    lastScanTime = scanSchedulerStatus.lastBleScanTime,
                    cooldownUntil = scanSchedulerStatus.bleScanCooldownUntil,
                    isPaused = scanSchedulerStatus.isPaused,
                    onScanNow = { onTriggerManualScan(com.flockyou.scanner.flipper.FlipperScanType.BLE) }
                )
            }

            // Additional info (NFC, IR, WIPS)
            if (scanSchedulerStatus.wipsEnabled || scanSchedulerStatus.nfcScanEnabled || scanSchedulerStatus.irScanEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (scanSchedulerStatus.nfcScanEnabled) {
                        ScanLoopChipWithControls(
                            label = "NFC",
                            isActive = true,
                            isScanning = scanSchedulerStatus.isNfcScanning,
                            intervalSeconds = null,
                            lastScanTime = scanSchedulerStatus.lastNfcScanTime,
                            cooldownUntil = scanSchedulerStatus.nfcScanCooldownUntil,
                            isPaused = scanSchedulerStatus.isPaused,
                            isOnDemand = true,
                            onScanNow = { onTriggerManualScan(com.flockyou.scanner.flipper.FlipperScanType.NFC) }
                        )
                    }
                    if (scanSchedulerStatus.irScanEnabled) {
                        ScanLoopChipWithControls(
                            label = "IR",
                            isActive = true,
                            isScanning = scanSchedulerStatus.isIrScanning,
                            intervalSeconds = null,
                            lastScanTime = scanSchedulerStatus.lastIrScanTime,
                            cooldownUntil = scanSchedulerStatus.irScanCooldownUntil,
                            isPaused = scanSchedulerStatus.isPaused,
                            isOnDemand = true,
                            onScanNow = { onTriggerManualScan(com.flockyou.scanner.flipper.FlipperScanType.IR) }
                        )
                    }
                    if (scanSchedulerStatus.wipsEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FeatureChip("WIPS", true)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Active monitoring",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Sub-GHz frequency range info
            if (scanSchedulerStatus.subGhzScanActive && !scanSchedulerStatus.isPaused) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sub-GHz range: ${scanSchedulerStatus.subGhzFrequencyStart / 1_000_000}-${scanSchedulerStatus.subGhzFrequencyEnd / 1_000_000} MHz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Heartbeat status
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulsingDot(
                    isActive = scanSchedulerStatus.heartbeatActive,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Heartbeat: ${scanSchedulerStatus.heartbeatIntervalSeconds}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ScanLoopChipWithControls(
    label: String,
    isActive: Boolean,
    isScanning: Boolean,
    intervalSeconds: Int?,
    lastScanTime: Long?,
    cooldownUntil: Long,
    isPaused: Boolean,
    isOnDemand: Boolean = false,
    onScanNow: () -> Unit
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            now = System.currentTimeMillis()
        }
    }

    val isOnCooldown = now < cooldownUntil

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            isPaused -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            isScanning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsing indicator
            PulsingDot(
                isActive = isActive && !isPaused,
                isScanning = isScanning,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))

            // Label and interval
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isPaused -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (intervalSeconds != null) {
                        Text(
                            text = "Every ${intervalSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = " | ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    } else if (isOnDemand) {
                        Text(
                            text = "On-demand",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = " | ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = formatRelativeTime(lastScanTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lastScanTime != null)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Scan now button
            IconButton(
                onClick = onScanNow,
                enabled = !isOnCooldown && !isScanning,
                modifier = Modifier.size(32.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan now",
                        modifier = Modifier.size(18.dp),
                        tint = if (isOnCooldown)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsingDot(
    isActive: Boolean,
    isScanning: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    // Pulsing animation for actively scanning state
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Breathing animation for idle active state
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheAlpha"
    )

    Box(
        modifier = Modifier
            .size(10.dp)
            .graphicsLayer {
                if (isScanning) {
                    scaleX = pulseScale
                    scaleY = pulseScale
                    alpha = pulseAlpha
                } else if (isActive) {
                    alpha = breatheAlpha
                }
            }
            .background(
                color = if (isActive) color else color.copy(alpha = 0.3f),
                shape = CircleShape
            )
    )
}

// Keep the original ScanLoopChip for backward compatibility
@Composable
private fun ScanLoopChip(
    label: String,
    isActive: Boolean,
    intervalSeconds: Int
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        else
            MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PulsingDot(
                isActive = isActive,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isActive) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${intervalSeconds}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeatureChip(
    label: String,
    isEnabled: Boolean
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isEnabled)
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
        else
            MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isEnabled) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (isEnabled)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isEnabled)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlipperCapabilitiesCard(
    flipperStatus: com.flockyou.scanner.flipper.FlipperStatusResponse?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "CAPABILITIES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (flipperStatus != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CapabilityChip("WiFi", flipperStatus.wifiBoardConnected)
                    CapabilityChip("Sub-GHz", flipperStatus.subGhzReady)
                    CapabilityChip("BLE", flipperStatus.bleReady)
                    CapabilityChip("IR", flipperStatus.irReady)
                    CapabilityChip("NFC", flipperStatus.nfcReady)
                }
            } else {
                Text(
                    text = "Loading capabilities...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CapabilityChip(
    label: String,
    isAvailable: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isAvailable)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isAvailable) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isAvailable)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isAvailable)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FlipperDisconnectedCard(
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.UsbOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Flipper Zero Not Connected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connect your Flipper Zero via USB or Bluetooth to extend scanning capabilities with WiFi Board, Sub-GHz, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onConnect) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect Flipper")
            }
        }
    }
}

@Composable
private fun FlipperConnectingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Connecting to Flipper Zero...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Establishing connection and discovering services",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun FlipperErrorCard(
    lastError: String?,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Connection Error",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastError ?: "Failed to connect to Flipper Zero",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry Connection")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlipperAdvancedInfoCard(
    flipperStatus: com.flockyou.scanner.flipper.FlipperStatusResponse?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DeveloperMode,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ADVANCED INFO",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (flipperStatus != null) {
                // Protocol Version
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Protocol Version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "v${flipperStatus.protocolVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Detection Scheduler Status
                Text(
                    text = "Scanner Status",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ScannerStatusChip("SubGHz", flipperStatus.subGhzReady, flipperStatus.subGhzDetectionCount)
                    ScannerStatusChip("BLE", flipperStatus.bleReady, flipperStatus.bleScanCount)
                    ScannerStatusChip("NFC", flipperStatus.nfcReady, flipperStatus.nfcDetectionCount)
                    ScannerStatusChip("IR", flipperStatus.irReady, flipperStatus.irDetectionCount)
                    ScannerStatusChip("WiFi", flipperStatus.wifiBoardConnected, flipperStatus.wifiScanCount)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Raw Detection Counts
                Text(
                    text = "Raw Detection Counts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    RawDataRow("WiFi Scans", flipperStatus.wifiScanCount)
                    RawDataRow("Sub-GHz Detections", flipperStatus.subGhzDetectionCount)
                    RawDataRow("BLE Scans", flipperStatus.bleScanCount)
                    RawDataRow("IR Detections", flipperStatus.irDetectionCount)
                    RawDataRow("NFC Detections", flipperStatus.nfcDetectionCount)
                    RawDataRow("WIPS Alerts", flipperStatus.wipsAlertCount)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Uptime raw
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Uptime (raw)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${flipperStatus.uptimeSeconds}s",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                Text(
                    text = "Waiting for status data...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ScannerStatusChip(
    name: String,
    isReady: Boolean,
    count: Int
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isReady)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (isReady) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                        RoundedCornerShape(3.dp)
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RawDataRow(
    label: String,
    value: Int
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
            text = value.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

// ============================================================================
// Flipper UX Improvements - Device Picker & Enhanced Connection Card
// ============================================================================

/**
 * Enhanced connection card with signal strength indicator and auto-reconnect state.
 */
@Composable
private fun FlipperConnectionCardEnhanced(
    connectionState: FlipperConnectionState,
    connectionType: FlipperClient.ConnectionType,
    lastError: String?,
    autoReconnectState: com.flockyou.scanner.flipper.AutoReconnectState,
    connectionRssi: Int?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancelAutoReconnect: () -> Unit
) {
    // Pulsing animation for reconnecting state
    val pulseAlpha = if (autoReconnectState.isReconnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "reconnect_pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        ).value
    } else {
        1f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                autoReconnectState.isReconnecting -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                connectionState == FlipperConnectionState.READY -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                connectionState == FlipperConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                connectionState in listOf(
                    FlipperConnectionState.CONNECTING,
                    FlipperConnectionState.CONNECTED,
                    FlipperConnectionState.DISCOVERING_SERVICES
                ) -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon with optional pulse animation for reconnecting
                Box {
                    Icon(
                        imageVector = when {
                            autoReconnectState.isReconnecting -> Icons.Default.Sync
                            connectionState == FlipperConnectionState.READY -> Icons.Default.CheckCircle
                            connectionState == FlipperConnectionState.ERROR -> Icons.Default.Error
                            connectionState in listOf(
                                FlipperConnectionState.CONNECTING,
                                FlipperConnectionState.CONNECTED,
                                FlipperConnectionState.DISCOVERING_SERVICES
                            ) -> Icons.Default.Sync
                            else -> Icons.Default.UsbOff
                        },
                        contentDescription = null,
                        tint = when {
                            autoReconnectState.isReconnecting -> MaterialTheme.colorScheme.tertiary.copy(alpha = pulseAlpha)
                            connectionState == FlipperConnectionState.READY -> MaterialTheme.colorScheme.primary
                            connectionState == FlipperConnectionState.ERROR -> MaterialTheme.colorScheme.error
                            connectionState in listOf(
                                FlipperConnectionState.CONNECTING,
                                FlipperConnectionState.CONNECTED,
                                FlipperConnectionState.DISCOVERING_SERVICES
                            ) -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Flipper Zero",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            autoReconnectState.isReconnecting ->
                                "Reconnecting... (${autoReconnectState.attemptNumber}/${autoReconnectState.maxAttempts})"
                            connectionState == FlipperConnectionState.READY ->
                                "Connected via ${connectionType.name}"
                            connectionState == FlipperConnectionState.CONNECTING -> "Connecting..."
                            connectionState == FlipperConnectionState.CONNECTED -> "Handshaking..."
                            connectionState == FlipperConnectionState.DISCOVERING_SERVICES -> "Discovering services..."
                            connectionState == FlipperConnectionState.LAUNCHING_FAP -> "Launching Flock Bridge app..."
                            connectionState == FlipperConnectionState.ERROR -> lastError ?: "Connection error"
                            connectionState == FlipperConnectionState.DISCONNECTED -> "Not connected"
                            else -> "Unknown state"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Signal strength indicator (only for Bluetooth when connected)
                if (connectionState == FlipperConnectionState.READY && connectionType == FlipperClient.ConnectionType.BLUETOOTH) {
                    SignalStrengthIndicator(rssi = connectionRssi)
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Connection type badge when connected
                if (connectionState == FlipperConnectionState.READY) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (connectionType == FlipperClient.ConnectionType.USB)
                                    Icons.Default.Usb else Icons.Default.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = connectionType.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Connection control buttons
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    autoReconnectState.isReconnecting -> {
                        // Show cancel button during auto-reconnect
                        OutlinedButton(
                            onClick = onCancelAutoReconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cancel")
                        }
                    }
                    connectionState == FlipperConnectionState.READY -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disconnect")
                        }
                    }
                    connectionState == FlipperConnectionState.DISCONNECTED ||
                    connectionState == FlipperConnectionState.ERROR -> {
                        Button(onClick = onConnect) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan for Devices")
                        }
                    }
                    else -> {
                        // Connecting states - show a loading indicator
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Signal strength indicator based on RSSI.
 */
@Composable
private fun SignalStrengthIndicator(rssi: Int?) {
    val signalLevel = when {
        rssi == null -> 0
        rssi >= -50 -> 4  // Excellent
        rssi >= -60 -> 3  // Good
        rssi >= -70 -> 2  // Fair
        rssi >= -80 -> 1  // Weak
        else -> 0         // Very weak
    }

    val barColor = when (signalLevel) {
        4 -> Color(0xFF4CAF50)  // Green
        3 -> Color(0xFF8BC34A)  // Light Green
        2 -> Color(0xFFFFC107)  // Amber
        1 -> Color(0xFFFF9800)  // Orange
        else -> Color(0xFFF44336)  // Red
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((8 + i * 4).dp)
                    .background(
                        if (i <= signalLevel) barColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }

    // Show RSSI value in tooltip or small text
    rssi?.let {
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${it}dBm",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Bottom sheet dialog for scanning and selecting Flipper devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlipperDevicePickerBottomSheet(
    discoveredDevices: List<com.flockyou.scanner.flipper.DiscoveredFlipperDevice>,
    recentDevices: List<com.flockyou.scanner.flipper.RecentFlipperDevice>,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDiscovered: (com.flockyou.scanner.flipper.DiscoveredFlipperDevice) -> Unit,
    onSelectRecent: (com.flockyou.scanner.flipper.RecentFlipperDevice) -> Unit,
    onRemoveRecent: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connect to Flipper",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recent Devices Section
            if (recentDevices.isNotEmpty()) {
                Text(
                    text = "RECENT DEVICES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                recentDevices.forEach { device ->
                    RecentDeviceItem(
                        device = device,
                        onSelect = { onSelectRecent(device) },
                        onRemove = { onRemoveRecent(device.address) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Bluetooth Scan Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NEARBY DEVICES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onStopScan) {
                            Text("Stop")
                        }
                    }
                } else {
                    TextButton(onClick = onStartScan) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scan")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Discovered devices list
            if (discoveredDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isScanning) "Scanning for Flipper devices..." else "No devices found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!isScanning) {
                            Text(
                                text = "Make sure your Flipper is powered on",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                discoveredDevices.forEach { device ->
                    DiscoveredDeviceItem(
                        device = device,
                        onSelect = { onSelectDiscovered(device) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // USB connection option
            Divider()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "OTHER OPTIONS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss, // USB connects automatically when plugged in
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "USB Connection",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Connect Flipper via USB cable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * List item for a recently connected device.
 */
@Composable
private fun RecentDeviceItem(
    device: com.flockyou.scanner.flipper.RecentFlipperDevice,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (device.connectionType == "BLUETOOTH")
                    Icons.Default.Bluetooth else Icons.Default.Usb,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * List item for a discovered Bluetooth device.
 */
@Composable
private fun DiscoveredDeviceItem(
    device: com.flockyou.scanner.flipper.DiscoveredFlipperDevice,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            // Signal strength
            SignalStrengthIndicator(rssi = device.rssi)
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * HTTPS tile source for OpenStreetMap embedded maps.
 */
private val DETAIL_SHEET_TILE_SOURCE = object : OnlineTileSourceBase(
    "Mapnik-HTTPS-Detail",
    0,
    19,
    256,
    ".png",
    arrayOf("https://a.tile.openstreetmap.org/", "https://b.tile.openstreetmap.org/", "https://c.tile.openstreetmap.org/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}$zoom/$x/$y$mImageFilenameEnding"
    }
}

/**
 * Creates a marker drawable for the embedded map
 */
private fun createDetailMapMarkerDrawable(threatLevel: ThreatLevel): android.graphics.drawable.Drawable {
    val color = when (threatLevel) {
        ThreatLevel.CRITICAL -> android.graphics.Color.parseColor("#D32F2F")
        ThreatLevel.HIGH -> android.graphics.Color.parseColor("#F57C00")
        ThreatLevel.MEDIUM -> android.graphics.Color.parseColor("#FBC02D")
        ThreatLevel.LOW -> android.graphics.Color.parseColor("#388E3C")
        ThreatLevel.INFO -> android.graphics.Color.parseColor("#1976D2")
    }

    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(3, android.graphics.Color.WHITE)
        setSize(32, 32)
    }
}

/**
 * Build a shareable text summary of a detection
 */
private fun buildDetectionShareText(detection: Detection): String {
    val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", java.util.Locale.getDefault())
    return buildString {
        appendLine("Flock You Detection Alert")
        appendLine("=" .repeat(30))
        appendLine()
        appendLine("Device: ${detection.deviceType.displayName}")
        appendLine("Threat Level: ${detection.threatLevel.displayName}")
        appendLine("Protocol: ${detection.protocol.displayName}")
        appendLine("Detection Method: ${detection.detectionMethod.displayName}")
        appendLine()
        appendLine("Signal: ${detection.rssi} dBm (${detection.signalStrength.displayName})")
        appendLine("First Seen: ${dateFormat.format(java.util.Date(detection.timestamp))}")
        if (detection.seenCount > 1) {
            appendLine("Times Seen: ${detection.seenCount}")
            appendLine("Last Seen: ${dateFormat.format(java.util.Date(detection.lastSeenTimestamp))}")
        }
        appendLine()
        detection.macAddress?.let { appendLine("MAC Address: $it") }
        detection.ssid?.let { appendLine("SSID: $it") }
        detection.deviceName?.let { appendLine("Device Name: $it") }
        detection.manufacturer?.let { appendLine("Manufacturer: $it") }
        if (detection.latitude != null && detection.longitude != null) {
            appendLine()
            appendLine("Location: ${detection.latitude}, ${detection.longitude}")
        }
        detection.userNote?.let {
            appendLine()
            appendLine("Note: $it")
        }
        appendLine()
        appendLine("-- Shared from Flock You app")
    }
}

/**
 * Build a JSON export of a detection
 */
private fun buildDetectionExportJson(detection: Detection): String {
    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.getDefault())
    return buildString {
        appendLine("{")
        appendLine("  \"id\": \"${detection.id}\",")
        appendLine("  \"timestamp\": \"${dateFormat.format(java.util.Date(detection.timestamp))}\",")
        appendLine("  \"lastSeenTimestamp\": \"${dateFormat.format(java.util.Date(detection.lastSeenTimestamp))}\",")
        appendLine("  \"deviceType\": \"${detection.deviceType.name}\",")
        appendLine("  \"deviceTypeDisplayName\": \"${detection.deviceType.displayName}\",")
        appendLine("  \"threatLevel\": \"${detection.threatLevel.name}\",")
        appendLine("  \"threatScore\": ${detection.threatScore},")
        appendLine("  \"protocol\": \"${detection.protocol.name}\",")
        appendLine("  \"detectionMethod\": \"${detection.detectionMethod.name}\",")
        appendLine("  \"rssi\": ${detection.rssi},")
        appendLine("  \"signalStrength\": \"${detection.signalStrength.name}\",")
        detection.macAddress?.let { appendLine("  \"macAddress\": \"$it\",") }
        detection.ssid?.let { appendLine("  \"ssid\": \"${it.replace("\"", "\\\"")}\",") }
        detection.deviceName?.let { appendLine("  \"deviceName\": \"${it.replace("\"", "\\\"")}\",") }
        detection.manufacturer?.let { appendLine("  \"manufacturer\": \"${it.replace("\"", "\\\"")}\",") }
        detection.latitude?.let { appendLine("  \"latitude\": $it,") }
        detection.longitude?.let { appendLine("  \"longitude\": $it,") }
        appendLine("  \"seenCount\": ${detection.seenCount},")
        appendLine("  \"isActive\": ${detection.isActive},")
        appendLine("  \"detectionSource\": \"${detection.detectionSource.name}\",")
        detection.fpScore?.let { appendLine("  \"fpScore\": $it,") }
        detection.fpReason?.let { appendLine("  \"fpReason\": \"${it.replace("\"", "\\\"")}\",") }
        detection.fpCategory?.let { appendLine("  \"fpCategory\": \"$it\",") }
        detection.userNote?.let { appendLine("  \"userNote\": \"${it.replace("\"", "\\\"").replace("\n", "\\n")}\",") }
        appendLine("  \"confirmedThreat\": ${detection.confirmedThreat},")
        appendLine("  \"exportedAt\": \"${dateFormat.format(java.util.Date())}\"")
        appendLine("}")
    }
}

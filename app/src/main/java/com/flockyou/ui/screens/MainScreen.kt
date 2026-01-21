@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.flockyou.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import com.flockyou.scanner.flipper.FlipperClient
import com.flockyou.scanner.flipper.FlipperConnectionState
import com.flockyou.service.CellularMonitor
import com.flockyou.service.ScanningService
import java.text.SimpleDateFormat
import java.util.*
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
                                if (uiState.cellularAnomalies.isNotEmpty()) {
                                    Badge { Text(uiState.cellularAnomalies.size.toString()) }
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
                        if (uiState.isScanning || uiState.cellularAnomalies.isNotEmpty()) {
                            item(key = "cellular_status_card") {
                                CellularStatusCard(
                                    cellStatus = uiState.cellStatus,
                                    anomalies = uiState.cellularAnomalies,
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
                                wifiAnomalyCount = uiState.rogueWifiAnomalies.size,
                                rfAnomalyCount = viewModel.getFilteredRfAnomalies().size,
                                ultrasonicBeaconCount = uiState.ultrasonicBeacons.size,
                                satelliteAnomalyCount = uiState.satelliteAnomalies.size
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
                                    DetectionCard(
                                        detection = detection,
                                        onClick = { selectedDetection = detection },
                                        advancedMode = uiState.advancedMode,
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
                            cellularAnomalies = uiState.cellularAnomalies,
                            seenCellTowers = uiState.seenCellTowers,
                            satelliteState = uiState.satelliteState,
                            satelliteAnomalies = uiState.satelliteAnomalies,
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
                            lastError = uiState.flipperLastError
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
        DetectionDetailSheet(
            detection = detection,
            onDismiss = { selectedDetection = null },
            onDelete = {
                viewModel.deleteDetection(detection)
                selectedDetection = null
            },
            advancedMode = uiState.advancedMode
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
    advancedMode: Boolean = false
) {
    val threatColor = detection.threatLevel.toColor()
    val deviceInfo = DetectionPatterns.getDeviceTypeInfo(detection.deviceType)
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()) }
    
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
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(threatColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = detection.deviceType.emoji,
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = deviceInfo.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = deviceInfo.shortDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Threat level banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = threatColor.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (detection.threatLevel) {
                                ThreatLevel.CRITICAL -> Icons.Default.Warning
                                ThreatLevel.HIGH -> Icons.Default.Error
                                else -> Icons.Default.Info
                            },
                            contentDescription = null,
                            tint = threatColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "${detection.threatLevel.displayName} Threat (${detection.threatScore}/100)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = threatColor
                            )
                            Text(
                                text = detection.threatLevel.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Device Description
            item {
                Text(
                    text = "About This Device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = deviceInfo.fullDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // AI Analysis Section (only show if LLM analysis was performed)
            if (detection.llmAnalyzed) {
                item {
                    Text(
                        text = "AI Analysis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // LLM badge
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
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
                                        text = "On-device LLM",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // FP verdict
                            if (detection.fpScore != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                val fpPercent = (detection.fpScore * 100).toInt()
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
                                    progress = detection.fpScore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = verdictColor,
                                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                                )
                            }

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
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Technical Details
            item {
                Text(
                    text = "Detection Details",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            item {
                DetailRow(
                    label = "First Detected",
                    value = dateFormat.format(Date(detection.timestamp))
                )
            }
            
            if (detection.lastSeenTimestamp != detection.timestamp) {
                item {
                    DetailRow(
                        label = "Last Seen",
                        value = dateFormat.format(Date(detection.lastSeenTimestamp))
                    )
                }
            }
            
            if (detection.seenCount > 1) {
                item {
                    DetailRow(
                        label = "Times Seen",
                        value = "${detection.seenCount}x"
                    )
                }
            }
            
            item {
                DetailRow(
                    label = "Status",
                    value = if (detection.isActive) "🟢 Active" else "⚪ Inactive"
                )
            }
            
            item {
                DetailRow(
                    label = "Protocol",
                    value = "${detection.protocol.icon} ${detection.protocol.displayName}"
                )
            }
            
            item {
                DetailRow(
                    label = "Method",
                    value = detection.detectionMethod.displayName
                )
            }
            
            // Show different fields based on protocol
            if (detection.protocol == DetectionProtocol.CELLULAR) {
                // Cellular-specific fields
                detection.firmwareVersion?.let { cellId ->
                    item {
                        DetailRow(label = "Cell Info", value = cellId)
                    }
                }
                
                detection.macAddress?.let { mccMnc ->
                    item {
                        DetailRow(label = "MCC-MNC", value = mccMnc)
                    }
                }
                
                detection.manufacturer?.let { networkType ->
                    item {
                        DetailRow(label = "Network Type", value = networkType)
                    }
                }
            } else {
                // WiFi/BLE fields
                detection.macAddress?.let { mac ->
                    item {
                        DetailRow(label = "MAC Address", value = mac)
                    }
                }
                
                detection.ssid?.let { ssid ->
                    item {
                        DetailRow(label = "SSID", value = ssid)
                    }
                }
                
                detection.manufacturer?.let { mfr ->
                    item {
                        DetailRow(label = "Manufacturer", value = mfr)
                    }
                }
                
                detection.firmwareVersion?.let { fw ->
                    item {
                        DetailRow(label = "Firmware", value = fw)
                    }
                }
            }
            
            detection.deviceName?.let { name ->
                item {
                    DetailRow(label = "Device Name", value = name)
                }
            }
            
            item {
                DetailRow(
                    label = "Signal",
                    value = "${detection.rssi} dBm (${detection.signalStrength.displayName})"
                )
            }
            
            item {
                DetailRow(
                    label = "Est. Distance",
                    value = rssiToDistance(detection.rssi)
                )
            }
            
            // Location Section
            if (detection.latitude != null && detection.longitude != null) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "📍 Location",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                item {
                    DetailRow(
                        label = "Coordinates",
                        value = "%.6f, %.6f".format(detection.latitude, detection.longitude)
                    )
                }
                
                item {
                    // Clickable location card to open in maps
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "View on Map",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Tap detection card to see on app map",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            // Capabilities Section
            if (deviceInfo.capabilities.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Known Capabilities",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                items(deviceInfo.capabilities.size) { index ->
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
                            text = deviceInfo.capabilities[index],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Privacy Concerns Section
            if (deviceInfo.privacyConcerns.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "⚠️ Privacy Concerns",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                items(deviceInfo.privacyConcerns.size) { index ->
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
                            text = deviceInfo.privacyConcerns[index],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Recommendations Section
            if (deviceInfo.recommendations.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "🛡️ What You Can Do",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(deviceInfo.recommendations.size) { index ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = deviceInfo.recommendations[index],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Advanced Mode: Raw Technical Data Section
            if (advancedMode) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "🔧 Raw Technical Data",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
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
            }

            // Actions
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete")
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Close")
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
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

@Composable
private fun CellularTabContent(
    modifier: Modifier = Modifier,
    cellStatus: CellularMonitor.CellStatus?,
    cellularStatus: ScanningService.SubsystemStatus,
    cellularAnomalies: List<CellularMonitor.CellularAnomaly>,
    seenCellTowers: List<CellularMonitor.SeenCellTower>,
    satelliteState: com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState?,
    satelliteAnomalies: List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly>,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    onClearCellularHistory: () -> Unit,
    onClearSatelliteHistory: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
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
                    TextButton(onClick = onClearCellularHistory) {
                        Text("Clear")
                    }
                }
            }
            
            items(
                items = seenCellTowers,
                key = { "${it.mcc}-${it.mnc}-${it.lac}-${it.cellId}" }
            ) { tower ->
                CellTowerHistoryCard(tower = tower, dateFormat = dateFormat)
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
    lastError: String?
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection Status Card
        item(key = "flipper_connection") {
            FlipperConnectionCard(
                connectionState = connectionState,
                connectionType = connectionType,
                lastError = lastError
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
            }
            FlipperConnectionState.DISCONNECTED -> {
                // Show connection prompt when disconnected
                item(key = "flipper_disconnected") {
                    FlipperDisconnectedCard()
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
                    FlipperErrorCard(lastError = lastError)
                }
            }
        }
    }
}

@Composable
private fun FlipperConnectionCard(
    connectionState: FlipperConnectionState,
    connectionType: FlipperClient.ConnectionType,
    lastError: String?
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
                // Connection type badge
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
private fun FlipperDisconnectedCard() {
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
            Text(
                text = "Go to Settings → Flipper Zero to connect",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
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
private fun FlipperErrorCard(lastError: String?) {
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
            Text(
                text = "Go to Settings → Flipper Zero to retry",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
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

package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.model.*
import com.flockyou.service.ScanningService
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.flockyou.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateToMap: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToNearby: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var selectedDetection by remember { mutableStateOf<Detection?>(null) }
    
    Scaffold(
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
                    IconButton(onClick = { showFilterSheet = true }) {
                        Badge(
                            containerColor = if (uiState.filterThreatLevel != null || uiState.filterDeviceType != null)
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
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) }
                )
                NavigationBarItem(
                    icon = { 
                        BadgedBox(
                            badge = {
                                if (uiState.highThreatCount > 0) {
                                    Badge { Text(uiState.highThreatCount.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = "Threats")
                        }
                    },
                    label = { Text("Threats") },
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") },
                    selected = uiState.selectedTab == 2,
                    onClick = { viewModel.selectTab(2) }
                )
            }
        }
    ) { paddingValues ->
        // Calculate filtered detections outside LazyColumn
        val filteredDetections = viewModel.getFilteredDetections()
        val detections = when (uiState.selectedTab) {
            1 -> filteredDetections.filter { 
                it.threatLevel == ThreatLevel.CRITICAL || it.threatLevel == ThreatLevel.HIGH 
            }
            else -> filteredDetections
        }
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status card
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
                    recentErrors = uiState.recentErrors,
                    onClearErrors = { viewModel.clearErrors() }
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
            
            // Last detection alert
            uiState.lastDetection?.let { detection ->
                item(key = "last_detection_${detection.id}") {
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        LastDetectionAlert(
                            detection = detection,
                            onClick = { selectedDetection = detection }
                        )
                    }
                }
            }
            
            // Filter chips if filters active
            if (uiState.filterThreatLevel != null || uiState.filterDeviceType != null) {
                item(key = "filter_chips") {
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
                        uiState.filterDeviceType?.let { type ->
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.setDeviceTypeFilter(null) },
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
                    }
                }
            }
            
            // Section header
            item(key = "section_header") {
                Text(
                    text = when (uiState.selectedTab) {
                        1 -> "HIGH PRIORITY THREATS"
                        2 -> "DETECTION HISTORY"
                        else -> "RECENT DETECTIONS"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            if (detections.isEmpty()) {
                item(key = "empty_state") {
                    EmptyState(isScanning = uiState.isScanning)
                }
            } else {
                items(
                    items = detections,
                    key = { it.id }
                ) { detection ->
                    DetectionCard(
                        detection = detection,
                        onClick = { selectedDetection = detection }
                    )
                }
            }
        }
    }
    
    // Filter bottom sheet
    if (showFilterSheet) {
        FilterBottomSheet(
            currentThreatFilter = uiState.filterThreatLevel,
            currentTypeFilter = uiState.filterDeviceType,
            onThreatFilterChange = { viewModel.setThreatFilter(it) },
            onTypeFilterChange = { viewModel.setDeviceTypeFilter(it) },
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
            }
        )
    }
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
    currentTypeFilter: DeviceType?,
    onThreatFilterChange: (ThreatLevel?) -> Unit,
    onTypeFilterChange: (DeviceType?) -> Unit,
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
        ) {
            Text(
                text = "Filter Detections",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
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
            
            Text(
                text = "Device Type",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                                selected = currentTypeFilter == type,
                                onClick = {
                                    onTypeFilterChange(if (currentTypeFilter == type) null else type)
                                },
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
    onDelete: () -> Unit
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
                .padding(horizontal = 16.dp)
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
private fun ThreatLevel.toColor(): Color = when (this) {
    ThreatLevel.CRITICAL -> Color(0xFFD32F2F)
    ThreatLevel.HIGH -> Color(0xFFF57C00)
    ThreatLevel.MEDIUM -> Color(0xFFFBC02D)
    ThreatLevel.LOW -> Color(0xFF388E3C)
    ThreatLevel.INFO -> Color(0xFF1976D2)
}

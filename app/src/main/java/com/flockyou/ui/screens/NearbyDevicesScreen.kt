package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flockyou.service.CellularMonitor
import com.flockyou.service.ScanningService
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyDevicesScreen(
    onNavigateBack: () -> Unit
) {
    val seenBleDevices by ScanningService.seenBleDevices.collectAsState()
    val seenWifiNetworks by ScanningService.seenWifiNetworks.collectAsState()
    val cellStatus by ScanningService.cellStatus.collectAsState()
    val cellularStatus by ScanningService.cellularStatus.collectAsState()
    val seenCellTowers by ScanningService.seenCellTowers.collectAsState()
    val cellularAnomalies by ScanningService.cellularAnomalies.collectAsState()
    val isScanning by ScanningService.isScanning.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("BLE", "WiFi", "Cellular")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Nearby Devices")
                        Text(
                            text = "All detected wireless activity",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { ScanningService.clearSeenDevices() }) {
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
                                        0 -> Icons.Default.Bluetooth
                                        1 -> Icons.Default.Wifi
                                        else -> Icons.Default.CellTower
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(title)
                                if (index < 2) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Badge {
                                        Text(
                                            if (index == 0) seenBleDevices.size.toString() 
                                            else seenWifiNetworks.size.toString()
                                        )
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
                            text = "Start scanning to discover nearby devices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            
            // Tab content
            when (selectedTab) {
                0, 1 -> {
                    // BLE and WiFi tabs
                    val devices = if (selectedTab == 0) seenBleDevices else seenWifiNetworks
                    
                    if (devices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = if (selectedTab == 0) Icons.Default.Bluetooth else Icons.Default.Wifi,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (isScanning) "Scanning..." else "No devices seen yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Devices that don't match surveillance patterns will appear here",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Summary card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        StatBox(
                                            label = "Total Seen",
                                            value = devices.size.toString()
                                        )
                                        StatBox(
                                            label = "With Names",
                                            value = devices.count { !it.name.isNullOrEmpty() }.toString()
                                        )
                                        StatBox(
                                            label = "Known Mfr",
                                            value = devices.count { it.manufacturer != null }.toString()
                                        )
                                    }
                                }
                            }
                            
                            items(devices.sortedByDescending { it.lastSeen }) { device ->
                                SeenDeviceCard(device = device, isBle = selectedTab == 0)
                            }
                            
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
                2 -> {
                    // Cellular tab
                    CellularStatusContent(
                        cellStatus = cellStatus,
                        cellularStatus = cellularStatus,
                        seenCellTowers = seenCellTowers,
                        cellularAnomalies = cellularAnomalies,
                        isScanning = isScanning
                    )
                }
            }
        }
    }
}

@Composable
private fun CellularStatusContent(
    cellStatus: CellularMonitor.CellStatus?,
    cellularStatus: ScanningService.SubsystemStatus,
    seenCellTowers: List<CellularMonitor.SeenCellTower>,
    cellularAnomalies: List<CellularMonitor.CellularAnomaly>,
    isScanning: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    LazyColumn(
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        Spacer(modifier = Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when (cellularStatus) {
                                is ScanningService.SubsystemStatus.Active -> Color(0xFF4CAF50)
                                is ScanningService.SubsystemStatus.PermissionDenied -> Color(0xFFF44336)
                                else -> Color(0xFF9E9E9E)
                            }
                        ) {
                            Text(
                                text = when (cellularStatus) {
                                    is ScanningService.SubsystemStatus.Active -> "Active"
                                    is ScanningService.SubsystemStatus.PermissionDenied -> "No Permission"
                                    is ScanningService.SubsystemStatus.Error -> "Error"
                                    else -> "Idle"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    if (cellularStatus is ScanningService.SubsystemStatus.PermissionDenied) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠️ READ_PHONE_STATE permission required for IMSI catcher detection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        
        // Current cell info
        if (cellStatus != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "📶 Current Cell Tower",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Network generation badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (cellStatus.networkGeneration) {
                                    "5G" -> Color(0xFF2196F3)
                                    "4G" -> Color(0xFF4CAF50)
                                    "3G" -> Color(0xFFFFC107)
                                    "2G" -> Color(0xFFF44336)
                                    else -> Color(0xFF9E9E9E)
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
                            Column {
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
                            Spacer(modifier = Modifier.weight(1f))
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
                        
                        // Cell details grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CellInfoItem("Cell ID", cellStatus.cellId, Modifier.weight(1f))
                            cellStatus.mcc?.let { 
                                CellInfoItem("MCC", it, Modifier.weight(1f))
                            }
                            cellStatus.mnc?.let {
                                CellInfoItem("MNC", it, Modifier.weight(1f))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            cellStatus.lac?.let {
                                CellInfoItem("LAC", it.toString(), Modifier.weight(1f))
                            }
                            cellStatus.tac?.let {
                                CellInfoItem("TAC", it.toString(), Modifier.weight(1f))
                            }
                        }
                        
                        // Status indicators
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!cellStatus.isKnownCell) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = "🆕 New Tower",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            if (cellStatus.isRoaming) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = "📍 Roaming",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Info card about what this monitors
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ℹ️ About Cellular Monitoring",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This monitors your cellular connection for signs of IMSI catchers " +
                                "(StingRay, Hailstorm, etc.) which are fake cell towers used for surveillance.\n\n" +
                                "Anomalies detected:\n" +
                                "• Encryption downgrade (4G/5G → 2G)\n" +
                                "• Suspicious network identifiers\n" +
                                "• Unexpected cell tower changes\n" +
                                "• Rapid cell switching\n" +
                                "• Signal strength anomalies",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                
                items(cellularAnomalies.take(10)) { anomaly ->
                    AnomalyCard(anomaly = anomaly, dateFormat = dateFormat)
                }
                
                if (cellularAnomalies.size > 10) {
                    item {
                        Text(
                            text = "+${cellularAnomalies.size - 10} more anomalies",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            
            // Cell tower history section
            if (seenCellTowers.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🗼 Cell Tower History (${seenCellTowers.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { ScanningService.clearCellularHistory() }) {
                            Text("Clear", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                
                items(seenCellTowers) { tower ->
                    SeenCellTowerCard(tower = tower, dateFormat = dateFormat)
                }
            }
        } else if (!isScanning) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CellTower,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Start scanning to monitor cellular network",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
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
                            text = "Acquiring cell info...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CellInfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun StatBox(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeenDeviceCard(
    device: ScanningService.SeenDevice,
    isBle: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Signal indicator
                Box(
                    modifier = Modifier
                        .size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isBle) Icons.Default.Bluetooth else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = rssiToColor(device.rssi),
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name ?: "(No name)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (device.name != null) 
                            MaterialTheme.colorScheme.onSurface 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = device.id,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (device.manufacturer != null) {
                        Text(
                            text = device.manufacturer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${device.rssi} dBm",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = rssiToColor(device.rssi)
                    )
                    Text(
                        text = dateFormat.format(Date(device.lastSeen)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (device.seenCount > 1) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${device.seenCount}x",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            // Expanded details
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    DetailRow("First Seen", dateFormat.format(Date(device.firstSeen)))
                    DetailRow("Last Seen", dateFormat.format(Date(device.lastSeen)))
                    DetailRow("Times Seen", device.seenCount.toString())
                    DetailRow("Signal", "${device.rssi} dBm (${rssiToDescription(device.rssi)})")
                    device.manufacturer?.let { DetailRow("Manufacturer", it) }
                    
                    if (device.serviceUuids.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Service UUIDs:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        device.serviceUuids.take(5).forEach { uuid ->
                            Text(
                                text = uuid,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                            )
                        }
                        if (device.serviceUuids.size > 5) {
                            Text(
                                text = "... and ${device.serviceUuids.size - 5} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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

private fun rssiToColor(rssi: Int): Color {
    return when {
        rssi >= -50 -> Color(0xFF4CAF50)  // Excellent - Green
        rssi >= -60 -> Color(0xFF8BC34A)  // Good - Light Green
        rssi >= -70 -> Color(0xFFFFC107)  // Medium - Yellow
        rssi >= -80 -> Color(0xFFFF9800)  // Weak - Orange
        else -> Color(0xFFF44336)          // Very Weak - Red
    }
}

private fun rssiToDescription(rssi: Int): String {
    return when {
        rssi >= -50 -> "Excellent, very close"
        rssi >= -60 -> "Good"
        rssi >= -70 -> "Medium"
        rssi >= -80 -> "Weak"
        else -> "Very weak, far away"
    }
}

@Composable
private fun AnomalyCard(
    anomaly: CellularMonitor.CellularAnomaly,
    dateFormat: SimpleDateFormat
) {
    val severityColor = when (anomaly.severity) {
        com.flockyou.data.model.ThreatLevel.CRITICAL -> Color(0xFFD32F2F)
        com.flockyou.data.model.ThreatLevel.HIGH -> Color(0xFFF57C00)
        com.flockyou.data.model.ThreatLevel.MEDIUM -> Color(0xFFFBC02D)
        else -> Color(0xFF9E9E9E)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = severityColor.copy(alpha = 0.15f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = anomaly.type.emoji,
                        style = MaterialTheme.typography.titleMedium
                    )
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
private fun SeenCellTowerCard(
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
                    // Network generation badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (tower.networkGeneration) {
                            "5G" -> Color(0xFF2196F3)
                            "4G" -> Color(0xFF4CAF50)
                            "3G" -> Color(0xFFFFC107)
                            "2G" -> Color(0xFFF44336)
                            else -> Color(0xFF9E9E9E)
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
                            text = tower.operator ?: "Unknown Operator",
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
                        text = "${tower.signalStrength} dBm",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${tower.seenCount}x seen",
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
                        tower.tac?.let {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("TAC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    
                    if (tower.latitude != null && tower.longitude != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "📍 ${String.format("%.6f", tower.latitude)}, ${String.format("%.6f", tower.longitude)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

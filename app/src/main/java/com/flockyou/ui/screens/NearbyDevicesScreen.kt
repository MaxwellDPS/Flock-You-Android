package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.monitoring.GnssSatelliteMonitor
import com.flockyou.monitoring.GnssSatelliteMonitor.*
import com.flockyou.service.CellularMonitor
import com.flockyou.service.ScanningService
import com.flockyou.service.UltrasonicDetector
import com.flockyou.service.UltrasonicDetector.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyDevicesScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    // Collect UI state from ViewModel (data comes via IPC from service process)
    val uiState by viewModel.uiState.collectAsState()

    // Extract values from UI state
    val seenBleDevices = uiState.seenBleDevices
    val seenWifiNetworks = uiState.seenWifiNetworks
    val cellStatus = uiState.cellStatus
    val cellularStatus = uiState.cellularStatus
    val seenCellTowers = uiState.seenCellTowers
    val cellularAnomalies = uiState.cellularAnomalies
    val isScanning = uiState.isScanning

    // Satellite state
    val satelliteState = uiState.satelliteState
    val satelliteAnomalies = uiState.satelliteAnomalies
    val satelliteStatus = uiState.satelliteStatus
    
    // GNSS satellite monitoring data
    val gnssStatus = uiState.gnssStatus
    val gnssSatellites = uiState.gnssSatellites
    val gnssAnomalies = uiState.gnssAnomalies

    // Ultrasonic detection data
    val ultrasonicStatus = uiState.ultrasonicStatus
    val ultrasonicAnomalies = uiState.ultrasonicAnomalies
    val ultrasonicBeacons = uiState.ultrasonicBeacons

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("BLE", "WiFi", "Cell", "GNSS", "Audio", "Sat")
    
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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearSeenDevices() }) {
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
            // Tab row - scrollable to fit all tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.wrapContentWidth()
                            ) {
                                Icon(
                                    imageVector = when (index) {
                                        0 -> Icons.Default.Bluetooth
                                        1 -> Icons.Default.Wifi
                                        2 -> Icons.Default.CellTower
                                        3 -> Icons.Default.GpsFixed
                                        4 -> Icons.Default.Mic
                                        else -> Icons.Default.SatelliteAlt
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = title,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                when (index) {
                                    0 -> if (seenBleDevices.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge { Text(seenBleDevices.size.toString()) }
                                    }
                                    1 -> if (seenWifiNetworks.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge { Text(seenWifiNetworks.size.toString()) }
                                    }
                                    3 -> if (gnssSatellites.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge { Text(gnssSatellites.size.toString()) }
                                    }
                                    4 -> if (ultrasonicBeacons.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ) { Text(ultrasonicBeacons.size.toString()) }
                                    }
                                    5 -> if (satelliteAnomalies.isNotEmpty()) {
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
                            
                            items(
                                items = devices.sortedByDescending { it.lastSeen },
                                key = { it.id }
                            ) { device ->
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
                3 -> {
                    // GNSS tab
                    GnssStatusContent(
                        gnssStatus = gnssStatus,
                        gnssSatellites = gnssSatellites,
                        gnssAnomalies = gnssAnomalies,
                        isScanning = isScanning
                    )
                }
                4 -> {
                    // Ultrasonic tab
                    UltrasonicStatusContent(
                        ultrasonicStatus = ultrasonicStatus,
                        ultrasonicBeacons = ultrasonicBeacons,
                        ultrasonicAnomalies = ultrasonicAnomalies,
                        isScanning = isScanning
                    )
                }
                5 -> {
                    // Satellite tab
                    SatelliteStatusContent(
                        satelliteState = satelliteState,
                        satelliteStatus = satelliteStatus,
                        satelliteAnomalies = satelliteAnomalies,
                        isScanning = isScanning
                    )
                }
            }
        }
    }
}

@Composable
private fun UltrasonicStatusContent(
    ultrasonicStatus: UltrasonicStatus?,
    ultrasonicBeacons: List<BeaconDetection>,
    ultrasonicAnomalies: List<UltrasonicAnomaly>,
    isScanning: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val isActive = ultrasonicStatus?.isScanning == true

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main status card
        item {
            val threatColor = when (ultrasonicStatus?.threatLevel) {
                com.flockyou.data.model.ThreatLevel.CRITICAL -> Color(0xFFD32F2F)
                com.flockyou.data.model.ThreatLevel.HIGH -> Color(0xFFF44336)
                com.flockyou.data.model.ThreatLevel.MEDIUM -> Color(0xFFFF9800)
                com.flockyou.data.model.ThreatLevel.LOW -> Color(0xFFFFC107)
                else -> Color(0xFF4CAF50)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = if (isActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isActive -> Color(0xFF4CAF50)
                                                isScanning -> Color(0xFFFFC107)
                                                else -> Color(0xFF9E9E9E)
                                            }
                                        )
                                )
                            }

                            Column {
                                Text(
                                    text = if (isActive) "Ultrasonic Detection Active" else "Ultrasonic Detection",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when {
                                        isActive -> "Monitoring 18-22 kHz frequencies"
                                        isScanning -> "Starting audio monitoring..."
                                        else -> "Audio monitoring inactive"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Threat level indicator
                        if (ultrasonicStatus != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = threatColor.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = ultrasonicStatus.threatLevel.displayName,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = threatColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (ultrasonicStatus != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            UltrasonicMetricItem(
                                label = "Noise Floor",
                                value = "${String.format("%.1f", ultrasonicStatus.noiseFloorDb)} dB"
                            )
                            UltrasonicMetricItem(
                                label = "Active Beacons",
                                value = "${ultrasonicStatus.activeBeaconCount}"
                            )
                            ultrasonicStatus.peakFrequency?.let { freq ->
                                UltrasonicMetricItem(
                                    label = "Peak Freq",
                                    value = "${freq} Hz"
                                )
                            } ?: UltrasonicMetricItem(
                                label = "Peak Freq",
                                value = "-"
                            )
                            ultrasonicStatus.peakAmplitudeDb?.let { amp ->
                                UltrasonicMetricItem(
                                    label = "Peak Amp",
                                    value = "${String.format("%.1f", amp)} dB"
                                )
                            } ?: UltrasonicMetricItem(
                                label = "Peak Amp",
                                value = "-"
                            )
                        }

                        // Last scan time
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Last scan: ${dateFormat.format(Date(ultrasonicStatus.lastScanTime))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Active beacons section
        if (ultrasonicBeacons.isNotEmpty()) {
            item {
                Text(
                    text = "ACTIVE BEACONS (${ultrasonicBeacons.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(
                items = ultrasonicBeacons.sortedByDescending { it.lastDetected },
                key = { it.frequency }
            ) { beacon ->
                UltrasonicBeaconCard(beacon = beacon, dateFormat = dateFormat)
            }
        }

        // Anomalies section
        if (ultrasonicAnomalies.isNotEmpty()) {
            item {
                Text(
                    text = "DETECTED ANOMALIES (${ultrasonicAnomalies.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(
                items = ultrasonicAnomalies.sortedByDescending { it.timestamp },
                key = { it.id }
            ) { anomaly ->
                UltrasonicAnomalyCard(anomaly = anomaly, dateFormat = dateFormat)
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
                        text = "About Ultrasonic Detection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Detects inaudible ultrasonic tones (18-22 kHz) used for:\n" +
                            "• Cross-device tracking (SilverPush, Alphonso)\n" +
                            "• TV ad tracking beacons\n" +
                            "• Retail location tracking\n" +
                            "• Audio fingerprinting\n\n" +
                            "Audio is analyzed in real-time and never stored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Empty state
        if (!isScanning && ultrasonicBeacons.isEmpty() && ultrasonicAnomalies.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No ultrasonic activity",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Start scanning to detect audio beacons",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun UltrasonicMetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun UltrasonicBeaconCard(
    beacon: BeaconDetection,
    dateFormat: SimpleDateFormat
) {
    val sourceColor = when {
        beacon.possibleSource.contains("SilverPush") || beacon.possibleSource.contains("Alphonso") ->
            Color(0xFFF44336)
        beacon.possibleSource.contains("Retail") -> Color(0xFFFF9800)
        beacon.possibleSource.contains("Cross-Device") -> Color(0xFFE91E63)
        else -> Color(0xFFFFC107)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = sourceColor.copy(alpha = 0.1f)
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
                        text = "📢",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "${beacon.frequency} Hz",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = beacon.possibleSource,
                            style = MaterialTheme.typography.bodySmall,
                            color = sourceColor
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${String.format("%.1f", beacon.peakAmplitudeDb)} dB",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${beacon.detectionCount}x detected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "First: ${dateFormat.format(Date(beacon.firstDetected))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Last: ${dateFormat.format(Date(beacon.lastDetected))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UltrasonicAnomalyCard(
    anomaly: UltrasonicAnomaly,
    dateFormat: SimpleDateFormat
) {
    val severityColor = when (anomaly.severity) {
        com.flockyou.data.model.ThreatLevel.CRITICAL -> Color(0xFFD32F2F)
        com.flockyou.data.model.ThreatLevel.HIGH -> Color(0xFFF44336)
        com.flockyou.data.model.ThreatLevel.MEDIUM -> Color(0xFFFF9800)
        com.flockyou.data.model.ThreatLevel.LOW -> Color(0xFFFFC107)
        com.flockyou.data.model.ThreatLevel.INFO -> Color(0xFF2196F3)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = severityColor.copy(alpha = 0.1f)
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
                    Column {
                        Text(
                            text = anomaly.type.displayName,
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    anomaly.frequency?.let { freq ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "${freq}Hz",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = severityColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = anomaly.confidence.displayName.split(" ").first(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = severityColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = anomaly.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                            if (!cellStatus.isTrustedCell) {
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
                
                items(
                    items = cellularAnomalies.take(10),
                    key = { it.id }
                ) { anomaly ->
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
                
                items(
                    items = seenCellTowers,
                    key = { it.cellId }
                ) { tower ->
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
private fun GnssStatusContent(
    gnssStatus: GnssEnvironmentStatus?,
    gnssSatellites: List<SatelliteInfo>,
    gnssAnomalies: List<GnssAnomaly>,
    isScanning: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val hasFix = gnssStatus?.hasFix == true
    val spoofingRisk = gnssStatus?.spoofingRiskLevel ?: SpoofingRiskLevel.UNKNOWN

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main GNSS status card
        item {
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
                Column(modifier = Modifier.padding(16.dp)) {
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
                                    modifier = Modifier.size(40.dp),
                                    tint = if (hasFix) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(12.dp)
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
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when {
                                        hasFix -> "${gnssStatus?.satellitesUsedInFix ?: 0} satellites in fix"
                                        isScanning -> "Acquiring satellites..."
                                        else -> "GNSS monitoring inactive"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
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
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            GnssMetricItem(label = "Satellites", value = "${gnssStatus.totalSatellites}")
                            GnssMetricItem(label = "In Fix", value = "${gnssStatus.satellitesUsedInFix}")
                            GnssMetricItem(label = "Avg C/N0", value = "${String.format("%.1f", gnssStatus.averageCn0DbHz)} dB")
                            GnssMetricItem(label = "Raw Data", value = if (gnssStatus.hasRawMeasurements) "Yes" else "No")
                        }
                    }
                }
            }
        }

        // Constellation breakdown
        if (gnssStatus != null && gnssStatus.constellationCounts.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Constellations",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            gnssStatus.constellationCounts.forEach { (constellation, count) ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = constellation.code,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "$count",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Visible satellites
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
                GnssSatelliteListCard(satellite = satellite)
            }
        }

        // GNSS anomalies
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
                GnssAnomalyListCard(anomaly = anomaly, dateFormat = dateFormat)
            }
        }

        // Empty state
        if (!isScanning && gnssSatellites.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No GNSS data",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Start scanning to monitor GNSS satellites",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GnssMetricItem(label: String, value: String) {
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

@Composable
private fun GnssSatelliteListCard(satellite: SatelliteInfo) {
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
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = satellite.constellation.code,
                        style = MaterialTheme.typography.labelLarge,
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
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
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
            }

            // Signal strength
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${String.format("%.1f", satellite.cn0DbHz)}",
                    style = MaterialTheme.typography.titleSmall,
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

@Composable
private fun GnssAnomalyListCard(
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = severityColor.copy(alpha = 0.1f)
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
                    Column {
                        Text(
                            text = anomaly.type.displayName,
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SatelliteStatusContent(
    satelliteState: com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState?,
    satelliteStatus: ScanningService.SubsystemStatus,
    satelliteAnomalies: List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly>,
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
                    containerColor = when {
                        satelliteState?.isConnected == true -> MaterialTheme.colorScheme.primaryContainer
                        satelliteStatus is ScanningService.SubsystemStatus.Active -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SatelliteAlt,
                                contentDescription = null,
                                tint = when {
                                    satelliteState?.isConnected == true -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Satellite Monitor",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when {
                                        satelliteState?.isConnected == true -> "🛰️ Connected to ${satelliteState.connectionType.name.replace("_", " ")}"
                                        isScanning -> "Monitoring for satellite connections"
                                        else -> "Not monitoring"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        // Connection indicator
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        satelliteState?.isConnected == true -> Color(0xFF4CAF50)
                                        isScanning -> Color(0xFFFFC107)
                                        else -> Color.Gray
                                    }
                                )
                        )
                    }
                    
                    // Connection details when connected
                    if (satelliteState?.isConnected == true) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SatelliteInfoItem(
                                label = "Provider",
                                value = satelliteState.provider.name
                            )
                            SatelliteInfoItem(
                                label = "Network",
                                value = satelliteState.networkName ?: "Unknown"
                            )
                            SatelliteInfoItem(
                                label = "Signal",
                                value = satelliteState.signalStrength?.toString() ?: "N/A"
                            )
                        }
                        
                        if (satelliteState.frequency != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SatelliteInfoItem(
                                    label = "Frequency",
                                    value = "${satelliteState.frequency} MHz"
                                )
                                SatelliteInfoItem(
                                    label = "NTN Band",
                                    value = if (satelliteState.isNTNBand) "✓ Valid" else "⚠ Unknown"
                                )
                                SatelliteInfoItem(
                                    label = "Radio Tech",
                                    value = when (satelliteState.radioTechnology) {
                                        1 -> "NB-IoT NTN"
                                        2 -> "NR-NTN"
                                        3 -> "eMTC NTN"
                                        4 -> "Proprietary"
                                        else -> "Unknown"
                                    }
                                )
                            }
                        }
                        
                        // Capabilities
                        val caps = satelliteState.capabilities
                        if (caps.supportsSMS || caps.supportsVoice || caps.supportsData || caps.supportsEmergency) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (caps.supportsSMS) CapabilityChip("SMS")
                                if (caps.supportsVoice) CapabilityChip("Voice")
                                if (caps.supportsData) CapabilityChip("Data")
                                if (caps.supportsEmergency) CapabilityChip("SOS")
                                if (caps.supportsLocationSharing) CapabilityChip("Location")
                            }
                        }
                    }
                }
            }
        }
        
        // Anomalies section
        if (satelliteAnomalies.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Satellite Anomalies (${satelliteAnomalies.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = { ScanningService.clearSatelliteHistory() }) {
                        Text("Clear")
                    }
                }
            }
            
            items(
                items = satelliteAnomalies.take(20),
                key = { "${it.type}-${it.timestamp}" }
            ) { anomaly ->
                SatelliteAnomalyListCard(anomaly = anomaly, dateFormat = dateFormat)
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
                        text = "🛰️ About Satellite Monitoring",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Monitors for satellite connectivity anomalies:\n" +
                            "• T-Mobile Starlink Direct to Cell (3GPP NTN)\n" +
                            "• Skylo NTN (Pixel 9/10 Satellite SOS)\n" +
                            "• Unexpected satellite connections in covered areas\n" +
                            "• Forced handoffs from terrestrial to satellite\n" +
                            "• Suspicious NTN parameters suggesting spoofing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Empty state when no activity
        if (!isScanning && satelliteState?.isConnected != true && satelliteAnomalies.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SatelliteAlt,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No satellite activity",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Start scanning to monitor for satellite connections",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SatelliteInfoItem(
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
private fun CapabilityChip(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatelliteAnomalyListCard(
    anomaly: com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly,
    dateFormat: SimpleDateFormat
) {
    val severityColor = when (anomaly.severity) {
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.CRITICAL -> Color(0xFFD32F2F)
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.HIGH -> Color(0xFFF44336)
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.MEDIUM -> Color(0xFFFF9800)
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.LOW -> Color(0xFFFFC107)
        com.flockyou.monitoring.SatelliteMonitor.AnomalySeverity.INFO -> Color(0xFF2196F3)
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
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(severityColor)
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = severityColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = anomaly.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (expanded && anomaly.recommendations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Recommendations:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
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
                        text = "${tower.lastSignal} dBm",
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

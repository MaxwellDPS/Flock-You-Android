@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.service.UltrasonicDetector
import com.flockyou.service.UltrasonicDetector.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ultrasonic Detection Screen
 *
 * Displays ultrasonic beacon detection for cross-device tracking:
 * - Tracking beacons (SilverPush, Alphonso, etc.)
 * - Advertising beacons from TV/radio
 * - Retail location beacons
 * - Unknown ultrasonic sources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UltrasonicDetectionScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val ultrasonicStatus = uiState.ultrasonicStatus
    val ultrasonicAnomalies = viewModel.getFilteredUltrasonicAnomalies()
    val ultrasonicBeacons = uiState.ultrasonicBeacons
    val isScanning = uiState.isScanning

    val tabs = listOf("Status", "Beacons", "Anomalies")

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
                        Text("Ultrasonic Detection")
                        Text(
                            text = "Audio tracking beacon monitoring",
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
                    // Actions removed - no functions available in viewModel
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab row with swipe support
            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            if (pagerState.currentPage != index && !pagerState.isScrollInProgress) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when (index) {
                                        0 -> Icons.Default.GraphicEq
                                        1 -> Icons.Outlined.VolumeUp
                                        else -> Icons.Default.Warning
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(title)
                                when (index) {
                                    1 -> if (ultrasonicBeacons.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ) { Text(ultrasonicBeacons.size.toString()) }
                                    }
                                    2 -> if (ultrasonicAnomalies.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge { Text(ultrasonicAnomalies.size.toString()) }
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Permission/status banner
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
                            text = "Start scanning to detect ultrasonic beacons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            // Swipeable HorizontalPager for tab content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> UltrasonicStatusContent(
                        status = ultrasonicStatus,
                        isScanning = isScanning
                    )
                    1 -> BeaconsContent(
                        beacons = ultrasonicBeacons,
                        isScanning = isScanning
                    )
                    2 -> UltrasonicAnomaliesContent(
                        anomalies = ultrasonicAnomalies,
                        onClear = { /* No clear function available */ }
                    )
                }
            }
        }
    }
}

@Composable
private fun UltrasonicStatusContent(
    status: UltrasonicStatus?,
    isScanning: Boolean
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main status card
        item {
            UltrasonicMainStatusCard(status = status, isScanning = isScanning)
        }

        // Frequency spectrum visualization (when active)
        if (status?.ultrasonicActivityDetected == true) {
            item {
                FrequencyActivityCard(status = status)
            }
        }

        // Privacy info card
        item {
            UltrasonicPrivacyCard()
        }

        // Info card
        item {
            UltrasonicInfoCard()
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun UltrasonicMainStatusCard(
    status: UltrasonicStatus?,
    isScanning: Boolean
) {
    val threatColor = when (status?.threatLevel) {
        com.flockyou.data.model.ThreatLevel.CRITICAL -> Color(0xFFD32F2F)
        com.flockyou.data.model.ThreatLevel.HIGH -> Color(0xFFF44336)
        com.flockyou.data.model.ThreatLevel.MEDIUM -> Color(0xFFFF9800)
        com.flockyou.data.model.ThreatLevel.LOW -> Color(0xFFFFC107)
        else -> Color(0xFF4CAF50)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status?.ultrasonicActivityDetected == true) {
                threatColor.copy(alpha = 0.1f)
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
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = if (status?.ultrasonicActivityDetected == true) {
                                threatColor
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
                                        status?.isScanning == true -> Color(0xFF4CAF50)
                                        isScanning -> Color(0xFFFFC107)
                                        else -> Color(0xFF9E9E9E)
                                    }
                                )
                        )
                    }

                    Column {
                        Text(
                            text = "Ultrasonic Monitor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                status?.ultrasonicActivityDetected == true -> "Tracking beacons detected"
                                status?.isScanning == true -> "Scanning for beacons..."
                                isScanning -> "Waiting for next scan..."
                                else -> "Not scanning"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Status indicator
                if (status?.ultrasonicActivityDetected == true) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = threatColor
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${status.activeBeaconCount}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            if (status != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    UltrasonicStatItem(
                        icon = Icons.Outlined.VolumeUp,
                        label = "Beacons",
                        value = status.activeBeaconCount.toString(),
                        highlight = status.activeBeaconCount > 0
                    )
                    UltrasonicStatItem(
                        icon = Icons.Default.VolumeOff,
                        label = "Noise Floor",
                        value = "${status.noiseFloorDb.toInt()}dB"
                    )
                    status.peakFrequency?.let { freq ->
                        UltrasonicStatItem(
                            icon = Icons.Default.GraphicEq,
                            label = "Peak Freq",
                            value = "${freq / 1000}kHz",
                            highlight = true
                        )
                    }
                }

                // Last scan time
                if (status.lastScanTime > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    Text(
                        text = "Last scan: ${dateFormat.format(Date(status.lastScanTime))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun UltrasonicStatItem(
    icon: ImageVector,
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (highlight) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (highlight) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FrequencyActivityCard(status: UltrasonicStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Ultrasonic Activity Detected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            status.peakFrequency?.let { freq ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Peak Frequency",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${freq}Hz (${freq / 1000.0}kHz)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            status.peakAmplitudeDb?.let { amp ->
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Peak Amplitude",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${String.format("%.1f", amp)}dB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "This may indicate cross-device tracking beacons from TV, " +
                    "radio, or retail tracking systems.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UltrasonicPrivacyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Privacy Protected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
                Text(
                    text = "Audio is encrypted in memory, analyzed for frequencies only, " +
                        "and never stored or transmitted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UltrasonicInfoCard() {
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
                    text = "About Ultrasonic Tracking",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Ultrasonic beacons are inaudible tones (18-22 kHz) used to:",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            val items = listOf(
                "Track users across devices (TV → Phone)",
                "Attribute advertising effectiveness",
                "Monitor retail location and behavior",
                "Enable cross-device login/authentication",
                "Link online and offline activity"
            )

            items.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Known tracking companies: SilverPush, Alphonso, Signal360",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Detection scans every 30 seconds for 5-second windows to preserve battery.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun BeaconsContent(
    beacons: List<BeaconDetection>,
    isScanning: Boolean
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (beacons.isEmpty()) {
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
                            text = "No Active Beacons",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF4CAF50)
                        )
                        Text(
                            text = if (isScanning) "Monitoring for ultrasonic tracking..."
                                   else "Start scanning to detect beacons",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    text = "Active Beacons (${beacons.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            items(
                items = beacons.sortedByDescending { it.peakAmplitudeDb },
                key = { it.frequency }
            ) { beacon ->
                BeaconCard(beacon = beacon, dateFormat = dateFormat)
            }
        }

        // Known beacon frequencies reference
        item {
            KnownBeaconFrequenciesCard()
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeaconCard(
    beacon: BeaconDetection,
    dateFormat: SimpleDateFormat
) {
    var expanded by remember { mutableStateOf(false) }

    val sourceColor = when {
        beacon.possibleSource.contains("SilverPush") ||
        beacon.possibleSource.contains("Alphonso") -> Color(0xFFD32F2F)
        beacon.possibleSource.contains("Cross-Device") -> Color(0xFFF44336)
        beacon.possibleSource.contains("Retail") -> Color(0xFFFF9800)
        beacon.possibleSource.contains("Advertising") -> Color(0xFFFFC107)
        else -> Color(0xFF2196F3)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = sourceColor.copy(alpha = 0.1f)
        ),
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "📢",
                        style = MaterialTheme.typography.headlineMedium
                    )

                    Column {
                        Text(
                            text = "${beacon.frequency / 1000.0}kHz",
                            style = MaterialTheme.typography.titleMedium,
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

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "${String.format("%.1f", beacon.peakAmplitudeDb)}dB",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${beacon.detectionCount}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    BeaconDetailRow("Frequency", "${beacon.frequency}Hz")
                    BeaconDetailRow("Peak Amplitude", "${String.format("%.1f", beacon.peakAmplitudeDb)}dB")
                    BeaconDetailRow("First Detected", dateFormat.format(Date(beacon.firstDetected)))
                    BeaconDetailRow("Last Detected", dateFormat.format(Date(beacon.lastDetected)))
                    BeaconDetailRow("Detection Count", beacon.detectionCount.toString())
                    BeaconDetailRow("Source", beacon.possibleSource)

                    if (beacon.latitude != null && beacon.longitude != null) {
                        BeaconDetailRow(
                            "Location",
                            "${String.format("%.5f", beacon.latitude)}, ${String.format("%.5f", beacon.longitude)}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BeaconDetailRow(label: String, value: String) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnownBeaconFrequenciesCard() {
    var expanded by remember { mutableStateOf(false) }

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
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Known Beacon Frequencies",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    val frequencies = listOf(
                        "18.0 kHz" to "SilverPush (Primary)",
                        "18.5 kHz" to "Alphonso (TV Tracking)",
                        "19.0 kHz" to "Advertising Beacon",
                        "19.5 kHz" to "Retail Tracking",
                        "20.0 kHz" to "Cross-Device Tracking",
                        "20.5 kHz" to "Location Beacon",
                        "21.0 kHz" to "Premium Ad Tracking"
                    )

                    frequencies.forEach { (freq, source) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = freq,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = source,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UltrasonicAnomaliesContent(
    anomalies: List<UltrasonicAnomaly>,
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
                            text = "No Anomalies Detected",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF4CAF50)
                        )
                        Text(
                            text = "No suspicious ultrasonic activity",
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
                    Text(
                        text = "Anomalies (${anomalies.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onClear) {
                        Text("Clear All")
                    }
                }
            }

            items(
                items = anomalies.sortedByDescending { it.timestamp },
                key = { it.id }
            ) { anomaly ->
                UltrasonicAnomalyCard(anomaly = anomaly, dateFormat = dateFormat)
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
        else -> Color(0xFF2196F3)
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
                    Text(
                        text = anomaly.type.emoji,
                        style = MaterialTheme.typography.titleLarge
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
                        text = anomaly.confidence.displayName.split(" ")[0],
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = anomaly.technicalDetails,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    anomaly.frequency?.let { freq ->
                        Spacer(modifier = Modifier.height(8.dp))
                        BeaconDetailRow("Frequency", "${freq}Hz (${freq / 1000.0}kHz)")
                    }

                    anomaly.amplitudeDb?.let { amp ->
                        BeaconDetailRow("Amplitude", "${String.format("%.1f", amp)}dB")
                    }

                    if (anomaly.contributingFactors.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Contributing Factors:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        anomaly.contributingFactors.forEach { factor ->
                            Text(
                                text = "• $factor",
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


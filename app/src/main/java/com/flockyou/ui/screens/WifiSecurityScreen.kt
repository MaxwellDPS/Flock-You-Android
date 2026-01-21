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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.service.RogueWifiMonitor
import com.flockyou.service.RogueWifiMonitor.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * WiFi Security Screen
 *
 * Dedicated screen for WiFi threat detection:
 * - Evil twin AP detection
 * - Deauth attack detection
 * - Hidden camera WiFi networks
 * - Surveillance van detection
 * - Rogue access points
 * - Following network detection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSecurityScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val wifiStatus = uiState.rogueWifiStatus
    val wifiAnomalies = viewModel.getFilteredRogueWifiAnomalies()
    val suspiciousNetworks = uiState.suspiciousNetworks
    val isScanning = uiState.isScanning

    val tabs = listOf("Status", "Threats", "Networks")

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
                        Text("WiFi Security")
                        Text(
                            text = "Evil twin & rogue AP detection",
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
                                        0 -> Icons.Default.Wifi
                                        1 -> Icons.Default.Warning
                                        else -> Icons.Outlined.WifiFind
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(title)
                                when (index) {
                                    1 -> if (wifiAnomalies.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ) { Text(wifiAnomalies.size.toString()) }
                                    }
                                    2 -> if (suspiciousNetworks.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge { Text(suspiciousNetworks.size.toString()) }
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
                            text = "Start scanning to monitor WiFi security",
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
                    0 -> WifiStatusContent(
                        wifiStatus = wifiStatus,
                        isScanning = isScanning
                    )
                    1 -> WifiThreatsContent(
                        anomalies = wifiAnomalies,
                        onClear = { /* No clear function available */ }
                    )
                    2 -> SuspiciousNetworksContent(
                        networks = suspiciousNetworks
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiStatusContent(
    wifiStatus: WifiEnvironmentStatus?,
    isScanning: Boolean
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main status card
        item {
            WifiEnvironmentCard(wifiStatus = wifiStatus, isScanning = isScanning)
        }

        // Evil twin detection card
        if (wifiStatus != null) {
            item {
                EvilTwinDetectionCard(wifiStatus = wifiStatus)
            }

            // Channel analysis card
            item {
                ChannelAnalysisCard(wifiStatus = wifiStatus)
            }

            // Network stats card
            item {
                NetworkStatsCard(wifiStatus = wifiStatus)
            }
        }

        // Info card
        item {
            WifiSecurityInfoCard()
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WifiEnvironmentCard(
    wifiStatus: WifiEnvironmentStatus?,
    isScanning: Boolean
) {
    val threatColor = when {
        wifiStatus == null -> Color(0xFF9E9E9E)
        wifiStatus.potentialEvilTwins > 0 -> Color(0xFFD32F2F)
        wifiStatus.suspiciousNetworks > 3 -> Color(0xFFFF9800)
        wifiStatus.suspiciousNetworks > 0 -> Color(0xFFFFC107)
        else -> Color(0xFF4CAF50)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = threatColor.copy(alpha = 0.1f)
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
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = threatColor
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isScanning) Color(0xFF4CAF50)
                                    else Color(0xFF9E9E9E)
                                )
                        )
                    }

                    Column {
                        Text(
                            text = "WiFi Environment",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                wifiStatus == null -> "Not scanning"
                                wifiStatus.potentialEvilTwins > 0 -> "Evil twin detected!"
                                wifiStatus.suspiciousNetworks > 0 -> "${wifiStatus.suspiciousNetworks} suspicious"
                                else -> "Environment appears safe"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Threat indicator
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = threatColor
                ) {
                    Text(
                        text = when {
                            wifiStatus == null -> "?"
                            wifiStatus.potentialEvilTwins > 0 -> "!"
                            wifiStatus.suspiciousNetworks > 0 -> wifiStatus.suspiciousNetworks.toString()
                            else -> "OK"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            if (wifiStatus != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Quick stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    WifiStatItem(
                        icon = Icons.Default.Wifi,
                        label = "Networks",
                        value = wifiStatus.totalNetworks.toString()
                    )
                    WifiStatItem(
                        icon = Icons.Default.LockOpen,
                        label = "Open",
                        value = wifiStatus.openNetworks.toString(),
                        highlight = wifiStatus.openNetworks > 5
                    )
                    WifiStatItem(
                        icon = Icons.Default.VisibilityOff,
                        label = "Hidden",
                        value = wifiStatus.hiddenNetworks.toString(),
                        highlight = wifiStatus.hiddenNetworks > 3
                    )
                    WifiStatItem(
                        icon = Icons.Default.PersonSearch,
                        label = "Evil Twins",
                        value = wifiStatus.potentialEvilTwins.toString(),
                        highlight = wifiStatus.potentialEvilTwins > 0
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiStatItem(
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
private fun EvilTwinDetectionCard(wifiStatus: WifiEnvironmentStatus) {
    val hasEvilTwins = wifiStatus.potentialEvilTwins > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasEvilTwins) {
                Color(0xFFD32F2F).copy(alpha = 0.1f)
            } else {
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (hasEvilTwins) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (hasEvilTwins) Color(0xFFD32F2F) else Color(0xFF4CAF50),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = if (hasEvilTwins) "Evil Twin Warning" else "No Evil Twins Detected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (hasEvilTwins) Color(0xFFD32F2F) else Color(0xFF4CAF50)
                )
                Text(
                    text = if (hasEvilTwins) {
                        "${wifiStatus.potentialEvilTwins} SSIDs have multiple APs - may indicate evil twin attack"
                    } else {
                        "No duplicate SSIDs from different sources detected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelAnalysisCard(wifiStatus: WifiEnvironmentStatus) {
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
                        imageVector = Icons.Default.BarChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Channel Congestion",
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

            // Summary
            val maxCongestion = wifiStatus.channelCongestion.maxByOrNull { it.value }
            if (maxCongestion != null) {
                Text(
                    text = "Most congested: Channel ${maxCongestion.key} (${maxCongestion.value} networks)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Show top 5 congested channels
                    val topChannels = wifiStatus.channelCongestion
                        .toList()
                        .sortedByDescending { it.second }
                        .take(5)

                    topChannels.forEach { (channel, count) ->
                        ChannelBar(
                            channel = channel,
                            count = count,
                            maxCount = topChannels.maxOfOrNull { it.second } ?: 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelBar(
    channel: Int,
    count: Int,
    maxCount: Int
) {
    val fraction = count.toFloat() / maxCount

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Ch $channel",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(50.dp)
        )
        LinearProgressIndicator(
            progress = fraction,
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = when {
                count > 10 -> Color(0xFFD32F2F)
                count > 5 -> Color(0xFFFF9800)
                else -> Color(0xFF4CAF50)
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(30.dp),
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun NetworkStatsCard(wifiStatus: WifiEnvironmentStatus) {
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
                    imageVector = Icons.Default.Analytics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Network Analysis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            WifiDetailRow("Total Networks", wifiStatus.totalNetworks.toString())
            WifiDetailRow("Open Networks", wifiStatus.openNetworks.toString())
            WifiDetailRow("Hidden Networks", wifiStatus.hiddenNetworks.toString())
            WifiDetailRow("Potential Evil Twins", wifiStatus.potentialEvilTwins.toString())
            WifiDetailRow("Suspicious Networks", wifiStatus.suspiciousNetworks.toString())
            wifiStatus.strongestSignal?.let { signal ->
                WifiDetailRow("Strongest Signal", "${signal}dBm")
            }

            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            WifiDetailRow("Last Scan", dateFormat.format(Date(wifiStatus.lastScanTime)))
        }
    }
}

@Composable
private fun WifiDetailRow(label: String, value: String) {
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

@Composable
private fun WifiSecurityInfoCard() {
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
                    text = "About WiFi Security",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "This module monitors for WiFi-based threats:",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            val threats = listOf(
                "Evil Twin" to "Same SSID from multiple APs - may be impersonating trusted network",
                "Deauth Attack" to "Rapid disconnections - may force you onto rogue AP",
                "Hidden Cameras" to "IoT cameras broadcasting WiFi (Hikvision, Dahua, etc.)",
                "Surveillance Van" to "Mobile hotspots with surveillance-related SSIDs",
                "Following Network" to "Network seen at multiple locations - may be tracking you",
                "Karma Attack" to "AP responding to any SSID probe requests"
            )

            threats.forEach { (name, desc) ->
                Text(
                    text = "• $name",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "  $desc",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun WifiThreatsContent(
    anomalies: List<WifiAnomaly>,
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
                            text = "No WiFi Threats",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF4CAF50)
                        )
                        Text(
                            text = "No suspicious WiFi activity detected",
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
                        text = "WiFi Threats (${anomalies.size})",
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
                WifiAnomalyCard(anomaly = anomaly, dateFormat = dateFormat)
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiAnomalyCard(
    anomaly: WifiAnomaly,
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
                        anomaly.ssid?.let { ssid ->
                            Text(
                                text = ssid,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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

                    anomaly.bssid?.let { bssid ->
                        Spacer(modifier = Modifier.height(8.dp))
                        WifiDetailRow("BSSID", bssid)
                    }
                    anomaly.rssi?.let { rssi ->
                        WifiDetailRow("Signal", "${rssi}dBm")
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

                    if (anomaly.relatedNetworks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Related Networks:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        anomaly.relatedNetworks.take(5).forEach { network ->
                            Text(
                                text = "• $network",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
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
private fun SuspiciousNetworksContent(networks: List<SuspiciousNetwork>) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (networks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.WifiFind,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Suspicious Networks",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "All detected networks appear normal",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    text = "Suspicious Networks (${networks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            items(
                items = networks,
                key = { it.bssid }
            ) { network ->
                SuspiciousNetworkCard(network = network, dateFormat = dateFormat)
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuspiciousNetworkCard(
    network: SuspiciousNetwork,
    dateFormat: SimpleDateFormat
) {
    val threatColor = when (network.threatLevel) {
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
            containerColor = threatColor.copy(alpha = 0.1f)
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (network.isOpen) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        tint = threatColor,
                        modifier = Modifier.size(24.dp)
                    )

                    Column {
                        Text(
                            text = network.ssid.ifEmpty { "(Hidden)" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = network.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = threatColor
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "${network.rssi}dBm",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${network.seenCount}x",
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

                    WifiDetailRow("BSSID", network.bssid)
                    WifiDetailRow("Frequency", "${network.frequency} MHz")
                    WifiDetailRow("Security", if (network.isOpen) "Open (No encryption)" else "Encrypted")
                    WifiDetailRow("First Seen", dateFormat.format(Date(network.firstSeen)))
                    WifiDetailRow("Last Seen", dateFormat.format(Date(network.lastSeen)))
                    WifiDetailRow("Times Seen", network.seenCount.toString())

                    if (network.latitude != null && network.longitude != null) {
                        WifiDetailRow(
                            "Location",
                            "${String.format("%.5f", network.latitude)}, ${String.format("%.5f", network.longitude)}"
                        )
                    }
                }
            }
        }
    }
}


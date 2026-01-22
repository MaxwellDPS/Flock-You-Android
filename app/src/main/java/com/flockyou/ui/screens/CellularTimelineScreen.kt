package com.flockyou.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flockyou.data.model.ThreatLevel
import com.flockyou.service.CellularMonitor
import com.flockyou.service.ScanningService
import com.flockyou.ui.components.toColor
import java.text.SimpleDateFormat
import java.util.*

/**
 * Timeline view of cellular network events for better visibility into
 * what's happening with cell tower connections
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellularTimelineScreen(
    events: List<CellularMonitor.CellularEvent>,
    seenTowers: List<CellularMonitor.SeenCellTower>,
    cellStatus: CellularMonitor.CellStatus?,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val fullDateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Timeline", "Seen Towers")

    Column(modifier = modifier.fillMaxSize()) {
        // Header with current status
        CellularStatusHeader(cellStatus)

        // Tab Row
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(title)
                            if (index == 0 && events.isNotEmpty()) {
                                Badge { Text("${events.size}") }
                            } else if (index == 1 && seenTowers.isNotEmpty()) {
                                Badge { Text("${seenTowers.size}") }
                            }
                        }
                    }
                )
            }
        }

        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedTab == 0) "Cellular Timeline" else "All Seen Cell Towers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClearHistory) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "Clear",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }

        // Content based on selected tab
        when (selectedTab) {
            0 -> TimelineContent(events, dateFormat, fullDateFormat)
            1 -> SeenTowersContent(seenTowers, dateFormat, fullDateFormat)
        }
    }
}

@Composable
private fun TimelineContent(
    events: List<CellularMonitor.CellularEvent>,
    dateFormat: SimpleDateFormat,
    fullDateFormat: SimpleDateFormat
) {
    if (events.isEmpty()) {
        // Empty state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Timeline,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "No cellular events yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Events will appear here as cell tower changes occur",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = events,
                key = { it.id }
            ) { event ->
                TimelineEventItem(
                    event = event,
                    dateFormat = dateFormat,
                    fullDateFormat = fullDateFormat
                )
            }
        }
    }
}

@Composable
private fun SeenTowersContent(
    seenTowers: List<CellularMonitor.SeenCellTower>,
    dateFormat: SimpleDateFormat,
    fullDateFormat: SimpleDateFormat
) {
    if (seenTowers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.CellTower,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "No cell towers seen yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Cell towers will appear here as your device connects to them",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = seenTowers,
                key = { it.cellId }
            ) { tower ->
                SeenTowerItem(
                    tower = tower,
                    dateFormat = dateFormat,
                    fullDateFormat = fullDateFormat
                )
            }
        }
    }
}

@Composable
private fun SeenTowerItem(
    tower: CellularMonitor.SeenCellTower,
    dateFormat: SimpleDateFormat,
    fullDateFormat: SimpleDateFormat
) {
    val isToday = remember(tower.lastSeen) {
        val today = Calendar.getInstance()
        val towerCal = Calendar.getInstance().apply { timeInMillis = tower.lastSeen }
        today.get(Calendar.DAY_OF_YEAR) == towerCal.get(Calendar.DAY_OF_YEAR) &&
            today.get(Calendar.YEAR) == towerCal.get(Calendar.YEAR)
    }

    val lastSeenString = if (isToday) {
        dateFormat.format(Date(tower.lastSeen))
    } else {
        fullDateFormat.format(Date(tower.lastSeen))
    }

    val firstSeenString = if (isToday) {
        dateFormat.format(Date(tower.firstSeen))
    } else {
        fullDateFormat.format(Date(tower.firstSeen))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (tower.isTrusted) {
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row with cell ID and trust status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CellTower,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (tower.isTrusted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Cell ${tower.cellId}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = tower.operator ?: "Unknown Operator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Trust badge
                if (tower.isTrusted) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF4CAF50)
                            )
                            Text(
                                text = "Trusted",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Network info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Network type
                Column {
                    Text(
                        text = "Network",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = tower.networkType,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Generation
                Column {
                    Text(
                        text = "Generation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (tower.networkGeneration) {
                            "5G" -> Color(0xFF2196F3).copy(alpha = 0.2f)
                            "4G" -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                            "3G" -> Color(0xFFFFC107).copy(alpha = 0.2f)
                            else -> Color(0xFFF44336).copy(alpha = 0.2f)
                        }
                    ) {
                        Text(
                            text = tower.networkGeneration,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (tower.networkGeneration) {
                                "5G" -> Color(0xFF2196F3)
                                "4G" -> Color(0xFF4CAF50)
                                "3G" -> Color(0xFFFFC107)
                                else -> Color(0xFFF44336)
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                // MCC/MNC
                tower.mcc?.let { mcc ->
                    tower.mnc?.let { mnc ->
                        Column {
                            Text(
                                text = "MCC/MNC",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "$mcc-$mnc",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Signal and timing info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Signal range
                Column {
                    Text(
                        text = "Signal Range",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "${tower.minSignal} to ${tower.maxSignal} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Seen count
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Seen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "${tower.seenCount}x",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Timestamps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "First: $firstSeenString",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Last: $lastSeenString",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun CellularStatusHeader(cellStatus: CellularMonitor.CellStatus?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        if (cellStatus != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Signal bars icon
                        SignalBarsIcon(cellStatus.signalBars)
                        
                        Column {
                            Text(
                                text = cellStatus.operator ?: "Unknown Operator",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = cellStatus.networkType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    // Trust indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (cellStatus.isTrustedCell) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = "Trusted",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Trusted",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50)
                            )
                        } else {
                            Icon(
                                Icons.Default.NewReleases,
                                contentDescription = "New",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Learning",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    
                    Text(
                        text = "Cell: ${cellStatus.cellId}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Cellular monitoring not active",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun SignalBarsIcon(bars: Int) {
    val iconVector = when (bars) {
        4 -> Icons.Default.SignalCellular4Bar
        3 -> Icons.Default.SignalCellular4Bar // Using 4 bar, would need custom assets
        2 -> Icons.Default.SignalCellular4Bar
        1 -> Icons.Default.SignalCellular4Bar
        else -> Icons.Default.SignalCellularOff
    }
    
    Icon(
        iconVector,
        contentDescription = "$bars bars",
        modifier = Modifier.size(24.dp),
        tint = when (bars) {
            4 -> Color(0xFF4CAF50)
            3 -> Color(0xFF8BC34A)
            2 -> Color(0xFFFFC107)
            1 -> Color(0xFFFF9800)
            else -> Color(0xFFF44336)
        }
    )
}

@Composable
private fun TimelineEventItem(
    event: CellularMonitor.CellularEvent,
    dateFormat: SimpleDateFormat,
    fullDateFormat: SimpleDateFormat
) {
    val isToday = remember(event.timestamp) {
        val today = Calendar.getInstance()
        val eventCal = Calendar.getInstance().apply { timeInMillis = event.timestamp }
        today.get(Calendar.DAY_OF_YEAR) == eventCal.get(Calendar.DAY_OF_YEAR) &&
            today.get(Calendar.YEAR) == eventCal.get(Calendar.YEAR)
    }
    
    val timeString = if (isToday) {
        dateFormat.format(Date(event.timestamp))
    } else {
        fullDateFormat.format(Date(event.timestamp))
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Timeline indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
            // Dot
            Box(
                modifier = Modifier
                    .size(if (event.isAnomaly) 14.dp else 10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            event.isAnomaly -> event.threatLevel.toColor()
                            event.type == CellularMonitor.CellularEventType.RETURNED_TO_TRUSTED -> Color(0xFF4CAF50)
                            event.type == CellularMonitor.CellularEventType.NEW_CELL_DISCOVERED -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
            )
            
            // Connecting line
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        
        // Event content
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    event.isAnomaly -> event.threatLevel.toColor().copy(alpha = 0.1f)
                    event.type == CellularMonitor.CellularEventType.RETURNED_TO_TRUSTED -> 
                        Color(0xFF4CAF50).copy(alpha = 0.1f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = event.type.emoji,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (event.isAnomaly) FontWeight.Bold else FontWeight.Medium,
                            color = if (event.isAnomaly) event.threatLevel.toColor() 
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Additional details
                if (event.cellId != null || event.networkType != null || event.signalStrength != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        event.cellId?.let { cellId ->
                            DetailChip(
                                icon = Icons.Default.CellTower,
                                text = cellId
                            )
                        }
                        event.networkType?.let { networkType ->
                            DetailChip(
                                icon = Icons.Default.NetworkCell,
                                text = networkType
                            )
                        }
                        event.signalStrength?.let { signal ->
                            DetailChip(
                                icon = Icons.Default.SignalCellularAlt,
                                text = "$signal dBm"
                            )
                        }
                    }
                }
                
                // Threat level badge for anomalies
                if (event.isAnomaly && event.threatLevel != ThreatLevel.INFO) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = event.threatLevel.toColor().copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "⚠️ ${event.threatLevel.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = event.threatLevel.toColor(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

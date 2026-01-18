package com.flockyou.ui.screens

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
import androidx.compose.ui.unit.dp
import com.flockyou.data.model.DetectionPatterns
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.PatternType
import com.flockyou.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionPatternsScreen(
    onNavigateBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("SSID Patterns", "BLE Patterns", "MAC Prefixes", "Raven UUIDs")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Detection Patterns")
                        Text(
                            text = "What we're scanning for",
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
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Summary card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        PatternCountBox(
                            count = DetectionPatterns.ssidPatterns.size,
                            label = "SSIDs"
                        )
                        PatternCountBox(
                            count = DetectionPatterns.bleNamePatterns.size,
                            label = "BLE Names"
                        )
                        PatternCountBox(
                            count = DetectionPatterns.macPrefixes.size,
                            label = "MAC OUIs"
                        )
                        PatternCountBox(
                            count = DetectionPatterns.ravenServiceUuids.size,
                            label = "Raven UUIDs"
                        )
                    }
                }
            }
            
            // Tabs
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            // Content based on selected tab
            when (selectedTab) {
                0 -> SsidPatternsList()
                1 -> BlePatternsList()
                2 -> MacPrefixesList()
                3 -> RavenUuidsList()
            }
        }
    }
}

@Composable
fun PatternCountBox(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SsidPatternsList() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DetectionPatterns.ssidPatterns) { pattern ->
            PatternCard(
                title = pattern.pattern.removePrefix("(?i)^").removeSuffix(".*").removeSuffix("[_-]?"),
                subtitle = pattern.description,
                type = "SSID Regex",
                deviceType = pattern.deviceType,
                threatScore = pattern.threatScore,
                manufacturer = pattern.manufacturer
            )
        }
    }
}

@Composable
fun BlePatternsList() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DetectionPatterns.bleNamePatterns) { pattern ->
            PatternCard(
                title = pattern.pattern.removePrefix("(?i)^").removeSuffix(".*").removeSuffix("[_-]?"),
                subtitle = pattern.description,
                type = "BLE Name Regex",
                deviceType = pattern.deviceType,
                threatScore = pattern.threatScore,
                manufacturer = pattern.manufacturer
            )
        }
    }
}

@Composable
fun MacPrefixesList() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DetectionPatterns.macPrefixes) { prefix ->
            PatternCard(
                title = prefix.prefix,
                subtitle = prefix.description,
                type = "MAC OUI",
                deviceType = prefix.deviceType,
                threatScore = prefix.threatScore,
                manufacturer = prefix.manufacturer
            )
        }
    }
}

@Composable
fun RavenUuidsList() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Raven Acoustic Sensors",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Raven devices (by Flock Safety / SoundThinking) are gunshot detection sensors " +
                            "that continuously record audio. They've been expanded to also detect 'human distress' (screaming). " +
                            "Detection requires matching 2+ of these service UUIDs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        items(DetectionPatterns.ravenServiceUuids) { service ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = service.uuid.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = service.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Data exposed: ${service.dataExposed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "Firmware: ${service.firmwareVersions.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PatternCard(
    title: String,
    subtitle: String,
    type: String,
    deviceType: DeviceType,
    threatScore: Int,
    manufacturer: String?
) {
    val threatColor = when {
        threatScore >= 90 -> ThreatCritical
        threatScore >= 75 -> ThreatHigh
        threatScore >= 50 -> ThreatMedium
        threatScore >= 25 -> ThreatLow
        else -> ThreatInfo
    }
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    manufacturer?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Threat score badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = threatColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "$threatScore%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = threatColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    
                    // Type badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = type,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Detects: ${deviceType.name.replace("_", " ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

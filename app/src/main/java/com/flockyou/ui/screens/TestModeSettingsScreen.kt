package com.flockyou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.model.ThreatLevel
import com.flockyou.testmode.TestModeConfig
import com.flockyou.testmode.TestModeStatus
import com.flockyou.testmode.TestScenario

/**
 * Test Mode Settings Screen.
 *
 * Allows users to enable test mode and run simulated surveillance detection scenarios
 * for demonstration, testing, and training purposes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestModeSettingsScreen(
    viewModel: TestModeSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val config by viewModel.config.collectAsState()
    val status by viewModel.status.collectAsState()
    val scenarios = viewModel.scenarios

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Test Mode") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (config.enabled) {
                        IconButton(onClick = { viewModel.disableTestMode() }) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "Stop Test Mode",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Test Mode Master Toggle
            item {
                TestModeToggle(
                    enabled = config.enabled,
                    onToggle = { enabled ->
                        if (enabled) {
                            viewModel.enableTestMode()
                        } else {
                            viewModel.disableTestMode()
                        }
                    }
                )
            }

            // Warning Banner when test mode is active
            if (config.enabled) {
                item {
                    TestModeWarningBanner()
                }
            }

            // Current Status (if scenario is running)
            if (status.isActive && status.activeScenarioId != null) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    TestModeStatusCard(
                        status = status,
                        onStop = { viewModel.stopScenario() }
                    )
                }
            }

            // Scenario Selection Header
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Test Scenarios",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // All scenarios
            items(scenarios) { scenario ->
                ScenarioCard(
                    scenario = scenario,
                    isActive = status.activeScenarioId == scenario.id,
                    isEnabled = config.enabled,
                    onClick = { viewModel.startScenario(scenario.id) }
                )
            }

            // Advanced Settings Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Advanced Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                TestModeAdvancedSettings(
                    config = config,
                    onConfigChange = { viewModel.updateConfig(it) }
                )
            }

            // Footer spacer
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun TestModeToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Enable Test Mode",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Simulate surveillance devices for testing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun TestModeWarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "TEST MODE ACTIVE - Detections are simulated",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenarioCard(
    scenario: TestScenario,
    isActive: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = isEnabled,
        colors = if (isActive) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        scenario.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Running",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    scenario.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    scenario.protocols.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            ThreatLevelBadge(scenario.threatLevel)
        }
    }
}

@Composable
private fun TestModeAdvancedSettings(
    config: TestModeConfig,
    onConfigChange: (TestModeConfig) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Emission interval slider
            Column {
                Text(
                    "Data Emission Interval",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${config.dataEmissionIntervalMs / 1000} seconds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = config.dataEmissionIntervalMs.toFloat(),
                    onValueChange = {
                        onConfigChange(config.copy(dataEmissionIntervalMs = it.toLong()))
                    },
                    valueRange = 1000f..10000f,
                    steps = 8
                )
            }

            Divider()

            // Signal variation toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Simulate Signal Variation")
                    Text(
                        "Add realistic RSSI fluctuations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.simulateSignalVariation,
                    onCheckedChange = {
                        onConfigChange(config.copy(simulateSignalVariation = it))
                    }
                )
            }

            Divider()

            // Show banner toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show Test Mode Banner")
                    Text(
                        "Display indicator on main screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.showTestModeBanner,
                    onCheckedChange = {
                        onConfigChange(config.copy(showTestModeBanner = it))
                    }
                )
            }
        }
    }
}

@Composable
private fun TestModeStatusCard(
    status: TestModeStatus,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
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
                Text(
                    "Active Scenario",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onStop) {
                    Text("Stop")
                }
            }
            Text(
                status.activeScenarioName ?: "Unknown",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Detections: ${status.detectionCount}",
                    style = MaterialTheme.typography.bodySmall
                )
                status.sessionDurationMs?.let { duration ->
                    Text(
                        "Duration: ${duration / 1000}s",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreatLevelBadge(threatLevel: ThreatLevel) {
    val (color, text) = when (threatLevel) {
        ThreatLevel.CRITICAL -> MaterialTheme.colorScheme.error to "CRITICAL"
        ThreatLevel.HIGH -> Color(0xFFFF6B00) to "HIGH"
        ThreatLevel.MEDIUM -> Color(0xFFFFC107) to "MEDIUM"
        ThreatLevel.LOW -> Color(0xFF4CAF50) to "LOW"
        ThreatLevel.INFO -> MaterialTheme.colorScheme.tertiary to "INFO"
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

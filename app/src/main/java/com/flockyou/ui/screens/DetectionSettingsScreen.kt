package com.flockyou.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetectionSettingsViewModel @Inject constructor(
    private val repository: DetectionSettingsRepository
) : ViewModel() {
    
    val settings: StateFlow<DetectionSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetectionSettings())
    
    fun toggleCellularPattern(pattern: CellularPattern, enabled: Boolean) {
        viewModelScope.launch { repository.toggleCellularPattern(pattern, enabled) }
    }
    
    fun toggleSatellitePattern(pattern: SatellitePattern, enabled: Boolean) {
        viewModelScope.launch { repository.toggleSatellitePattern(pattern, enabled) }
    }
    
    fun toggleBlePattern(pattern: BlePattern, enabled: Boolean) {
        viewModelScope.launch { repository.toggleBlePattern(pattern, enabled) }
    }
    
    fun toggleWifiPattern(pattern: WifiPattern, enabled: Boolean) {
        viewModelScope.launch { repository.toggleWifiPattern(pattern, enabled) }
    }
    
    fun setGlobalEnabled(cellular: Boolean? = null, satellite: Boolean? = null, ble: Boolean? = null, wifi: Boolean? = null) {
        viewModelScope.launch { repository.setGlobalDetectionEnabled(cellular, satellite, ble, wifi) }
    }
    
    fun updateCellularThresholds(thresholds: CellularThresholds) {
        viewModelScope.launch { repository.updateCellularThresholds(thresholds) }
    }
    
    fun updateSatelliteThresholds(thresholds: SatelliteThresholds) {
        viewModelScope.launch { repository.updateSatelliteThresholds(thresholds) }
    }
    
    fun updateBleThresholds(thresholds: BleThresholds) {
        viewModelScope.launch { repository.updateBleThresholds(thresholds) }
    }
    
    fun updateWifiThresholds(thresholds: WifiThresholds) {
        viewModelScope.launch { repository.updateWifiThresholds(thresholds) }
    }
    
    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionSettingsScreen(
    viewModel: DetectionSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Cellular", "Satellite", "BLE", "WiFi")

    var showResetDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Detection Patterns")
                        Text(
                            text = "Configure detection rules and thresholds",
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
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Help")
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Reset")
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
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when (index) {
                                        0 -> Icons.Default.CellTower
                                        1 -> Icons.Default.SatelliteAlt
                                        2 -> Icons.Default.Bluetooth
                                        else -> Icons.Default.Wifi
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(title)
                            }
                        }
                    )
                }
            }
            
            // Tab content
            when (selectedTab) {
                0 -> CellularSettingsContent(
                    settings = settings,
                    onTogglePattern = { pattern, enabled ->
                        viewModel.toggleCellularPattern(pattern, enabled)
                    },
                    onToggleGlobal = { enabled ->
                        viewModel.setGlobalEnabled(cellular = enabled)
                    },
                    onUpdateThresholds = { thresholds ->
                        viewModel.updateCellularThresholds(thresholds)
                    }
                )
                1 -> SatelliteSettingsContent(
                    settings = settings,
                    onTogglePattern = { pattern, enabled ->
                        viewModel.toggleSatellitePattern(pattern, enabled)
                    },
                    onToggleGlobal = { enabled ->
                        viewModel.setGlobalEnabled(satellite = enabled)
                    },
                    onUpdateThresholds = { thresholds ->
                        viewModel.updateSatelliteThresholds(thresholds)
                    }
                )
                2 -> BleSettingsContent(
                    settings = settings,
                    onTogglePattern = { pattern, enabled ->
                        viewModel.toggleBlePattern(pattern, enabled)
                    },
                    onToggleGlobal = { enabled ->
                        viewModel.setGlobalEnabled(ble = enabled)
                    },
                    onUpdateThresholds = { thresholds ->
                        viewModel.updateBleThresholds(thresholds)
                    }
                )
                3 -> WifiSettingsContent(
                    settings = settings,
                    onTogglePattern = { pattern, enabled ->
                        viewModel.toggleWifiPattern(pattern, enabled)
                    },
                    onToggleGlobal = { enabled ->
                        viewModel.setGlobalEnabled(wifi = enabled)
                    },
                    onUpdateThresholds = { thresholds ->
                        viewModel.updateWifiThresholds(thresholds)
                    }
                )
            }
        }
    }
    
    // Reset confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
            title = { Text("Reset to Defaults?") },
            text = { Text("This will reset all detection patterns and thresholds to their default values.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetToDefaults()
                        showResetDialog = false
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Help dialog
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("Detection Tuning Help") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "These settings control which surveillance devices are detected and how sensitive the detection is.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    HelpSection(
                        title = "Pattern Toggles",
                        description = "Enable or disable specific detection categories. Disabling a pattern stops alerts for that type of device."
                    )

                    HelpSection(
                        title = "Threshold Sliders",
                        description = "Adjust detection sensitivity. Lower thresholds = more sensitive (more alerts, possible false positives). Higher thresholds = less sensitive (fewer alerts, may miss weak signals)."
                    )

                    HelpSection(
                        title = "RSSI Values",
                        description = "Signal strength in dBm. -30 = very strong (close), -80 = weak (far). Set min RSSI higher to ignore distant devices."
                    )

                    HelpSection(
                        title = "Tracking Alerts",
                        description = "Duration and count thresholds determine when a device is flagged as \"following\" you across locations."
                    )

                    Text(
                        text = "Tip: If you get too many false positives, increase thresholds. If you're missing detections, decrease them.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

@Composable
private fun HelpSection(
    title: String,
    description: String
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== Cellular Settings ====================

@Composable
private fun CellularSettingsContent(
    settings: DetectionSettings,
    onTogglePattern: (CellularPattern, Boolean) -> Unit,
    onToggleGlobal: (Boolean) -> Unit,
    onUpdateThresholds: (CellularThresholds) -> Unit
) {
    var showThresholds by remember { mutableStateOf(false) }
    
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Global toggle
        item {
            GlobalToggleCard(
                title = "Cellular Detection",
                description = "Monitor for IMSI catchers and cell anomalies",
                icon = Icons.Default.CellTower,
                enabled = settings.enableCellularDetection,
                onToggle = onToggleGlobal,
                enabledCount = settings.enabledCellularPatterns.size,
                totalCount = CellularPattern.values().size
            )
        }
        
        // Thresholds section
        item {
            ThresholdsSectionCard(
                title = "Cellular Thresholds",
                expanded = showThresholds,
                onToggle = { showThresholds = !showThresholds }
            ) {
                CellularThresholdsContent(
                    thresholds = settings.cellularThresholds,
                    onUpdate = onUpdateThresholds
                )
            }
        }
        
        // Pattern toggles
        item {
            Text(
                text = "Detection Patterns",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        items(CellularPattern.values().toList()) { pattern ->
            PatternToggleCard(
                name = pattern.displayName,
                description = pattern.description,
                enabled = pattern in settings.enabledCellularPatterns,
                globalEnabled = settings.enableCellularDetection,
                onToggle = { onTogglePattern(pattern, it) }
            )
        }
        
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun CellularThresholdsContent(
    thresholds: CellularThresholds,
    onUpdate: (CellularThresholds) -> Unit
) {
    var signalSpike by remember(thresholds) { mutableStateOf(thresholds.signalSpikeThreshold.toFloat()) }
    var rapidSwitchStationary by remember(thresholds) { mutableStateOf(thresholds.rapidSwitchCountStationary.toFloat()) }
    var rapidSwitchMoving by remember(thresholds) { mutableStateOf(thresholds.rapidSwitchCountMoving.toFloat()) }
    var trustedThreshold by remember(thresholds) { mutableStateOf(thresholds.trustedCellThreshold.toFloat()) }
    var anomalyInterval by remember(thresholds) { mutableStateOf(thresholds.minAnomalyIntervalMs.toFloat() / 1000f) }
    
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ThresholdSlider(
            label = "Signal Spike Threshold",
            value = signalSpike,
            valueRange = 10f..50f,
            unit = "dBm",
            description = "Minimum signal change to trigger spike alert",
            onValueChange = { signalSpike = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(signalSpikeThreshold = signalSpike.toInt()))
            }
        )
        
        ThresholdSlider(
            label = "Rapid Switch (Stationary)",
            value = rapidSwitchStationary,
            valueRange = 1f..10f,
            unit = "/min",
            steps = 8,
            description = "Max cell switches per minute while stationary",
            onValueChange = { rapidSwitchStationary = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(rapidSwitchCountStationary = rapidSwitchStationary.toInt()))
            }
        )
        
        ThresholdSlider(
            label = "Rapid Switch (Moving)",
            value = rapidSwitchMoving,
            valueRange = 3f..20f,
            unit = "/min",
            description = "Max cell switches per minute while moving",
            onValueChange = { rapidSwitchMoving = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(rapidSwitchCountMoving = rapidSwitchMoving.toInt()))
            }
        )
        
        ThresholdSlider(
            label = "Trusted Cell Threshold",
            value = trustedThreshold,
            valueRange = 2f..20f,
            unit = "times",
            steps = 17,
            description = "Times seen before cell tower is trusted",
            onValueChange = { trustedThreshold = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(trustedCellThreshold = trustedThreshold.toInt()))
            }
        )
        
        ThresholdSlider(
            label = "Anomaly Cooldown",
            value = anomalyInterval,
            valueRange = 10f..300f,
            unit = "sec",
            description = "Minimum time between same anomaly alerts",
            onValueChange = { anomalyInterval = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(minAnomalyIntervalMs = (anomalyInterval * 1000).toLong()))
            }
        )
    }
}

// ==================== Satellite Settings ====================

@Composable
private fun SatelliteSettingsContent(
    settings: DetectionSettings,
    onTogglePattern: (SatellitePattern, Boolean) -> Unit,
    onToggleGlobal: (Boolean) -> Unit,
    onUpdateThresholds: (SatelliteThresholds) -> Unit
) {
    var showThresholds by remember { mutableStateOf(false) }
    
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Global toggle
        item {
            GlobalToggleCard(
                title = "Satellite Detection",
                description = "Monitor for satellite connection anomalies",
                icon = Icons.Default.SatelliteAlt,
                enabled = settings.enableSatelliteDetection,
                onToggle = onToggleGlobal,
                enabledCount = settings.enabledSatellitePatterns.size,
                totalCount = SatellitePattern.values().size
            )
        }
        
        // Thresholds section
        item {
            ThresholdsSectionCard(
                title = "Satellite Thresholds",
                expanded = showThresholds,
                onToggle = { showThresholds = !showThresholds }
            ) {
                SatelliteThresholdsContent(
                    thresholds = settings.satelliteThresholds,
                    onUpdate = onUpdateThresholds
                )
            }
        }
        
        // Pattern toggles
        item {
            Text(
                text = "Detection Patterns",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        items(SatellitePattern.values().toList()) { pattern ->
            PatternToggleCard(
                name = pattern.displayName,
                description = pattern.description,
                enabled = pattern in settings.enabledSatellitePatterns,
                globalEnabled = settings.enableSatelliteDetection,
                onToggle = { onTogglePattern(pattern, it) }
            )
        }
        
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun SatelliteThresholdsContent(
    thresholds: SatelliteThresholds,
    onUpdate: (SatelliteThresholds) -> Unit
) {
    var unexpectedThreshold by remember(thresholds) { mutableStateOf(thresholds.unexpectedSatelliteThresholdMs.toFloat() / 1000f) }
    var rapidHandoff by remember(thresholds) { mutableStateOf(thresholds.rapidHandoffThresholdMs.toFloat() / 1000f) }
    var minTerrestrialSignal by remember(thresholds) { mutableStateOf(thresholds.minSignalForTerrestrial.toFloat()) }
    var rapidSwitchWindow by remember(thresholds) { mutableStateOf(thresholds.rapidSwitchingWindowMs.toFloat() / 1000f) }
    var rapidSwitchCount by remember(thresholds) { mutableStateOf(thresholds.rapidSwitchingCount.toFloat()) }
    
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ThresholdSlider(
            label = "Unexpected Satellite Threshold",
            value = unexpectedThreshold,
            valueRange = 1f..30f,
            unit = "sec",
            description = "Time window to detect unexpected satellite",
            onValueChange = { unexpectedThreshold = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(unexpectedSatelliteThresholdMs = (unexpectedThreshold * 1000).toLong()))
            }
        )
        
        ThresholdSlider(
            label = "Rapid Handoff Threshold",
            value = rapidHandoff,
            valueRange = 0.5f..10f,
            unit = "sec",
            description = "Time for rapid handoff detection",
            onValueChange = { rapidHandoff = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(rapidHandoffThresholdMs = (rapidHandoff * 1000).toLong()))
            }
        )
        
        ThresholdSlider(
            label = "Min Terrestrial Signal",
            value = minTerrestrialSignal,
            valueRange = -120f..-70f,
            unit = "dBm",
            description = "Minimum signal for good terrestrial coverage",
            onValueChange = { minTerrestrialSignal = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(minSignalForTerrestrial = minTerrestrialSignal.toInt()))
            }
        )
        
        ThresholdSlider(
            label = "Rapid Switching Window",
            value = rapidSwitchWindow,
            valueRange = 30f..300f,
            unit = "sec",
            description = "Time window for rapid switching detection",
            onValueChange = { rapidSwitchWindow = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(rapidSwitchingWindowMs = (rapidSwitchWindow * 1000).toLong()))
            }
        )
        
        ThresholdSlider(
            label = "Rapid Switching Count",
            value = rapidSwitchCount,
            valueRange = 2f..10f,
            unit = "times",
            steps = 7,
            description = "Switches in window to trigger alert",
            onValueChange = { rapidSwitchCount = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(rapidSwitchingCount = rapidSwitchCount.toInt()))
            }
        )
    }
}

// ==================== BLE Settings ====================

@Composable
private fun BleSettingsContent(
    settings: DetectionSettings,
    onTogglePattern: (BlePattern, Boolean) -> Unit,
    onToggleGlobal: (Boolean) -> Unit,
    onUpdateThresholds: (BleThresholds) -> Unit
) {
    var showThresholds by remember { mutableStateOf(false) }
    
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Global toggle
        item {
            GlobalToggleCard(
                title = "BLE Detection",
                description = "Scan for surveillance Bluetooth devices",
                icon = Icons.Default.Bluetooth,
                enabled = settings.enableBleDetection,
                onToggle = onToggleGlobal,
                enabledCount = settings.enabledBlePatterns.size,
                totalCount = BlePattern.values().size
            )
        }
        
        // Thresholds section
        item {
            ThresholdsSectionCard(
                title = "BLE Thresholds",
                expanded = showThresholds,
                onToggle = { showThresholds = !showThresholds }
            ) {
                BleThresholdsContent(
                    thresholds = settings.bleThresholds,
                    onUpdate = onUpdateThresholds
                )
            }
        }
        
        // Pattern toggles
        item {
            Text(
                text = "Detection Patterns",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        items(BlePattern.values().toList()) { pattern ->
            PatternToggleCard(
                name = pattern.displayName,
                description = pattern.description,
                enabled = pattern in settings.enabledBlePatterns,
                globalEnabled = settings.enableBleDetection,
                onToggle = { onTogglePattern(pattern, it) }
            )
        }
        
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun BleThresholdsContent(
    thresholds: BleThresholds,
    onUpdate: (BleThresholds) -> Unit
) {
    var minRssi by remember(thresholds) { mutableStateOf(thresholds.minRssiForAlert.toFloat()) }
    var proximityRssi by remember(thresholds) { mutableStateOf(thresholds.proximityAlertRssi.toFloat()) }
    var trackingDuration by remember(thresholds) { mutableStateOf(thresholds.trackingDurationMs.toFloat() / 60000f) }
    var trackingCount by remember(thresholds) { mutableStateOf(thresholds.minSeenCountForTracking.toFloat()) }
    var showRssiHelp by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // RSSI Help Card (expandable)
        RssiHelpCard(
            expanded = showRssiHelp,
            onToggle = { showRssiHelp = !showRssiHelp }
        )

        // Preset buttons
        RssiPresetButtons(
            currentMinRssi = minRssi.toInt(),
            currentProximityRssi = proximityRssi.toInt(),
            onPresetSelected = { preset ->
                when (preset) {
                    RssiPreset.SENSITIVE -> {
                        minRssi = -90f
                        proximityRssi = -60f
                        onUpdate(thresholds.copy(
                            minRssiForAlert = -90,
                            proximityAlertRssi = -60
                        ))
                    }
                    RssiPreset.BALANCED -> {
                        minRssi = -75f
                        proximityRssi = -50f
                        onUpdate(thresholds.copy(
                            minRssiForAlert = -75,
                            proximityAlertRssi = -50
                        ))
                    }
                    RssiPreset.CONSERVATIVE -> {
                        minRssi = -60f
                        proximityRssi = -40f
                        onUpdate(thresholds.copy(
                            minRssiForAlert = -60,
                            proximityAlertRssi = -40
                        ))
                    }
                }
            }
        )

        Divider()

        ThresholdSliderWithRssiContext(
            label = "Minimum RSSI for Alert",
            value = minRssi,
            valueRange = -100f..-50f,
            unit = "dBm",
            description = "Minimum signal strength to trigger alert",
            onValueChange = { minRssi = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(minRssiForAlert = minRssi.toInt()))
            }
        )

        ThresholdSliderWithRssiContext(
            label = "Proximity Alert RSSI",
            value = proximityRssi,
            valueRange = -70f..-30f,
            unit = "dBm",
            description = "Signal strength for close proximity warning",
            onValueChange = { proximityRssi = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(proximityAlertRssi = proximityRssi.toInt()))
            }
        )

        ThresholdSlider(
            label = "Tracking Duration",
            value = trackingDuration,
            valueRange = 1f..30f,
            unit = "min",
            description = "Time before tracking alert is triggered",
            onValueChange = { trackingDuration = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(trackingDurationMs = (trackingDuration * 60000).toLong()))
            }
        )

        ThresholdSlider(
            label = "Tracking Sighting Count",
            value = trackingCount,
            valueRange = 2f..10f,
            unit = "times",
            steps = 7,
            description = "Minimum sightings for tracking alert",
            onValueChange = { trackingCount = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(minSeenCountForTracking = trackingCount.toInt()))
            }
        )
    }
}

// RSSI Preset enum
private enum class RssiPreset {
    SENSITIVE, BALANCED, CONSERVATIVE
}

// RSSI Help Card component
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RssiHelpCard(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        onClick = onToggle
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "What is RSSI?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = "RSSI (Received Signal Strength Indicator) measures signal power in dBm (decibels-milliwatts).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Real-world meaning:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    RssiExampleRow("-30 dBm", "Very close (< 1 meter)", MaterialTheme.colorScheme.error)
                    RssiExampleRow("-50 dBm", "Same room (1-5 meters)", MaterialTheme.colorScheme.tertiary)
                    RssiExampleRow("-70 dBm", "Nearby (5-15 meters)", MaterialTheme.colorScheme.primary)
                    RssiExampleRow("-80 dBm", "Far away (15-30 meters)", MaterialTheme.colorScheme.onSurfaceVariant)
                    RssiExampleRow("-90 dBm", "Barely detectable", MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Lower values (more negative) = weaker signal = farther away",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun RssiExampleRow(
    rssi: String,
    meaning: String,
    color: Color
) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = color.copy(alpha = 0.2f),
            modifier = Modifier.width(70.dp)
        ) {
            Text(
                text = rssi,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = meaning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// Preset buttons component
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RssiPresetButtons(
    currentMinRssi: Int,
    currentProximityRssi: Int,
    onPresetSelected: (RssiPreset) -> Unit
) {
    val selectedPreset = when {
        currentMinRssi <= -85 && currentProximityRssi <= -55 -> RssiPreset.SENSITIVE
        currentMinRssi in -80..-65 && currentProximityRssi in -55..-45 -> RssiPreset.BALANCED
        currentMinRssi >= -65 && currentProximityRssi >= -45 -> RssiPreset.CONSERVATIVE
        else -> null
    }

    Column {
        Text(
            text = "Quick Presets",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedPreset == RssiPreset.SENSITIVE,
                onClick = { onPresetSelected(RssiPreset.SENSITIVE) },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sensitive", style = MaterialTheme.typography.labelMedium)
                        Text("More alerts", style = MaterialTheme.typography.labelSmall)
                    }
                },
                leadingIcon = if (selectedPreset == RssiPreset.SENSITIVE) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = selectedPreset == RssiPreset.BALANCED,
                onClick = { onPresetSelected(RssiPreset.BALANCED) },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Balanced", style = MaterialTheme.typography.labelMedium)
                        Text("Recommended", style = MaterialTheme.typography.labelSmall)
                    }
                },
                leadingIcon = if (selectedPreset == RssiPreset.BALANCED) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = selectedPreset == RssiPreset.CONSERVATIVE,
                onClick = { onPresetSelected(RssiPreset.CONSERVATIVE) },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Conservative", style = MaterialTheme.typography.labelMedium)
                        Text("Fewer alerts", style = MaterialTheme.typography.labelSmall)
                    }
                },
                leadingIcon = if (selectedPreset == RssiPreset.CONSERVATIVE) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// Threshold slider with RSSI context visualization
@Composable
private fun ThresholdSliderWithRssiContext(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    description: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    steps: Int = 0
) {
    val rssiMeaning = getRssiMeaning(value.toInt())

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${if (value == value.toInt().toFloat()) value.toInt() else "%.1f".format(value)} $unit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = rssiMeaning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
        // Visual distance indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Far away",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Very close",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getRssiMeaning(rssi: Int): String {
    return when {
        rssi >= -35 -> "touching"
        rssi >= -50 -> "very close"
        rssi >= -60 -> "same room"
        rssi >= -70 -> "nearby"
        rssi >= -80 -> "moderate distance"
        rssi >= -90 -> "far away"
        else -> "barely detectable"
    }
}

// ==================== WiFi Settings ====================

@Composable
private fun WifiSettingsContent(
    settings: DetectionSettings,
    onTogglePattern: (WifiPattern, Boolean) -> Unit,
    onToggleGlobal: (Boolean) -> Unit,
    onUpdateThresholds: (WifiThresholds) -> Unit
) {
    var showThresholds by remember { mutableStateOf(false) }
    
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Global toggle
        item {
            GlobalToggleCard(
                title = "WiFi Detection",
                description = "Scan for surveillance WiFi networks",
                icon = Icons.Default.Wifi,
                enabled = settings.enableWifiDetection,
                onToggle = onToggleGlobal,
                enabledCount = settings.enabledWifiPatterns.size,
                totalCount = WifiPattern.values().size
            )
        }
        
        // Thresholds section
        item {
            ThresholdsSectionCard(
                title = "WiFi Thresholds",
                expanded = showThresholds,
                onToggle = { showThresholds = !showThresholds }
            ) {
                WifiThresholdsContent(
                    thresholds = settings.wifiThresholds,
                    onUpdate = onUpdateThresholds
                )
            }
        }
        
        // Pattern toggles
        item {
            Text(
                text = "Detection Patterns",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        items(WifiPattern.values().toList()) { pattern ->
            PatternToggleCard(
                name = pattern.displayName,
                description = pattern.description,
                enabled = pattern in settings.enabledWifiPatterns,
                globalEnabled = settings.enableWifiDetection,
                onToggle = { onTogglePattern(pattern, it) }
            )
        }
        
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun WifiThresholdsContent(
    thresholds: WifiThresholds,
    onUpdate: (WifiThresholds) -> Unit
) {
    var minSignal by remember(thresholds) { mutableStateOf(thresholds.minSignalForAlert.toFloat()) }
    var strongSignal by remember(thresholds) { mutableStateOf(thresholds.strongSignalThreshold.toFloat()) }
    var trackingDuration by remember(thresholds) { mutableStateOf(thresholds.trackingDurationMs.toFloat() / 60000f) }
    var trackingCount by remember(thresholds) { mutableStateOf(thresholds.minSeenCountForTracking.toFloat()) }
    
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ThresholdSlider(
            label = "Minimum Signal for Alert",
            value = minSignal,
            valueRange = -90f..-50f,
            unit = "dBm",
            description = "Minimum signal level to trigger alert",
            onValueChange = { minSignal = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(minSignalForAlert = minSignal.toInt()))
            }
        )
        
        ThresholdSlider(
            label = "Strong Signal Threshold",
            value = strongSignal,
            valueRange = -70f..-30f,
            unit = "dBm",
            description = "Signal level for strong signal alert",
            onValueChange = { strongSignal = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(strongSignalThreshold = strongSignal.toInt()))
            }
        )
        
        ThresholdSlider(
            label = "Tracking Duration",
            value = trackingDuration,
            valueRange = 1f..30f,
            unit = "min",
            description = "Time before tracking alert is triggered",
            onValueChange = { trackingDuration = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(trackingDurationMs = (trackingDuration * 60000).toLong()))
            }
        )
        
        ThresholdSlider(
            label = "Tracking Sighting Count",
            value = trackingCount,
            valueRange = 2f..10f,
            unit = "times",
            steps = 7,
            description = "Minimum sightings for tracking alert",
            onValueChange = { trackingCount = it },
            onValueChangeFinished = {
                onUpdate(thresholds.copy(minSeenCountForTracking = trackingCount.toInt()))
            }
        )
    }
}

// ==================== Common Components ====================

@Composable
private fun GlobalToggleCard(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    enabledCount: Int,
    totalCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$enabledCount of $totalCount patterns enabled",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThresholdsSectionCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (expanded) "Tap to collapse" else "Tap to expand",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun PatternToggleCard(
    name: String,
    description: String,
    enabled: Boolean,
    globalEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !globalEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                enabled -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (globalEnabled) MaterialTheme.colorScheme.onSurface 
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled && globalEnabled,
                onCheckedChange = { onToggle(it) },
                enabled = globalEnabled
            )
        }
    }
}

@Composable
private fun ThresholdSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    description: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    steps: Int = 0
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${if (value == value.toInt().toFloat()) value.toInt() else "%.1f".format(value)} $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

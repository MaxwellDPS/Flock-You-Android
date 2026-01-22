package com.flockyou.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flockyou.data.NetworkSettings
import com.flockyou.data.OuiSettings
import com.flockyou.data.PrivacySettings
import com.flockyou.network.OrbotHelper
import com.flockyou.network.TorConnectionStatus
import com.flockyou.service.BootReceiver
import com.flockyou.service.ScanningService
import com.flockyou.ui.components.SectionHeader
import com.flockyou.ui.components.settings.QuickSettingsHero
import com.flockyou.ui.components.settings.ProtectionPresetSelector
import com.flockyou.ui.components.settings.DetectionCategoryCard
import com.flockyou.ui.components.settings.DetectionCategory
import com.flockyou.ui.components.settings.PatternItem
import com.flockyou.data.ProtectionPreset
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToDetectionSettings: () -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToNuke: () -> Unit = {},
    onNavigateToAllDetections: () -> Unit = {},
    onNavigateToAiSettings: () -> Unit = {},
    onNavigateToServiceHealth: () -> Unit = {},
    onNavigateToFlipperSettings: () -> Unit = {},
    onNavigateToTestMode: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: MainViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    // Use IPC-based data from uiState instead of direct companion object access
    val scanStats = uiState.scanStats
    val ouiSettings by viewModel.ouiSettings.collectAsState()
    val isOuiUpdating by viewModel.isOuiUpdating.collectAsState()
    var showScanSettings by remember { mutableStateOf(false) }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(isBatteryOptimizationIgnored(context))
    }
    var autoStartOnBoot by remember {
        mutableStateOf(BootReceiver.isAutoStartOnBoot(context))
    }
    
    // Re-check battery optimization when returning to screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored = isBatteryOptimizationIgnored(context)
                autoStartOnBoot = BootReceiver.isAutoStartOnBoot(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Scan timing settings state - using default values since config is stored in DataStore
    // These are just initial UI state; actual config comes from ScanSettingsRepository
    var wifiInterval by remember { mutableStateOf(35) }
    var bleDuration by remember { mutableStateOf(10) }
    var enableBle by remember { mutableStateOf(true) }
    var enableWifi by remember { mutableStateOf(true) }
    var enableCellular by remember { mutableStateOf(true) }
    var enableSatellite by remember { mutableStateOf(true) }
    var trackSeenDevices by remember { mutableStateOf(true) }

    // Section expansion states
    var detectionExpanded by remember { mutableStateOf(true) }
    var notificationsExpanded by remember { mutableStateOf(false) }
    var privacyExpanded by remember { mutableStateOf(false) }
    var securityExpanded by remember { mutableStateOf(false) }
    var advancedVisible by remember { mutableStateOf(false) }

    // Category expansion states within detection section
    var cellularExpanded by remember { mutableStateOf(false) }
    var satelliteExpanded by remember { mutableStateOf(false) }
    var bleExpanded by remember { mutableStateOf(false) }
    var wifiExpanded by remember { mutableStateOf(false) }

    // Protection preset state
    var currentPreset by remember { mutableStateOf(ProtectionPreset.BALANCED) }
    var showPresetBottomSheet by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. QuickSettingsHero (always visible)
            item {
                QuickSettingsHero(
                    currentPreset = currentPreset,
                    onPresetClick = { showPresetBottomSheet = true },
                    bleEnabled = enableBle,
                    wifiEnabled = enableWifi,
                    cellularEnabled = enableCellular,
                    satelliteEnabled = enableSatellite,
                    onToggleBle = { enabled ->
                        enableBle = enabled
                        updateScanSettings(
                            wifiInterval, bleDuration,
                            enableBle, enableWifi, enableCellular, trackSeenDevices
                        )
                    },
                    onToggleWifi = { enabled ->
                        enableWifi = enabled
                        updateScanSettings(
                            wifiInterval, bleDuration,
                            enableBle, enableWifi, enableCellular, trackSeenDevices
                        )
                    },
                    onToggleCellular = { enabled ->
                        enableCellular = enabled
                        updateScanSettings(
                            wifiInterval, bleDuration,
                            enableBle, enableWifi, enableCellular, trackSeenDevices
                        )
                    },
                    onToggleSatellite = { enabled ->
                        enableSatellite = enabled
                        // TODO: Add satellite to scan settings when supported
                    },
                    activePatternCount = 42, // TODO: Wire to actual pattern count from ViewModel
                    isScanning = uiState.isScanning
                )
            }

            // 2. ProtectionPresetSelector chip row
            item {
                ProtectionPresetSelector(
                    currentPreset = currentPreset,
                    onPresetSelected = { preset ->
                        currentPreset = preset
                        // Apply preset settings
                        when (preset) {
                            ProtectionPreset.ESSENTIAL -> {
                                enableBle = true
                                enableWifi = false
                                enableCellular = true
                                enableSatellite = false
                            }
                            ProtectionPreset.BALANCED -> {
                                enableBle = true
                                enableWifi = true
                                enableCellular = true
                                enableSatellite = false
                            }
                            ProtectionPreset.PARANOID -> {
                                enableBle = true
                                enableWifi = true
                                enableCellular = true
                                enableSatellite = true
                            }
                            ProtectionPreset.CUSTOM -> {
                                // Keep current settings
                            }
                        }
                        updateScanSettings(
                            wifiInterval, bleDuration,
                            enableBle, enableWifi, enableCellular, trackSeenDevices
                        )
                    },
                    showBottomSheet = showPresetBottomSheet,
                    onDismissBottomSheet = { showPresetBottomSheet = false }
                )
            }

            // 3. Detection Configuration Section Header
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { detectionExpanded = !detectionExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionHeader(title = "Detection Configuration")
                    Icon(
                        imageVector = if (detectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (detectionExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Detection Category Cards (when detection section expanded)
            if (detectionExpanded) {
                // Cellular Category Card
                item {
                    DetectionCategoryCard(
                        category = DetectionCategory.CELLULAR,
                        categoryEnabled = enableCellular,
                        enabledPatternCount = 5,
                        totalPatternCount = 8,
                        expanded = cellularExpanded,
                        onCategoryToggle = { enabled ->
                            enableCellular = enabled
                            updateScanSettings(
                                wifiInterval, bleDuration,
                                enableBle, enableWifi, enableCellular, trackSeenDevices
                            )
                        },
                        onExpandClick = { cellularExpanded = !cellularExpanded },
                        patterns = listOf(
                            PatternItem("imsi_catcher", "IMSI Catcher Detection", "Detect rogue base stations", true),
                            PatternItem("cell_anomaly", "Cell Anomaly Detection", "Detect unusual cell behavior", true),
                            PatternItem("downgrade", "Downgrade Attack", "Detect forced 2G downgrades", true)
                        ),
                        onPatternToggle = { _, _ -> /* TODO: Wire to ViewModel */ },
                        thresholdsContent = {
                            Text(
                                text = "Signal threshold and timing settings for cellular detection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onResetDefaults = { /* TODO: Reset cellular patterns */ }
                    )
                }

                // Satellite Category Card
                item {
                    DetectionCategoryCard(
                        category = DetectionCategory.SATELLITE,
                        categoryEnabled = enableSatellite,
                        enabledPatternCount = 3,
                        totalPatternCount = 5,
                        expanded = satelliteExpanded,
                        onCategoryToggle = { enabled ->
                            enableSatellite = enabled
                        },
                        onExpandClick = { satelliteExpanded = !satelliteExpanded },
                        patterns = listOf(
                            PatternItem("ntn_threat", "NTN Threat Detection", "Detect satellite network threats", true),
                            PatternItem("sat_anomaly", "Satellite Anomaly", "Detect unusual satellite activity", true)
                        ),
                        onPatternToggle = { _, _ -> /* TODO: Wire to ViewModel */ },
                        thresholdsContent = {
                            Text(
                                text = "Satellite detection sensitivity settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onResetDefaults = { /* TODO: Reset satellite patterns */ }
                    )
                }

                // BLE Category Card
                item {
                    DetectionCategoryCard(
                        category = DetectionCategory.BLE,
                        categoryEnabled = enableBle,
                        enabledPatternCount = 12,
                        totalPatternCount = 15,
                        expanded = bleExpanded,
                        onCategoryToggle = { enabled ->
                            enableBle = enabled
                            updateScanSettings(
                                wifiInterval, bleDuration,
                                enableBle, enableWifi, enableCellular, trackSeenDevices
                            )
                        },
                        onExpandClick = { bleExpanded = !bleExpanded },
                        patterns = listOf(
                            PatternItem("airtag", "AirTag Detection", "Apple AirTag trackers", true),
                            PatternItem("tile", "Tile Detection", "Tile tracking devices", true),
                            PatternItem("samsung", "SmartTag Detection", "Samsung SmartTag trackers", true),
                            PatternItem("generic_ble", "Generic BLE Trackers", "Unknown BLE trackers", true)
                        ),
                        onPatternToggle = { _, _ -> /* TODO: Wire to ViewModel */ },
                        thresholdsContent = {
                            Column {
                                Text(
                                    text = "BLE Scan Duration: ${bleDuration}s",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = bleDuration.toFloat(),
                                    onValueChange = { bleDuration = it.toInt() },
                                    valueRange = 5f..30f,
                                    steps = 4,
                                    onValueChangeFinished = {
                                        updateScanSettings(
                                            wifiInterval, bleDuration,
                                            enableBle, enableWifi, enableCellular, trackSeenDevices
                                        )
                                    }
                                )
                            }
                        },
                        onResetDefaults = { bleDuration = 10 }
                    )
                }

                // WiFi Category Card
                item {
                    DetectionCategoryCard(
                        category = DetectionCategory.WIFI,
                        categoryEnabled = enableWifi,
                        enabledPatternCount = 8,
                        totalPatternCount = 10,
                        expanded = wifiExpanded,
                        onCategoryToggle = { enabled ->
                            enableWifi = enabled
                            updateScanSettings(
                                wifiInterval, bleDuration,
                                enableBle, enableWifi, enableCellular, trackSeenDevices
                            )
                        },
                        onExpandClick = { wifiExpanded = !wifiExpanded },
                        patterns = listOf(
                            PatternItem("evil_twin", "Evil Twin Detection", "Detect AP impersonation", true),
                            PatternItem("rogue_ap", "Rogue AP Detection", "Detect unauthorized APs", true),
                            PatternItem("deauth", "Deauth Attack", "Detect deauthentication attacks", true),
                            PatternItem("stingray_wifi", "WiFi Stingray", "Detect WiFi surveillance", true)
                        ),
                        onPatternToggle = { _, _ -> /* TODO: Wire to ViewModel */ },
                        thresholdsContent = {
                            Column {
                                Text(
                                    text = "WiFi Scan Interval: ${wifiInterval}s",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Android limits to 4 scans per 2 minutes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = wifiInterval.toFloat(),
                                    onValueChange = { wifiInterval = it.toInt() },
                                    valueRange = 30f..120f,
                                    steps = 8,
                                    onValueChangeFinished = {
                                        updateScanSettings(
                                            wifiInterval, bleDuration,
                                            enableBle, enableWifi, enableCellular, trackSeenDevices
                                        )
                                    }
                                )
                            }
                        },
                        onResetDefaults = { wifiInterval = 35 }
                    )
                }

                // All Detections link
                item {
                    SettingsItem(
                        icon = Icons.Default.Visibility,
                        title = "All Detection Patterns",
                        subtitle = "View all patterns, manage custom rules",
                        onClick = onNavigateToAllDetections
                    )
                }
            }

            // 4. Alerts & Notifications Section (collapsible)
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { notificationsExpanded = !notificationsExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionHeader(title = "Alerts & Notifications")
                    Icon(
                        imageVector = if (notificationsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (notificationsExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (notificationsExpanded) {
                item {
                    SettingsItem(
                        icon = Icons.Default.Notifications,
                        title = "Notifications",
                        subtitle = "Alert sounds, vibration, quiet hours",
                        onClick = onNavigateToNotifications
                    )
                }

                item {
                    AutomationBroadcastSection(viewModel = viewModel)
                }
            }

            // 5. Privacy & Data Section (collapsible)
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { privacyExpanded = !privacyExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionHeader(title = "Privacy & Data")
                    Icon(
                        imageVector = if (privacyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (privacyExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (privacyExpanded) {
                item {
                    NetworkPrivacySection(viewModel = viewModel)
                }

                item {
                    DangerZoneSection(
                        privacySettings = viewModel.privacySettings.collectAsState().value,
                        onNavigateToPrivacy = onNavigateToPrivacy,
                        onNavigateToNuke = onNavigateToNuke
                    )
                }
            }

            // 6. Security Section (collapsible)
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { securityExpanded = !securityExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionHeader(title = "Security")
                    Icon(
                        imageVector = if (securityExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (securityExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (securityExpanded) {
                item {
                    SettingsItem(
                        icon = Icons.Default.Lock,
                        title = "App Lock",
                        subtitle = "PIN and biometric authentication",
                        onClick = onNavigateToSecurity
                    )
                }

                // Battery Optimization Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (batteryOptimizationIgnored)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (batteryOptimizationIgnored)
                                        Icons.Default.BatteryChargingFull
                                    else
                                        Icons.Default.BatterySaver,
                                    contentDescription = null,
                                    tint = if (batteryOptimizationIgnored)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Battery Optimization",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (batteryOptimizationIgnored)
                                            "Unrestricted - scanning will run continuously"
                                        else
                                            "Restricted - Android may stop scanning",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (batteryOptimizationIgnored)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            if (!batteryOptimizationIgnored) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { openBatteryOptimizationSettings(context) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.BatteryAlert, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Disable Battery Optimization")
                                }
                            }
                        }
                    }
                }

                // Auto-start on boot toggle
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (autoStartOnBoot)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-start on Boot",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = if (autoStartOnBoot)
                                        "Scanning starts automatically when device boots"
                                    else
                                        "Manual start required after reboot",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = autoStartOnBoot,
                                onCheckedChange = { enabled ->
                                    autoStartOnBoot = enabled
                                    BootReceiver.setAutoStartOnBoot(context, enabled)
                                }
                            )
                        }
                    }
                }
            }

            // 7. Advanced Toggle + Advanced Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedVisible = !advancedVisible }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = if (advancedVisible)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show Advanced",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (advancedVisible)
                                    "Showing technical settings"
                                else
                                    "Tap to show technical settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = advancedVisible,
                            onCheckedChange = { advancedVisible = it }
                        )
                    }
                }
            }

            // Advanced Section (hidden by default)
            if (advancedVisible) {
                // Advanced Mode toggle
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeveloperMode,
                                contentDescription = null,
                                tint = if (uiState.advancedMode)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Advanced Mode",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = if (uiState.advancedMode)
                                        "Showing all technical details and raw data"
                                    else
                                        "Show additional technical information",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.advancedMode,
                                onCheckedChange = { enabled ->
                                    viewModel.setAdvancedMode(enabled)
                                }
                            )
                        }
                    }
                }

                // Scan Statistics Section
                item {
                    SectionHeader(title = "Scan Statistics")
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            StatRow("BLE Devices Seen", scanStats.bleDevicesSeen.toString())
                            StatRow("WiFi Networks Seen", scanStats.wifiNetworksSeen.toString())
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            StatRow("Total BLE Scans", scanStats.totalBleScans.toString())
                            StatRow("Total WiFi Scans", scanStats.totalWifiScans.toString())
                            StatRow("Successful WiFi Scans", scanStats.successfulWifiScans.toString())
                            StatRow(
                                "Throttled WiFi Scans",
                                scanStats.throttledWifiScans.toString(),
                                valueColor = if (scanStats.throttledWifiScans > 0)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            scanStats.lastBleSuccessTime?.let { time ->
                                StatRow(
                                    "Last BLE Success",
                                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
                                )
                            }
                            scanStats.lastWifiSuccessTime?.let { time ->
                                StatRow(
                                    "Last WiFi Success",
                                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
                                )
                            }
                        }
                    }
                }

                // Service Health Status
                item {
                    SettingsItem(
                        icon = Icons.Default.MonitorHeart,
                        title = "Service Health",
                        subtitle = "View detector status, errors, and restart counts",
                        onClick = onNavigateToServiceHealth
                    )
                }

                // Flipper Zero Integration
                item {
                    SettingsItem(
                        icon = Icons.Default.DevicesOther,
                        title = "Flipper Zero",
                        subtitle = "Connect and install Flock Bridge app",
                        onClick = onNavigateToFlipperSettings
                    )
                }

                // Test Mode
                item {
                    SettingsItem(
                        icon = Icons.Default.Science,
                        title = "Test Mode",
                        subtitle = "Simulate surveillance detections for testing",
                        onClick = onNavigateToTestMode
                    )
                }

                // AI Analysis
                item {
                    SettingsItem(
                        icon = Icons.Default.Psychology,
                        title = "AI-Powered Analysis",
                        subtitle = "On-device LLM for threat insights",
                        onClick = onNavigateToAiSettings
                    )
                }

                // OUI Database Section
                item {
                    OuiDatabaseSection(
                        ouiSettings = ouiSettings,
                        isUpdating = isOuiUpdating,
                        onAutoUpdateToggle = { viewModel.setOuiAutoUpdate(it) },
                        onIntervalChange = { viewModel.setOuiUpdateInterval(it) },
                        onWifiOnlyToggle = { viewModel.setOuiWifiOnly(it) },
                        onManualUpdate = { viewModel.triggerOuiUpdate() }
                    )
                }

                // Service Kill Switch
                item {
                    var showKillConfirmation by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Stop All Scanning",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Completely stop the scanning service",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { showKillConfirmation = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Force Stop Service")
                            }
                        }
                    }

                    if (showKillConfirmation) {
                        AlertDialog(
                            onDismissRequest = { showKillConfirmation = false },
                            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                            title = { Text("Stop Scanning Service?") },
                            text = {
                                Text(
                                    "This will completely stop the scanning service and disable auto-restart. " +
                                    "You will need to manually restart the app to resume scanning.\n\n" +
                                    "Surveillance devices will NOT be detected while the service is stopped."
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showKillConfirmation = false
                                        BootReceiver.setAutoStartOnBoot(context, false)
                                        autoStartOnBoot = false
                                        ScanningService.forceStop(context)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Stop Service")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showKillConfirmation = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }

            // 8. About Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "About")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Flock You",
                    subtitle = "Surveillance detection for the privacy-conscious",
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "Open Source",
                    subtitle = "View source code on GitHub",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MaxwellDPS/Flock-You-Android"))
                        context.startActivity(intent)
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private fun updateScanSettings(
    wifiIntervalSeconds: Int,
    bleDurationSeconds: Int,
    enableBle: Boolean,
    enableWifi: Boolean,
    enableCellular: Boolean,
    trackSeenDevices: Boolean
) {
    ScanningService.updateSettings(
        wifiIntervalSeconds = wifiIntervalSeconds,
        bleDurationSeconds = bleDurationSeconds,
        enableBle = enableBle,
        enableWifi = enableWifi,
        enableCellular = enableCellular,
        trackSeenDevices = trackSeenDevices
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun StatRow(
    label: String, 
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
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
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

@Composable
fun LogEntryCard(error: ScanningService.ScanError) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error.recoverable)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
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
                        color = when (error.subsystem) {
                            "BLE" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            "WiFi" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                            "Location" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = error.subsystem,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = dateFormat.format(Date(error.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatteryOptimizationSettings(context: Context) {
    try {
        // Direct request to disable battery optimization for this app
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fall back to battery optimization list
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e2: Exception) {
            // Fall back to app settings as last resort
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e3: Exception) {
                // Final fallback: open general battery settings
                try {
                    val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e4: Exception) {
                    android.util.Log.e("SettingsScreen", "Failed to open any battery settings", e4)
                }
            }
        }
    }
}

@Composable
fun NetworkPrivacySection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val networkSettings by viewModel.networkSettings.collectAsState()
    val isOrbotInstalled by viewModel.isOrbotInstalled.collectAsState()
    val isOrbotRunning by viewModel.isOrbotRunning.collectAsState()
    val torConnectionStatus by viewModel.torConnectionStatus.collectAsState()
    val isTorTesting by viewModel.isTorTesting.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.VpnLock,
                    contentDescription = null,
                    tint = if (networkSettings.useTorProxy && isOrbotRunning)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Route Traffic via Tor",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when {
                            !isOrbotInstalled -> "Orbot not installed"
                            !isOrbotRunning -> "Orbot not running"
                            networkSettings.useTorProxy -> "All network requests use Tor"
                            else -> "Direct connection"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = networkSettings.useTorProxy,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            viewModel.setUseTorProxy(enabled)
                            viewModel.clearTorStatus()
                        }
                    },
                    enabled = isOrbotInstalled
                )
            }

            if (!isOrbotInstalled) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(OrbotHelper.ORBOT_FDROID_URL)
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Install Orbot")
                }
            } else if (networkSettings.useTorProxy && !isOrbotRunning) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Orbot is not running. Network requests will use direct connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.launchOrbot()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Launch Orbot")
                }
            } else if (networkSettings.useTorProxy && isOrbotRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Connected via Tor (${networkSettings.torProxyHost}:${networkSettings.torProxyPort})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Tor Exit IP Badge
                torConnectionStatus?.let { status ->
                    Spacer(modifier = Modifier.height(12.dp))
                    TorExitBadge(status = status)
                }

                // Test Connection Button
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.testTorConnection() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isTorTesting
                ) {
                    if (isTorTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Connection")
                    }
                }
            }
        }
    }
}

@Composable
private fun TorExitBadge(status: TorConnectionStatus) {
    val backgroundColor = when {
        status.isTor -> MaterialTheme.colorScheme.primaryContainer
        status.isConnected -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when {
        status.isTor -> MaterialTheme.colorScheme.onPrimaryContainer
        status.isConnected -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when {
        status.isTor -> Icons.Default.Shield
        status.isConnected -> Icons.Default.Warning
        else -> Icons.Default.Error
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        status.isTor -> "Tor Connected"
                        status.isConnected -> "Connected (Not Tor)"
                        else -> "Connection Failed"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
            }

            if (status.exitIp != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Exit IP: ${status.exitIp}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = contentColor
                    )
                }

                if (status.country != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${status.country} (${status.countryCode})",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor
                        )
                    }
                }
            }

            if (status.error != null && !status.isTor) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationBroadcastSection(viewModel: MainViewModel) {
    val broadcastSettings by viewModel.broadcastSettings.collectAsState()
    var showDetails by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = if (broadcastSettings.enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automation Broadcasts",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (broadcastSettings.enabled)
                            "Broadcasting to Tasker, Automate, etc."
                        else
                            "Send intents to automation apps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = broadcastSettings.enabled,
                    onCheckedChange = { enabled ->
                        viewModel.setBroadcastEnabled(enabled)
                    }
                )
            }

            if (broadcastSettings.enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Expand/collapse details
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDetails = !showDetails },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Configure broadcast events",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (showDetails) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Event toggles
                    BroadcastToggle(
                        label = "Device Detections",
                        description = "BLE/WiFi surveillance devices",
                        checked = broadcastSettings.broadcastOnDetection,
                        onCheckedChange = { viewModel.setBroadcastOnDetection(it) }
                    )
                    BroadcastToggle(
                        label = "Cellular Anomalies",
                        description = "IMSI catcher detection",
                        checked = broadcastSettings.broadcastOnCellularAnomaly,
                        onCheckedChange = { viewModel.setBroadcastOnCellular(it) }
                    )
                    BroadcastToggle(
                        label = "Satellite Anomalies",
                        description = "NTN/satellite threats",
                        checked = broadcastSettings.broadcastOnSatelliteAnomaly,
                        onCheckedChange = { viewModel.setBroadcastOnSatellite(it) }
                    )
                    BroadcastToggle(
                        label = "WiFi Anomalies",
                        description = "Evil twins, deauth attacks",
                        checked = broadcastSettings.broadcastOnWifiAnomaly,
                        onCheckedChange = { viewModel.setBroadcastOnWifi(it) }
                    )
                    BroadcastToggle(
                        label = "RF Anomalies",
                        description = "Jammers, drones, surveillance",
                        checked = broadcastSettings.broadcastOnRfAnomaly,
                        onCheckedChange = { viewModel.setBroadcastOnRf(it) }
                    )
                    BroadcastToggle(
                        label = "Ultrasonic Beacons",
                        description = "Audio tracking beacons",
                        checked = broadcastSettings.broadcastOnUltrasonic,
                        onCheckedChange = { viewModel.setBroadcastOnUltrasonic(it) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Include location toggle
                    BroadcastToggle(
                        label = "Include Location",
                        description = "Add GPS coordinates to broadcasts",
                        checked = broadcastSettings.includeLocation,
                        onCheckedChange = { viewModel.setBroadcastIncludeLocation(it) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Minimum threat level dropdown
                    Text(
                        text = "Minimum Threat Level",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val levels = listOf("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        levels.forEach { level ->
                            FilterChip(
                                selected = broadcastSettings.minThreatLevel == level,
                                onClick = { viewModel.setBroadcastMinThreatLevel(level) },
                                label = { Text(level, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Intent action info
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Intent Actions:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "com.flockyou.DETECTION\ncom.flockyou.CELLULAR_ANOMALY\ncom.flockyou.SATELLITE_ANOMALY\ncom.flockyou.WIFI_ANOMALY\ncom.flockyou.RF_ANOMALY\ncom.flockyou.ULTRASONIC",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
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
private fun BroadcastToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * Danger Zone Section - Groups all destructive settings visually
 * with warning styling and summary of active dangerous settings
 */
@Composable
fun DangerZoneSection(
    privacySettings: PrivacySettings,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToNuke: () -> Unit
) {
    // Count active dangerous settings for the summary
    val activeDangerousSettings = mutableListOf<String>()
    if (privacySettings.ephemeralModeEnabled) activeDangerousSettings.add("Ephemeral Mode")
    if (privacySettings.autoPurgeOnScreenLock) activeDangerousSettings.add("Auto-Purge on Lock")

    Column(modifier = Modifier.fillMaxWidth()) {
        // Danger Zone Header with warning styling
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "DANGER ZONE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Settings that can permanently delete your data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Active Dangerous Settings Summary Card (if any active)
        if (activeDangerousSettings.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(0.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Active Dangerous Settings:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    activeDangerousSettings.forEach { setting ->
                        Row(
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(8.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = setting,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Privacy & Data Settings Card
        DangerousSettingCard(
            icon = Icons.Default.PrivacyTip,
            title = "Privacy & Data",
            subtitle = "Ephemeral mode, data retention, quick wipe",
            consequenceText = "Can immediately delete all detection history",
            onClick = onNavigateToPrivacy
        )

        Spacer(modifier = Modifier.height(1.dp))

        // Emergency Wipe Settings Card
        DangerousSettingCard(
            icon = Icons.Default.DeleteForever,
            title = "Emergency Wipe",
            subtitle = "Auto-nuke triggers, duress PIN, dead man's switch",
            consequenceText = "Can automatically wipe data based on triggers",
            onClick = onNavigateToNuke,
            isLast = true
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DangerousSettingCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    consequenceText: String,
    onClick: () -> Unit,
    isLast: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ),
        shape = if (isLast) {
            RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
        } else {
            RoundedCornerShape(0.dp)
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
            // Consequence statement
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = consequenceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

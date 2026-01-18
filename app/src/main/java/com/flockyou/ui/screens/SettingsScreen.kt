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
import com.flockyou.data.OuiSettings
import com.flockyou.service.BootReceiver
import com.flockyou.service.ScanningService
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPatterns: () -> Unit,
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToRules: () -> Unit = {},
    onNavigateToDetectionSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: MainViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val scanStats by ScanningService.scanStats.collectAsState()
    val errorLog by ScanningService.errorLog.collectAsState()
    val currentConfig by ScanningService.currentSettings.collectAsState()
    val ouiSettings by viewModel.ouiSettings.collectAsState()
    val isOuiUpdating by viewModel.isOuiUpdating.collectAsState()

    var showLogs by remember { mutableStateOf(false) }
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
    
    // Scan timing settings state
    var wifiInterval by remember { mutableStateOf(currentConfig.wifiScanInterval / 1000) }
    var bleDuration by remember { mutableStateOf(currentConfig.bleScanDuration / 1000) }
    var enableBle by remember { mutableStateOf(currentConfig.enableBle) }
    var enableWifi by remember { mutableStateOf(currentConfig.enableWifi) }
    var enableCellular by remember { mutableStateOf(currentConfig.enableCellular) }
    var trackSeenDevices by remember { mutableStateOf(currentConfig.trackSeenDevices) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Battery Optimization Section
            item {
                SettingsSectionHeader(title = "Battery & Background")
            }
            
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
                                        "✓ Unrestricted - scanning will run continuously"
                                    else
                                        "⚠ Restricted - Android may stop scanning",
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
                            Text(
                                text = "Battery optimization is enabled. Android may stop the scanning service to save battery. Tap below to disable it for this app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    openBatteryOptimizationSettings(context)
                                },
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
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
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
            
            // Scan Configuration Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader(title = "Scan Configuration")
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Tune,
                    title = "Scan Timing",
                    subtitle = "WiFi: ${wifiInterval}s interval, BLE: ${bleDuration}s duration",
                    onClick = { showScanSettings = !showScanSettings },
                    trailing = {
                        Icon(
                            if (showScanSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                )
            }
            
            if (showScanSettings) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // WiFi Scan Interval
                            Text(
                                text = "WiFi Scan Interval: ${wifiInterval.toInt()} seconds",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Android limits to 4 scans per 2 minutes. Minimum 30s recommended.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = wifiInterval.toFloat(),
                                onValueChange = { wifiInterval = it.toLong() },
                                valueRange = 30f..120f,
                                steps = 8,
                                onValueChangeFinished = {
                                    updateScanSettings(
                                        wifiInterval.toInt(), bleDuration.toInt(),
                                        enableBle, enableWifi, enableCellular, trackSeenDevices
                                    )
                                }
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // BLE Scan Duration
                            Text(
                                text = "BLE Scan Duration: ${bleDuration.toInt()} seconds",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "How long to scan for BLE devices each cycle",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = bleDuration.toFloat(),
                                onValueChange = { bleDuration = it.toLong() },
                                valueRange = 5f..30f,
                                steps = 4,
                                onValueChangeFinished = {
                                    updateScanSettings(
                                        wifiInterval.toInt(), bleDuration.toInt(),
                                        enableBle, enableWifi, enableCellular, trackSeenDevices
                                    )
                                }
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Toggle switches
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable BLE Scanning")
                                Switch(
                                    checked = enableBle,
                                    onCheckedChange = { 
                                        enableBle = it
                                        updateScanSettings(
                                            wifiInterval.toInt(), bleDuration.toInt(),
                                            enableBle, enableWifi, enableCellular, trackSeenDevices
                                        )
                                    }
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable WiFi Scanning")
                                Switch(
                                    checked = enableWifi,
                                    onCheckedChange = { 
                                        enableWifi = it
                                        updateScanSettings(
                                            wifiInterval.toInt(), bleDuration.toInt(),
                                            enableBle, enableWifi, enableCellular, trackSeenDevices
                                        )
                                    }
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Cellular Monitoring")
                                    Text(
                                        text = "Detect IMSI catchers & anomalies",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = enableCellular,
                                    onCheckedChange = { 
                                        enableCellular = it
                                        updateScanSettings(
                                            wifiInterval.toInt(), bleDuration.toInt(),
                                            enableBle, enableWifi, enableCellular, trackSeenDevices
                                        )
                                    }
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Track Seen Devices")
                                    Text(
                                        text = "Log non-matching devices",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = trackSeenDevices,
                                    onCheckedChange = { 
                                        trackSeenDevices = it
                                        updateScanSettings(
                                            wifiInterval.toInt(), bleDuration.toInt(),
                                            enableBle, enableWifi, enableCellular, trackSeenDevices
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            // Detection Patterns
            item {
                SettingsItem(
                    icon = Icons.Default.Pattern,
                    title = "Detection Patterns",
                    subtitle = "View SSIDs, BLE names, and MAC prefixes being scanned",
                    onClick = onNavigateToPatterns
                )
            }
            
            // Detection Rules
            item {
                SettingsItem(
                    icon = Icons.Default.Checklist,
                    title = "Detection Rules",
                    subtitle = "Toggle built-in rules, add custom regex patterns",
                    onClick = onNavigateToRules
                )
            }
            
            // Detection Tuning (new)
            item {
                SettingsItem(
                    icon = Icons.Default.Tune,
                    title = "Detection Tuning",
                    subtitle = "Toggle patterns and adjust thresholds for Cell, Satellite, BLE, WiFi",
                    onClick = onNavigateToDetectionSettings
                )
            }
            
            // Notification Settings
            item {
                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = "Notifications",
                    subtitle = "Alert sounds, vibration, quiet hours",
                    onClick = onNavigateToNotifications
                )
            }
            
            // Scan Statistics Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader(title = "Scan Statistics")
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
            
            // Advanced Logs Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader(title = "Advanced")
            }

            // Advanced Mode toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
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

            item {
                SettingsItem(
                    icon = Icons.Default.BugReport,
                    title = "Error Log",
                    subtitle = "${errorLog.size} entries",
                    onClick = { showLogs = !showLogs },
                    trailing = {
                        Icon(
                            if (showLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }
                )
            }
            
            if (showLogs) {
                if (errorLog.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No errors logged",
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
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { ScanningService.clearErrors() }) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear All")
                            }
                        }
                    }
                    
                    items(errorLog.take(20)) { error ->
                        LogEntryCard(error = error)
                    }
                }
            }

            // OUI Database Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                OuiDatabaseSection(
                    ouiSettings = ouiSettings,
                    isUpdating = isOuiUpdating,
                    onAutoUpdateToggle = { viewModel.setOuiAutoUpdate(it) },
                    onIntervalChange = { viewModel.setOuiUpdateInterval(it) },
                    onWifiOnlyToggle = { viewModel.setOuiWifiOnly(it) },
                    onManualUpdate = { viewModel.triggerOuiUpdate() }
                )
            }

            // About Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSectionHeader(title = "About")
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
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ANG13T/flock-you-android"))
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

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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

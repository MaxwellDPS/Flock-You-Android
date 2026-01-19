package com.flockyou.ui.screens

import android.content.Context
import android.hardware.usb.UsbManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.scanner.flipper.FlipperConnectionPreference
import com.flockyou.scanner.flipper.FlipperSettings
import com.flockyou.ui.components.SectionHeader
import com.flockyou.ui.screens.FlipperSettingsViewModel.ConnectionState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlipperSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: FlipperSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isInstalling by viewModel.isInstalling.collectAsState()
    val installProgress by viewModel.installProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flipper Zero") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Connection Status Section
            item {
                SectionHeader(title = "Connection")
            }

            item {
                FlipperConnectionCard(
                    connectionState = connectionState,
                    settings = settings,
                    onToggleEnabled = { scope.launch { viewModel.setFlipperEnabled(it) } },
                    onConnect = { viewModel.connect() },
                    onDisconnect = { viewModel.disconnect() }
                )
            }

            // FAP Installation Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Flock Bridge App")
            }

            item {
                FapInstallationCard(
                    isInstalling = isInstalling,
                    installProgress = installProgress,
                    connectionState = connectionState,
                    onInstall = {
                        scope.launch {
                            viewModel.installFapToFlipper(context)
                        }
                    }
                )
            }

            // Connection Preferences Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Connection Preferences")
            }

            item {
                ConnectionPreferencesCard(
                    settings = settings,
                    onPreferenceChange = { scope.launch { viewModel.setPreferredConnection(it) } },
                    onAutoConnectUsbChange = { scope.launch { viewModel.setAutoConnectUsb(it) } },
                    onAutoConnectBluetoothChange = { scope.launch { viewModel.setAutoConnectBluetooth(it) } }
                )
            }

            // Scan Settings Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Scan Modules")
            }

            item {
                ScanModulesCard(
                    settings = settings,
                    onWifiChange = { scope.launch { viewModel.setEnableWifiScanning(it) } },
                    onSubGhzChange = { scope.launch { viewModel.setEnableSubGhzScanning(it) } },
                    onBleChange = { scope.launch { viewModel.setEnableBleScanning(it) } },
                    onIrChange = { scope.launch { viewModel.setEnableIrScanning(it) } },
                    onNfcChange = { scope.launch { viewModel.setEnableNfcScanning(it) } }
                )
            }

            // WIPS Settings Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "WIPS (WiFi Intrusion Detection)")
            }

            item {
                WipsSettingsCard(
                    settings = settings,
                    onWipsEnabledChange = { scope.launch { viewModel.setWipsEnabled(it) } },
                    onEvilTwinChange = { scope.launch { viewModel.setWipsEvilTwinDetection(it) } },
                    onDeauthChange = { scope.launch { viewModel.setWipsDeauthDetection(it) } },
                    onKarmaChange = { scope.launch { viewModel.setWipsKarmaDetection(it) } },
                    onRogueApChange = { scope.launch { viewModel.setWipsRogueApDetection(it) } }
                )
            }

            // About Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "About")
            }

            item {
                FlipperAboutCard()
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun FlipperConnectionCard(
    connectionState: ConnectionState,
    settings: FlipperSettings,
    onToggleEnabled: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                is ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (connectionState) {
                        is ConnectionState.Connected -> Icons.Default.CheckCircle
                        is ConnectionState.Connecting -> Icons.Default.Sync
                        is ConnectionState.Error -> Icons.Default.Error
                        else -> Icons.Default.DevicesOther
                    },
                    contentDescription = null,
                    tint = when (connectionState) {
                        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                        is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
                        is ConnectionState.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Flipper Zero",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected ->
                                "Connected via ${connectionState.connectionType}"
                            is ConnectionState.Connecting -> "Connecting..."
                            is ConnectionState.Error -> connectionState.message
                            is ConnectionState.Disconnected -> "Not connected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.flipperEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (settings.flipperEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (connectionState) {
                        is ConnectionState.Connected -> {
                            OutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.LinkOff, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Disconnect")
                            }
                        }
                        is ConnectionState.Connecting -> {
                            OutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier.weight(1f),
                                enabled = true
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cancel")
                            }
                        }
                        else -> {
                            Button(
                                onClick = onConnect,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connect")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FapInstallationCard(
    isInstalling: Boolean,
    installProgress: String?,
    connectionState: ConnectionState,
    onInstall: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Flock Bridge FAP",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Multi-spectrum scanner for Flipper Zero",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Feature list
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    FeatureRow(icon = Icons.Default.Wifi, text = "WiFi scanning via ESP32")
                    FeatureRow(icon = Icons.Default.Radio, text = "Sub-GHz RF detection")
                    FeatureRow(icon = Icons.Default.Bluetooth, text = "BLE scanning")
                    FeatureRow(icon = Icons.Default.Sensors, text = "IR/NFC detection")
                    FeatureRow(icon = Icons.Default.Security, text = "WIPS intrusion detection")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (installProgress != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = installProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected && !isInstalling
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Installing...")
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Install to Flipper")
                }
            }

            if (!isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connect your Flipper Zero to install the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ConnectionPreferencesCard(
    settings: FlipperSettings,
    onPreferenceChange: (FlipperConnectionPreference) -> Unit,
    onAutoConnectUsbChange: (Boolean) -> Unit,
    onAutoConnectBluetoothChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Connection Mode",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlipperConnectionPreference.entries.forEach { preference ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPreferenceChange(preference) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.preferredConnection == preference,
                        onClick = { onPreferenceChange(preference) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when (preference) {
                                FlipperConnectionPreference.USB_PREFERRED -> "USB Preferred"
                                FlipperConnectionPreference.BLUETOOTH_PREFERRED -> "Bluetooth Preferred"
                                FlipperConnectionPreference.USB_ONLY -> "USB Only"
                                FlipperConnectionPreference.BLUETOOTH_ONLY -> "Bluetooth Only"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = when (preference) {
                                FlipperConnectionPreference.USB_PREFERRED -> "Try USB first, fall back to Bluetooth"
                                FlipperConnectionPreference.BLUETOOTH_PREFERRED -> "Try Bluetooth first, fall back to USB"
                                FlipperConnectionPreference.USB_ONLY -> "Only connect via USB cable"
                                FlipperConnectionPreference.BLUETOOTH_ONLY -> "Only connect via Bluetooth"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-connect USB",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Connect when Flipper is plugged in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoConnectUsb,
                    onCheckedChange = onAutoConnectUsbChange
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-connect Bluetooth",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Connect to saved Flipper automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoConnectBluetooth,
                    onCheckedChange = onAutoConnectBluetoothChange
                )
            }
        }
    }
}

@Composable
private fun ScanModulesCard(
    settings: FlipperSettings,
    onWifiChange: (Boolean) -> Unit,
    onSubGhzChange: (Boolean) -> Unit,
    onBleChange: (Boolean) -> Unit,
    onIrChange: (Boolean) -> Unit,
    onNfcChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            ScanToggle(
                icon = Icons.Default.Wifi,
                title = "WiFi Scanning",
                subtitle = "Requires ESP32 module",
                checked = settings.enableWifiScanning,
                onCheckedChange = onWifiChange
            )
            ScanToggle(
                icon = Icons.Default.Radio,
                title = "Sub-GHz RF",
                subtitle = "300-928 MHz surveillance detection",
                checked = settings.enableSubGhzScanning,
                onCheckedChange = onSubGhzChange
            )
            ScanToggle(
                icon = Icons.Default.Bluetooth,
                title = "BLE Scanning",
                subtitle = "Bluetooth Low Energy devices",
                checked = settings.enableBleScanning,
                onCheckedChange = onBleChange
            )
            ScanToggle(
                icon = Icons.Default.Sensors,
                title = "Infrared",
                subtitle = "IR transmitter detection",
                checked = settings.enableIrScanning,
                onCheckedChange = onIrChange
            )
            ScanToggle(
                icon = Icons.Default.Nfc,
                title = "NFC",
                subtitle = "NFC/RFID detection",
                checked = settings.enableNfcScanning,
                onCheckedChange = onNfcChange
            )
        }
    }
}

@Composable
private fun ScanToggle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun WipsSettingsCard(
    settings: FlipperSettings,
    onWipsEnabledChange: (Boolean) -> Unit,
    onEvilTwinChange: (Boolean) -> Unit,
    onDeauthChange: (Boolean) -> Unit,
    onKarmaChange: (Boolean) -> Unit,
    onRogueApChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WIPS Enabled",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Wireless Intrusion Prevention System",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.wipsEnabled,
                    onCheckedChange = onWipsEnabledChange
                )
            }

            if (settings.wipsEnabled) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))

                WipsToggle(
                    title = "Evil Twin Detection",
                    subtitle = "Detect fake access points impersonating legitimate ones",
                    checked = settings.wipsEvilTwinDetection,
                    onCheckedChange = onEvilTwinChange
                )
                WipsToggle(
                    title = "Deauth Attack Detection",
                    subtitle = "Detect WiFi deauthentication attacks",
                    checked = settings.wipsDeauthDetection,
                    onCheckedChange = onDeauthChange
                )
                WipsToggle(
                    title = "Karma Attack Detection",
                    subtitle = "Detect rogue APs responding to all probe requests",
                    checked = settings.wipsKarmaDetection,
                    onCheckedChange = onKarmaChange
                )
                WipsToggle(
                    title = "Rogue AP Detection",
                    subtitle = "Detect unauthorized access points",
                    checked = settings.wipsRogueApDetection,
                    onCheckedChange = onRogueApChange
                )
            }
        }
    }
}

@Composable
private fun WipsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun FlipperAboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "About Flipper Integration",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "The Flock Bridge app runs on your Flipper Zero and communicates with " +
                        "this Android app to provide extended multi-spectrum surveillance detection. " +
                        "It supports both USB and Bluetooth connections.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "For WiFi scanning capabilities, an ESP32 module connected to your " +
                        "Flipper's GPIO is required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Version: 1.2",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Protocol: Flock Bridge v1",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

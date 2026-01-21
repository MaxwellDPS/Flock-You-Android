package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flockyou.scanner.flipper.FlipperOnboardingSettings

/**
 * Enhanced scan scheduler card with info tooltips for each scan type.
 * Shows contextual information about what each scan detects.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlipperScanSchedulerCardWithTips(
    scanSchedulerStatus: com.flockyou.scanner.flipper.ScanSchedulerStatus,
    showTips: Boolean = true,
    onTogglePause: () -> Unit = {},
    onTriggerManualScan: (com.flockyou.scanner.flipper.FlipperScanType) -> Unit = {}
) {
    var showScanTypeInfo by remember { mutableStateOf<String?>(null) }
    var showWhatDoesThisDetect by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SCAN SCHEDULER",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                // Info button to show what each scan detects
                if (showTips) {
                    IconButton(
                        onClick = { showWhatDoesThisDetect = !showWhatDoesThisDetect },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (showWhatDoesThisDetect) Icons.Default.ExpandLess else Icons.Default.Info,
                            contentDescription = "What does this detect?",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Active scan loops with info icons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // WiFi scan
                ScanLoopChipWithInfo(
                    label = "WiFi",
                    isActive = scanSchedulerStatus.wifiScanActive,
                    intervalSeconds = scanSchedulerStatus.wifiScanIntervalSeconds,
                    showInfo = showTips,
                    onInfoClick = { showScanTypeInfo = "WiFi" }
                )
                // Sub-GHz scan
                ScanLoopChipWithInfo(
                    label = "Sub-GHz",
                    isActive = scanSchedulerStatus.subGhzScanActive,
                    intervalSeconds = scanSchedulerStatus.subGhzScanIntervalSeconds,
                    showInfo = showTips,
                    onInfoClick = { showScanTypeInfo = "Sub-GHz" }
                )
                // BLE scan
                ScanLoopChipWithInfo(
                    label = "BLE",
                    isActive = scanSchedulerStatus.bleScanActive,
                    intervalSeconds = scanSchedulerStatus.bleScanIntervalSeconds,
                    showInfo = showTips,
                    onInfoClick = { showScanTypeInfo = "BLE" }
                )
                // Heartbeat (no info needed)
                ScanLoopChipWithInfo(
                    label = "Heartbeat",
                    isActive = scanSchedulerStatus.heartbeatActive,
                    intervalSeconds = scanSchedulerStatus.heartbeatIntervalSeconds,
                    showInfo = false,
                    onInfoClick = {}
                )
            }

            // Additional features
            if (scanSchedulerStatus.wipsEnabled || scanSchedulerStatus.nfcScanEnabled || scanSchedulerStatus.irScanEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (scanSchedulerStatus.wipsEnabled) {
                        FeatureChipWithInfo(
                            label = "WIPS",
                            isEnabled = true,
                            showInfo = showTips,
                            onInfoClick = { showScanTypeInfo = "WIPS" }
                        )
                    }
                    if (scanSchedulerStatus.nfcScanEnabled) {
                        FeatureChipWithInfo(
                            label = "NFC",
                            isEnabled = true,
                            showInfo = showTips,
                            onInfoClick = { showScanTypeInfo = "NFC" }
                        )
                    }
                    if (scanSchedulerStatus.irScanEnabled) {
                        FeatureChipWithInfo(
                            label = "IR",
                            isEnabled = true,
                            showInfo = showTips,
                            onInfoClick = { showScanTypeInfo = "IR" }
                        )
                    }
                }
            }

            // Sub-GHz frequency range info
            if (scanSchedulerStatus.subGhzScanActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sub-GHz range: ${scanSchedulerStatus.subGhzFrequencyStart / 1_000_000}-${scanSchedulerStatus.subGhzFrequencyEnd / 1_000_000} MHz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expandable "What does this detect?" section
            AnimatedVisibility(
                visible = showWhatDoesThisDetect,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "What Does Each Scan Detect?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ScanTypeExplanationRow(
                        icon = Icons.Default.Wifi,
                        title = "WiFi",
                        description = "Surveillance WiFi networks, drone controllers, hidden cameras, Flock Safety ALPR cameras"
                    )
                    ScanTypeExplanationRow(
                        icon = Icons.Default.SettingsInputAntenna,
                        title = "Sub-GHz",
                        description = "Wireless bugs, car trackers, RF transmitters, key fob cloners (300-928 MHz)"
                    )
                    ScanTypeExplanationRow(
                        icon = Icons.Default.Bluetooth,
                        title = "BLE",
                        description = "AirTags, Tile trackers, SmartTags, body cameras, Raven gunshot detectors"
                    )
                    ScanTypeExplanationRow(
                        icon = Icons.Default.Nfc,
                        title = "NFC",
                        description = "NFC skimmers, rogue readers, access control exploitation attempts"
                    )
                    ScanTypeExplanationRow(
                        icon = Icons.Default.Security,
                        title = "WIPS",
                        description = "WiFi Intrusion Prevention - deauth attacks, evil twin APs, rogue networks"
                    )
                }
            }
        }
    }

    // Scan type info dialog
    if (showScanTypeInfo != null) {
        ScanTypeInfoDialog(
            scanType = showScanTypeInfo!!,
            onDismiss = { showScanTypeInfo = null }
        )
    }
}

@Composable
private fun ScanLoopChipWithInfo(
    label: String,
    isActive: Boolean,
    intervalSeconds: Int,
    showInfo: Boolean,
    onInfoClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        else
            MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (isActive)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isActive) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${intervalSeconds}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showInfo) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = "Info about $label",
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onInfoClick() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun FeatureChipWithInfo(
    label: String,
    isEnabled: Boolean,
    showInfo: Boolean,
    onInfoClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isEnabled)
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
        else
            MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isEnabled) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (isEnabled)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isEnabled)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showInfo) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = "Info about $label",
                    modifier = Modifier
                        .size(12.dp)
                        .clickable { onInfoClick() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ScanTypeExplanationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScanTypeInfoDialog(
    scanType: String,
    onDismiss: () -> Unit
) {
    data class ScanTypeInfo(
        val title: String,
        val description: String,
        val devicesDetected: List<String>,
        val icon: androidx.compose.ui.graphics.vector.ImageVector
    )

    val info = when (scanType) {
        "WiFi" -> ScanTypeInfo(
            title = "WiFi Scanning",
            description = "Uses the Flipper's WiFi Dev Board to scan for wireless networks and identify surveillance devices by their network signatures.",
            devicesDetected = listOf(
                "Flock Safety ALPR cameras",
                "Police vehicle WiFi routers",
                "Surveillance van hotspots",
                "Hidden cameras with WiFi",
                "Drone controllers",
                "Rogue access points"
            ),
            icon = Icons.Default.Wifi
        )
        "Sub-GHz" -> ScanTypeInfo(
            title = "Sub-GHz RF Scanning",
            description = "Scans the 300-928 MHz frequency range for radio transmitters commonly used by tracking and surveillance devices.",
            devicesDetected = listOf(
                "GPS car trackers",
                "Wireless bugs/listening devices",
                "RF transmitters",
                "Tire pressure sensors (TPMS)",
                "Key fob signals",
                "Garage door openers"
            ),
            icon = Icons.Default.SettingsInputAntenna
        )
        "BLE" -> ScanTypeInfo(
            title = "Bluetooth LE Scanning",
            description = "Scans for Bluetooth Low Energy devices that could be used for tracking or surveillance.",
            devicesDetected = listOf(
                "Apple AirTags",
                "Tile trackers",
                "Samsung SmartTags",
                "Axon body cameras",
                "Raven gunshot detectors",
                "Police radio accessories"
            ),
            icon = Icons.Default.Bluetooth
        )
        "NFC" -> ScanTypeInfo(
            title = "NFC Detection",
            description = "Detects NFC-enabled devices in close proximity that could be used for data theft or tracking.",
            devicesDetected = listOf(
                "NFC skimmers",
                "Rogue card readers",
                "Access control exploits",
                "Payment terminal tampering"
            ),
            icon = Icons.Default.Nfc
        )
        "WIPS" -> ScanTypeInfo(
            title = "WiFi Intrusion Prevention",
            description = "Monitors for active WiFi attacks that could compromise your device or network.",
            devicesDetected = listOf(
                "Deauthentication attacks",
                "Evil twin access points",
                "Karma attacks",
                "WiFi phishing networks",
                "Man-in-the-middle setups"
            ),
            icon = Icons.Default.Security
        )
        "IR" -> ScanTypeInfo(
            title = "Infrared Detection",
            description = "Detects infrared signals that may indicate surveillance or recording equipment.",
            devicesDetected = listOf(
                "Hidden camera IR LEDs",
                "Night vision equipment",
                "IR communication devices"
            ),
            icon = Icons.Default.Visibility
        )
        else -> ScanTypeInfo(
            title = scanType,
            description = "Detection scan for surveillance devices.",
            devicesDetected = emptyList(),
            icon = Icons.Default.Radar
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = info.icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = info.title,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (info.devicesDetected.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Devices Detected:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    info.devicesDetected.forEach { device ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "\u2022",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = device,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

/**
 * Enhanced disconnected card with better empty state UX, helpful tips, and action buttons.
 */
@Composable
fun FlipperDisconnectedCardEnhanced(
    hasEverConnected: Boolean,
    onConnect: () -> Unit,
    onShowSetupWizard: () -> Unit,
    onLearnMore: () -> Unit,
    onTroubleshooting: () -> Unit
) {
    var showTip by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main disconnected state card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Larger illustration with device icon
                Surface(
                    modifier = Modifier.size(100.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "Flipper Zero",
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = if (hasEverConnected) "Flipper Zero Disconnected" else "Connect Your Flipper Zero",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // More helpful description based on user's experience
                Text(
                    text = if (hasEverConnected) {
                        "Your Flipper Zero is not connected. Reconnect to resume extended RF scanning and surveillance detection."
                    } else {
                        "Unlock powerful surveillance detection capabilities by connecting your Flipper Zero. Scan WiFi networks, detect RF transmitters, find Bluetooth trackers, and more."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Feature highlights (only for first-time users)
                if (!hasEverConnected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FlipperFeatureHighlight(
                            icon = Icons.Default.Wifi,
                            label = "WiFi"
                        )
                        FlipperFeatureHighlight(
                            icon = Icons.Default.SettingsInputAntenna,
                            label = "Sub-GHz"
                        )
                        FlipperFeatureHighlight(
                            icon = Icons.Default.Bluetooth,
                            label = "BLE"
                        )
                        FlipperFeatureHighlight(
                            icon = Icons.Default.Nfc,
                            label = "NFC"
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }

                // Primary action button
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasEverConnected) "Reconnect" else "Connect Flipper Zero",
                        fontWeight = FontWeight.Bold
                    )
                }

                // Secondary actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!hasEverConnected) {
                        OutlinedButton(
                            onClick = onShowSetupWizard,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Setup Guide")
                        }
                    }
                    OutlinedButton(
                        onClick = onTroubleshooting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Help,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Troubleshoot")
                    }
                }
            }
        }

        // Quick tip card (dismissable)
        AnimatedVisibility(
            visible = showTip,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Tip: Make sure Flock Bridge is running",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Launch the Flock Bridge app on your Flipper Zero before connecting. For USB connections, it will auto-launch if installed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    IconButton(
                        onClick = { showTip = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss tip",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        // Learn more link
        TextButton(
            onClick = onLearnMore,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Learn more about Flipper Zero integration")
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun FlipperFeatureHighlight(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

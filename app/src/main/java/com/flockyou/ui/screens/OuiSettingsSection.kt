package com.flockyou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flockyou.data.OuiSettings
import com.flockyou.data.OuiUpdateInterval
import com.flockyou.ui.components.SectionHeader
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun OuiDatabaseSection(
    ouiSettings: OuiSettings,
    isUpdating: Boolean,
    onAutoUpdateToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit,
    onManualUpdate: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    var showIntervalDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Section Header
        SectionHeader(title = "OUI Database")

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (ouiSettings.lastUpdateSuccess)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (ouiSettings.lastUpdateSuccess)
                            Icons.Default.CheckCircle
                        else
                            Icons.Default.Error,
                        contentDescription = null,
                        tint = if (ouiSettings.lastUpdateSuccess)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "IEEE OUI Database",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${ouiSettings.totalEntries.formatWithCommas()} manufacturers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (ouiSettings.lastUpdateTimestamp > 0) {
                    Text(
                        text = "Last updated: ${dateFormat.format(Date(ouiSettings.lastUpdateTimestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Never updated - tap below to download",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!ouiSettings.lastUpdateSuccess && ouiSettings.lastUpdateError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Error: ${ouiSettings.lastUpdateError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Manual Update Button
                Button(
                    onClick = onManualUpdate,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Updating...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update Now")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Auto-update toggle
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Autorenew,
                    contentDescription = null,
                    tint = if (ouiSettings.autoUpdateEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automatic Updates",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Keep manufacturer database current",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = ouiSettings.autoUpdateEnabled,
                    onCheckedChange = onAutoUpdateToggle
                )
            }
        }

        // Update interval and WiFi-only settings (only shown when auto-update enabled)
        if (ouiSettings.autoUpdateEnabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // Update interval
            SettingsItem(
                icon = Icons.Default.Schedule,
                title = "Update Interval",
                subtitle = OuiUpdateInterval.entries.find {
                    it.hours == ouiSettings.updateIntervalHours
                }?.displayName ?: "Weekly",
                onClick = { showIntervalDialog = true }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // WiFi-only toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (ouiSettings.useWifiOnly)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "WiFi Only",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Download updates only on WiFi (~3MB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = ouiSettings.useWifiOnly,
                        onCheckedChange = onWifiOnlyToggle
                    )
                }
            }
        }
    }

    // Interval selection dialog
    if (showIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text("Update Interval") },
            text = {
                Column {
                    OuiUpdateInterval.entries.forEach { interval ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = ouiSettings.updateIntervalHours == interval.hours,
                                onClick = {
                                    onIntervalChange(interval.hours)
                                    showIntervalDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(interval.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntervalDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun Int.formatWithCommas(): String {
    return String.format(Locale.US, "%,d", this)
}

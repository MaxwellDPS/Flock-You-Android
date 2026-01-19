package com.flockyou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.data.NotificationSettings
import com.flockyou.data.NotificationSettingsRepository
import com.flockyou.data.VibratePattern
import com.flockyou.data.model.ThreatLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val repository: NotificationSettingsRepository
) : ViewModel() {
    
    val settings = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        NotificationSettings()
    )
    
    fun updateSettings(update: (NotificationSettings) -> NotificationSettings) {
        viewModelScope.launch {
            repository.updateSettings(update)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    var showVibratePatternDialog by remember { mutableStateOf(false) }
    var showQuietHoursDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Master toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.enabled)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Notifications",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (settings.enabled) "Detection alerts are enabled" else "All alerts are disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateSettings { it.copy(enabled = enabled) }
                            }
                        )
                    }
                }
            }
            
            // Alert types section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "ALERT BY THREAT LEVEL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                ThreatLevelToggle(
                    level = ThreatLevel.CRITICAL,
                    enabled = settings.criticalAlertsEnabled,
                    onToggle = { enabled ->
                        viewModel.updateSettings { it.copy(criticalAlertsEnabled = enabled) }
                    },
                    description = "Audio surveillance, StingRay detected"
                )
            }
            
            item {
                ThreatLevelToggle(
                    level = ThreatLevel.HIGH,
                    enabled = settings.highAlertsEnabled,
                    onToggle = { enabled ->
                        viewModel.updateSettings { it.copy(highAlertsEnabled = enabled) }
                    },
                    description = "Confirmed surveillance cameras"
                )
            }
            
            item {
                ThreatLevelToggle(
                    level = ThreatLevel.MEDIUM,
                    enabled = settings.mediumAlertsEnabled,
                    onToggle = { enabled ->
                        viewModel.updateSettings { it.copy(mediumAlertsEnabled = enabled) }
                    },
                    description = "Likely surveillance equipment"
                )
            }
            
            item {
                ThreatLevelToggle(
                    level = ThreatLevel.LOW,
                    enabled = settings.lowAlertsEnabled,
                    onToggle = { enabled ->
                        viewModel.updateSettings { it.copy(lowAlertsEnabled = enabled) }
                    },
                    description = "Possible surveillance"
                )
            }
            
            item {
                ThreatLevelToggle(
                    level = ThreatLevel.INFO,
                    enabled = settings.infoAlertsEnabled,
                    onToggle = { enabled ->
                        viewModel.updateSettings { it.copy(infoAlertsEnabled = enabled) }
                    },
                    description = "Devices of interest"
                )
            }
            
            // Sound & Vibration section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "SOUND & VIBRATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                SettingsToggleRow(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    title = "Sound",
                    subtitle = "Play alert sound",
                    checked = settings.sound,
                    onCheckedChange = { enabled ->
                        viewModel.updateSettings { it.copy(sound = enabled) }
                    }
                )
            }
            
            item {
                SettingsToggleRow(
                    icon = Icons.Default.Vibration,
                    title = "Vibration",
                    subtitle = "Vibrate on detection",
                    checked = settings.vibrate,
                    onCheckedChange = { enabled ->
                        viewModel.updateSettings { it.copy(vibrate = enabled) }
                    }
                )
            }
            
            if (settings.vibrate) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showVibratePatternDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Vibration Pattern",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = settings.vibratePattern.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
            
            // Display section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "DISPLAY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                SettingsToggleRow(
                    icon = Icons.Default.Lock,
                    title = "Show on Lock Screen",
                    subtitle = "Display alerts when phone is locked",
                    checked = settings.showOnLockScreen,
                    onCheckedChange = { enabled ->
                        viewModel.updateSettings { it.copy(showOnLockScreen = enabled) }
                    }
                )
            }
            
            item {
                SettingsToggleRow(
                    icon = Icons.Default.PushPin,
                    title = "Persistent Notification",
                    subtitle = "Keep scanning status in notification bar",
                    checked = settings.persistentNotification,
                    onCheckedChange = { enabled ->
                        viewModel.updateSettings { it.copy(persistentNotification = enabled) }
                    }
                )
            }
            
            // Quiet Hours section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "QUIET HOURS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                SettingsToggleRow(
                    icon = Icons.Default.DoNotDisturb,
                    title = "Quiet Hours",
                    subtitle = if (settings.quietHoursEnabled) 
                        "${formatHour(settings.quietHoursStart)} - ${formatHour(settings.quietHoursEnd)}"
                    else 
                        "Silence non-critical alerts at night",
                    checked = settings.quietHoursEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.updateSettings { it.copy(quietHoursEnabled = enabled) }
                    }
                )
            }
            
            if (settings.quietHoursEnabled) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showQuietHoursDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Quiet Hours Schedule", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "CRITICAL alerts still come through",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // Vibration pattern dialog
    if (showVibratePatternDialog) {
        AlertDialog(
            onDismissRequest = { showVibratePatternDialog = false },
            title = { Text("Vibration Pattern") },
            text = {
                Column {
                    VibratePattern.entries.forEach { pattern ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = settings.vibratePattern == pattern,
                                    onClick = {
                                        viewModel.updateSettings { it.copy(vibratePattern = pattern) }
                                        showVibratePatternDialog = false
                                    }
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.vibratePattern == pattern,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(pattern.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVibratePatternDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Quiet hours dialog
    if (showQuietHoursDialog) {
        QuietHoursDialog(
            startHour = settings.quietHoursStart,
            endHour = settings.quietHoursEnd,
            onDismiss = { showQuietHoursDialog = false },
            onConfirm = { start, end ->
                viewModel.updateSettings { it.copy(quietHoursStart = start, quietHoursEnd = end) }
                showQuietHoursDialog = false
            }
        )
    }
}

@Composable
private fun ThreatLevelToggle(
    level: ThreatLevel,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    description: String
) {
    val color = when (level) {
        ThreatLevel.CRITICAL -> MaterialTheme.colorScheme.error
        ThreatLevel.HIGH -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        ThreatLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary
        ThreatLevel.LOW -> MaterialTheme.colorScheme.primary
        ThreatLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) color.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface
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
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = color.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = level.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = description,
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
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursDialog(
    startHour: Int,
    endHour: Int,
    onDismiss: () -> Unit,
    onConfirm: (start: Int, end: Int) -> Unit
) {
    var selectedStart by remember { mutableStateOf(startHour) }
    var selectedEnd by remember { mutableStateOf(endHour) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quiet Hours") },
        text = {
            Column {
                Text(
                    text = "Only CRITICAL alerts will vibrate/sound during quiet hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Start Time", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(21, 22, 23, 0).forEach { hour ->
                        FilterChip(
                            selected = selectedStart == hour,
                            onClick = { selectedStart = hour },
                            label = { Text(formatHour(hour)) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("End Time", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(6, 7, 8, 9).forEach { hour ->
                        FilterChip(
                            selected = selectedEnd == hour,
                            onClick = { selectedEnd = hour },
                            label = { Text(formatHour(hour)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedStart, selectedEnd) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatHour(hour: Int): String {
    return when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }
}

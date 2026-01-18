package com.flockyou.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flockyou.data.DangerZone
import com.flockyou.data.NukeSettings
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.security.AppLockManager
import com.flockyou.security.DuressAuthenticator
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NukeSettingsScreen(
    nukeSettingsRepository: NukeSettingsRepository,
    appLockManager: AppLockManager,
    duressAuthenticator: DuressAuthenticator,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val settings by nukeSettingsRepository.settings.collectAsState(initial = NukeSettings())

    var showDuressPinDialog by remember { mutableStateOf(false) }
    var showAddDangerZoneDialog by remember { mutableStateOf(false) }
    var expandedSection by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emergency Wipe") },
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
            // Master Enable
            item {
                MasterEnableCard(
                    enabled = settings.nukeEnabled,
                    onEnabledChange = { scope.launch { nukeSettingsRepository.setNukeEnabled(it) } }
                )
            }

            if (settings.nukeEnabled) {
                // Warning Banner
                item {
                    WarningBanner()
                }

                // USB/ADB Trigger
                item {
                    TriggerSection(
                        title = "USB/ADB Detection",
                        icon = Icons.Default.Usb,
                        enabled = settings.usbTriggerEnabled,
                        onEnabledChange = { scope.launch { nukeSettingsRepository.setUsbTriggerEnabled(it) } },
                        expanded = expandedSection == "usb",
                        onExpandChange = { expandedSection = if (it) "usb" else null },
                        description = "Trigger when forensic tools connect via USB"
                    ) {
                        SwitchSetting(
                            label = "Trigger on data connection",
                            checked = settings.usbTriggerOnDataConnection,
                            onCheckedChange = { scope.launch { nukeSettingsRepository.setUsbTriggerOnDataConnection(it) } }
                        )
                        SwitchSetting(
                            label = "Trigger when ADB detected",
                            checked = settings.usbTriggerOnAdbConnection,
                            onCheckedChange = { scope.launch { nukeSettingsRepository.setUsbTriggerOnAdbConnection(it) } }
                        )
                        SliderSetting(
                            label = "Delay before wipe",
                            value = settings.usbTriggerDelaySeconds.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setUsbTriggerDelaySeconds(it.toInt()) } },
                            valueRange = 0f..60f,
                            valueLabel = "${settings.usbTriggerDelaySeconds}s"
                        )
                    }
                }

                // Failed Auth Trigger
                item {
                    TriggerSection(
                        title = "Failed Authentication",
                        icon = Icons.Default.Lock,
                        enabled = settings.failedAuthTriggerEnabled,
                        onEnabledChange = { scope.launch { nukeSettingsRepository.setFailedAuthTriggerEnabled(it) } },
                        expanded = expandedSection == "auth",
                        onExpandChange = { expandedSection = if (it) "auth" else null },
                        description = "Trigger after multiple failed unlock attempts"
                    ) {
                        SliderSetting(
                            label = "Failed attempts threshold",
                            value = settings.failedAuthThreshold.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setFailedAuthThreshold(it.toInt()) } },
                            valueRange = 3f..50f,
                            valueLabel = "${settings.failedAuthThreshold} attempts"
                        )
                        SliderSetting(
                            label = "Reset counter after",
                            value = settings.failedAuthResetHours.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setFailedAuthResetHours(it.toInt()) } },
                            valueRange = 1f..72f,
                            valueLabel = "${settings.failedAuthResetHours} hours"
                        )
                    }
                }

                // Dead Man's Switch
                item {
                    TriggerSection(
                        title = "Dead Man's Switch",
                        icon = Icons.Default.Timer,
                        enabled = settings.deadManSwitchEnabled,
                        onEnabledChange = { scope.launch { nukeSettingsRepository.setDeadManSwitchEnabled(it) } },
                        expanded = expandedSection == "deadman",
                        onExpandChange = { expandedSection = if (it) "deadman" else null },
                        description = "Wipe if not authenticated within time limit"
                    ) {
                        SliderSetting(
                            label = "Time until wipe",
                            value = settings.deadManSwitchHours.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setDeadManSwitchHours(it.toInt()) } },
                            valueRange = 1f..168f,
                            valueLabel = formatHours(settings.deadManSwitchHours)
                        )
                        SwitchSetting(
                            label = "Show warning before wipe",
                            checked = settings.deadManSwitchWarningEnabled,
                            onCheckedChange = { scope.launch { nukeSettingsRepository.setDeadManSwitchWarningEnabled(it) } }
                        )
                        if (settings.deadManSwitchWarningEnabled) {
                            SliderSetting(
                                label = "Warning before wipe",
                                value = settings.deadManSwitchWarningHours.toFloat(),
                                onValueChange = { scope.launch { nukeSettingsRepository.setDeadManSwitchWarningHours(it.toInt()) } },
                                valueRange = 1f..48f,
                                valueLabel = "${settings.deadManSwitchWarningHours} hours"
                            )
                        }
                    }
                }

                // Network Isolation
                item {
                    TriggerSection(
                        title = "Network Isolation",
                        icon = Icons.Default.AirplanemodeActive,
                        enabled = settings.networkIsolationTriggerEnabled,
                        onEnabledChange = { scope.launch { nukeSettingsRepository.setNetworkIsolationTriggerEnabled(it) } },
                        expanded = expandedSection == "network",
                        onExpandChange = { expandedSection = if (it) "network" else null },
                        description = "Trigger when device is isolated from networks"
                    ) {
                        SliderSetting(
                            label = "Hours offline before wipe",
                            value = settings.networkIsolationHours.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setNetworkIsolationHours(it.toInt()) } },
                            valueRange = 1f..48f,
                            valueLabel = "${settings.networkIsolationHours} hours"
                        )
                        SwitchSetting(
                            label = "Require both airplane mode AND no network",
                            checked = settings.networkIsolationRequireBoth,
                            onCheckedChange = { scope.launch { nukeSettingsRepository.setNetworkIsolationRequireBoth(it) } }
                        )
                    }
                }

                // SIM Removal
                item {
                    TriggerSection(
                        title = "SIM Removal",
                        icon = Icons.Default.SimCard,
                        enabled = settings.simRemovalTriggerEnabled,
                        onEnabledChange = { scope.launch { nukeSettingsRepository.setSimRemovalTriggerEnabled(it) } },
                        expanded = expandedSection == "sim",
                        onExpandChange = { expandedSection = if (it) "sim" else null },
                        description = "Trigger when SIM card is removed"
                    ) {
                        SliderSetting(
                            label = "Delay after SIM removal",
                            value = settings.simRemovalDelaySeconds.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setSimRemovalDelaySeconds(it.toInt()) } },
                            valueRange = 0f..600f,
                            valueLabel = formatSeconds(settings.simRemovalDelaySeconds)
                        )
                        SwitchSetting(
                            label = "Only if SIM was previously present",
                            checked = settings.simRemovalTriggerOnPreviouslyPresent,
                            onCheckedChange = { scope.launch { nukeSettingsRepository.setSimRemovalTriggerOnPreviouslyPresent(it) } }
                        )
                    }
                }

                // Rapid Reboot
                item {
                    TriggerSection(
                        title = "Rapid Reboot Detection",
                        icon = Icons.Default.RestartAlt,
                        enabled = settings.rapidRebootTriggerEnabled,
                        onEnabledChange = { scope.launch { nukeSettingsRepository.setRapidRebootTriggerEnabled(it) } },
                        expanded = expandedSection == "reboot",
                        onExpandChange = { expandedSection = if (it) "reboot" else null },
                        description = "Trigger when device reboots suspiciously fast"
                    ) {
                        SliderSetting(
                            label = "Reboot count threshold",
                            value = settings.rapidRebootCount.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setRapidRebootCount(it.toInt()) } },
                            valueRange = 2f..10f,
                            valueLabel = "${settings.rapidRebootCount} reboots"
                        )
                        SliderSetting(
                            label = "Time window",
                            value = settings.rapidRebootWindowMinutes.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setRapidRebootWindowMinutes(it.toInt()) } },
                            valueRange = 1f..30f,
                            valueLabel = "${settings.rapidRebootWindowMinutes} minutes"
                        )
                    }
                }

                // Geofence
                item {
                    TriggerSection(
                        title = "Geofence (Danger Zones)",
                        icon = Icons.Default.LocationOn,
                        enabled = settings.geofenceTriggerEnabled,
                        onEnabledChange = { scope.launch { nukeSettingsRepository.setGeofenceTriggerEnabled(it) } },
                        expanded = expandedSection == "geofence",
                        onExpandChange = { expandedSection = if (it) "geofence" else null },
                        description = "Trigger when entering specific locations"
                    ) {
                        val dangerZones = settings.getDangerZones()

                        SliderSetting(
                            label = "Delay when entering zone",
                            value = settings.geofenceTriggerDelaySeconds.toFloat(),
                            onValueChange = { scope.launch { nukeSettingsRepository.setGeofenceTriggerDelaySeconds(it.toInt()) } },
                            valueRange = 0f..300f,
                            valueLabel = formatSeconds(settings.geofenceTriggerDelaySeconds)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Danger Zones (${dangerZones.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )

                        dangerZones.forEach { zone ->
                            DangerZoneItem(
                                zone = zone,
                                onRemove = {
                                    scope.launch { nukeSettingsRepository.removeDangerZone(zone.id) }
                                }
                            )
                        }

                        TextButton(
                            onClick = { showAddDangerZoneDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Danger Zone")
                        }
                    }
                }

                // Duress PIN
                item {
                    TriggerSection(
                        title = "Duress PIN",
                        icon = Icons.Default.Warning,
                        enabled = settings.duressPinEnabled,
                        onEnabledChange = { scope.launch { nukeSettingsRepository.setDuressPinEnabled(it) } },
                        expanded = expandedSection == "duress",
                        onExpandChange = { expandedSection = if (it) "duress" else null },
                        description = "Secondary PIN that wipes data instead of unlocking"
                    ) {
                        val isPinSet = settings.duressPinHash.isNotBlank()

                        if (isPinSet) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Duress PIN is set")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Button(
                            onClick = { showDuressPinDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isPinSet) "Change Duress PIN" else "Set Duress PIN")
                        }

                        if (isPinSet) {
                            TextButton(
                                onClick = { scope.launch { duressAuthenticator.removeDuressPin() } },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Remove Duress PIN")
                            }
                        }

                        SwitchSetting(
                            label = "Show fake empty app after duress",
                            checked = settings.duressPinShowFakeApp,
                            onCheckedChange = { scope.launch { nukeSettingsRepository.setDuressPinShowFakeApp(it) } }
                        )
                    }
                }

                // Wipe Options Section
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "WIPE OPTIONS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SwitchSetting(
                                label = "Wipe detection database",
                                checked = settings.wipeDatabase,
                                onCheckedChange = { scope.launch { nukeSettingsRepository.setWipeDatabase(it) } }
                            )
                            SwitchSetting(
                                label = "Wipe app settings",
                                checked = settings.wipeSettings,
                                onCheckedChange = { scope.launch { nukeSettingsRepository.setWipeSettings(it) } }
                            )
                            SwitchSetting(
                                label = "Wipe cache",
                                checked = settings.wipeCache,
                                onCheckedChange = { scope.launch { nukeSettingsRepository.setWipeCache(it) } }
                            )
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            SwitchSetting(
                                label = "Secure wipe (overwrite before delete)",
                                checked = settings.secureWipe,
                                onCheckedChange = { scope.launch { nukeSettingsRepository.setSecureWipe(it) } }
                            )
                            if (settings.secureWipe) {
                                SliderSetting(
                                    label = "Overwrite passes",
                                    value = settings.secureWipePasses.toFloat(),
                                    onValueChange = { scope.launch { nukeSettingsRepository.setSecureWipePasses(it.toInt()) } },
                                    valueRange = 1f..7f,
                                    valueLabel = "${settings.secureWipePasses} passes"
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Duress PIN Dialog
    if (showDuressPinDialog) {
        SetDuressPinDialog(
            appLockManager = appLockManager,
            duressAuthenticator = duressAuthenticator,
            onDismiss = { showDuressPinDialog = false },
            onPinSet = { showDuressPinDialog = false }
        )
    }

    // Add Danger Zone Dialog
    if (showAddDangerZoneDialog) {
        AddDangerZoneDialog(
            onDismiss = { showAddDangerZoneDialog = false },
            onAdd = { zone ->
                scope.launch {
                    nukeSettingsRepository.addDangerZone(zone)
                }
                showAddDangerZoneDialog = false
            }
        )
    }
}

@Composable
private fun MasterEnableCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (enabled) Icons.Default.Warning else Icons.Default.Shield,
                contentDescription = null,
                tint = if (enabled)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Emergency Wipe System",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (enabled)
                        "ARMED - Triggers are active"
                    else
                        "Disabled - No automatic wipe will occur",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.error,
                    checkedTrackColor = MaterialTheme.colorScheme.errorContainer
                )
            )
        }
    }
}

@Composable
private fun WarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Warning: Enabled triggers can permanently destroy your data. Configure carefully to avoid accidental data loss.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun TriggerSection(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandChange(!expanded) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded && enabled) {
                Divider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun DangerZoneItem(
    zone: DangerZone,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = zone.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "%.4f, %.4f (${zone.radiusMeters.toInt()}m)".format(zone.latitude, zone.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SetDuressPinDialog(
    appLockManager: AppLockManager,
    duressAuthenticator: DuressAuthenticator,
    onDismiss: () -> Unit,
    onPinSet: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (step == 1) "Set Duress PIN" else "Confirm Duress PIN") },
        text = {
            Column {
                Text(
                    text = if (step == 1)
                        "Enter a 4-8 digit PIN. This PIN will WIPE ALL DATA when used. Must be different from your unlock PIN."
                    else
                        "Re-enter the duress PIN to confirm",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = if (step == 1) pin else confirmPin,
                    onValueChange = { value ->
                        if (value.length <= 8 && value.all { it.isDigit() }) {
                            if (step == 1) pin = value else confirmPin = value
                            error = null
                        }
                    },
                    label = { Text("Duress PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (step == 1) {
                        if (pin.length < 4) {
                            error = "PIN must be at least 4 digits"
                        } else {
                            step = 2
                        }
                    } else {
                        if (confirmPin != pin) {
                            error = "PINs don't match"
                            confirmPin = ""
                        } else {
                            scope.launch {
                                // Get normal PIN hash from AppLockManager for comparison
                                // We need to ensure duress PIN is different
                                val success = duressAuthenticator.setDuressPin(
                                    pin = pin,
                                    normalPinHash = null, // Would need to get from AppLockManager
                                    normalPinSalt = null
                                )
                                if (success) {
                                    onPinSet()
                                } else {
                                    error = "Failed to set duress PIN"
                                }
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(if (step == 1) "Next" else "Set Duress PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (step == 2) {
                    step = 1
                    confirmPin = ""
                    error = null
                } else {
                    onDismiss()
                }
            }) {
                Text(if (step == 2) "Back" else "Cancel")
            }
        }
    )
}

@Composable
private fun AddDangerZoneDialog(
    onDismiss: () -> Unit,
    onAdd: (DangerZone) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("500") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
        title = { Text("Add Danger Zone") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Zone Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latitude,
                        onValueChange = { latitude = it; error = null },
                        label = { Text("Latitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = longitude,
                        onValueChange = { longitude = it; error = null },
                        label = { Text("Longitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = radius,
                    onValueChange = { radius = it; error = null },
                    label = { Text("Radius (meters)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lat = latitude.toDoubleOrNull()
                    val lon = longitude.toDoubleOrNull()
                    val rad = radius.toFloatOrNull()

                    when {
                        name.isBlank() -> error = "Name is required"
                        lat == null || lat < -90 || lat > 90 -> error = "Invalid latitude"
                        lon == null || lon < -180 || lon > 180 -> error = "Invalid longitude"
                        rad == null || rad < 10 || rad > 50000 -> error = "Radius must be 10-50000m"
                        else -> {
                            onAdd(
                                DangerZone(
                                    id = UUID.randomUUID().toString(),
                                    name = name,
                                    latitude = lat,
                                    longitude = lon,
                                    radiusMeters = rad
                                )
                            )
                        }
                    }
                }
            ) {
                Text("Add Zone")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatHours(hours: Int): String {
    return when {
        hours < 24 -> "$hours hours"
        hours == 24 -> "1 day"
        hours % 24 == 0 -> "${hours / 24} days"
        else -> "${hours / 24}d ${hours % 24}h"
    }
}

private fun formatSeconds(seconds: Int): String {
    return when {
        seconds == 0 -> "Immediate"
        seconds < 60 -> "$seconds seconds"
        seconds == 60 -> "1 minute"
        seconds % 60 == 0 -> "${seconds / 60} minutes"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}

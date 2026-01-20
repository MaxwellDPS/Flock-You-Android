package com.flockyou.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.scanner.flipper.FlipperConnectionState
import com.flockyou.scanner.probes.*
import com.flockyou.ui.components.SectionHeader
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveProbesScreen(
    onNavigateBack: () -> Unit,
    viewModel: ActiveProbesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()
    val flipperState by viewModel.flipperConnectionState.collectAsState()
    val executionState by viewModel.probeExecutionState.collectAsState()
    val showAuthDialog by viewModel.showAuthorizationDialog.collectAsState()

    // Toast messages
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Global Authorization Dialog
    if (showAuthDialog) {
        AuthorizationDialog(
            onConfirm = { note ->
                scope.launch { viewModel.setAuthorizationAndEnable(note) }
            },
            onDismiss = { viewModel.dismissAuthorizationDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Probes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Connection status indicator
                    FlipperStatusChip(state = flipperState)
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
            // Warning Banner
            item {
                WarningBanner()
            }

            // Master Toggle
            item {
                MasterToggleCard(
                    enabled = settings.activeProbesEnabled,
                    authorizationNote = settings.authorizationNote,
                    authorizationTimestamp = settings.authorizationTimestamp,
                    onToggle = { scope.launch { viewModel.setActiveProbesEnabled(it) } }
                )
            }

            // Execution State Indicator
            if (executionState !is ProbeExecutionState.Idle) {
                item {
                    ExecutionStateCard(state = executionState)
                }
            }

            // Flipper Connection Warning
            if (flipperState != FlipperConnectionState.READY) {
                item {
                    FlipperConnectionWarning(state = flipperState)
                }
            }

            // Category Sections (only show if master toggle is on)
            if (settings.activeProbesEnabled) {
                // Public Safety & Fleet
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    CategorySection(
                        category = ProbeCategory.PUBLIC_SAFETY,
                        enabled = settings.publicSafetyProbesEnabled,
                        onToggle = { scope.launch { viewModel.setCategoryEnabled(ProbeCategory.PUBLIC_SAFETY, it) } },
                        probes = ProbeCatalog.BY_CATEGORY[ProbeCategory.PUBLIC_SAFETY] ?: emptyList(),
                        onProbeClick = { viewModel.requestProbeExecution(it.id) }
                    )
                }

                // Infrastructure
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    CategorySection(
                        category = ProbeCategory.INFRASTRUCTURE,
                        enabled = settings.infrastructureProbesEnabled,
                        onToggle = { scope.launch { viewModel.setCategoryEnabled(ProbeCategory.INFRASTRUCTURE, it) } },
                        probes = ProbeCatalog.BY_CATEGORY[ProbeCategory.INFRASTRUCTURE] ?: emptyList(),
                        onProbeClick = { viewModel.requestProbeExecution(it.id) }
                    )
                }

                // Industrial
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    CategorySection(
                        category = ProbeCategory.INDUSTRIAL,
                        enabled = settings.industrialProbesEnabled,
                        onToggle = { scope.launch { viewModel.setCategoryEnabled(ProbeCategory.INDUSTRIAL, it) } },
                        probes = ProbeCatalog.BY_CATEGORY[ProbeCategory.INDUSTRIAL] ?: emptyList(),
                        onProbeClick = { viewModel.requestProbeExecution(it.id) }
                    )
                }

                // Physical Access
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    CategorySection(
                        category = ProbeCategory.PHYSICAL_ACCESS,
                        enabled = settings.physicalAccessProbesEnabled,
                        onToggle = { scope.launch { viewModel.setCategoryEnabled(ProbeCategory.PHYSICAL_ACCESS, it) } },
                        probes = ProbeCatalog.BY_CATEGORY[ProbeCategory.PHYSICAL_ACCESS] ?: emptyList(),
                        onProbeClick = { viewModel.requestProbeExecution(it.id) }
                    )
                }

                // Digital
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    CategorySection(
                        category = ProbeCategory.DIGITAL,
                        enabled = settings.digitalProbesEnabled,
                        onToggle = { scope.launch { viewModel.setCategoryEnabled(ProbeCategory.DIGITAL, it) } },
                        probes = ProbeCatalog.BY_CATEGORY[ProbeCategory.DIGITAL] ?: emptyList(),
                        onProbeClick = { viewModel.requestProbeExecution(it.id) }
                    )
                }

                // Safety Limits
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    SafetyLimitsCard(
                        settings = settings,
                        onUpdateLimits = { lf, ir, replay ->
                            scope.launch { viewModel.setSafetyLimits(lf, ir, replay) }
                        }
                    )
                }

                // Clear Authorization
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    ClearAuthorizationCard(
                        onClear = { scope.launch { viewModel.clearAuthorization() } }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun WarningBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Authorization Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Active probes transmit RF signals and may affect nearby systems. " +
                            "Use only with explicit authorization for penetration testing, " +
                            "security research, or CTF competitions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun MasterToggleCard(
    enabled: Boolean,
    authorizationNote: String,
    authorizationTimestamp: Long,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (enabled) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Active Probes Master Toggle",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (enabled) "Active probes enabled" else "All active probes disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle
                )
            }

            if (enabled && authorizationNote.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Assignment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Authorization: $authorizationNote",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (authorizationTimestamp > 0) {
                    Text(
                        text = "Recorded: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(authorizationTimestamp))}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FlipperStatusChip(state: FlipperConnectionState) {
    val (icon, text, color) = when (state) {
        FlipperConnectionState.READY -> Triple(Icons.Default.CheckCircle, "Connected", MaterialTheme.colorScheme.primary)
        FlipperConnectionState.CONNECTING -> Triple(Icons.Default.Sync, "Connecting", MaterialTheme.colorScheme.tertiary)
        else -> Triple(Icons.Default.LinkOff, "Disconnected", MaterialTheme.colorScheme.error)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
private fun FlipperConnectionWarning(state: FlipperConnectionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Connect your Flipper Zero to execute active probes. " +
                        "Go to Settings > Flipper Zero to connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun ExecutionStateCard(state: ProbeExecutionState) {
    val (icon, text, color) = when (state) {
        is ProbeExecutionState.Executing -> Triple(
            Icons.Default.Sync,
            "Executing: ${state.probeName}...",
            MaterialTheme.colorScheme.tertiary
        )
        is ProbeExecutionState.Success -> Triple(
            Icons.Default.CheckCircle,
            "Success: ${state.probeName}",
            MaterialTheme.colorScheme.primary
        )
        is ProbeExecutionState.Error -> Triple(
            Icons.Default.Error,
            state.message,
            MaterialTheme.colorScheme.error
        )
        else -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state is ProbeExecutionState.Executing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = color
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = color
            )
        }
    }
}

@Composable
private fun CategorySection(
    category: ProbeCategory,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    probes: List<ProbeDefinition>,
    onProbeClick: (ProbeDefinition) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val categoryColor = Color(category.colorHex)

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Category Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = categoryColor.copy(alpha = 0.2f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(categoryColor)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val activeCount = probes.count { it.type == ProbeType.ACTIVE }
                    val passiveCount = probes.count { it.type == ProbeType.PASSIVE }
                    Text(
                        text = "$activeCount active, $passiveCount passive probes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Probe List
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    probes.forEach { probe ->
                        ProbeRow(
                            probe = probe,
                            categoryEnabled = enabled,
                            onClick = { onProbeClick(probe) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeRow(
    probe: ProbeDefinition,
    categoryEnabled: Boolean,
    onClick: () -> Unit
) {
    val isActive = probe.type != ProbeType.PASSIVE
    val typeColor = when (probe.type) {
        ProbeType.PASSIVE -> MaterialTheme.colorScheme.primary
        ProbeType.ACTIVE -> MaterialTheme.colorScheme.error
        ProbeType.PHYSICAL -> MaterialTheme.colorScheme.tertiary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = categoryEnabled && isActive) { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Type Badge
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = typeColor.copy(alpha = 0.2f)
        ) {
            Text(
                text = probe.type.displayName.take(1),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = typeColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = probe.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (categoryEnabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = probe.mechanism,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (categoryEnabled) 1f else 0.5f
                )
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = probe.hardware.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = probe.targetSystem,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Execute button for active probes
        if (isActive && categoryEnabled) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Execute",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SafetyLimitsCard(
    settings: ActiveProbeSettings,
    onUpdateLimits: (Int?, Int?, Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Safety Limits",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure maximum durations and counts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))

                    // LF Duration
                    Text(
                        text = "Max LF Duration: ${settings.maxLfDurationMs}ms",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = settings.maxLfDurationMs.toFloat(),
                        onValueChange = { onUpdateLimits(it.toInt(), null, null) },
                        valueRange = 100f..5000f,
                        steps = 48
                    )

                    // IR Duration
                    Text(
                        text = "Max IR Strobe Duration: ${settings.maxIrStrobeDurationMs}ms",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = settings.maxIrStrobeDurationMs.toFloat(),
                        onValueChange = { onUpdateLimits(null, it.toInt(), null) },
                        valueRange = 100f..10000f,
                        steps = 98
                    )

                    // Replay Count
                    Text(
                        text = "Max Replay Count: ${settings.maxReplayCount}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = settings.maxReplayCount.toFloat(),
                        onValueChange = { onUpdateLimits(null, null, it.toInt()) },
                        valueRange = 1f..100f,
                        steps = 98
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearAuthorizationCard(
    onClear: () -> Unit
) {
    var showConfirmation by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Revoke Authorization",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Clear authorization and disable all active probes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { showConfirmation = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Revoke")
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Revoke Authorization?") },
            text = {
                Text(
                    "This will clear your authorization and disable all active probes. " +
                            "You will need to provide authorization again to use active probes."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmation = false
                        onClear()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AuthorizationDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var authNote by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Assignment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Authorization Context")
        },
        text = {
            Column {
                Text(
                    text = "Before enabling active probes, please document your authorization context. " +
                            "This helps ensure you're using these tools responsibly.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = authNote,
                    onValueChange = { authNote = it },
                    label = { Text("Authorization Note") },
                    placeholder = { Text("e.g., Pentest engagement for Client X") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Examples: CTF competition, authorized security audit, research lab testing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(authNote) },
                enabled = authNote.isNotBlank()
            ) {
                Text("Enable Active Probes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

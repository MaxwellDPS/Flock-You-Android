package com.flockyou.ui.components.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flockyou.data.ProtectionPreset

/**
 * Sensitivity level for threshold configuration
 */
enum class SensitivityLevel(val displayName: String) {
    LOW("Conservative"),
    MEDIUM("Balanced"),
    HIGH("Sensitive"),
    CUSTOM("Custom")
}

/**
 * Get the icon for a protection preset
 */
private fun ProtectionPreset.getIcon(): ImageVector = when (this) {
    ProtectionPreset.ESSENTIAL -> Icons.Default.Shield
    ProtectionPreset.BALANCED -> Icons.Default.Balance
    ProtectionPreset.PARANOID -> Icons.Default.Security
    ProtectionPreset.CUSTOM -> Icons.Default.Tune
}

/**
 * Get the sensitivity level for a protection preset
 */
private fun ProtectionPreset.getSensitivityLevel(): SensitivityLevel = when (this) {
    ProtectionPreset.ESSENTIAL -> SensitivityLevel.LOW
    ProtectionPreset.BALANCED -> SensitivityLevel.MEDIUM
    ProtectionPreset.PARANOID -> SensitivityLevel.HIGH
    ProtectionPreset.CUSTOM -> SensitivityLevel.CUSTOM
}

/**
 * Get the enabled pattern summary for a protection preset
 */
private fun ProtectionPreset.getEnabledPatternSummary(): String = when (this) {
    ProtectionPreset.ESSENTIAL -> "ALPR cameras, known trackers, IMSI catchers"
    ProtectionPreset.BALANCED -> "All surveillance devices, cellular anomalies, WiFi threats"
    ProtectionPreset.PARANOID -> "All patterns enabled, low thresholds, tracking alerts"
    ProtectionPreset.CUSTOM -> "User-defined configuration"
}

/**
 * Main protection preset selector component.
 * Displays a row of selectable preset chips and optionally a bottom sheet for detailed selection.
 *
 * @param currentPreset The currently selected preset
 * @param onPresetSelected Callback when a preset is selected
 * @param showBottomSheet Whether to show the detailed selection bottom sheet
 * @param onDismissBottomSheet Callback when the bottom sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectionPresetSelector(
    currentPreset: ProtectionPreset,
    onPresetSelected: (ProtectionPreset) -> Unit,
    showBottomSheet: Boolean = false,
    onDismissBottomSheet: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Chip row for quick selection
        PresetChipRow(
            currentPreset = currentPreset,
            onPresetClick = onPresetSelected
        )

        // Bottom sheet for detailed selection
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = onDismissBottomSheet,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                PresetBottomSheetContent(
                    currentPreset = currentPreset,
                    onPresetSelected = { preset ->
                        onPresetSelected(preset)
                        onDismissBottomSheet()
                    },
                    onDismiss = onDismissBottomSheet
                )
            }
        }
    }
}

/**
 * Horizontal row of preset filter chips.
 *
 * @param currentPreset The currently selected preset
 * @param onPresetClick Callback when a preset chip is clicked
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PresetChipRow(
    currentPreset: ProtectionPreset,
    onPresetClick: (ProtectionPreset) -> Unit
) {
    var showLongPressHint by remember { mutableStateOf(false) }
    var longPressedPreset by remember { mutableStateOf<ProtectionPreset?>(null) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProtectionPreset.entries.forEach { preset ->
                val isSelected = currentPreset == preset

                FilterChip(
                    selected = isSelected,
                    onClick = { onPresetClick(preset) },
                    label = {
                        Text(
                            text = preset.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = preset.getIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (preset) {
                            ProtectionPreset.ESSENTIAL -> MaterialTheme.colorScheme.tertiaryContainer
                            ProtectionPreset.BALANCED -> MaterialTheme.colorScheme.primaryContainer
                            ProtectionPreset.PARANOID -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                            ProtectionPreset.CUSTOM -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        selectedLabelColor = when (preset) {
                            ProtectionPreset.ESSENTIAL -> MaterialTheme.colorScheme.onTertiaryContainer
                            ProtectionPreset.BALANCED -> MaterialTheme.colorScheme.onPrimaryContainer
                            ProtectionPreset.PARANOID -> MaterialTheme.colorScheme.onErrorContainer
                            ProtectionPreset.CUSTOM -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    ),
                    modifier = Modifier.combinedClickable(
                        onClick = { onPresetClick(preset) },
                        onLongClick = {
                            longPressedPreset = preset
                            showLongPressHint = true
                        }
                    )
                )
            }
        }

        // Show preset description on long press
        if (showLongPressHint && longPressedPreset != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = longPressedPreset!!.getIcon(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = longPressedPreset!!.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = { showLongPressHint = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = longPressedPreset!!.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Content for the detailed preset selection bottom sheet.
 */
@Composable
private fun PresetBottomSheetContent(
    currentPreset: ProtectionPreset,
    onPresetSelected: (ProtectionPreset) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Choose Protection Level",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Text(
            text = "Select a preset to quickly configure detection sensitivity",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Preset list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(ProtectionPreset.entries.toList()) { preset ->
                PresetDetailCard(
                    preset = preset,
                    isSelected = currentPreset == preset,
                    onClick = { onPresetSelected(preset) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Action buttons
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }

            Button(
                onClick = { onPresetSelected(currentPreset) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Confirm")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Card showing detailed information about a preset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDetailCard(
    preset: ProtectionPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = when {
        isSelected -> when (preset) {
            ProtectionPreset.ESSENTIAL -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ProtectionPreset.BALANCED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ProtectionPreset.PARANOID -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ProtectionPreset.CUSTOM -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        }
        else -> MaterialTheme.colorScheme.surface
    }

    val borderColor = when {
        isSelected -> when (preset) {
            ProtectionPreset.ESSENTIAL -> MaterialTheme.colorScheme.tertiary
            ProtectionPreset.BALANCED -> MaterialTheme.colorScheme.primary
            ProtectionPreset.PARANOID -> MaterialTheme.colorScheme.error
            ProtectionPreset.CUSTOM -> MaterialTheme.colorScheme.secondary
        }
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(borderColor)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = preset.getIcon(),
                        contentDescription = null,
                        tint = borderColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = preset.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        SensitivityBadge(sensitivityLevel = preset.getSensitivityLevel())
                    }
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = borderColor,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Patterns summary
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Checklist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = preset.getEnabledPatternSummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Badge showing the sensitivity level.
 */
@Composable
private fun SensitivityBadge(sensitivityLevel: SensitivityLevel) {
    val (containerColor, contentColor) = when (sensitivityLevel) {
        SensitivityLevel.LOW -> Pair(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        SensitivityLevel.MEDIUM -> Pair(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        SensitivityLevel.HIGH -> Pair(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        SensitivityLevel.CUSTOM -> Pair(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = containerColor
    ) {
        Text(
            text = sensitivityLevel.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

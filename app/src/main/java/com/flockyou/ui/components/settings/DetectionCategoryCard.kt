package com.flockyou.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Detection category enum representing the four main detection types
 */
enum class DetectionCategory {
    CELLULAR,
    SATELLITE,
    BLE,
    WIFI
}

/**
 * Get the appropriate Material icon for the detection category
 */
fun DetectionCategory.toIcon(): ImageVector = when (this) {
    DetectionCategory.CELLULAR -> Icons.Default.CellTower
    DetectionCategory.SATELLITE -> Icons.Default.SatelliteAlt
    DetectionCategory.BLE -> Icons.Default.Bluetooth
    DetectionCategory.WIFI -> Icons.Default.Wifi
}

/**
 * Get the human-readable display name for the detection category
 */
fun DetectionCategory.toDisplayName(): String = when (this) {
    DetectionCategory.CELLULAR -> "Cellular"
    DetectionCategory.SATELLITE -> "Satellite"
    DetectionCategory.BLE -> "BLE"
    DetectionCategory.WIFI -> "WiFi"
}

/**
 * Data class representing a detection pattern item
 */
data class PatternItem(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)

/**
 * An expandable card component that displays a detection category with its patterns and thresholds.
 *
 * Collapsed state shows:
 * - Category icon
 * - Category name
 * - Badge showing "X of Y patterns enabled"
 * - Master toggle for the entire category
 * - Expand/collapse chevron
 *
 * Expanded state additionally shows:
 * - List of pattern toggles with names and descriptions
 * - Collapsible thresholds section
 * - Reset to defaults button
 *
 * @param category The detection category (CELLULAR, SATELLITE, BLE, WIFI)
 * @param categoryEnabled Whether the entire category is enabled
 * @param enabledPatternCount Number of patterns currently enabled
 * @param totalPatternCount Total number of patterns in this category
 * @param expanded Whether the card is currently expanded
 * @param onCategoryToggle Callback when the master category toggle changes
 * @param onExpandClick Callback when the expand/collapse button is clicked
 * @param patterns List of pattern items to display
 * @param onPatternToggle Callback when a pattern toggle changes (pattern id, new enabled state)
 * @param thresholdsContent Slot for threshold controls content
 * @param onResetDefaults Callback when reset to defaults is clicked
 */
@Composable
fun DetectionCategoryCard(
    category: DetectionCategory,
    categoryEnabled: Boolean,
    enabledPatternCount: Int,
    totalPatternCount: Int,
    expanded: Boolean,
    onCategoryToggle: (Boolean) -> Unit,
    onExpandClick: () -> Unit,
    patterns: List<PatternItem>,
    onPatternToggle: (String, Boolean) -> Unit,
    thresholdsContent: @Composable () -> Unit,
    onResetDefaults: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevronRotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (categoryEnabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row - Always visible
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category Icon
                Icon(
                    imageVector = category.toIcon(),
                    contentDescription = null,
                    tint = if (categoryEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Category Name and Badge
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category.toDisplayName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (categoryEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$enabledPatternCount of $totalPatternCount patterns enabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (categoryEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Master Toggle
                Switch(
                    checked = categoryEnabled,
                    onCheckedChange = onCategoryToggle
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Expand/Collapse Chevron
                IconButton(
                    onClick = onExpandClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded Content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Divider()

                    Spacer(modifier = Modifier.height(16.dp))

                    // Detection Patterns Section
                    Text(
                        text = "Detection Patterns",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Pattern Toggle Rows
                    patterns.forEach { pattern ->
                        PatternToggleRow(
                            pattern = pattern,
                            enabled = categoryEnabled,
                            onToggle = { enabled ->
                                onPatternToggle(pattern.id, enabled)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Thresholds Sub-Section
                    ThresholdsSubSection(
                        enabled = categoryEnabled,
                        content = thresholdsContent
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Reset to Defaults Button
                    OutlinedButton(
                        onClick = onResetDefaults,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reset to Defaults")
                    }
                }
            }
        }
    }
}

/**
 * A row component for toggling an individual detection pattern
 */
@Composable
private fun PatternToggleRow(
    pattern: PatternItem,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (pattern.enabled && enabled)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pattern.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = pattern.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = pattern.enabled,
                onCheckedChange = onToggle,
                enabled = enabled
            )
        }
    }
}

/**
 * A collapsible sub-section for threshold controls
 */
@Composable
private fun ThresholdsSubSection(
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    var thresholdsExpanded by remember { mutableStateOf(false) }

    val chevronRotation by animateFloatAsState(
        targetValue = if (thresholdsExpanded) 180f else 0f,
        label = "thresholdsChevronRotation"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            // Thresholds Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { thresholdsExpanded = !thresholdsExpanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = if (enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Threshold Controls",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (thresholdsExpanded) "Collapse thresholds" else "Expand thresholds",
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Thresholds Content
            AnimatedVisibility(visible = thresholdsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    Divider(modifier = Modifier.padding(bottom = 12.dp))
                    content()
                }
            }
        }
    }
}

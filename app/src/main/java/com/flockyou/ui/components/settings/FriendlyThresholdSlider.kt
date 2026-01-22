package com.flockyou.ui.components.settings

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Data class holding preset threshold values for Float-based thresholds
 */
data class ThresholdPresets(
    val sensitive: Float,
    val balanced: Float,
    val conservative: Float
)

/**
 * Data class holding preset threshold values for Int-based thresholds
 */
data class IntThresholdPresets(
    val sensitive: Int,
    val balanced: Int,
    val conservative: Int
)

/**
 * Enum representing the three preset options
 */
private enum class PresetOption {
    SENSITIVE,
    BALANCED,
    CONSERVATIVE
}

/**
 * A beginner-friendly threshold control component that provides both preset buttons
 * and an advanced slider for fine-tuned control.
 *
 * In beginner mode, users can choose from three presets: Sensitive, Balanced, and Conservative.
 * In advanced mode, users have access to a full slider for precise value selection.
 *
 * @param label The label text for this threshold control
 * @param value The current threshold value
 * @param range The valid range for the threshold value
 * @param unit The unit string to display (e.g., "dB", "ms", "%")
 * @param presetValues The preset values for sensitive, balanced, and conservative modes
 * @param description A helpful description explaining what this threshold controls
 * @param advancedMode Whether advanced mode is currently enabled
 * @param onValueChange Callback when the threshold value changes
 * @param onAdvancedModeToggle Callback when advanced mode is toggled
 * @param modifier Optional modifier for the component
 * @param steps Number of discrete steps for the slider (0 for continuous)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendlyThresholdSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    presetValues: ThresholdPresets,
    description: String,
    advancedMode: Boolean,
    onValueChange: (Float) -> Unit,
    onAdvancedModeToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0
) {
    // Determine which preset is closest to the current value
    val selectedPreset = remember(value, presetValues) {
        findClosestPreset(value, presetValues)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Label row with Advanced toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Advanced mode toggle button
            TextButton(
                onClick = { onAdvancedModeToggle(!advancedMode) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (advancedMode) Icons.Default.Tune else Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (advancedMode) "Simple" else "Advanced",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Beginner mode: Preset buttons
        AnimatedVisibility(
            visible = !advancedMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedPreset == PresetOption.SENSITIVE,
                    onClick = { onValueChange(presetValues.sensitive) },
                    label = { Text("Sensitive") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedPreset == PresetOption.BALANCED,
                    onClick = { onValueChange(presetValues.balanced) },
                    label = { Text("Balanced") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedPreset == PresetOption.CONSERVATIVE,
                    onClick = { onValueChange(presetValues.conservative) },
                    label = { Text("Conservative") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Advanced mode: Slider with value display
        AnimatedVisibility(
            visible = advancedMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Value display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current value:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatValue(value, unit),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Slider
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = range,
                    steps = steps,
                    modifier = Modifier.fillMaxWidth()
                )

                // Range labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatValue(range.start, unit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatValue(range.endInclusive, unit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Description text
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Int overload version of FriendlyThresholdSlider for integer threshold values.
 *
 * @param label The label text for this threshold control
 * @param value The current threshold value (integer)
 * @param range The valid range for the threshold value
 * @param unit The unit string to display (e.g., "dB", "ms", "%")
 * @param presetValues The preset values for sensitive, balanced, and conservative modes
 * @param description A helpful description explaining what this threshold controls
 * @param advancedMode Whether advanced mode is currently enabled
 * @param onValueChange Callback when the threshold value changes
 * @param onAdvancedModeToggle Callback when advanced mode is toggled
 * @param modifier Optional modifier for the component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendlyThresholdSlider(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    presetValues: IntThresholdPresets,
    description: String,
    advancedMode: Boolean,
    onValueChange: (Int) -> Unit,
    onAdvancedModeToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine which preset is closest to the current value
    val selectedPreset = remember(value, presetValues) {
        findClosestPreset(value, presetValues)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Label row with Advanced toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Advanced mode toggle button
            TextButton(
                onClick = { onAdvancedModeToggle(!advancedMode) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (advancedMode) Icons.Default.Tune else Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (advancedMode) "Simple" else "Advanced",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Beginner mode: Preset buttons
        AnimatedVisibility(
            visible = !advancedMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedPreset == PresetOption.SENSITIVE,
                    onClick = { onValueChange(presetValues.sensitive) },
                    label = { Text("Sensitive") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedPreset == PresetOption.BALANCED,
                    onClick = { onValueChange(presetValues.balanced) },
                    label = { Text("Balanced") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedPreset == PresetOption.CONSERVATIVE,
                    onClick = { onValueChange(presetValues.conservative) },
                    label = { Text("Conservative") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Advanced mode: Slider with value display
        AnimatedVisibility(
            visible = advancedMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Value display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current value:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$value $unit",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Slider - convert Int range to Float for Slider
                val floatValue = value.toFloat()
                val floatRange = range.first.toFloat()..range.last.toFloat()
                val steps = range.last - range.first - 1

                Slider(
                    value = floatValue,
                    onValueChange = { onValueChange(it.toInt()) },
                    valueRange = floatRange,
                    steps = if (steps > 0 && steps <= 100) steps else 0,
                    modifier = Modifier.fillMaxWidth()
                )

                // Range labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${range.first} $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${range.last} $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Description text
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Find the closest preset option to the given float value
 */
private fun findClosestPreset(value: Float, presets: ThresholdPresets): PresetOption {
    val sensitiveDistance = abs(value - presets.sensitive)
    val balancedDistance = abs(value - presets.balanced)
    val conservativeDistance = abs(value - presets.conservative)

    return when {
        sensitiveDistance <= balancedDistance && sensitiveDistance <= conservativeDistance -> PresetOption.SENSITIVE
        balancedDistance <= conservativeDistance -> PresetOption.BALANCED
        else -> PresetOption.CONSERVATIVE
    }
}

/**
 * Find the closest preset option to the given int value
 */
private fun findClosestPreset(value: Int, presets: IntThresholdPresets): PresetOption {
    val sensitiveDistance = abs(value - presets.sensitive)
    val balancedDistance = abs(value - presets.balanced)
    val conservativeDistance = abs(value - presets.conservative)

    return when {
        sensitiveDistance <= balancedDistance && sensitiveDistance <= conservativeDistance -> PresetOption.SENSITIVE
        balancedDistance <= conservativeDistance -> PresetOption.BALANCED
        else -> PresetOption.CONSERVATIVE
    }
}

/**
 * Format a float value with its unit for display
 */
private fun formatValue(value: Float, unit: String): String {
    return if (value == value.toInt().toFloat()) {
        "${value.toInt()} $unit"
    } else {
        "${"%.1f".format(value)} $unit"
    }
}

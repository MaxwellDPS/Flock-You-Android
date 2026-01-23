@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.flockyou.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.SignalStrength
import com.flockyou.ui.screens.TimeRange
import com.flockyou.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Get icon for signal strength
 */
fun SignalStrength.toIcon(): ImageVector = when (this) {
    SignalStrength.EXCELLENT -> Icons.Default.SignalCellular4Bar
    SignalStrength.GOOD -> Icons.Default.SignalCellularAlt
    SignalStrength.MEDIUM -> Icons.Default.SignalCellularAlt2Bar
    SignalStrength.WEAK -> Icons.Default.SignalCellularAlt1Bar
    SignalStrength.VERY_WEAK -> Icons.Default.SignalCellular0Bar
    SignalStrength.UNKNOWN -> Icons.Default.SignalCellularConnectedNoInternet0Bar
}

/**
 * Filter badge showing count of active filters
 */
@Composable
fun FilterBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Badge(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Collapsible filter section with header
 */
@Composable
fun CollapsibleFilterSection(
    title: String,
    selectedCount: Int = 0,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectedCount > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text(text = selectedCount.toString())
                }
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Protocol filter section with protocol chips
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtocolFilterSection(
    selected: Set<DetectionProtocol>,
    onToggle: (DetectionProtocol) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DetectionProtocol.entries.forEach { protocol ->
            FilterChip(
                selected = protocol in selected,
                onClick = { onToggle(protocol) },
                label = { Text(protocol.displayName) },
                leadingIcon = {
                    Icon(
                        imageVector = protocol.toIcon(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

/**
 * Time range filter section with preset chips
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeRangeFilterSection(
    selected: TimeRange,
    customStart: Long?,
    customEnd: Long?,
    onSelectPreset: (TimeRange) -> Unit,
    onSelectCustom: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }

    Column(modifier = modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeRange.entries.filter { it != TimeRange.CUSTOM }.forEach { range ->
                FilterChip(
                    selected = selected == range,
                    onClick = { onSelectPreset(range) },
                    label = { Text(range.label) }
                )
            }
            // Custom chip
            FilterChip(
                selected = selected == TimeRange.CUSTOM,
                onClick = { showDatePicker = true },
                label = {
                    if (selected == TimeRange.CUSTOM && customStart != null && customEnd != null) {
                        Text("${dateFormat.format(Date(customStart))} - ${dateFormat.format(Date(customEnd))}")
                    } else {
                        Text("Custom")
                    }
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }

    if (showDatePicker) {
        CustomDateRangePicker(
            initialStart = customStart,
            initialEnd = customEnd,
            onConfirm = { start, end ->
                onSelectCustom(start, end)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

/**
 * Custom date range picker dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDateRangePicker(
    initialStart: Long?,
    initialEnd: Long?,
    onConfirm: (Long, Long) -> Unit,
    onDismiss: () -> Unit
) {
    val dateRangePickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart,
        initialSelectedEndDateMillis = initialEnd
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = dateRangePickerState.selectedStartDateMillis
                    val end = dateRangePickerState.selectedEndDateMillis
                    if (start != null && end != null) {
                        // Set end to end of day
                        val endOfDay = end + (24 * 60 * 60 * 1000 - 1)
                        onConfirm(start, endOfDay)
                    }
                },
                enabled = dateRangePickerState.selectedStartDateMillis != null &&
                        dateRangePickerState.selectedEndDateMillis != null
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DateRangePicker(
            state = dateRangePickerState,
            title = { Text("Select date range", modifier = Modifier.padding(16.dp)) },
            modifier = Modifier.height(500.dp)
        )
    }
}

/**
 * Signal strength filter section
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalStrengthFilterSection(
    selected: Set<SignalStrength>,
    onToggle: (SignalStrength) -> Unit,
    modifier: Modifier = Modifier
) {
    // Only show the main signal strength options (not UNKNOWN)
    val displayedStrengths = listOf(
        SignalStrength.EXCELLENT,
        SignalStrength.GOOD,
        SignalStrength.MEDIUM,
        SignalStrength.WEAK
    )

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        displayedStrengths.forEach { strength ->
            FilterChip(
                selected = strength in selected,
                onClick = { onToggle(strength) },
                label = { Text(strength.displayName) },
                leadingIcon = {
                    Icon(
                        imageVector = strength.toIcon(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = strength.toColor().copy(alpha = 0.2f),
                    selectedLabelColor = strength.toColor()
                )
            )
        }
    }
}

/**
 * Active only toggle component
 */
@Composable
fun ActiveOnlyToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (enabled) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) StatusActive else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Active devices only",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Show only devices currently broadcasting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

/**
 * Quick filters row - commonly used filter combinations
 */
@Composable
fun QuickFiltersRow(
    activeOnly: Boolean,
    timeRange: TimeRange,
    onActiveOnlyChange: (Boolean) -> Unit,
    onTimeRangeChange: (TimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = activeOnly,
                onClick = { onActiveOnlyChange(!activeOnly) },
                label = { Text("Active Only") },
                leadingIcon = if (activeOnly) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
        item {
            FilterChip(
                selected = timeRange == TimeRange.LAST_24H,
                onClick = {
                    onTimeRangeChange(
                        if (timeRange == TimeRange.LAST_24H) TimeRange.ALL_TIME else TimeRange.LAST_24H
                    )
                },
                label = { Text("Last 24h") },
                leadingIcon = if (timeRange == TimeRange.LAST_24H) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
        item {
            FilterChip(
                selected = timeRange == TimeRange.LAST_HOUR,
                onClick = {
                    onTimeRangeChange(
                        if (timeRange == TimeRange.LAST_HOUR) TimeRange.ALL_TIME else TimeRange.LAST_HOUR
                    )
                },
                label = { Text("Last Hour") },
                leadingIcon = if (timeRange == TimeRange.LAST_HOUR) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

/**
 * Filter button with badge showing active filter count
 */
@Composable
fun FilterButton(
    filterCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = "Filters"
            )
        }
        if (filterCount > 0) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = filterCount.toString(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

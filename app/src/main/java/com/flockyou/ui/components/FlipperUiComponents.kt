package com.flockyou.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flockyou.data.FlipperViewMode
import com.flockyou.scanner.flipper.FlipperConnectionState

/**
 * Determines the status level for the Flipper at-a-glance header.
 */
enum class FlipperStatusLevel {
    GOOD,      // Green - connected & scanning, no issues
    WARNING,   // Yellow - connected but warnings (low battery, scan errors)
    ERROR      // Red - disconnected or error state
}

/**
 * Calculate the overall status level for the Flipper Zero.
 */
fun calculateFlipperStatusLevel(
    connectionState: FlipperConnectionState,
    flipperStatus: com.flockyou.scanner.flipper.FlipperStatusResponse?,
    isScanning: Boolean,
    lastError: String?
): FlipperStatusLevel {
    return when {
        connectionState == FlipperConnectionState.ERROR -> FlipperStatusLevel.ERROR
        connectionState == FlipperConnectionState.DISCONNECTED -> FlipperStatusLevel.ERROR
        connectionState != FlipperConnectionState.READY -> FlipperStatusLevel.WARNING
        lastError != null -> FlipperStatusLevel.WARNING
        flipperStatus != null && flipperStatus.batteryPercent <= 20 -> FlipperStatusLevel.WARNING
        !isScanning -> FlipperStatusLevel.WARNING
        else -> FlipperStatusLevel.GOOD
    }
}

/**
 * Get the status text for the Flipper at-a-glance header.
 */
fun getFlipperStatusText(
    connectionState: FlipperConnectionState,
    flipperStatus: com.flockyou.scanner.flipper.FlipperStatusResponse?,
    isScanning: Boolean,
    lastError: String?
): String {
    return when {
        connectionState == FlipperConnectionState.ERROR -> "Connection Error"
        connectionState == FlipperConnectionState.DISCONNECTED -> "Disconnected"
        connectionState != FlipperConnectionState.READY -> "Connecting..."
        lastError != null -> "Scan Error"
        flipperStatus != null && flipperStatus.batteryPercent <= 20 -> "Low Battery (${flipperStatus.batteryPercent}%)"
        !isScanning -> "Scanning Paused"
        else -> "All Systems Active"
    }
}

/**
 * Status At-A-Glance Header for Flipper tab.
 * Shows a color-coded status bar at the top of the Flipper tab.
 * - Green: connected & scanning, no issues
 * - Yellow: connected but warnings (low battery, scan errors)
 * - Red: disconnected or error state
 */
@Composable
fun FlipperStatusHeader(
    statusLevel: FlipperStatusLevel,
    statusText: String,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (statusLevel) {
        FlipperStatusLevel.GOOD -> Color(0xFF4CAF50).copy(alpha = 0.15f)
        FlipperStatusLevel.WARNING -> Color(0xFFFFC107).copy(alpha = 0.15f)
        FlipperStatusLevel.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    }
    val contentColor = when (statusLevel) {
        FlipperStatusLevel.GOOD -> Color(0xFF2E7D32)
        FlipperStatusLevel.WARNING -> Color(0xFFF57C00)
        FlipperStatusLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    val icon = when (statusLevel) {
        FlipperStatusLevel.GOOD -> Icons.Default.CheckCircle
        FlipperStatusLevel.WARNING -> Icons.Default.Warning
        FlipperStatusLevel.ERROR -> Icons.Default.Error
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}

/**
 * View mode toggle (Detailed/Summary).
 * Allows switching between detailed view (all cards) and summary view (compact single card).
 */
@Composable
fun FlipperViewModeToggle(
    currentMode: FlipperViewMode,
    onModeChange: (FlipperViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FlipperViewMode.entries.forEach { mode ->
                val isSelected = mode == currentMode
                Surface(
                    modifier = Modifier.weight(1f),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    onClick = { onModeChange(mode) }
                ) {
                    Text(
                        text = if (mode == FlipperViewMode.DETAILED) "Detailed" else "Summary",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Compact Summary Card showing key metrics in one view.
 * Shows: connection status, battery %, active scans count, detection count.
 */
@Composable
fun FlipperSummaryCard(
    connectionState: FlipperConnectionState,
    flipperStatus: com.flockyou.scanner.flipper.FlipperStatusResponse?,
    isScanning: Boolean,
    detectionCount: Int,
    scanSchedulerStatus: com.flockyou.scanner.flipper.ScanSchedulerStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "QUICK STATUS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Connection status
                FlipperSummaryMetric(
                    label = "Connection",
                    value = if (connectionState == FlipperConnectionState.READY) "Connected" else "Offline",
                    valueColor = if (connectionState == FlipperConnectionState.READY)
                        Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )

                // Battery
                FlipperSummaryMetric(
                    label = "Battery",
                    value = flipperStatus?.batteryPercent?.let { "$it%" } ?: "--",
                    valueColor = when {
                        flipperStatus == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        flipperStatus.batteryPercent > 20 -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.error
                    }
                )

                // Active scans count
                val activeScans = listOfNotNull(
                    if (scanSchedulerStatus.wifiScanActive) "WiFi" else null,
                    if (scanSchedulerStatus.subGhzScanActive) "Sub-GHz" else null,
                    if (scanSchedulerStatus.bleScanActive) "BLE" else null
                ).size
                FlipperSummaryMetric(
                    label = "Active Scans",
                    value = activeScans.toString(),
                    valueColor = if (activeScans > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Detection count
                FlipperSummaryMetric(
                    label = "Detections",
                    value = detectionCount.toString(),
                    valueColor = if (detectionCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Scanning status indicator
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isScanning) "Scanning Active" else "Scanning Paused",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FlipperSummaryMetric(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Collapsible card header with expand/collapse functionality.
 * Shows a header row with title, optional icon, and expand/collapse button.
 * The expand icon rotates smoothly when toggling.
 */
@Composable
fun CollapsibleCardHeader(
    title: String,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    icon: @Composable (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(),
        label = "expandIconRotation"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = titleColor,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { onExpandedChange(!isExpanded) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    rotationZ = rotationAngle
                }
            )
        }
    }
}

/**
 * Collapsible card content wrapper.
 * Wraps content with AnimatedVisibility for smooth expand/collapse animation.
 * Uses animateContentSize on the card itself for smooth height transitions.
 */
@Composable
fun CollapsibleCardContent(
    isExpanded: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = isExpanded,
        enter = fadeIn() + expandVertically(animationSpec = spring()),
        exit = fadeOut() + shrinkVertically(animationSpec = spring())
    ) {
        content()
    }
}

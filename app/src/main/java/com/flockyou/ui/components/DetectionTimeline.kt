package com.flockyou.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flockyou.data.model.SignalStrength
import com.flockyou.data.model.rssiToSignalStrength
import com.flockyou.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a single event on the detection timeline.
 *
 * @param timestamp The time of the detection event in milliseconds since epoch
 * @param signalStrength Optional RSSI value in dBm (typically -30 to -100)
 * @param isActive Whether this event represents an active/current detection
 */
data class TimelineEvent(
    val timestamp: Long,
    val signalStrength: Int? = null,
    val isActive: Boolean = false
)

/**
 * A horizontal timeline component for visualizing detection history.
 * Shows first seen, last seen timestamps with optional intermediate events.
 *
 * Features:
 * - Horizontal timeline with connecting line
 * - Dots sized/colored by signal strength
 * - First and last seen labels
 * - Compact design for bottom sheets
 *
 * @param firstSeen Timestamp when the detection was first observed
 * @param lastSeen Optional timestamp for when the detection was last seen (null if same as firstSeen)
 * @param events Optional list of intermediate timeline events with signal strength data
 * @param modifier Modifier for the composable
 */
@Composable
fun DetectionTimeline(
    firstSeen: Long,
    lastSeen: Long?,
    events: List<TimelineEvent> = emptyList(),
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant

    // Calculate if this is a single detection or has history
    val effectiveLastSeen = lastSeen ?: firstSeen
    val isSingleDetection = firstSeen == effectiveLastSeen && events.isEmpty()
    val hasMultipleEvents = events.size > 1

    // Sort events by timestamp
    val sortedEvents = remember(events) {
        events.sortedBy { it.timestamp }
    }

    // Pre-compute event colors and sizes outside of Canvas (since toColor() is @Composable)
    val eventVisuals = sortedEvents.map { event ->
        val (color, size) = getSignalColorAndSize(event.signalStrength)
        Triple(event, color, size)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Timeline canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val centerY = canvasHeight / 2

                // Drawing constants
                val lineY = centerY
                val startX = 24f
                val endX = canvasWidth - 24f
                val lineWidth = endX - startX

                // Draw connecting line
                drawLine(
                    color = surfaceVariantColor,
                    start = Offset(startX, lineY),
                    end = Offset(endX, lineY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )

                if (isSingleDetection) {
                    // Single detection - just show one dot in the center
                    val centerX = canvasWidth / 2
                    val dotRadius = 8.dp.toPx()

                    // Outer glow
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.3f),
                        radius = dotRadius + 4.dp.toPx(),
                        center = Offset(centerX, lineY)
                    )

                    // Main dot
                    drawCircle(
                        color = primaryColor,
                        radius = dotRadius,
                        center = Offset(centerX, lineY)
                    )

                    // Inner highlight
                    drawCircle(
                        color = Color.White.copy(alpha = 0.3f),
                        radius = dotRadius * 0.4f,
                        center = Offset(centerX - 2.dp.toPx(), lineY - 2.dp.toPx())
                    )
                } else {
                    // Multiple events - show first, intermediate events, and last
                    val timeRange = effectiveLastSeen - firstSeen

                    // Draw intermediate events (smaller dots)
                    if (hasMultipleEvents) {
                        eventVisuals.forEach { (event, dotColor, dotSizeDp) ->
                            val progress = if (timeRange > 0) {
                                ((event.timestamp - firstSeen).toFloat() / timeRange).coerceIn(0f, 1f)
                            } else 0.5f
                            val x = startX + (lineWidth * progress)
                            val dotRadius = dotSizeDp.dp.toPx()

                            // Draw event dot
                            if (event.isActive) {
                                // Active event gets a glow
                                drawCircle(
                                    color = dotColor.copy(alpha = 0.3f),
                                    radius = dotRadius + 3.dp.toPx(),
                                    center = Offset(x, lineY)
                                )
                            }

                            drawCircle(
                                color = dotColor,
                                radius = dotRadius,
                                center = Offset(x, lineY)
                            )
                        }
                    }

                    // Draw first seen dot (larger, with outline)
                    val firstDotRadius = 8.dp.toPx()
                    drawCircle(
                        color = outlineColor,
                        radius = firstDotRadius + 2.dp.toPx(),
                        center = Offset(startX, lineY),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = firstDotRadius,
                        center = Offset(startX, lineY)
                    )

                    // Draw last seen dot (larger, filled, with glow for active)
                    val lastDotRadius = 8.dp.toPx()
                    val lastIsActive = sortedEvents.lastOrNull()?.isActive ?: false

                    if (lastIsActive) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.3f),
                            radius = lastDotRadius + 4.dp.toPx(),
                            center = Offset(endX, lineY)
                        )
                    }

                    drawCircle(
                        color = primaryColor,
                        radius = lastDotRadius,
                        center = Offset(endX, lineY)
                    )

                    // Inner highlight on last dot
                    drawCircle(
                        color = Color.White.copy(alpha = 0.3f),
                        radius = lastDotRadius * 0.4f,
                        center = Offset(endX - 2.dp.toPx(), lineY - 2.dp.toPx())
                    )
                }
            }
        }

        // Labels row
        if (isSingleDetection) {
            // Single detection - show centered timestamp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Detected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTimestamp(firstSeen, dateFormat, timeFormat),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            // Multiple events - show first and last labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "First seen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTimestamp(firstSeen, dateFormat, timeFormat),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Show event count in the middle if there are intermediate events
                if (hasMultipleEvents) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${sortedEvents.size} events",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Last seen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTimestamp(effectiveLastSeen, dateFormat, timeFormat),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Format timestamp for display, showing relative time for recent events
 */
private fun formatTimestamp(
    timestamp: Long,
    dateFormat: SimpleDateFormat,
    timeFormat: SimpleDateFormat
): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> timeFormat.format(Date(timestamp))
        diff < 604800_000 -> "${diff / 86400_000}d ago, ${timeFormat.format(Date(timestamp))}"
        else -> dateFormat.format(Date(timestamp))
    }
}

/**
 * Get color and size for a timeline dot based on signal strength (RSSI)
 */
@Composable
private fun getSignalColorAndSize(rssi: Int?): Pair<Color, Float> {
    if (rssi == null) {
        return Pair(MaterialTheme.colorScheme.outline, 4.dp.value)
    }

    val signalStrength = rssiToSignalStrength(rssi)
    val color = signalStrength.toColor()

    // Size based on signal strength - stronger = larger
    val sizeDp = when (signalStrength) {
        SignalStrength.EXCELLENT -> 7.dp
        SignalStrength.GOOD -> 6.dp
        SignalStrength.MEDIUM -> 5.dp
        SignalStrength.WEAK -> 4.dp
        SignalStrength.VERY_WEAK -> 3.dp
        SignalStrength.UNKNOWN -> 4.dp
    }

    return Pair(color, sizeDp.value)
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, backgroundColor = 0xFF161B22)
@Composable
private fun DetectionTimelineSingleEventPreview() {
    FlockYouTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DetectionTimeline(
                firstSeen = System.currentTimeMillis() - 300_000, // 5 minutes ago
                lastSeen = null,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161B22)
@Composable
private fun DetectionTimelineSimpleRangePreview() {
    FlockYouTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DetectionTimeline(
                firstSeen = System.currentTimeMillis() - 3600_000, // 1 hour ago
                lastSeen = System.currentTimeMillis() - 60_000,    // 1 minute ago
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161B22)
@Composable
private fun DetectionTimelineWithEventsPreview() {
    FlockYouTheme {
        val now = System.currentTimeMillis()
        Surface(color = MaterialTheme.colorScheme.surface) {
            DetectionTimeline(
                firstSeen = now - 7200_000, // 2 hours ago
                lastSeen = now - 60_000,    // 1 minute ago
                events = listOf(
                    TimelineEvent(
                        timestamp = now - 7200_000,
                        signalStrength = -75,
                        isActive = false
                    ),
                    TimelineEvent(
                        timestamp = now - 5400_000,
                        signalStrength = -60,
                        isActive = false
                    ),
                    TimelineEvent(
                        timestamp = now - 3600_000,
                        signalStrength = -45,
                        isActive = false
                    ),
                    TimelineEvent(
                        timestamp = now - 1800_000,
                        signalStrength = -55,
                        isActive = false
                    ),
                    TimelineEvent(
                        timestamp = now - 60_000,
                        signalStrength = -50,
                        isActive = true
                    )
                ),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161B22)
@Composable
private fun DetectionTimelineWeakSignalsPreview() {
    FlockYouTheme {
        val now = System.currentTimeMillis()
        Surface(color = MaterialTheme.colorScheme.surface) {
            DetectionTimeline(
                firstSeen = now - 86400_000, // 1 day ago
                lastSeen = now - 3600_000,   // 1 hour ago
                events = listOf(
                    TimelineEvent(
                        timestamp = now - 86400_000,
                        signalStrength = -90,
                        isActive = false
                    ),
                    TimelineEvent(
                        timestamp = now - 43200_000,
                        signalStrength = -85,
                        isActive = false
                    ),
                    TimelineEvent(
                        timestamp = now - 3600_000,
                        signalStrength = -80,
                        isActive = false
                    )
                ),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161B22)
@Composable
private fun DetectionTimelineOldDetectionPreview() {
    FlockYouTheme {
        val now = System.currentTimeMillis()
        Surface(color = MaterialTheme.colorScheme.surface) {
            DetectionTimeline(
                firstSeen = now - 604800_000 * 2, // 2 weeks ago
                lastSeen = now - 604800_000,      // 1 week ago
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

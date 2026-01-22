package com.flockyou.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flockyou.data.model.*
import com.flockyou.ui.theme.FlockYouTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * Maximum number of related detections to display before showing "See All" button.
 */
private const val MAX_VISIBLE_DETECTIONS = 5

/**
 * Section displaying related detections for a detection detail view.
 * Shows a horizontal scrollable row of compact detection cards with a header
 * showing the count and optional "See All" button.
 *
 * Related detections can include:
 * - Same MAC address seen at different times/locations
 * - Nearby detections (same approximate location)
 * - Same device type from same manufacturer
 *
 * @param relatedDetections List of related detection objects
 * @param onDetectionClick Callback when a detection card is clicked
 * @param onSeeAllClick Callback when "See All" button is clicked
 * @param modifier Optional modifier for the section container
 */
@Composable
fun RelatedDetectionsSection(
    relatedDetections: List<Detection>,
    onDetectionClick: (Detection) -> Unit,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleDetections = relatedDetections.take(MAX_VISIBLE_DETECTIONS)
    val hasMoreDetections = relatedDetections.size > MAX_VISIBLE_DETECTIONS

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with title and count badge
            RelatedDetectionsHeader(
                count = relatedDetections.size,
                showSeeAll = hasMoreDetections,
                onSeeAllClick = onSeeAllClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (relatedDetections.isEmpty()) {
                // Empty state
                RelatedDetectionsEmptyState()
            } else {
                // Horizontal scrollable row of compact cards
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    visibleDetections.forEach { detection ->
                        CompactDetectionCard(
                            detection = detection,
                            onClick = { onDetectionClick(detection) }
                        )
                    }

                    // Show "See All" card at the end if there are more detections
                    if (hasMoreDetections) {
                        SeeAllCard(
                            remainingCount = relatedDetections.size - MAX_VISIBLE_DETECTIONS,
                            onClick = onSeeAllClick
                        )
                    }
                }
            }
        }
    }
}

/**
 * Header row for the related detections section.
 * Shows title, count badge, and optional "See All" button.
 */
@Composable
private fun RelatedDetectionsHeader(
    count: Int,
    showSeeAll: Boolean,
    onSeeAllClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Hub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "Related Detections",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )

        // Count badge
        if (count > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // See All button for larger lists
        if (showSeeAll) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onSeeAllClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "See All",
                    style = MaterialTheme.typography.labelMedium
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Empty state displayed when there are no related detections.
 */
@Composable
private fun RelatedDetectionsEmptyState() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "No related detections found",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Compact detection card for display in the horizontal scroll row.
 * Shows device icon, name, threat badge, and time ago in a compact format.
 *
 * @param detection The detection to display
 * @param onClick Callback when the card is clicked
 * @param modifier Optional modifier for the card
 */
@Composable
private fun CompactDetectionCard(
    detection: Detection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val threatColor = detection.threatLevel.toColor()
    val timeAgo = formatTimeAgo(detection.timestamp)

    Card(
        modifier = modifier
            .width(160.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${detection.deviceType.displayName}, ${detection.threatLevel.displayName} threat, $timeAgo"
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Top row: Icon and threat badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Device icon with threat color background
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(threatColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = detection.deviceType.toIcon(),
                        contentDescription = null,
                        tint = threatColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Compact threat badge
                CompactThreatBadge(threatLevel = detection.threatLevel)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Device name
            Text(
                text = detection.deviceName ?: detection.deviceType.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Secondary info (MAC or SSID)
            val secondaryInfo = detection.macAddress
                ?: detection.ssid
                ?: detection.manufacturer
            if (secondaryInfo != null) {
                Text(
                    text = secondaryInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Time ago
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = timeAgo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Compact threat level badge for use in the compact detection card.
 * Smaller than the standard ThreatBadge for space efficiency.
 */
@Composable
private fun CompactThreatBadge(
    threatLevel: ThreatLevel,
    modifier: Modifier = Modifier
) {
    val color = threatLevel.toColor()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = threatLevel.name.take(4),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * "See All" card shown at the end of the horizontal scroll when there are
 * more detections than the visible limit.
 */
@Composable
private fun SeeAllCard(
    remainingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "+$remainingCount more",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "See All",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Formats a timestamp into a human-readable "time ago" string.
 */
private fun formatTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> dateFormat.format(Date(timestamp))
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun RelatedDetectionsSectionPreview() {
    val sampleDetections = listOf(
        Detection(
            id = "1",
            timestamp = System.currentTimeMillis() - 300_000, // 5 minutes ago
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.AIRTAG_DETECTED,
            deviceType = DeviceType.AIRTAG,
            deviceName = "AirTag",
            macAddress = "AA:BB:CC:DD:EE:01",
            rssi = -65,
            signalStrength = SignalStrength.GOOD,
            threatLevel = ThreatLevel.MEDIUM
        ),
        Detection(
            id = "2",
            timestamp = System.currentTimeMillis() - 3600_000, // 1 hour ago
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            deviceName = "Flock Camera",
            macAddress = "AA:BB:CC:DD:EE:02",
            ssid = "FLOCK-CAM-001",
            rssi = -72,
            signalStrength = SignalStrength.MEDIUM,
            threatLevel = ThreatLevel.HIGH
        ),
        Detection(
            id = "3",
            timestamp = System.currentTimeMillis() - 7200_000, // 2 hours ago
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.BLE_SERVICE_UUID,
            deviceType = DeviceType.FLIPPER_ZERO,
            deviceName = "Flipper Zero",
            macAddress = "AA:BB:CC:DD:EE:03",
            rssi = -80,
            signalStrength = SignalStrength.WEAK,
            threatLevel = ThreatLevel.LOW
        )
    )

    FlockYouTheme {
        RelatedDetectionsSection(
            relatedDetections = sampleDetections,
            onDetectionClick = {},
            onSeeAllClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RelatedDetectionsSectionEmptyPreview() {
    FlockYouTheme {
        RelatedDetectionsSection(
            relatedDetections = emptyList(),
            onDetectionClick = {},
            onSeeAllClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RelatedDetectionsSectionManyPreview() {
    val sampleDetections = (1..8).map { index ->
        Detection(
            id = index.toString(),
            timestamp = System.currentTimeMillis() - (index * 1800_000L),
            protocol = if (index % 2 == 0) DetectionProtocol.WIFI else DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.MAC_PREFIX,
            deviceType = when (index % 4) {
                0 -> DeviceType.AIRTAG
                1 -> DeviceType.FLOCK_SAFETY_CAMERA
                2 -> DeviceType.RING_DOORBELL
                else -> DeviceType.FLIPPER_ZERO
            },
            macAddress = "AA:BB:CC:DD:EE:%02X".format(index),
            rssi = -60 - (index * 5),
            signalStrength = SignalStrength.MEDIUM,
            threatLevel = when (index % 5) {
                0 -> ThreatLevel.CRITICAL
                1 -> ThreatLevel.HIGH
                2 -> ThreatLevel.MEDIUM
                3 -> ThreatLevel.LOW
                else -> ThreatLevel.INFO
            }
        )
    }

    FlockYouTheme {
        RelatedDetectionsSection(
            relatedDetections = sampleDetections,
            onDetectionClick = {},
            onSeeAllClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CompactDetectionCardPreview() {
    val detection = Detection(
        id = "preview",
        timestamp = System.currentTimeMillis() - 1800_000,
        protocol = DetectionProtocol.BLUETOOTH_LE,
        detectionMethod = DetectionMethod.AIRTAG_DETECTED,
        deviceType = DeviceType.AIRTAG,
        deviceName = "Unknown AirTag",
        macAddress = "AA:BB:CC:DD:EE:FF",
        rssi = -68,
        signalStrength = SignalStrength.GOOD,
        threatLevel = ThreatLevel.HIGH
    )

    FlockYouTheme {
        CompactDetectionCard(
            detection = detection,
            onClick = {}
        )
    }
}

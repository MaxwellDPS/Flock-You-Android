package com.flockyou.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flockyou.ui.theme.FlockYouTheme

/**
 * Preferences helper for CollapsibleSection state persistence
 */
private object CollapsibleSectionPreferences {
    private const val PREFS_NAME = "collapsible_section_prefs"

    fun getExpandedState(context: Context, key: String, default: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(key, default)
    }

    fun setExpandedState(context: Context, key: String, expanded: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, expanded).apply()
    }
}

/**
 * A reusable collapsible section component with animated expand/collapse,
 * header with icon/title/badge, and optional state persistence.
 *
 * @param title The title text displayed in the header
 * @param icon The icon displayed at the start of the header
 * @param defaultExpanded Initial expanded state (used when persistKey is null or no saved state exists)
 * @param persistKey If provided, the expanded state will be persisted to SharedPreferences using this key
 * @param badge Optional badge/count text displayed in the header (e.g., "5" for item count)
 * @param modifier Modifier for the root container
 * @param content The content to show/hide when expanded/collapsed
 */
@Composable
fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    defaultExpanded: Boolean = false,
    persistKey: String? = null,
    badge: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Initialize expanded state - load from preferences if persistKey is provided
    var expanded by remember(persistKey) {
        mutableStateOf(
            if (persistKey != null) {
                CollapsibleSectionPreferences.getExpandedState(context, persistKey, defaultExpanded)
            } else {
                defaultExpanded
            }
        )
    }

    // Persist state changes when persistKey is provided
    LaunchedEffect(expanded, persistKey) {
        if (persistKey != null) {
            CollapsibleSectionPreferences.setExpandedState(context, persistKey, expanded)
        }
    }

    // Animate chevron rotation
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "chevronRotation"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Section icon
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Title
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() }
                    )

                    // Badge (optional)
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Chevron indicator with rotation animation
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse $title section" else "Expand $title section",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(chevronRotation)
                )
            }

            // Expandable content with animated visibility
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300),
                    expandFrom = Alignment.Top
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 300),
                    shrinkTowards = Alignment.Top
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    // Divider above content
                    Divider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    content()
                }
            }
        }
    }
}

/**
 * Variant of CollapsibleSection with a card-style appearance
 */
@Composable
fun CollapsibleCard(
    title: String,
    icon: ImageVector,
    defaultExpanded: Boolean = false,
    persistKey: String? = null,
    badge: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        CollapsibleSectionContent(
            title = title,
            icon = icon,
            defaultExpanded = defaultExpanded,
            persistKey = persistKey,
            badge = badge,
            content = content
        )
    }
}

/**
 * Internal composable for shared content between CollapsibleSection variants
 */
@Composable
private fun CollapsibleSectionContent(
    title: String,
    icon: ImageVector,
    defaultExpanded: Boolean,
    persistKey: String?,
    badge: String?,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    var expanded by remember(persistKey) {
        mutableStateOf(
            if (persistKey != null) {
                CollapsibleSectionPreferences.getExpandedState(context, persistKey, defaultExpanded)
            } else {
                defaultExpanded
            }
        )
    }

    LaunchedEffect(expanded, persistKey) {
        if (persistKey != null) {
            CollapsibleSectionPreferences.setExpandedState(context, persistKey, expanded)
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "chevronRotation"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )

                if (badge != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse $title section" else "Expand $title section",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(chevronRotation)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 300),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 300),
                shrinkTowards = Alignment.Top
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                content()
            }
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun CollapsibleSectionCollapsedPreview() {
    FlockYouTheme {
        CollapsibleSection(
            title = "Detection Settings",
            icon = Icons.Default.Settings,
            defaultExpanded = false,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "This is the content that appears when expanded.",
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun CollapsibleSectionExpandedPreview() {
    FlockYouTheme {
        CollapsibleSection(
            title = "Active Detections",
            icon = Icons.Default.Sensors,
            defaultExpanded = true,
            badge = "12",
            modifier = Modifier.padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Detection 1: Flock Safety Camera",
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Detection 2: Unknown Bluetooth Device",
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Detection 3: Suspicious WiFi Network",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun CollapsibleSectionWithBadgePreview() {
    FlockYouTheme {
        CollapsibleSection(
            title = "Threats",
            icon = Icons.Default.Warning,
            defaultExpanded = true,
            badge = "3",
            modifier = Modifier.padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "High threat devices detected in your area.",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun CollapsibleCardPreview() {
    FlockYouTheme {
        CollapsibleCard(
            title = "Cellular Monitor",
            icon = Icons.Default.CellTower,
            defaultExpanded = true,
            badge = "Active",
            modifier = Modifier.padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Network: 5G",
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Signal: -85 dBm",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun MultipleCollapsibleSectionsPreview() {
    FlockYouTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CollapsibleSection(
                title = "Bluetooth",
                icon = Icons.Default.Bluetooth,
                defaultExpanded = true,
                badge = "5"
            ) {
                Text(
                    text = "5 Bluetooth devices detected nearby.",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            CollapsibleSection(
                title = "WiFi Networks",
                icon = Icons.Default.Wifi,
                defaultExpanded = false,
                badge = "8"
            ) {
                Text(
                    text = "8 WiFi networks in range.",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            CollapsibleSection(
                title = "Cellular",
                icon = Icons.Default.CellTower,
                defaultExpanded = false
            ) {
                Text(
                    text = "Connected to tower XYZ.",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

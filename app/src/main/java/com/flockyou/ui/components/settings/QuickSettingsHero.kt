package com.flockyou.ui.components.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flockyou.data.ProtectionPreset

/**
 * Get primary color for a protection preset
 */
private fun ProtectionPreset.getPrimaryColor(): Color = when (this) {
    ProtectionPreset.ESSENTIAL -> Color(0xFF4CAF50)  // Green
    ProtectionPreset.BALANCED -> Color(0xFF2196F3)   // Blue
    ProtectionPreset.PARANOID -> Color(0xFFFF5722)   // Deep Orange
    ProtectionPreset.CUSTOM -> Color(0xFF9C27B0)     // Purple
}

/**
 * Get secondary color for a protection preset
 */
private fun ProtectionPreset.getSecondaryColor(): Color = when (this) {
    ProtectionPreset.ESSENTIAL -> Color(0xFF81C784)
    ProtectionPreset.BALANCED -> Color(0xFF64B5F6)
    ProtectionPreset.PARANOID -> Color(0xFFFF8A65)
    ProtectionPreset.CUSTOM -> Color(0xFFBA68C8)
}

/**
 * Hero section for the settings screen displaying protection level and quick toggles.
 *
 * Features:
 * - Large protection level display with animated gradient background
 * - Status badge showing scanning state
 * - Quick toggle chips for BLE, WiFi, Cellular, and Satellite scanning
 * - Summary of active patterns and scanning status
 */
@Composable
fun QuickSettingsHero(
    currentPreset: ProtectionPreset,
    onPresetClick: () -> Unit,
    bleEnabled: Boolean,
    wifiEnabled: Boolean,
    cellularEnabled: Boolean,
    satelliteEnabled: Boolean,
    onToggleBle: (Boolean) -> Unit,
    onToggleWifi: (Boolean) -> Unit,
    onToggleCellular: (Boolean) -> Unit,
    onToggleSatellite: (Boolean) -> Unit,
    activePatternCount: Int,
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    // Animate gradient colors based on preset
    val animatedPrimaryColor by animateColorAsState(
        targetValue = currentPreset.getPrimaryColor(),
        animationSpec = tween(durationMillis = 500),
        label = "primaryColor"
    )
    val animatedSecondaryColor by animateColorAsState(
        targetValue = currentPreset.getSecondaryColor(),
        animationSpec = tween(durationMillis = 500),
        label = "secondaryColor"
    )

    // Pulsing animation for active scanning
    val infiniteTransition = rememberInfiniteTransition(label = "scanPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Quick settings hero section showing ${currentPreset.displayName} protection level" },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            animatedPrimaryColor.copy(alpha = if (isScanning) pulseAlpha * 0.3f else 0.2f),
                            animatedSecondaryColor.copy(alpha = if (isScanning) pulseAlpha * 0.15f else 0.1f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // Protection Level Display
                ProtectionLevelSection(
                    currentPreset = currentPreset,
                    isScanning = isScanning,
                    animatedPrimaryColor = animatedPrimaryColor,
                    onPresetClick = onPresetClick
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Master Scan Toggles Row
                ScanTogglesRow(
                    bleEnabled = bleEnabled,
                    wifiEnabled = wifiEnabled,
                    cellularEnabled = cellularEnabled,
                    satelliteEnabled = satelliteEnabled,
                    onToggleBle = onToggleBle,
                    onToggleWifi = onToggleWifi,
                    onToggleCellular = onToggleCellular,
                    onToggleSatellite = onToggleSatellite
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Status Summary
                StatusSummarySection(
                    activePatternCount = activePatternCount,
                    isScanning = isScanning,
                    animatedPrimaryColor = animatedPrimaryColor
                )
            }
        }
    }
}

@Composable
private fun ProtectionLevelSection(
    currentPreset: ProtectionPreset,
    isScanning: Boolean,
    animatedPrimaryColor: Color,
    onPresetClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPresetClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Protection Level",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentPreset.displayName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = animatedPrimaryColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = currentPreset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            horizontalAlignment = Alignment.End
        ) {
            // Status Badge
            ScanningStatusBadge(
                isScanning = isScanning,
                primaryColor = animatedPrimaryColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Edit preset indicator
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Change protection preset",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(32.dp)
                        .padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun ScanningStatusBadge(
    isScanning: Boolean,
    primaryColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isScanning)
            primaryColor.copy(alpha = 0.15f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isScanning)
                            primaryColor.copy(alpha = dotAlpha)
                        else
                            Color(0xFF9E9E9E)
                    )
            )
            Text(
                text = if (isScanning) "Active" else "Paused",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (isScanning) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanTogglesRow(
    bleEnabled: Boolean,
    wifiEnabled: Boolean,
    cellularEnabled: Boolean,
    satelliteEnabled: Boolean,
    onToggleBle: (Boolean) -> Unit,
    onToggleWifi: (Boolean) -> Unit,
    onToggleCellular: (Boolean) -> Unit,
    onToggleSatellite: (Boolean) -> Unit
) {
    Column {
        Text(
            text = "Scan Modes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScanToggleChip(
                label = "BLE",
                icon = Icons.Default.Bluetooth,
                enabled = bleEnabled,
                onToggle = onToggleBle
            )
            ScanToggleChip(
                label = "WiFi",
                icon = Icons.Default.Wifi,
                enabled = wifiEnabled,
                onToggle = onToggleWifi
            )
            ScanToggleChip(
                label = "Cellular",
                icon = Icons.Default.CellTower,
                enabled = cellularEnabled,
                onToggle = onToggleCellular
            )
            ScanToggleChip(
                label = "Satellite",
                icon = Icons.Default.SatelliteAlt,
                enabled = satelliteEnabled,
                onToggle = onToggleSatellite
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanToggleChip(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    FilterChip(
        selected = enabled,
        onClick = { onToggle(!enabled) },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        modifier = Modifier.semantics {
            contentDescription = "$label scanning ${if (enabled) "enabled" else "disabled"}"
        }
    )
}

@Composable
private fun StatusSummarySection(
    activePatternCount: Int,
    isScanning: Boolean,
    animatedPrimaryColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Active Patterns Count
            StatusSummaryItem(
                icon = Icons.Default.Pattern,
                value = activePatternCount.toString(),
                label = "patterns active",
                color = MaterialTheme.colorScheme.tertiary
            )

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // Scanning Status
            StatusSummaryItem(
                icon = if (isScanning) Icons.Default.Radar else Icons.Default.PauseCircle,
                value = if (isScanning) "Scanning" else "Paused",
                label = "status",
                color = if (isScanning) animatedPrimaryColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusSummaryItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

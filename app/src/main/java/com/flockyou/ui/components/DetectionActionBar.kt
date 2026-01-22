package com.flockyou.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flockyou.ui.theme.FlockYouTheme

/**
 * Action colors for the detection action bar.
 * Using theme-consistent colors for safe (green), threat (red), and neutral actions.
 */
private val SafeActionColor = Color(0xFF4CAF50)  // Green - matches StatusActive
private val ThreatActionColor = Color(0xFFFF5252) // Red - matches theme error color

/**
 * Sticky bottom action bar for the detection detail sheet.
 * Provides quick access to primary actions: Mark Safe, Mark Threat, Share
 * and secondary actions via overflow menu: Navigate, Add Note, Export.
 *
 * @param onMarkSafe Callback when "Mark Safe" action is triggered
 * @param onMarkThreat Callback when "Mark Threat" action is triggered
 * @param onShare Callback when "Share" action is triggered
 * @param onNavigate Callback when "Navigate" action is triggered. Pass null if no location available.
 * @param onAddNote Callback when "Add Note" action is triggered
 * @param onExport Callback when "Export" action is triggered
 * @param isSafeEnabled Whether the "Mark Safe" button is enabled
 * @param isThreatEnabled Whether the "Mark Threat" button is enabled
 * @param modifier Modifier for the action bar
 */
@Composable
fun DetectionActionBar(
    onMarkSafe: () -> Unit,
    onMarkThreat: () -> Unit,
    onShare: () -> Unit,
    onNavigate: (() -> Unit)? = null,
    onAddNote: () -> Unit,
    onExport: () -> Unit,
    isSafeEnabled: Boolean = true,
    isThreatEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Helper function for haptic feedback
    fun performHaptic() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Primary action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Mark Safe button (green)
                ActionButton(
                    onClick = {
                        performHaptic()
                        onMarkSafe()
                    },
                    icon = Icons.Outlined.VerifiedUser,
                    label = "Safe",
                    contentDescription = "Mark as safe - false positive",
                    enabled = isSafeEnabled,
                    containerColor = SafeActionColor.copy(alpha = 0.15f),
                    contentColor = SafeActionColor,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.weight(1f)
                )

                // Mark Threat button (red)
                ActionButton(
                    onClick = {
                        performHaptic()
                        onMarkThreat()
                    },
                    icon = Icons.Outlined.Warning,
                    label = "Threat",
                    contentDescription = "Mark as confirmed threat",
                    enabled = isThreatEnabled,
                    containerColor = ThreatActionColor.copy(alpha = 0.15f),
                    contentColor = ThreatActionColor,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.weight(1f)
                )

                // Share button (neutral)
                ActionButton(
                    onClick = {
                        performHaptic()
                        onShare()
                    },
                    icon = Icons.Outlined.Share,
                    label = "Share",
                    contentDescription = "Share detection details",
                    enabled = true,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.weight(1f)
                )
            }

            // Overflow menu for secondary actions
            Box {
                IconButton(
                    onClick = {
                        performHaptic()
                        showOverflowMenu = true
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "More actions menu"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    // Navigate action (only if location available)
                    if (onNavigate != null) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Navigation,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Navigate to location")
                                }
                            },
                            onClick = {
                                performHaptic()
                                showOverflowMenu = false
                                onNavigate()
                            }
                        )
                    }

                    // Add Note action
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.NoteAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Add note")
                            }
                        },
                        onClick = {
                            performHaptic()
                            showOverflowMenu = false
                            onAddNote()
                        }
                    )

                    // Export action
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Export detection")
                            }
                        },
                        onClick = {
                            performHaptic()
                            showOverflowMenu = false
                            onExport()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Individual action button with icon and label.
 * Designed for compact display with icon above label.
 */
@Composable
private fun ActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    disabledContainerColor: Color,
    disabledContentColor: Color,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.semantics {
                this.contentDescription = contentDescription
            }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ================================
// Preview Composables
// ================================

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun DetectionActionBarPreview() {
    FlockYouTheme {
        DetectionActionBar(
            onMarkSafe = {},
            onMarkThreat = {},
            onShare = {},
            onNavigate = {},
            onAddNote = {},
            onExport = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun DetectionActionBarNoLocationPreview() {
    FlockYouTheme {
        DetectionActionBar(
            onMarkSafe = {},
            onMarkThreat = {},
            onShare = {},
            onNavigate = null, // No location available
            onAddNote = {},
            onExport = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun DetectionActionBarDisabledPreview() {
    FlockYouTheme {
        DetectionActionBar(
            onMarkSafe = {},
            onMarkThreat = {},
            onShare = {},
            onNavigate = {},
            onAddNote = {},
            onExport = {},
            isSafeEnabled = false,
            isThreatEnabled = false
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun DetectionActionBarSafeOnlyPreview() {
    FlockYouTheme {
        DetectionActionBar(
            onMarkSafe = {},
            onMarkThreat = {},
            onShare = {},
            onNavigate = null,
            onAddNote = {},
            onExport = {},
            isSafeEnabled = true,
            isThreatEnabled = false
        )
    }
}

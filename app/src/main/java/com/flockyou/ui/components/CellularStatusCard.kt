package com.flockyou.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flockyou.service.CellularMonitor

/**
 * Card displaying current cellular network status and any anomalies
 */
@Composable
fun CellularStatusCard(
    cellStatus: CellularMonitor.CellStatus?,
    anomalies: List<CellularMonitor.CellularAnomaly>,
    isMonitoring: Boolean,
    modifier: Modifier = Modifier
) {
    val hasAnomalies = anomalies.isNotEmpty()
    val criticalAnomalies = anomalies.filter { 
        it.severity == com.flockyou.data.model.ThreatLevel.CRITICAL 
    }
    val hasCritical = criticalAnomalies.isNotEmpty()
    
    // Pulsing animation for critical alerts
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    val cardColor by animateColorAsState(
        targetValue = when {
            hasCritical -> MaterialTheme.colorScheme.errorContainer.copy(alpha = pulseAlpha)
            hasAnomalies -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
        label = "cardColor"
    )
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = null,
                        tint = if (hasCritical) MaterialTheme.colorScheme.error 
                               else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Cellular Monitor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Monitoring status indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isMonitoring) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isMonitoring) "Active" else "Inactive",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (cellStatus != null) {
                // Network info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Network type and generation
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NetworkGenerationBadge(cellStatus.networkGeneration)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = cellStatus.networkType,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = cellStatus.operator ?: "Unknown Operator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // Signal strength
                    Column(horizontalAlignment = Alignment.End) {
                        SignalBarsIndicator(bars = cellStatus.signalBars)
                        Text(
                            text = "${cellStatus.signalStrength} dBm",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Cell details (collapsible)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CellDetail("Cell ID", cellStatus.cellId.take(12))
                    cellStatus.lac?.let { CellDetail("LAC", it.toString()) }
                    cellStatus.mcc?.let { CellDetail("MCC", it.toString()) }
                    cellStatus.mnc?.let { CellDetail("MNC", it.toString()) }
                }
                
                // Cell status
                if (!cellStatus.isKnownCell) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.NewReleases,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "New cell tower - not previously seen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
                
                if (cellStatus.isRoaming) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠️ Roaming",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Text(
                    text = if (isMonitoring) "Acquiring cell info..." else "Enable cellular monitoring in settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Anomalies section
            if (anomalies.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "⚠️ ANOMALIES DETECTED",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                anomalies.take(3).forEach { anomaly ->
                    AnomalyItem(anomaly = anomaly)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                if (anomalies.size > 3) {
                    Text(
                        text = "+${anomalies.size - 3} more anomalies",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkGenerationBadge(generation: String) {
    val color = when (generation) {
        "5G" -> Color(0xFF2196F3)
        "4G" -> Color(0xFF4CAF50)
        "3G" -> Color(0xFFFFC107)
        "2G" -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }
    
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = generation,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SignalBarsIndicator(bars: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        (1..4).forEach { bar ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((4 + bar * 3).dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (bar <= bars) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
            )
        }
    }
}

@Composable
private fun CellDetail(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AnomalyItem(anomaly: CellularMonitor.CellularAnomaly) {
    val color = when (anomaly.severity) {
        com.flockyou.data.model.ThreatLevel.CRITICAL -> MaterialTheme.colorScheme.error
        com.flockyou.data.model.ThreatLevel.HIGH -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.tertiary
    }
    
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = anomaly.type.emoji,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anomaly.type.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = anomaly.description.take(80) + if (anomaly.description.length > 80) "..." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

package com.flockyou.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.AiModelStatus
import com.flockyou.data.model.Detection

/**
 * Card component for displaying AI-powered analysis of a detection.
 */
@Composable
fun AiAnalysisCard(
    detection: Detection,
    isAiEnabled: Boolean,
    isAnalyzing: Boolean,
    analysisResult: AiAnalysisResult?,
    modelStatus: AiModelStatus,
    onAnalyze: () -> Unit,
    onConfigureAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI Analysis",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (!isAiEnabled) {
                    TextButton(onClick = onConfigureAi) {
                        Text("Configure")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                !isAiEnabled -> {
                    // AI not enabled
                    AiDisabledContent(onConfigureAi = onConfigureAi)
                }
                modelStatus !is AiModelStatus.Ready -> {
                    // Model not ready
                    ModelNotReadyContent(
                        modelStatus = modelStatus,
                        onConfigureAi = onConfigureAi
                    )
                }
                isAnalyzing -> {
                    // Currently analyzing
                    AnalyzingContent()
                }
                analysisResult != null -> {
                    // Show analysis result
                    AnalysisResultContent(result = analysisResult)
                }
                else -> {
                    // Ready to analyze
                    ReadyToAnalyzeContent(
                        detection = detection,
                        onAnalyze = onAnalyze
                    )
                }
            }
        }
    }
}

@Composable
private fun AiDisabledContent(onConfigureAi: () -> Unit) {
    Column {
        Text(
            text = "AI analysis can provide detailed explanations of detected devices and personalized threat assessments.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onConfigureAi,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enable AI Analysis")
        }
    }
}

@Composable
private fun ModelNotReadyContent(
    modelStatus: AiModelStatus,
    onConfigureAi: () -> Unit
) {
    Column {
        val icon = when (modelStatus) {
            is AiModelStatus.NotDownloaded -> Icons.Default.CloudDownload
            is AiModelStatus.Downloading -> Icons.Default.CloudSync
            is AiModelStatus.Initializing -> Icons.Default.Refresh
            is AiModelStatus.Error -> Icons.Default.Error
            else -> Icons.Default.Info
        }
        val message: String = when (modelStatus) {
            is AiModelStatus.NotDownloaded -> "Download the AI model to enable analysis"
            is AiModelStatus.Downloading -> "Model downloading... ${modelStatus.progressPercent}%"
            is AiModelStatus.Initializing -> "Initializing AI model..."
            is AiModelStatus.Error -> modelStatus.message
            else -> "Model not ready"
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (modelStatus is AiModelStatus.Error)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (modelStatus is AiModelStatus.NotDownloaded || modelStatus is AiModelStatus.Error) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onConfigureAi,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Configure AI")
            }
        }

        if (modelStatus is AiModelStatus.Downloading) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AnalyzingContent() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Analyzing detection...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AnalysisResultContent(result: AiAnalysisResult) {
    Column {
        if (result.success && result.analysis != null) {
            // False positive banner (if applicable)
            if (result.isFalsePositive && result.falsePositiveBanner != null) {
                FalsePositiveBanner(
                    bannerMessage = result.falsePositiveBanner,
                    confidence = result.falsePositiveConfidence,
                    reasons = result.falsePositiveReasons
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Analysis text
            Text(
                text = result.analysis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Recommendations
            if (result.recommendations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Recommendations:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                result.recommendations.forEach { recommendation ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = recommendation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Metadata
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (result.wasOnDevice) "On-device" else "Cloud API",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = "${result.processingTimeMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            // Error state
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = result.error ?: "Analysis failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Banner component showing false positive detection explanation.
 * Displayed when AI analysis determines a detection is likely a false positive.
 */
@Composable
fun FalsePositiveBanner(
    bannerMessage: String,
    confidence: Float,
    reasons: List<String>,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val confidenceText = when {
        confidence >= 0.8f -> "High confidence"
        confidence >= 0.6f -> "Likely"
        else -> "Possibly"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$confidenceText False Positive",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (reasons.isNotEmpty()) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bannerMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f)
            )

            // Expandable reasons section
            AnimatedVisibility(
                visible = isExpanded && reasons.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Analysis factors:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    reasons.forEach { reason ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyToAnalyzeContent(
    detection: Detection,
    onAnalyze: () -> Unit
) {
    Column {
        Text(
            text = "Get AI-powered insights about this ${detection.deviceType.displayName}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onAnalyze,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Analyze with AI")
        }
    }
}

/**
 * Compact AI analysis button for use in detection cards.
 */
@Composable
fun AiAnalyzeButton(
    isEnabled: Boolean,
    isAnalyzing: Boolean,
    hasResult: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        enabled = isEnabled && !isAnalyzing,
        modifier = modifier
    ) {
        when {
            isAnalyzing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
            hasResult -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Analysis complete",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            isEnabled -> {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Analyze with AI",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = "AI disabled",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Threat assessment summary card for the main screen.
 */
@Composable
fun ThreatAssessmentCard(
    isAiEnabled: Boolean,
    isAnalyzing: Boolean,
    assessment: String?,
    totalDetections: Int,
    criticalCount: Int,
    highCount: Int,
    onGenerateAssessment: () -> Unit,
    onConfigureAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Threat Assessment",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                !isAiEnabled -> {
                    Text(
                        text = "Enable AI to get personalized threat assessments based on your detected devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onConfigureAi) {
                        Text("Enable AI Analysis")
                    }
                }
                isAnalyzing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Generating assessment...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                assessment != null -> {
                    Text(
                        text = assessment,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onGenerateAssessment) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh")
                    }
                }
                totalDetections > 0 -> {
                    Text(
                        text = "You have $totalDetections detections" +
                                if (criticalCount > 0) " including $criticalCount critical threats." else ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onGenerateAssessment,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Assessment")
                    }
                }
                else -> {
                    Text(
                        text = "No detections yet. Start scanning to find surveillance devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

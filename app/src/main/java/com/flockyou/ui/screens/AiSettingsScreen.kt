package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.AiModel
import com.flockyou.data.AiModelStatus
import com.flockyou.data.AiSettings
import com.flockyou.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.aiSettings.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Analysis") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Privacy Notice
            item {
                PrivacyNoticeCard()
            }

            // Main Enable Toggle
            item {
                AiEnableCard(
                    enabled = settings.enabled,
                    modelStatus = modelStatus,
                    onEnabledChange = { viewModel.setEnabled(it) }
                )
            }

            // Model Status & Download
            item {
                ModelStatusCard(
                    modelStatus = modelStatus,
                    downloadProgress = downloadProgress,
                    settings = settings,
                    onDownload = { viewModel.downloadModel() },
                    onDelete = { viewModel.deleteModel() },
                    onInitialize = { viewModel.initializeModel() }
                )
            }

            // Analysis Capabilities
            if (settings.enabled) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader(title = "Analysis Capabilities")
                }

                item {
                    CapabilitiesCard(
                        settings = settings,
                        onAnalyzeDetectionsChange = { viewModel.setAnalyzeDetections(it) },
                        onThreatAssessmentsChange = { viewModel.setGenerateThreatAssessments(it) },
                        onIdentifyUnknownChange = { viewModel.setIdentifyUnknownDevices(it) },
                        onAutoAnalyzeChange = { viewModel.setAutoAnalyzeNewDetections(it) }
                    )
                }

                // Performance Settings
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader(title = "Performance")
                }

                item {
                    PerformanceCard(
                        settings = settings,
                        onGpuChange = { viewModel.setUseGpuAcceleration(it) }
                    )
                }

                // Test Analysis
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader(title = "Test")
                }

                item {
                    TestAnalysisCard(
                        isAnalyzing = isAnalyzing,
                        modelStatus = modelStatus,
                        testResult = testResult,
                        onTest = { viewModel.testAnalysis() },
                        onClearResult = { viewModel.clearTestResult() }
                    )
                }
            }

            // Info Card
            item {
                Spacer(modifier = Modifier.height(8.dp))
                InfoCard()
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun PrivacyNoticeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "100% Local & Private",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "All AI analysis runs entirely on your device. Your detection data never leaves your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AiEnableCard(
    enabled: Boolean,
    modelStatus: AiModelStatus,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI-Powered Analysis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        !enabled -> "Enable to get intelligent threat insights"
                        modelStatus is AiModelStatus.Ready -> "Ready for analysis"
                        modelStatus is AiModelStatus.Downloading -> "Downloading model..."
                        else -> "Using rule-based analysis"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun ModelStatusCard(
    modelStatus: AiModelStatus,
    downloadProgress: Int,
    settings: AiSettings,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onInitialize: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (modelStatus) {
                        is AiModelStatus.Ready -> Icons.Default.CheckCircle
                        is AiModelStatus.Downloading -> Icons.Default.Download
                        is AiModelStatus.Initializing -> Icons.Default.Refresh
                        is AiModelStatus.Error -> Icons.Default.Error
                        else -> Icons.Default.Memory
                    },
                    contentDescription = null,
                    tint = when (modelStatus) {
                        is AiModelStatus.Ready -> MaterialTheme.colorScheme.primary
                        is AiModelStatus.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "On-Device Model",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when (modelStatus) {
                            is AiModelStatus.NotDownloaded -> "Optional: Gemini Nano (~300 MB)"
                            is AiModelStatus.Downloading -> "Downloading... $downloadProgress%"
                            is AiModelStatus.Initializing -> "Initializing..."
                            is AiModelStatus.Ready -> if (settings.modelSizeMb > 0) "Ready (${settings.modelSizeMb} MB)" else "Ready (rule-based)"
                            is AiModelStatus.Error -> modelStatus.message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (modelStatus is AiModelStatus.Error)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Download progress bar
            AnimatedVisibility(visible = modelStatus is AiModelStatus.Downloading) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = downloadProgress / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info about rule-based fallback
            if (modelStatus is AiModelStatus.NotDownloaded) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Rule-based analysis works without downloading. The LLM model is optional and provides enhanced natural language explanations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (modelStatus) {
                    is AiModelStatus.NotDownloaded, is AiModelStatus.Error -> {
                        OutlinedButton(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download LLM")
                        }
                    }
                    is AiModelStatus.Ready -> {
                        if (settings.modelSizeMb > 0) {
                            OutlinedButton(
                                onClick = onInitialize,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reinitialize")
                            }
                            OutlinedButton(
                                onClick = onDelete,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                    }
                    is AiModelStatus.Downloading, is AiModelStatus.Initializing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilitiesCard(
    settings: AiSettings,
    onAnalyzeDetectionsChange: (Boolean) -> Unit,
    onThreatAssessmentsChange: (Boolean) -> Unit,
    onIdentifyUnknownChange: (Boolean) -> Unit,
    onAutoAnalyzeChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            CapabilityToggle(
                icon = Icons.Default.Analytics,
                title = "Detection Analysis",
                description = "Explain detected devices and their capabilities",
                checked = settings.analyzeDetections,
                onCheckedChange = onAnalyzeDetectionsChange
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.Security,
                title = "Threat Assessments",
                description = "Generate contextual risk analysis",
                checked = settings.generateThreatAssessments,
                onCheckedChange = onThreatAssessmentsChange
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.DeviceUnknown,
                title = "Device Identification",
                description = "Help identify unknown devices",
                checked = settings.identifyUnknownDevices,
                onCheckedChange = onIdentifyUnknownChange
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.AutoAwesome,
                title = "Auto-Analyze New Detections",
                description = "Automatically analyze when new devices are found",
                checked = settings.autoAnalyzeNewDetections,
                onCheckedChange = onAutoAnalyzeChange
            )
        }
    }
}

@Composable
private fun CapabilityToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PerformanceCard(
    settings: AiSettings,
    onGpuChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // GPU Acceleration
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = if (settings.useGpuAcceleration) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GPU Acceleration",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Use GPU for faster LLM inference (when available)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.useGpuAcceleration,
                    onCheckedChange = onGpuChange
                )
            }
        }
    }
}

@Composable
private fun TestAnalysisCard(
    isAnalyzing: Boolean,
    modelStatus: AiModelStatus,
    testResult: String?,
    onTest: () -> Unit,
    onClearResult: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Test AI Analysis",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Run a test analysis to see how the AI analyzes a sample detection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onTest,
                enabled = !isAnalyzing && modelStatus is AiModelStatus.Ready,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyzing...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Test Analysis")
                }
            }

            // Show test result
            AnimatedVisibility(visible = testResult != null) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Analysis Result",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                IconButton(
                                    onClick = onClearResult,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = testResult ?: "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "About Local AI Analysis",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "All analysis runs 100% on your device. No data is ever sent to any server.\n\n" +
                        "Two analysis modes are available:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            AiModel.entries.forEach { model ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (model.sizeMb > 0) "${model.displayName} (~${model.sizeMb} MB)" else model.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

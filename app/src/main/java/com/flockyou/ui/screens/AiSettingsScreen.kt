package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.AiModel
import com.flockyou.data.AiModelStatus
import com.flockyou.data.AiSettings
import com.flockyou.ui.components.SectionHeader
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

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
                        onGpuChange = { viewModel.setUseGpuAcceleration(it) },
                        onMaxTokensChange = { viewModel.setMaxTokens(it) },
                        onTemperatureChange = { viewModel.setTemperature(it) }
                    )
                }

                // Cloud Fallback
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader(title = "Cloud Fallback (Optional)")
                }

                item {
                    CloudFallbackCard(
                        settings = settings,
                        onFallbackChange = { viewModel.setFallbackToCloud(it) },
                        onApiKeyChange = { viewModel.setCloudApiKey(it) }
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
                        onTest = { viewModel.testAnalysis() }
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
                        else -> "Download model to begin"
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
                        is AiModelStatus.Downloading -> Icons.Default.CloudDownload
                        is AiModelStatus.Initializing -> Icons.Default.Refresh
                        is AiModelStatus.Error -> Icons.Default.Error
                        else -> Icons.Default.CloudOff
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
                            is AiModelStatus.NotDownloaded -> "Not downloaded (~300 MB)"
                            is AiModelStatus.Downloading -> "Downloading... $downloadProgress%"
                            is AiModelStatus.Initializing -> "Initializing..."
                            is AiModelStatus.Ready -> "Ready (${settings.modelSizeMb} MB)"
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
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (modelStatus) {
                    is AiModelStatus.NotDownloaded, is AiModelStatus.Error -> {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download Model")
                        }
                    }
                    is AiModelStatus.Ready -> {
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.Security,
                title = "Threat Assessments",
                description = "Generate contextual risk analysis",
                checked = settings.generateThreatAssessments,
                onCheckedChange = onThreatAssessmentsChange
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.DeviceUnknown,
                title = "Device Identification",
                description = "Help identify unknown devices",
                checked = settings.identifyUnknownDevices,
                onCheckedChange = onIdentifyUnknownChange
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
    onGpuChange: (Boolean) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onTemperatureChange: (Int) -> Unit
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
                        text = "Use GPU for faster inference",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.useGpuAcceleration,
                    onCheckedChange = onGpuChange
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Max Tokens Slider
            Text(
                text = "Response Length: ${settings.maxTokens} tokens",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.maxTokens.toFloat(),
                onValueChange = { onMaxTokensChange(it.toInt()) },
                valueRange = 64f..512f,
                steps = 7
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Temperature Slider
            Text(
                text = "Creativity: ${settings.temperatureTenths / 10f}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Lower = more factual, Higher = more creative",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = settings.temperatureTenths.toFloat(),
                onValueChange = { onTemperatureChange(it.toInt()) },
                valueRange = 0f..10f,
                steps = 9
            )
        }
    }
}

@Composable
private fun CloudFallbackCard(
    settings: AiSettings,
    onFallbackChange: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf(settings.cloudApiKey) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = if (settings.fallbackToCloudApi) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use Gemini API Fallback",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Use cloud API when on-device unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.fallbackToCloudApi,
                    onCheckedChange = onFallbackChange
                )
            }

            AnimatedVisibility(visible = settings.fallbackToCloudApi) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cloud API sends detection data to Google servers. On-device analysis is more private.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            onApiKeyChange(it)
                        },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("Enter your API key") },
                        visualTransformation = if (showApiKey)
                            VisualTransformation.None
                        else
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey)
                                        Icons.Default.VisibilityOff
                                    else
                                        Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "Hide" else "Show"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Get an API key from aistudio.google.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TestAnalysisCard(
    isAnalyzing: Boolean,
    modelStatus: AiModelStatus,
    onTest: () -> Unit
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
                text = "Run a test analysis to verify the AI is working correctly.",
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
                    text = "About AI Analysis",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "AI analysis uses Google's Gemini Nano model running entirely on your device. " +
                        "Your detection data never leaves your phone unless you enable cloud fallback.\n\n" +
                        "The AI can help explain detected surveillance devices, assess threat levels, " +
                        "and identify unknown devices based on their wireless characteristics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Recommended models:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(4.dp))

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
                            text = "${model.displayName} (~${model.sizeMb} MB)",
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

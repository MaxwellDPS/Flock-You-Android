package com.flockyou.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.AiModel
import com.flockyou.data.AiModelStatus
import com.flockyou.data.AiSettings
import com.flockyou.data.ApiStability
import com.flockyou.data.LlmEnginePreference
import com.flockyou.ai.DetectionAnalyzer
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
    val availableModels by viewModel.availableModels.collectAsState()
    val deviceCapabilities by viewModel.deviceCapabilities.collectAsState()
    val selectedModelForDownload by viewModel.selectedModelForDownload.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val activeEngine by viewModel.activeEngine.collectAsState()

    // Model selection dialog
    var showModelSelector by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var modelForImport by remember { mutableStateOf<AiModel?>(null) }

    // File picker for model import
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            modelForImport?.let { model ->
                viewModel.importModel(selectedUri, model)
                modelForImport = null
            }
        }
    }

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
                    currentModel = AiModel.fromId(settings.selectedModel),
                    onEnabledChange = { viewModel.setEnabled(it) }
                )
            }

            // Model Selection
            item {
                ModelSelectionCard(
                    settings = settings,
                    modelStatus = modelStatus,
                    downloadProgress = downloadProgress,
                    availableModels = availableModels,
                    deviceCapabilities = deviceCapabilities,
                    downloadError = downloadError,
                    downloadedModels = downloadedModels,
                    activeEngineName = activeEngine,
                    onSelectModel = { showModelSelector = true },
                    onDownload = { viewModel.downloadModel(AiModel.fromId(settings.selectedModel)) },
                    onDelete = { viewModel.deleteModel() },
                    onInitialize = { viewModel.initializeModel() },
                    onImport = { showImportDialog = true },
                    onClearError = { viewModel.clearDownloadError() },
                    onCancelDownload = { viewModel.cancelDownload() }
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

                // Advanced Features
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader(title = "Advanced Features")
                }

                item {
                    AdvancedFeaturesCard(
                        settings = settings,
                        onContextualAnalysisChange = { viewModel.setContextualAnalysis(it) },
                        onBatchAnalysisChange = { viewModel.setBatchAnalysis(it) },
                        onTrackFeedbackChange = { viewModel.setTrackFeedback(it) },
                        onFalsePositiveFilteringChange = { viewModel.setFalsePositiveFiltering(it) }
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
                        deviceCapabilities = deviceCapabilities,
                        onGpuChange = { viewModel.setUseGpuAcceleration(it) },
                        onNpuChange = { viewModel.setUseNpuAcceleration(it) }
                    )
                }

                // LLM Engine Selection
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader(title = "LLM Engine")
                }

                item {
                    LlmEngineSelectionCard(
                        currentEngine = settings.preferredEngine,
                        deviceCapabilities = deviceCapabilities,
                        onEngineChange = { viewModel.setPreferredEngine(it) }
                    )
                }

                // Hugging Face Token for authenticated downloads
                item {
                    HuggingFaceTokenCard(
                        token = settings.huggingFaceToken,
                        onTokenChange = { viewModel.setHuggingFaceToken(it) }
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
                        onCancel = { viewModel.cancelAnalysis() },
                        onClearResult = { viewModel.clearTestResult() }
                    )
                }
            }

            // Info Card
            item {
                Spacer(modifier = Modifier.height(8.dp))
                InfoCard(availableModels = availableModels)
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Model Selection Dialog
    if (showModelSelector) {
        ModelSelectorDialog(
            availableModels = availableModels,
            currentModelId = settings.selectedModel,
            deviceCapabilities = deviceCapabilities,
            downloadedModels = downloadedModels,
            onSelectModel = { model ->
                viewModel.selectModel(model)
                showModelSelector = false
            },
            onDownloadModel = { model ->
                viewModel.selectModelForDownload(model)
                viewModel.downloadModel(model)
                showModelSelector = false
            },
            onDismiss = { showModelSelector = false }
        )
    }

    // Download confirmation dialog - only show when not already downloading
    val isDownloading by viewModel.isDownloading.collectAsState()
    if (!isDownloading) {
        selectedModelForDownload?.let { model ->
            if (model != AiModel.RULE_BASED) {
                AlertDialog(
                    onDismissRequest = { viewModel.clearSelectedModel() },
                    title = { Text("Download ${model.displayName}?") },
                    text = {
                        Column {
                            Text("This will download approximately ${model.sizeMb} MB.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                model.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Models download from public repositories. No authentication required.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = { viewModel.downloadModel(model) }) {
                            Text("Download")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.clearSelectedModel() }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }

    // Import Model Dialog
    if (showImportDialog) {
        ImportModelDialog(
            availableModels = availableModels.filter {
                it != AiModel.RULE_BASED && it != AiModel.GEMINI_NANO
            },
            onSelectModel = { model ->
                modelForImport = model
                showImportDialog = false
                filePickerLauncher.launch("*/*")
            },
            onDismiss = { showImportDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportModelDialog(
    availableModels: List<AiModel>,
    onSelectModel: (AiModel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Import Model")
                Text(
                    text = "Select which model you're importing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Download a .task or .bin model file, then select it here to import.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                availableModels.forEach { model ->
                    OutlinedCard(
                        onClick = { onSelectModel(model) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${model.sizeMb} MB • ${model.quantization}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Select file",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
                contentDescription = "Privacy protection",
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
                    text = "All AI analysis runs entirely on your device. Your detection data never leaves your phone - no cloud, no servers, no tracking.",
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
    currentModel: AiModel,
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
                contentDescription = "AI Analysis",
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
                        modelStatus is AiModelStatus.Ready -> "Using ${currentModel.displayName}"
                        modelStatus is AiModelStatus.Downloading -> "Downloading model..."
                        modelStatus is AiModelStatus.Initializing -> "Initializing..."
                        else -> "Ready"
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
private fun ModelSelectionCard(
    settings: AiSettings,
    modelStatus: AiModelStatus,
    downloadProgress: Int,
    availableModels: List<AiModel>,
    deviceCapabilities: DetectionAnalyzer.DeviceCapabilities?,
    downloadError: String?,
    downloadedModels: Set<String>,
    activeEngineName: String,
    onSelectModel: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onInitialize: () -> Unit,
    onImport: () -> Unit,
    onClearError: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val currentModel = AiModel.fromId(settings.selectedModel)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Current model info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = settings.enabled) { onSelectModel() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (modelStatus) {
                        is AiModelStatus.Ready -> Icons.Default.CheckCircle
                        is AiModelStatus.Downloading -> Icons.Default.Download
                        is AiModelStatus.Initializing -> Icons.Default.Refresh
                        is AiModelStatus.Error -> Icons.Default.Error
                        else -> Icons.Default.Memory
                    },
                    contentDescription = when (modelStatus) {
                        is AiModelStatus.Ready -> "Model ready"
                        is AiModelStatus.Downloading -> "Downloading model"
                        is AiModelStatus.Initializing -> "Initializing model"
                        is AiModelStatus.Error -> "Model error"
                        else -> "Model not downloaded"
                    },
                    tint = when (modelStatus) {
                        is AiModelStatus.Ready -> MaterialTheme.colorScheme.primary
                        is AiModelStatus.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentModel.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when (modelStatus) {
                            is AiModelStatus.NotDownloaded -> "Not downloaded"
                            is AiModelStatus.Downloading -> "Downloading... $downloadProgress%"
                            is AiModelStatus.Initializing -> "Initializing..."
                            is AiModelStatus.Ready -> if (settings.modelSizeMb > 0) "${settings.modelSizeMb} MB" else "Ready"
                            is AiModelStatus.Error -> modelStatus.message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (modelStatus is AiModelStatus.Error)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (settings.enabled) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Select model",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Download progress with cancel button
            AnimatedVisibility(visible = modelStatus is AiModelStatus.Downloading) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            LinearProgressIndicator(
                                progress = downloadProgress / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Downloading... $downloadProgress%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = onCancelDownload,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel download",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Show active engine info when ready
            AnimatedVisibility(visible = modelStatus is AiModelStatus.Ready && settings.enabled) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Engine active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Active: $activeEngineName",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Model capabilities
            if (currentModel != AiModel.RULE_BASED && settings.enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    currentModel.capabilities.take(3).forEach { capability ->
                        SuggestionChip(
                            onClick = { },
                            label = { Text(capability, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Device capabilities info
            deviceCapabilities?.let { caps ->
                if (settings.enabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // NPU status
                    if (caps.hasNpu) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = "Tensor NPU available",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Tensor NPU available",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    // AICore status (for Gemini Nano)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = if (caps.hasAiCore) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = if (caps.hasAiCore) "Google AICore ready" else "AICore not installed",
                            tint = if (caps.hasAiCore) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (caps.hasAiCore) "Google AICore ready" else "AICore not installed",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (caps.hasAiCore) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Download error message
            AnimatedVisibility(visible = downloadError != null) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Download error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = downloadError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onClearError,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Action buttons
            if (settings.enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        modelStatus is AiModelStatus.Downloading || modelStatus is AiModelStatus.Initializing -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        currentModel == AiModel.RULE_BASED -> {
                            OutlinedButton(
                                onClick = onSelectModel,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download LLM")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download LLM")
                            }
                            OutlinedButton(onClick = onImport) {
                                Icon(Icons.Default.FileOpen, contentDescription = "Import model")
                            }
                        }
                        // Gemini Nano: show download button if not ready (managed by AICore, sizeMb=0)
                        currentModel == AiModel.GEMINI_NANO && modelStatus !is AiModelStatus.Ready -> {
                            OutlinedButton(
                                onClick = onDownload,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download Gemini Nano")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download Gemini Nano")
                            }
                        }
                        // Gemini Nano: show reinitialize when ready
                        currentModel == AiModel.GEMINI_NANO && modelStatus is AiModelStatus.Ready -> {
                            OutlinedButton(
                                onClick = onInitialize,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reinitialize model")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reinitialize")
                            }
                        }
                        modelStatus is AiModelStatus.Ready && settings.modelSizeMb > 0 -> {
                            OutlinedButton(
                                onClick = onInitialize,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reinitialize model")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reinitialize")
                            }
                            OutlinedButton(
                                onClick = onDelete,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete model")
                            }
                        }
                        modelStatus is AiModelStatus.NotDownloaded || modelStatus is AiModelStatus.Error -> {
                            OutlinedButton(
                                onClick = onDownload,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download model")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download")
                            }
                            OutlinedButton(onClick = onImport) {
                                Icon(Icons.Default.FileOpen, contentDescription = "Import model")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectorDialog(
    availableModels: List<AiModel>,
    currentModelId: String,
    deviceCapabilities: DetectionAnalyzer.DeviceCapabilities?,
    downloadedModels: Set<String>,
    onSelectModel: (AiModel) -> Unit,
    onDownloadModel: (AiModel) -> Unit,
    onDismiss: () -> Unit
) {
    // Categorize models
    val builtInModels = availableModels.filter { it == AiModel.RULE_BASED }
    val googleAiModels = availableModels.filter { it == AiModel.GEMINI_NANO }
    val downloadableModels = availableModels.filter {
        it != AiModel.RULE_BASED && it != AiModel.GEMINI_NANO
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Select AI Engine")
                Text(
                    text = "Choose how detection analysis is performed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Built-in section
                if (builtInModels.isNotEmpty()) {
                    item {
                        EngineCategoryHeader(
                            title = "Built-in",
                            subtitle = "No download required",
                            icon = Icons.Default.CheckCircle
                        )
                    }
                    items(builtInModels) { model ->
                        EngineOptionCard(
                            model = model,
                            isSelected = model.id == currentModelId,
                            isRecommended = true,
                            recommendedReason = "Works everywhere",
                            onSelect = { onSelectModel(model) }
                        )
                    }
                }

                // Google AI section (Gemini Nano via ML Kit)
                if (googleAiModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        EngineCategoryHeader(
                            title = "Google AI (On-Device)",
                            subtitle = "Gemini Nano via ML Kit GenAI • Pixel 8+ only",
                            icon = Icons.Default.AutoAwesome
                        )
                    }
                    items(googleAiModels) { model ->
                        val hasAiCore = deviceCapabilities?.hasAiCore == true
                        val isPixel8 = deviceCapabilities?.isPixel8OrNewer == true
                        val isAvailable = hasAiCore && isPixel8
                        EngineOptionCard(
                            model = model,
                            isSelected = model.id == currentModelId,
                            isRecommended = isAvailable,
                            recommendedReason = if (isAvailable) "Best for Pixel 8+" else null,
                            isAvailable = isAvailable,
                            unavailableReason = when {
                                !isPixel8 -> "Requires Pixel 8+ device"
                                !hasAiCore -> "AICore not installed"
                                else -> null
                            },
                            onSelect = { if (isAvailable) onSelectModel(model) }
                        )
                    }
                }

                // Downloadable Models section (MediaPipe format)
                if (downloadableModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        EngineCategoryHeader(
                            title = "Downloadable Models",
                            subtitle = "MediaPipe format • Runs on any device",
                            icon = Icons.Default.Download
                        )
                    }

                    // Small models (< 800MB)
                    val smallModels = downloadableModels.filter { it.sizeMb < 800 }
                    val largeModels = downloadableModels.filter { it.sizeMb >= 800 }

                    if (smallModels.isNotEmpty()) {
                        item {
                            Text(
                                text = "Lightweight (< 600 MB)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                        items(smallModels) { model ->
                            val isDownloaded = downloadedModels.contains(model.id)
                            EngineOptionCard(
                                model = model,
                                isSelected = model.id == currentModelId,
                                isRecommended = model == AiModel.GEMMA3_1B,
                                recommendedReason = if (model == AiModel.GEMMA3_1B) "Recommended" else null,
                                showDownloadIcon = !isDownloaded,
                                isDownloaded = isDownloaded,
                                onSelect = {
                                    if (isDownloaded) onSelectModel(model) else onDownloadModel(model)
                                }
                            )
                        }
                    }

                    if (largeModels.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Full-Size (500+ MB)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                        items(largeModels) { model ->
                            val hasEnoughRam = (deviceCapabilities?.availableRamMb ?: 0) > model.sizeMb * 1.5
                            val isDownloaded = downloadedModels.contains(model.id)
                            EngineOptionCard(
                                model = model,
                                isSelected = model.id == currentModelId,
                                isRecommended = model == AiModel.GEMMA_2B_GPU && hasEnoughRam,
                                recommendedReason = if (model == AiModel.GEMMA_2B_GPU && hasEnoughRam) "Best quality" else null,
                                isAvailable = hasEnoughRam || isDownloaded,
                                unavailableReason = if (!hasEnoughRam && !isDownloaded) "Needs ${(model.sizeMb * 1.5).toInt()} MB RAM" else null,
                                showDownloadIcon = !isDownloaded,
                                isDownloaded = isDownloaded,
                                onSelect = {
                                    if (isDownloaded) onSelectModel(model)
                                    else if (hasEnoughRam) onDownloadModel(model)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun EngineCategoryHeader(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineOptionCard(
    model: AiModel,
    isSelected: Boolean,
    isRecommended: Boolean = false,
    recommendedReason: String? = null,
    isAvailable: Boolean = true,
    unavailableReason: String? = null,
    showDownloadIcon: Boolean = false,
    isDownloaded: Boolean = false,
    onSelect: () -> Unit
) {
    val alpha = if (isAvailable) 1f else 0.5f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isAvailable) { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                isRecommended && isAvailable -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
            }
        ),
        border = when {
            isSelected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isRecommended && isAvailable -> BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
            else -> null
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                        )
                        // API Stability indicator (Alpha/Beta)
                        if (model.apiStability != ApiStability.STABLE) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = when (model.apiStability) {
                                    ApiStability.ALPHA -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                    ApiStability.BETA -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = when (model.apiStability) {
                                        ApiStability.ALPHA -> "ALPHA"
                                        ApiStability.BETA -> "BETA"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when (model.apiStability) {
                                        ApiStability.ALPHA -> MaterialTheme.colorScheme.error
                                        ApiStability.BETA -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (model.requiresNpu) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "NPU",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (isRecommended && recommendedReason != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = recommendedReason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (model.sizeMb > 0) {
                        Text(
                            text = "${model.sizeMb} MB • ${model.quantization}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                        )
                    }
                }
                when {
                    isSelected -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    !isAvailable -> Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = "Unavailable",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    isDownloaded -> Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    showDownloadIcon -> Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download required",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (!isAvailable && unavailableReason != null) unavailableReason else model.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (!isAvailable) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )

            // Show capabilities for non-rule-based models
            if (model != AiModel.RULE_BASED && isAvailable) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    model.capabilities.take(3).forEach { cap ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = cap,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
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
                description = "Explain detected devices and their surveillance capabilities",
                checked = settings.analyzeDetections,
                onCheckedChange = onAnalyzeDetectionsChange
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.Security,
                title = "Threat Assessments",
                description = "Generate contextual risk analysis for your environment",
                checked = settings.generateThreatAssessments,
                onCheckedChange = onThreatAssessmentsChange
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.DeviceUnknown,
                title = "Device Identification",
                description = "Help identify unknown surveillance devices",
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
private fun AdvancedFeaturesCard(
    settings: AiSettings,
    onContextualAnalysisChange: (Boolean) -> Unit,
    onBatchAnalysisChange: (Boolean) -> Unit,
    onTrackFeedbackChange: (Boolean) -> Unit,
    onFalsePositiveFilteringChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            CapabilityToggle(
                icon = Icons.Default.VerifiedUser,
                title = "False Positive Filtering",
                description = "Auto-detect and flag likely false positives with AI explanation",
                checked = settings.enableFalsePositiveFiltering,
                onCheckedChange = onFalsePositiveFilteringChange
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.Timeline,
                title = "Contextual Analysis",
                description = "Include location patterns, time correlation, and historical data",
                checked = settings.enableContextualAnalysis,
                onCheckedChange = onContextualAnalysisChange
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.Map,
                title = "Batch Analysis",
                description = "Enable surveillance density mapping and cluster detection",
                checked = settings.enableBatchAnalysis,
                onCheckedChange = onBatchAnalysisChange
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            CapabilityToggle(
                icon = Icons.Default.ThumbUp,
                title = "Feedback Learning",
                description = "Track feedback to improve analysis accuracy over time",
                checked = settings.trackAnalysisFeedback,
                onCheckedChange = onTrackFeedbackChange
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
            contentDescription = title,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmEngineSelectionCard(
    currentEngine: String,
    deviceCapabilities: DetectionAnalyzer.DeviceCapabilities?,
    onEngineChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentPreference = LlmEnginePreference.entries.find { it.id == currentEngine }
        ?: LlmEnginePreference.AUTO

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Select which LLM engine to use for AI analysis",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Engine selection dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = currentPreference.displayName,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("LLM Engine") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    LlmEnginePreference.entries.forEach { engine ->
                        val isSupported = when (engine) {
                            LlmEnginePreference.GEMINI_NANO -> deviceCapabilities?.hasAiCore == true
                            else -> true
                        }

                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(engine.displayName)
                                        if (!isSupported) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "(Not available)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        if (engine == LlmEnginePreference.GEMINI_NANO) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            SuggestionChip(
                                                onClick = { },
                                                label = { Text("Alpha", style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                    Text(
                                        text = engine.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                if (isSupported || engine == LlmEnginePreference.AUTO || engine == LlmEnginePreference.RULE_BASED) {
                                    onEngineChange(engine.id)
                                    expanded = false
                                }
                            },
                            enabled = isSupported || engine == LlmEnginePreference.AUTO || engine == LlmEnginePreference.RULE_BASED,
                            leadingIcon = {
                                if (engine == currentPreference) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // Current engine status
            Spacer(modifier = Modifier.height(12.dp))

            when (currentPreference) {
                LlmEnginePreference.AUTO -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Auto-select engine",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Will try: Gemini Nano → MediaPipe → Rule-Based",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LlmEnginePreference.GEMINI_NANO -> {
                    if (deviceCapabilities?.hasAiCore != true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Gemini Nano not available",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Gemini Nano requires Pixel 8+ with AICore",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = "NPU acceleration available",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Uses NPU acceleration via Google AICore",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                LlmEnginePreference.MEDIAPIPE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "MediaPipe engine",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Requires a downloaded Gemma model",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LlmEnginePreference.RULE_BASED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Rule,
                            contentDescription = "Rule-based engine",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "No LLM, uses built-in threat intelligence only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformanceCard(
    settings: AiSettings,
    deviceCapabilities: DetectionAnalyzer.DeviceCapabilities?,
    onGpuChange: (Boolean) -> Unit,
    onNpuChange: (Boolean) -> Unit
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
                    contentDescription = "GPU Acceleration",
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
                        text = "Use GPU for faster LLM inference",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.useGpuAcceleration,
                    onCheckedChange = onGpuChange
                )
            }

            // NPU Acceleration (only show if device supports it)
            if (deviceCapabilities?.hasNpu == true) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "NPU Acceleration",
                        tint = if (settings.useNpuAcceleration) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "NPU Acceleration",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SuggestionChip(
                                onClick = { },
                                label = { Text("Pixel 8+", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        Text(
                            text = "Use Tensor NPU for Gemini Nano",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.useNpuAcceleration,
                        onCheckedChange = onNpuChange
                    )
                }
            }

            // RAM info
            deviceCapabilities?.let { caps ->
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = "Available RAM",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Available RAM",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${caps.availableRamMb} MB available for model inference",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceTokenCard(
    token: String,
    onTokenChange: (String) -> Unit
) {
    var showToken by remember { mutableStateOf(false) }
    var editingToken by remember { mutableStateOf(token) }
    var isEditing by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Hugging Face Token",
                    tint = if (token.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hugging Face Token",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (token.isNotBlank()) "Token configured" else "Required for some model downloads",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (token.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (token.isNotBlank() && !isEditing) {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showToken) "Hide token" else "Show token"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = editingToken,
                    onValueChange = { editingToken = it },
                    label = { Text("Enter HF Token") },
                    placeholder = { Text("hf_...") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showToken) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        editingToken = token
                        isEditing = false
                    }) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onTokenChange(editingToken.trim())
                        isEditing = false
                    }) {
                        Text("Save")
                    }
                }
            } else {
                if (token.isNotBlank() && showToken) {
                    Text(
                        text = token,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            editingToken = token
                            isEditing = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit token", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (token.isBlank()) "Add Token" else "Edit")
                    }
                    if (token.isNotBlank()) {
                        OutlinedButton(
                            onClick = { onTokenChange("") }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove token", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Help text
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Get your token at huggingface.co/settings/tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TestAnalysisCard(
    isAnalyzing: Boolean,
    modelStatus: AiModelStatus,
    testResult: String?,
    onTest: () -> Unit,
    onCancel: () -> Unit,
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
                text = "Run a test analysis on a sample Flock Safety camera detection to see the AI in action.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (isAnalyzing) {
                // Show cancel button when analyzing
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancel Analysis")
                }
            } else {
                Button(
                    onClick = onTest,
                    enabled = modelStatus is AiModelStatus.Ready,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run test analysis")
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
private fun InfoCard(availableModels: List<AiModel>) {
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
                    contentDescription = "About On-Device AI",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "About On-Device AI",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "All analysis runs 100% on your device using local LLM inference. Choose from multiple models based on your needs:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            availableModels.take(4).forEach { model ->
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
                            text = model.description.take(80) + if (model.description.length > 80) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (availableModels.size > 4) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "+ ${availableModels.size - 4} more models available",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

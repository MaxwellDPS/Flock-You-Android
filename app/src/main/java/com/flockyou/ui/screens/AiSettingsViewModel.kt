package com.flockyou.ui.screens

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.ai.DetectionAnalyzer
import com.flockyou.data.AiModel
import com.flockyou.data.AiModelStatus
import com.flockyou.data.AiSettings
import com.flockyou.data.AiSettingsRepository
import com.flockyou.data.AnalysisFeedback
import com.flockyou.data.FeedbackType
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.SignalStrength
import com.flockyou.data.model.ThreatLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val application: Application,
    private val aiSettingsRepository: AiSettingsRepository,
    private val detectionAnalyzer: DetectionAnalyzer
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AiSettingsViewModel"
    }

    val aiSettings: StateFlow<AiSettings> = aiSettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiSettings()
        )

    val modelStatus: StateFlow<AiModelStatus> = detectionAnalyzer.modelStatus

    val isAnalyzing: StateFlow<Boolean> = detectionAnalyzer.isAnalyzing

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _deviceCapabilities = MutableStateFlow<DetectionAnalyzer.DeviceCapabilities?>(null)
    val deviceCapabilities: StateFlow<DetectionAnalyzer.DeviceCapabilities?> = _deviceCapabilities.asStateFlow()

    private val _selectedModelForDownload = MutableStateFlow<AiModel?>(null)
    val selectedModelForDownload: StateFlow<AiModel?> = _selectedModelForDownload.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    // Track downloaded models
    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    // Active engine info
    val activeEngine: StateFlow<String> = detectionAnalyzer.activeEngineName

    init {
        loadDeviceCapabilities()
        loadDownloadedModels()
    }

    private fun loadDownloadedModels() {
        viewModelScope.launch {
            _downloadedModels.value = detectionAnalyzer.getDownloadedModelIds()
        }
    }

    private fun loadDeviceCapabilities() {
        viewModelScope.launch {
            val capabilities = detectionAnalyzer.getDeviceCapabilities()
            _deviceCapabilities.value = capabilities
            _availableModels.value = capabilities.supportedModels
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setEnabled(enabled)
            if (enabled) {
                detectionAnalyzer.initializeModel()
            }
        }
    }

    fun setAnalyzeDetections(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setAnalyzeDetections(enabled)
        }
    }

    fun setGenerateThreatAssessments(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setGenerateThreatAssessments(enabled)
        }
    }

    fun setIdentifyUnknownDevices(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setIdentifyUnknownDevices(enabled)
        }
    }

    fun setAutoAnalyzeNewDetections(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setAutoAnalyzeNewDetections(enabled)
        }
    }

    fun setUseGpuAcceleration(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setUseGpuAcceleration(enabled)
            // Reinitialize model to apply new acceleration settings
            if (aiSettings.value.enabled) {
                detectionAnalyzer.initializeModel()
            }
        }
    }

    fun setUseNpuAcceleration(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setUseNpuAcceleration(enabled)
            // Reinitialize model to apply new acceleration settings
            if (aiSettings.value.enabled) {
                detectionAnalyzer.initializeModel()
            }
        }
    }

    fun setHuggingFaceToken(token: String) {
        viewModelScope.launch {
            aiSettingsRepository.setHuggingFaceToken(token)
        }
    }

    fun setContextualAnalysis(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setContextualAnalysis(enabled)
        }
    }

    fun setBatchAnalysis(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setBatchAnalysis(enabled)
        }
    }

    fun setTrackFeedback(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setTrackFeedback(enabled)
        }
    }

    fun setFalsePositiveFiltering(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setFalsePositiveFiltering(enabled)
        }
    }

    fun setPreferredEngine(engineId: String) {
        viewModelScope.launch {
            aiSettingsRepository.setPreferredEngine(engineId)
            // Re-initialize with the new engine preference
            detectionAnalyzer.initializeModel()
        }
    }

    fun selectModelForDownload(model: AiModel) {
        _selectedModelForDownload.value = model
    }

    fun clearSelectedModel() {
        _selectedModelForDownload.value = null
    }

    fun downloadModel(model: AiModel = _selectedModelForDownload.value ?: AiModel.RULE_BASED) {
        viewModelScope.launch {
            // Guard against concurrent downloads
            if (_isDownloading.value) {
                Toast.makeText(
                    application,
                    "Download already in progress",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            _downloadProgress.value = 0
            _downloadError.value = null

            if (model == AiModel.RULE_BASED) {
                // No download needed for rule-based
                aiSettingsRepository.setSelectedModel(model.id)
                detectionAnalyzer.selectModel(model.id)
                Toast.makeText(
                    application,
                    "Using rule-based analysis",
                    Toast.LENGTH_SHORT
                ).show()
                _selectedModelForDownload.value = null
                return@launch
            }

            _isDownloading.value = true
            try {
                val success = detectionAnalyzer.downloadModel(model.id) { progress ->
                    _downloadProgress.value = progress
                }

                if (success) {
                    Log.i(TAG, "Download succeeded for ${model.displayName}, enabling AI and initializing...")
                    // Enable AI analysis after successful download
                    aiSettingsRepository.setEnabled(true)
                    Log.d(TAG, "AI enabled, now calling initializeModel()...")
                    Toast.makeText(
                        application,
                        "${model.displayName} ready",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Now initialize the model (AI is enabled so this will work)
                    val initResult = detectionAnalyzer.initializeModel()
                    Log.i(TAG, "initializeModel() returned: $initResult")
                } else {
                    val errorMsg = "Failed to download ${model.displayName}"
                    _downloadError.value = errorMsg
                    Toast.makeText(
                        application,
                        errorMsg,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                _isDownloading.value = false
                _selectedModelForDownload.value = null
            }
        }
    }

    fun clearDownloadError() {
        _downloadError.value = null
    }

    /**
     * Cancel an ongoing model download.
     */
    fun cancelDownload() {
        viewModelScope.launch {
            detectionAnalyzer.cancelDownload()
            _isDownloading.value = false
            _downloadProgress.value = 0
            Toast.makeText(
                application,
                "Download cancelled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Get storage info for a downloaded model.
     */
    fun getModelStorageInfo(modelId: String): String? {
        return detectionAnalyzer.getModelStorageInfo(modelId)
    }

    fun selectModel(model: AiModel) {
        viewModelScope.launch {
            val success = detectionAnalyzer.selectModel(model.id)
            if (success) {
                Toast.makeText(
                    application,
                    "Switched to ${model.displayName}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Model not downloaded, prompt download
                _selectedModelForDownload.value = model
                Toast.makeText(
                    application,
                    "${model.displayName} not downloaded",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            val success = detectionAnalyzer.deleteModel()
            if (success) {
                Toast.makeText(
                    application,
                    "Model deleted, using rule-based analysis",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun initializeModel() {
        viewModelScope.launch {
            val success = detectionAnalyzer.initializeModel()
            if (success) {
                Toast.makeText(
                    application,
                    "Model initialized",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    application,
                    "Failed to initialize model",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun testAnalysis() {
        viewModelScope.launch {
            Log.i(TAG, "=== testAnalysis START ===")

            // Create a test detection
            val testDetection = Detection(
                protocol = DetectionProtocol.WIFI,
                detectionMethod = DetectionMethod.SSID_PATTERN,
                deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
                deviceName = "FLOCK-TEST-001",
                ssid = "Flock-Camera-A1B2C3",
                rssi = -65,
                signalStrength = SignalStrength.GOOD,
                threatLevel = ThreatLevel.HIGH,
                threatScore = 85,
                manufacturer = "Flock Safety"
            )

            Log.d(TAG, "Calling detectionAnalyzer.analyzeDetection()...")
            val result = detectionAnalyzer.analyzeDetection(testDetection)
            Log.i(TAG, "testAnalysis result: success=${result.success}, model=${result.modelUsed}, error=${result.error}")

            when {
                result.success -> {
                    _testResult.value = result.analysis
                    val modelInfo = if (result.modelUsed == "rule-based") "rule-based" else result.modelUsed
                    Log.i(TAG, "Test analysis succeeded with model: $modelInfo")
                    Toast.makeText(
                        application,
                        "Analysis completed in ${result.processingTimeMs}ms ($modelInfo)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                result.wasCancelled -> {
                    _testResult.value = null
                    Log.d(TAG, "Test analysis was cancelled")
                    // No toast for cancellation - user initiated it
                }
                else -> {
                    _testResult.value = null
                    Log.w(TAG, "Test analysis failed: ${result.error}")
                    Toast.makeText(
                        application,
                        result.error ?: "Analysis failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun submitFeedback(detectionId: String, feedbackType: FeedbackType, wasHelpful: Boolean) {
        viewModelScope.launch {
            val feedback = AnalysisFeedback(
                detectionId = detectionId,
                analysisTimestamp = System.currentTimeMillis(),
                wasHelpful = wasHelpful,
                feedbackType = feedbackType
            )
            detectionAnalyzer.recordFeedback(feedback)
            Toast.makeText(
                application,
                "Feedback recorded - thank you!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    /**
     * Cancel any ongoing AI analysis.
     */
    fun cancelAnalysis() {
        detectionAnalyzer.cancelAnalysis()
    }

    /**
     * Import a model file from a Uri (e.g., from file picker).
     */
    fun importModel(uri: android.net.Uri, model: AiModel) {
        viewModelScope.launch {
            if (_isDownloading.value) {
                Toast.makeText(
                    application,
                    "Import already in progress",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            _isDownloading.value = true
            _downloadProgress.value = 0
            _downloadError.value = null

            try {
                val success = detectionAnalyzer.importModel(uri, model.id) { progress ->
                    _downloadProgress.value = progress
                }

                if (success) {
                    // Enable AI analysis after successful import
                    aiSettingsRepository.setEnabled(true)
                    Toast.makeText(
                        application,
                        "${model.displayName} imported successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Note: DetectionAnalyzer.importModel() already calls initializeModel()
                } else {
                    val errorMsg = "Failed to import ${model.displayName}"
                    _downloadError.value = errorMsg
                    Toast.makeText(
                        application,
                        errorMsg,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                _isDownloading.value = false
                _selectedModelForDownload.value = null
            }
        }
    }

    /**
     * Get the models directory path for manual file placement instructions.
     */
    fun getModelsDirectoryPath(): String {
        return detectionAnalyzer.getModelsDirectory().absolutePath
    }
}

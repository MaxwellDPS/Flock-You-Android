package com.flockyou.ui.screens

import android.app.Application
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

    init {
        loadDeviceCapabilities()
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
        }
    }

    fun setUseNpuAcceleration(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setUseNpuAcceleration(enabled)
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

    fun selectModelForDownload(model: AiModel) {
        _selectedModelForDownload.value = model
    }

    fun clearSelectedModel() {
        _selectedModelForDownload.value = null
    }

    fun downloadModel(model: AiModel = _selectedModelForDownload.value ?: AiModel.RULE_BASED) {
        viewModelScope.launch {
            _downloadProgress.value = 0

            if (model == AiModel.RULE_BASED) {
                // No download needed for rule-based
                aiSettingsRepository.setSelectedModel(model.id)
                detectionAnalyzer.selectModel(model.id)
                Toast.makeText(
                    application,
                    "Using rule-based analysis",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val success = detectionAnalyzer.downloadModel(model.id) { progress ->
                _downloadProgress.value = progress
            }

            if (success) {
                Toast.makeText(
                    application,
                    "${model.displayName} ready",
                    Toast.LENGTH_SHORT
                ).show()
                detectionAnalyzer.initializeModel()
            } else {
                Toast.makeText(
                    application,
                    "Failed to download ${model.displayName}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            _selectedModelForDownload.value = null
        }
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

            val result = detectionAnalyzer.analyzeDetection(testDetection)

            if (result.success) {
                _testResult.value = result.analysis
                val modelInfo = if (result.modelUsed == "rule-based") "rule-based" else result.modelUsed
                Toast.makeText(
                    application,
                    "Analysis completed in ${result.processingTimeMs}ms ($modelInfo)",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                _testResult.value = null
                Toast.makeText(
                    application,
                    result.error ?: "Analysis failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun submitFeedback(detectionId: String, feedbackType: FeedbackType, wasHelpful: Boolean) {
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

    fun clearTestResult() {
        _testResult.value = null
    }
}

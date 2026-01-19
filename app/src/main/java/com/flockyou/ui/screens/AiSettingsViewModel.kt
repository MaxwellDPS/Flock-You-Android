package com.flockyou.ui.screens

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.ai.DetectionAnalyzer
import com.flockyou.data.AiModelStatus
import com.flockyou.data.AiSettings
import com.flockyou.data.AiSettingsRepository
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

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            aiSettingsRepository.setEnabled(enabled)
            if (enabled) {
                // Initialize the model when enabled
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

    fun downloadModel() {
        viewModelScope.launch {
            _downloadProgress.value = 0
            val success = detectionAnalyzer.downloadModel { progress ->
                _downloadProgress.value = progress
            }

            if (success) {
                Toast.makeText(
                    application,
                    "Model downloaded successfully",
                    Toast.LENGTH_SHORT
                ).show()
                // Initialize after download
                detectionAnalyzer.initializeModel()
            } else {
                Toast.makeText(
                    application,
                    "Failed to download model",
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
                    "Model deleted",
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
                Toast.makeText(
                    application,
                    "Analysis completed in ${result.processingTimeMs}ms (${result.modelUsed})",
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

    fun clearTestResult() {
        _testResult.value = null
    }
}

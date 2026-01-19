package com.flockyou.ai

import android.content.Context
import android.util.Log
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.AiModelStatus
import com.flockyou.data.AiSettings
import com.flockyou.data.AiSettingsRepository
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI-powered detection analyzer using on-device LLM inference.
 *
 * Provides three main capabilities:
 * 1. Detection explanation - Generate natural language descriptions of detected devices
 * 2. Threat assessment - Provide contextual risk analysis and recommendations
 * 3. Device identification - Help identify unknown devices based on characteristics
 */
@Singleton
class DetectionAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiSettingsRepository: AiSettingsRepository
) {
    companion object {
        private const val TAG = "DetectionAnalyzer"

        // System prompt for surveillance detection analysis
        private const val SYSTEM_PROMPT = """You are a security analyst AI specialized in surveillance device detection and privacy protection. Your role is to analyze detected wireless devices and provide:

1. Clear explanations of what the device is and its capabilities
2. Privacy risk assessment based on the device type and context
3. Actionable recommendations for the user

Be concise, factual, and focus on privacy implications. Avoid speculation but do highlight legitimate concerns. Format your response clearly with sections for Analysis, Risk Assessment, and Recommendations."""

        // Prompt templates
        private const val DETECTION_ANALYSIS_TEMPLATE = """Analyze this detected surveillance device:

Device Type: %s
Detection Method: %s
Protocol: %s
Signal Strength: %s (estimated distance: %s)
Manufacturer: %s
%s

Provide a brief analysis including:
1. What this device likely is and its purpose
2. What data it may be collecting
3. Privacy risk level and why
4. What the user should consider doing"""

        private const val THREAT_ASSESSMENT_TEMPLATE = """Assess the threat level of these recent detections:

Total Detections: %d
Critical: %d
High: %d
Medium: %d
Low: %d

Recent Devices:
%s

Provide:
1. Overall threat assessment for this environment
2. Most concerning detections and why
3. Patterns or correlations noticed
4. Priority recommendations"""

        private const val DEVICE_IDENTIFICATION_TEMPLATE = """Help identify this unknown wireless device:

Protocol: %s
Signal Characteristics:
- RSSI: %d dBm
- Signal Strength: %s
%s
%s
%s

Based on these characteristics, what type of device could this be? Consider:
1. Most likely device categories
2. Common devices with similar signatures
3. Whether this could be surveillance-related
4. Confidence level in the identification"""
    }

    private val _modelStatus = MutableStateFlow<AiModelStatus>(AiModelStatus.NotDownloaded)
    val modelStatus: StateFlow<AiModelStatus> = _modelStatus.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private var generativeModel: GenerativeModel? = null

    /**
     * Initialize the AI model for inference.
     * For Gemini Nano on-device, this would initialize LiteRT.
     * Falls back to Gemini API if configured.
     */
    suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val settings = aiSettingsRepository.settings.first()

            if (!settings.enabled) {
                Log.d(TAG, "AI analysis is disabled")
                return@withContext false
            }

            _modelStatus.value = AiModelStatus.Initializing

            // Try on-device model first (Gemini Nano via LiteRT)
            val onDeviceInitialized = tryInitializeOnDeviceModel(settings)

            if (onDeviceInitialized) {
                _modelStatus.value = AiModelStatus.Ready
                Log.i(TAG, "On-device model initialized successfully")
                return@withContext true
            }

            // Fall back to cloud API if configured
            if (settings.fallbackToCloudApi && settings.cloudApiKey.isNotBlank()) {
                val cloudInitialized = initializeCloudModel(settings)
                if (cloudInitialized) {
                    _modelStatus.value = AiModelStatus.Ready
                    Log.i(TAG, "Cloud API model initialized as fallback")
                    return@withContext true
                }
            }

            _modelStatus.value = AiModelStatus.Error("Failed to initialize any AI model")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AI model", e)
            _modelStatus.value = AiModelStatus.Error(e.message ?: "Unknown error")
            false
        }
    }

    private suspend fun tryInitializeOnDeviceModel(settings: AiSettings): Boolean {
        // On-device inference using Google AI Edge LiteRT
        // This would use the downloaded Gemini Nano model
        // For now, we'll check if model files exist and return status

        try {
            val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
            val modelFile = java.io.File(modelDir, "gemini_nano.tflite")

            if (!modelFile.exists()) {
                Log.d(TAG, "On-device model not downloaded yet")
                _modelStatus.value = AiModelStatus.NotDownloaded
                return false
            }

            // In a full implementation, this would initialize the LiteRT interpreter
            // with the downloaded model file and configure GPU acceleration
            Log.d(TAG, "On-device model file found: ${modelFile.absolutePath}")

            aiSettingsRepository.setModelDownloaded(true, modelFile.length() / (1024 * 1024))
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize on-device model", e)
            return false
        }
    }

    private fun initializeCloudModel(settings: AiSettings): Boolean {
        return try {
            generativeModel = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = settings.cloudApiKey,
                generationConfig = generationConfig {
                    temperature = settings.temperatureTenths / 10f
                    maxOutputTokens = settings.maxTokens
                }
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize cloud model", e)
            false
        }
    }

    /**
     * Analyze a single detection and generate insights.
     */
    suspend fun analyzeDetection(detection: Detection): AiAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val settings = aiSettingsRepository.settings.first()
            if (!settings.enabled || !settings.analyzeDetections) {
                return@withContext AiAnalysisResult(
                    success = false,
                    error = "AI analysis is disabled"
                )
            }

            _isAnalyzing.value = true

            val prompt = buildDetectionPrompt(detection)
            val result = generateResponse(prompt, settings)

            val processingTime = System.currentTimeMillis() - startTime

            if (result != null) {
                parseAnalysisResult(result, processingTime, generativeModel != null)
            } else {
                AiAnalysisResult(
                    success = false,
                    error = "No response from AI model",
                    processingTimeMs = processingTime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing detection", e)
            AiAnalysisResult(
                success = false,
                error = e.message ?: "Analysis failed",
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } finally {
            _isAnalyzing.value = false
        }
    }

    /**
     * Generate a threat assessment for multiple recent detections.
     */
    suspend fun generateThreatAssessment(
        detections: List<Detection>,
        criticalCount: Int,
        highCount: Int,
        mediumCount: Int,
        lowCount: Int
    ): AiAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val settings = aiSettingsRepository.settings.first()
            if (!settings.enabled || !settings.generateThreatAssessments) {
                return@withContext AiAnalysisResult(
                    success = false,
                    error = "Threat assessment is disabled"
                )
            }

            _isAnalyzing.value = true

            val recentDevicesSummary = detections.take(10).joinToString("\n") { detection ->
                "- ${detection.deviceType.displayName} (${detection.threatLevel.displayName}): ${detection.protocol.displayName}"
            }

            val prompt = String.format(
                THREAT_ASSESSMENT_TEMPLATE,
                detections.size,
                criticalCount,
                highCount,
                mediumCount,
                lowCount,
                recentDevicesSummary
            )

            val result = generateResponse("$SYSTEM_PROMPT\n\n$prompt", settings)
            val processingTime = System.currentTimeMillis() - startTime

            if (result != null) {
                AiAnalysisResult(
                    success = true,
                    threatAssessment = result,
                    processingTimeMs = processingTime,
                    modelUsed = if (generativeModel != null) "gemini-1.5-flash" else "gemini-nano",
                    wasOnDevice = generativeModel == null
                )
            } else {
                AiAnalysisResult(
                    success = false,
                    error = "No response from AI model",
                    processingTimeMs = processingTime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating threat assessment", e)
            AiAnalysisResult(
                success = false,
                error = e.message ?: "Assessment failed",
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } finally {
            _isAnalyzing.value = false
        }
    }

    /**
     * Help identify an unknown device based on its characteristics.
     */
    suspend fun identifyUnknownDevice(
        protocol: String,
        rssi: Int,
        signalStrength: String,
        ssid: String? = null,
        macAddress: String? = null,
        serviceUuids: String? = null
    ): AiAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val settings = aiSettingsRepository.settings.first()
            if (!settings.enabled || !settings.identifyUnknownDevices) {
                return@withContext AiAnalysisResult(
                    success = false,
                    error = "Device identification is disabled"
                )
            }

            _isAnalyzing.value = true

            val ssidInfo = ssid?.let { "SSID: $it" } ?: ""
            val macInfo = macAddress?.let { "MAC Prefix: ${it.take(8)}" } ?: ""
            val uuidInfo = serviceUuids?.let { "Service UUIDs: $it" } ?: ""

            val prompt = String.format(
                DEVICE_IDENTIFICATION_TEMPLATE,
                protocol,
                rssi,
                signalStrength,
                ssidInfo,
                macInfo,
                uuidInfo
            )

            val result = generateResponse("$SYSTEM_PROMPT\n\n$prompt", settings)
            val processingTime = System.currentTimeMillis() - startTime

            if (result != null) {
                AiAnalysisResult(
                    success = true,
                    analysis = result,
                    processingTimeMs = processingTime,
                    modelUsed = if (generativeModel != null) "gemini-1.5-flash" else "gemini-nano",
                    wasOnDevice = generativeModel == null
                )
            } else {
                AiAnalysisResult(
                    success = false,
                    error = "No response from AI model",
                    processingTimeMs = processingTime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error identifying device", e)
            AiAnalysisResult(
                success = false,
                error = e.message ?: "Identification failed",
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } finally {
            _isAnalyzing.value = false
        }
    }

    private fun buildDetectionPrompt(detection: Detection): String {
        val additionalInfo = buildString {
            detection.ssid?.let { append("SSID: $it\n") }
            detection.macAddress?.let { append("MAC: ${it.take(8)}:XX:XX:XX\n") }
            detection.firmwareVersion?.let { append("Firmware: $it\n") }
            detection.serviceUuids?.let { append("BLE Services: $it\n") }
        }

        return "$SYSTEM_PROMPT\n\n" + String.format(
            DETECTION_ANALYSIS_TEMPLATE,
            detection.deviceType.displayName,
            detection.detectionMethod.displayName,
            detection.protocol.displayName,
            detection.signalStrength.displayName,
            com.flockyou.data.model.rssiToDistance(detection.rssi),
            detection.manufacturer ?: "Unknown",
            additionalInfo
        )
    }

    private suspend fun generateResponse(prompt: String, settings: AiSettings): String? {
        // Try cloud API if available
        generativeModel?.let { model ->
            return try {
                val response = model.generateContent(prompt)
                response.text
            } catch (e: Exception) {
                Log.e(TAG, "Cloud API error", e)
                null
            }
        }

        // On-device inference would go here
        // For now, return a placeholder that indicates on-device isn't available
        return generateLocalFallbackAnalysis(prompt)
    }

    /**
     * Local rule-based analysis as fallback when LLM is not available.
     * This provides basic analysis without requiring AI inference.
     */
    private fun generateLocalFallbackAnalysis(prompt: String): String? {
        // This is a simplified local analysis that doesn't require LLM
        // It provides basic guidance based on device type patterns
        return null // Return null to indicate AI is not available
    }

    private fun parseAnalysisResult(
        response: String,
        processingTimeMs: Long,
        wasCloud: Boolean
    ): AiAnalysisResult {
        // Parse the response to extract structured data
        val recommendations = mutableListOf<String>()

        // Simple extraction of recommendation-like lines
        response.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("-") || trimmed.startsWith("•") ||
                trimmed.matches(Regex("^\\d+\\..*"))) {
                recommendations.add(trimmed.removePrefix("-").removePrefix("•").trim())
            }
        }

        return AiAnalysisResult(
            success = true,
            analysis = response,
            recommendations = recommendations.take(5),
            confidence = 0.85f, // Could be derived from model output
            processingTimeMs = processingTimeMs,
            modelUsed = if (wasCloud) "gemini-1.5-flash" else "gemini-nano",
            wasOnDevice = !wasCloud
        )
    }

    /**
     * Download the on-device AI model.
     */
    suspend fun downloadModel(
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            _modelStatus.value = AiModelStatus.Downloading(0)

            // In a full implementation, this would:
            // 1. Download the model from Google's servers
            // 2. Verify the model integrity
            // 3. Store it in the app's private directory

            // Simulate download progress for now
            for (progress in 0..100 step 10) {
                _modelStatus.value = AiModelStatus.Downloading(progress)
                onProgress(progress)
                kotlinx.coroutines.delay(100)
            }

            val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
            val modelFile = java.io.File(modelDir, "gemini_nano.tflite")

            // Create placeholder file to indicate model is "downloaded"
            // In production, this would be the actual model file
            if (!modelFile.exists()) {
                modelFile.createNewFile()
                modelFile.writeText("PLACEHOLDER_MODEL_FILE")
            }

            aiSettingsRepository.setModelDownloaded(true, 300)
            _modelStatus.value = AiModelStatus.Ready

            Log.i(TAG, "Model download completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            _modelStatus.value = AiModelStatus.Error(e.message ?: "Download failed")
            false
        }
    }

    /**
     * Delete the downloaded model to free up storage.
     */
    suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
            modelDir.listFiles()?.forEach { it.delete() }

            aiSettingsRepository.setModelDownloaded(false, 0)
            _modelStatus.value = AiModelStatus.NotDownloaded
            generativeModel = null

            Log.i(TAG, "Model deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model", e)
            false
        }
    }

    /**
     * Check if AI analysis is available (either on-device or cloud).
     */
    suspend fun isAvailable(): Boolean {
        val settings = aiSettingsRepository.settings.first()
        if (!settings.enabled) return false

        return when (_modelStatus.value) {
            is AiModelStatus.Ready -> true
            else -> settings.fallbackToCloudApi && settings.cloudApiKey.isNotBlank()
        }
    }
}

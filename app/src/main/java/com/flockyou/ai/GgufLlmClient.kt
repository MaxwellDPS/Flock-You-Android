package com.flockyou.ai

import android.content.Context
import android.util.Log
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.AiModel
import com.flockyou.data.InferenceConfig
import com.flockyou.data.model.Detection
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for running GGUF model inference using MediaPipe LLM Inference.
 * All processing happens entirely on-device with no cloud connectivity.
 *
 * Supports models like SmolLM, Gemma, TinyLlama, Phi-3, Qwen2, etc.
 */
@Singleton
class GgufLlmClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GgufLlmClient"
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
        private const val TOP_P = 0.9f
    }

    private var llmInference: LlmInference? = null
    private var currentModelPath: String? = null
    private var isInitialized = false
    private var initializationError: String? = null
    private val initMutex = Mutex()

    /**
     * Initialize the LLM with a specific model file.
     */
    suspend fun initialize(modelFile: File, config: InferenceConfig = InferenceConfig(
        maxTokens = MAX_TOKENS,
        temperature = TEMPERATURE,
        useGpuAcceleration = true,
        useNpuAcceleration = false
    )): Boolean = initMutex.withLock {
        if (isInitialized && currentModelPath == modelFile.absolutePath) {
            Log.d(TAG, "Model already initialized: ${modelFile.name}")
            return@withLock true
        }

        // Close existing model if any
        cleanup()
        initializationError = null

        return@withLock withContext(Dispatchers.IO) {
            try {
                if (!modelFile.exists()) {
                    initializationError = "Model file not found: ${modelFile.absolutePath}"
                    Log.e(TAG, initializationError!!)
                    return@withContext false
                }

                Log.d(TAG, "Initializing LLM with model: ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")

                // Build LLM inference options
                val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(config.maxTokens)
                    .setTopK(TOP_K)
                    .setRandomSeed(42)

                val options = optionsBuilder.build()

                // Create the LLM inference instance
                llmInference = LlmInference.createFromOptions(context, options)
                currentModelPath = modelFile.absolutePath
                isInitialized = true

                Log.i(TAG, "LLM initialized successfully: ${modelFile.name}")
                true

            } catch (e: Exception) {
                initializationError = "Failed to initialize LLM: ${e.message}"
                Log.e(TAG, initializationError!!, e)
                isInitialized = false
                false
            }
        }
    }

    /**
     * Generate text completion for the given prompt.
     */
    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        val inference = llmInference
        if (inference == null || !isInitialized) {
            Log.w(TAG, "LLM not initialized")
            return@withContext null
        }

        try {
            Log.d(TAG, "Generating response for prompt (${prompt.length} chars)")
            val startTime = System.currentTimeMillis()

            val response = inference.generateResponse(prompt)

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Generated response in ${duration}ms (${response?.length ?: 0} chars)")

            response
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            null
        }
    }

    /**
     * Analyze a detection using the LLM.
     */
    suspend fun analyzeDetection(detection: Detection, model: AiModel): AiAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (!isInitialized) {
            return@withContext AiAnalysisResult(
                success = false,
                error = initializationError ?: "LLM not initialized",
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = model.id
            )
        }

        try {
            val prompt = buildAnalysisPrompt(detection)
            val response = generateResponse(prompt)

            if (response != null) {
                AiAnalysisResult(
                    success = true,
                    analysis = formatResponse(response, detection),
                    confidence = 0.85f,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    modelUsed = model.id,
                    wasOnDevice = true
                )
            } else {
                AiAnalysisResult(
                    success = false,
                    error = "Failed to generate analysis",
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    modelUsed = model.id
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing detection", e)
            AiAnalysisResult(
                success = false,
                error = "Analysis failed: ${e.message}",
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = model.id
            )
        }
    }

    /**
     * Build the analysis prompt for a detection.
     */
    private fun buildAnalysisPrompt(detection: Detection): String {
        return """<|system|>
You are a privacy and surveillance expert assistant. Analyze surveillance devices detected nearby and provide concise, actionable privacy advice. Be direct and helpful.
<|end|>
<|user|>
I detected a surveillance device nearby. Please analyze it and tell me:
1. What this device does and its surveillance capabilities
2. What data it can collect about me
3. The privacy risk level and implications
4. What actions I should take

Device Details:
- Type: ${detection.deviceType.displayName}
- Protocol: ${detection.protocol.displayName}
- Signal: ${detection.signalStrength.displayName} (${detection.rssi} dBm)
- Threat Level: ${detection.threatLevel.displayName}
${detection.manufacturer?.let { "- Manufacturer: $it" } ?: ""}
${detection.deviceName?.let { "- Device Name: $it" } ?: ""}
${detection.ssid?.let { "- Network: $it" } ?: ""}
<|end|>
<|assistant|>
"""
    }

    /**
     * Format and clean up the LLM response.
     */
    private fun formatResponse(response: String, detection: Detection): String {
        // Clean up the response
        var cleaned = response
            .replace("<|end|>", "")
            .replace("<|assistant|>", "")
            .trim()

        // Add a header if the response doesn't have one
        if (!cleaned.startsWith("#") && !cleaned.startsWith("##")) {
            cleaned = "## ${detection.deviceType.displayName} Analysis\n\n$cleaned"
        }

        return cleaned
    }

    /**
     * Check if the LLM is ready for inference.
     */
    fun isReady(): Boolean = isInitialized && llmInference != null

    /**
     * Get the current initialization status.
     */
    fun getStatus(): GgufLlmStatus {
        return when {
            isInitialized -> GgufLlmStatus.Ready(currentModelPath ?: "unknown")
            initializationError != null -> GgufLlmStatus.Error(initializationError!!)
            else -> GgufLlmStatus.NotInitialized
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LLM: ${e.message}")
        }
        llmInference = null
        currentModelPath = null
        isInitialized = false
    }
}

sealed class GgufLlmStatus {
    object NotInitialized : GgufLlmStatus()
    data class Ready(val modelPath: String) : GgufLlmStatus()
    data class Error(val message: String) : GgufLlmStatus()
}

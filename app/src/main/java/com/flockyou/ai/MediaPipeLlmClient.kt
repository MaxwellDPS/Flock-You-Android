package com.flockyou.ai

import android.content.Context
import android.util.Log
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.AiModel
import com.flockyou.data.InferenceConfig
import com.flockyou.data.ModelFormat
import com.flockyou.data.model.Detection
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for running on-device LLM inference using MediaPipe LLM Inference API.
 * All processing happens entirely on-device with no cloud connectivity.
 *
 * Supports models in MediaPipe .task and .bin formats (e.g., Gemma models).
 * Note: Raw GGUF files are NOT supported - models must be converted to .task/.bin format.
 */
@Singleton
class MediaPipeLlmClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MediaPipeLlmClient"
        private const val MAX_TOKENS = 512
        private const val INFERENCE_TIMEOUT_MS = 60_000L // 60 second timeout for inference
        private const val INIT_TIMEOUT_MS = 30_000L // 30 second timeout for initialization
    }

    private var llmInference: LlmInference? = null
    private var currentModelPath: String? = null
    private var isInitialized = false
    private var initializationError: String? = null
    private val initMutex = Mutex()

    /**
     * Initialize the LLM with a specific model file.
     * The model file must be in MediaPipe .task or .bin format.
     */
    suspend fun initialize(modelFile: File, config: InferenceConfig = InferenceConfig(
        maxTokens = MAX_TOKENS,
        temperature = 0.7f,
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

        return@withLock try {
            withTimeout(INIT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    if (!modelFile.exists()) {
                        initializationError = "Model file not found: ${modelFile.absolutePath}"
                        Log.e(TAG, initializationError!!)
                        return@withContext false
                    }

                    // Verify file has supported extension (.task or .bin)
                    val validExtensions = listOf(".task", ".bin")
                    if (!validExtensions.any { modelFile.name.endsWith(it) }) {
                        initializationError = "Invalid model format. Expected .task or .bin file, got: ${modelFile.name}. " +
                            "MediaPipe requires models in .task or .bin format."
                        Log.e(TAG, initializationError!!)
                        return@withContext false
                    }

                    // Verify minimum file size (at least 10MB for a real model)
                    val minSizeBytes = 10 * 1024 * 1024L
                    if (modelFile.length() < minSizeBytes) {
                        initializationError = "Model file too small (${modelFile.length() / 1024 / 1024} MB). " +
                            "Expected at least ${minSizeBytes / 1024 / 1024} MB for a valid model."
                        Log.e(TAG, initializationError!!)
                        return@withContext false
                    }

                    Log.d(TAG, "Initializing MediaPipe LLM with model: ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")

                    // Build LLM inference options
                    // Must set PreferredBackend to GPU or CPU (not legacy) for models with input masks
                    val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(config.maxTokens)
                        .setPreferredBackend(
                            if (config.useGpuAcceleration) {
                                LlmInference.Backend.GPU
                            } else {
                                LlmInference.Backend.CPU
                            }
                        )

                    val options = optionsBuilder.build()

                    // Create the LLM inference instance
                    llmInference = LlmInference.createFromOptions(context, options)
                    currentModelPath = modelFile.absolutePath
                    isInitialized = true

                    Log.i(TAG, "MediaPipe LLM initialized successfully: ${modelFile.name}")
                    true
                }
            }
        } catch (e: TimeoutCancellationException) {
            initializationError = "Model initialization timed out after ${INIT_TIMEOUT_MS / 1000} seconds"
            Log.e(TAG, initializationError!!)
            isInitialized = false
            false
        } catch (e: Exception) {
            initializationError = "Failed to initialize MediaPipe LLM: ${e.message}"
            Log.e(TAG, initializationError!!, e)
            isInitialized = false
            false
        }
    }

    /**
     * Generate text completion for the given prompt.
     * Includes timeout protection to prevent indefinite hangs.
     */
    suspend fun generateResponse(prompt: String): String? {
        val inference = llmInference
        if (inference == null || !isInitialized) {
            Log.w(TAG, "MediaPipe LLM not initialized")
            return null
        }

        return try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Generating response for prompt (${prompt.length} chars)")
                    val startTime = System.currentTimeMillis()

                    val response = inference.generateResponse(prompt)

                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Generated response in ${duration}ms (${response?.length ?: 0} chars)")

                    response
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Inference timed out after ${INFERENCE_TIMEOUT_MS / 1000} seconds")
            null
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
                error = initializationError ?: "MediaPipe LLM not initialized",
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = model.id
            )
        }

        try {
            val prompt = buildAnalysisPrompt(detection)
            Log.d(TAG, "Analyzing detection with prompt length: ${prompt.length}")
            val response = generateResponse(prompt)

            if (response != null) {
                Log.d(TAG, "LLM generated response: ${response.take(100)}...")
                AiAnalysisResult(
                    success = true,
                    analysis = formatResponse(response, detection),
                    confidence = 0.85f,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    modelUsed = model.id,
                    wasOnDevice = true
                )
            } else {
                Log.w(TAG, "LLM returned null response")
                AiAnalysisResult(
                    success = false,
                    error = "Failed to generate analysis - model returned empty response",
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
     * Uses a format suitable for Gemma instruction-tuned models.
     */
    private fun buildAnalysisPrompt(detection: Detection): String {
        // Use Gemma's instruction format
        return """<start_of_turn>user
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
<end_of_turn>
<start_of_turn>model
"""
    }

    /**
     * Format and clean up the LLM response.
     */
    private fun formatResponse(response: String, detection: Detection): String {
        // Clean up the response - remove any model control tokens
        var cleaned = response
            .replace("<end_of_turn>", "")
            .replace("<start_of_turn>model", "")
            .replace("<start_of_turn>user", "")
            .replace("<eos>", "")
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
    fun getStatus(): MediaPipeLlmStatus {
        return when {
            isInitialized -> MediaPipeLlmStatus.Ready(currentModelPath ?: "unknown")
            initializationError != null -> MediaPipeLlmStatus.Error(initializationError!!)
            else -> MediaPipeLlmStatus.NotInitialized
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing MediaPipe LLM: ${e.message}")
        }
        llmInference = null
        currentModelPath = null
        isInitialized = false
    }
}

sealed class MediaPipeLlmStatus {
    object NotInitialized : MediaPipeLlmStatus()
    data class Ready(val modelPath: String) : MediaPipeLlmStatus()
    data class Error(val message: String) : MediaPipeLlmStatus()
}

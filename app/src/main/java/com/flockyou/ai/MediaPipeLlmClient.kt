package com.flockyou.ai

import android.content.Context
import android.os.Build
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
        private const val PREFS_NAME = "mediapipe_llm_prefs"
        private const val KEY_GPU_FAILED = "gpu_backend_failed"

        // Check if OpenCL is available on this device
        // GPU backend requires OpenCL which may not be available on all devices
        private val isOpenClAvailable: Boolean by lazy {
            try {
                // Try to load OpenCL library - if it fails, GPU won't work
                System.loadLibrary("OpenCL")
                Log.i(TAG, "OpenCL library loaded successfully - GPU backend available")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "OpenCL library not available: ${e.message}")
                false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check OpenCL availability: ${e.message}")
                false
            }
        }

        /**
         * Check if GPU acceleration is supported on this device.
         * Returns false if OpenCL is not available.
         *
         * Note: For GPU to work on Android 12+, ensure AndroidManifest.xml includes:
         *   <uses-native-library android:name="libvndksupport.so" android:required="false" />
         *   <uses-native-library android:name="libOpenCL.so" android:required="false" />
         */
        fun isGpuSupported(): Boolean {
            Log.d(TAG, "Checking GPU support on device: ${Build.HARDWARE}/${Build.SOC_MODEL}")

            // Check OpenCL availability
            if (!isOpenClAvailable) {
                Log.d(TAG, "GPU not supported: OpenCL library not available")
                return false
            }

            Log.d(TAG, "GPU supported: OpenCL available on ${Build.HARDWARE}/${Build.SOC_MODEL}")
            return true
        }
    }

    private var llmInference: LlmInference? = null
    private var currentModelPath: String? = null
    private var currentBackend: LlmInference.Backend? = null
    private var isInitialized = false
    private var initializationError: String? = null
    private val initMutex = Mutex()

    // Track if GPU has previously failed on this device
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private fun hasGpuFailed(): Boolean = prefs.getBoolean(KEY_GPU_FAILED, false)

    private fun markGpuFailed() {
        prefs.edit().putBoolean(KEY_GPU_FAILED, true).apply()
        Log.w(TAG, "GPU backend marked as failed - will use CPU for future initializations")
    }

    /**
     * Reset GPU failure flag (call this if user wants to retry GPU)
     */
    fun resetGpuFailure() {
        prefs.edit().putBoolean(KEY_GPU_FAILED, false).apply()
        Log.i(TAG, "GPU failure flag reset - will try GPU on next initialization")
    }

    /**
     * Get the currently active backend
     */
    fun getActiveBackend(): String = currentBackend?.name ?: "NONE"

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

                    // Determine which backends to try
                    // GPU is ~8x faster for prefill but can crash on some devices
                    // Must set PreferredBackend to GPU or CPU (not legacy) for models with input masks
                    val gpuSupported = isGpuSupported()
                    val gpuPreviouslyFailed = hasGpuFailed()
                    val backendsToTry = when {
                        !config.useGpuAcceleration -> {
                            Log.d(TAG, "GPU disabled in config, using CPU only")
                            listOf(LlmInference.Backend.CPU)
                        }
                        !gpuSupported -> {
                            Log.d(TAG, "GPU not supported on this device, using CPU only")
                            listOf(LlmInference.Backend.CPU)
                        }
                        gpuPreviouslyFailed -> {
                            Log.d(TAG, "GPU previously failed on this device, using CPU only")
                            listOf(LlmInference.Backend.CPU)
                        }
                        else -> {
                            Log.d(TAG, "Will try GPU first, then fall back to CPU if needed")
                            listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)
                        }
                    }

                    var lastException: Exception? = null
                    for (backend in backendsToTry) {
                        try {
                            Log.d(TAG, "Attempting initialization with backend: $backend")

                            val options = LlmInference.LlmInferenceOptions.builder()
                                .setModelPath(modelFile.absolutePath)
                                .setMaxTokens(config.maxTokens)
                                .setPreferredBackend(backend)
                                .build()

                            llmInference = LlmInference.createFromOptions(context, options)
                            currentModelPath = modelFile.absolutePath
                            currentBackend = backend
                            isInitialized = true

                            Log.i(TAG, "MediaPipe LLM initialized successfully with $backend backend: ${modelFile.name}")
                            return@withContext true
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to initialize with $backend backend: ${e.message}")
                            lastException = e

                            // If GPU failed, mark it so we don't try again
                            if (backend == LlmInference.Backend.GPU) {
                                markGpuFailed()
                            }
                            // Continue to try next backend
                        }
                    }

                    // All backends failed
                    initializationError = "Failed to initialize with any backend: ${lastException?.message}"
                    Log.e(TAG, initializationError!!)
                    false
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
        currentBackend = null
        isInitialized = false
    }
}

sealed class MediaPipeLlmStatus {
    object NotInitialized : MediaPipeLlmStatus()
    data class Ready(val modelPath: String) : MediaPipeLlmStatus()
    data class Error(val message: String) : MediaPipeLlmStatus()
}

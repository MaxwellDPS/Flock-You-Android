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
        private const val KEY_INFERENCE_IN_PROGRESS = "inference_in_progress"
        private const val KEY_INFERENCE_CRASHED = "inference_crashed"
        private const val KEY_CRASH_COUNT = "inference_crash_count"
        private const val MAX_CRASH_COUNT = 2 // Disable after 2 crashes
        private const val MAX_CONSECUTIVE_ERRORS = 3 // Recreate session after this many errors
        private const val MAX_PROMPT_LENGTH = 2048 // Limit prompt length to avoid tokenizer issues

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
    private var currentConfig: InferenceConfig? = null
    @Volatile private var isInitialized = false
    private var initializationError: String? = null
    private val initMutex = Mutex()
    private val inferenceMutex = Mutex()

    // Error tracking for session recreation
    @Volatile private var consecutiveErrors = 0
    @Volatile private var needsSessionRecreation = false

    // Track if GPU has previously failed on this device
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private fun hasGpuFailed(): Boolean = prefs.getBoolean(KEY_GPU_FAILED, false)

    private fun markGpuFailed() {
        prefs.edit().putBoolean(KEY_GPU_FAILED, true).apply()
        Log.w(TAG, "GPU backend marked as failed - will use CPU for future initializations")
    }

    /**
     * Check if inference previously crashed (app died during inference).
     * Called on initialization to detect and prevent repeated crashes.
     */
    private fun checkAndHandlePreviousCrash(): Boolean {
        val wasInProgress = prefs.getBoolean(KEY_INFERENCE_IN_PROGRESS, false)
        if (wasInProgress) {
            // App crashed during inference
            val crashCount = prefs.getInt(KEY_CRASH_COUNT, 0) + 1
            prefs.edit()
                .putBoolean(KEY_INFERENCE_IN_PROGRESS, false)
                .putBoolean(KEY_INFERENCE_CRASHED, true)
                .putInt(KEY_CRASH_COUNT, crashCount)
                .apply()
            Log.e(TAG, "Detected crash during previous inference! Crash count: $crashCount")
            return true
        }
        return false
    }

    /**
     * Check if MediaPipe should be disabled due to repeated crashes.
     */
    private fun isDisabledDueToCrashes(): Boolean {
        val crashCount = prefs.getInt(KEY_CRASH_COUNT, 0)
        val disabled = crashCount >= MAX_CRASH_COUNT
        if (disabled) {
            Log.w(TAG, "MediaPipe disabled due to $crashCount crashes (max: $MAX_CRASH_COUNT)")
        }
        return disabled
    }

    private fun markInferenceStarted() {
        prefs.edit().putBoolean(KEY_INFERENCE_IN_PROGRESS, true).apply()
    }

    private fun markInferenceCompleted() {
        prefs.edit().putBoolean(KEY_INFERENCE_IN_PROGRESS, false).apply()
    }

    /**
     * Reset crash tracking (call this to re-enable MediaPipe after user action).
     */
    fun resetCrashTracking() {
        prefs.edit()
            .putBoolean(KEY_INFERENCE_CRASHED, false)
            .putInt(KEY_CRASH_COUNT, 0)
            .apply()
        Log.i(TAG, "Crash tracking reset - MediaPipe re-enabled")
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
        // Check for previous crash on startup
        checkAndHandlePreviousCrash()

        // Don't initialize if disabled due to repeated crashes
        if (isDisabledDueToCrashes()) {
            initializationError = "MediaPipe disabled due to repeated native crashes. Use resetCrashTracking() to re-enable."
            Log.e(TAG, initializationError!!)
            return@withLock false
        }

        if (isInitialized && currentModelPath == modelFile.absolutePath) {
            Log.d(TAG, "Model already initialized: ${modelFile.name}")
            return@withLock true
        }

        // Close existing model if any
        cleanupInternal()
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
                            currentConfig = config
                            consecutiveErrors = 0
                            needsSessionRecreation = false
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
     * Uses mutex to prevent race conditions with cleanup().
     * Tracks inference in progress to detect native crashes.
     *
     * If the detokenizer crashes (token ID -1), will recreate the session.
     */
    suspend fun generateResponse(prompt: String): String? = inferenceMutex.withLock {
        // Recreate session if needed from previous error
        if (needsSessionRecreation) {
            Log.i(TAG, "Session recreation needed, attempting...")
            recreateSessionInternal()
        }

        // Check inside the lock to prevent race with cleanup()
        val inference = llmInference
        if (inference == null || !isInitialized) {
            Log.w(TAG, "MediaPipe LLM not initialized")
            return@withLock null
        }

        // Check if disabled due to previous crashes
        if (isDisabledDueToCrashes()) {
            Log.w(TAG, "MediaPipe disabled due to crashes, skipping inference")
            return@withLock null
        }

        // Sanitize prompt to reduce tokenizer issues
        val sanitizedPrompt = sanitizePrompt(prompt)

        return@withLock try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Generating response for prompt (${sanitizedPrompt.length} chars)")
                    val startTime = System.currentTimeMillis()

                    // Mark that we're about to call native inference
                    // If app crashes here, we'll detect it on next startup
                    markInferenceStarted()

                    val response = try {
                        inference.generateResponse(sanitizedPrompt)
                    } finally {
                        // Mark inference completed (even if it threw an exception)
                        markInferenceCompleted()
                    }

                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Generated response in ${duration}ms (${response?.length ?: 0} chars)")

                    // Success - reset error count
                    consecutiveErrors = 0
                    response
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Inference timed out after ${INFERENCE_TIMEOUT_MS / 1000} seconds")
            markInferenceCompleted()
            handleInferenceError("Timeout")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response: ${e.message}", e)
            markInferenceCompleted()
            handleInferenceError(e.message ?: "Unknown error")
            null
        }
    }

    /**
     * Sanitize the prompt to reduce chance of generating invalid tokens.
     * The detokenizer crash (token ID -1) often occurs with unusual characters.
     */
    private fun sanitizePrompt(prompt: String): String {
        return prompt
            // Remove control characters except newlines and tabs
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            // Remove any stray model control tokens that might confuse the tokenizer
            .replace("<eos>", "")
            .replace("<bos>", "")
            .replace("<pad>", "")
            // Limit prompt length
            .take(MAX_PROMPT_LENGTH)
            .trim()
    }

    /**
     * Handle inference errors - track consecutive failures and trigger session recreation.
     */
    private fun handleInferenceError(error: String) {
        consecutiveErrors++
        Log.w(TAG, "Inference error #$consecutiveErrors: $error")

        // Check if this is a detokenizer crash (the specific bug we're fixing)
        val isDetokenizerError = error.contains("RET_CHECK") ||
                error.contains("detokenizer") ||
                error.contains("id >= 0") ||
                error.contains("-1 vs. 0")

        if (isDetokenizerError) {
            Log.e(TAG, "Detected detokenizer crash - marking for session recreation")
            needsSessionRecreation = true
        } else if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "Too many consecutive errors ($consecutiveErrors), marking for session recreation")
            needsSessionRecreation = true
        }
    }

    /**
     * Recreate the LLM session after errors.
     * MediaPipe's own error message says "Please create a new Session and start over."
     */
    private fun recreateSessionInternal() {
        Log.i(TAG, "Recreating MediaPipe LLM session...")
        needsSessionRecreation = false

        val modelPath = currentModelPath
        val backend = currentBackend
        val config = currentConfig

        if (modelPath == null) {
            Log.e(TAG, "Cannot recreate session - no model path saved")
            return
        }

        // Close existing session
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing old session: ${e.message}")
        }
        llmInference = null
        isInitialized = false

        // Recreate with saved parameters
        try {
            Log.d(TAG, "Recreating session with backend: $backend")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(config?.maxTokens ?: MAX_TOKENS)
                .apply {
                    if (backend != null) {
                        setPreferredBackend(backend)
                    }
                }
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            consecutiveErrors = 0

            Log.i(TAG, "Session recreated successfully with $backend backend")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate session: ${e.message}", e)
            initializationError = "Session recreation failed: ${e.message}"
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
     * Internal cleanup - does not acquire mutex (for use when mutex is already held).
     */
    private fun cleanupInternal() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing MediaPipe LLM: ${e.message}")
        }
        llmInference = null
        currentModelPath = null
        currentBackend = null
        currentConfig = null
        isInitialized = false
        consecutiveErrors = 0
        needsSessionRecreation = false
    }

    /**
     * Clean up resources.
     * Uses mutex to wait for any ongoing inference to complete before cleanup.
     */
    suspend fun cleanup() = inferenceMutex.withLock {
        cleanupInternal()
    }

    /**
     * Synchronous cleanup for non-suspend contexts (e.g., onDestroy).
     * Marks as not initialized immediately to reject new inference requests,
     * then waits for any ongoing inference to complete before closing resources.
     *
     * Thread-safe: Uses mutex to prevent race conditions with ongoing inference.
     */
    fun cleanupSync() {
        // Mark as not initialized first to reject new requests
        isInitialized = false

        // Try to acquire the inference mutex to ensure no inference is running
        // Use tryLock with timeout to avoid blocking forever
        val acquired = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                inferenceMutex.lock()
                true
            } ?: false
        }

        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing MediaPipe LLM: ${e.message}")
        }
        llmInference = null
        currentModelPath = null
        currentBackend = null

        if (acquired) {
            inferenceMutex.unlock()
        } else {
            Log.w(TAG, "Could not acquire inference mutex during cleanup - inference may still be running")
        }
    }
}

sealed class MediaPipeLlmStatus {
    object NotInitialized : MediaPipeLlmStatus()
    data class Ready(val modelPath: String) : MediaPipeLlmStatus()
    data class Error(val message: String) : MediaPipeLlmStatus()
}

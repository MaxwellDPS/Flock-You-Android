package com.flockyou.ai

import android.content.Context
import android.util.Log
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.AiModel
import com.flockyou.data.AiSettings
import com.flockyou.data.InferenceConfig
import com.flockyou.data.LlmEnginePreference
import com.flockyou.data.ModelFormat
import com.flockyou.data.model.Detection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM Engine Manager - Central coordinator for all on-device LLM inference.
 *
 * Implements a smart fallback chain:
 * 1. ML Kit GenAI (Gemini Nano) - Alpha API, Pixel 8+ only, best quality
 * 2. MediaPipe LLM (Gemma models) - Stable API, works on most devices
 * 3. Rule-based analysis - Always available fallback
 *
 * The manager automatically selects the best available engine based on:
 * - Device capabilities (Pixel 8+ for Gemini Nano)
 * - Model availability (downloaded models)
 * - Engine health status (tracks failures and recovers)
 *
 * Note: ML Kit GenAI is alpha and may have breaking changes. MediaPipe is stable.
 */
@Singleton
class LlmEngineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geminiNanoClient: GeminiNanoClient,
    private val mediaPipeLlmClient: MediaPipeLlmClient
) {
    companion object {
        private const val TAG = "LlmEngineManager"

        // Engine health thresholds
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val FAILURE_RESET_INTERVAL_MS = 300000L // 5 minutes
    }

    // Current engine status
    private val _engineStatus = MutableStateFlow<EngineStatus>(EngineStatus.NotInitialized)
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    // Active engine tracking
    private val _activeEngine = MutableStateFlow<LlmEngine>(LlmEngine.RULE_BASED)
    val activeEngine: StateFlow<LlmEngine> = _activeEngine.asStateFlow()

    // Engine health tracking
    private val engineHealth = mutableMapOf<LlmEngine, EngineHealth>()
    private val healthMutex = Mutex()

    // Initialization mutex
    private val initMutex = Mutex()
    private var isInitialized = false

    init {
        // Initialize health tracking for all engines
        LlmEngine.entries.forEach { engine ->
            engineHealth[engine] = EngineHealth()
        }
    }

    /**
     * Initialize the LLM engine manager.
     * Respects the user's engine preference from settings.
     *
     * @param preferredModel The model user has selected (may influence engine choice)
     * @param settings Current AI settings
     * @return true if at least one engine is ready (even rule-based)
     */
    suspend fun initialize(
        preferredModel: AiModel,
        settings: AiSettings
    ): Boolean = initMutex.withLock {
        Log.i(TAG, "=== LlmEngineManager.initialize START ===")
        Log.d(TAG, "Preferred model: ${preferredModel.id} (${preferredModel.displayName})")
        Log.d(TAG, "Model format: ${preferredModel.modelFormat}")
        Log.d(TAG, "Engine preference: ${settings.preferredEngine}")

        _engineStatus.value = EngineStatus.Initializing

        // Parse the engine preference
        val enginePreference = LlmEnginePreference.entries.find { it.id == settings.preferredEngine }
            ?: LlmEnginePreference.AUTO

        // Determine the engine based on user preference
        val initResult = when (enginePreference) {
            LlmEnginePreference.GEMINI_NANO -> {
                // User explicitly wants Gemini Nano
                Log.i(TAG, "User selected Gemini Nano engine")
                tryInitializeGeminiNano()
            }
            LlmEnginePreference.MEDIAPIPE -> {
                // User explicitly wants MediaPipe
                Log.i(TAG, "User selected MediaPipe engine")
                tryInitializeMediaPipe(settings, preferredModel)
            }
            LlmEnginePreference.RULE_BASED -> {
                // User explicitly wants rule-based only
                Log.i(TAG, "User selected Rule-Based engine")
                EngineInitResult(LlmEngine.RULE_BASED, true)
            }
            LlmEnginePreference.AUTO -> {
                // Auto mode: determine based on model format and device capabilities
                Log.i(TAG, "Auto mode: determining best engine")
                when (preferredModel.modelFormat) {
                    ModelFormat.MLKIT_GENAI, ModelFormat.AICORE -> {
                        // Try ML Kit GenAI first (Gemini Nano)
                        tryInitializeGeminiNano() ?: tryInitializeMediaPipe(settings)
                    }
                    ModelFormat.TASK -> {
                        // User explicitly selected a MediaPipe model
                        tryInitializeMediaPipe(settings, preferredModel)
                    }
                    ModelFormat.NONE -> {
                        // Rule-based selected - no initialization needed
                        EngineInitResult(LlmEngine.RULE_BASED, true)
                    }
                }
            }
        }

        // Determine final result with fallback handling
        val finalResult = when {
            // If initialization succeeded, use it
            initResult != null && initResult.success -> initResult

            // AUTO mode - try the full fallback chain
            enginePreference == LlmEnginePreference.AUTO -> tryFallbackChain(settings)

            // Specific engine requested but failed - log warning and allow fallback to rule-based
            else -> {
                Log.w(TAG, "Requested engine ${enginePreference.displayName} failed to initialize, falling back to rule-based")
                // Don't try fallback chain for explicit engine preference, just use rule-based
                null
            }
        }

        if (finalResult != null && finalResult.success) {
            _activeEngine.value = finalResult.engine
            _engineStatus.value = EngineStatus.Ready(finalResult.engine)
            isInitialized = true
            Log.i(TAG, "Engine manager initialized successfully with: ${finalResult.engine}")
            return@withLock true
        }

        // Always have rule-based as final fallback
        _activeEngine.value = LlmEngine.RULE_BASED
        _engineStatus.value = EngineStatus.Ready(LlmEngine.RULE_BASED)
        isInitialized = true
        Log.w(TAG, "No LLM engine available, using rule-based fallback")
        return@withLock true
    }

    /**
     * Try to initialize Gemini Nano via ML Kit GenAI.
     */
    private suspend fun tryInitializeGeminiNano(): EngineInitResult? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting Gemini Nano initialization...")

        // Check if engine is healthy
        val health = engineHealth[LlmEngine.GEMINI_NANO]
        if (health != null && !health.isHealthy()) {
            Log.w(TAG, "Gemini Nano engine marked unhealthy, skipping (failures: ${health.consecutiveFailures})")
            return@withContext null
        }

        // Check device support
        if (!geminiNanoClient.isDeviceSupported()) {
            Log.d(TAG, "Device does not support Gemini Nano")
            return@withContext null
        }

        // Check model availability
        val modelStatus = geminiNanoClient.checkModelAvailability()
        Log.d(TAG, "Gemini Nano model status: $modelStatus")

        // Try to initialize
        val initialized = geminiNanoClient.initialize()
        if (initialized && geminiNanoClient.isReady()) {
            Log.i(TAG, "Gemini Nano initialized successfully!")
            recordSuccess(LlmEngine.GEMINI_NANO)
            return@withContext EngineInitResult(LlmEngine.GEMINI_NANO, true)
        }

        Log.w(TAG, "Gemini Nano initialization failed")
        recordFailure(LlmEngine.GEMINI_NANO, "Initialization failed")
        null
    }

    /**
     * Try to initialize MediaPipe LLM with the specified or best available model.
     */
    private suspend fun tryInitializeMediaPipe(
        settings: AiSettings,
        preferredModel: AiModel? = null
    ): EngineInitResult? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting MediaPipe initialization...")

        // Check if engine is healthy
        val health = engineHealth[LlmEngine.MEDIAPIPE]
        if (health != null && !health.isHealthy()) {
            Log.w(TAG, "MediaPipe engine marked unhealthy, skipping (failures: ${health.consecutiveFailures})")
            return@withContext null
        }

        val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)

        // Determine which model to use
        val modelsToTry = if (preferredModel != null && preferredModel.modelFormat == ModelFormat.TASK) {
            listOf(preferredModel) + getMediaPipeModelsByPreference()
        } else {
            getMediaPipeModelsByPreference()
        }

        for (model in modelsToTry) {
            val modelFile = findModelFile(modelDir, model)
            if (modelFile != null) {
                Log.d(TAG, "Found model file: ${modelFile.name} for ${model.displayName}")

                val config = InferenceConfig.fromSettings(settings)
                val initialized = mediaPipeLlmClient.initialize(modelFile, config)

                if (initialized && mediaPipeLlmClient.isReady()) {
                    Log.i(TAG, "MediaPipe initialized with ${model.displayName}")
                    recordSuccess(LlmEngine.MEDIAPIPE)
                    return@withContext EngineInitResult(LlmEngine.MEDIAPIPE, true, model)
                }

                Log.w(TAG, "MediaPipe initialization failed for ${model.displayName}")
            }
        }

        Log.w(TAG, "No MediaPipe-compatible models found")
        recordFailure(LlmEngine.MEDIAPIPE, "No compatible models found")
        null
    }

    /**
     * Try fallback chain when primary engine fails.
     */
    private suspend fun tryFallbackChain(settings: AiSettings): EngineInitResult? {
        Log.d(TAG, "Trying fallback chain...")

        // Try Gemini Nano first (if not already tried)
        val geminiResult = tryInitializeGeminiNano()
        if (geminiResult != null) return geminiResult

        // Try MediaPipe
        val mediaPipeResult = tryInitializeMediaPipe(settings)
        if (mediaPipeResult != null) return mediaPipeResult

        // Rule-based is always available
        return EngineInitResult(LlmEngine.RULE_BASED, true)
    }

    /**
     * Get list of MediaPipe models ordered by preference (smaller/faster first).
     */
    private fun getMediaPipeModelsByPreference(): List<AiModel> = listOf(
        AiModel.GEMMA3_1B,    // Smallest, fastest
        AiModel.GEMMA_2B_CPU, // CPU optimized
        AiModel.GEMMA_2B_GPU  // Larger, better quality
    )

    /**
     * Find the model file for a given model.
     */
    private fun findModelFile(modelDir: File, model: AiModel): File? {
        val extension = AiModel.getFileExtension(model)
        val primaryFile = File(modelDir, "${model.id}$extension")

        if (primaryFile.exists() && primaryFile.length() > 10_000_000) { // > 10MB
            return primaryFile
        }

        // Try alternate extension
        val alternateExtension = if (extension == ".task") ".bin" else ".task"
        val alternateFile = File(modelDir, "${model.id}$alternateExtension")

        if (alternateFile.exists() && alternateFile.length() > 10_000_000) {
            return alternateFile
        }

        return null
    }

    /**
     * Analyze a detection using the best available engine with automatic fallback.
     */
    suspend fun analyzeDetection(detection: Detection): AiAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Ensure initialized
        if (!isInitialized) {
            Log.w(TAG, "Engine manager not initialized, returning error")
            return@withContext AiAnalysisResult(
                success = false,
                error = "LLM engine not initialized",
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = "none"
            )
        }

        // Try current active engine first
        var result = tryAnalyzeWithEngine(_activeEngine.value, detection)

        // If failed, try fallback chain
        if (result == null || !result.success) {
            Log.w(TAG, "Primary engine failed, trying fallback chain")

            for (engine in getFallbackOrder(_activeEngine.value)) {
                result = tryAnalyzeWithEngine(engine, detection)
                if (result != null && result.success) {
                    Log.i(TAG, "Fallback to $engine succeeded")
                    break
                }
            }
        }

        // Return result or error
        result ?: AiAnalysisResult(
            success = false,
            error = "All engines failed",
            processingTimeMs = System.currentTimeMillis() - startTime,
            modelUsed = "none"
        )
    }

    /**
     * Try to analyze with a specific engine.
     */
    private suspend fun tryAnalyzeWithEngine(
        engine: LlmEngine,
        detection: Detection
    ): AiAnalysisResult? {
        return try {
            when (engine) {
                LlmEngine.GEMINI_NANO -> {
                    if (geminiNanoClient.isReady()) {
                        val result = geminiNanoClient.analyzeDetection(detection)
                        if (result.success) {
                            recordSuccess(LlmEngine.GEMINI_NANO)
                        } else {
                            recordFailure(LlmEngine.GEMINI_NANO, result.error ?: "Unknown error")
                        }
                        result
                    } else null
                }
                LlmEngine.MEDIAPIPE -> {
                    if (mediaPipeLlmClient.isReady()) {
                        val result = mediaPipeLlmClient.analyzeDetection(detection, AiModel.RULE_BASED)
                        if (result.success) {
                            recordSuccess(LlmEngine.MEDIAPIPE)
                        } else {
                            recordFailure(LlmEngine.MEDIAPIPE, result.error ?: "Unknown error")
                            // Check for detokenizer crash - MediaPipe will handle session recreation internally
                            if (result.error?.contains("RET_CHECK") == true ||
                                result.error?.contains("detokenizer") == true) {
                                Log.w(TAG, "MediaPipe detokenizer error detected, session will be recreated on next call")
                            }
                        }
                        result
                    } else null
                }
                LlmEngine.RULE_BASED -> {
                    // Rule-based is handled by DetectionAnalyzer
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine $engine threw exception", e)
            recordFailure(engine, e.message ?: "Exception")
            null
        }
    }

    /**
     * Get the fallback order for engines.
     */
    private fun getFallbackOrder(currentEngine: LlmEngine): List<LlmEngine> {
        return when (currentEngine) {
            LlmEngine.GEMINI_NANO -> listOf(LlmEngine.MEDIAPIPE, LlmEngine.RULE_BASED)
            LlmEngine.MEDIAPIPE -> listOf(LlmEngine.GEMINI_NANO, LlmEngine.RULE_BASED)
            LlmEngine.RULE_BASED -> listOf(LlmEngine.GEMINI_NANO, LlmEngine.MEDIAPIPE)
        }
    }

    /**
     * Generate text response using the best available engine.
     * Used for custom prompts (pattern analysis, user explanations, etc.)
     */
    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        // Try with the active engine first
        val activeEng = _activeEngine.value

        when (activeEng) {
            LlmEngine.GEMINI_NANO -> {
                if (geminiNanoClient.isReady()) {
                    try {
                        val response = geminiNanoClient.generateResponse(prompt)
                        if (response != null) {
                            recordSuccess(LlmEngine.GEMINI_NANO)
                            return@withContext response
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Gemini Nano generation failed", e)
                        recordFailure(LlmEngine.GEMINI_NANO, e.message ?: "Generation failed")
                    }
                }
            }
            LlmEngine.MEDIAPIPE -> {
                if (mediaPipeLlmClient.isReady()) {
                    try {
                        val response = mediaPipeLlmClient.generateResponse(prompt)
                        if (response != null) {
                            recordSuccess(LlmEngine.MEDIAPIPE)
                            return@withContext response
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "MediaPipe generation failed", e)
                        recordFailure(LlmEngine.MEDIAPIPE, e.message ?: "Generation failed")
                    }
                }
            }
            LlmEngine.RULE_BASED -> {
                // Rule-based doesn't support free-form generation
                Log.d(TAG, "Rule-based engine doesn't support generateResponse")
            }
        }

        // Try fallback engines
        for (engine in getFallbackOrder(activeEng)) {
            when (engine) {
                LlmEngine.GEMINI_NANO -> {
                    if (geminiNanoClient.isReady()) {
                        try {
                            val response = geminiNanoClient.generateResponse(prompt)
                            if (response != null) {
                                recordSuccess(LlmEngine.GEMINI_NANO)
                                return@withContext response
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Gemini Nano fallback generation failed", e)
                        }
                    }
                }
                LlmEngine.MEDIAPIPE -> {
                    if (mediaPipeLlmClient.isReady()) {
                        try {
                            val response = mediaPipeLlmClient.generateResponse(prompt)
                            if (response != null) {
                                recordSuccess(LlmEngine.MEDIAPIPE)
                                return@withContext response
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "MediaPipe fallback generation failed", e)
                        }
                    }
                }
                LlmEngine.RULE_BASED -> {
                    // Skip - doesn't support generation
                }
            }
        }

        null
    }

    /**
     * Record a successful operation for an engine.
     */
    private suspend fun recordSuccess(engine: LlmEngine) = healthMutex.withLock {
        engineHealth[engine]?.let { health ->
            health.consecutiveFailures = 0
            health.lastSuccessTime = System.currentTimeMillis()
        }
    }

    /**
     * Record a failure for an engine.
     */
    private suspend fun recordFailure(engine: LlmEngine, error: String) = healthMutex.withLock {
        engineHealth[engine]?.let { health ->
            health.consecutiveFailures++
            health.lastFailureTime = System.currentTimeMillis()
            health.lastError = error
            Log.w(TAG, "Engine $engine failure #${health.consecutiveFailures}: $error")
        }
    }

    /**
     * Check if a specific engine is ready for inference.
     */
    fun isEngineReady(engine: LlmEngine): Boolean {
        return when (engine) {
            LlmEngine.GEMINI_NANO -> geminiNanoClient.isReady()
            LlmEngine.MEDIAPIPE -> mediaPipeLlmClient.isReady()
            LlmEngine.RULE_BASED -> true
        }
    }

    /**
     * Get the status of a specific engine.
     */
    fun getEngineHealth(engine: LlmEngine): EngineHealth? = engineHealth[engine]

    /**
     * Reset health tracking for an engine (e.g., after model re-download).
     */
    suspend fun resetEngineHealth(engine: LlmEngine) = healthMutex.withLock {
        engineHealth[engine] = EngineHealth()
        Log.i(TAG, "Reset health for engine: $engine")
    }

    /**
     * Cleanup resources.
     */
    suspend fun cleanup() {
        geminiNanoClient.cleanup()
        mediaPipeLlmClient.cleanup()
        isInitialized = false
        _engineStatus.value = EngineStatus.NotInitialized
        _activeEngine.value = LlmEngine.RULE_BASED
    }

    /**
     * Synchronous cleanup for non-suspend contexts (e.g., onDestroy).
     */
    fun cleanupSync() {
        geminiNanoClient.cleanup()
        mediaPipeLlmClient.cleanupSync()
        isInitialized = false
        _engineStatus.value = EngineStatus.NotInitialized
        _activeEngine.value = LlmEngine.RULE_BASED
    }
}

/**
 * Available LLM engines in order of preference.
 */
enum class LlmEngine(val displayName: String, val isAlpha: Boolean) {
    GEMINI_NANO("Gemini Nano (ML Kit)", true),
    MEDIAPIPE("MediaPipe LLM", false),
    RULE_BASED("Rule-Based", false)
}

/**
 * Engine initialization result.
 */
data class EngineInitResult(
    val engine: LlmEngine,
    val success: Boolean,
    val model: AiModel? = null
)

/**
 * Overall engine manager status.
 */
sealed class EngineStatus {
    object NotInitialized : EngineStatus()
    object Initializing : EngineStatus()
    data class Ready(val activeEngine: LlmEngine) : EngineStatus()
    data class Error(val message: String) : EngineStatus()
}

/**
 * Health tracking for individual engines.
 */
data class EngineHealth(
    var consecutiveFailures: Int = 0,
    var lastSuccessTime: Long = 0,
    var lastFailureTime: Long = 0,
    var lastError: String? = null
) {
    /**
     * Check if the engine is considered healthy.
     * An engine is unhealthy if it has too many consecutive failures
     * and hasn't had a recent success.
     */
    fun isHealthy(): Boolean {
        if (consecutiveFailures < LlmEngineManager.MAX_CONSECUTIVE_FAILURES) {
            return true
        }

        // Allow retry after reset interval
        val now = System.currentTimeMillis()
        return now - lastFailureTime > LlmEngineManager.FAILURE_RESET_INTERVAL_MS
    }

    companion object {
        // Make constants accessible
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val FAILURE_RESET_INTERVAL_MS = 300000L
    }
}

// Extension for accessing constants
private val LlmEngineManager.Companion.MAX_CONSECUTIVE_FAILURES: Int
    get() = 3
private val LlmEngineManager.Companion.FAILURE_RESET_INTERVAL_MS: Long
    get() = 300000L

package com.flockyou.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.flockyou.R
import com.flockyou.data.*
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.repository.DetectionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.*
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.detection.DetectionRegistry
import com.flockyou.detection.profile.DeviceTypeProfile as CentralizedProfile
import com.flockyou.detection.profile.DeviceTypeProfileRegistry
import com.flockyou.detection.profile.PrivacyImpact
import com.flockyou.detection.profile.Recommendation
import com.flockyou.detection.profile.RecommendationUrgency
import com.flockyou.detection.handler.DeviceTypeProfile as HandlerProfile

/**
 * Result type for progressive analysis pipeline.
 * Allows UI to show immediate rule-based results while LLM analysis runs in background.
 *
 * Usage:
 * ```
 * detectionAnalyzer.analyzeProgressively(detection).collect { result ->
 *     when (result) {
 *         is ProgressiveAnalysisResult.RuleBasedResult -> showQuickResult(result.analysis)
 *         is ProgressiveAnalysisResult.LlmResult -> showFinalResult(result.analysis)
 *         is ProgressiveAnalysisResult.Error -> showError(result.error, result.fallbackAnalysis)
 *     }
 * }
 * ```
 */
sealed class ProgressiveAnalysisResult {
    /**
     * Quick rule-based analysis result (< 10ms).
     * Provides instant feedback while LLM analysis runs in background.
     *
     * @property analysis The quick analysis result
     * @property isComplete False - indicates more detailed analysis is coming
     */
    data class RuleBasedResult(
        val analysis: AiAnalysisResult,
        val isComplete: Boolean = false
    ) : ProgressiveAnalysisResult()

    /**
     * Full LLM analysis result.
     * This is the final, detailed analysis from the on-device LLM.
     *
     * @property analysis The comprehensive LLM analysis result
     * @property isComplete True - this is the final result
     */
    data class LlmResult(
        val analysis: AiAnalysisResult,
        val isComplete: Boolean = true
    ) : ProgressiveAnalysisResult()

    /**
     * Error during analysis with optional fallback.
     *
     * @property error Description of what went wrong
     * @property fallbackAnalysis Rule-based analysis to show if LLM fails
     */
    data class Error(
        val error: String,
        val fallbackAnalysis: AiAnalysisResult?
    ) : ProgressiveAnalysisResult()
}

/**
 * AI-powered detection analyzer using LOCAL ON-DEVICE LLM inference only.
 * No data is ever sent to cloud services - all analysis happens on the device.
 *
 * Features:
 * 1. Multiple selectable on-device LLM models (GGUF format via llama.cpp)
 * 2. Pixel NPU support for Gemini Nano
 * 3. Comprehensive rule-based analysis for all 50+ device types
 * 4. Contextual analysis (location patterns, time correlation, clustering)
 * 5. Batch analysis for surveillance density mapping
 * 6. Structured output parsing for programmatic use
 * 7. Analysis feedback tracking for learning
 * 8. Progressive analysis pipeline for instant user feedback
 */
@Singleton
class DetectionAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiSettingsRepository: AiSettingsRepository,
    private val detectionRepository: DetectionRepository,
    private val geminiNanoClient: GeminiNanoClient,
    private val mediaPipeLlmClient: MediaPipeLlmClient,
    private val falsePositiveAnalyzer: FalsePositiveAnalyzer,
    private val llmEngineManager: LlmEngineManager,
    private val detectionRegistry: DetectionRegistry,
    private val feedbackTracker: AnalysisFeedbackTracker,
    private val ruleBasedAnalyzer: RuleBasedAnalyzer
) {
    companion object {
        private const val TAG = "DetectionAnalyzer"
        private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 minutes
        private const val MAX_CACHE_SIZE = 100
        private const val MAX_FEEDBACK_HISTORY_SIZE = 500
        private const val CLUSTER_RADIUS_METERS = 100.0
        private const val DOWNLOAD_TIMEOUT_SECONDS = 300L
        private const val MAX_DOWNLOAD_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        // Cache expiry times based on device stability
        private const val CACHE_EXPIRY_CONSUMER_DEVICE_MS = 2 * 60 * 60 * 1000L // 2 hours for Ring, Nest, etc.
        private const val CACHE_EXPIRY_INFRASTRUCTURE_MS = 2 * 60 * 60 * 1000L // 2 hours for WiFi routers
        private const val CACHE_EXPIRY_UNKNOWN_MS = 30 * 60 * 1000L // 30 min for unknown/suspicious
    }

    private val _modelStatus = MutableStateFlow<AiModelStatus>(AiModelStatus.NotDownloaded)
    val modelStatus: StateFlow<AiModelStatus> = _modelStatus.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    // Thread-safe analysis cache with semantic similarity support
    private data class CacheEntry(
        val result: AiAnalysisResult,
        val timestamp: Long,
        val detection: Detection, // Store detection for similarity comparison
        val expiryMs: Long = CACHE_EXPIRY_MS // Variable expiry based on device type
    )
    private val analysisCache = Collections.synchronizedMap(mutableMapOf<String, CacheEntry>())
    private val cacheMutex = Mutex()

    // Fast-path cache for quick lookups by device type + detection method + protocol
    data class CachedAnalysis(
        val result: AiAnalysisResult,
        val timestamp: Long,
        val expiryMs: Long
    )
    private val fastPathCache = java.util.concurrent.ConcurrentHashMap<String, CachedAnalysis>()

    // Cache statistics tracking
    data class CacheStats(
        var hits: Int = 0,
        var misses: Int = 0,
        var fastPathHits: Int = 0,
        var semanticHits: Int = 0
    ) {
        val hitRate: Float get() = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f
        val totalRequests: Int get() = hits + misses
        fun reset() { hits = 0; misses = 0; fastPathHits = 0; semanticHits = 0 }
    }
    private val cacheStats = CacheStats()

    // Semantic cache settings
    private val semanticCacheEnabled = true
    private val semanticSimilarityThreshold = 0.85f // 85% similarity required for cache hit

    // Common benign device types for cache pre-population
    private val consumerDeviceTypes = setOf(
        DeviceType.RING_DOORBELL,
        DeviceType.NEST_CAMERA,
        DeviceType.WYZE_CAMERA,
        DeviceType.ARLO_CAMERA,
        DeviceType.EUFY_CAMERA,
        DeviceType.BLINK_CAMERA,
        DeviceType.SIMPLISAFE_DEVICE,
        DeviceType.ADT_DEVICE,
        DeviceType.VIVINT_DEVICE,
        DeviceType.AMAZON_SIDEWALK
    )

    private val infrastructureDeviceTypes = setOf(
        DeviceType.BLUETOOTH_BEACON,
        DeviceType.RETAIL_TRACKER
    )

    // Efficient feedback storage with O(1) removal from front
    private val feedbackHistory = ArrayDeque<AnalysisFeedback>(MAX_FEEDBACK_HISTORY_SIZE)
    private val feedbackMutex = Mutex()

    // Reusable HTTP client with connection pooling for model downloads
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(maxIdleConnections = 2, keepAliveDuration = 5, TimeUnit.MINUTES))
            .build()
    }

    // Thread-safe model state using atomic references
    private val currentModelRef = AtomicReference(AiModel.RULE_BASED)
    private val isModelLoadedRef = AtomicBoolean(false)
    private val geminiNanoInitializedRef = AtomicBoolean(false)
    private val modelStateMutex = Mutex()

    // Current model accessor (for backward compatibility)
    private var currentModel: AiModel
        get() = currentModelRef.get()
        set(value) = currentModelRef.set(value)

    private var isModelLoaded: Boolean
        get() = isModelLoadedRef.get()
        set(value) = isModelLoadedRef.set(value)

    private var geminiNanoInitialized: Boolean
        get() = geminiNanoInitializedRef.get()
        set(value) = geminiNanoInitializedRef.set(value)

    private var currentInferenceConfig: InferenceConfig = InferenceConfig(
        maxTokens = 1024,  // Increased to handle larger prompts (input + output combined)
        temperature = 0.7f,
        useGpuAcceleration = true,
        useNpuAcceleration = true
    )

    // Current analysis job for cancellation support
    private var currentAnalysisJob: Job? = null

    // Device capabilities
    private val deviceInfo: DeviceCapabilities by lazy { detectDeviceCapabilities() }

    // Initialize FP analyzer with lazy MediaPipe init callback
    init {
        falsePositiveAnalyzer.setLazyInitCallback {
            initializeMediaPipeForFpAnalysis()
        }
    }

    data class DeviceCapabilities(
        val isPixel8OrNewer: Boolean,
        val hasNpu: Boolean,
        val hasAiCore: Boolean,
        val availableRamMb: Long,
        val supportedModels: List<AiModel>
    )

    private fun detectDeviceCapabilities(): DeviceCapabilities {
        val isPixel8OrNewer = Build.MODEL.lowercase().let { model ->
            model.contains("pixel 8") || model.contains("pixel 9") ||
            model.contains("pixel 10") || model.contains("pixel 11") ||
            model.contains("pixel fold") || model.contains("pixel tablet")
        }

        // NPU available on Pixel 8+ with Tensor G3/G4/G5
        val hasNpu = isPixel8OrNewer && Build.VERSION.SDK_INT >= 34

        // Check if AICore is available for Gemini Nano
        val hasAiCore = try {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            true
        } catch (e: Exception) {
            false
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        // Use totalMem instead of availMem for model compatibility - availMem fluctuates,
        // but we want to show models the device CAN run, not just what fits right now
        val totalRamMb = memInfo.totalMem / (1024 * 1024)

        val supportedModels = AiModel.getAvailableModels(isPixel8OrNewer, hasNpu, totalRamMb)

        return DeviceCapabilities(isPixel8OrNewer, hasNpu, hasAiCore, totalRamMb, supportedModels)
    }

    /**
     * Get device capabilities for UI
     */
    fun getDeviceCapabilities(): DeviceCapabilities = deviceInfo

    /**
     * Get current inference configuration for model calls
     */
    fun getInferenceConfig(): InferenceConfig = currentInferenceConfig

    /**
     * Initialize the selected AI model for inference.
     * Uses mutex to prevent race conditions during concurrent initialization attempts.
     *
     * The initialization follows a smart fallback chain:
     * 1. ML Kit GenAI (Gemini Nano) - Alpha API, Pixel 8+ only, best quality
     * 2. MediaPipe LLM (Gemma models) - Stable API, works on most devices
     * 3. Rule-based analysis - Always available fallback
     */
    suspend fun initializeModel(): Boolean = modelStateMutex.withLock {
        Log.i(TAG, "=== initializeModel START ===")
        withContext(Dispatchers.IO) {
            try {
                val settings = aiSettingsRepository.settings.first()
                Log.d(TAG, "initializeModel settings: enabled=${settings.enabled}, selectedModel=${settings.selectedModel}")

                if (!settings.enabled) {
                    Log.w(TAG, "AI analysis is disabled in settings - returning false")
                    return@withContext false
                }

                _modelStatus.value = AiModelStatus.Initializing
                // Reset model state before initialization
                isModelLoaded = false
                geminiNanoInitialized = false

                // Read the model from settings - this is the source of truth
                // settings.selectedModel contains the model ID that was saved after download
                val modelFromSettings = AiModel.fromId(settings.selectedModel)
                Log.d(TAG, "Model from settings: ${modelFromSettings.id} (${modelFromSettings.displayName})")
                Log.d(TAG, "Current in-memory model: ${currentModel.id}")

                // Always use the model from settings as the source of truth
                currentModel = modelFromSettings

                // Load inference configuration from settings
                currentInferenceConfig = InferenceConfig.fromSettings(settings)
                Log.d(TAG, "Initializing model: ${currentModel.displayName} (${currentModel.id})")
                Log.d(TAG, "Inference config: maxTokens=${currentInferenceConfig.maxTokens}, " +
                    "temp=${currentInferenceConfig.temperature}, " +
                    "gpu=${currentInferenceConfig.useGpuAcceleration}, " +
                    "npu=${currentInferenceConfig.useNpuAcceleration}")

                // Use LlmEngineManager for smart fallback chain initialization
                val initialized = llmEngineManager.initialize(modelFromSettings, settings)

                if (initialized) {
                    // Update local state based on engine manager result
                    val activeEngine = llmEngineManager.activeEngine.value
                    Log.d(TAG, "LlmEngineManager initialized with engine: $activeEngine")

                    when (activeEngine) {
                        LlmEngine.GEMINI_NANO -> {
                            geminiNanoInitialized = true
                            isModelLoaded = true
                            currentModel = AiModel.GEMINI_NANO
                        }
                        LlmEngine.MEDIAPIPE -> {
                            isModelLoaded = true
                            // Keep the model from settings if it's a MediaPipe model
                            if (currentModel.modelFormat != ModelFormat.TASK) {
                                currentModel = AiModel.GEMMA3_1B // Default to smallest
                            }
                        }
                        LlmEngine.RULE_BASED -> {
                            isModelLoaded = true
                            currentModel = AiModel.RULE_BASED
                        }
                    }

                    // Check if user wanted a specific model but got rule-based fallback
                    val wantedGeminiNano = modelFromSettings == AiModel.GEMINI_NANO
                    val gotRuleBasedFallback = activeEngine == LlmEngine.RULE_BASED

                    if (wantedGeminiNano && gotRuleBasedFallback) {
                        // User wanted Gemini Nano but it's not available - check why
                        val nanoStatus = geminiNanoClient.getStatus()
                        Log.w(TAG, "User wanted Gemini Nano but got rule-based fallback. Nano status: $nanoStatus")
                        when (nanoStatus) {
                            is GeminiNanoStatus.NeedsDownload -> {
                                _modelStatus.value = AiModelStatus.NotDownloaded
                                Log.i(TAG, "Gemini Nano needs download - showing NotDownloaded status")
                            }
                            is GeminiNanoStatus.Downloading -> {
                                _modelStatus.value = AiModelStatus.Downloading((nanoStatus as GeminiNanoStatus.Downloading).progress)
                            }
                            is GeminiNanoStatus.Error -> {
                                _modelStatus.value = AiModelStatus.Error((nanoStatus as GeminiNanoStatus.Error).message)
                            }
                            else -> {
                                // Nano might be initializing or in some other state
                                _modelStatus.value = AiModelStatus.Ready
                            }
                        }
                    } else {
                        _modelStatus.value = AiModelStatus.Ready
                    }
                    Log.i(TAG, "Model initialized successfully via LlmEngineManager: $activeEngine, model=${currentModel.displayName}")
                    return@withContext true
                }

                // Fall back to rule-based only if initialization failed (should not happen)
                Log.w(TAG, "LlmEngineManager initialization failed, falling back to rule-based")
                currentModel = AiModel.RULE_BASED
                isModelLoaded = true
                _modelStatus.value = AiModelStatus.Ready
                Log.i(TAG, "Now using rule-based analysis as fallback")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing AI model", e)
                // Reset model state on error to ensure consistent state
                isModelLoaded = false
                geminiNanoInitialized = false
                currentModel = AiModel.RULE_BASED
                _modelStatus.value = AiModelStatus.Error(e.message ?: "Unknown error")
                false
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun tryInitializeGeminiNano(settings: AiSettings): Boolean {
        Log.d(TAG, "Attempting to initialize Gemini Nano via ML Kit GenAI...")

        // First check device support
        if (!geminiNanoClient.isDeviceSupported()) {
            Log.d(TAG, "Device does not support Gemini Nano (requires Pixel 8+ with Android 14+)")
            return false
        }

        // Initialize via GeminiNanoClient (uses ML Kit GenAI)
        val initialized = geminiNanoClient.initialize()

        if (initialized) {
            geminiNanoInitialized = true
            isModelLoaded = true
            Log.i(TAG, "Gemini Nano initialized successfully via ML Kit GenAI")
            return true
        }

        // Check if download is needed
        val status = geminiNanoClient.getStatus()
        Log.w(TAG, "Gemini Nano initialization status: $status")

        when (status) {
            is GeminiNanoStatus.NeedsDownload -> {
                Log.i(TAG, "Gemini Nano model needs to be downloaded first")
                _modelStatus.value = AiModelStatus.NotDownloaded
            }
            is GeminiNanoStatus.Downloading -> {
                Log.i(TAG, "Gemini Nano model is currently downloading")
                _modelStatus.value = AiModelStatus.Downloading(0)
            }
            is GeminiNanoStatus.NotSupported -> {
                Log.w(TAG, "Gemini Nano is not supported on this device")
                _modelStatus.value = AiModelStatus.Error("Gemini Nano not supported on this device")
            }
            is GeminiNanoStatus.Error -> {
                Log.e(TAG, "Gemini Nano error: ${status.message}")
                _modelStatus.value = AiModelStatus.Error(status.message)
            }
            else -> {
                Log.w(TAG, "Unexpected Gemini Nano status: $status")
            }
        }

        return false
    }

    /**
     * Download the Gemini Nano model via ML Kit GenAI.
     * This is only needed on Pixel 8+ devices with AICore support.
     */
    suspend fun downloadGeminiNanoModel(onProgress: (Int) -> Unit): Boolean {
        if (!geminiNanoClient.isDeviceSupported()) {
            Log.w(TAG, "Cannot download Gemini Nano - device not supported")
            return false
        }

        Log.i(TAG, "Starting Gemini Nano model download...")
        val success = geminiNanoClient.downloadModel(onProgress)

        if (success) {
            Log.i(TAG, "Gemini Nano download completed, initializing...")
            // After download, initialize the model
            return geminiNanoClient.initialize()
        }

        return false
    }

    private suspend fun tryInitializeMediaPipeModel(settings: AiSettings): Boolean {
        val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
        // Get the correct file extension based on model's download URL
        val fileExtension = AiModel.getFileExtension(currentModel)
        val modelFile = File(modelDir, "${currentModel.id}$fileExtension")

        // Also check for alternate extension if primary doesn't exist
        val alternateFile = if (fileExtension == ".task") {
            File(modelDir, "${currentModel.id}.bin")
        } else {
            File(modelDir, "${currentModel.id}.task")
        }

        Log.d(TAG, "Looking for model files:")
        Log.d(TAG, "  Primary: ${modelFile.absolutePath} (exists=${modelFile.exists()}, size=${if (modelFile.exists()) modelFile.length() else 0})")
        Log.d(TAG, "  Alternate: ${alternateFile.absolutePath} (exists=${alternateFile.exists()}, size=${if (alternateFile.exists()) alternateFile.length() else 0})")

        // Also list all files in the model directory for debugging
        val allFiles = modelDir.listFiles()
        Log.d(TAG, "  All files in model dir: ${allFiles?.map { "${it.name} (${it.length() / 1024 / 1024}MB)" } ?: "none"}")

        val actualModelFile = when {
            modelFile.exists() && modelFile.length() > 1000 -> modelFile
            alternateFile.exists() && alternateFile.length() > 1000 -> alternateFile
            else -> {
                Log.w(TAG, "Model file not found!")
                Log.w(TAG, "  Expected: ${modelFile.absolutePath}")
                Log.w(TAG, "  Or: ${alternateFile.absolutePath}")
                Log.w(TAG, "  To use this model, download it first.")
                return false
            }
        }

        Log.i(TAG, "Found model file: ${actualModelFile.absolutePath} (${actualModelFile.length() / 1024 / 1024} MB)")
        Log.d(TAG, "Initializing MediaPipe LLM client...")

        // Initialize the MediaPipe LLM client with the model
        val config = InferenceConfig.fromSettings(settings)
        val success = mediaPipeLlmClient.initialize(actualModelFile, config)

        if (success) {
            isModelLoaded = true
            Log.i(TAG, "MediaPipe model initialized successfully!")
            Log.i(TAG, "  Model: ${currentModel.displayName}")
            Log.i(TAG, "  File: ${actualModelFile.name}")
            Log.i(TAG, "  isModelLoaded: $isModelLoaded")
            Log.i(TAG, "  mediaPipeLlmClient.isReady(): ${mediaPipeLlmClient.isReady()}")
        } else {
            Log.e(TAG, "Failed to initialize MediaPipe model!")
            Log.e(TAG, "  Status: ${mediaPipeLlmClient.getStatus()}")
        }

        return success
    }

    /**
     * Initialize MediaPipe specifically for FP analysis.
     * This is called lazily when FP analysis needs LLM but MediaPipe isn't ready
     * (e.g., when main model is GeminiNano or rule-based).
     *
     * Tries to find and load any available MediaPipe-compatible model,
     * preferring smaller models (Gemma 1B) for faster FP analysis.
     */
    private suspend fun initializeMediaPipeForFpAnalysis(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Attempting lazy MediaPipe initialization for FP analysis")

        // If MediaPipe is already ready, nothing to do
        if (mediaPipeLlmClient.isReady()) {
            Log.d(TAG, "MediaPipe already ready for FP analysis")
            return@withContext true
        }

        val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
        val allFiles = modelDir.listFiles() ?: emptyArray()

        // Find available MediaPipe-compatible models (prefer smaller ones)
        val mediaPipeModels = listOf(
            AiModel.GEMMA3_1B,    // Smallest, fastest - preferred for FP
            AiModel.GEMMA_2B_CPU, // Fallback - CPU version
            AiModel.GEMMA_2B_GPU  // Fallback - GPU version
        )

        for (model in mediaPipeModels) {
            val fileExtension = AiModel.getFileExtension(model)
            val modelFile = File(modelDir, "${model.id}$fileExtension")
            val alternateFile = if (fileExtension == ".task") {
                File(modelDir, "${model.id}.bin")
            } else {
                File(modelDir, "${model.id}.task")
            }

            val actualFile = when {
                modelFile.exists() && modelFile.length() > 1000 -> modelFile
                alternateFile.exists() && alternateFile.length() > 1000 -> alternateFile
                else -> null
            }

            if (actualFile != null) {
                Log.i(TAG, "Found MediaPipe model for FP analysis: ${actualFile.name}")
                val settings = aiSettingsRepository.settings.first()
                val config = InferenceConfig.fromSettings(settings)

                val success = mediaPipeLlmClient.initialize(actualFile, config)
                if (success) {
                    Log.i(TAG, "MediaPipe initialized successfully for FP analysis using ${model.displayName}")
                    return@withContext true
                } else {
                    Log.w(TAG, "Failed to initialize ${model.displayName} for FP analysis")
                }
            }
        }

        // Check if any .task or .bin file exists that we could try
        val anyModelFile = allFiles.firstOrNull {
            (it.name.endsWith(".task") || it.name.endsWith(".bin")) && it.length() > 1000
        }

        if (anyModelFile != null) {
            Log.i(TAG, "Found generic model file for FP analysis: ${anyModelFile.name}")
            val settings = aiSettingsRepository.settings.first()
            val config = InferenceConfig.fromSettings(settings)

            val success = mediaPipeLlmClient.initialize(anyModelFile, config)
            if (success) {
                Log.i(TAG, "MediaPipe initialized with generic model for FP analysis")
                return@withContext true
            }
        }

        Log.w(TAG, "No MediaPipe-compatible model found for FP analysis. " +
            "Download a Gemma model to enable LLM-enhanced FP detection.")
        return@withContext false
    }

    // Mutex for analysis to prevent concurrent analysis operations
    private val analysisMutex = Mutex()

    /**
     * Analyze a single detection with full context.
     * Supports cancellation - call cancelAnalysis() to abort.
     * Uses mutex to prevent concurrent analysis operations which could overload the model.
     */
    suspend fun analyzeDetection(detection: Detection): AiAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "=== analyzeDetection START ===")
        Log.d(TAG, "Detection: ${detection.id} (${detection.deviceType})")

        // Lazy initialization: ensure model is loaded before analysis
        // This handles cases where model was downloaded but not yet initialized
        val settings = aiSettingsRepository.settings.first()
        val modelFromSettings = AiModel.fromId(settings.selectedModel)

        Log.d(TAG, "Settings check:")
        Log.d(TAG, "  - enabled: ${settings.enabled}")
        Log.d(TAG, "  - analyzeDetections: ${settings.analyzeDetections}")
        Log.d(TAG, "  - selectedModel: ${settings.selectedModel}")
        Log.d(TAG, "  - modelFromSettings: ${modelFromSettings.id} (${modelFromSettings.displayName})")
        Log.d(TAG, "  - currentModel (in-memory): ${currentModel.id}")
        Log.d(TAG, "  - isModelLoaded: $isModelLoaded")
        Log.d(TAG, "  - mediaPipeLlmClient.isReady(): ${mediaPipeLlmClient.isReady()}")

        if (settings.enabled && modelFromSettings != AiModel.RULE_BASED && !isModelLoaded) {
            Log.i(TAG, "Model not loaded, attempting lazy initialization for: ${modelFromSettings.displayName}")
            val initialized = initializeModel()
            Log.d(TAG, "Lazy initialization result: $initialized")
            Log.d(TAG, "After init - isModelLoaded: $isModelLoaded, mediaPipeReady: ${mediaPipeLlmClient.isReady()}")
            if (!initialized) {
                Log.w(TAG, "Lazy initialization failed, will use rule-based fallback")
            }
        } else {
            Log.d(TAG, "Skipping lazy init: enabled=${settings.enabled}, modelFromSettings=${modelFromSettings.id}, isModelLoaded=$isModelLoaded")
        }

        // FAST-PATH: Try fast-path cache first (O(1) lookup by device type + method + protocol)
        val fastPathResult = tryFastPathCache(detection)
        if (fastPathResult != null) {
            return@withContext fastPathResult.copy(
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Check if already analyzing - return early with informative message
        if (_isAnalyzing.value) {
            Log.d(TAG, "Analysis already in progress, checking cache for: ${detection.id}")
            // Try to serve from cache even if analysis is in progress (reuse settings from above)
            val cacheKey = "${detection.id}_${detection.deviceType}_${detection.threatLevel}_ctx${settings.enableContextualAnalysis}"
            val cached = analysisCache[cacheKey]
            val expiryMs = getCacheExpiryForDevice(detection.deviceType)
            if (cached != null && System.currentTimeMillis() - cached.timestamp < expiryMs) {
                cacheStats.hits++
                return@withContext cached.result.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // Try to acquire the mutex, or return immediately if another analysis is running
        // Track lock ownership to ensure safe unlock in finally block
        var acquiredLock = false
        try {
            acquiredLock = analysisMutex.tryLock()
            if (!acquiredLock) {
                Log.d(TAG, "Analysis mutex busy, returning busy response for: ${detection.id}")
                return@withContext AiAnalysisResult(
                    success = false,
                    error = "Another analysis is in progress. Please wait.",
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }

            // Store reference to current job for cancellation support
            currentAnalysisJob = coroutineContext[Job]

            // Settings already loaded above for lazy initialization
            if (!settings.enabled || !settings.analyzeDetections) {
                Log.w(TAG, "Returning 'AI analysis is disabled': enabled=${settings.enabled}, analyzeDetections=${settings.analyzeDetections}")
                return@withContext AiAnalysisResult(
                    success = false,
                    error = "AI analysis is disabled"
                )
            }

            // Check for cancellation before expensive operations
            coroutineContext.ensureActive()

            // Check cache first before setting analyzing state (include contextual flag and model to avoid serving stale results)
            val cacheKey = "${detection.id}_${detection.deviceType}_${detection.threatLevel}_ctx${settings.enableContextualAnalysis}_${currentModel.id}"
            val cacheExpiryMs = getCacheExpiryForDevice(detection.deviceType)
            val cached = cacheMutex.withLock {
                val entry = analysisCache[cacheKey]
                if (entry != null && System.currentTimeMillis() - entry.timestamp < entry.expiryMs) {
                    entry.result
                } else null
            }
            if (cached != null) {
                cacheStats.hits++
                return@withContext cached.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }

            // Try semantic cache lookup for similar detections (when exact cache misses)
            val similarCached = findSimilarCachedResult(detection)
            if (similarCached != null) {
                // Statistics already tracked in findSimilarCachedResult
                return@withContext similarCached.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }

            // Cache miss - track it
            cacheStats.misses++

            _isAnalyzing.value = true

            // Check for cancellation before gathering context
            coroutineContext.ensureActive()

            // Get contextual data if enabled
            val contextualInsights = if (settings.enableContextualAnalysis) {
                gatherContextualInsights(detection)
            } else null

            // Check for cancellation before generating analysis
            coroutineContext.ensureActive()

            // Generate analysis
            val result = generateAnalysis(detection, contextualInsights, settings)

            // Check for cancellation before FP analysis
            coroutineContext.ensureActive()

            // Run false positive analysis if enabled
            val fpResult = if (settings.enableFalsePositiveFiltering) {
                val contextInfo = buildFpContextInfo(detection, contextualInsights)
                falsePositiveAnalyzer.analyzeForFalsePositive(detection, contextInfo)
            } else null

            // Apply feedback-based adjustments if enabled
            val feedbackAdjustedResult = if (settings.trackAnalysisFeedback) {
                adjustAnalysisWithFeedback(result, detection)
            } else {
                result
            }

            val processingTime = System.currentTimeMillis() - startTime
            val finalResult = feedbackAdjustedResult.copy(
                processingTimeMs = processingTime,
                isFalsePositive = fpResult?.isFalsePositive ?: false,
                falsePositiveConfidence = fpResult?.confidence ?: 0f,
                falsePositiveBanner = fpResult?.bannerMessage,
                falsePositiveReasons = fpResult?.allReasons?.map { it.description } ?: emptyList()
            )

            // Check for cancellation before caching
            coroutineContext.ensureActive()

            // Cache result with variable expiry based on device type
            if (finalResult.success) {
                val deviceExpiryMs = getCacheExpiryForDevice(detection.deviceType)
                cacheMutex.withLock {
                    pruneCache()
                    analysisCache[cacheKey] = CacheEntry(
                        result = finalResult,
                        timestamp = System.currentTimeMillis(),
                        detection = detection,
                        expiryMs = deviceExpiryMs
                    )
                }
                // Also add to fast-path cache for quick lookups
                addToFastPathCache(detection, finalResult)
            }

            finalResult
        } catch (e: CancellationException) {
            Log.d(TAG, "Analysis cancelled for detection: ${detection.id}")
            AiAnalysisResult(
                success = false,
                error = "Analysis cancelled",
                processingTimeMs = System.currentTimeMillis() - startTime,
                wasCancelled = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing detection", e)
            AiAnalysisResult(
                success = false,
                error = e.message ?: "Analysis failed",
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } finally {
            _isAnalyzing.value = false
            currentAnalysisJob = null
            // Only unlock if we successfully acquired the lock
            if (acquiredLock) {
                analysisMutex.unlock()
            }
        }
    }

    /**
     * Cancel any ongoing analysis operation.
     * Safe to call even if no analysis is in progress.
     * Note: The mutex will be properly released by the coroutine's finally block
     * when the cancellation is processed.
     */
    fun cancelAnalysis() {
        currentAnalysisJob?.cancel()
        currentAnalysisJob = null
        _isAnalyzing.value = false
        // Note: We don't manually unlock the mutex here because:
        // 1. We can't safely check if WE own the lock (isLocked is not public API)
        // 2. The coroutine's finally block will handle proper unlock when cancelled
        Log.d(TAG, "Analysis cancellation requested")
    }

    // ==================== PROGRESSIVE ANALYSIS ====================

    /**
     * Analyze a detection progressively, providing instant feedback while LLM analysis runs.
     *
     * This method implements a progressive analysis pipeline:
     * 1. Check cache first - instant return if there's a valid cached result
     * 2. Emit rule-based result immediately (< 10ms) for instant user feedback
     * 3. Launch LLM analysis in background
     * 4. Emit LLM result when complete (replaces rule-based result in UI)
     *
     * Usage:
     * ```
     * detectionAnalyzer.analyzeProgressively(detection).collect { result ->
     *     when (result) {
     *         is ProgressiveAnalysisResult.RuleBasedResult -> updateUI(result.analysis, isPartial = true)
     *         is ProgressiveAnalysisResult.LlmResult -> updateUI(result.analysis, isPartial = false)
     *         is ProgressiveAnalysisResult.Error -> showError(result.error)
     *     }
     * }
     * ```
     *
     * @param detection The detection to analyze
     * @return Flow of progressive analysis results
     */
    fun analyzeProgressively(detection: Detection): Flow<ProgressiveAnalysisResult> = flow {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "=== analyzeProgressively START for ${detection.id} (${detection.deviceType}) ===")

        // Load settings once for the entire pipeline
        val settings = aiSettingsRepository.settings.first()

        // Step 1: Check cache first (instant return if hit)
        val cachedResult = tryGetCachedResult(detection, settings)
        if (cachedResult != null) {
            Log.d(TAG, "Cache hit for progressive analysis - returning cached LLM result")
            emit(ProgressiveAnalysisResult.LlmResult(
                analysis = cachedResult.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime
                ),
                isComplete = true
            ))
            return@flow
        }

        // Step 2: Emit rule-based result immediately for instant user feedback
        Log.d(TAG, "Emitting quick rule-based result...")
        val ruleBasedStartTime = System.currentTimeMillis()
        val quickResult = ruleBasedAnalyzer.analyzeQuick(detection)
        val ruleBasedTime = System.currentTimeMillis() - ruleBasedStartTime
        Log.d(TAG, "Rule-based analysis completed in ${ruleBasedTime}ms")

        emit(ProgressiveAnalysisResult.RuleBasedResult(
            analysis = quickResult.copy(
                processingTimeMs = ruleBasedTime,
                modelUsed = "rule-based-progressive"
            ),
            isComplete = false
        ))

        // Step 3: Check if AI analysis is enabled
        if (!settings.enabled || !settings.analyzeDetections) {
            Log.d(TAG, "AI analysis disabled - rule-based result is final")
            // Re-emit as complete since no LLM analysis will follow
            emit(ProgressiveAnalysisResult.RuleBasedResult(
                analysis = quickResult.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    modelUsed = "rule-based"
                ),
                isComplete = true
            ))
            return@flow
        }

        // Step 4: Check if we should use rule-based only model
        val modelFromSettings = AiModel.fromId(settings.selectedModel)
        if (modelFromSettings == AiModel.RULE_BASED) {
            Log.d(TAG, "Rule-based model selected - rule-based result is final")
            emit(ProgressiveAnalysisResult.RuleBasedResult(
                analysis = quickResult.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    modelUsed = "rule-based"
                ),
                isComplete = true
            ))
            return@flow
        }

        // Step 5: Launch LLM analysis
        Log.d(TAG, "Starting LLM analysis in background...")
        try {
            val llmResult = withContext(Dispatchers.IO) {
                analyzeDetection(detection)
            }

            if (llmResult.success) {
                Log.i(TAG, "LLM analysis completed successfully in ${llmResult.processingTimeMs}ms")
                emit(ProgressiveAnalysisResult.LlmResult(
                    analysis = llmResult.copy(
                        processingTimeMs = System.currentTimeMillis() - startTime
                    ),
                    isComplete = true
                ))
            } else {
                // LLM failed - emit error with rule-based fallback
                Log.w(TAG, "LLM analysis failed: ${llmResult.error}")
                emit(ProgressiveAnalysisResult.Error(
                    error = llmResult.error ?: "LLM analysis failed",
                    fallbackAnalysis = quickResult.copy(
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        modelUsed = "rule-based-fallback"
                    )
                ))
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Progressive analysis cancelled")
            emit(ProgressiveAnalysisResult.Error(
                error = "Analysis cancelled",
                fallbackAnalysis = quickResult
            ))
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Error during LLM analysis", e)
            emit(ProgressiveAnalysisResult.Error(
                error = e.message ?: "Unknown error during analysis",
                fallbackAnalysis = quickResult.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    modelUsed = "rule-based-error-fallback"
                )
            ))
        }
    }

    /**
     * Try to get a cached result for the detection.
     * Returns null if no valid cache entry exists.
     */
    private suspend fun tryGetCachedResult(detection: Detection, settings: AiSettings): AiAnalysisResult? {
        // Try fast-path cache first
        val fastPathResult = tryFastPathCache(detection)
        if (fastPathResult != null) {
            cacheStats.hits++
            cacheStats.fastPathHits++
            return fastPathResult
        }

        // Try semantic cache
        val cacheKey = "${detection.id}_${detection.deviceType}_${detection.threatLevel}_ctx${settings.enableContextualAnalysis}_${currentModel.id}"
        val cached = cacheMutex.withLock {
            val entry = analysisCache[cacheKey]
            if (entry != null && System.currentTimeMillis() - entry.timestamp < entry.expiryMs) {
                entry.result
            } else null
        }

        if (cached != null) {
            cacheStats.hits++
            return cached
        }

        // Try semantic similarity cache for similar detections
        val similarCached = findSimilarCachedResult(detection)
        if (similarCached != null) {
            // Statistics already tracked in findSimilarCachedResult
            return similarCached
        }

        cacheStats.misses++
        return null
    }

    // ==================== FEEDBACK-BASED ANALYSIS ADJUSTMENT ====================

    /**
     * Adjust analysis results based on historical user feedback.
     *
     * This method applies confidence adjustments learned from user interactions:
     * - Lowers confidence if users frequently dismiss/mark as FP for this device type
     * - Raises confidence if users frequently investigate/confirm/report for this device type
     * - Adds contextual notes about historical accuracy
     *
     * @param analysis The original analysis result
     * @param detection The detection being analyzed
     * @return Adjusted analysis result with feedback-based modifications
     */
    private suspend fun adjustAnalysisWithFeedback(
        analysis: AiAnalysisResult,
        detection: Detection
    ): AiAnalysisResult {
        return try {
            // Delegate to the feedback tracker for the actual adjustment
            val adjustedResult = feedbackTracker.adjustAnalysisWithFeedback(
                analysis = analysis,
                deviceType = detection.deviceType
            )

            Log.d(TAG, "Feedback adjustment applied for ${detection.deviceType}: " +
                "confidence ${analysis.confidence} -> ${adjustedResult.confidence}")

            adjustedResult
        } catch (e: Exception) {
            Log.e(TAG, "Error applying feedback adjustment", e)
            // Return original analysis if adjustment fails
            analysis
        }
    }

    /**
     * Record user feedback for a detection analysis.
     * Call this when users take actions on detections to improve future analysis.
     *
     * @param detection The detection the user interacted with
     * @param action The type of action taken (DISMISSED, INVESTIGATED, MARKED_FALSE_POSITIVE, etc.)
     * @param analysis Optional analysis result if one was shown
     */
    suspend fun recordUserFeedback(
        detection: Detection,
        action: UserAction,
        analysis: AiAnalysisResult? = null
    ) {
        try {
            feedbackTracker.recordFeedback(detection, action, analysis)
            Log.d(TAG, "Recorded user feedback: $action for ${detection.deviceType}")
        } catch (e: Exception) {
            Log.e(TAG, "Error recording user feedback", e)
        }
    }

    /**
     * Get the confidence adjustment factor for a device type based on feedback history.
     * Returns a multiplier (0.5-1.2) to apply to confidence scores.
     *
     * @param deviceType The device type to check
     * @return Confidence multiplier
     */
    suspend fun getConfidenceAdjustmentForDeviceType(deviceType: DeviceType): Float {
        return feedbackTracker.getConfidenceAdjustment(deviceType)
    }

    /**
     * Get aggregated feedback statistics for a device type.
     * Useful for displaying historical accuracy in UI.
     *
     * @param deviceType The device type to get stats for
     * @return Flow of FeedbackStats
     */
    fun getFeedbackStatsForDeviceType(deviceType: DeviceType) =
        feedbackTracker.getFeedbackStats(deviceType)

    /**
     * Get overall accuracy statistics across all device types.
     * Useful for displaying in settings/about screen.
     */
    suspend fun getOverallAccuracyStats() = feedbackTracker.getOverallAccuracyStats()

    /**
     * Get the feedback tracker for direct access if needed.
     */
    fun getFeedbackTracker(): AnalysisFeedbackTracker = feedbackTracker

    // ==================== FALSE POSITIVE ANALYSIS ====================

    /**
     * Check a single detection for false positive likelihood.
     * Returns the FP result with banner message if applicable.
     */
    suspend fun checkForFalsePositive(detection: Detection): FalsePositiveResult {
        return falsePositiveAnalyzer.analyzeForFalsePositive(detection)
    }

    /**
     * Filter a list of detections, removing likely false positives.
     * Returns filtered results with FP explanations.
     *
     * @param detections List of detections to filter
     * @param confidenceThreshold Minimum FP confidence to filter (0.0-1.0, default 0.6)
     */
    suspend fun filterFalsePositives(
        detections: List<Detection>,
        confidenceThreshold: Float = 0.6f
    ): FilteredDetections {
        return falsePositiveAnalyzer.filterFalsePositives(
            detections = detections,
            threshold = confidenceThreshold
        )
    }

    /**
     * Batch analyze detections for false positives.
     * Returns a map of detection ID to FP result.
     */
    suspend fun batchCheckFalsePositives(
        detections: List<Detection>
    ): Map<String, FalsePositiveResult> {
        return falsePositiveAnalyzer.analyzeMultiple(detections)
    }

    /**
     * Get the false positive analyzer for direct access if needed.
     */
    fun getFalsePositiveAnalyzer(): FalsePositiveAnalyzer = falsePositiveAnalyzer

    private suspend fun gatherContextualInsights(detection: Detection): ContextualInsights {
        val allDetections = detectionRepository.getAllDetectionsSnapshot()

        // Find detections at same location
        val detectionLat = detection.latitude
        val detectionLon = detection.longitude
        val nearbyDetections = if (detectionLat != null && detectionLon != null) {
            allDetections.filter { other ->
                val otherLat = other.latitude
                val otherLon = other.longitude
                otherLat != null && otherLon != null &&
                calculateDistance(detectionLat, detectionLon, otherLat, otherLon) < CLUSTER_RADIUS_METERS
            }
        } else emptyList()

        // Find same device seen before
        val sameDeviceHistory = allDetections.filter { other ->
            (detection.macAddress != null && other.macAddress == detection.macAddress) ||
            (detection.ssid != null && other.ssid == detection.ssid)
        }.sortedBy { it.timestamp }

        // Analyze time patterns
        val timePattern = analyzeTimePattern(sameDeviceHistory)

        // Detect clusters
        val clusterInfo = if (nearbyDetections.size > 2) {
            "Part of ${nearbyDetections.size}-device surveillance cluster within ${CLUSTER_RADIUS_METERS.toInt()}m"
        } else null

        // Historical context
        val historicalContext = if (sameDeviceHistory.size > 1) {
            val firstSeen = sameDeviceHistory.first().timestamp
            val daysSince = (System.currentTimeMillis() - firstSeen) / (1000 * 60 * 60 * 24)
            "First seen $daysSince days ago, detected ${sameDeviceHistory.size} times"
        } else null

        return ContextualInsights(
            isKnownLocation = nearbyDetections.size > 1,
            locationPattern = if (nearbyDetections.size > 1) "Seen ${nearbyDetections.size} times at this location" else null,
            timePattern = timePattern,
            clusterInfo = clusterInfo,
            historicalContext = historicalContext
        )
    }

    private fun analyzeTimePattern(detections: List<Detection>): String? {
        if (detections.size < 3) return null

        val hours = detections.map { detection ->
            java.util.Calendar.getInstance().apply {
                timeInMillis = detection.timestamp
            }.get(java.util.Calendar.HOUR_OF_DAY)
        }

        val avgHour = hours.average()
        return when {
            avgHour < 6 -> "Usually active late night (midnight-6am)"
            avgHour < 12 -> "Usually active in morning"
            avgHour < 18 -> "Usually active in afternoon"
            else -> "Usually active in evening/night"
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Build context information for false positive analysis.
     * Uses contextual insights and detection location to determine user context.
     */
    private fun buildFpContextInfo(
        detection: Detection,
        contextualInsights: ContextualInsights?
    ): FpContextInfo {
        // Determine time of day
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isNightTime = currentHour < 6 || currentHour >= 22

        // Check if at a known/familiar location
        val isKnownLocation = contextualInsights?.isKnownLocation ?: false

        return FpContextInfo(
            isAtHome = isKnownLocation && detection.latitude != null,
            homeLatitude = if (isKnownLocation) detection.latitude else null,
            homeLongitude = if (isKnownLocation) detection.longitude else null,
            isAtWork = false, // Would need user preference storage
            isKnownSafeArea = isKnownLocation,
            isNightTime = isNightTime,
            recentlyTraveled = false // Would need location history
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun generateAnalysis(
        detection: Detection,
        contextualInsights: ContextualInsights?,
        settings: AiSettings // Reserved for future use with inference configuration
    ): AiAnalysisResult {
        Log.i(TAG, "=== generateAnalysis START ===")
        Log.d(TAG, "State at generateAnalysis:")
        Log.d(TAG, "  - currentModel: ${currentModel.id} (${currentModel.displayName})")
        Log.d(TAG, "  - activeEngine: ${llmEngineManager.activeEngine.value}")
        Log.d(TAG, "  - isModelLoaded: $isModelLoaded")
        Log.d(TAG, "  - geminiNanoClient.isReady(): ${geminiNanoClient.isReady()}")
        Log.d(TAG, "  - mediaPipeLlmClient.isReady(): ${mediaPipeLlmClient.isReady()}")

        // Sync active engine to best available before analysis
        llmEngineManager.syncActiveEngine()

        // Use LlmEngineManager for analysis with automatic fallback
        val activeEngine = llmEngineManager.activeEngine.value
        Log.d(TAG, "Using LlmEngineManager with active engine: $activeEngine")

        // Check if any LLM engine is ready (not just the active one)
        // This allows fallback to work even if the primary engine is rule-based
        val geminiReady = llmEngineManager.isEngineReady(LlmEngine.GEMINI_NANO)
        val mediaPipeReady = llmEngineManager.isEngineReady(LlmEngine.MEDIAPIPE)
        val anyLlmReady = geminiReady || mediaPipeReady
        Log.d(TAG, "  geminiReady=$geminiReady, mediaPipeReady=$mediaPipeReady, anyLlmReady=$anyLlmReady")

        // Try LLM if active engine is LLM OR if any LLM engine is ready
        // The LlmEngineManager will handle the fallback chain internally
        val shouldTryLlm = (activeEngine != LlmEngine.RULE_BASED) || anyLlmReady

        if (shouldTryLlm) {
            Log.d(TAG, "Attempting LLM analysis (activeEngine=$activeEngine, anyLlmReady=$anyLlmReady)")
            val llmResult = llmEngineManager.analyzeDetection(detection)

            if (llmResult.success) {
                Log.i(TAG, "LLM analysis succeeded! Model: ${llmResult.modelUsed}, Response length: ${llmResult.analysis?.length ?: 0}")
                // Enhance with contextual insights if available
                return if (contextualInsights != null) {
                    llmResult.copy(
                        analysis = buildString {
                            append(llmResult.analysis ?: "")
                            appendLine()
                            appendLine("### Contextual Analysis")
                            contextualInsights.locationPattern?.let { appendLine("- Location: $it") }
                            contextualInsights.timePattern?.let { appendLine("- Time Pattern: $it") }
                            contextualInsights.clusterInfo?.let { appendLine("- Cluster: $it") }
                            contextualInsights.historicalContext?.let { appendLine("- History: $it") }
                        },
                        structuredData = buildStructuredData(detection, contextualInsights)
                    )
                } else {
                    llmResult.copy(structuredData = buildStructuredData(detection, null))
                }
            }

            // Log fallback reason
            Log.w(TAG, "LLM analysis failed (${llmResult.error}), using rule-based fallback")
        } else {
            Log.d(TAG, "No LLM engines available, using rule-based analysis")
        }

        // Use comprehensive rule-based analysis as fallback
        return generateRuleBasedAnalysis(detection, contextualInsights)
    }

    private fun buildStructuredData(
        detection: Detection,
        contextualInsights: ContextualInsights?
    ): StructuredAnalysis {
        val deviceInfo = getComprehensiveDeviceInfo(detection.deviceType)
        val dataCollection = getDataCollectionCapabilities(detection.deviceType)
        val recommendations = getSmartRecommendations(detection, contextualInsights)

        return StructuredAnalysis(
            deviceCategory = deviceInfo.category,
            surveillanceType = deviceInfo.surveillanceType,
            dataCollectionTypes = dataCollection,
            riskScore = calculateRiskScore(detection, contextualInsights),
            riskFactors = getRiskFactors(detection, contextualInsights),
            mitigationActions = recommendations.mapIndexed { index, rec ->
                MitigationAction(
                    action = rec,
                    priority = when (index) {
                        0 -> ActionPriority.IMMEDIATE
                        1 -> ActionPriority.HIGH
                        else -> ActionPriority.MEDIUM
                    },
                    description = rec
                )
            },
            contextualInsights = contextualInsights
        )
    }

    // ==================== ENRICHED PROMPT SELECTION ====================

    /**
     * Select the best prompt template based on detection type and available enriched data.
     * Priority order:
     * 1. Enriched detector-specific prompts (when enriched data is available)
     * 2. Profile AI prompt template (device-type specific templates from DeviceTypeProfile)
     * 3. Chain-of-thought reasoning (for high-threat or complex detections)
     * 4. Few-shot prompting (for standard detections)
     */
    private fun selectPromptForDetection(
        detection: Detection,
        contextualInsights: ContextualInsights?,
        enrichedData: EnrichedDetectorData?
    ): String {
        // If we have enriched data, use detector-specific prompts
        if (enrichedData != null) {
            return when (enrichedData) {
                is EnrichedDetectorData.Cellular ->
                    PromptTemplates.buildCellularEnrichedPrompt(detection, enrichedData.analysis)
                is EnrichedDetectorData.Gnss ->
                    PromptTemplates.buildGnssEnrichedPrompt(detection, enrichedData.analysis)
                is EnrichedDetectorData.Ultrasonic ->
                    PromptTemplates.buildUltrasonicEnrichedPrompt(detection, enrichedData.analysis)
                is EnrichedDetectorData.WifiFollowing ->
                    PromptTemplates.buildWifiFollowingEnrichedPrompt(detection, enrichedData.analysis)
                is EnrichedDetectorData.Satellite ->
                    PromptTemplates.buildSatelliteEnrichedPrompt(detection, enrichedData)
            }
        }

        // Check for profile-based AI prompt template
        val profilePrompt = getProfileAiPromptTemplate(detection.deviceType)
        if (profilePrompt != null) {
            return buildProfileBasedPrompt(detection, profilePrompt, contextualInsights)
        }

        // For high-threat or complex detections, use chain-of-thought reasoning
        if (detection.threatLevel == ThreatLevel.CRITICAL ||
            detection.threatLevel == ThreatLevel.HIGH ||
            contextualInsights?.clusterInfo != null) {
            return PromptTemplates.buildChainOfThoughtPrompt(detection, enrichedData)
        }

        // For standard detections, use few-shot prompting
        return PromptTemplates.buildFewShotPrompt(detection, enrichedData)
    }

    /**
     * Get AI prompt template from profile system.
     * Only DeviceTypeProfileRegistry (centralized profile) has aiPromptTemplate.
     */
    private fun getProfileAiPromptTemplate(deviceType: DeviceType): String? {
        // Use DeviceTypeProfileRegistry for AI prompt templates
        val profile = DeviceTypeProfileRegistry.getProfile(deviceType)
        return profile.aiPromptTemplate
    }

    /**
     * Build a prompt using the profile's AI prompt template.
     * Wraps the template with detection context for comprehensive analysis.
     */
    private fun buildProfileBasedPrompt(
        detection: Detection,
        profileTemplate: String,
        contextualInsights: ContextualInsights?
    ): String {
        val contextSection = contextualInsights?.let {
            buildString {
                appendLine("\n=== CONTEXTUAL DATA ===")
                it.locationPattern?.let { appendLine("Location: $it") }
                it.timePattern?.let { appendLine("Time Pattern: $it") }
                it.clusterInfo?.let { appendLine("Cluster: $it") }
                it.historicalContext?.let { appendLine("History: $it") }
            }
        } ?: ""

        return """<start_of_turn>user
You are a privacy security expert analyzing a detected surveillance device.

$profileTemplate

=== DETECTION DATA ===
Device Type: ${detection.deviceType.displayName}
Protocol: ${detection.protocol.displayName}
Detection Method: ${detection.detectionMethod.displayName}
Signal: ${detection.signalStrength.displayName} (${detection.rssi} dBm)
Threat Level: ${detection.threatLevel.displayName}
Threat Score: ${detection.threatScore}/100
${detection.manufacturer?.let { "Manufacturer: $it" } ?: ""}
${detection.deviceName?.let { "Device Name: $it" } ?: ""}
${detection.ssid?.let { "Network SSID: $it" } ?: ""}
${detection.matchedPatterns?.let { "Matched Patterns: $it" } ?: ""}
$contextSection

Provide your analysis with specific recommendations for this detection.
<end_of_turn>
<start_of_turn>model
"""
    }

    /**
     * Generate user-friendly explanation for a detection at the specified level.
     * Available levels: SIMPLE (for non-technical users), STANDARD, TECHNICAL.
     */
    suspend fun generateUserFriendlyExplanation(
        detection: Detection,
        level: PromptTemplates.ExplanationLevel = PromptTemplates.ExplanationLevel.STANDARD
    ): UserFriendlyExplanation {
        // Try MediaPipe LLM if ready (has prompt-based generation)
        if (mediaPipeLlmClient.isReady()) {
            val prompt = PromptTemplates.buildUserFriendlyPrompt(detection, null, level)
            val llmResponse = mediaPipeLlmClient.generateResponse(prompt)

            // Parse LLM response if available
            if (llmResponse != null) {
                return LlmOutputParser.parseUserFriendlyExplanation(llmResponse)
            }
        }

        // Fallback to rule-based (also used for Gemini Nano since it doesn't have prompt-based API)
        return generateRuleBasedExplanation(detection, level)
    }

    /**
     * Generate rule-based user-friendly explanation as fallback.
     */
    private fun generateRuleBasedExplanation(
        detection: Detection,
        level: PromptTemplates.ExplanationLevel
    ): UserFriendlyExplanation {
        val deviceInfo = getComprehensiveDeviceInfo(detection.deviceType)
        val recommendations = getSmartRecommendations(detection, null)

        val (headline, whatIsHappening, whyItMatters) = when (level) {
            PromptTemplates.ExplanationLevel.SIMPLE -> Triple(
                "Device Found Nearby",
                "A ${detection.deviceType.displayName} was detected. " +
                    "This is a device that ${deviceInfo.simpleDescription ?: "can monitor activity"}.",
                deviceInfo.simplePrivacyImpact ?: "This device may be recording information about you."
            )
            PromptTemplates.ExplanationLevel.STANDARD -> Triple(
                "${detection.deviceType.displayName} Detected",
                deviceInfo.description,
                "Privacy Impact: ${detection.threatLevel.displayName}. ${deviceInfo.privacyImpact ?: ""}"
            )
            PromptTemplates.ExplanationLevel.TECHNICAL -> Triple(
                "${detection.deviceType.displayName} - ${detection.detectionMethod.displayName}",
                "${deviceInfo.description}\n\nDetection: ${detection.detectionMethod.description}",
                "Threat Score: ${detection.threatScore}/100. ${detection.matchedPatterns ?: ""}"
            )
        }

        val urgency = when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> UrgencyLevel.IMMEDIATE
            ThreatLevel.HIGH -> UrgencyLevel.HIGH
            ThreatLevel.MEDIUM -> UrgencyLevel.MEDIUM
            else -> UrgencyLevel.LOW
        }

        return UserFriendlyExplanation(
            headline = headline,
            whatIsHappening = whatIsHappening,
            whyItMatters = whyItMatters,
            whatToDo = recommendations.take(3),
            urgency = urgency
        )
    }

    // ==================== CROSS-DETECTION PATTERN RECOGNITION ====================

    /**
     * Analyze patterns across multiple detections to identify coordinated surveillance,
     * following patterns, timing correlations, and geographic clustering.
     *
     * @param timeWindowMs Time window to consider (default: 1 hour)
     * @return List of identified patterns with confidence scores
     */
    suspend fun analyzePatterns(
        timeWindowMs: Long = 3600000L // 1 hour default
    ): List<PatternInsight> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cutoffTime = now - timeWindowMs
        val recentDetections = detectionRepository.getAllDetectionsSnapshot()
            .filter { it.timestamp >= cutoffTime }
            .sortedByDescending { it.timestamp }

        if (recentDetections.size < 2) {
            return@withContext emptyList()
        }

        val patterns = mutableListOf<PatternInsight>()

        // Try LLM-based pattern analysis if available
        if (mediaPipeLlmClient.isReady()) {
            val timeDesc = when {
                timeWindowMs <= 3600000L -> "past hour"
                timeWindowMs <= 86400000L -> "past ${timeWindowMs / 3600000} hours"
                else -> "past ${timeWindowMs / 86400000} days"
            }

            val prompt = PromptTemplates.buildPatternRecognitionPrompt(recentDetections, timeDesc)
            val response = mediaPipeLlmClient.generateResponse(prompt)

            if (response != null) {
                val llmPatterns = LlmOutputParser.parsePatternAnalysis(response)
                if (llmPatterns.isNotEmpty()) {
                    return@withContext llmPatterns
                }
            }
        }

        // Fall back to rule-based pattern detection
        patterns.addAll(detectCoordinatedSurveillance(recentDetections))
        patterns.addAll(detectFollowingPattern(recentDetections))
        patterns.addAll(detectTimingCorrelation(recentDetections))
        patterns.addAll(detectGeographicClustering(recentDetections))
        patterns.addAll(detectEscalationPattern(recentDetections))
        patterns.addAll(detectMultimodalSurveillance(recentDetections))

        patterns.sortedByDescending { it.confidence }
    }

    /**
     * Rule-based: Detect coordinated surveillance (multiple related devices active together)
     */
    private fun detectCoordinatedSurveillance(detections: List<Detection>): List<PatternInsight> {
        // Group by time windows (5 minute buckets)
        val timeGroups = detections.groupBy { it.timestamp / 300000 }

        val patterns = mutableListOf<PatternInsight>()

        for ((_, group) in timeGroups) {
            if (group.size >= 3) {
                // Multiple devices detected within same 5-minute window
                val deviceTypes = group.map { it.deviceType }.distinct()
                if (deviceTypes.size >= 2) {
                    patterns.add(PatternInsight(
                        patternType = PatternType.COORDINATED_SURVEILLANCE,
                        affectedDetections = group.map { it.id },
                        description = "${group.size} devices detected simultaneously: ${deviceTypes.joinToString { it.displayName }}",
                        implication = "Multiple surveillance devices operating together may indicate coordinated monitoring",
                        confidence = minOf(0.4f + (group.size * 0.1f), 0.9f)
                    ))
                }
            }
        }

        return patterns
    }

    /**
     * Rule-based: Detect if devices appear to be following the user
     */
    private fun detectFollowingPattern(detections: List<Detection>): List<PatternInsight> {
        // Group detections by device (MAC or SSID)
        val byDevice = detections.groupBy { it.macAddress ?: it.ssid ?: it.deviceName }
            .filter { it.key != null && it.value.size >= 2 }

        val patterns = mutableListOf<PatternInsight>()

        for ((_, deviceDetections) in byDevice) {
            val locations = deviceDetections.mapNotNull {
                if (it.latitude != null && it.longitude != null) it.latitude to it.longitude
                else null
            }.distinct()

            if (locations.size >= 2) {
                // Same device detected at multiple distinct locations
                val firstDet = deviceDetections.first()
                patterns.add(PatternInsight(
                    patternType = PatternType.FOLLOWING_PATTERN,
                    affectedDetections = deviceDetections.map { it.id },
                    description = "${firstDet.deviceType.displayName} detected at ${locations.size} different locations",
                    implication = "This device may be following you or is mobile surveillance equipment",
                    confidence = minOf(0.5f + (locations.size * 0.15f), 0.95f)
                ))
            }
        }

        return patterns
    }

    /**
     * Rule-based: Detect timing correlations (devices activating at similar times)
     */
    private fun detectTimingCorrelation(detections: List<Detection>): List<PatternInsight> {
        if (detections.size < 4) return emptyList()

        // Check for regular intervals
        val sorted = detections.sortedBy { it.timestamp }
        val intervals = sorted.zipWithNext { a, b -> b.timestamp - a.timestamp }

        if (intervals.size < 3) return emptyList()

        val avgInterval = intervals.average()
        val variance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        // Low variance indicates regular timing
        if (stdDev < avgInterval * 0.3 && avgInterval < 600000) { // Less than 30% variance, intervals < 10 min
            return listOf(PatternInsight(
                patternType = PatternType.TIMING_CORRELATION,
                affectedDetections = detections.map { it.id },
                description = "Detections occurring at regular ${(avgInterval / 60000).toInt()}-minute intervals",
                implication = "Regular timing suggests automated or scheduled surveillance sweeps",
                confidence = 0.7f - (stdDev / avgInterval).toFloat().coerceIn(0f, 0.3f)
            ))
        }

        return emptyList()
    }

    /**
     * Rule-based: Detect geographic clustering
     */
    private fun detectGeographicClustering(detections: List<Detection>): List<PatternInsight> {
        val locatedDetections = detections.filter { it.latitude != null && it.longitude != null }
        if (locatedDetections.size < 3) return emptyList()

        // Find clusters using simple distance-based grouping
        val clusters = mutableListOf<List<Detection>>()
        val used = mutableSetOf<String>()

        for (detection in locatedDetections) {
            if (detection.id in used) continue

            // Get coordinates with null safety
            val detLat = detection.latitude
            val detLon = detection.longitude
            if (detLat == null || detLon == null) continue

            val cluster = locatedDetections.filter { other ->
                val otherLat = other.latitude
                val otherLon = other.longitude
                other.id !in used &&
                otherLat != null && otherLon != null &&
                calculateDistance(detLat, detLon, otherLat, otherLon) < CLUSTER_RADIUS_METERS
            }

            if (cluster.size >= 3) {
                clusters.add(cluster)
                used.addAll(cluster.map { it.id })
            }
        }

        return clusters.map { cluster ->
            val types = cluster.map { it.deviceType.displayName }.distinct()
            PatternInsight(
                patternType = PatternType.GEOGRAPHIC_CLUSTERING,
                affectedDetections = cluster.map { it.id },
                description = "${cluster.size} devices clustered within ${CLUSTER_RADIUS_METERS.toInt()}m: ${types.joinToString()}",
                implication = "Concentrated surveillance infrastructure in this area",
                confidence = minOf(0.5f + (cluster.size * 0.1f), 0.9f)
            )
        }
    }

    /**
     * Rule-based: Detect escalation in threat levels over time
     */
    private fun detectEscalationPattern(detections: List<Detection>): List<PatternInsight> {
        if (detections.size < 3) return emptyList()

        val sorted = detections.sortedBy { it.timestamp }
        val scores = sorted.map { it.threatScore }

        // Check if threat scores are generally increasing
        var increases = 0
        var decreases = 0
        for (i in 1 until scores.size) {
            if (scores[i] > scores[i - 1]) increases++
            else if (scores[i] < scores[i - 1]) decreases++
        }

        if (increases > decreases * 2 && increases >= 3) {
            val firstScore = scores.first()
            val lastScore = scores.last()
            return listOf(PatternInsight(
                patternType = PatternType.ESCALATION_PATTERN,
                affectedDetections = sorted.map { it.id },
                description = "Threat scores increasing from $firstScore to $lastScore",
                implication = "Surveillance activity appears to be escalating in your area",
                confidence = 0.6f + (increases.toFloat() / scores.size * 0.3f)
            ))
        }

        return emptyList()
    }

    /**
     * Rule-based: Detect multimodal surveillance (different detection types targeting same area)
     */
    private fun detectMultimodalSurveillance(detections: List<Detection>): List<PatternInsight> {
        val protocols = detections.map { it.protocol }.distinct()

        if (protocols.size >= 3) {
            return listOf(PatternInsight(
                patternType = PatternType.MULTIMODAL_SURVEILLANCE,
                affectedDetections = detections.map { it.id },
                description = "Multiple surveillance modalities active: ${protocols.joinToString { it.displayName }}",
                implication = "Comprehensive surveillance using multiple technologies (WiFi, cellular, audio, etc.)",
                confidence = 0.7f + (protocols.size * 0.05f).coerceAtMost(0.2f)
            ))
        }

        return emptyList()
    }

    /**
     * Comprehensive rule-based analysis covering all 50+ device types.
     * Uses the new enterprise description system for actionable, context-aware descriptions.
     */
    private fun generateRuleBasedAnalysis(
        detection: Detection,
        contextualInsights: ContextualInsights?
    ): AiAnalysisResult {
        // Convert local ContextualInsights to PromptTemplates version
        val templateInsights = contextualInsights?.let {
            PromptTemplates.ContextualInsights(
                isKnownLocation = it.isKnownLocation,
                locationPattern = it.locationPattern,
                timePattern = it.timePattern,
                clusterInfo = it.clusterInfo,
                historicalContext = it.historicalContext
            )
        }

        // Generate enterprise-grade description
        val enterpriseDesc = PromptTemplates.generateEnterpriseDescription(
            detection = detection,
            enrichedData = null, // Enriched data handled separately via LLM path
            contextualInsights = templateInsights,
            falsePositiveResult = null // Will be populated by FP analyzer
        )

        // Format the enterprise description as user-facing analysis
        val analysis = PromptTemplates.formatEnterpriseDescriptionForUser(enterpriseDesc)

        // Generate recommendations from enterprise description
        val recommendations = mutableListOf<String>()
        enterpriseDesc.immediateAction?.let { recommendations.add(it.action) }
        recommendations.add(enterpriseDesc.monitoringRecommendation)
        enterpriseDesc.documentationSuggestion?.let { recommendations.add(it) }

        // Get legacy device info for structured data compatibility
        val deviceInfo = getComprehensiveDeviceInfo(detection.deviceType)
        val dataCollection = getDataCollectionCapabilities(detection.deviceType)

        // Build structured data with enterprise insights
        val structuredData = StructuredAnalysis(
            deviceCategory = deviceInfo.category,
            surveillanceType = deviceInfo.surveillanceType,
            dataCollectionTypes = dataCollection,
            riskScore = calculateRiskScore(detection, contextualInsights),
            riskFactors = getRiskFactors(detection, contextualInsights),
            mitigationActions = recommendations.mapIndexed { index, rec ->
                MitigationAction(
                    action = rec,
                    priority = when (index) {
                        0 -> if (enterpriseDesc.immediateAction != null) ActionPriority.IMMEDIATE else ActionPriority.MEDIUM
                        1 -> ActionPriority.HIGH
                        else -> ActionPriority.MEDIUM
                    },
                    description = rec
                )
            },
            contextualInsights = contextualInsights,
            // Add enterprise description metadata
            enterpriseDescription = enterpriseDesc
        )

        // Adjust confidence based on false positive likelihood
        val adjustedConfidence = if (enterpriseDesc.isMostLikelyBenign) {
            // Lower confidence when likely false positive
            (0.95f * (1f - enterpriseDesc.falsePositiveLikelihood / 100f)).coerceIn(0.3f, 0.95f)
        } else {
            0.95f
        }

        return AiAnalysisResult(
            success = true,
            analysis = analysis,
            recommendations = recommendations.distinct().take(6),
            confidence = adjustedConfidence,
            modelUsed = "rule-based-enterprise", // Indicates enhanced rule-based with enterprise templates
            wasOnDevice = true,
            structuredData = structuredData,
            // Add enterprise-specific fields
            isFalsePositiveLikely = enterpriseDesc.isMostLikelyBenign,
            falsePositiveLikelihoodPercent = enterpriseDesc.falsePositiveLikelihood,
            simpleExplanation = enterpriseDesc.simpleExplanation,
            technicalDetails = enterpriseDesc.technicalDetails
        )
    }

    private data class DeviceInfo(
        val description: String,
        val category: String,
        val surveillanceType: String,
        val typicalOperator: String? = null,
        val legalFramework: String? = null,
        // User-friendly fields for different explanation levels
        val simpleDescription: String? = null,      // Short, simple language for non-technical users
        val simplePrivacyImpact: String? = null,    // Privacy impact in simple terms
        val privacyImpact: String? = null           // Standard privacy impact explanation
    )

    /**
     * Extension function to convert CentralizedProfile to local DeviceInfo.
     */
    private fun CentralizedProfile.toDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            description = this.description,
            category = this.category,
            surveillanceType = this.surveillanceType,
            typicalOperator = this.typicalOperator,
            legalFramework = this.legalFramework,
            simpleDescription = this.simpleDescription,
            simplePrivacyImpact = this.simplePrivacyImpact,
            privacyImpact = this.privacyImpact.description
        )
    }

    /**
     * Extension function to convert HandlerProfile to local DeviceInfo.
     * HandlerProfile has fewer fields, so we use what's available.
     */
    private fun HandlerProfile.toDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            description = this.description,
            category = this.manufacturer, // Use manufacturer as category approximation
            surveillanceType = "Detection via ${this.typicalThreatLevel.displayName} threat patterns",
            typicalOperator = null,
            legalFramework = this.legalConsiderations.ifEmpty { null },
            simpleDescription = null,
            simplePrivacyImpact = null,
            privacyImpact = null
        )
    }

    /**
     * Create a default DeviceInfo for unknown device types.
     */
    private fun createDefaultDeviceInfo(deviceType: DeviceType): DeviceInfo {
        return DeviceInfo(
            description = "Unknown surveillance device detected based on wireless signature patterns. Unable to determine specific type, but characteristics suggest surveillance capability.",
            category = "Unknown",
            surveillanceType = "Unknown",
            typicalOperator = null,
            legalFramework = null,
            simpleDescription = "Unknown device with potential surveillance capability",
            simplePrivacyImpact = "Privacy implications unknown - treat with caution"
        )
    }

    /**
     * Get device information from the profile registry.
     * Uses DeviceTypeProfileRegistry (centralized profiles) as the primary source,
     * with fallback to DetectionRegistry handler profiles for additional data.
     */
    private fun getComprehensiveDeviceInfo(deviceType: DeviceType): DeviceInfo {
        // Primary: Use DeviceTypeProfileRegistry (comprehensive profile system)
        val centralizedProfile = DeviceTypeProfileRegistry.getProfile(deviceType)

        // The centralized profile always returns a valid profile (with defaults for unknown types)
        return centralizedProfile.toDeviceInfo()
    }

    // ==================== LEGACY DEVICE INFO (kept for reference during migration) ====================
    // The following when block has been replaced by profile lookups above.
    // Keeping as private function for backward compatibility during testing.
    @Suppress("unused")
    private fun getComprehensiveDeviceInfoLegacy(deviceType: DeviceType): DeviceInfo {
        return when (deviceType) {
            // ALPR & Traffic Cameras
            DeviceType.FLOCK_SAFETY_CAMERA -> DeviceInfo(
                description = "Flock Safety is an Automatic License Plate Recognition (ALPR) camera system. It captures images of all passing vehicles, extracting license plates, vehicle make/model/color, and timestamps. Data is stored in searchable databases accessible to law enforcement agencies and shared across jurisdictions.",
                category = "ALPR System",
                surveillanceType = "Vehicle Tracking",
                typicalOperator = "Law enforcement, HOAs, businesses",
                legalFramework = "Varies by state; some states restrict ALPR data retention"
            )
            DeviceType.LICENSE_PLATE_READER -> DeviceInfo(
                description = "Generic license plate reader system that captures and stores vehicle plate data. May be stationary or mobile-mounted on police vehicles. Creates detailed records of vehicle movements over time.",
                category = "ALPR System",
                surveillanceType = "Vehicle Tracking",
                typicalOperator = "Law enforcement, parking enforcement",
                legalFramework = "Subject to local ALPR regulations"
            )
            DeviceType.SPEED_CAMERA -> DeviceInfo(
                description = "Automated speed enforcement camera that captures vehicle speed and plate data. May issue automated citations. Stores vehicle images and speed records.",
                category = "Traffic Enforcement",
                surveillanceType = "Vehicle Monitoring",
                typicalOperator = "Municipal traffic enforcement",
                legalFramework = "Varies by jurisdiction; some states ban automated enforcement"
            )
            DeviceType.RED_LIGHT_CAMERA -> DeviceInfo(
                description = "Intersection camera that captures vehicles running red lights. Records vehicle images, plates, and violation evidence. May be combined with speed enforcement.",
                category = "Traffic Enforcement",
                surveillanceType = "Vehicle Monitoring",
                typicalOperator = "Municipal traffic enforcement"
            )
            DeviceType.TOLL_READER -> DeviceInfo(
                description = "Electronic toll collection reader (E-ZPass, SunPass, etc.). Tracks vehicle movements through toll points. Data may be subpoenaed for investigations.",
                category = "Toll System",
                surveillanceType = "Vehicle Tracking",
                typicalOperator = "Toll authorities, DOT"
            )
            DeviceType.TRAFFIC_SENSOR -> DeviceInfo(
                description = "Traffic monitoring sensor for flow analysis. May use radar, cameras, or induction loops. Some systems capture individual vehicle data.",
                category = "Traffic Infrastructure",
                surveillanceType = "Traffic Analysis"
            )

            // Acoustic Surveillance
            DeviceType.RAVEN_GUNSHOT_DETECTOR -> DeviceInfo(
                description = "Raven is an acoustic gunshot detection system using networked microphones to detect and triangulate gunfire. While designed for public safety, it continuously monitors ambient audio in the area and may capture conversations.",
                category = "Acoustic Surveillance",
                surveillanceType = "Audio Monitoring",
                typicalOperator = "Law enforcement",
                legalFramework = "Generally considered public space monitoring"
            )
            DeviceType.SHOTSPOTTER -> DeviceInfo(
                description = "ShotSpotter is a citywide acoustic surveillance network. Uses arrays of sensitive microphones that continuously record and analyze ambient audio for gunshot-like sounds. Audio snippets are reviewed by analysts.",
                category = "Acoustic Surveillance",
                surveillanceType = "Continuous Audio Monitoring",
                typicalOperator = "Law enforcement (contracted service)",
                legalFramework = "Has faced legal challenges over audio retention"
            )

            // Cell Site Simulators
            DeviceType.STINGRAY_IMSI -> DeviceInfo(
                description = "Cell-site simulator (IMSI catcher/Stingray) that mimics a cell tower to force phones to connect. " +
                    "Detection analysis includes: encryption downgrade chain tracking (5G→4G→3G→2G), " +
                    "signal spike correlation with new tower appearances, IMSI catcher signature scoring (0-100%), " +
                    "movement analysis via Haversine distance calculations, and cell trust scoring based on " +
                    "historical tower observations. Key indicators: forced encryption downgrades with simultaneous " +
                    "signal spikes, unfamiliar cell IDs in familiar areas, and impossible movement speeds suggesting " +
                    "tower location jumps. Can intercept calls, texts, and precisely track device locations.",
                category = "Cell Site Simulator",
                surveillanceType = "Communications Interception",
                typicalOperator = "Law enforcement (requires warrant in most jurisdictions)",
                legalFramework = "Carpenter v. US requires warrant for historical location data"
            )

            // Forensic Equipment
            DeviceType.CELLEBRITE_FORENSICS -> DeviceInfo(
                description = "Cellebrite is mobile forensics equipment used to extract data from phones including deleted content, encrypted data, and app data. Detection suggests active forensic operations nearby.",
                category = "Mobile Forensics",
                surveillanceType = "Device Data Extraction",
                typicalOperator = "Law enforcement, private investigators",
                legalFramework = "Generally requires warrant for search"
            )
            DeviceType.GRAYKEY_DEVICE -> DeviceInfo(
                description = "GrayKey is an iPhone unlocking and forensics device. Can bypass iOS security to extract device contents. Indicates active mobile forensics operation.",
                category = "Mobile Forensics",
                surveillanceType = "Device Data Extraction",
                typicalOperator = "Law enforcement"
            )

            // Smart Home Cameras
            DeviceType.RING_DOORBELL -> DeviceInfo(
                description = "Amazon Ring doorbell/camera. Records video and audio of public areas. Footage may be shared with law enforcement through Ring's Neighbors program or via subpoena without owner notification.",
                category = "Smart Home Camera",
                surveillanceType = "Video/Audio Recording",
                typicalOperator = "Private homeowners",
                legalFramework = "Amazon partners with 2,000+ police departments"
            )
            DeviceType.NEST_CAMERA -> DeviceInfo(
                description = "Google Nest camera/doorbell. Provides 24/7 video recording with cloud storage. Google may comply with law enforcement requests for footage. Features AI-powered person detection.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording",
                typicalOperator = "Private homeowners"
            )
            DeviceType.ARLO_CAMERA -> DeviceInfo(
                description = "Arlo security camera with cloud storage. May record continuously or on motion detection. Footage accessible to law enforcement via subpoena.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording"
            )
            DeviceType.WYZE_CAMERA -> DeviceInfo(
                description = "Wyze smart camera. Low-cost camera with cloud connectivity. Has had security vulnerabilities in the past. May share data with third parties.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording"
            )
            DeviceType.EUFY_CAMERA -> DeviceInfo(
                description = "Eufy security camera. Marketed as local-only storage but has sent data to cloud. Be aware of potential data collection beyond stated privacy policy.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording"
            )
            DeviceType.BLINK_CAMERA -> DeviceInfo(
                description = "Amazon Blink camera. Part of Amazon's home security ecosystem. May participate in Sidewalk mesh network and share footage with law enforcement.",
                category = "Smart Home Camera",
                surveillanceType = "Video Recording"
            )

            // Security Systems
            DeviceType.SIMPLISAFE_DEVICE -> DeviceInfo(
                description = "SimpliSafe security system component. Professional monitoring service may share data with authorities. Includes cameras, sensors, and alarm systems.",
                category = "Security System",
                surveillanceType = "Home Monitoring"
            )
            DeviceType.ADT_DEVICE -> DeviceInfo(
                description = "ADT security system component. One of the largest security providers. Professional monitoring with law enforcement partnerships.",
                category = "Security System",
                surveillanceType = "Home Monitoring"
            )
            DeviceType.VIVINT_DEVICE -> DeviceInfo(
                description = "Vivint smart home security device. Full home automation and security monitoring with cloud connectivity and professional monitoring.",
                category = "Security System",
                surveillanceType = "Home Monitoring"
            )

            // Personal Trackers
            DeviceType.AIRTAG -> DeviceInfo(
                description = "Apple AirTag Bluetooth tracker. Uses Apple's Find My network (billions of devices) for location tracking. If you don't own this and see it repeatedly, it may be tracking you.",
                category = "Personal Tracker",
                surveillanceType = "Location Tracking",
                typicalOperator = "Private individuals",
                legalFramework = "Apple added anti-stalking alerts; illegal to track without consent"
            )
            DeviceType.TILE_TRACKER -> DeviceInfo(
                description = "Tile Bluetooth tracker. Uses Tile's network for location tracking. Check your belongings if you see this repeatedly and don't own a Tile.",
                category = "Personal Tracker",
                surveillanceType = "Location Tracking"
            )
            DeviceType.SAMSUNG_SMARTTAG -> DeviceInfo(
                description = "Samsung SmartTag tracker. Uses Samsung's Galaxy Find Network. Can track items or potentially be used for unwanted tracking.",
                category = "Personal Tracker",
                surveillanceType = "Location Tracking"
            )
            DeviceType.GENERIC_BLE_TRACKER -> DeviceInfo(
                description = "Generic Bluetooth Low Energy tracker detected. Could be a legitimate item tracker or potentially used for unwanted surveillance.",
                category = "Personal Tracker",
                surveillanceType = "Location Tracking"
            )

            // Mesh Networks
            DeviceType.AMAZON_SIDEWALK -> DeviceInfo(
                description = "Amazon Sidewalk is a shared mesh network using Ring and Echo devices. Can track Sidewalk-enabled devices across the network and raises privacy concerns about shared bandwidth.",
                category = "Mesh Network",
                surveillanceType = "Network Tracking",
                typicalOperator = "Amazon (opt-out required)"
            )

            // Network Attack Devices
            DeviceType.WIFI_PINEAPPLE -> DeviceInfo(
                description = "WiFi Pineapple is a penetration testing device capable of man-in-the-middle attacks, credential capture, and network manipulation. Detection suggests active security testing or potential attack.",
                category = "Network Attack Tool",
                surveillanceType = "Network Interception",
                legalFramework = "Illegal to use without authorization"
            )
            DeviceType.ROGUE_AP -> DeviceInfo(
                description = "Unauthorized or suspicious access point detected. May be attempting evil twin attacks or network interception. Do not connect to unknown networks.",
                category = "Rogue Network",
                surveillanceType = "Network Interception"
            )
            DeviceType.MAN_IN_MIDDLE -> DeviceInfo(
                description = "Potential man-in-the-middle attack device detected. May be intercepting network traffic. Use VPN and verify HTTPS connections.",
                category = "Network Attack",
                surveillanceType = "Traffic Interception"
            )
            DeviceType.PACKET_SNIFFER -> DeviceInfo(
                description = "Network packet capture device detected. May be monitoring network traffic for reconnaissance or data exfiltration.",
                category = "Network Monitoring",
                surveillanceType = "Traffic Analysis"
            )

            // Drones
            DeviceType.DRONE -> DeviceInfo(
                description = "Aerial drone/UAV detected via WiFi signal. Could be recreational, commercial, or surveillance-related. Drones can carry cameras, thermal sensors, and other surveillance equipment.",
                category = "Aerial Surveillance",
                surveillanceType = "Aerial Monitoring",
                legalFramework = "FAA regulations; privacy laws vary by state"
            )

            // Commercial Surveillance
            DeviceType.CCTV_CAMERA -> DeviceInfo(
                description = "Closed-circuit television camera. May be part of business or municipal surveillance system. Footage typically retained for days to months.",
                category = "Video Surveillance",
                surveillanceType = "Video Recording"
            )
            DeviceType.PTZ_CAMERA -> DeviceInfo(
                description = "Pan-tilt-zoom camera with remote control capabilities. Can actively track subjects and provide detailed surveillance coverage.",
                category = "Video Surveillance",
                surveillanceType = "Active Video Tracking"
            )
            DeviceType.THERMAL_CAMERA -> DeviceInfo(
                description = "Thermal/infrared camera that can see heat signatures through walls, detect people in darkness, and identify concealed individuals.",
                category = "Thermal Surveillance",
                surveillanceType = "Thermal Imaging",
                legalFramework = "Kyllo v. US restricts warrantless thermal imaging of homes"
            )
            DeviceType.NIGHT_VISION -> DeviceInfo(
                description = "Night vision device capable of surveillance in low-light conditions. May be handheld or camera-mounted.",
                category = "Night Surveillance",
                surveillanceType = "Low-Light Monitoring"
            )
            DeviceType.HIDDEN_CAMERA -> DeviceInfo(
                description = "Covert camera detected. May be hidden in everyday objects. Check for recording devices in private spaces.",
                category = "Covert Surveillance",
                surveillanceType = "Hidden Video Recording",
                legalFramework = "Generally illegal in private spaces without consent"
            )

            // Retail & Commercial Tracking
            DeviceType.BLUETOOTH_BEACON -> DeviceInfo(
                description = "Bluetooth beacon for indoor positioning and tracking. Used in retail stores to track customer movements and send targeted advertisements.",
                category = "Retail Tracking",
                surveillanceType = "Indoor Location Tracking"
            )
            DeviceType.RETAIL_TRACKER -> DeviceInfo(
                description = "Retail tracking device for customer analytics. Monitors shopping patterns, dwell time, and movement through stores.",
                category = "Retail Analytics",
                surveillanceType = "Customer Tracking"
            )
            DeviceType.CROWD_ANALYTICS -> DeviceInfo(
                description = "Crowd analytics sensor for counting and tracking people. May use WiFi probe requests, cameras, or other sensors to monitor crowds.",
                category = "People Counting",
                surveillanceType = "Crowd Monitoring"
            )

            // Facial Recognition
            DeviceType.FACIAL_RECOGNITION -> DeviceInfo(
                description = "Facial recognition system detected. Captures and analyzes faces for identification. May be connected to law enforcement databases.",
                category = "Biometric Surveillance",
                surveillanceType = "Facial Recognition",
                typicalOperator = "Law enforcement, businesses, venues",
                legalFramework = "Banned in some cities; BIPA in Illinois"
            )
            DeviceType.CLEARVIEW_AI -> DeviceInfo(
                description = "Clearview AI facial recognition system. Uses scraped social media photos to identify individuals. Highly controversial with 30+ billion face database.",
                category = "Biometric Surveillance",
                surveillanceType = "Facial Recognition",
                typicalOperator = "Law enforcement",
                legalFramework = "Banned in several countries; multiple lawsuits pending"
            )

            // Law Enforcement Specific
            DeviceType.BODY_CAMERA -> DeviceInfo(
                description = "Police body-worn camera detected. Records video and audio of interactions. Footage may be subject to FOIA requests.",
                category = "Body Camera",
                surveillanceType = "Video/Audio Recording",
                typicalOperator = "Law enforcement"
            )
            DeviceType.POLICE_RADIO -> DeviceInfo(
                description = "Police radio system detected. Indicates law enforcement presence in the area.",
                category = "Communications",
                surveillanceType = "Radio Communications",
                typicalOperator = "Law enforcement"
            )
            DeviceType.POLICE_VEHICLE -> DeviceInfo(
                description = "Police or emergency vehicle wireless system detected. May include ALPR, mobile data terminals, and radio equipment.",
                category = "Mobile Surveillance",
                surveillanceType = "Vehicle-based Monitoring",
                typicalOperator = "Law enforcement"
            )
            DeviceType.MOTOROLA_POLICE_TECH -> DeviceInfo(
                description = "Motorola Solutions law enforcement technology detected. May include radios, body cameras, or command systems.",
                category = "Law Enforcement Tech",
                surveillanceType = "Police Technology"
            )
            DeviceType.AXON_POLICE_TECH -> DeviceInfo(
                description = "Axon (formerly Taser) law enforcement technology. May include body cameras, Tasers, or fleet management systems.",
                category = "Law Enforcement Tech",
                surveillanceType = "Police Technology"
            )
            DeviceType.PALANTIR_DEVICE -> DeviceInfo(
                description = "Palantir data integration system. Powerful analytics platform used by law enforcement to aggregate and analyze data from multiple sources.",
                category = "Data Analytics",
                surveillanceType = "Data Aggregation",
                typicalOperator = "Law enforcement, intelligence agencies"
            )

            // Military/Government
            DeviceType.L3HARRIS_SURVEILLANCE -> DeviceInfo(
                description = "L3Harris surveillance technology detected. Major defense contractor providing military-grade surveillance, communications, and intelligence equipment.",
                category = "Military Surveillance",
                surveillanceType = "Advanced Surveillance"
            )

            // Ultrasonic
            DeviceType.ULTRASONIC_BEACON -> DeviceInfo(
                description = "Ultrasonic tracking beacon detected. Uses inaudible sound (18-22 kHz) to track users across devices. " +
                    "Detection analysis includes: amplitude fingerprinting (steady vs pulsing vs modulated patterns), " +
                    "source attribution against known beacon types (SilverPush, Alphonso, Signal360, LISNR, Shopkick), " +
                    "cross-location correlation to detect beacons following the user across multiple locations, " +
                    "signal-to-noise ratio analysis, and tracking likelihood scoring (0-100%). Key indicators: " +
                    "same frequency/amplitude profile detected at multiple distinct locations, pulsing amplitude " +
                    "patterns matching known ad-tech signatures, and persistence score indicating dedicated tracking. " +
                    "Often used for advertising attribution and cross-device identity resolution.",
                category = "Cross-Device Tracking",
                surveillanceType = "Ultrasonic Tracking",
                legalFramework = "FTC has taken action against undisclosed tracking"
            )

            // Satellite
            DeviceType.SATELLITE_NTN -> DeviceInfo(
                description = "Non-terrestrial network (satellite) device detected. Could be legitimate satellite connectivity or spoofed signal.",
                category = "Satellite Communication",
                surveillanceType = "Satellite Monitoring"
            )

            // GNSS Threats
            DeviceType.GNSS_SPOOFER -> DeviceInfo(
                description = "GPS/GNSS spoofing device detected. Transmits fake satellite signals to manipulate location data. " +
                    "Detection analysis includes: constellation fingerprinting (expected vs observed GPS/GLONASS/Galileo/BeiDou), " +
                    "C/N0 baseline deviation (abnormal signal strength uniformity indicates fake signals), " +
                    "clock drift accumulation tracking (spoofed signals often show erratic drift patterns), " +
                    "satellite geometry analysis (spoofed signals may show unnaturally uniform spacing or angles), " +
                    "and composite spoofing likelihood scoring (0-100%). Key indicators: missing expected constellations, " +
                    "C/N0 values deviating >2σ from baseline, erratic clock drift trends, and unnaturally uniform signal strengths. " +
                    "Your reported position may be inaccurate.",
                category = "GNSS Attack",
                surveillanceType = "Location Manipulation",
                legalFramework = "Federal crime to interfere with GPS signals"
            )
            DeviceType.GNSS_JAMMER -> DeviceInfo(
                description = "GPS/GNSS jamming device detected. Blocks legitimate satellite signals, preventing accurate positioning.",
                category = "GNSS Attack",
                surveillanceType = "Signal Denial",
                legalFramework = "Federal crime under Communications Act"
            )

            // RF Threats
            DeviceType.RF_JAMMER -> DeviceInfo(
                description = "RF jamming device detected. Blocks wireless communications in the area. May affect cellular, WiFi, and GPS signals.",
                category = "Signal Jamming",
                surveillanceType = "Communications Denial",
                legalFramework = "Illegal under FCC regulations"
            )
            DeviceType.HIDDEN_TRANSMITTER -> DeviceInfo(
                description = "Hidden RF transmitter detected. Could be a covert listening device (bug) or other surveillance equipment.",
                category = "Covert Surveillance",
                surveillanceType = "Audio/Video Transmission"
            )
            DeviceType.RF_INTERFERENCE -> DeviceInfo(
                description = "Significant RF interference detected. May indicate jamming, environmental factors, or equipment malfunction.",
                category = "RF Anomaly",
                surveillanceType = "Signal Analysis"
            )
            DeviceType.RF_ANOMALY -> DeviceInfo(
                description = "Unusual RF activity pattern detected indicating potential covert surveillance infrastructure. " +
                    "Analysis shows anomalous hidden WiFi network characteristics including signal patterns, " +
                    "manufacturer clustering, temporal behavior, and channel distribution that deviate from " +
                    "typical residential or commercial environments. Hidden networks with stronger signals than " +
                    "visible ones, low signal variance (same hardware), known surveillance vendor OUIs, or " +
                    "simultaneous appearance patterns are strong indicators of coordinated surveillance deployment.",
                category = "RF Anomaly",
                surveillanceType = "Signal Analysis",
                typicalOperator = "Law enforcement, private investigators, corporate security, government agencies",
                legalFramework = "Varies by jurisdiction; covert surveillance generally requires warrants"
            )

            // Fleet/Commercial Vehicles
            DeviceType.FLEET_VEHICLE -> DeviceInfo(
                description = "Commercial fleet vehicle tracking system detected. May include GPS tracking, cameras, and telemetry systems.",
                category = "Fleet Management",
                surveillanceType = "Vehicle Tracking"
            )
            DeviceType.SURVEILLANCE_VAN -> DeviceInfo(
                description = "Possible mobile surveillance van detected. May contain advanced monitoring equipment including IMSI catchers, cameras, or listening devices.",
                category = "Mobile Surveillance",
                surveillanceType = "Multi-Modal Surveillance"
            )

            // Misc Surveillance
            DeviceType.SURVEILLANCE_INFRASTRUCTURE -> DeviceInfo(
                description = "General surveillance infrastructure detected. May be part of a larger monitoring system.",
                category = "Infrastructure",
                surveillanceType = "General Surveillance"
            )
            DeviceType.TRACKING_DEVICE -> DeviceInfo(
                description = "Generic tracking device detected. May be used for asset tracking or personal surveillance.",
                category = "Tracking",
                surveillanceType = "Location Tracking"
            )

            // Vendor Specific
            DeviceType.PENGUIN_SURVEILLANCE -> DeviceInfo(
                description = "Penguin Surveillance system detected. Commercial surveillance platform.",
                category = "Commercial Surveillance",
                surveillanceType = "Video Surveillance"
            )
            DeviceType.PIGVISION_SYSTEM -> DeviceInfo(
                description = "Pigvision surveillance system detected. Agricultural/industrial monitoring system.",
                category = "Commercial Surveillance",
                surveillanceType = "Industrial Monitoring"
            )

            // Flipper Zero and Hacking Tools
            DeviceType.FLIPPER_ZERO -> DeviceInfo(
                description = "Flipper Zero multi-tool hacking device detected. Capable of interacting with Sub-GHz, RFID, NFC, IR, and BLE protocols. Can clone access cards, capture garage door signals, and perform BLE attacks. May be used for legitimate security research or malicious purposes.",
                category = "Hacking Tool",
                surveillanceType = "Multi-Protocol Attack Tool",
                typicalOperator = "Security researchers, pentesters, hobbyists, or malicious actors",
                legalFramework = "Device itself is legal; usage for unauthorized access is illegal"
            )
            DeviceType.FLIPPER_ZERO_SPAM -> DeviceInfo(
                description = "Active Flipper Zero BLE spam attack detected. Device is flooding Bluetooth with fake device advertisements causing popup floods on iPhones or notification spam on Android. This is malicious use with no legitimate purpose.",
                category = "Active Attack",
                surveillanceType = "BLE Spam Attack",
                typicalOperator = "Malicious actor",
                legalFramework = "May violate computer fraud laws, harassment statutes, or FCC regulations"
            )
            DeviceType.HACKRF_SDR -> DeviceInfo(
                description = "Software Defined Radio (HackRF or similar) detected. Capable of wide-spectrum RF reception and transmission. Used for RF research, amateur radio, and security testing.",
                category = "RF Analysis Tool",
                surveillanceType = "RF Monitoring",
                typicalOperator = "Radio hobbyists, security researchers"
            )
            DeviceType.PROXMARK -> DeviceInfo(
                description = "Proxmark RFID/NFC research tool detected. Powerful device for reading, writing, and emulating RFID/NFC cards. Can clone access cards and building badges.",
                category = "RFID/NFC Tool",
                surveillanceType = "Card Cloning",
                typicalOperator = "Security researchers, physical pentesters",
                legalFramework = "Cloning cards without authorization is illegal"
            )
            DeviceType.USB_RUBBER_DUCKY -> DeviceInfo(
                description = "USB Rubber Ducky keystroke injection device detected. Looks like USB drive but acts as keyboard, injecting pre-programmed keystrokes at high speed.",
                category = "USB Attack Tool",
                surveillanceType = "Keystroke Injection",
                legalFramework = "Unauthorized use is computer fraud"
            )
            DeviceType.BASH_BUNNY -> DeviceInfo(
                description = "Hak5 Bash Bunny USB attack platform detected. Advanced multi-function USB attack tool capable of keystroke injection, network attacks, and data exfiltration.",
                category = "USB Attack Tool",
                surveillanceType = "Multi-Function USB Attack"
            )
            DeviceType.LAN_TURTLE -> DeviceInfo(
                description = "Hak5 LAN Turtle covert network access device detected. Appears as USB ethernet adapter but provides persistent remote access to networks.",
                category = "Network Attack Tool",
                surveillanceType = "Covert Network Access"
            )
            DeviceType.KEYCROC -> DeviceInfo(
                description = "Hak5 Key Croc inline keylogger detected. Captures all keystrokes and exfiltrates them over WiFi. Sits between keyboard and computer.",
                category = "Keylogger",
                surveillanceType = "Keystroke Capture"
            )
            DeviceType.SHARK_JACK -> DeviceInfo(
                description = "Hak5 Shark Jack portable network attack tool detected. Pocket-sized device for network reconnaissance and attacks.",
                category = "Network Attack Tool",
                surveillanceType = "Network Reconnaissance"
            )
            DeviceType.SCREEN_CRAB -> DeviceInfo(
                description = "Hak5 Screen Crab HDMI interception device detected. Captures screenshots from HDMI video stream and exfiltrates over WiFi.",
                category = "Video Interception",
                surveillanceType = "Screen Capture"
            )
            DeviceType.GENERIC_HACKING_TOOL -> DeviceInfo(
                description = "Security testing or hacking tool detected. Device matches patterns associated with penetration testing equipment.",
                category = "Hacking Tool",
                surveillanceType = "Security Testing"
            )

            // Catch-all
            DeviceType.UNKNOWN_SURVEILLANCE -> DeviceInfo(
                description = "Unknown surveillance device detected based on wireless signature patterns. Unable to determine specific type, but characteristics suggest surveillance capability.",
                category = "Unknown",
                surveillanceType = "Unknown"
            )
        }
    }

    /**
     * Get data collection capabilities for each device type.
     * Uses DeviceTypeProfileRegistry (centralized profile system) as primary source.
     * HandlerProfile has 'capabilities' which is more generic; we prefer the detailed
     * 'dataCollected' from the centralized profile.
     */
    private fun getDataCollectionCapabilities(deviceType: DeviceType): List<String> {
        // Primary: Use DeviceTypeProfileRegistry for detailed data collection info
        val centralizedProfile = DeviceTypeProfileRegistry.getProfile(deviceType)
        if (centralizedProfile.dataCollected.isNotEmpty()) {
            return centralizedProfile.dataCollected
        }

        // Fallback: Try handler profile's capabilities as approximation
        val handlerProfile = detectionRegistry.getProfile(deviceType)
        if (handlerProfile != null && handlerProfile.capabilities.isNotEmpty()) {
            return handlerProfile.capabilities
        }

        // Default fallback for unknown device types
        return listOf(
            "Device-specific data collection varies",
            "May include location and identifiers",
            "Behavioral patterns possible",
            "Check device documentation"
        )
    }

    private fun getRiskAssessment(detection: Detection): String {
        return when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> "CRITICAL RISK: This device poses immediate and significant privacy concerns. It can actively collect sensitive personal data, intercept communications, or perform invasive surveillance. Take protective measures immediately."
            ThreatLevel.HIGH -> "HIGH RISK: This surveillance device can collect identifying information, track your movements, or record your activities. Data may be stored indefinitely and shared with law enforcement or third parties without your knowledge."
            ThreatLevel.MEDIUM -> "MODERATE RISK: This device collects data that could be used for tracking or profiling. While not immediately dangerous, prolonged exposure or pattern analysis could reveal sensitive information about your habits."
            ThreatLevel.LOW -> "LOW RISK: This device has limited surveillance capabilities. It may collect some metadata but poses minimal immediate privacy concerns for most users."
            ThreatLevel.INFO -> "INFORMATIONAL: Device detected but poses minimal direct privacy risk. May be standard infrastructure or consumer electronics."
        }
    }

    private fun calculateRiskScore(detection: Detection, context: ContextualInsights?): Int {
        var score = detection.threatScore

        // Adjust based on context
        context?.let {
            if (it.clusterInfo != null) score += 10 // Part of surveillance cluster
            if (it.historicalContext?.contains("detected") == true) {
                val times = Regex("(\\d+) times").find(it.historicalContext)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                score += minOf(times * 2, 15) // More frequent = higher risk
            }
        }

        return score.coerceIn(0, 100)
    }

    private fun getRiskFactors(detection: Detection, context: ContextualInsights?): List<String> {
        val factors = mutableListOf<String>()

        when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> factors.add("Active surveillance capability")
            ThreatLevel.HIGH -> factors.add("Confirmed surveillance device")
            else -> {}
        }

        if (detection.signalStrength.ordinal <= 1) { // Excellent or Good
            factors.add("Close proximity (strong signal)")
        }

        context?.let {
            if (it.clusterInfo != null) factors.add("Part of surveillance network")
            if (it.isKnownLocation) factors.add("Persistent presence at location")
        }

        if (detection.seenCount > 5) {
            factors.add("Repeatedly detected (${detection.seenCount} times)")
        }

        return factors
    }

    private fun getSmartRecommendations(detection: Detection, context: ContextualInsights?): List<String> {
        val recommendations = mutableListOf<String>()

        // Threat-level based recommendations (dynamic based on detection context)
        when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> {
                recommendations.add("Consider leaving the area immediately if safety allows")
                recommendations.add("Enable airplane mode or use a Faraday bag for your devices")
                recommendations.add("Use only end-to-end encrypted communications")
                recommendations.add("Document this detection with timestamp and location")
            }
            ThreatLevel.HIGH -> {
                recommendations.add("Be aware that your presence/vehicle is being recorded")
                recommendations.add("Consider varying your routes and patterns")
                recommendations.add("Use VPN and encrypted messaging apps")
            }
            ThreatLevel.MEDIUM -> {
                recommendations.add("Note this location for future awareness")
                recommendations.add("Review privacy settings on your devices")
            }
            ThreatLevel.LOW, ThreatLevel.INFO -> {
                recommendations.add("No immediate action required")
            }
        }

        // Device-specific recommendations from profile system
        val profileRecommendations = getProfileRecommendations(detection.deviceType)
        recommendations.addAll(profileRecommendations)

        // Context-based recommendations
        context?.let {
            if (it.clusterInfo != null) {
                recommendations.add("This is a high-surveillance area - multiple devices detected")
            }
        }

        return recommendations.distinct().take(6)
    }

    /**
     * Get device-specific recommendations from the profile system.
     * Uses DeviceTypeProfileRegistry (centralized profile) as primary source.
     * HandlerProfile has 'mitigationAdvice' (string) which we can use as fallback.
     */
    private fun getProfileRecommendations(deviceType: DeviceType): List<String> {
        // Primary: Use DeviceTypeProfileRegistry for structured recommendations
        val centralizedProfile = DeviceTypeProfileRegistry.getProfile(deviceType)
        if (centralizedProfile.recommendations.isNotEmpty()) {
            return centralizedProfile.recommendations
                .sortedBy { it.priority }
                .map { it.action }
        }

        // Fallback: Try handler profile's mitigationAdvice
        val handlerProfile = detectionRegistry.getProfile(deviceType)
        if (handlerProfile != null && handlerProfile.mitigationAdvice.isNotEmpty()) {
            // Split mitigation advice into individual recommendations if it contains multiple sentences
            return handlerProfile.mitigationAdvice
                .split(". ")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        // Empty list if no recommendations found
        return emptyList()
    }

    /**
     * Batch analysis for surveillance density mapping.
     */
    suspend fun performBatchAnalysis(
        detections: List<Detection>
    ): BatchAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val settings = aiSettingsRepository.settings.first()
            if (!settings.enabled || !settings.enableBatchAnalysis) {
                return@withContext BatchAnalysisResult(
                    success = false,
                    totalDevicesAnalyzed = 0,
                    surveillanceDensityScore = 0,
                    hotspots = emptyList(),
                    anomalies = emptyList(),
                    processingTimeMs = 0,
                    error = "Batch analysis is disabled"
                )
            }

            // Find clusters/hotspots
            val hotspots = findSurveillanceHotspots(detections)

            // Calculate density score
            val densityScore = calculateDensityScore(detections, hotspots)

            // Find anomalies
            val anomalies = detectAnomalies(detections)

            BatchAnalysisResult(
                success = true,
                totalDevicesAnalyzed = detections.size,
                surveillanceDensityScore = densityScore,
                hotspots = hotspots,
                anomalies = anomalies,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Batch analysis failed", e)
            BatchAnalysisResult(
                success = false,
                totalDevicesAnalyzed = 0,
                surveillanceDensityScore = 0,
                hotspots = emptyList(),
                anomalies = listOf(e.message ?: "Unknown error"),
                processingTimeMs = System.currentTimeMillis() - startTime,
                error = e.message
            )
        }
    }

    private fun findSurveillanceHotspots(detections: List<Detection>): List<SurveillanceHotspot> {
        val geoDetections = detections.filter { it.latitude != null && it.longitude != null }
        if (geoDetections.isEmpty()) return emptyList()

        val hotspots = mutableListOf<SurveillanceHotspot>()
        val processed = mutableSetOf<String>()

        for (detection in geoDetections) {
            if (detection.id in processed) continue

            val nearby = geoDetections.filter { other ->
                other.id !in processed &&
                calculateDistance(
                    detection.latitude!!, detection.longitude!!,
                    other.latitude!!, other.longitude!!
                ) < CLUSTER_RADIUS_METERS
            }

            if (nearby.size >= 2) {
                // Found a cluster
                nearby.forEach { processed.add(it.id) }

                val avgLat = nearby.mapNotNull { it.latitude }.average()
                val avgLon = nearby.mapNotNull { it.longitude }.average()
                val dominantType = nearby.groupBy { it.deviceType }
                    .maxByOrNull { it.value.size }?.key ?: DeviceType.UNKNOWN_SURVEILLANCE
                val maxThreat = nearby.maxOfOrNull { it.threatLevel.ordinal } ?: 0

                hotspots.add(SurveillanceHotspot(
                    latitude = avgLat,
                    longitude = avgLon,
                    radiusMeters = CLUSTER_RADIUS_METERS.toInt(),
                    deviceCount = nearby.size,
                    threatLevel = ThreatLevel.entries[maxThreat].displayName,
                    dominantDeviceType = dominantType.displayName
                ))
            }
        }

        return hotspots.sortedByDescending { it.deviceCount }
    }

    private fun calculateDensityScore(detections: List<Detection>, hotspots: List<SurveillanceHotspot>): Int {
        if (detections.isEmpty()) return 0

        var score = 0

        // Base score from device count
        score += minOf(detections.size * 5, 30)

        // Score from threat levels
        score += detections.count { it.threatLevel == ThreatLevel.CRITICAL } * 15
        score += detections.count { it.threatLevel == ThreatLevel.HIGH } * 10
        score += detections.count { it.threatLevel == ThreatLevel.MEDIUM } * 5

        // Score from clusters
        score += hotspots.size * 10
        score += hotspots.sumOf { minOf(it.deviceCount, 10) }

        return score.coerceIn(0, 100)
    }

    private fun detectAnomalies(detections: List<Detection>): List<String> {
        val anomalies = mutableListOf<String>()

        // Check for unusual patterns
        val recentDetections = detections.filter {
            System.currentTimeMillis() - it.timestamp < 24 * 60 * 60 * 1000
        }

        if (recentDetections.count { it.threatLevel == ThreatLevel.CRITICAL } > 2) {
            anomalies.add("Multiple critical-level devices detected in 24 hours")
        }

        val imsiCatchers = recentDetections.count { it.deviceType == DeviceType.STINGRAY_IMSI }
        if (imsiCatchers > 0) {
            anomalies.add("Cell-site simulator activity detected")
        }

        val trackers = recentDetections.filter {
            it.deviceType in listOf(DeviceType.AIRTAG, DeviceType.TILE_TRACKER, DeviceType.SAMSUNG_SMARTTAG)
        }
        if (trackers.size > 1) {
            anomalies.add("Multiple personal trackers detected - possible tracking attempt")
        }

        return anomalies
    }

    /**
     * Generate threat assessment for environment.
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

            val overallLevel = when {
                criticalCount > 0 -> "CRITICAL"
                highCount > 2 -> "HIGH"
                highCount > 0 || mediumCount > 3 -> "ELEVATED"
                mediumCount > 0 -> "MODERATE"
                else -> "LOW"
            }

            val assessment = buildString {
                appendLine("## Environment Threat Assessment")
                appendLine()
                appendLine("### Overall Level: $overallLevel")
                appendLine()
                appendLine("### Summary")
                appendLine("- Total devices: ${detections.size}")
                if (criticalCount > 0) appendLine("- Critical: $criticalCount")
                if (highCount > 0) appendLine("- High: $highCount")
                if (mediumCount > 0) appendLine("- Medium: $mediumCount")
                if (lowCount > 0) appendLine("- Low/Info: $lowCount")
                appendLine()

                if (criticalCount > 0 || highCount > 0) {
                    appendLine("### Priority Concerns")
                    detections.filter { it.threatLevel in listOf(ThreatLevel.CRITICAL, ThreatLevel.HIGH) }
                        .take(5)
                        .forEach { appendLine("- ${it.deviceType.displayName} (${it.threatLevel.displayName})") }
                    appendLine()
                }

                appendLine("### Recommendations")
                when (overallLevel) {
                    "CRITICAL" -> {
                        appendLine("1. Exercise extreme caution - active surveillance detected")
                        appendLine("2. Consider limiting electronic device usage")
                        appendLine("3. Use only encrypted communications")
                        appendLine("4. Document all detections")
                    }
                    "HIGH" -> {
                        appendLine("1. Be aware of active surveillance in this area")
                        appendLine("2. Review your digital privacy practices")
                        appendLine("3. Consider your exposure to data collection")
                    }
                    else -> {
                        appendLine("1. Standard privacy precautions recommended")
                        appendLine("2. Continue monitoring for changes")
                    }
                }
            }

            AiAnalysisResult(
                success = true,
                threatAssessment = assessment,
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = currentModel.id,
                wasOnDevice = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error generating threat assessment", e)
            AiAnalysisResult(
                success = false,
                error = e.message,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } finally {
            _isAnalyzing.value = false
        }
    }

    /**
     * Record user feedback for analysis improvement.
     * Uses ArrayDeque for O(1) removal from front during pruning.
     */
    suspend fun recordFeedback(feedback: AnalysisFeedback) {
        feedbackMutex.withLock {
            feedbackHistory.addLast(feedback)
            // Efficient O(1) pruning with ArrayDeque
            while (feedbackHistory.size > MAX_FEEDBACK_HISTORY_SIZE) {
                feedbackHistory.removeFirst()
            }
        }
        // In production, this could update local preference weights
        Log.d(TAG, "Recorded feedback: ${feedback.feedbackType} for ${feedback.detectionId}")
    }

    /**
     * Download selected model with retry logic and resumable download support.
     * Progress callbacks are dispatched to the Main thread for UI safety.
     */
    suspend fun downloadModel(
        modelId: String = currentModel.id,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // Wrap progress callback to ensure it runs on Main thread for UI safety
        val safeProgress: suspend (Int) -> Unit = { progress ->
            withContext(Dispatchers.Main) {
                onProgress(progress)
            }
        }

        try {
            val model = AiModel.fromId(modelId)

            if (model == AiModel.RULE_BASED) {
                // No download needed
                safeProgress(100)
                return@withContext true
            }

            if (model == AiModel.GEMINI_NANO) {
                // Gemini Nano is managed by Google Play Services via ML Kit GenAI
                // Download and initialize the model
                _modelStatus.value = AiModelStatus.Downloading(0)
                val success = downloadGeminiNanoModel { progress ->
                    kotlinx.coroutines.runBlocking {
                        safeProgress(progress)
                    }
                    _modelStatus.value = AiModelStatus.Downloading(progress)
                }

                if (success) {
                    geminiNanoInitialized = true
                    isModelLoaded = true
                    _modelStatus.value = AiModelStatus.Ready
                    aiSettingsRepository.setEnabled(true)
                    aiSettingsRepository.setSelectedModel(model.id)
                    Log.i(TAG, "Gemini Nano download and initialization completed")
                } else {
                    _modelStatus.value = AiModelStatus.Error("Failed to download Gemini Nano model")
                    Log.w(TAG, "Gemini Nano download/init failed")
                }
                return@withContext success
            }

            // Get download URL via NetworkConfig for OEM customization support
            val downloadUrl = AiModel.getDownloadUrl(model)
            if (downloadUrl == null) {
                // Model requires manual download (e.g., from Kaggle)
                Log.w(TAG, "Model ${model.id} requires manual download. ${AiModel.getDownloadInstructions(model)}")
                _modelStatus.value = AiModelStatus.Error("Model requires manual download from Kaggle. See instructions in app.")
                return@withContext false
            }

            _modelStatus.value = AiModelStatus.Downloading(0)
            safeProgress(0)

            val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
            // Use the appropriate file extension based on model format
            val fileExtension = AiModel.getFileExtension(model)
            val modelFile = File(modelDir, "${model.id}$fileExtension")
            val tempFile = File(modelDir, "${model.id}$fileExtension.tmp")

            // Get HF token from settings for authenticated downloads
            val hfToken = aiSettingsRepository.settings.first().huggingFaceToken.takeIf { it.isNotBlank() }

            // Retry logic with exponential backoff
            var lastException: Exception? = null
            repeat(MAX_DOWNLOAD_RETRIES) { attempt ->
                try {
                    val success = downloadWithResume(downloadUrl, tempFile, modelFile, model.sizeMb * 1024 * 1024, safeProgress, hfToken)
                    if (success) {
                        // Update settings - enable AI and set model
                        aiSettingsRepository.setEnabled(true)  // Enable AI so initializeModel() works
                        aiSettingsRepository.setSelectedModel(model.id)
                        aiSettingsRepository.setModelDownloaded(true, modelFile.length() / (1024 * 1024))
                        currentModel = model
                        Log.i(TAG, "Model download completed, selected model: ${model.displayName}")
                        return@withContext true
                    }
                } catch (e: IOException) {
                    lastException = e
                    Log.w(TAG, "Download attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < MAX_DOWNLOAD_RETRIES - 1) {
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt) // Exponential backoff
                        Log.d(TAG, "Retrying in ${delayMs}ms...")
                        kotlinx.coroutines.delay(delayMs)
                    }
                }
            }

            throw lastException ?: Exception("Download failed after $MAX_DOWNLOAD_RETRIES attempts")
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            _modelStatus.value = AiModelStatus.Error(e.message ?: "Download failed")
            false
        }
    }

    /**
     * Download with resume support for interrupted downloads.
     * Progress callback is invoked from IO thread but should be safe (wrapped by caller).
     * @param hfToken Optional Hugging Face token for authenticated downloads
     */
    private suspend fun downloadWithResume(
        downloadUrl: String,
        tempFile: File,
        finalFile: File,
        expectedSize: Long,
        onProgress: suspend (Int) -> Unit,
        hfToken: String? = null
    ): Boolean {
        // Check for existing partial download
        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
        val requestBuilder = Request.Builder()
            .url(downloadUrl)
            .addHeader("User-Agent", context.getString(R.string.user_agent))

        // Add Hugging Face authorization header if token is provided
        if (!hfToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $hfToken")
            Log.d(TAG, "Using Hugging Face token for authenticated download")
        }

        // Add Range header for resume if we have partial data
        if (existingBytes > 0 && existingBytes < expectedSize) {
            requestBuilder.addHeader("Range", "bytes=$existingBytes-")
            Log.d(TAG, "Resuming download from byte $existingBytes")
        }

        val request = requestBuilder.build()

        httpClient.newCall(request).execute().use { response ->
            // Handle resume response (206) or fresh download (200)
            if (!response.isSuccessful && response.code != 206) {
                // If resume fails with 416 (Range Not Satisfiable), start fresh
                if (response.code == 416) {
                    tempFile.delete()
                    return downloadWithResume(downloadUrl, tempFile, finalFile, expectedSize, onProgress)
                }
                // Handle authentication errors (shouldn't happen with public repos)
                if (response.code == 401 || response.code == 403) {
                    throw IOException("Download failed: Authentication required (HTTP ${response.code}). Try using 'Import Model' to load a manually downloaded file.")
                }
                throw IOException("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response")
            val contentLength = body.contentLength()
            val totalSize = if (response.code == 206) existingBytes + contentLength else contentLength

            // Use append mode for resume, otherwise create fresh
            val appendMode = response.code == 206
            var lastProgressUpdate = 0L
            FileOutputStream(tempFile, appendMode).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = existingBytes
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val progress = if (totalSize > 0) {
                            ((downloadedBytes * 100) / totalSize).toInt().coerceIn(0, 99)
                        } else {
                            ((downloadedBytes / (expectedSize.toDouble())) * 100).toInt().coerceIn(0, 99)
                        }

                        // Throttle progress updates to avoid overwhelming the UI (max once per 100ms)
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100) {
                            _modelStatus.value = AiModelStatus.Downloading(progress)
                            onProgress(progress)
                            lastProgressUpdate = now
                        }
                    }
                }
            }

            // Rename temp file to final file atomically
            if (tempFile.renameTo(finalFile)) {
                _modelStatus.value = AiModelStatus.Ready
                onProgress(100)
                Log.i(TAG, "Model downloaded: ${finalFile.name} (${finalFile.length() / 1024 / 1024} MB)")
                return true
            } else {
                throw IOException("Failed to rename temp file to final file")
            }
        }
    }

    /**
     * Import a model file from a Uri (e.g., from file picker).
     * Copies the file to the app's internal storage and sets it as the selected model.
     */
    suspend fun importModel(
        uri: android.net.Uri,
        modelId: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val model = AiModel.fromId(modelId)
            if (model == AiModel.RULE_BASED || model == AiModel.GEMINI_NANO) {
                Log.w(TAG, "Cannot import ${model.id} - not a file-based model")
                return@withContext false
            }

            _modelStatus.value = AiModelStatus.Downloading(0)
            onProgress(0)

            val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
            val fileExtension = AiModel.getFileExtension(model)
            val modelFile = File(modelDir, "${model.id}$fileExtension")

            // Copy from Uri to internal storage
            context.contentResolver.openInputStream(uri)?.use { input ->
                val fileSize = input.available().toLong().coerceAtLeast(1L)
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        val progress = ((totalRead * 100) / fileSize).toInt().coerceIn(0, 99)
                        _modelStatus.value = AiModelStatus.Downloading(progress)
                        onProgress(progress)
                    }
                }
            } ?: run {
                _modelStatus.value = AiModelStatus.Error("Could not open file")
                return@withContext false
            }

            // Verify the file was copied successfully
            if (!modelFile.exists() || modelFile.length() < 1000) {
                _modelStatus.value = AiModelStatus.Error("File copy failed or file too small")
                return@withContext false
            }

            Log.i(TAG, "Model imported: ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")

            // Update settings - enable AI and set model
            aiSettingsRepository.setEnabled(true)  // Enable AI so initializeModel() works
            aiSettingsRepository.setModelDownloaded(true, modelFile.length() / (1024 * 1024))
            aiSettingsRepository.setSelectedModel(modelId)
            currentModel = model
            _modelStatus.value = AiModelStatus.Ready
            onProgress(100)

            // Initialize the model (AI is now enabled)
            initializeModel()
        } catch (e: Exception) {
            Log.e(TAG, "Error importing model", e)
            _modelStatus.value = AiModelStatus.Error("Import failed: ${e.message}")
            false
        }
    }

    /**
     * Get the path to the models directory for manual placement.
     */
    fun getModelsDirectory(): File {
        return context.getDir("ai_models", Context.MODE_PRIVATE)
    }

    /**
     * Get the name of the currently active LLM engine.
     */
    val activeEngineName: StateFlow<String>
        get() = MutableStateFlow(llmEngineManager.activeEngine.value.displayName).asStateFlow()

    /**
     * Cancel an ongoing model download.
     */
    fun cancelDownload() {
        geminiNanoClient.cancelDownload()
        // Note: MediaPipe downloads are handled separately via HTTP client
        // For now, we can cancel Gemini Nano downloads
        Log.i(TAG, "Download cancellation requested")
    }

    /**
     * Get the set of downloaded model IDs.
     */
    suspend fun getDownloadedModelIds(): Set<String> = withContext(Dispatchers.IO) {
        val downloadedModels = mutableSetOf<String>()

        // Rule-based is always "downloaded"
        downloadedModels.add(AiModel.RULE_BASED.id)

        // Check Gemini Nano availability
        if (geminiNanoClient.isReady() || geminiNanoClient.getStatus() == GeminiNanoStatus.Ready) {
            downloadedModels.add(AiModel.GEMINI_NANO.id)
        }

        // Check for downloaded MediaPipe models
        val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
        AiModel.entries.filter { it.modelFormat == ModelFormat.TASK }.forEach { model ->
            val taskFile = File(modelDir, "${model.id}.task")
            val binFile = File(modelDir, "${model.id}.bin")
            if ((taskFile.exists() && taskFile.length() > 10_000_000) ||
                (binFile.exists() && binFile.length() > 10_000_000)) {
                downloadedModels.add(model.id)
            }
        }

        downloadedModels
    }

    /**
     * Get storage info for a downloaded model.
     * Returns a human-readable string like "529 MB" or null if not downloaded.
     */
    fun getModelStorageInfo(modelId: String): String? {
        val model = AiModel.fromId(modelId)

        when (model.modelFormat) {
            ModelFormat.NONE -> return "Built-in"
            ModelFormat.MLKIT_GENAI, ModelFormat.AICORE -> {
                return if (geminiNanoClient.isReady()) "Managed by AICore" else null
            }
            ModelFormat.TASK -> {
                val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
                val taskFile = File(modelDir, "${model.id}.task")
                val binFile = File(modelDir, "${model.id}.bin")

                val file = when {
                    taskFile.exists() -> taskFile
                    binFile.exists() -> binFile
                    else -> return null
                }

                val sizeMb = file.length() / (1024 * 1024)
                return "$sizeMb MB"
            }
        }
    }

    /**
     * Delete downloaded model.
     */
    suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
            modelDir.listFiles()?.forEach { it.delete() }

            aiSettingsRepository.setModelDownloaded(false, 0)
            aiSettingsRepository.setSelectedModel("rule-based")
            currentModel = AiModel.RULE_BASED
            isModelLoaded = true
            _modelStatus.value = AiModelStatus.Ready
            analysisCache.clear()

            Log.i(TAG, "Model deleted, using rule-based analysis")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model", e)
            false
        }
    }

    /**
     * Switch to a different model.
     * Clears the analysis cache to prevent stale results from the previous model.
     */
    suspend fun selectModel(modelId: String): Boolean = modelStateMutex.withLock {
        val model = AiModel.fromId(modelId)

        // Clear cache when switching models to prevent serving stale results
        analysisCache.clear()
        Log.d(TAG, "Cleared analysis cache for model switch to: ${model.displayName}")

        if (model == AiModel.RULE_BASED) {
            currentModel = model
            isModelLoaded = true
            aiSettingsRepository.setSelectedModel(modelId)
            _modelStatus.value = AiModelStatus.Ready
            return@withLock true
        }

        // Gemini Nano is managed by Google Play Services, no file check needed
        if (model == AiModel.GEMINI_NANO) {
            if (!deviceInfo.isPixel8OrNewer || !deviceInfo.hasNpu) {
                Log.w(TAG, "Device does not support Gemini Nano")
                return@withLock false
            }
            currentModel = model
            aiSettingsRepository.setSelectedModel(modelId)
            return@withLock initializeModel()
        }

        // Check if model file exists (check both .task and .bin extensions)
        val modelDir = context.getDir("ai_models", Context.MODE_PRIVATE)
        val fileExtension = AiModel.getFileExtension(model)
        val modelFile = File(modelDir, "${model.id}$fileExtension")

        // Also check alternate extension
        val alternateExtension = if (fileExtension == ".task") ".bin" else ".task"
        val alternateFile = File(modelDir, "${model.id}$alternateExtension")

        val actualFile = when {
            modelFile.exists() && modelFile.length() > 1000 -> modelFile
            alternateFile.exists() && alternateFile.length() > 1000 -> alternateFile
            else -> null
        }

        return@withLock if (actualFile != null) {
            currentModel = model
            aiSettingsRepository.setSelectedModel(modelId)
            initializeModel()
        } else {
            Log.d(TAG, "Model file not found: ${modelFile.absolutePath} or ${alternateFile.absolutePath}")
            false // Need to download first
        }
    }

    private fun pruneCache() {
        if (analysisCache.size >= MAX_CACHE_SIZE) {
            val now = System.currentTimeMillis()
            // Remove expired entries using each entry's own expiry time
            val expired = analysisCache.entries
                .filter { now - it.value.timestamp > it.value.expiryMs }
                .map { it.key }
            expired.forEach { analysisCache.remove(it) }

            if (analysisCache.size >= MAX_CACHE_SIZE) {
                val oldest = analysisCache.entries
                    .sortedBy { it.value.timestamp }
                    .take(MAX_CACHE_SIZE / 4)
                    .map { it.key }
                oldest.forEach { analysisCache.remove(it) }
            }
        }

        // Also prune fast-path cache (max 200 entries)
        if (fastPathCache.size > 200) {
            val now = System.currentTimeMillis()
            val expiredKeys = fastPathCache.entries
                .filter { now - it.value.timestamp > it.value.expiryMs }
                .map { it.key }
            expiredKeys.forEach { fastPathCache.remove(it) }
        }
    }

    fun clearCache() {
        analysisCache.clear()
        fastPathCache.clear()
        cacheStats.reset()
        Log.d(TAG, "All caches cleared")
    }

    suspend fun isAvailable(): Boolean {
        val settings = aiSettingsRepository.settings.first()
        return settings.enabled
    }

    // ==================== SEMANTIC CACHE FUNCTIONS ====================

    /**
     * Generate a fast-path cache key using device type + detection method + protocol.
     * This allows quick lookups for identical device classification patterns.
     */
    private fun getFastCacheKey(detection: Detection): String {
        return "${detection.deviceType.name}:${detection.detectionMethod.name}:${detection.protocol.name}"
    }

    /**
     * Get appropriate cache expiry time based on device type classification.
     * Consumer devices and infrastructure get longer expiry (2 hours),
     * unknown/suspicious devices keep shorter expiry (30 min).
     */
    private fun getCacheExpiryForDevice(deviceType: DeviceType): Long {
        return when {
            deviceType in consumerDeviceTypes -> CACHE_EXPIRY_CONSUMER_DEVICE_MS
            deviceType in infrastructureDeviceTypes -> CACHE_EXPIRY_INFRASTRUCTURE_MS
            // Unknown or suspicious device types get shorter expiry
            deviceType == DeviceType.UNKNOWN_SURVEILLANCE -> CACHE_EXPIRY_UNKNOWN_MS
            deviceType == DeviceType.STINGRAY_IMSI -> CACHE_EXPIRY_UNKNOWN_MS
            deviceType == DeviceType.SURVEILLANCE_VAN -> CACHE_EXPIRY_UNKNOWN_MS
            deviceType == DeviceType.HIDDEN_CAMERA -> CACHE_EXPIRY_UNKNOWN_MS
            deviceType == DeviceType.ROGUE_AP -> CACHE_EXPIRY_UNKNOWN_MS
            deviceType == DeviceType.TRACKING_DEVICE -> CACHE_EXPIRY_UNKNOWN_MS
            deviceType == DeviceType.GNSS_SPOOFER -> CACHE_EXPIRY_UNKNOWN_MS
            deviceType == DeviceType.GNSS_JAMMER -> CACHE_EXPIRY_UNKNOWN_MS
            deviceType == DeviceType.RF_JAMMER -> CACHE_EXPIRY_UNKNOWN_MS
            // Default to standard 30-minute expiry
            else -> CACHE_EXPIRY_MS
        }
    }

    /**
     * Try to get a result from the fast-path cache.
     * Returns null if no valid cached result exists.
     */
    private fun tryFastPathCache(detection: Detection): AiAnalysisResult? {
        val key = getFastCacheKey(detection)
        val cached = fastPathCache[key] ?: return null
        val now = System.currentTimeMillis()

        if (now - cached.timestamp > cached.expiryMs) {
            // Entry expired, remove it
            fastPathCache.remove(key)
            return null
        }

        cacheStats.fastPathHits++
        cacheStats.hits++
        Log.d(TAG, "Fast-path cache hit for key: $key")
        return cached.result
    }

    /**
     * Add a result to the fast-path cache.
     */
    private fun addToFastPathCache(detection: Detection, result: AiAnalysisResult) {
        val key = getFastCacheKey(detection)
        val expiryMs = getCacheExpiryForDevice(detection.deviceType)
        fastPathCache[key] = CachedAnalysis(
            result = result,
            timestamp = System.currentTimeMillis(),
            expiryMs = expiryMs
        )
        Log.d(TAG, "Added to fast-path cache: $key (expiry: ${expiryMs / 1000 / 60} min)")
    }

    /**
     * Get current cache statistics for monitoring.
     */
    fun getCacheStats(): CacheStats = cacheStats.copy()

    /**
     * Reset cache statistics.
     */
    fun resetCacheStats() {
        cacheStats.reset()
    }

    /**
     * Find a semantically similar cached result.
     * Returns the cached result if a detection with similar characteristics exists.
     */
    private fun findSimilarCachedResult(detection: Detection): AiAnalysisResult? {
        if (!semanticCacheEnabled) return null

        val now = System.currentTimeMillis()

        for ((_, entry) in analysisCache) {
            // Skip expired entries (use variable expiry from entry)
            if (now - entry.timestamp > entry.expiryMs) continue

            val similarity = computeDetectionSimilarity(detection, entry.detection)
            if (similarity >= semanticSimilarityThreshold) {
                cacheStats.semanticHits++
                cacheStats.hits++
                Log.d(TAG, "Semantic cache hit! Similarity: ${(similarity * 100).toInt()}%")
                return entry.result.copy(
                    analysis = entry.result.analysis?.let {
                        "$it\n\n_[Analysis from similar detection]_"
                    }
                )
            }
        }

        return null
    }

    /**
     * Compute semantic similarity between two detections.
     * Returns a value between 0.0 (completely different) and 1.0 (identical).
     */
    private fun computeDetectionSimilarity(a: Detection, b: Detection): Float {
        var score = 0f
        var weight = 0f

        // Device type must match (heaviest weight)
        if (a.deviceType == b.deviceType) {
            score += 0.35f
        }
        weight += 0.35f

        // Protocol match
        if (a.protocol == b.protocol) {
            score += 0.15f
        }
        weight += 0.15f

        // Detection method match
        if (a.detectionMethod == b.detectionMethod) {
            score += 0.15f
        }
        weight += 0.15f

        // Threat level match
        if (a.threatLevel == b.threatLevel) {
            score += 0.1f
        }
        weight += 0.1f

        // Signal strength within 10 dBm
        if (kotlin.math.abs(a.rssi - b.rssi) <= 10) {
            score += 0.1f
        }
        weight += 0.1f

        // Threat score within 10 points
        if (kotlin.math.abs(a.threatScore - b.threatScore) <= 10) {
            score += 0.1f
        }
        weight += 0.1f

        // Manufacturer match (if both have it)
        if (a.manufacturer != null && b.manufacturer != null && a.manufacturer == b.manufacturer) {
            score += 0.05f
        }
        weight += 0.05f

        return score / weight
    }

    // ==================== MODEL WARM-UP ====================

    /**
     * Warm up the LLM model with a simple query to reduce first-inference latency.
     * Call this after model initialization for better user experience.
     */
    suspend fun warmUpModel(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting model warm-up...")

        if (currentModel == AiModel.GEMINI_NANO && geminiNanoClient.isReady()) {
            // Gemini Nano doesn't need warm-up (always ready via AICore)
            Log.d(TAG, "Gemini Nano ready (no warm-up needed)")
            return@withContext true
        }

        if (!mediaPipeLlmClient.isReady()) {
            Log.w(TAG, "MediaPipe LLM not ready for warm-up")
            return@withContext false
        }

        try {
            val warmupPrompt = """<start_of_turn>user
What is an IMSI catcher? Reply in one sentence.
<end_of_turn>
<start_of_turn>model
"""
            val startTime = System.currentTimeMillis()
            val response = mediaPipeLlmClient.generateResponse(warmupPrompt)
            val duration = System.currentTimeMillis() - startTime

            if (response != null) {
                Log.i(TAG, "Model warm-up completed in ${duration}ms")
                return@withContext true
            } else {
                Log.w(TAG, "Model warm-up returned null response")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model warm-up failed: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Warm up the analysis cache with pre-populated common patterns.
     * Call this during app startup to improve cache hit rates for common device types.
     * This method:
     * 1. Pre-populates the fast-path cache with analysis for common benign devices
     * 2. Optionally warms up the LLM model
     */
    suspend fun warmUpCache(): Unit = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting cache warm-up...")
        val startTime = System.currentTimeMillis()

        try {
            // Pre-populate common patterns
            prepopulateCommonPatterns()

            // Also warm up the model if needed
            warmUpModel()

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Cache warm-up completed in ${duration}ms. Fast-path cache size: ${fastPathCache.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Cache warm-up failed: ${e.message}")
        }
    }

    /**
     * Pre-populate the fast-path cache with common benign device patterns.
     * This improves hit rates by caching analysis for devices that are frequently encountered.
     */
    private fun prepopulateCommonPatterns() {
        Log.d(TAG, "Pre-populating common device patterns...")

        // Common consumer smart home devices with pre-built analysis
        val commonPatterns = listOf(
            // Ring devices
            Triple(
                DeviceType.RING_DOORBELL,
                DetectionMethod.SSID_PATTERN,
                "Consumer smart home device. Ring Doorbells are Amazon-owned smart doorbells that can record video and audio. While they contribute to neighborhood surveillance networks, they are legitimate consumer products. Privacy concern is moderate due to data sharing with law enforcement."
            ),
            // Nest/Google cameras
            Triple(
                DeviceType.NEST_CAMERA,
                DetectionMethod.SSID_PATTERN,
                "Consumer smart home device. Google Nest cameras are cloud-connected security cameras. They may share data with Google and, under certain conditions, law enforcement. Privacy concern is moderate."
            ),
            // Wyze cameras
            Triple(
                DeviceType.WYZE_CAMERA,
                DetectionMethod.SSID_PATTERN,
                "Consumer smart home device. Wyze cameras are affordable smart cameras popular for home security. Data is stored in the cloud. Privacy concern is low to moderate."
            ),
            // Arlo cameras
            Triple(
                DeviceType.ARLO_CAMERA,
                DetectionMethod.SSID_PATTERN,
                "Consumer smart home device. Arlo cameras are wireless security cameras with cloud storage. They have good encryption but data is cloud-accessible. Privacy concern is low to moderate."
            ),
            // Eufy cameras
            Triple(
                DeviceType.EUFY_CAMERA,
                DetectionMethod.SSID_PATTERN,
                "Consumer smart home device. Eufy cameras emphasize local storage but have had past privacy controversies regarding cloud uploads. Privacy concern is low to moderate."
            ),
            // Blink cameras
            Triple(
                DeviceType.BLINK_CAMERA,
                DetectionMethod.SSID_PATTERN,
                "Consumer smart home device. Blink is an Amazon-owned camera brand with cloud storage. Similar privacy implications to Ring. Privacy concern is moderate."
            ),
            // SimpliSafe
            Triple(
                DeviceType.SIMPLISAFE_DEVICE,
                DetectionMethod.SSID_PATTERN,
                "Consumer home security system. SimpliSafe is a popular DIY home security system. Professional monitoring available. Privacy concern is low."
            ),
            // ADT
            Triple(
                DeviceType.ADT_DEVICE,
                DetectionMethod.SSID_PATTERN,
                "Professional home security system. ADT is a traditional security company with professional monitoring. Privacy concern is low."
            ),
            // Vivint
            Triple(
                DeviceType.VIVINT_DEVICE,
                DetectionMethod.SSID_PATTERN,
                "Professional smart home security. Vivint provides professional installation and monitoring with smart home integration. Privacy concern is low."
            ),
            // Amazon Sidewalk
            Triple(
                DeviceType.AMAZON_SIDEWALK,
                DetectionMethod.SSID_PATTERN,
                "Amazon Sidewalk network device. Sidewalk creates a shared network using customer devices. This extends Amazon's tracking capabilities but is opt-out. Privacy concern is moderate due to mesh network tracking potential."
            ),
            // Bluetooth beacons (infrastructure)
            Triple(
                DeviceType.BLUETOOTH_BEACON,
                DetectionMethod.BLE_SERVICE_UUID,
                "Retail/commercial Bluetooth beacon. Used for indoor navigation and proximity marketing. Common in stores and malls. Privacy concern is low to moderate depending on location tracking usage."
            ),
            // Retail trackers
            Triple(
                DeviceType.RETAIL_TRACKER,
                DetectionMethod.BLE_SERVICE_UUID,
                "Retail analytics device. Used by stores for foot traffic analysis and customer behavior tracking. Privacy concern is moderate as it tracks movement patterns."
            )
        )

        val now = System.currentTimeMillis()

        for ((deviceType, detectionMethod, analysisText) in commonPatterns) {
            // Create analysis result for each common pattern
            val result = AiAnalysisResult(
                success = true,
                analysis = analysisText,
                modelUsed = "cached_pattern",
                processingTimeMs = 0,
                structuredData = StructuredAnalysis(
                    deviceCategory = when (deviceType) {
                        in consumerDeviceTypes -> "Consumer Smart Home"
                        in infrastructureDeviceTypes -> "Commercial Infrastructure"
                        else -> "Unknown"
                    },
                    surveillanceType = "Passive Observation",
                    dataCollectionTypes = listOf("Video", "Audio", "Presence"),
                    riskScore = when (deviceType) {
                        DeviceType.RING_DOORBELL, DeviceType.NEST_CAMERA, DeviceType.AMAZON_SIDEWALK -> 30
                        DeviceType.RETAIL_TRACKER -> 40
                        else -> 20
                    },
                    riskFactors = listOf("Consumer surveillance device", "Data may be cloud-stored"),
                    mitigationActions = emptyList(),
                    contextualInsights = null
                )
            )

            // Add to fast-path cache with consumer device expiry (2 hours)
            val protocols = listOf(DetectionProtocol.WIFI, DetectionProtocol.BLUETOOTH_LE)
            for (protocol in protocols) {
                val key = "${deviceType.name}:${detectionMethod.name}:${protocol.name}"
                fastPathCache[key] = CachedAnalysis(
                    result = result,
                    timestamp = now,
                    expiryMs = CACHE_EXPIRY_CONSUMER_DEVICE_MS
                )
            }
        }

        Log.d(TAG, "Pre-populated ${fastPathCache.size} fast-path cache entries")
    }

    /**
     * Check if the model is warmed up and ready for fast inference.
     */
    fun isModelWarmedUp(): Boolean {
        return when {
            currentModel == AiModel.GEMINI_NANO -> geminiNanoClient.isReady()
            currentModel == AiModel.RULE_BASED -> true
            else -> mediaPipeLlmClient.isReady()
        }
    }

    /**
     * Get estimated inference time based on model and device capabilities.
     */
    fun getEstimatedInferenceTimeMs(): Long {
        return when (currentModel) {
            AiModel.GEMINI_NANO -> 500L  // NPU accelerated
            AiModel.RULE_BASED -> 10L    // No LLM, instant
            else -> if (deviceInfo.hasNpu || deviceInfo.availableRamMb > 8000) {
                2000L  // Good hardware
            } else {
                5000L  // Standard hardware
            }
        }
    }

    /**
     * Clean up all resources held by the DetectionAnalyzer.
     * Call this when the component is being destroyed to prevent memory leaks.
     */
    suspend fun cleanup() {
        cancelAnalysis()
        falsePositiveAnalyzer.clearLazyInitCallback()
        cacheMutex.withLock {
            analysisCache.clear()
        }
        fastPathCache.clear()
        cacheStats.reset()
        feedbackMutex.withLock {
            feedbackHistory.clear()
        }
        llmEngineManager.cleanup()
        isModelLoaded = false
        geminiNanoInitialized = false
        Log.d(TAG, "DetectionAnalyzer cleanup completed")
    }

    /**
     * Synchronous cleanup for non-suspend contexts (e.g., onDestroy).
     * Marks state as cleaned up immediately.
     */
    fun cleanupSync() {
        cancelAnalysis()
        falsePositiveAnalyzer.clearLazyInitCallback()
        analysisCache.clear()
        feedbackHistory.clear()
        llmEngineManager.cleanupSync()
        isModelLoaded = false
        geminiNanoInitialized = false
        Log.d(TAG, "DetectionAnalyzer sync cleanup completed")
    }

    // ==================== GEMINI NANO DIAGNOSTICS ====================

    /**
     * Get detailed diagnostics for Gemini Nano / AICore troubleshooting.
     * Returns comprehensive information about device support, AICore status, and model availability.
     */
    suspend fun getGeminiNanoDiagnostics(): GeminiNanoDiagnostics {
        return geminiNanoClient.getDiagnostics()
    }

    /**
     * Get the current Gemini Nano status.
     */
    fun getGeminiNanoStatus(): GeminiNanoStatus {
        return geminiNanoClient.getStatus()
    }

    /**
     * Get a user-friendly status message for Gemini Nano.
     */
    fun getGeminiNanoStatusMessage(): String {
        return geminiNanoClient.getStatusMessage()
    }

    /**
     * Force retry Gemini Nano model download and initialization.
     * Use this when the user wants to retry after a failed download/initialization.
     *
     * @param onProgress Callback for download progress updates (0-100)
     * @return true if Gemini Nano is ready for inference after this call
     */
    suspend fun forceRetryGeminiNano(onProgress: (Int) -> Unit = {}): Boolean {
        Log.i(TAG, "Force retry Gemini Nano requested")

        val result = geminiNanoClient.forceRetryDownload(onProgress)

        if (result) {
            // Update local state
            geminiNanoInitialized = true
            isModelLoaded = true
            currentModel = AiModel.GEMINI_NANO
            _modelStatus.value = AiModelStatus.Ready

            // Save the model selection
            aiSettingsRepository.setSelectedModel(AiModel.GEMINI_NANO.id)
            aiSettingsRepository.setEnabled(true)

            Log.i(TAG, "Gemini Nano force retry succeeded!")
        } else {
            Log.w(TAG, "Gemini Nano force retry failed")
        }

        return result
    }

    /**
     * Check if Gemini Nano is supported on this device.
     */
    fun isGeminiNanoSupported(): Boolean {
        return geminiNanoClient.isDeviceSupported()
    }

    /**
     * Check if AICore service is available on this device.
     */
    suspend fun isAiCoreAvailable(): Boolean {
        return geminiNanoClient.isAiCoreAvailable()
    }

    /**
     * Expose Gemini Nano model status flow for UI observation.
     */
    val geminiNanoModelStatus: StateFlow<GeminiNanoStatus> = geminiNanoClient.modelStatus

    /**
     * Expose Gemini Nano download progress flow for UI observation.
     */
    val geminiNanoDownloadProgress: StateFlow<Int> = geminiNanoClient.downloadProgress
}

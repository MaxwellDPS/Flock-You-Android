package com.flockyou.data

import android.content.Context
import com.flockyou.config.NetworkConfig
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_settings")

/**
 * AI analysis settings for LOCAL ON-DEVICE LLM inference only.
 * No cloud APIs - all analysis happens on the device for maximum privacy.
 */
/**
 * LLM engine preference for manual selection.
 */
enum class LlmEnginePreference(val id: String, val displayName: String, val description: String) {
    AUTO("auto", "Auto (Recommended)", "Automatically selects the best available engine"),
    GEMINI_NANO("gemini-nano", "Gemini Nano", "Google's on-device AI via ML Kit GenAI (Pixel 8+, Alpha API)"),
    MEDIAPIPE("mediapipe", "MediaPipe LLM", "MediaPipe with Gemma models (works on most devices)"),
    RULE_BASED("rule-based", "Rule-Based Only", "No LLM, use built-in rules only")
}

/**
 * Prompt compression mode for LLM analysis.
 * Compact prompts reduce token count by 50-70% for faster inference.
 */
enum class PromptCompressionMode(val id: String, val displayName: String, val description: String) {
    AUTO("auto", "Auto", "Automatically select based on model size and device capability"),
    VERBOSE("verbose", "Verbose", "Full detailed prompts for maximum quality (~1500+ tokens)"),
    COMPACT("compact", "Compact", "Compressed prompts for faster inference (~400 tokens)")
}

data class AiSettings(
    val enabled: Boolean = false,
    val modelDownloaded: Boolean = false,
    val selectedModel: String = "rule-based", // Selected model ID
    val preferredEngine: String = "auto", // LLM engine preference: auto, gemini-nano, mediapipe, rule-based
    val modelSizeMb: Long = 0,
    val useGpuAcceleration: Boolean = true,
    val useNpuAcceleration: Boolean = true, // Pixel NPU support
    val analyzeDetections: Boolean = true,
    val generateThreatAssessments: Boolean = true,
    val identifyUnknownDevices: Boolean = true,
    val autoAnalyzeNewDetections: Boolean = false,
    val enableContextualAnalysis: Boolean = true, // Use location/time patterns
    val enableBatchAnalysis: Boolean = false, // Batch analysis for density mapping
    val trackAnalysisFeedback: Boolean = true, // Learn from user feedback
    val enableFalsePositiveFiltering: Boolean = true, // Auto-filter likely false positives
    val maxTokens: Int = 1024,
    val temperatureTenths: Int = 7, // 0.7 stored as int to avoid float precision issues
    val lastModelUpdate: Long = 0,
    val huggingFaceToken: String = "", // HF token for authenticated model downloads
    val promptCompressionMode: String = "auto" // Prompt compression: auto, verbose, compact
)

/**
 * Model format types for on-device LLM inference.
 */
enum class ModelFormat {
    NONE,     // No model file needed (rule-based)
    AICORE,   // Google AICore (Gemini Nano via legacy SDK)
    MLKIT_GENAI, // ML Kit GenAI Prompt API (Gemini Nano, alpha)
    TASK      // MediaPipe .task format (Gemma models)
}

/**
 * API stability status for engines.
 */
enum class ApiStability {
    STABLE,   // Production-ready API
    BETA,     // Beta API - may have minor changes
    ALPHA     // Alpha API - no SLA, may have breaking changes
}

/**
 * Available on-device LLM models for analysis.
 * All models run entirely on-device - no cloud connectivity required.
 *
 * Note: MediaPipe LLM Inference requires models in .task or .bin format,
 * NOT raw GGUF files. The models listed here use MediaPipe-compatible formats.
 */
enum class AiModel(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeMb: Long,
    val capabilities: List<String>,
    val minAndroidVersion: Int = 26, // Android 8.0
    val requiresPixel8: Boolean = false,
    val requiresNpu: Boolean = false,
    val downloadUrl: String? = null, // null means bundled or special handling
    val quantization: String = "N/A",
    val modelFormat: ModelFormat = ModelFormat.TASK, // MediaPipe format
    val apiStability: ApiStability = ApiStability.STABLE // API stability indicator
) {
    RULE_BASED(
        id = "rule-based",
        displayName = "Rule-Based Analysis",
        description = "Built-in analysis using curated threat intelligence. Works on all devices, no download required.",
        sizeMb = 0,
        capabilities = listOf("Device identification", "Threat assessment", "Recommendations"),
        quantization = "N/A",
        modelFormat = ModelFormat.NONE,
        apiStability = ApiStability.STABLE
    ),
    GEMINI_NANO(
        id = "gemini-nano",
        displayName = "Gemini Nano (ML Kit)",
        description = "Google's Gemini Nano via ML Kit GenAI Prompt API. Alpha API - may have breaking changes. Requires Pixel 8+ with AICore.",
        sizeMb = 0, // Managed by AICore
        capabilities = listOf("Custom prompts", "Text generation", "Multimodal input", "NPU acceleration"),
        requiresPixel8 = true,
        requiresNpu = true,
        minAndroidVersion = 34, // Android 14+
        quantization = "INT4",
        modelFormat = ModelFormat.MLKIT_GENAI,
        apiStability = ApiStability.ALPHA
    ),
    // MediaPipe-compatible Gemma 3 models (latest, recommended)
    // Official litert-community Gemma 3 1B model optimized for both GPU and CPU
    // See: https://huggingface.co/litert-community/Gemma3-1B-IT
    // Download URL is configurable via NetworkConfig for OEM customization
    GEMMA3_1B(
        id = "gemma3-1b",
        displayName = "Gemma 3 1B",
        description = "Official Gemma 3 1B model with GPU/CPU support. ~529MB. No authentication required.",
        sizeMb = 529,
        capabilities = listOf("Text generation", "Reasoning", "Summarization"),
        minAndroidVersion = 26,
        // URL resolved at runtime via NetworkConfig for OEM customization
        downloadUrl = null, // Use getDownloadUrl() instead
        quantization = "INT4 QAT",
        modelFormat = ModelFormat.TASK,
        apiStability = ApiStability.STABLE
    ),
    // MediaPipe-compatible Gemma 2 models
    // Download URLs are configurable via NetworkConfig for OEM customization
    GEMMA_2B_CPU(
        id = "gemma-2b-cpu",
        displayName = "Gemma 2B (CPU)",
        description = "Google's Gemma 2B optimized for CPU. ~1.35GB download. No authentication required.",
        sizeMb = 1350,
        capabilities = listOf("Text generation", "Reasoning", "Summarization"),
        minAndroidVersion = 26,
        // URL resolved at runtime via NetworkConfig for OEM customization
        downloadUrl = null, // Use getDownloadUrl() instead
        quantization = "INT4",
        modelFormat = ModelFormat.TASK, // MediaPipe also supports .bin files
        apiStability = ApiStability.STABLE
    ),
    GEMMA_2B_GPU(
        id = "gemma-2b-gpu",
        displayName = "Gemma 2 2B (CPU INT8)",
        description = "Gemma 2 2B with INT8 quantization. Higher quality, larger size (~3.2GB).",
        sizeMb = 3200,
        capabilities = listOf("Text generation", "Reasoning", "Summarization", "Higher accuracy"),
        minAndroidVersion = 28,
        // URL resolved at runtime via NetworkConfig for OEM customization
        downloadUrl = null, // Use getDownloadUrl() instead
        quantization = "INT8",
        modelFormat = ModelFormat.TASK,
        apiStability = ApiStability.STABLE
    );

    companion object {
        private const val TAG = "AiModel"

        fun fromId(id: String): AiModel {
            val model = entries.find { it.id == id }
            if (model == null) {
                android.util.Log.w(TAG, "Unknown model ID '$id', falling back to RULE_BASED")
            }
            return model ?: RULE_BASED
        }

        /**
         * Get the download URL for a model.
         * URLs are resolved at runtime via NetworkConfig to support OEM customization.
         */
        fun getDownloadUrl(model: AiModel): String? {
            return when (model) {
                GEMMA3_1B -> NetworkConfig.AI_MODEL_GEMMA3_1B_URL
                GEMMA_2B_CPU -> NetworkConfig.AI_MODEL_GEMMA_2B_CPU_URL
                GEMMA_2B_GPU -> NetworkConfig.AI_MODEL_GEMMA_2B_GPU_URL
                else -> model.downloadUrl // Return static URL for other models (if any)
            }
        }

        /**
         * Get models suitable for the current device
         */
        fun getAvailableModels(
            isPixel8OrNewer: Boolean = false,
            hasNpu: Boolean = false,
            availableRamMb: Long = 2048
        ): List<AiModel> {
            return entries.filter { model ->
                when {
                    model.requiresPixel8 && !isPixel8OrNewer -> false
                    model.requiresNpu && !hasNpu -> false
                    model.sizeMb > 0 && model.sizeMb * 1.5 > availableRamMb -> false // Need ~1.5x model size for inference
                    else -> true
                }
            }
        }

        /**
         * Get the file extension for the model format.
         * Extracts extension from download URL (resolved via NetworkConfig) if available.
         */
        fun getFileExtension(model: AiModel): String {
            // Get download URL via NetworkConfig for runtime resolution
            val url = getDownloadUrl(model)
            if (url != null) {
                return when {
                    url.endsWith(".task") -> ".task"
                    url.endsWith(".bin") -> ".bin"
                    else -> ".task" // Default for TASK format
                }
            }
            // Fallback based on format
            return when (model.modelFormat) {
                ModelFormat.TASK -> ".task"
                ModelFormat.AICORE -> "" // No file, managed by AICore
                ModelFormat.MLKIT_GENAI -> "" // No file, managed by ML Kit GenAI/AICore
                ModelFormat.NONE -> "" // No file needed
            }
        }

        /**
         * Get instructions for how to obtain the model
         */
        fun getDownloadInstructions(model: AiModel): String {
            return when (model.modelFormat) {
                ModelFormat.TASK -> """
                    To use this model:
                    1. Visit https://www.kaggle.com/models/google/gemma
                    2. Accept the license agreement
                    3. Download the model in MediaPipe format
                    4. Place the .task file in the app's models folder
                """.trimIndent()
                ModelFormat.AICORE -> "This model is managed by Google Play Services. Ensure AICore is up to date."
                ModelFormat.MLKIT_GENAI -> "This model is managed by ML Kit GenAI via AICore. The model will be downloaded automatically on first use."
                ModelFormat.NONE -> "No download required."
            }
        }
    }
}

/**
 * Result of an AI analysis operation.
 * All analysis is performed locally on the device.
 *
 * Enhanced with enterprise-grade fields for comprehensive, actionable intelligence:
 * - Severity-aligned messaging (LOW severity = reassuring, HIGH = urgent)
 * - False positive assessment and acknowledgment
 * - Simple and technical explanations for different user types
 * - Actionable recommendations with urgency levels
 */
data class AiAnalysisResult(
    val success: Boolean,
    val analysis: String? = null,
    val threatAssessment: String? = null,
    val recommendations: List<String> = emptyList(),
    val confidence: Float = 0f,
    val processingTimeMs: Long = 0,
    val error: String? = null,
    val modelUsed: String = "rule-based",
    val wasOnDevice: Boolean = true, // Always true - no cloud fallback
    val wasCancelled: Boolean = false, // True if analysis was cancelled by user
    // Structured output fields for programmatic use
    val structuredData: StructuredAnalysis? = null,
    // False positive analysis results
    val isFalsePositive: Boolean = false,
    val falsePositiveConfidence: Float = 0f,
    val falsePositiveBanner: String? = null, // User-friendly explanation if FP
    val falsePositiveReasons: List<String> = emptyList(),

    // === ENTERPRISE-GRADE FIELDS ===
    // These provide comprehensive, actionable intelligence beyond basic analysis

    // False positive likelihood as percentage (0-100)
    // If > 50%, the detection is likely benign
    val isFalsePositiveLikely: Boolean = false,
    val falsePositiveLikelihoodPercent: Int = 0,

    // User-friendly explanation (non-technical) for general users
    // Example: "Your phone's connection changed, but this is probably just normal cell tower behavior."
    val simpleExplanation: String? = null,

    // Technical details for advanced users
    // Example: "IMSI Catcher Score: 20%, Encryption Chain: 5G -> 4G, Cell Trust: 85%"
    val technicalDetails: String? = null
)

/**
 * Structured analysis data for programmatic consumption.
 * Allows other parts of the app to use analysis results directly.
 *
 * Enhanced with enterprise description for comprehensive metadata access.
 */
data class StructuredAnalysis(
    val deviceCategory: String,
    val surveillanceType: String,
    val dataCollectionTypes: List<String>,
    val riskScore: Int, // 0-100
    val riskFactors: List<String>,
    val mitigationActions: List<MitigationAction>,
    val contextualInsights: ContextualInsights? = null,

    // === ENTERPRISE DESCRIPTION ===
    // Full enterprise-grade description with all metadata
    // This is typed as Any? to avoid circular dependency with PromptTemplates
    // Cast to PromptTemplates.EnterpriseDetectionDescription when needed
    val enterpriseDescription: Any? = null
)

data class MitigationAction(
    val action: String,
    val priority: ActionPriority,
    val description: String
)

enum class ActionPriority { IMMEDIATE, HIGH, MEDIUM, LOW }

/**
 * Configuration for model inference, derived from AiSettings.
 */
data class InferenceConfig(
    val maxTokens: Int,
    val temperature: Float,
    val useGpuAcceleration: Boolean,
    val useNpuAcceleration: Boolean
) {
    companion object {
        fun fromSettings(settings: AiSettings): InferenceConfig = InferenceConfig(
            maxTokens = settings.maxTokens,
            temperature = settings.temperatureTenths / 10f,
            useGpuAcceleration = settings.useGpuAcceleration,
            useNpuAcceleration = settings.useNpuAcceleration
        )
    }
}

data class ContextualInsights(
    val isKnownLocation: Boolean = false,
    val locationPattern: String? = null, // e.g., "Seen 5 times at this location"
    val timePattern: String? = null, // e.g., "Usually active at night"
    val clusterInfo: String? = null, // e.g., "Part of 3-device surveillance cluster"
    val historicalContext: String? = null // e.g., "First seen 2 weeks ago"
)

/**
 * Batch analysis result for density mapping and route analysis
 */
data class BatchAnalysisResult(
    val success: Boolean,
    val totalDevicesAnalyzed: Int,
    val surveillanceDensityScore: Int, // 0-100
    val hotspots: List<SurveillanceHotspot>,
    val routeAnalysis: RouteAnalysis? = null,
    val anomalies: List<String>,
    val processingTimeMs: Long,
    val error: String? = null
)

data class SurveillanceHotspot(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val deviceCount: Int,
    val threatLevel: String,
    val dominantDeviceType: String
)

data class RouteAnalysis(
    val totalRouteLength: Double, // meters
    val surveillanceExposure: Int, // percentage of route under surveillance
    val safestAlternative: String? = null, // Description of safer route
    val highRiskSegments: List<String>
)

/**
 * User feedback for analysis learning
 */
data class AnalysisFeedback(
    val detectionId: String,
    val analysisTimestamp: Long,
    val wasHelpful: Boolean,
    val feedbackType: FeedbackType,
    val userCorrection: String? = null
)

enum class FeedbackType {
    ACCURATE, // Analysis was accurate
    INACCURATE_DEVICE_TYPE, // Wrong device identification
    INACCURATE_THREAT_LEVEL, // Wrong threat assessment
    MISSING_INFO, // Analysis missed important information
    TOO_VERBOSE, // Analysis was too long
    NOT_HELPFUL // General unhelpful
}

/**
 * Status of the AI model download/initialization.
 */
sealed class AiModelStatus {
    object NotDownloaded : AiModelStatus()
    data class Downloading(val progressPercent: Int) : AiModelStatus()
    object Initializing : AiModelStatus()
    object Ready : AiModelStatus()
    data class Error(val message: String) : AiModelStatus()
}

@Singleton
class AiSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("ai_enabled")
        val MODEL_DOWNLOADED = booleanPreferencesKey("ai_model_downloaded")
        val SELECTED_MODEL = stringPreferencesKey("ai_selected_model")
        val PREFERRED_ENGINE = stringPreferencesKey("ai_preferred_engine")
        val MODEL_SIZE_MB = longPreferencesKey("ai_model_size_mb")
        val USE_GPU = booleanPreferencesKey("ai_use_gpu")
        val USE_NPU = booleanPreferencesKey("ai_use_npu")
        val ANALYZE_DETECTIONS = booleanPreferencesKey("ai_analyze_detections")
        val GENERATE_THREAT_ASSESSMENTS = booleanPreferencesKey("ai_generate_threat_assessments")
        val IDENTIFY_UNKNOWN = booleanPreferencesKey("ai_identify_unknown")
        val AUTO_ANALYZE = booleanPreferencesKey("ai_auto_analyze")
        val CONTEXTUAL_ANALYSIS = booleanPreferencesKey("ai_contextual_analysis")
        val BATCH_ANALYSIS = booleanPreferencesKey("ai_batch_analysis")
        val TRACK_FEEDBACK = booleanPreferencesKey("ai_track_feedback")
        val FALSE_POSITIVE_FILTERING = booleanPreferencesKey("ai_false_positive_filtering")
        val MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        val TEMPERATURE_TENTHS = intPreferencesKey("ai_temperature_tenths")
        val LAST_MODEL_UPDATE = longPreferencesKey("ai_last_model_update")
        val PROMPT_COMPRESSION_MODE = stringPreferencesKey("ai_prompt_compression_mode")
        // Note: HuggingFace token is stored in EncryptedSharedPreferences, not DataStore
    }

    // Encrypted storage for sensitive credentials like API tokens
    private object EncryptedKeys {
        const val HUGGINGFACE_TOKEN = "hf_token_encrypted"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "ai_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("AiSettingsRepository", "Failed to create encrypted prefs, using fallback", e)
            // Fallback to regular shared prefs if encryption fails (rare, device-specific issues)
            context.getSharedPreferences("ai_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    val settings: Flow<AiSettings> = context.aiSettingsDataStore.data.map { prefs ->
        AiSettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            modelDownloaded = prefs[Keys.MODEL_DOWNLOADED] ?: false,
            selectedModel = prefs[Keys.SELECTED_MODEL] ?: "rule-based",
            preferredEngine = prefs[Keys.PREFERRED_ENGINE] ?: "auto",
            modelSizeMb = prefs[Keys.MODEL_SIZE_MB] ?: 0,
            useGpuAcceleration = prefs[Keys.USE_GPU] ?: true,
            useNpuAcceleration = prefs[Keys.USE_NPU] ?: true,
            analyzeDetections = prefs[Keys.ANALYZE_DETECTIONS] ?: true,
            generateThreatAssessments = prefs[Keys.GENERATE_THREAT_ASSESSMENTS] ?: true,
            identifyUnknownDevices = prefs[Keys.IDENTIFY_UNKNOWN] ?: true,
            autoAnalyzeNewDetections = prefs[Keys.AUTO_ANALYZE] ?: false,
            enableContextualAnalysis = prefs[Keys.CONTEXTUAL_ANALYSIS] ?: true,
            enableBatchAnalysis = prefs[Keys.BATCH_ANALYSIS] ?: false,
            trackAnalysisFeedback = prefs[Keys.TRACK_FEEDBACK] ?: true,
            enableFalsePositiveFiltering = prefs[Keys.FALSE_POSITIVE_FILTERING] ?: true,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 1024,
            temperatureTenths = prefs[Keys.TEMPERATURE_TENTHS] ?: 7,
            lastModelUpdate = prefs[Keys.LAST_MODEL_UPDATE] ?: 0,
            // Read HuggingFace token from encrypted storage
            huggingFaceToken = getHuggingFaceTokenInternal(),
            promptCompressionMode = prefs[Keys.PROMPT_COMPRESSION_MODE] ?: "auto"
        )
    }

    /**
     * Get the HuggingFace token from encrypted storage.
     * Called synchronously since EncryptedSharedPreferences doesn't support flows.
     */
    private fun getHuggingFaceTokenInternal(): String {
        return try {
            encryptedPrefs.getString(EncryptedKeys.HUGGINGFACE_TOKEN, "") ?: ""
        } catch (e: Exception) {
            android.util.Log.w("AiSettingsRepository", "Failed to read encrypted token", e)
            ""
        }
    }

    /**
     * Set the HuggingFace API token.
     * Stored in EncryptedSharedPreferences for security.
     */
    suspend fun setHuggingFaceToken(token: String) {
        try {
            encryptedPrefs.edit().putString(EncryptedKeys.HUGGINGFACE_TOKEN, token).apply()
        } catch (e: Exception) {
            android.util.Log.e("AiSettingsRepository", "Failed to save encrypted token", e)
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setModelDownloaded(downloaded: Boolean, sizeMb: Long = 0) {
        context.aiSettingsDataStore.edit {
            it[Keys.MODEL_DOWNLOADED] = downloaded
            it[Keys.MODEL_SIZE_MB] = sizeMb
            if (downloaded) {
                it[Keys.LAST_MODEL_UPDATE] = System.currentTimeMillis()
            }
        }
    }

    suspend fun setSelectedModel(modelId: String) {
        context.aiSettingsDataStore.edit { it[Keys.SELECTED_MODEL] = modelId }
    }

    suspend fun setPreferredEngine(engineId: String) {
        context.aiSettingsDataStore.edit { it[Keys.PREFERRED_ENGINE] = engineId }
    }

    suspend fun setUseGpuAcceleration(useGpu: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.USE_GPU] = useGpu }
    }

    suspend fun setUseNpuAcceleration(useNpu: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.USE_NPU] = useNpu }
    }

    suspend fun setAnalyzeDetections(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.ANALYZE_DETECTIONS] = enabled }
    }

    suspend fun setGenerateThreatAssessments(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.GENERATE_THREAT_ASSESSMENTS] = enabled }
    }

    suspend fun setIdentifyUnknownDevices(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.IDENTIFY_UNKNOWN] = enabled }
    }

    suspend fun setAutoAnalyzeNewDetections(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.AUTO_ANALYZE] = enabled }
    }

    suspend fun setContextualAnalysis(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.CONTEXTUAL_ANALYSIS] = enabled }
    }

    suspend fun setBatchAnalysis(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.BATCH_ANALYSIS] = enabled }
    }

    suspend fun setTrackFeedback(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.TRACK_FEEDBACK] = enabled }
    }

    suspend fun setFalsePositiveFiltering(enabled: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.FALSE_POSITIVE_FILTERING] = enabled }
    }

    suspend fun setMaxTokens(tokens: Int) {
        context.aiSettingsDataStore.edit { it[Keys.MAX_TOKENS] = tokens.coerceIn(64, 512) }
    }

    suspend fun setTemperature(tenths: Int) {
        context.aiSettingsDataStore.edit { it[Keys.TEMPERATURE_TENTHS] = tenths.coerceIn(0, 10) }
    }

    suspend fun setPromptCompressionMode(mode: String) {
        // Validate against known modes
        val validModes = PromptCompressionMode.entries.map { it.id }
        val safeMode = if (mode in validModes) mode else "auto"
        context.aiSettingsDataStore.edit { it[Keys.PROMPT_COMPRESSION_MODE] = safeMode }
    }

    suspend fun clearSettings() {
        context.aiSettingsDataStore.edit { it.clear() }
    }
}

/**
 * Progressive analysis result for streaming analysis pipeline.
 * Enables instant feedback with rule-based analysis while LLM runs in background.
 *
 * Flow:
 * 1. RuleBasedResult emitted immediately (< 10ms) - provides instant feedback
 * 2. LlmResult emitted when LLM completes (1-10s) - provides enhanced analysis
 * 3. Error emitted if something fails - includes fallback analysis when available
 */
sealed class ProgressiveAnalysisResult {
    /**
     * Quick rule-based analysis result.
     * Provides instant feedback based on pattern matching and device type profiles.
     *
     * @param analysis The rule-based analysis result
     * @param isComplete True if this is the final result (no LLM analysis coming)
     */
    data class RuleBasedResult(
        val analysis: AiAnalysisResult,
        val isComplete: Boolean = false
    ) : ProgressiveAnalysisResult()

    /**
     * Full LLM-enhanced analysis result.
     * Provides comprehensive analysis with contextual understanding.
     *
     * @param analysis The LLM-enhanced analysis result
     * @param isComplete Always true for LLM results
     */
    data class LlmResult(
        val analysis: AiAnalysisResult,
        val isComplete: Boolean = true
    ) : ProgressiveAnalysisResult()

    /**
     * Error during analysis.
     * May include a fallback analysis from rule-based system.
     *
     * @param error Error message describing what went wrong
     * @param fallbackAnalysis Optional rule-based analysis as fallback
     */
    data class Error(
        val error: String,
        val fallbackAnalysis: AiAnalysisResult?
    ) : ProgressiveAnalysisResult()
}

package com.flockyou.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
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
data class AiSettings(
    val enabled: Boolean = false,
    val modelDownloaded: Boolean = false,
    val selectedModel: String = "rule-based", // Selected model ID
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
    val maxTokens: Int = 256,
    val temperatureTenths: Int = 7, // 0.7 stored as int to avoid float precision issues
    val lastModelUpdate: Long = 0
)

/**
 * Available on-device LLM models for analysis.
 * All models run entirely on-device - no cloud connectivity required.
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
    val quantization: String = "N/A"
) {
    RULE_BASED(
        id = "rule-based",
        displayName = "Rule-Based Analysis",
        description = "Built-in analysis using curated threat intelligence. Works on all devices, no download required.",
        sizeMb = 0,
        capabilities = listOf("Device identification", "Threat assessment", "Recommendations"),
        quantization = "N/A"
    ),
    GEMINI_NANO(
        id = "gemini-nano",
        displayName = "Gemini Nano (Google AI)",
        description = "Google's official on-device model via AI Core. Requires Pixel 8/Pro or newer with NPU.",
        sizeMb = 0, // Managed by Google Play Services
        capabilities = listOf("Text generation", "Summarization", "Classification", "NPU acceleration"),
        requiresPixel8 = true,
        requiresNpu = true,
        quantization = "INT4"
    ),
    SMOLLM_135M(
        id = "smollm-135m",
        displayName = "SmolLM 135M",
        description = "Ultra-lightweight model, fast inference. Good for basic analysis on any device.",
        sizeMb = 90,
        capabilities = listOf("Basic text generation", "Classification"),
        downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM-135M-Instruct-GGUF/resolve/main/smollm-135m-instruct-q4_k_m.gguf",
        quantization = "Q4_K_M"
    ),
    SMOLLM_360M(
        id = "smollm-360m",
        displayName = "SmolLM 360M",
        description = "Compact model with improved reasoning. Good balance of speed and capability.",
        sizeMb = 230,
        capabilities = listOf("Text generation", "Reasoning", "Classification"),
        downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM-360M-Instruct-GGUF/resolve/main/smollm-360m-instruct-q4_k_m.gguf",
        quantization = "Q4_K_M"
    ),
    QWEN2_0_5B(
        id = "qwen2-0.5b",
        displayName = "Qwen2 0.5B",
        description = "Alibaba's efficient small model. Strong multilingual support.",
        sizeMb = 350,
        capabilities = listOf("Text generation", "Multilingual", "Classification"),
        downloadUrl = "https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_k_m.gguf",
        quantization = "Q4_K_M"
    ),
    PHI3_MINI(
        id = "phi3-mini",
        displayName = "Phi-3 Mini 3.8B",
        description = "Microsoft's powerful small model. Best reasoning capability but requires more RAM (~3GB).",
        sizeMb = 2200,
        capabilities = listOf("Advanced reasoning", "Code understanding", "Detailed analysis"),
        minAndroidVersion = 28,
        downloadUrl = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
        quantization = "Q4_0"
    ),
    GEMMA2_2B(
        id = "gemma2-2b",
        displayName = "Gemma 2 2B",
        description = "Google's open model. Excellent quality, moderate size (~1.5GB RAM).",
        sizeMb = 1500,
        capabilities = listOf("Text generation", "Reasoning", "Summarization"),
        minAndroidVersion = 28,
        downloadUrl = "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-q4_k_m.gguf",
        quantization = "Q4_K_M"
    ),
    TINYLLAMA_1B(
        id = "tinyllama-1b",
        displayName = "TinyLlama 1.1B",
        description = "Efficient Llama-architecture model. Good capability with moderate resource use.",
        sizeMb = 700,
        capabilities = listOf("Text generation", "Reasoning", "Analysis"),
        downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
        quantization = "Q4_K_M"
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
    }
}

/**
 * Result of an AI analysis operation.
 * All analysis is performed locally on the device.
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
    val structuredData: StructuredAnalysis? = null
)

/**
 * Structured analysis data for programmatic consumption.
 * Allows other parts of the app to use analysis results directly.
 */
data class StructuredAnalysis(
    val deviceCategory: String,
    val surveillanceType: String,
    val dataCollectionTypes: List<String>,
    val riskScore: Int, // 0-100
    val riskFactors: List<String>,
    val mitigationActions: List<MitigationAction>,
    val contextualInsights: ContextualInsights? = null
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
        val MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        val TEMPERATURE_TENTHS = intPreferencesKey("ai_temperature_tenths")
        val LAST_MODEL_UPDATE = longPreferencesKey("ai_last_model_update")
    }

    val settings: Flow<AiSettings> = context.aiSettingsDataStore.data.map { prefs ->
        AiSettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            modelDownloaded = prefs[Keys.MODEL_DOWNLOADED] ?: false,
            selectedModel = prefs[Keys.SELECTED_MODEL] ?: "rule-based",
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
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 256,
            temperatureTenths = prefs[Keys.TEMPERATURE_TENTHS] ?: 7,
            lastModelUpdate = prefs[Keys.LAST_MODEL_UPDATE] ?: 0
        )
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

    suspend fun setMaxTokens(tokens: Int) {
        context.aiSettingsDataStore.edit { it[Keys.MAX_TOKENS] = tokens.coerceIn(64, 512) }
    }

    suspend fun setTemperature(tenths: Int) {
        context.aiSettingsDataStore.edit { it[Keys.TEMPERATURE_TENTHS] = tenths.coerceIn(0, 10) }
    }

    suspend fun clearSettings() {
        context.aiSettingsDataStore.edit { it.clear() }
    }
}

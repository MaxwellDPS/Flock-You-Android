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
    val modelName: String = "gemini-nano",
    val modelSizeMb: Long = 0,
    val useGpuAcceleration: Boolean = true,
    val analyzeDetections: Boolean = true,
    val generateThreatAssessments: Boolean = true,
    val identifyUnknownDevices: Boolean = true,
    val autoAnalyzeNewDetections: Boolean = false,
    val maxTokens: Int = 256,
    val temperatureTenths: Int = 7, // 0.7 stored as int to avoid float precision issues
    val lastModelUpdate: Long = 0
)

/**
 * Available on-device LLM models for analysis.
 * All models run entirely on-device - no cloud connectivity required.
 */
enum class AiModel(
    val displayName: String,
    val description: String,
    val sizeMb: Long,
    val capabilities: List<String>,
    val minAndroidVersion: Int = 26, // Android 8.0
    val requiresPixel8: Boolean = false
) {
    GEMINI_NANO(
        displayName = "Gemini Nano",
        description = "Google's on-device model. Requires Pixel 8+ or compatible device.",
        sizeMb = 300,
        capabilities = listOf("Text generation", "Summarization", "Classification"),
        requiresPixel8 = true
    ),
    RULE_BASED(
        displayName = "Rule-Based Analysis",
        description = "Built-in analysis using curated threat intelligence. Works on all devices.",
        sizeMb = 0,
        capabilities = listOf("Device identification", "Threat assessment", "Recommendations")
    )
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
    val wasOnDevice: Boolean = true // Always true - no cloud fallback
)

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
        val MODEL_NAME = stringPreferencesKey("ai_model_name")
        val MODEL_SIZE_MB = longPreferencesKey("ai_model_size_mb")
        val USE_GPU = booleanPreferencesKey("ai_use_gpu")
        val ANALYZE_DETECTIONS = booleanPreferencesKey("ai_analyze_detections")
        val GENERATE_THREAT_ASSESSMENTS = booleanPreferencesKey("ai_generate_threat_assessments")
        val IDENTIFY_UNKNOWN = booleanPreferencesKey("ai_identify_unknown")
        val AUTO_ANALYZE = booleanPreferencesKey("ai_auto_analyze")
        val MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        val TEMPERATURE_TENTHS = intPreferencesKey("ai_temperature_tenths")
        val LAST_MODEL_UPDATE = longPreferencesKey("ai_last_model_update")
    }

    val settings: Flow<AiSettings> = context.aiSettingsDataStore.data.map { prefs ->
        AiSettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            modelDownloaded = prefs[Keys.MODEL_DOWNLOADED] ?: false,
            modelName = prefs[Keys.MODEL_NAME] ?: "gemini-nano",
            modelSizeMb = prefs[Keys.MODEL_SIZE_MB] ?: 0,
            useGpuAcceleration = prefs[Keys.USE_GPU] ?: true,
            analyzeDetections = prefs[Keys.ANALYZE_DETECTIONS] ?: true,
            generateThreatAssessments = prefs[Keys.GENERATE_THREAT_ASSESSMENTS] ?: true,
            identifyUnknownDevices = prefs[Keys.IDENTIFY_UNKNOWN] ?: true,
            autoAnalyzeNewDetections = prefs[Keys.AUTO_ANALYZE] ?: false,
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

    suspend fun setModelName(name: String) {
        context.aiSettingsDataStore.edit { it[Keys.MODEL_NAME] = name }
    }

    suspend fun setUseGpuAcceleration(useGpu: Boolean) {
        context.aiSettingsDataStore.edit { it[Keys.USE_GPU] = useGpu }
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

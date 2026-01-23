package com.flockyou.ai

import android.util.Log
import com.flockyou.data.AiModel
import com.flockyou.data.AiSettings
import com.flockyou.data.PromptCompressionMode
import com.flockyou.data.model.Detection
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomalyAnalysis
import com.flockyou.service.CellularMonitor.CellularAnomalyAnalysis
import com.flockyou.service.RogueWifiMonitor.FollowingNetworkAnalysis
import com.flockyou.service.UltrasonicDetector.BeaconAnalysis

/**
 * Intelligent prompt selector that chooses between verbose and compact prompts
 * based on model capability, user settings, and device constraints.
 *
 * Selection logic:
 * - AUTO mode: Uses compact prompts for smaller models (<1GB) or when maxTokens < 512
 * - VERBOSE mode: Always uses full PromptTemplates
 * - COMPACT mode: Always uses CompactPromptTemplates
 *
 * Compact prompts reduce token count by 50-70% for faster inference on resource-constrained devices.
 */
object PromptSelector {

    private const val TAG = "PromptSelector"

    // Model size threshold for auto-switching to compact prompts (in MB)
    private const val COMPACT_MODEL_SIZE_THRESHOLD_MB = 1000L

    // Token threshold for auto-switching to compact prompts
    private const val COMPACT_TOKEN_THRESHOLD = 512

    /**
     * Determine whether to use compact prompts based on settings and model.
     */
    fun shouldUseCompactPrompts(settings: AiSettings, model: AiModel): Boolean {
        val mode = PromptCompressionMode.entries.find { it.id == settings.promptCompressionMode }
            ?: PromptCompressionMode.AUTO

        return when (mode) {
            PromptCompressionMode.VERBOSE -> {
                Log.d(TAG, "Using verbose prompts (user preference)")
                false
            }
            PromptCompressionMode.COMPACT -> {
                Log.d(TAG, "Using compact prompts (user preference)")
                true
            }
            PromptCompressionMode.AUTO -> {
                // Auto-select based on model size and token settings
                val useCompact = when {
                    // Rule-based doesn't use prompts
                    model == AiModel.RULE_BASED -> false

                    // Small models benefit from compact prompts
                    model.sizeMb in 1 until COMPACT_MODEL_SIZE_THRESHOLD_MB -> true

                    // Low token budget suggests resource constraints
                    settings.maxTokens < COMPACT_TOKEN_THRESHOLD -> true

                    // Gemini Nano is powerful enough for verbose prompts
                    model == AiModel.GEMINI_NANO -> false

                    // Large models (>1GB) can handle verbose prompts
                    model.sizeMb >= COMPACT_MODEL_SIZE_THRESHOLD_MB -> false

                    // Default to verbose for best quality
                    else -> false
                }

                Log.d(TAG, "Auto-selected ${if (useCompact) "compact" else "verbose"} prompts " +
                    "(model=${model.displayName}, size=${model.sizeMb}MB, maxTokens=${settings.maxTokens})")
                useCompact
            }
        }
    }

    /**
     * Get the appropriate analysis prompt for a detection.
     */
    fun getAnalysisPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData?,
        settings: AiSettings,
        model: AiModel
    ): String {
        return if (shouldUseCompactPrompts(settings, model)) {
            CompactPromptTemplates.compactAnalysisPrompt(detection, enrichedData)
        } else {
            PromptTemplates.buildStructuredOutputPrompt(detection, enrichedData)
        }
    }

    /**
     * Get the appropriate cellular/IMSI catcher analysis prompt.
     */
    fun getCellularPrompt(
        detection: Detection,
        analysis: CellularAnomalyAnalysis,
        settings: AiSettings,
        model: AiModel
    ): String {
        return if (shouldUseCompactPrompts(settings, model)) {
            CompactPromptTemplates.compactCellularPrompt(detection, analysis)
        } else {
            PromptTemplates.buildCellularEnrichedPrompt(detection, analysis)
        }
    }

    /**
     * Get the appropriate GNSS spoofing/jamming analysis prompt.
     */
    fun getGnssPrompt(
        detection: Detection,
        analysis: GnssAnomalyAnalysis,
        settings: AiSettings,
        model: AiModel
    ): String {
        return if (shouldUseCompactPrompts(settings, model)) {
            CompactPromptTemplates.compactGnssPrompt(detection, analysis)
        } else {
            PromptTemplates.buildGnssEnrichedPrompt(detection, analysis)
        }
    }

    /**
     * Get the appropriate ultrasonic beacon analysis prompt.
     */
    fun getUltrasonicPrompt(
        detection: Detection,
        analysis: BeaconAnalysis,
        settings: AiSettings,
        model: AiModel
    ): String {
        return if (shouldUseCompactPrompts(settings, model)) {
            CompactPromptTemplates.compactUltrasonicPrompt(detection, analysis)
        } else {
            PromptTemplates.buildUltrasonicEnrichedPrompt(detection, analysis)
        }
    }

    /**
     * Get the appropriate WiFi following network analysis prompt.
     */
    fun getWifiFollowingPrompt(
        detection: Detection,
        analysis: FollowingNetworkAnalysis,
        settings: AiSettings,
        model: AiModel
    ): String {
        return if (shouldUseCompactPrompts(settings, model)) {
            CompactPromptTemplates.compactWifiFollowingPrompt(detection, analysis)
        } else {
            PromptTemplates.buildWifiFollowingEnrichedPrompt(detection, analysis)
        }
    }

    /**
     * Get the appropriate pattern recognition prompt.
     */
    fun getPatternPrompt(
        detections: List<Detection>,
        timeWindowDescription: String,
        settings: AiSettings,
        model: AiModel
    ): String {
        return if (shouldUseCompactPrompts(settings, model)) {
            CompactPromptTemplates.compactPatternPrompt(detections, timeWindowDescription)
        } else {
            PromptTemplates.buildPatternRecognitionPrompt(detections, timeWindowDescription)
        }
    }

    /**
     * Get the appropriate summary prompt.
     */
    fun getSummaryPrompt(
        detections: List<Detection>,
        periodDescription: String,
        previousPeriodComparison: String?,
        settings: AiSettings,
        model: AiModel
    ): String {
        return if (shouldUseCompactPrompts(settings, model)) {
            CompactPromptTemplates.compactSummaryPrompt(detections, periodDescription, previousPeriodComparison)
        } else {
            PromptTemplates.buildSummaryPrompt(detections, periodDescription, previousPeriodComparison)
        }
    }

    /**
     * Get the appropriate user-friendly explanation prompt.
     */
    fun getUserExplanationPrompt(
        detection: Detection,
        level: PromptTemplates.ExplanationLevel,
        settings: AiSettings,
        model: AiModel
    ): String {
        return if (shouldUseCompactPrompts(settings, model)) {
            CompactPromptTemplates.compactUserExplanationPrompt(detection, level)
        } else {
            PromptTemplates.buildUserFriendlyPrompt(detection, null, level)
        }
    }

    /**
     * Get the appropriate chain-of-thought prompt.
     * Note: For chain-of-thought reasoning, verbose prompts are strongly recommended.
     * Compact prompts may not provide the guidance needed for step-by-step reasoning.
     */
    fun getChainOfThoughtPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData?,
        settings: AiSettings,
        model: AiModel
    ): String {
        // For chain-of-thought, we prefer verbose unless user explicitly requested compact
        val mode = PromptCompressionMode.entries.find { it.id == settings.promptCompressionMode }
            ?: PromptCompressionMode.AUTO

        return if (mode == PromptCompressionMode.COMPACT) {
            // User explicitly wants compact - use regular analysis prompt
            CompactPromptTemplates.compactAnalysisPrompt(detection, enrichedData)
        } else {
            // Use verbose chain-of-thought for better reasoning
            PromptTemplates.buildChainOfThoughtPrompt(detection, enrichedData)
        }
    }

    /**
     * Get the appropriate few-shot prompt.
     * Note: Few-shot examples are more effective with verbose prompts.
     */
    fun getFewShotPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData?,
        settings: AiSettings,
        model: AiModel
    ): String {
        val mode = PromptCompressionMode.entries.find { it.id == settings.promptCompressionMode }
            ?: PromptCompressionMode.AUTO

        return if (mode == PromptCompressionMode.COMPACT) {
            // User explicitly wants compact - use regular analysis prompt
            CompactPromptTemplates.compactAnalysisPrompt(detection, enrichedData)
        } else {
            // Use verbose few-shot for better output format consistency
            PromptTemplates.buildFewShotPrompt(detection, enrichedData)
        }
    }

    /**
     * Estimate token savings from using compact prompts.
     * Returns a rough percentage of tokens saved.
     */
    fun estimateTokenSavings(verbosePrompt: String, compactPrompt: String): Int {
        // Rough estimate: ~4 characters per token on average
        val verboseTokens = verbosePrompt.length / 4
        val compactTokens = compactPrompt.length / 4

        if (verboseTokens <= 0) return 0

        val savings = ((verboseTokens - compactTokens) * 100) / verboseTokens
        return savings.coerceIn(0, 100)
    }
}

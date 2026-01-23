package com.flockyou.ai

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.feedbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "analysis_feedback"
)

/**
 * User actions on detections that provide implicit and explicit feedback
 * for improving AI analysis accuracy.
 */
enum class UserAction {
    /** User dismissed the alert without investigating */
    DISMISSED,

    /** User tapped to view detection details */
    INVESTIGATED,

    /** User explicitly marked as false positive (safe) */
    MARKED_FALSE_POSITIVE,

    /** User explicitly confirmed as a real threat */
    MARKED_THREAT,

    /** User shared/reported the detection */
    REPORTED,

    /** No action taken before timeout (e.g., notification dismissed automatically) */
    IGNORED,

    /** User provided positive feedback on analysis accuracy */
    ANALYSIS_HELPFUL,

    /** User provided negative feedback on analysis accuracy */
    ANALYSIS_NOT_HELPFUL
}

/**
 * Aggregated feedback statistics for a specific device type.
 * Used to adjust confidence scores based on historical user behavior.
 */
data class FeedbackStats(
    val deviceType: String, // DeviceType.name for serialization
    val totalDetections: Int = 0, // Total number of detections for this device type
    val dismissCount: Int = 0,
    val investigateCount: Int = 0,
    val reportCount: Int = 0,
    val falsePositiveCount: Int = 0,
    val confirmedThreatCount: Int = 0,
    val helpfulCount: Int = 0,
    val notHelpfulCount: Int = 0,
    val ignoredCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /**
     * Calculate the dismiss rate: how often users dismiss without investigating.
     * High dismiss rate suggests over-alerting for this device type.
     */
    val dismissRate: Float
        get() {
            val total = dismissCount + investigateCount + 1 // +1 to avoid division by zero
            return dismissCount.toFloat() / total
        }

    /**
     * Calculate the false positive rate based on explicit user markings.
     * High FP rate suggests this device type is often misclassified.
     */
    val falsePositiveRate: Float
        get() {
            val total = falsePositiveCount + confirmedThreatCount + 1
            return falsePositiveCount.toFloat() / total
        }

    /**
     * Calculate analysis accuracy based on user feedback.
     * Used to weight this device type's analysis confidence.
     */
    val analysisAccuracy: Float
        get() {
            val total = helpfulCount + notHelpfulCount
            return if (total == 0) 0.5f else helpfulCount.toFloat() / total
        }

    /**
     * Total number of interactions for this device type.
     */
    val totalInteractions: Int
        get() = dismissCount + investigateCount + reportCount +
                falsePositiveCount + confirmedThreatCount + ignoredCount

    /**
     * Check if we have enough data for reliable statistics.
     * Need at least 5 interactions before using feedback for adjustment.
     */
    val hasReliableData: Boolean
        get() = totalInteractions >= 5
}

/**
 * Individual feedback event record for detailed tracking.
 * Stores the last 30 days of feedback for trend analysis.
 */
data class FeedbackEvent(
    val detectionId: String,
    val deviceType: String,
    val action: UserAction,
    val analysisConfidence: Float?,
    val analysisModelUsed: String?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Tracks and aggregates user feedback on AI analysis to improve accuracy over time.
 *
 * Key features:
 * - Records explicit feedback (marking as threat/FP, analysis ratings)
 * - Records implicit feedback (investigating vs dismissing, time spent)
 * - Aggregates stats per device type
 * - Calculates confidence adjustments based on historical accuracy
 * - Persists data in DataStore with 30-day retention
 *
 * Integration points:
 * - Call [recordFeedback] when user takes action on a detection
 * - Call [getConfidenceAdjustment] in DetectionAnalyzer to adjust scores
 * - Observe [getFeedbackStats] to show accuracy metrics in settings
 */
@Singleton
class AnalysisFeedbackTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AnalysisFeedbackTracker"

        // DataStore keys
        private val STATS_KEY = stringPreferencesKey("feedback_stats_json")
        private val EVENTS_KEY = stringPreferencesKey("feedback_events_json")
        private val LAST_CLEANUP_KEY = longPreferencesKey("last_cleanup_timestamp")

        // Retention policy
        private val EVENT_RETENTION_DAYS = 30L
        private val CLEANUP_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

        // Confidence adjustment parameters
        private const val MAX_DISMISS_RATE_PENALTY = 0.3f // Max 30% reduction for high dismiss rate
        private const val MAX_FP_RATE_PENALTY = 0.4f // Max 40% reduction for high FP rate
        private const val ACCURACY_BOOST_MAX = 0.2f // Max 20% boost for high accuracy
        private const val MIN_CONFIDENCE_FLOOR = 0.1f // Never reduce below 10% confidence
    }

    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .create()

    private val dataStore = context.feedbackDataStore

    /**
     * Record user feedback when they take an action on a detection.
     *
     * @param detection The detection the user interacted with
     * @param action The type of action taken
     * @param analysis Optional AI analysis result if one was shown
     */
    suspend fun recordFeedback(
        detection: Detection,
        action: UserAction,
        analysis: AiAnalysisResult? = null
    ) {
        try {
            val deviceType = detection.deviceType.name

            // Create the feedback event
            val event = FeedbackEvent(
                detectionId = detection.id,
                deviceType = deviceType,
                action = action,
                analysisConfidence = analysis?.confidence,
                analysisModelUsed = analysis?.modelUsed
            )

            dataStore.edit { prefs ->
                // Update aggregated stats
                val currentStats = loadStatsMap(prefs)
                val deviceStats = currentStats[deviceType] ?: FeedbackStats(deviceType = deviceType)
                val updatedStats = updateStats(deviceStats, action)
                currentStats[deviceType] = updatedStats
                prefs[STATS_KEY] = gson.toJson(currentStats)

                // Append event to history
                val events = loadEvents(prefs).toMutableList()
                events.add(event)
                prefs[EVENTS_KEY] = gson.toJson(events)
            }

            Log.d(TAG, "Recorded feedback: $action for ${detection.deviceType} (${detection.id})")

            // Periodic cleanup of old events
            maybeCleanupOldEvents()
        } catch (e: Exception) {
            Log.e(TAG, "Error recording feedback", e)
        }
    }

    /**
     * Get aggregated feedback statistics for a specific device type.
     * Returns a Flow that updates when stats change.
     */
    fun getFeedbackStats(deviceType: DeviceType): Flow<FeedbackStats> {
        return dataStore.data.map { prefs ->
            val statsMap = loadStatsMap(prefs)
            statsMap[deviceType.name] ?: FeedbackStats(deviceType = deviceType.name)
        }
    }

    /**
     * Get all feedback statistics as a map by device type.
     */
    fun getAllFeedbackStats(): Flow<Map<String, FeedbackStats>> {
        return dataStore.data.map { prefs ->
            loadStatsMap(prefs)
        }
    }

    /**
     * Calculate confidence adjustment factor based on historical feedback.
     *
     * Returns a multiplier (0.0 to ~1.2) to apply to analysis confidence:
     * - < 1.0: Reduce confidence (high dismiss/FP rate for this device type)
     * - 1.0: No adjustment (insufficient data or neutral feedback)
     * - > 1.0: Boost confidence (historically accurate for this device type)
     *
     * @param deviceType The device type to get adjustment for
     * @return Confidence multiplier to apply
     */
    suspend fun getConfidenceAdjustment(deviceType: DeviceType): Float {
        return try {
            val stats = getFeedbackStats(deviceType).first()
            calculateAdjustment(stats)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting confidence adjustment", e)
            1.0f // No adjustment on error
        }
    }

    /**
     * Apply feedback-based adjustments to an analysis result.
     * Creates a new result with adjusted confidence and added context.
     *
     * @param analysis Original analysis result
     * @param deviceType Device type for looking up feedback
     * @return Adjusted analysis result
     */
    suspend fun adjustAnalysisWithFeedback(
        analysis: AiAnalysisResult,
        deviceType: DeviceType
    ): AiAnalysisResult {
        val stats = getFeedbackStats(deviceType).first()

        // Don't adjust if we don't have enough data
        if (!stats.hasReliableData) {
            return analysis
        }

        val adjustment = calculateAdjustment(stats)
        val adjustedConfidence = (analysis.confidence * adjustment)
            .coerceIn(MIN_CONFIDENCE_FLOOR, 1.0f)

        // Add feedback context to technical details
        val feedbackContext = buildFeedbackContext(stats, adjustment)
        val enhancedTechnicalDetails = buildString {
            analysis.technicalDetails?.let {
                append(it)
                append("\n\n")
            }
            append(feedbackContext)
        }

        return analysis.copy(
            confidence = adjustedConfidence,
            technicalDetails = enhancedTechnicalDetails.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Get summary statistics for display in settings/about screen.
     */
    suspend fun getOverallAccuracyStats(): OverallAccuracyStats {
        val allStats = getAllFeedbackStats().first()

        var totalInteractions = 0
        var totalFp = 0
        var totalThreats = 0
        var totalHelpful = 0
        var totalNotHelpful = 0
        var deviceTypesWithData = 0

        allStats.values.forEach { stats ->
            totalInteractions += stats.totalInteractions
            totalFp += stats.falsePositiveCount
            totalThreats += stats.confirmedThreatCount
            totalHelpful += stats.helpfulCount
            totalNotHelpful += stats.notHelpfulCount
            if (stats.hasReliableData) deviceTypesWithData++
        }

        val overallAccuracy = if (totalHelpful + totalNotHelpful > 0) {
            totalHelpful.toFloat() / (totalHelpful + totalNotHelpful)
        } else {
            null
        }

        return OverallAccuracyStats(
            totalInteractions = totalInteractions,
            totalFalsePositives = totalFp,
            totalConfirmedThreats = totalThreats,
            totalHelpfulFeedback = totalHelpful,
            totalNotHelpfulFeedback = totalNotHelpful,
            overallAccuracy = overallAccuracy,
            deviceTypesWithReliableData = deviceTypesWithData,
            totalDeviceTypesTracked = allStats.size
        )
    }

    /**
     * Clear all feedback data. Use with caution.
     */
    suspend fun clearAllFeedback() {
        dataStore.edit { prefs ->
            prefs.remove(STATS_KEY)
            prefs.remove(EVENTS_KEY)
            prefs.remove(LAST_CLEANUP_KEY)
        }
        Log.i(TAG, "Cleared all feedback data")
    }

    // ==================== Private helpers ====================

    private fun loadStatsMap(prefs: Preferences): MutableMap<String, FeedbackStats> {
        val statsJson = prefs[STATS_KEY] ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, FeedbackStats>>() {}.type
            gson.fromJson(statsJson, type) ?: mutableMapOf()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stats, resetting", e)
            mutableMapOf()
        }
    }

    private fun loadEvents(prefs: Preferences): List<FeedbackEvent> {
        val eventsJson = prefs[EVENTS_KEY] ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FeedbackEvent>>() {}.type
            gson.fromJson(eventsJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse events, resetting", e)
            emptyList()
        }
    }

    private fun updateStats(stats: FeedbackStats, action: UserAction): FeedbackStats {
        // Increment totalDetections for all actions (represents user interactions with detections)
        val baseStats = stats.copy(
            totalDetections = stats.totalDetections + 1,
            lastUpdated = System.currentTimeMillis()
        )

        return when (action) {
            UserAction.DISMISSED -> baseStats.copy(
                dismissCount = stats.dismissCount + 1
            )
            UserAction.INVESTIGATED -> baseStats.copy(
                investigateCount = stats.investigateCount + 1
            )
            UserAction.MARKED_FALSE_POSITIVE -> baseStats.copy(
                falsePositiveCount = stats.falsePositiveCount + 1
            )
            UserAction.MARKED_THREAT -> baseStats.copy(
                confirmedThreatCount = stats.confirmedThreatCount + 1
            )
            UserAction.REPORTED -> baseStats.copy(
                reportCount = stats.reportCount + 1
            )
            UserAction.IGNORED -> baseStats.copy(
                ignoredCount = stats.ignoredCount + 1
            )
            UserAction.ANALYSIS_HELPFUL -> baseStats.copy(
                helpfulCount = stats.helpfulCount + 1
            )
            UserAction.ANALYSIS_NOT_HELPFUL -> baseStats.copy(
                notHelpfulCount = stats.notHelpfulCount + 1
            )
        }
    }

    private fun calculateAdjustment(stats: FeedbackStats): Float {
        if (!stats.hasReliableData) {
            return 1.0f // No adjustment without sufficient data
        }

        var adjustment = 1.0f

        // Penalty for high dismiss rate (users often ignore this type)
        // This suggests we may be over-alerting
        adjustment -= stats.dismissRate * MAX_DISMISS_RATE_PENALTY

        // Penalty for high false positive rate (users often mark as FP)
        // This suggests threat level may be too high
        adjustment -= stats.falsePositiveRate * MAX_FP_RATE_PENALTY

        // Boost for historically accurate analysis
        // If users consistently find analysis helpful, increase confidence
        if (stats.helpfulCount + stats.notHelpfulCount >= 3) {
            val accuracyBoost = (stats.analysisAccuracy - 0.5f) * 2 * ACCURACY_BOOST_MAX
            adjustment += accuracyBoost
        }

        // Boost for confirmed threats (validates our detection)
        if (stats.confirmedThreatCount > 0 && stats.totalInteractions > 0) {
            val threatRate = stats.confirmedThreatCount.toFloat() / stats.totalInteractions
            adjustment += threatRate * 0.1f // Small boost for validated threats
        }

        return adjustment.coerceIn(0.5f, 1.2f)
    }

    private fun buildFeedbackContext(stats: FeedbackStats, adjustment: Float): String {
        return buildString {
            append("--- Feedback Learning ---\n")
            append("Based on ${stats.totalInteractions} interactions with ${stats.deviceType}:\n")

            if (stats.falsePositiveCount > 0 || stats.confirmedThreatCount > 0) {
                append("- User verdicts: ${stats.confirmedThreatCount} confirmed threats, ")
                append("${stats.falsePositiveCount} false positives\n")
            }

            if (stats.dismissRate > 0.5f) {
                append("- Note: High dismiss rate (${(stats.dismissRate * 100).toInt()}%) - ")
                append("may be over-alerting for this device type\n")
            }

            when {
                adjustment < 0.8f -> append("- Confidence reduced due to high FP rate history\n")
                adjustment > 1.1f -> append("- Confidence boosted due to historically accurate analysis\n")
            }
        }
    }

    private suspend fun maybeCleanupOldEvents() {
        val prefs = dataStore.data.first()
        val lastCleanup = prefs[LAST_CLEANUP_KEY] ?: 0L
        val now = System.currentTimeMillis()

        if (now - lastCleanup < CLEANUP_INTERVAL_MS) {
            return // Don't cleanup too frequently
        }

        dataStore.edit { editPrefs ->
            val cutoff = now - TimeUnit.DAYS.toMillis(EVENT_RETENTION_DAYS)
            val events = loadEvents(editPrefs)
            val recentEvents = events.filter { it.timestamp >= cutoff }

            if (recentEvents.size < events.size) {
                editPrefs[EVENTS_KEY] = gson.toJson(recentEvents)
                Log.d(TAG, "Cleaned up ${events.size - recentEvents.size} old feedback events")
            }

            editPrefs[LAST_CLEANUP_KEY] = now
        }
    }
}

/**
 * Overall accuracy statistics for display in settings/about.
 */
data class OverallAccuracyStats(
    val totalInteractions: Int,
    val totalFalsePositives: Int,
    val totalConfirmedThreats: Int,
    val totalHelpfulFeedback: Int,
    val totalNotHelpfulFeedback: Int,
    val overallAccuracy: Float?, // null if no feedback yet
    val deviceTypesWithReliableData: Int,
    val totalDeviceTypesTracked: Int
)

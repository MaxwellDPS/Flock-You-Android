package com.flockyou.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.flockyou.BuildConfig
import com.flockyou.ai.DetectionAnalyzer
import com.flockyou.ai.FalsePositiveAnalyzer
import com.flockyou.ai.FalsePositiveResult
import com.flockyou.ai.LlmEngine
import com.flockyou.ai.LlmEngineManager
import com.flockyou.data.AiSettingsRepository
import com.flockyou.data.model.Detection
import com.flockyou.data.repository.DetectionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background worker that pre-processes detections with LLM analysis during idle time.
 *
 * This worker runs periodically to analyze detections that haven't been processed yet,
 * computing false positive scores and caching results for immediate display when the
 * user views detections.
 *
 * Benefits:
 * - Detections are pre-analyzed before user views them
 * - LLM analysis happens during device idle time, saving battery
 * - Results are cached for instant display
 * - Reduces UI latency when viewing detection details
 */
@HiltWorker
class BackgroundAnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val detectionRepository: DetectionRepository,
    private val detectionAnalyzer: DetectionAnalyzer,
    private val falsePositiveAnalyzer: FalsePositiveAnalyzer,
    private val llmEngineManager: LlmEngineManager,
    private val aiSettingsRepository: AiSettingsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "BackgroundAnalysisWorker"
        const val WORK_NAME = "background_analysis"

        // Batch processing limits
        const val DEFAULT_BATCH_SIZE = 15
        const val MIN_BATCH_SIZE = 5
        const val MAX_BATCH_SIZE = 25

        // Input data keys
        const val KEY_BATCH_SIZE = "batch_size"
        const val KEY_FORCE_REANALYZE = "force_reanalyze"

        // Analysis age threshold - re-analyze detections older than this
        private const val ANALYSIS_STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours

        // Repeat interval
        private const val REPEAT_INTERVAL_MINUTES = 45L
        private const val FLEX_INTERVAL_MINUTES = 15L

        /**
         * Schedule periodic background analysis.
         * Runs every 45 minutes with a 15-minute flex window, preferring when device is idle.
         */
        fun schedule(context: Context, batchSize: Int = DEFAULT_BATCH_SIZE) {
            val actualBatchSize = batchSize.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)

            val inputData = Data.Builder()
                .putInt(KEY_BATCH_SIZE, actualBatchSize)
                .build()

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                // Prefer idle time but don't require it - analysis is lightweight
                .setRequiresDeviceIdle(false)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<BackgroundAnalysisWorker>(
                REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES,
                FLEX_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Scheduled periodic background analysis (batch: $actualBatchSize, interval: ${REPEAT_INTERVAL_MINUTES}min)")
            }
        }

        /**
         * Schedule periodic background analysis that only runs when device is idle.
         * More battery-efficient but may run less frequently.
         */
        fun scheduleIdleOnly(context: Context, batchSize: Int = DEFAULT_BATCH_SIZE) {
            val actualBatchSize = batchSize.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)

            val inputData = Data.Builder()
                .putInt(KEY_BATCH_SIZE, actualBatchSize)
                .build()

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<BackgroundAnalysisWorker>(
                60, TimeUnit.MINUTES,  // Longer interval for idle-only
                30, TimeUnit.MINUTES   // Wider flex window
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Scheduled idle-only background analysis (batch: $actualBatchSize)")
            }
        }

        /**
         * Trigger immediate analysis run.
         */
        fun triggerImmediate(
            context: Context,
            batchSize: Int = DEFAULT_BATCH_SIZE,
            forceReanalyze: Boolean = false
        ): java.util.UUID {
            val actualBatchSize = batchSize.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)

            val inputData = Data.Builder()
                .putInt(KEY_BATCH_SIZE, actualBatchSize)
                .putBoolean(KEY_FORCE_REANALYZE, forceReanalyze)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<BackgroundAnalysisWorker>()
                .setInputData(inputData)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Triggered immediate background analysis (batch: $actualBatchSize, force: $forceReanalyze)")
            }
            return workRequest.id
        }

        /**
         * Cancel background analysis work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork("${WORK_NAME}_immediate")
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Cancelled background analysis work")
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val batchSize = inputData.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE)
        val forceReanalyze = inputData.getBoolean(KEY_FORCE_REANALYZE, false)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Starting background analysis (batch: $batchSize, force: $forceReanalyze)")
        }

        try {
            // Check if AI analysis is enabled
            val aiSettings = aiSettingsRepository.settings.first()
            if (!aiSettings.enabled) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "AI analysis is disabled, skipping background analysis")
                }
                return@withContext Result.success()
            }

            // Check if FP filtering is enabled
            if (!aiSettings.enableFalsePositiveFiltering) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "False positive filtering is disabled, skipping background analysis")
                }
                return@withContext Result.success()
            }

            // Get all detections
            val allDetections = detectionRepository.getAllDetectionsSnapshot()

            if (allDetections.isEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "No detections to analyze")
                }
                return@withContext Result.success()
            }

            // Select detections that need analysis
            val detectionsToAnalyze = selectDetectionsForAnalysis(
                allDetections,
                batchSize,
                forceReanalyze
            )

            if (detectionsToAnalyze.isEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "All detections already analyzed")
                }
                return@withContext Result.success()
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Analyzing ${detectionsToAnalyze.size} detections")
            }

            // Analyze detections and cache results
            val results = analyzeDetections(detectionsToAnalyze)

            if (BuildConfig.DEBUG) {
                val successCount = results.count { it.value != null }
                Log.d(TAG, "Background analysis complete: $successCount/${detectionsToAnalyze.size} successful")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background analysis failed", e)

            // Retry on transient failures
            if (isTransientFailure(e)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Will retry due to transient failure")
                }
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Select detections that need analysis.
     * Prioritizes:
     * 1. Detections with higher threat levels
     * 2. More recently seen detections
     * 3. Detections that haven't been analyzed
     */
    private fun selectDetectionsForAnalysis(
        allDetections: List<Detection>,
        batchSize: Int,
        forceReanalyze: Boolean
    ): List<Detection> {
        val now = System.currentTimeMillis()
        val staleThreshold = now - ANALYSIS_STALE_THRESHOLD_MS

        // For now, we use a simple heuristic based on when detections were last seen.
        // In a future enhancement, we could add an "analyzedAt" field to Detection
        // to track when each detection was last analyzed by the background worker.
        //
        // Current approach: analyze detections sorted by threat level and recency,
        // limiting to batch size. The FalsePositiveAnalyzer caches results internally.

        return if (forceReanalyze) {
            // Force re-analyze: take top detections by threat and recency
            allDetections
                .sortedWith(
                    compareByDescending<Detection> { it.threatLevel.ordinal }
                        .thenByDescending { it.lastSeenTimestamp }
                )
                .take(batchSize)
        } else {
            // Normal mode: prioritize active detections first
            allDetections
                .filter { it.isActive }
                .sortedWith(
                    compareByDescending<Detection> { it.threatLevel.ordinal }
                        .thenByDescending { it.lastSeenTimestamp }
                )
                .take(batchSize)
                .ifEmpty {
                    // If no active detections, fall back to all detections
                    allDetections
                        .sortedWith(
                            compareByDescending<Detection> { it.threatLevel.ordinal }
                                .thenByDescending { it.lastSeenTimestamp }
                        )
                        .take(batchSize)
                }
        }
    }

    /**
     * Analyze detections using the FalsePositiveAnalyzer.
     * Results are automatically cached by the analyzer.
     */
    private suspend fun analyzeDetections(
        detections: List<Detection>
    ): Map<String, FalsePositiveResult?> {
        val results = mutableMapOf<String, FalsePositiveResult?>()

        // Check LLM engine status
        val activeEngine = llmEngineManager.activeEngine.value
        val isLlmReady = activeEngine != LlmEngine.RULE_BASED &&
                         llmEngineManager.isEngineReady(activeEngine)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "LLM engine status: $activeEngine, ready: $isLlmReady")
        }

        for (detection in detections) {
            try {
                // Analyze for false positive - this caches the result internally
                val fpResult = falsePositiveAnalyzer.analyzeForFalsePositive(
                    detection = detection,
                    contextInfo = null, // Background analysis doesn't have context
                    tryLazyInit = true  // Allow lazy LLM initialization
                )

                results[detection.id] = fpResult

                if (BuildConfig.DEBUG) {
                    Log.v(TAG, "Analyzed ${detection.id}: FP=${fpResult.isFalsePositive}, " +
                            "confidence=${(fpResult.confidence * 100).toInt()}%, " +
                            "method=${fpResult.analysisMethod}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to analyze detection ${detection.id}: ${e.message}")
                results[detection.id] = null
            }
        }

        return results
    }

    /**
     * Determine if an exception is a transient failure that should be retried.
     */
    private fun isTransientFailure(e: Exception): Boolean {
        return when {
            e is java.io.IOException -> true
            e.message?.contains("timeout", ignoreCase = true) == true -> true
            e.message?.contains("memory", ignoreCase = true) == true -> true
            e.message?.contains("resource", ignoreCase = true) == true -> true
            else -> false
        }
    }
}

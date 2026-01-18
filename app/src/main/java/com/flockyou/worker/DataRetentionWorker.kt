package com.flockyou.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.flockyou.BuildConfig
import com.flockyou.data.repository.DetectionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker for automatic data retention policy enforcement.
 * Deletes old detection records based on user-configured retention period.
 */
@HiltWorker
class DataRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val detectionRepository: DetectionRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "DataRetentionWorker"
        const val WORK_NAME = "data_retention_cleanup"

        // Input data keys
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_RETENTION_HOURS = "retention_hours"

        // Default retention periods (changed from 30 days to 3 days for privacy)
        const val DEFAULT_RETENTION_DAYS = 3
        const val MIN_RETENTION_DAYS = 0  // 0 means using hours instead
        const val MAX_RETENTION_DAYS = 365

        // New hour-based retention options
        const val RETENTION_HOURS_4 = 4
        const val RETENTION_HOURS_24 = 24  // 1 day
        const val RETENTION_HOURS_72 = 72  // 3 days
        const val RETENTION_HOURS_168 = 168  // 7 days
        const val RETENTION_HOURS_720 = 720  // 30 days

        /**
         * Schedule periodic data cleanup with hour-based retention.
         * Runs at appropriate intervals based on retention period.
         */
        fun schedulePeriodicCleanupHours(context: Context, retentionHours: Int) {
            val inputData = Data.Builder()
                .putInt(KEY_RETENTION_HOURS, retentionHours)
                .build()

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            // For short retention periods (< 24h), run more frequently
            val repeatInterval = when {
                retentionHours <= 4 -> 1L  // Every hour for 4-hour retention
                retentionHours <= 24 -> 4L // Every 4 hours for 1-day retention
                else -> 24L // Daily for longer retention periods
            }

            val workRequest = PeriodicWorkRequestBuilder<DataRetentionWorker>(
                repeatInterval, TimeUnit.HOURS,
                repeatInterval / 2, TimeUnit.HOURS  // Flex interval
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
                Log.d(TAG, "Scheduled periodic data cleanup (retention: $retentionHours hours, interval: $repeatInterval hours)")
            }
        }

        /**
         * Schedule periodic data cleanup.
         * Runs daily to enforce retention policy.
         */
        fun schedulePeriodicCleanup(context: Context, retentionDays: Int) {
            val actualRetentionDays = retentionDays.coerceIn(1, MAX_RETENTION_DAYS)

            val inputData = Data.Builder()
                .putInt(KEY_RETENTION_DAYS, actualRetentionDays)
                .build()

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<DataRetentionWorker>(
                1, TimeUnit.DAYS,  // Run daily
                6, TimeUnit.HOURS  // Flex interval of 6 hours
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
                Log.d(TAG, "Scheduled periodic data cleanup (retention: $actualRetentionDays days)")
            }
        }

        /**
         * Trigger immediate cleanup.
         */
        fun triggerImmediateCleanup(context: Context, retentionDays: Int): java.util.UUID {
            val actualRetentionDays = retentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

            val inputData = Data.Builder()
                .putInt(KEY_RETENTION_DAYS, actualRetentionDays)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<DataRetentionWorker>()
                .setInputData(inputData)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Triggered immediate data cleanup (retention: $actualRetentionDays days)")
            }
            return workRequest.id
        }

        /**
         * Cancel data retention work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Cancelled data retention work")
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Check for hour-based retention first (new format), then fall back to day-based
        val retentionHours = inputData.getInt(KEY_RETENTION_HOURS, -1)
        val retentionDays = inputData.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)

        val cutoffTimestamp = if (retentionHours > 0) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Starting data retention cleanup (retention: $retentionHours hours)")
            }
            System.currentTimeMillis() - (retentionHours * 60 * 60 * 1000L)
        } else {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Starting data retention cleanup (retention: $retentionDays days)")
            }
            System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        }

        try {
            // Delete old detections
            detectionRepository.deleteOldDetections(cutoffTimestamp)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Data retention cleanup complete")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Data retention cleanup failed", e)
            Result.retry()
        }
    }
}

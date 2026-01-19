package com.flockyou.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.flockyou.data.OuiSettingsRepository
import com.flockyou.data.oui.OuiDownloadResult
import com.flockyou.data.oui.OuiDownloader
import com.flockyou.data.repository.OuiRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class OuiUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val ouiDownloader: OuiDownloader,
    private val ouiRepository: OuiRepository,
    private val ouiSettingsRepository: OuiSettingsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "OuiUpdateWorker"
        const val WORK_NAME_PERIODIC = "oui_periodic_update"
        const val WORK_NAME_ONETIME = "oui_onetime_update"
        private const val BATCH_INSERT_SIZE = 1000
        private const val NETWORK_TIMEOUT_MS = 60_000L // 60 second timeout for network operations

        /**
         * Schedule periodic OUI updates.
         */
        fun schedulePeriodicUpdate(context: Context, intervalHours: Int, wifiOnly: Boolean) {
            if (intervalHours <= 0) {
                // Manual only - cancel any existing periodic work
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
                Log.d(TAG, "Cancelled periodic OUI updates (manual mode)")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<OuiUpdateWorker>(
                intervalHours.toLong(), TimeUnit.HOURS,
                1, TimeUnit.HOURS // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            Log.d(TAG, "Scheduled periodic OUI update every $intervalHours hours (wifiOnly=$wifiOnly)")
        }

        /**
         * Trigger immediate one-time update.
         */
        fun triggerImmediateUpdate(context: Context): java.util.UUID {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<OuiUpdateWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONETIME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.d(TAG, "Triggered immediate OUI update")
            return workRequest.id
        }

        /**
         * Cancel all OUI update work.
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            Log.d(TAG, "Cancelled all OUI update work")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting OUI database update")

        try {
            // Check if database is empty - if so, try to load from bundled assets first
            val hasExistingData = ouiRepository.hasData()
            if (!hasExistingData && ouiDownloader.hasBundledAssets()) {
                Log.d(TAG, "Database empty, loading bundled OUI data first")
                loadBundledData()
            }

            // Try to download fresh data from IEEE with timeout
            val result = try {
                withTimeout(NETWORK_TIMEOUT_MS) {
                    ouiDownloader.downloadAndParse()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "OUI download timed out after ${NETWORK_TIMEOUT_MS / 1000} seconds")
                OuiDownloadResult.Error("Download timed out", e)
            }

            return@withContext when (result) {
                is OuiDownloadResult.Success -> {
                    Log.d(TAG, "Downloaded ${result.entries.size} OUI entries")

                    // Insert in batches for memory efficiency
                    result.entries.chunked(BATCH_INSERT_SIZE).forEach { batch ->
                        ouiRepository.insertEntries(batch)
                    }

                    // Record success
                    ouiSettingsRepository.recordUpdateResult(
                        success = true,
                        entryCount = result.entries.size
                    )

                    Log.d(TAG, "OUI database update complete: ${result.entries.size} entries")
                    Result.success()
                }

                is OuiDownloadResult.Error -> {
                    Log.e(TAG, "OUI network update failed: ${result.message}", result.exception)

                    // If network failed and we have no data, fall back to bundled assets
                    if (!ouiRepository.hasData() && ouiDownloader.hasBundledAssets()) {
                        Log.d(TAG, "Falling back to bundled OUI data")
                        return@withContext loadBundledData()
                    }

                    // Record failure
                    ouiSettingsRepository.recordUpdateResult(
                        success = false,
                        error = result.message
                    )

                    // Retry if it might be transient (and we already have some data)
                    if (result.exception is IOException && ouiRepository.hasData()) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OUI update exception", e)
            ouiSettingsRepository.recordUpdateResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
            return@withContext Result.failure()
        }
    }

    /**
     * Load OUI data from bundled assets.
     * Used as fallback when network download fails or for initial app startup.
     */
    private suspend fun loadBundledData(): Result {
        return when (val bundledResult = ouiDownloader.loadFromBundledAssets()) {
            is OuiDownloadResult.Success -> {
                Log.d(TAG, "Loaded ${bundledResult.entries.size} OUI entries from bundled assets")

                // Replace all with bundled data
                ouiRepository.replaceAllEntries(bundledResult.entries)

                // Record success (from bundled)
                ouiSettingsRepository.recordUpdateResult(
                    success = true,
                    entryCount = bundledResult.entries.size,
                    fromBundled = true
                )

                Log.d(TAG, "OUI database initialized from bundled assets: ${bundledResult.entries.size} entries")
                Result.success()
            }

            is OuiDownloadResult.Error -> {
                Log.e(TAG, "Failed to load bundled OUI data: ${bundledResult.message}")
                ouiSettingsRepository.recordUpdateResult(
                    success = false,
                    error = "Bundled data load failed: ${bundledResult.message}"
                )
                Result.failure()
            }
        }
    }
}

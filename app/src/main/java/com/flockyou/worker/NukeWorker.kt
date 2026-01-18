package com.flockyou.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.security.NukeManager
import com.flockyou.security.NukeTriggerSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker for executing delayed nuke operations.
 * Used by various triggers that have configurable delays before nuking.
 */
@HiltWorker
class NukeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val nukeManager: NukeManager,
    private val nukeSettingsRepository: NukeSettingsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "NukeWorker"

        // Work names for different triggers
        const val WORK_NAME_USB = "nuke_usb_trigger"
        const val WORK_NAME_SIM = "nuke_sim_trigger"
        const val WORK_NAME_NETWORK = "nuke_network_trigger"
        const val WORK_NAME_GEOFENCE = "nuke_geofence_trigger"
        const val WORK_NAME_DEAD_MAN = "nuke_dead_man_switch"
        const val WORK_NAME_REBOOT = "nuke_reboot_trigger"
        const val WORK_NAME_FAILED_AUTH = "nuke_failed_auth_trigger"

        // Input data keys
        const val KEY_TRIGGER_SOURCE = "trigger_source"

        /**
         * Schedule a delayed nuke for USB trigger.
         */
        fun scheduleUsbNuke(context: Context, delaySeconds: Int) {
            scheduleNuke(
                context = context,
                workName = WORK_NAME_USB,
                triggerSource = NukeTriggerSource.USB_CONNECTION,
                delaySeconds = delaySeconds
            )
        }

        /**
         * Schedule a delayed nuke for SIM removal trigger.
         */
        fun scheduleSimNuke(context: Context, delaySeconds: Int) {
            scheduleNuke(
                context = context,
                workName = WORK_NAME_SIM,
                triggerSource = NukeTriggerSource.SIM_REMOVAL,
                delaySeconds = delaySeconds
            )
        }

        /**
         * Schedule a delayed nuke for network isolation trigger.
         */
        fun scheduleNetworkIsolationNuke(context: Context, delayHours: Int) {
            scheduleNuke(
                context = context,
                workName = WORK_NAME_NETWORK,
                triggerSource = NukeTriggerSource.NETWORK_ISOLATION,
                delaySeconds = delayHours * 3600
            )
        }

        /**
         * Schedule a delayed nuke for geofence trigger.
         */
        fun scheduleGeofenceNuke(context: Context, delaySeconds: Int) {
            scheduleNuke(
                context = context,
                workName = WORK_NAME_GEOFENCE,
                triggerSource = NukeTriggerSource.GEOFENCE,
                delaySeconds = delaySeconds
            )
        }

        /**
         * Schedule the dead man's switch check.
         */
        fun scheduleDeadManSwitchCheck(context: Context, checkIntervalHours: Int = 1) {
            val workRequest = PeriodicWorkRequestBuilder<DeadManSwitchWorker>(
                checkIntervalHours.toLong(), TimeUnit.HOURS
            )
                .addTag(TAG)
                .addTag(WORK_NAME_DEAD_MAN)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_DEAD_MAN,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            Log.d(TAG, "Scheduled dead man's switch check every $checkIntervalHours hour(s)")
        }

        /**
         * Schedule an immediate nuke for failed auth trigger.
         */
        fun scheduleFailedAuthNuke(context: Context) {
            scheduleNuke(
                context = context,
                workName = WORK_NAME_FAILED_AUTH,
                triggerSource = NukeTriggerSource.FAILED_AUTH,
                delaySeconds = 0
            )
        }

        /**
         * Schedule an immediate nuke for rapid reboot trigger.
         */
        fun scheduleRebootNuke(context: Context) {
            scheduleNuke(
                context = context,
                workName = WORK_NAME_REBOOT,
                triggerSource = NukeTriggerSource.RAPID_REBOOT,
                delaySeconds = 0
            )
        }

        /**
         * Generic method to schedule a nuke with a specific trigger source.
         */
        private fun scheduleNuke(
            context: Context,
            workName: String,
            triggerSource: NukeTriggerSource,
            delaySeconds: Int
        ) {
            val inputData = Data.Builder()
                .putString(KEY_TRIGGER_SOURCE, triggerSource.name)
                .build()

            val workRequestBuilder = OneTimeWorkRequestBuilder<NukeWorker>()
                .setInputData(inputData)
                .addTag(TAG)
                .addTag(workName)

            if (delaySeconds > 0) {
                workRequestBuilder.setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS)
            }

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                workRequestBuilder.build()
            )

            Log.w(TAG, "Scheduled nuke from $triggerSource with ${delaySeconds}s delay")
        }

        /**
         * Cancel a specific nuke trigger.
         */
        fun cancelNuke(context: Context, workName: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName)
            Log.d(TAG, "Cancelled nuke: $workName")
        }

        /**
         * Cancel all pending nuke triggers.
         */
        fun cancelAllNukes(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            Log.d(TAG, "Cancelled all pending nukes")
        }

        /**
         * Check if a specific nuke is pending.
         */
        suspend fun isNukePending(context: Context, workName: String): Boolean {
            val workInfo = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(workName)
                .get()

            return workInfo.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val triggerSourceName = inputData.getString(KEY_TRIGGER_SOURCE)
            ?: NukeTriggerSource.MANUAL.name

        val triggerSource = try {
            NukeTriggerSource.valueOf(triggerSourceName)
        } catch (e: IllegalArgumentException) {
            NukeTriggerSource.MANUAL
        }

        Log.w(TAG, "Executing nuke from trigger: $triggerSource")

        // Verify that nuke is still enabled before executing
        val settings = nukeSettingsRepository.settings.first()
        if (!settings.nukeEnabled) {
            Log.w(TAG, "Nuke cancelled - system is disabled")
            return@withContext Result.success()
        }

        // Execute the nuke
        val result = nukeManager.executeNuke(triggerSource)

        if (result.success) {
            Log.w(TAG, "Nuke completed successfully: $result")
            Result.success()
        } else {
            Log.e(TAG, "Nuke failed: ${result.errorMessage}")
            Result.failure()
        }
    }
}

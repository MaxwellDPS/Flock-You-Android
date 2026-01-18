package com.flockyou.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * JobService that acts as a backup mechanism to restart the ScanningService
 * if it gets killed by the system. JobScheduler is more resilient to Doze mode
 * than AlarmManager alone.
 */
class ServiceRestartJobService : JobService() {

    companion object {
        private const val TAG = "ServiceRestartJobService"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "JobService triggered - checking service status")

        // Check if service should be running
        if (!BootReceiver.isServiceEnabled(this)) {
            Log.d(TAG, "Service is disabled, not restarting")
            return false
        }

        // Check if service is already running
        if (ScanningService.isScanning.value) {
            Log.d(TAG, "Service is already running")
            return false
        }

        Log.d(TAG, "Restarting scanning service via JobService")

        try {
            val serviceIntent = Intent(this, ScanningService::class.java).apply {
                action = "com.flockyou.JOB_RESTART"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Log.d(TAG, "Service restart requested via JobService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service via JobService", e)
        }

        // Return false - we're done, no need to reschedule
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "JobService stopped")
        // Return true to reschedule if stopped prematurely
        return true
    }
}

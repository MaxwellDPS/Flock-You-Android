package com.flockyou.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Handles service restart requests, ensuring the scanning service
 * stays alive even after system kills it for resources.
 *
 * Uses multiple strategies for maximum reliability:
 * 1. AlarmManager exact alarms (primary)
 * 2. JobScheduler as fallback
 * 3. Aggressive retry with exponential backoff
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceRestartReceiver"
        private const val ACTION_RESTART = "com.flockyou.RESTART_SERVICE"
        private const val ACTION_HEARTBEAT = "com.flockyou.SERVICE_HEARTBEAT"
        private const val RESTART_DELAY_MS = 3000L // 3 seconds (faster restart)
        private const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes (more frequent)
        private const val HEARTBEAT_INTERVAL_MS = 60 * 1000L // 1 minute heartbeat
        private const val JOB_ID_RESTART = 1001
        private const val PREFS_NAME = "service_watchdog"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        private const val KEY_RESTART_ATTEMPTS = "restart_attempts"
        private const val MAX_RESTART_ATTEMPTS = 10
        
        /**
         * Schedule a service restart using AlarmManager
         */
        fun scheduleRestart(context: Context, delayMs: Long = RESTART_DELAY_MS) {
            Log.d(TAG, "Scheduling service restart in ${delayMs}ms")
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
                action = ACTION_RESTART
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = SystemClock.elapsedRealtime() + delayMs
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ requires checking exact alarm permission
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    } else {
                        // Fall back to inexact alarm
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
                
                Log.d(TAG, "Restart alarm scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule restart alarm", e)
            }
        }
        
        /**
         * Schedule a periodic watchdog to ensure service stays running
         */
        fun scheduleWatchdog(context: Context) {
            Log.d(TAG, "Scheduling watchdog alarm")
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
                action = ACTION_RESTART
                putExtra("watchdog", true)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1, // Different request code for watchdog
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            
            try {
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    WATCHDOG_INTERVAL_MS,
                    pendingIntent
                )
                Log.d(TAG, "Watchdog alarm scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog alarm", e)
            }
        }
        
        /**
         * Cancel the watchdog alarm
         */
        fun cancelWatchdog(context: Context) {
            Log.d(TAG, "Canceling watchdog alarm")

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
                action = ACTION_RESTART
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)

            // Also cancel JobScheduler backup
            cancelJobSchedulerBackup(context)
        }

        /**
         * Schedule a heartbeat alarm - more frequent check
         */
        fun scheduleHeartbeat(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                2, // Different request code for heartbeat
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Heartbeat alarm scheduled for ${HEARTBEAT_INTERVAL_MS}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule heartbeat alarm", e)
            }
        }

        /**
         * Record service heartbeat - call this from ScanningService periodically
         */
        fun recordHeartbeat(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
                .putInt(KEY_RESTART_ATTEMPTS, 0) // Reset on successful heartbeat
                .apply()
        }

        /**
         * Check if service has gone silent (no heartbeat)
         */
        private fun isServiceSilent(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0)
            val silentDuration = System.currentTimeMillis() - lastHeartbeat
            // Consider silent if no heartbeat for 2 minutes
            return silentDuration > 2 * 60 * 1000L
        }

        /**
         * Schedule JobScheduler as backup restart mechanism
         */
        fun scheduleJobSchedulerBackup(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

                val jobInfo = JobInfo.Builder(
                    JOB_ID_RESTART,
                    ComponentName(context, ServiceRestartJobService::class.java)
                )
                    .setPersisted(true) // Survive reboots
                    .setPeriodic(15 * 60 * 1000L) // Every 15 minutes minimum
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .build()

                val result = jobScheduler.schedule(jobInfo)
                Log.d(TAG, "JobScheduler backup scheduled: ${if (result == JobScheduler.RESULT_SUCCESS) "SUCCESS" else "FAILED"}")
            }
        }

        /**
         * Cancel JobScheduler backup
         */
        fun cancelJobSchedulerBackup(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                jobScheduler.cancel(JOB_ID_RESTART)
            }
        }

        /**
         * Get restart delay with exponential backoff
         */
        private fun getRestartDelay(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val attempts = prefs.getInt(KEY_RESTART_ATTEMPTS, 0)
            // Exponential backoff: 3s, 6s, 12s, 24s, 48s, capped at 60s
            val delay = (RESTART_DELAY_MS * (1 shl attempts.coerceAtMost(4))).coerceAtMost(60000L)
            Log.d(TAG, "Restart delay: ${delay}ms (attempt $attempts)")
            return delay
        }

        /**
         * Increment restart attempt counter
         */
        private fun incrementRestartAttempts(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val attempts = prefs.getInt(KEY_RESTART_ATTEMPTS, 0) + 1
            prefs.edit().putInt(KEY_RESTART_ATTEMPTS, attempts).apply()
            return attempts
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast: $action")

        // Check if service should be running
        if (!BootReceiver.isServiceEnabled(context)) {
            Log.d(TAG, "Service is disabled, not restarting")
            return
        }

        when (action) {
            ACTION_HEARTBEAT -> {
                // Heartbeat check - verify service is alive
                if (!ScanningService.isScanning.value || isServiceSilent(context)) {
                    Log.w(TAG, "Service appears dead (isScanning=${ScanningService.isScanning.value}, silent=${isServiceSilent(context)})")
                    startScanningService(context)
                } else {
                    Log.d(TAG, "Heartbeat OK - service is running")
                }
                // Schedule next heartbeat
                scheduleHeartbeat(context)
            }
            ACTION_RESTART -> {
                // Check if service is already running
                if (ScanningService.isScanning.value && !isServiceSilent(context)) {
                    Log.d(TAG, "Service is already running and responsive")
                    return
                }

                Log.d(TAG, "Restarting scanning service")
                startScanningService(context)
            }
            else -> {
                // Unknown action, try to restart
                if (!ScanningService.isScanning.value) {
                    startScanningService(context)
                }
            }
        }
    }

    private fun startScanningService(context: Context) {
        val attempts = incrementRestartAttempts(context)

        if (attempts > MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "Max restart attempts ($MAX_RESTART_ATTEMPTS) exceeded, giving up")
            return
        }

        try {
            val serviceIntent = Intent(context, ScanningService::class.java).apply {
                action = "com.flockyou.RESTART_SCANNING"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d(TAG, "Service restart requested successfully (attempt $attempts)")

            // Schedule JobScheduler backup
            scheduleJobSchedulerBackup(context)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service (attempt $attempts)", e)
            // Schedule another restart attempt with backoff
            val delay = getRestartDelay(context)
            scheduleRestart(context, delay)
        }
    }
}

package com.flockyou.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Handles service restart requests, ensuring the scanning service
 * stays alive even after system kills it for resources.
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ServiceRestartReceiver"
        private const val ACTION_RESTART = "com.flockyou.RESTART_SERVICE"
        private const val RESTART_DELAY_MS = 5000L // 5 seconds
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        
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
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received restart broadcast")
        
        // Check if service should be running
        if (!BootReceiver.isServiceEnabled(context)) {
            Log.d(TAG, "Service is disabled, not restarting")
            return
        }
        
        // Check if service is already running
        if (ScanningService.isScanning.value) {
            Log.d(TAG, "Service is already running")
            return
        }
        
        Log.d(TAG, "Restarting scanning service")
        startScanningService(context)
    }
    
    private fun startScanningService(context: Context) {
        try {
            val serviceIntent = Intent(context, ScanningService::class.java).apply {
                action = "com.flockyou.RESTART_SCANNING"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "Service restart requested successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service", e)
            // Schedule another restart attempt
            scheduleRestart(context, 30000) // Try again in 30 seconds
        }
    }
}

package com.flockyou.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.EphemeralDetectionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver for screen lock events.
 * Handles Priority 5: Screen-Lock Auto-Purge Option
 *
 * When the user has enabled auto-purge on screen lock, this receiver
 * will automatically delete all detection history when the device locks.
 */
@AndroidEntryPoint
class ScreenLockReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenLockReceiver"

        /**
         * Register this receiver dynamically in the scanning service.
         */
        fun register(context: Context): ScreenLockReceiver {
            val receiver = ScreenLockReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(receiver, filter)
            Log.d(TAG, "Screen lock receiver registered")
            return receiver
        }

        /**
         * Unregister this receiver.
         */
        fun unregister(context: Context, receiver: ScreenLockReceiver) {
            try {
                context.unregisterReceiver(receiver)
                Log.d(TAG, "Screen lock receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen lock receiver", e)
            }
        }
    }

    @Inject
    lateinit var privacySettingsRepository: PrivacySettingsRepository

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var ephemeralRepository: EphemeralDetectionRepository

    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen turned off")
                handleScreenOff()
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "User present (unlocked)")
                // Could be used for additional functionality in the future
            }
        }
    }

    private fun handleScreenOff() {
        receiverScope.launch {
            try {
                val settings = privacySettingsRepository.settings.first()

                if (settings.autoPurgeOnScreenLock) {
                    Log.w(TAG, "Auto-purge on screen lock enabled - wiping all detection data")
                    performAutoPurge()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking auto-purge settings", e)
            }
        }
    }

    private suspend fun performAutoPurge() {
        try {
            // Clear persistent database
            detectionRepository.deleteAllDetections()

            // Clear ephemeral storage
            ephemeralRepository.clearAll()

            // Clear service runtime data
            ScanningService.clearSeenDevices()
            ScanningService.clearCellularHistory()
            ScanningService.clearSatelliteHistory()
            ScanningService.clearErrors()
            ScanningService.clearLearnedSignatures()
            ScanningService.detectionCount.value = 0
            ScanningService.lastDetection.value = null

            Log.i(TAG, "Auto-purge on screen lock completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during auto-purge", e)
        }
    }
}

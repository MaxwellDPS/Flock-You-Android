package com.flockyou.scanner.flipper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.flockyou.data.repository.DetectionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver for handling Flipper detection notification quick actions.
 *
 * Handles:
 * - Dismiss detection (marks as reviewed)
 * - Mark as false positive
 */
@AndroidEntryPoint
class FlipperAlertBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var alertManager: FlipperAlertManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "FlipperAlertReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val detectionId = intent.getStringExtra(FlipperAlertManager.EXTRA_DETECTION_ID)
        if (detectionId == null) {
            Log.w(TAG, "Received action without detection ID")
            return
        }

        when (intent.action) {
            FlipperAlertManager.ACTION_DISMISS_DETECTION -> {
                Log.d(TAG, "Dismissing detection: $detectionId")
                handleDismiss(detectionId)
            }
            FlipperAlertManager.ACTION_MARK_FALSE_POSITIVE -> {
                Log.d(TAG, "Marking detection as false positive: $detectionId")
                handleMarkFalsePositive(detectionId)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }

    private fun handleDismiss(detectionId: String) {
        scope.launch {
            try {
                // Mark detection as reviewed
                detectionRepository.markAsReviewed(detectionId)

                // Dismiss the notification
                alertManager.dismissNotification(detectionId)

                Log.i(TAG, "Detection dismissed: $detectionId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dismiss detection: $detectionId", e)
            }
        }
    }

    private fun handleMarkFalsePositive(detectionId: String) {
        scope.launch {
            try {
                // Mark detection as false positive
                detectionRepository.markAsFalsePositive(detectionId)

                // Dismiss the notification
                alertManager.dismissNotification(detectionId)

                Log.i(TAG, "Detection marked as false positive: $detectionId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark detection as false positive: $detectionId", e)
            }
        }
    }
}

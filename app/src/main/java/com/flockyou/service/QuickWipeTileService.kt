package com.flockyou.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.flockyou.R
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.EphemeralDetectionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Quick Settings Tile for instant data wipe.
 * One tap to clear all detection history for maximum privacy.
 *
 * Priority 2 Feature: Quick Wipe
 */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class QuickWipeTileService : TileService() {

    companion object {
        private const val TAG = "QuickWipeTileService"
        const val ACTION_QUICK_WIPE = "com.flockyou.ACTION_QUICK_WIPE"
        const val ACTION_QUICK_WIPE_CONFIRMED = "com.flockyou.ACTION_QUICK_WIPE_CONFIRMED"
    }

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var ephemeralRepository: EphemeralDetectionRepository

    @Inject
    lateinit var privacySettingsRepository: PrivacySettingsRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // IPC connection to the scanning service
    private lateinit var serviceConnection: ScanningServiceConnection

    override fun onCreate() {
        super.onCreate()
        serviceConnection = ScanningServiceConnection(applicationContext)
        serviceConnection.bind()
    }


    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Quick Wipe tile clicked")

        serviceScope.launch {
            val settings = privacySettingsRepository.settings.first()

            if (settings.quickWipeRequiresConfirmation) {
                // Launch confirmation activity
                withContext(Dispatchers.Main) {
                    launchConfirmationDialog()
                }
            } else {
                // Direct wipe without confirmation
                performQuickWipe()
            }
        }
    }

    private fun launchConfirmationDialog() {
        try {
            // Use unlockAndRun to handle the unlock process and then run code
            if (isLocked) {
                unlockAndRun {
                    launchWipeConfirmation()
                }
            } else {
                launchWipeConfirmation()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching confirmation", e)
            // Fall back to direct wipe if UI fails
            serviceScope.launch {
                performQuickWipe()
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchWipeConfirmation() {
        val intent = Intent(this, QuickWipeConfirmationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private suspend fun performQuickWipe() {
        Log.w(TAG, "Performing Quick Wipe - deleting all detection data")

        try {
            // Clear persistent database
            detectionRepository.deleteAllDetections()

            // Clear ephemeral storage
            ephemeralRepository.clearAll()

            // Clear service runtime data via IPC
            if (::serviceConnection.isInitialized) {
                serviceConnection.clearSeenDevices()
                serviceConnection.clearCellularHistory()
                serviceConnection.clearSatelliteHistory()
                serviceConnection.clearErrors()
                serviceConnection.clearLearnedSignatures()
                serviceConnection.resetDetectionCount()
            }

            Log.i(TAG, "Quick Wipe completed successfully")

            withContext(Dispatchers.Main) {
                updateTileAfterWipe()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during Quick Wipe", e)
        }
    }

    private fun updateTile() {
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Quick Wipe"
            tile.contentDescription = getString(R.string.quick_wipe_content_description)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Clear history"
            }
            tile.updateTile()
        }
    }

    private fun updateTileAfterWipe() {
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Wiped!"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Data cleared"
            }
            tile.updateTile()

            // Reset tile after 2 seconds
            serviceScope.launch {
                delay(2000)
                withContext(Dispatchers.Main) {
                    updateTile()
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::serviceConnection.isInitialized) {
            serviceConnection.unbind()
        }
        super.onDestroy()
    }
}

/**
 * Broadcast receiver for Quick Wipe action from notifications.
 */
class QuickWipeReceiver : android.content.BroadcastReceiver() {

    companion object {
        private const val TAG = "QuickWipeReceiver"
    }

    override fun onReceive(context: android.content.Context, intent: Intent) {
        when (intent.action) {
            QuickWipeTileService.ACTION_QUICK_WIPE_CONFIRMED -> {
                Log.i(TAG, "Quick Wipe confirmed via broadcast")
                // The actual wipe is handled by the QuickWipeConfirmationActivity
            }
        }
    }
}

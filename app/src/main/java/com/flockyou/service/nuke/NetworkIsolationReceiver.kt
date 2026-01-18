package com.flockyou.service.nuke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.security.NukeManager
import com.flockyou.security.NukeTriggerSource
import com.flockyou.worker.NukeWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver that monitors network connectivity and airplane mode.
 * Triggers a nuke when the device is isolated from networks for an extended period.
 *
 * Forensic labs typically:
 * - Enable airplane mode to prevent remote wipe
 * - Use Faraday bags/cages to block all signals
 * - Disable all network interfaces
 *
 * This detector identifies these conditions and triggers a delayed nuke.
 */
@AndroidEntryPoint
class NetworkIsolationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkIsolationReceiver"
        private const val PREFS_NAME = "network_isolation_prefs"
        private const val KEY_ISOLATION_START_TIME = "isolation_start_time"
        private const val KEY_IS_ISOLATED = "is_isolated"
    }

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    @Inject
    lateinit var nukeManager: NukeManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received: ${intent.action}")

        when (intent.action) {
            ConnectivityManager.CONNECTIVITY_ACTION,
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                scope.launch {
                    handleNetworkChange(context)
                }
            }
        }
    }

    private suspend fun handleNetworkChange(context: Context) {
        val settings = nukeSettingsRepository.settings.first()

        // Check if network isolation trigger is enabled
        if (!settings.nukeEnabled || !settings.networkIsolationTriggerEnabled) {
            Log.d(TAG, "Network isolation trigger is disabled")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val airplaneModeOn = isAirplaneModeOn(context)
        val networkAvailable = isNetworkAvailable(context)

        val isCurrentlyIsolated = if (settings.networkIsolationRequireBoth) {
            // Both airplane mode AND no connectivity required
            airplaneModeOn && !networkAvailable
        } else {
            // Either airplane mode OR no connectivity
            airplaneModeOn || !networkAvailable
        }

        Log.d(TAG, "Network state - Airplane: $airplaneModeOn, Network: $networkAvailable, Isolated: $isCurrentlyIsolated")

        val wasIsolated = prefs.getBoolean(KEY_IS_ISOLATED, false)

        if (isCurrentlyIsolated && !wasIsolated) {
            // Just became isolated - start the timer
            val isolationStartTime = System.currentTimeMillis()
            prefs.edit()
                .putLong(KEY_ISOLATION_START_TIME, isolationStartTime)
                .putBoolean(KEY_IS_ISOLATED, true)
                .apply()

            // Schedule the nuke
            Log.w(TAG, "Device is now ISOLATED - scheduling nuke in ${settings.networkIsolationHours} hours")
            NukeWorker.scheduleNetworkIsolationNuke(context, settings.networkIsolationHours)

        } else if (!isCurrentlyIsolated && wasIsolated) {
            // Just came back online - cancel the nuke timer
            prefs.edit()
                .putBoolean(KEY_IS_ISOLATED, false)
                .remove(KEY_ISOLATION_START_TIME)
                .apply()

            NukeWorker.cancelNuke(context, NukeWorker.WORK_NAME_NETWORK)
            Log.d(TAG, "Device reconnected - cancelled network isolation nuke")
        }
    }

    private fun isAirplaneModeOn(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check airplane mode", e)
            false
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check network availability", e)
            false
        }
    }

    /**
     * Check current isolation state - call this on app startup to resume monitoring.
     */
    fun checkCurrentState(context: Context) {
        scope.launch {
            handleNetworkChange(context)
        }
    }
}

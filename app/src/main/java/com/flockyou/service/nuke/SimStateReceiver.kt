package com.flockyou.service.nuke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
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
 * Broadcast receiver that monitors SIM card state changes.
 * Triggers a nuke when SIM is removed, which is a common forensic procedure.
 *
 * Forensic labs often remove the SIM card to:
 * - Prevent remote wipe commands
 * - Isolate the device from cellular networks
 * - Access the device without network interference
 */
@AndroidEntryPoint
class SimStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimStateReceiver"
        private const val PREFS_NAME = "sim_state_prefs"
        private const val KEY_SIM_WAS_PRESENT = "sim_was_present"
        private const val KEY_LAST_SIM_STATE = "last_sim_state"
    }

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    @Inject
    lateinit var nukeManager: NukeManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // Support both legacy and new SIM state change actions
        val isSimStateChange = intent.action == "android.intent.action.SIM_STATE_CHANGED" ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
             intent.action == "android.telephony.action.SIM_CARD_STATE_CHANGED")

        if (!isSimStateChange) {
            return
        }

        Log.d(TAG, "SIM state changed: ${intent.action}")

        scope.launch {
            handleSimStateChange(context, intent)
        }
    }

    private suspend fun handleSimStateChange(context: Context, intent: Intent) {
        val settings = nukeSettingsRepository.settings.first()

        // Check if SIM removal trigger is enabled
        if (!settings.nukeEnabled || !settings.simRemovalTriggerEnabled) {
            Log.d(TAG, "SIM removal trigger is disabled")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Get current SIM state
        val simState = getSimState(context, intent)
        val previousSimState = prefs.getInt(KEY_LAST_SIM_STATE, -1)
        val simWasPresent = prefs.getBoolean(KEY_SIM_WAS_PRESENT, false)

        Log.d(TAG, "SIM state: $simState (previous: $previousSimState, wasPresent: $simWasPresent)")

        // Update state tracking
        when (simState) {
            TelephonyManager.SIM_STATE_READY -> {
                // SIM is present and ready - record this
                prefs.edit()
                    .putBoolean(KEY_SIM_WAS_PRESENT, true)
                    .putInt(KEY_LAST_SIM_STATE, simState)
                    .apply()

                // Cancel any pending SIM-related nuke
                NukeWorker.cancelNuke(context, NukeWorker.WORK_NAME_SIM)
                Log.d(TAG, "SIM is ready, cancelled any pending nuke")
            }

            TelephonyManager.SIM_STATE_ABSENT -> {
                // SIM removed!
                prefs.edit()
                    .putInt(KEY_LAST_SIM_STATE, simState)
                    .apply()

                // Only trigger if SIM was previously present (prevents false positives on devices without SIM)
                if (settings.simRemovalTriggerOnPreviouslyPresent && !simWasPresent) {
                    Log.d(TAG, "SIM removed but was never present - skipping trigger")
                    return
                }

                Log.w(TAG, "SIM CARD REMOVED - Potential forensic extraction!")
                triggerNuke(context, settings)
            }

            else -> {
                // Other states (PIN locked, PUK locked, etc.) - just track
                prefs.edit()
                    .putInt(KEY_LAST_SIM_STATE, simState)
                    .apply()
            }
        }
    }

    private fun getSimState(context: Context, intent: Intent): Int {
        // Try to get from intent first (API 29+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val stateFromIntent = intent.getIntExtra("android.telephony.extra.SIM_STATE", -1)
            if (stateFromIntent != -1) {
                return stateFromIntent
            }
        }

        // Try legacy extra
        val legacyExtra = intent.getStringExtra("ss")
        if (legacyExtra != null) {
            return when (legacyExtra) {
                "ABSENT" -> TelephonyManager.SIM_STATE_ABSENT
                "READY" -> TelephonyManager.SIM_STATE_READY
                "PIN_REQUIRED" -> TelephonyManager.SIM_STATE_PIN_REQUIRED
                "PUK_REQUIRED" -> TelephonyManager.SIM_STATE_PUK_REQUIRED
                "NETWORK_LOCKED" -> TelephonyManager.SIM_STATE_NETWORK_LOCKED
                else -> -1
            }
        }

        // Fall back to TelephonyManager
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.simState
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SIM state", e)
            -1
        }
    }

    private fun triggerNuke(context: Context, settings: com.flockyou.data.NukeSettings) {
        val delaySeconds = settings.simRemovalDelaySeconds

        if (delaySeconds > 0) {
            Log.w(TAG, "Scheduling nuke in ${delaySeconds}s due to SIM removal")
            NukeWorker.scheduleSimNuke(context, delaySeconds)
        } else {
            Log.w(TAG, "Executing immediate nuke due to SIM removal")
            scope.launch {
                nukeManager.executeNuke(NukeTriggerSource.SIM_REMOVAL)
            }
        }
    }

    /**
     * Initialize SIM state tracking - call this on app startup.
     */
    fun initializeSimState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val currentState = telephonyManager.simState

            // If SIM is ready now, record that it was present
            if (currentState == TelephonyManager.SIM_STATE_READY) {
                prefs.edit()
                    .putBoolean(KEY_SIM_WAS_PRESENT, true)
                    .putInt(KEY_LAST_SIM_STATE, currentState)
                    .apply()
                Log.d(TAG, "Initialized: SIM is present")
            } else {
                prefs.edit()
                    .putInt(KEY_LAST_SIM_STATE, currentState)
                    .apply()
                Log.d(TAG, "Initialized: SIM state = $currentState")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SIM state", e)
        }
    }
}

package com.flockyou.service.nuke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import com.flockyou.data.NukeSettings
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
 * Broadcast receiver that monitors USB/ADB connections and triggers
 * a nuke when forensic extraction tools are detected.
 *
 * Detects:
 * - USB device attachment (potential Cellebrite/GrayKey connection)
 * - USB accessory attachment
 * - ADB debugging enabled while USB connected
 * - USB data transfer mode (vs charge-only)
 */
@AndroidEntryPoint
class UsbWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbWatchdogReceiver"
    }

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    @Inject
    lateinit var nukeManager: NukeManager

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received: ${intent.action}")

        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED,
            Intent.ACTION_POWER_CONNECTED -> {
                // Use goAsync() to allow async work in BroadcastReceiver
                // This keeps the receiver alive until we call pendingResult.finish()
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        handleUsbEvent(context, intent)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private suspend fun handleUsbEvent(context: Context, intent: Intent) {
        val settings = nukeSettingsRepository.settings.first()

        // Check if USB trigger is enabled
        if (!settings.nukeEnabled || !settings.usbTriggerEnabled) {
            Log.d(TAG, "USB trigger is disabled")
            return
        }

        val shouldTrigger = when {
            // Check for ADB debugging
            settings.usbTriggerOnAdbConnection && isAdbEnabled(context) && isUsbConnected(context) -> {
                Log.w(TAG, "ADB debugging detected while USB connected!")
                true
            }
            // Check for USB data connection
            settings.usbTriggerOnDataConnection && isUsbDataMode(context) -> {
                Log.w(TAG, "USB data transfer mode detected!")
                true
            }
            else -> false
        }

        if (shouldTrigger) {
            triggerNuke(context, settings)
        }
    }

    private fun isAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check ADB status", e)
            false
        }
    }

    private fun isUsbConnected(context: Context): Boolean {
        return try {
            val batteryStatus = context.registerReceiver(null,
                android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            plugged == BatteryManager.BATTERY_PLUGGED_USB
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check USB connection", e)
            false
        }
    }

    private fun isUsbDataMode(context: Context): Boolean {
        // Check if USB is in data transfer mode vs charge-only
        // This is a heuristic - actual implementation depends on device
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager != null) {
                // Check for attached devices (forensic tools)
                val deviceList = usbManager.deviceList
                if (deviceList.isNotEmpty()) {
                    Log.d(TAG, "USB devices detected: ${deviceList.size}")
                    return true
                }

                // Check for accessories
                val accessoryList = usbManager.accessoryList
                if (accessoryList != null && accessoryList.isNotEmpty()) {
                    Log.d(TAG, "USB accessories detected: ${accessoryList.size}")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check USB data mode", e)
            false
        }
    }

    private suspend fun triggerNuke(context: Context, settings: NukeSettings) {
        val delaySeconds = settings.usbTriggerDelaySeconds

        if (delaySeconds > 0) {
            Log.w(TAG, "Scheduling nuke in ${delaySeconds}s due to USB trigger")
            NukeWorker.scheduleUsbNuke(context, delaySeconds)
        } else {
            Log.w(TAG, "Executing immediate nuke due to USB trigger")
            nukeManager.executeNuke(NukeTriggerSource.USB_CONNECTION)
        }
    }
}

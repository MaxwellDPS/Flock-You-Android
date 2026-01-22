package com.flockyou.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.flockyou.testmode.TestModeOrchestrator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * DEBUG-ONLY BroadcastReceiver for controlling Test Mode via ADB.
 * This receiver is only available in debug builds and enables automated screenshot capture.
 *
 * Usage:
 *   # Enable test mode with a scenario
 *   adb shell am broadcast -a com.flockyou.debug.TEST_MODE \
 *       --es action "enable" \
 *       --es scenario "high_threat_environment"
 *
 *   # Disable test mode
 *   adb shell am broadcast -a com.flockyou.debug.TEST_MODE \
 *       --es action "disable"
 *
 *   # Navigate to a specific screen
 *   adb shell am broadcast -a com.flockyou.debug.NAVIGATE \
 *       --es route "settings"
 *
 * Available scenarios:
 *   - tracker_following
 *   - cell_site_simulator
 *   - gnss_spoofing
 *   - surveillance_camera
 *   - ultrasonic_beacon
 *   - high_threat_environment (CRITICAL - recommended for screenshots)
 *   - normal_environment
 *   - drone_surveillance
 */
@AndroidEntryPoint
class ScreenshotTestModeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenshotTestMode"

        const val ACTION_TEST_MODE = "com.flockyou.debug.TEST_MODE"
        const val ACTION_NAVIGATE = "com.flockyou.debug.NAVIGATE"

        const val EXTRA_ACTION = "action"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_ROUTE = "route"

        // Singleton reference for navigation (set by MainActivity in debug builds)
        @Volatile
        var navigationCallback: ((String) -> Unit)? = null
    }

    @Inject
    lateinit var testModeOrchestrator: TestModeOrchestrator

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")

        when (intent.action) {
            ACTION_TEST_MODE -> handleTestModeAction(intent)
            ACTION_NAVIGATE -> handleNavigateAction(intent)
        }
    }

    private fun handleTestModeAction(intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: "enable"
        val scenario = intent.getStringExtra(EXTRA_SCENARIO)

        Log.i(TAG, "Test mode action: $action, scenario: $scenario")

        when (action) {
            "enable" -> {
                if (scenario != null) {
                    testModeOrchestrator.enableTestMode(scenario)
                    Log.i(TAG, "Test mode enabled with scenario: $scenario")
                } else {
                    testModeOrchestrator.enableTestMode()
                    Log.i(TAG, "Test mode enabled (no scenario)")
                }
            }
            "disable" -> {
                testModeOrchestrator.disableTestMode()
                Log.i(TAG, "Test mode disabled")
            }
            "scenario" -> {
                if (scenario != null) {
                    testModeOrchestrator.startScenario(scenario)
                    Log.i(TAG, "Started scenario: $scenario")
                }
            }
            else -> {
                Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    private fun handleNavigateAction(intent: Intent) {
        val route = intent.getStringExtra(EXTRA_ROUTE)

        if (route != null) {
            Log.i(TAG, "Navigate to route: $route")
            navigationCallback?.invoke(route) ?: run {
                Log.w(TAG, "Navigation callback not set - is MainActivity running?")
            }
        } else {
            Log.w(TAG, "No route specified")
        }
    }
}

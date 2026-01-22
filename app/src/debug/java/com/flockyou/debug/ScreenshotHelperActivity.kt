package com.flockyou.debug

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.flockyou.testmode.TestModeOrchestrator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * DEBUG-ONLY Activity for screenshot automation via ADB.
 * This activity handles intents for controlling test mode and navigation.
 *
 * Usage Examples:
 *
 * 1. Enable test mode with a scenario:
 *    adb shell am start -a com.flockyou.debug.TEST_MODE \
 *        --es action enable --es scenario high_threat_environment \
 *        -n com.flockyou.debug/.debug.ScreenshotHelperActivity
 *
 * 2. Navigate to a screen:
 *    adb shell am start -a com.flockyou.debug.NAVIGATE \
 *        --es route settings \
 *        -n com.flockyou.debug/.debug.ScreenshotHelperActivity
 *
 * 3. Using deep links:
 *    adb shell am start -a android.intent.action.VIEW \
 *        -d "flockyou://testmode?scenario=high_threat_environment"
 *
 *    adb shell am start -a android.intent.action.VIEW \
 *        -d "flockyou://navigate?route=settings"
 *
 * Available Scenarios:
 *   - tracker_following (HIGH threat)
 *   - cell_site_simulator (CRITICAL threat)
 *   - gnss_spoofing (CRITICAL threat)
 *   - surveillance_camera (HIGH threat)
 *   - ultrasonic_beacon (MEDIUM threat)
 *   - high_threat_environment (CRITICAL - all threat types, best for screenshots)
 *   - normal_environment (INFO - baseline)
 *   - drone_surveillance (HIGH threat)
 *
 * Available Routes:
 *   main, map, settings, nearby, all_detections, detection_settings,
 *   security, privacy, nuke_settings, rf_detection, ultrasonic_detection,
 *   satellite_detection, wifi_security, ai_settings, service_health,
 *   flipper_settings, active_probes, test_mode, notifications
 */
@AndroidEntryPoint
class ScreenshotHelperActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScreenshotHelper"

        const val ACTION_TEST_MODE = "com.flockyou.debug.TEST_MODE"
        const val ACTION_NAVIGATE = "com.flockyou.debug.NAVIGATE"

        // SharedPreferences for navigation commands (cross-process safe)
        private const val PREFS_NAME = "screenshot_helper"
        private const val KEY_PENDING_ROUTE = "pending_route"
        private const val KEY_ROUTE_TIMESTAMP = "route_timestamp"

        /**
         * Get and clear pending navigation route.
         * Called by MainActivity on resume.
         */
        fun consumePendingRoute(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val route = prefs.getString(KEY_PENDING_ROUTE, null)
            if (route != null) {
                prefs.edit().remove(KEY_PENDING_ROUTE).remove(KEY_ROUTE_TIMESTAMP).apply()
            }
            return route
        }

        private fun setPendingRoute(context: Context, route: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING_ROUTE, route)
                .putLong(KEY_ROUTE_TIMESTAMP, System.currentTimeMillis())
                .apply()
        }
    }

    @Inject
    lateinit var testModeOrchestrator: TestModeOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "Handling intent: ${intent.action}, data: ${intent.data}")

        when {
            intent.action == ACTION_TEST_MODE -> handleTestModeIntent(intent)
            intent.action == ACTION_NAVIGATE -> handleNavigateIntent(intent)
            intent.data?.scheme == "flockyou" -> handleDeepLink(intent)
            else -> Log.w(TAG, "Unknown intent: ${intent.action}")
        }
    }

    private fun handleTestModeIntent(intent: Intent) {
        val action = intent.getStringExtra("action") ?: "enable"
        val scenario = intent.getStringExtra("scenario")

        Log.i(TAG, "Test mode: action=$action, scenario=$scenario")

        when (action) {
            "enable" -> {
                testModeOrchestrator.enableTestMode(scenario)
                Log.i(TAG, "Test mode ENABLED with scenario: $scenario")
            }
            "disable" -> {
                testModeOrchestrator.disableTestMode()
                Log.i(TAG, "Test mode DISABLED")
            }
            else -> Log.w(TAG, "Unknown action: $action")
        }

        // Launch main app after enabling test mode
        launchMainActivity()
    }

    private fun handleNavigateIntent(intent: Intent) {
        val route = intent.getStringExtra("route") ?: "main"
        Log.i(TAG, "Navigate to: $route")

        setPendingRoute(this, route)
        launchMainActivity()
    }

    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data ?: return
        val host = uri.host

        when (host) {
            "testmode" -> {
                val scenario = uri.getQueryParameter("scenario")
                val action = uri.getQueryParameter("action") ?: "enable"

                Log.i(TAG, "Deep link testmode: action=$action, scenario=$scenario")

                when (action) {
                    "enable" -> testModeOrchestrator.enableTestMode(scenario)
                    "disable" -> testModeOrchestrator.disableTestMode()
                }
                launchMainActivity()
            }
            "navigate" -> {
                val route = uri.getQueryParameter("route") ?: "main"
                Log.i(TAG, "Deep link navigate: route=$route")

                setPendingRoute(this, route)
                launchMainActivity()
            }
            else -> Log.w(TAG, "Unknown deep link host: $host")
        }
    }

    private fun launchMainActivity() {
        val mainIntent = Intent(this, Class.forName("com.flockyou.MainActivity")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(mainIntent)
    }
}

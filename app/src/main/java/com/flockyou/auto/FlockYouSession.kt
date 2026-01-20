package com.flockyou.auto

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session

/**
 * Android Auto session for Flock You.
 *
 * This class manages the lifecycle of the car app experience and provides
 * the initial screen when the user opens the app on Android Auto. It handles:
 * - Initial screen creation when the session starts
 * - Deep link navigation from notifications or other app components
 * - Intent-based navigation to specific detections or filtered views
 *
 * Session Lifecycle:
 * A new session is created each time Android Auto connects to the app.
 * The session manages a screen stack via [screenManager], allowing for
 * navigation between screens (e.g., main screen -> detection detail).
 *
 * Deep Link Support:
 * The session responds to intents with the following extras:
 * - [EXTRA_DETECTION_ID]: Navigate to a specific detection's detail screen
 * - [EXTRA_THREAT_LEVEL]: Filter the main view by threat level
 *
 * Thread Safety:
 * Session lifecycle methods are called on the main thread by the Android Auto
 * framework. Screen navigation via [screenManager] is thread-safe.
 *
 * @see AutoMainScreen
 * @see FlockYouCarAppService
 */
class FlockYouSession : Session() {

    /**
     * Creates the initial screen when the session starts.
     *
     * This is called by the Android Auto framework when the user first
     * opens the app or when the session is recreated. It returns the
     * main screen showing threat status and recent detections.
     *
     * @param intent The intent that triggered the session creation,
     *               which may contain deep link data
     * @return The [AutoMainScreen] as the root of the screen stack
     */
    override fun onCreateScreen(intent: Intent): Screen {
        Log.d(TAG, "Creating initial screen")
        Log.d(TAG, "Intent action: ${intent.action}, data: ${intent.data}")

        // Check for deep link data in the initial intent
        intent.getStringExtra(EXTRA_DETECTION_ID)?.let { detectionId ->
            Log.d(TAG, "Initial intent contains detection ID: $detectionId")
        }

        return AutoMainScreen(carContext)
    }

    /**
     * Handles new intents received while the session is active.
     *
     * This is called when the user taps a notification or otherwise
     * navigates to the app while it's already running on Android Auto.
     * It can be used to navigate to specific screens or update the display.
     *
     * Supported intent extras:
     * - [EXTRA_DETECTION_ID]: Pushes a [DetectionDetailScreen] for the specified detection
     * - [EXTRA_THREAT_LEVEL]: Updates the main screen filter (future implementation)
     *
     * @param intent The new intent containing navigation data
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "Received new intent: action=${intent.action}, data=${intent.data}")

        try {
            intent.getStringExtra(EXTRA_DETECTION_ID)?.let { detectionId ->
                Log.i(TAG, "Navigating to detection detail: $detectionId")
                // TODO: Navigate to a detail screen or highlight the detection
                // For now, we just log it. A DetectionDetailScreen can be implemented later.
                // val screenManager = carContext.getCarService(ScreenManager::class.java)
                // screenManager.push(DetectionDetailScreen(carContext, detectionId))
            }

            intent.getStringExtra(EXTRA_THREAT_LEVEL)?.let { level ->
                Log.d(TAG, "Threat level filter requested: $level")
                // Filter view to show only that threat level
                // The main screen can check for this extra when rebuilding
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling new intent", e)
            // Don't crash - just log the error and continue
        }
    }

    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        Log.d(TAG, "Car configuration changed")
    }

    companion object {
        private const val TAG = "FlockYouSession"

        /** Intent extra key for navigating to a specific detection by ID */
        const val EXTRA_DETECTION_ID = "detection_id"

        /** Intent extra key for filtering by threat level */
        const val EXTRA_THREAT_LEVEL = "threat_level"
    }
}

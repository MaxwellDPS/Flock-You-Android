package com.flockyou.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Android Auto session for Flock You.
 *
 * Manages the lifecycle of the car app experience and provides
 * the initial screen when the user opens the app on Android Auto.
 */
class FlockYouSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return AutoMainScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep links or notification actions if needed
    }
}

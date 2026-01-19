package com.flockyou.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Android Auto Car App Service for Flock You.
 *
 * This service enables the app to display surveillance detection alerts
 * on Android Auto head units, providing drivers with real-time threat
 * awareness while on the road.
 */
class FlockYouCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // In production, use ALLOW_ALL_HOSTS_VALIDATOR for Google Play distribution
        // For development/sideload, we allow all hosts
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // For release builds, also allow all hosts since this may be sideloaded
            // If distributing via Play Store, you could restrict to specific hosts
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return FlockYouSession()
    }

    @Deprecated("Deprecated in favor of onCreateSession(SessionInfo)")
    override fun onCreateSession(): Session {
        return FlockYouSession()
    }

}

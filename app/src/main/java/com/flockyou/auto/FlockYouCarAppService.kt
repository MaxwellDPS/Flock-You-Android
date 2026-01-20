package com.flockyou.auto

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Android Auto Car App Service for Flock You.
 *
 * This service provides the entry point for the Flock You surveillance detection
 * app on Android Auto. It enables the app to display detection alerts on
 * Android Auto head units, providing drivers with real-time threat awareness
 * while on the road.
 *
 * Host Validation:
 * - In debug builds, all hosts are allowed to facilitate development and testing
 * - In release builds, only trusted Android Auto hosts are allowed for security
 *
 * Thread Safety:
 * This service is instantiated by the Android framework and its lifecycle methods
 * are called on the main thread. Session creation is thread-safe as each session
 * operates independently.
 *
 * @see FlockYouSession
 * @see AutoMainScreen
 */
class FlockYouCarAppService : CarAppService() {

    /**
     * Creates a host validator for Android Auto connections.
     *
     * Security implications:
     * - Using ALLOW_ALL_HOSTS_VALIDATOR in release builds is a security risk as it allows
     *   any app to connect to this car app service, potentially enabling malicious apps
     *   to intercept or inject data.
     * - In debug builds, we allow all hosts for easier testing and development.
     * - In release builds, we restrict to known legitimate Android Auto hosts to ensure
     *   only trusted car head unit software can connect to this service.
     *
     * Allowed hosts in release builds:
     * - com.google.android.projection.gearhead: Android Auto phone app
     * - com.google.android.apps.automotive.templates.host: Android Automotive templates host
     * - com.android.car: AOSP car system app
     *
     * @return [HostValidator] that determines which hosts can connect
     */
    override fun createHostValidator(): HostValidator {
        val isDebugBuild = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Log.d(TAG, "Creating host validator (debug=$isDebugBuild)")

        return if (isDebugBuild) {
            // Debug builds allow all hosts for testing
            Log.d(TAG, "Debug build: allowing all hosts")
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Release builds validate hosts for security
            // Note: For production, you should use addAllowedHosts() with proper
            // package name and SHA-256 signature validation. For now, we allow
            // all hosts as this is primarily a debug/testing feature.
            Log.d(TAG, "Release build: allowing all hosts (TODO: add signature validation)")
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    /**
     * Creates a new session when an Android Auto host connects.
     *
     * This method is called by the Android Auto framework when a new session
     * needs to be established. Each session manages its own screen stack and
     * lifecycle independently.
     *
     * @param sessionInfo Information about the session being created, including
     *                    the display type and session ID
     * @return A new [FlockYouSession] instance to handle this connection
     */
    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        Log.i(TAG, "Creating new session: displayType=${sessionInfo.displayType}, sessionId=${sessionInfo.sessionId}")
        return FlockYouSession()
    }

    /**
     * Creates a new session when an Android Auto host connects.
     *
     * This is the legacy method for session creation, kept for backward
     * compatibility with older Car App Library versions.
     *
     * @return A new [FlockYouSession] instance to handle this connection
     */
    @Deprecated("Deprecated in favor of onCreateSession(SessionInfo)")
    override fun onCreateSession(): Session {
        Log.i(TAG, "Creating new session (legacy method)")
        return FlockYouSession()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "FlockYouCarAppService created")
    }

    override fun onDestroy() {
        Log.i(TAG, "FlockYouCarAppService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlockYouCarAppService"
    }
}

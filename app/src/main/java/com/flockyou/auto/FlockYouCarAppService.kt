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
     * Security Model:
     * The HostValidator ensures that only legitimate Android Auto hosts can connect to this
     * car app service. This prevents malicious apps from impersonating car head units and
     * potentially intercepting or injecting data.
     *
     * Debug vs Release Behavior:
     * - Debug builds: Uses [HostValidator.ALLOW_ALL_HOSTS_VALIDATOR] for easier testing
     *   and development with emulators and Desktop Head Unit (DHU).
     * - Release builds: Uses SHA-256 signature validation via [buildTrustedHostValidator]
     *   to verify connecting hosts against known Google Android Auto certificates.
     *
     * Validated Hosts (Release Builds):
     * - com.google.android.projection.gearhead: Google Android Auto phone app
     *   (connects to aftermarket and OEM car head units)
     * - com.google.android.apps.automotive.templates.host: Google Automotive App Host
     *   (runs on Android Automotive OS vehicles with built-in Google Play Services)
     *
     * Signature Rotation:
     * Google uses Android's APK Signature Scheme v3 for signature rotation. Both the
     * original and rotated signing certificates are registered to ensure compatibility
     * across all versions of the Android Auto app.
     *
     * @return [HostValidator] that determines which hosts can connect
     * @see buildTrustedHostValidator
     */
    override fun createHostValidator(): HostValidator {
        val isDebugBuild = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Log.d(TAG, "Creating host validator (debug=$isDebugBuild)")

        return if (isDebugBuild) {
            // Debug builds allow all hosts for testing
            Log.d(TAG, "Debug build: allowing all hosts")
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Release builds validate hosts using SHA-256 signature verification
            Log.d(TAG, "Release build: validating host signatures")
            buildTrustedHostValidator()
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

    /**
     * Builds a HostValidator that only allows known, trusted Android Auto hosts.
     *
     * This validator uses SHA-256 certificate fingerprints to verify that connecting
     * hosts are legitimate Google Android Auto/Automotive applications. This prevents
     * malicious apps from impersonating Android Auto hosts.
     *
     * The signatures are the SHA-256 digests of the DER encoding of the host certificates,
     * formatted as lowercase hex values separated by colons.
     *
     * @return A [HostValidator] configured to accept only trusted hosts
     */
    private fun buildTrustedHostValidator(): HostValidator {
        return HostValidator.Builder(applicationContext)
            // Google Android Auto phone app (com.google.android.projection.gearhead)
            // This is the main Android Auto app that runs on phones and connects to car head units.
            // Primary signing certificate (gearhead key)
            .addAllowedHost(
                PACKAGE_ANDROID_AUTO,
                SIGNATURE_ANDROID_AUTO_PRIMARY
            )
            // Secondary signing certificate (Android key - used for signature rotation)
            .addAllowedHost(
                PACKAGE_ANDROID_AUTO,
                SIGNATURE_ANDROID_AUTO_SECONDARY
            )
            // Google Automotive App Host (com.google.android.apps.automotive.templates.host)
            // This runs on Android Automotive OS vehicles with Google Play Services built-in.
            .addAllowedHost(
                PACKAGE_AUTOMOTIVE_TEMPLATES_HOST,
                SIGNATURE_AUTOMOTIVE_TEMPLATES_HOST
            )
            .build()
    }

    companion object {
        private const val TAG = "FlockYouCarAppService"

        // ============================================================================
        // Android Auto Host Package Names
        // ============================================================================

        /**
         * Google Android Auto phone app package name.
         * This is the primary Android Auto app that runs on Android phones and mirrors
         * the car app interface to compatible head units via USB or wireless connection.
         */
        private const val PACKAGE_ANDROID_AUTO = "com.google.android.projection.gearhead"

        /**
         * Google Automotive App Host package name.
         * This runs natively on Android Automotive OS vehicles (like Polestar, Volvo, etc.)
         * that have Google Play Services built into the vehicle's infotainment system.
         */
        private const val PACKAGE_AUTOMOTIVE_TEMPLATES_HOST =
            "com.google.android.apps.automotive.templates.host"

        // ============================================================================
        // SHA-256 Certificate Signatures
        // ============================================================================
        // These signatures are the SHA-256 digests of the DER encoding of each host's
        // signing certificate. They are formatted as 32 lowercase 2-digit hexadecimal
        // values separated by colons.
        //
        // Sources for these signatures:
        // - APKMirror certificate verification pages
        // - Android Auto APK signature analysis
        //
        // Note: Apps may have multiple valid signatures due to Android's signature
        // rotation scheme (APK Signature Scheme v3). When signature rotation is used,
        // both old and new certificates may be valid.
        // ============================================================================

        /**
         * Primary SHA-256 signature for Google Android Auto (gearhead key).
         * Certificate: CN=gearhead, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US
         * Raw hex: fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf
         */
        private const val SIGNATURE_ANDROID_AUTO_PRIMARY =
            "fd:b0:0c:43:db:de:8b:51:cb:31:2a:a8:1d:3b:5f:a1:" +
            "77:13:ad:b9:4b:28:f5:98:d7:7f:8e:b8:9d:ac:ee:df"

        /**
         * Secondary SHA-256 signature for Google Android Auto (Android key).
         * Certificate: CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US
         * Raw hex: 1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295
         * This is the rotated signing key used in newer versions of Android Auto.
         */
        private const val SIGNATURE_ANDROID_AUTO_SECONDARY =
            "1c:a8:dc:c0:be:d3:cb:d8:72:d2:cb:79:12:00:c0:29:" +
            "2c:a9:97:57:68:a8:2d:67:6b:8b:42:4f:b6:5b:52:95"

        /**
         * SHA-256 signature for Google Automotive App Host.
         * Certificate: CN=com_google_android_apps_automotive_templates_host_key, OU=Android,
         *              O=Google Inc., L=Mountain View, ST=California, C=US
         * Raw hex: dd66deaf312d8daec7adbe85a218ecc8c64f3b152f9b5998d5b29300c2623f61
         */
        private const val SIGNATURE_AUTOMOTIVE_TEMPLATES_HOST =
            "dd:66:de:af:31:2d:8d:ae:c7:ad:be:85:a2:18:ec:c8:" +
            "c6:4f:3b:15:2f:9b:59:98:d5:b2:93:00:c2:62:3f:61"
    }
}

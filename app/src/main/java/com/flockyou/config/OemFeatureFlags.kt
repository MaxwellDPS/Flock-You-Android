package com.flockyou.config

import com.flockyou.BuildConfig

/**
 * OEM Feature Flags
 *
 * Provides compile-time feature flags that allow OEMs to enable/disable major features
 * via gradle.properties configuration. These flags are baked into the APK at build time
 * and cannot be changed at runtime.
 *
 * ## Usage
 *
 * Check feature availability before accessing feature-specific code:
 * ```kotlin
 * if (OemFeatureFlags.FLIPPER_ZERO_ENABLED) {
 *     // Show Flipper Zero settings
 * }
 * ```
 *
 * ## Configuration
 *
 * For OEM builds, features can be configured in gradle.properties:
 * ```properties
 * OEM_FEATURE_FLIPPER_ENABLED=false
 * OEM_FEATURE_ULTRASONIC_ENABLED=true
 * ```
 *
 * Or via command line:
 * ```
 * ./gradlew assembleOemRelease -POEM_FEATURE_FLIPPER_ENABLED=false
 * ```
 *
 * ## Flavor Defaults
 *
 * - sideload: All features enabled (standard Play Store / APK install)
 * - system: All features enabled (system privileged app)
 * - oem: Configurable via gradle.properties (defaults to all enabled)
 *
 * ## Features
 *
 * - FLIPPER_ZERO_ENABLED: Flipper Zero integration for extended RF scanning
 * - ULTRASONIC_DETECTION_ENABLED: Ultrasonic/audio-based detection
 * - ANDROID_AUTO_ENABLED: Android Auto car integration
 * - NUKE_ENABLED: Emergency data wipe features
 * - AI_ENABLED: On-device AI analysis features
 * - TOR_ENABLED: Tor network integration for anonymous updates
 * - MAP_ENABLED: Map display and geolocation features
 */
object OemFeatureFlags {

    /**
     * Flipper Zero integration.
     * When enabled, allows connecting to Flipper Zero devices for extended RF scanning
     * capabilities including SubGHz, NFC, and IR detection.
     */
    val FLIPPER_ZERO_ENABLED: Boolean
        get() = BuildConfig.FEATURE_FLIPPER_ENABLED

    /**
     * Ultrasonic detection.
     * When enabled, allows detecting ultrasonic audio signals that may indicate
     * hidden surveillance devices or tracking beacons.
     */
    val ULTRASONIC_DETECTION_ENABLED: Boolean
        get() = BuildConfig.FEATURE_ULTRASONIC_ENABLED

    /**
     * Android Auto support.
     * When enabled, provides a simplified interface for use with Android Auto
     * in vehicles, showing detection alerts while driving.
     */
    val ANDROID_AUTO_ENABLED: Boolean
        get() = BuildConfig.FEATURE_ANDROID_AUTO_ENABLED

    /**
     * Nuke/emergency wipe features.
     * When enabled, allows configuring emergency data destruction triggers
     * including duress PIN, dead man's switch, and geofence triggers.
     */
    val NUKE_ENABLED: Boolean
        get() = BuildConfig.FEATURE_NUKE_ENABLED

    /**
     * AI-powered analysis.
     * When enabled, allows using on-device AI models (Gemini Nano, MediaPipe)
     * for intelligent threat analysis and false positive reduction.
     */
    val AI_ENABLED: Boolean
        get() = BuildConfig.FEATURE_AI_ENABLED

    /**
     * Tor network integration.
     * When enabled, allows routing update checks and pattern downloads
     * through the Tor network for enhanced privacy.
     */
    val TOR_ENABLED: Boolean
        get() = BuildConfig.FEATURE_TOR_ENABLED

    /**
     * Map features.
     * When enabled, allows displaying detections on a map and using
     * location-based analysis features.
     */
    val MAP_ENABLED: Boolean
        get() = BuildConfig.FEATURE_MAP_ENABLED

    /**
     * Returns a list of all enabled features for diagnostic purposes.
     */
    fun getEnabledFeatures(): List<String> = buildList {
        if (FLIPPER_ZERO_ENABLED) add("Flipper Zero")
        if (ULTRASONIC_DETECTION_ENABLED) add("Ultrasonic Detection")
        if (ANDROID_AUTO_ENABLED) add("Android Auto")
        if (NUKE_ENABLED) add("Nuke")
        if (AI_ENABLED) add("AI")
        if (TOR_ENABLED) add("Tor")
        if (MAP_ENABLED) add("Map")
    }

    /**
     * Returns a list of all disabled features for diagnostic purposes.
     */
    fun getDisabledFeatures(): List<String> = buildList {
        if (!FLIPPER_ZERO_ENABLED) add("Flipper Zero")
        if (!ULTRASONIC_DETECTION_ENABLED) add("Ultrasonic Detection")
        if (!ANDROID_AUTO_ENABLED) add("Android Auto")
        if (!NUKE_ENABLED) add("Nuke")
        if (!AI_ENABLED) add("AI")
        if (!TOR_ENABLED) add("Tor")
        if (!MAP_ENABLED) add("Map")
    }

    /**
     * Returns true if all features are enabled.
     */
    fun allFeaturesEnabled(): Boolean =
        FLIPPER_ZERO_ENABLED &&
        ULTRASONIC_DETECTION_ENABLED &&
        ANDROID_AUTO_ENABLED &&
        NUKE_ENABLED &&
        AI_ENABLED &&
        TOR_ENABLED &&
        MAP_ENABLED

    /**
     * Returns a debug string showing all feature states.
     */
    fun toDebugString(): String = buildString {
        appendLine("OEM Feature Flags:")
        appendLine("  Flipper Zero: $FLIPPER_ZERO_ENABLED")
        appendLine("  Ultrasonic Detection: $ULTRASONIC_DETECTION_ENABLED")
        appendLine("  Android Auto: $ANDROID_AUTO_ENABLED")
        appendLine("  Nuke: $NUKE_ENABLED")
        appendLine("  AI: $AI_ENABLED")
        appendLine("  Tor: $TOR_ENABLED")
        appendLine("  Map: $MAP_ENABLED")
    }
}

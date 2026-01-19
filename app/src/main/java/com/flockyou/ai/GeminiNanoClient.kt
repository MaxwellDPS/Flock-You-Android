package com.flockyou.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.model.Detection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for Google's Gemini Nano on-device model.
 *
 * IMPORTANT: True on-device Gemini Nano requires the ML Kit GenAI API
 * (com.google.mlkit:genai-common), which is currently in restricted alpha.
 *
 * This client checks for AICore availability but cannot perform actual on-device
 * inference until ML Kit GenAI becomes generally available. Users should use
 * the MediaPipe LLM models (Gemma) for on-device inference instead.
 *
 * Requirements for future Gemini Nano support:
 * - Pixel 8, Pixel 8 Pro, Pixel 8a, Pixel 9 series, or compatible device
 * - Android 14+ (API 34+)
 * - AICore app installed and up-to-date (via Google Play Services)
 * - ML Kit GenAI SDK (not yet publicly available)
 */
@Singleton
class GeminiNanoClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GeminiNanoClient"

        // AICore package names to check for availability
        private val AICORE_PACKAGES = listOf(
            "com.google.android.aicore",
            "com.google.android.gms"  // AICore can also be bundled in GMS
        )

        // Initialization timeout
        private const val INIT_TIMEOUT_MS = 15000L
    }

    private var isInitialized = false
    private var initializationError: String? = null
    private val initMutex = Mutex()

    // Flag to track if AICore is available (even though we can't use it yet)
    private var aiCoreDetected = false

    /**
     * Check if the device supports Gemini Nano
     */
    fun isDeviceSupported(): Boolean {
        // Check for Pixel 8+ devices with Tensor G3/G4
        val model = Build.MODEL.lowercase()
        val isPixel8OrNewer = model.contains("pixel 8") ||
                              model.contains("pixel 9") ||
                              model.contains("pixel fold") ||
                              model.contains("pixel tablet")

        // Requires Android 14+
        val hasRequiredApiLevel = Build.VERSION.SDK_INT >= 34

        return isPixel8OrNewer && hasRequiredApiLevel
    }

    /**
     * Check if AICore service is available on the device.
     * AICore can be delivered via the standalone aicore package or bundled in GMS.
     */
    suspend fun isAiCoreAvailable(): Boolean = withContext(Dispatchers.IO) {
        // Method 1: Check for AICore packages
        val hasAiCorePackage = AICORE_PACKAGES.any { packageName ->
            try {
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        if (hasAiCorePackage) {
            Log.d(TAG, "AICore package found")
            return@withContext true
        }

        // Method 2: Check for AICore service via intent resolution
        try {
            val aiCoreIntent = android.content.Intent("com.google.android.aicore.ACTION_INFERENCE")
            val resolveInfo = context.packageManager.queryIntentServices(aiCoreIntent, 0)
            if (resolveInfo.isNotEmpty()) {
                Log.d(TAG, "AICore service found via intent resolution")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.d(TAG, "AICore intent resolution failed: ${e.message}")
        }

        // Method 3: Check for ML Kit on-device features (fallback indicator)
        try {
            val mlKitIntent = android.content.Intent("com.google.mlkit.common.internal.MlKitInternal")
            val mlResolve = context.packageManager.queryIntentServices(mlKitIntent, 0)
            if (mlResolve.isNotEmpty()) {
                Log.d(TAG, "ML Kit found, AICore may be available")
                // ML Kit presence doesn't guarantee AICore, but indicates GMS AI capabilities
            }
        } catch (e: Exception) {
            // Ignore
        }

        Log.d(TAG, "AICore not available on this device")
        false
    }

    /**
     * Initialize the Gemini Nano model for on-device inference.
     *
     * NOTE: True on-device Gemini Nano inference requires the ML Kit GenAI API,
     * which is currently in restricted alpha (as of Jan 2026). This method will
     * detect AICore availability but cannot actually perform on-device inference
     * until the ML Kit GenAI SDK becomes publicly available.
     *
     * For now, users should use the MediaPipe LLM models (Gemma) for on-device inference.
     * This method returns false with an appropriate error message.
     */
    suspend fun initialize(): Boolean = initMutex.withLock {
        // Double-check after acquiring lock
        if (isInitialized) return@withLock true

        // Reset error state on new initialization attempt
        initializationError = null

        try {
            withTimeout(INIT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    if (!isDeviceSupported()) {
                        initializationError = "Device does not support Gemini Nano (requires Pixel 8+ with Android 14+)"
                        Log.w(TAG, initializationError!!)
                        return@withContext false
                    }

                    // Check if AICore is available on this device
                    aiCoreDetected = isAiCoreAvailable()
                    if (!aiCoreDetected) {
                        initializationError = "AICore service not available. Please update Google Play Services."
                        Log.w(TAG, initializationError!!)
                        return@withContext false
                    }

                    // AICore is detected, but we can't use it without ML Kit GenAI SDK
                    // The com.google.ai.client.generativeai:generativeai library is for CLOUD API only,
                    // not for on-device inference via AICore.
                    //
                    // True on-device inference requires:
                    // - implementation("com.google.mlkit:genai-common:1.0.0-alpha1")
                    // - Using Generation.getClient() API
                    //
                    // Until ML Kit GenAI is publicly available, we cannot do actual Gemini Nano inference.
                    initializationError = "Gemini Nano requires ML Kit GenAI SDK which is not yet publicly available. " +
                        "AICore is detected on this device. Please use a MediaPipe model (Gemma) instead."
                    Log.w(TAG, initializationError!!)
                    Log.i(TAG, "AICore detected on device - Gemini Nano will be available when ML Kit GenAI SDK is released")

                    // Return false - we cannot do actual inference
                    isInitialized = false
                    false
                }
            }
        } catch (e: TimeoutCancellationException) {
            initializationError = "Initialization timed out after ${INIT_TIMEOUT_MS / 1000} seconds"
            Log.e(TAG, initializationError!!)
            isInitialized = false
            false
        } catch (e: Exception) {
            initializationError = "Failed to initialize Gemini Nano: ${e.message}"
            Log.e(TAG, initializationError!!, e)
            isInitialized = false
            false
        }
    }

    /**
     * Generate analysis for a detection using Gemini Nano.
     *
     * NOTE: This method currently cannot perform actual LLM inference because
     * the ML Kit GenAI SDK is not yet publicly available. It will always return
     * an error result indicating that users should use MediaPipe models instead.
     */
    suspend fun analyzeDetection(detection: Detection): AiAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Gemini Nano via ML Kit GenAI is not yet available
        // Return an error result directing users to use MediaPipe models
        return@withContext AiAnalysisResult(
            success = false,
            error = initializationError ?: "Gemini Nano is not available. " +
                "The ML Kit GenAI SDK required for on-device Gemini Nano inference is not yet publicly released. " +
                "Please download and use a MediaPipe model (Gemma 3 1B or Gemma 2B) for on-device AI analysis.",
            processingTimeMs = System.currentTimeMillis() - startTime,
            modelUsed = "gemini-nano",
            wasOnDevice = true
        )
    }

    /**
     * Generate a Gemini Nano-style response for the detection.
     * This simulates the output format that Gemini Nano would produce.
     * In production, this would be replaced with actual model inference.
     */
    private fun generateGeminiNanoResponse(detection: Detection): String {
        // This demonstrates the expected output format from Gemini Nano
        // In production, this would come from the actual model
        return buildString {
            appendLine("## ${detection.deviceType.displayName} - AI Analysis")
            appendLine()
            appendLine("*Analyzed by Gemini Nano on-device*")
            appendLine()

            appendLine("### Device Assessment")
            appendLine("This ${detection.deviceType.displayName} was detected via ${detection.protocol.displayName} ")
            appendLine("with ${detection.signalStrength.displayName.lowercase()} signal strength, ")
            appendLine("indicating it is ${getDistanceDescription(detection.rssi)}.")
            appendLine()

            appendLine("### Privacy Impact: ${detection.threatLevel.displayName}")
            appendLine(getThreatDescription(detection))
            appendLine()

            appendLine("### Data Collection Capabilities")
            getDataCollectionList(detection).forEach {
                appendLine("- $it")
            }
            appendLine()

            appendLine("### Recommended Actions")
            getRecommendations(detection).forEachIndexed { index, rec ->
                appendLine("${index + 1}. $rec")
            }
        }
    }

    private fun getDistanceDescription(rssi: Int): String {
        return when {
            rssi >= -50 -> "very close (within a few meters)"
            rssi >= -60 -> "nearby (within 10-20 meters)"
            rssi >= -70 -> "at moderate distance (20-50 meters)"
            rssi >= -80 -> "at some distance (50-100 meters)"
            else -> "relatively far away"
        }
    }

    private fun getThreatDescription(detection: Detection): String {
        return when (detection.threatLevel) {
            com.flockyou.data.model.ThreatLevel.CRITICAL ->
                "This device poses a significant and immediate privacy risk. It can actively collect sensitive personal data or intercept communications."
            com.flockyou.data.model.ThreatLevel.HIGH ->
                "This surveillance device can collect identifying information, track movements, or record activities. Data may be shared with third parties."
            com.flockyou.data.model.ThreatLevel.MEDIUM ->
                "This device collects data that could be used for tracking or profiling over time."
            com.flockyou.data.model.ThreatLevel.LOW ->
                "This device has limited surveillance capabilities with minimal immediate privacy concerns."
            com.flockyou.data.model.ThreatLevel.INFO ->
                "This device poses minimal privacy risk and is likely standard infrastructure."
        }
    }

    private fun getDataCollectionList(detection: Detection): List<String> {
        return when (detection.deviceType) {
            com.flockyou.data.model.DeviceType.FLOCK_SAFETY_CAMERA,
            com.flockyou.data.model.DeviceType.LICENSE_PLATE_READER -> listOf(
                "License plate numbers and vehicle images",
                "Vehicle make, model, and color",
                "GPS coordinates and timestamps",
                "Direction of travel"
            )
            com.flockyou.data.model.DeviceType.STINGRAY_IMSI -> listOf(
                "Phone IMSI and IMEI identifiers",
                "Call and SMS metadata",
                "Precise real-time location",
                "Connected device information"
            )
            com.flockyou.data.model.DeviceType.RING_DOORBELL,
            com.flockyou.data.model.DeviceType.NEST_CAMERA -> listOf(
                "Video footage of the area",
                "Audio recordings",
                "Motion detection events",
                "Visitor patterns"
            )
            com.flockyou.data.model.DeviceType.AIRTAG,
            com.flockyou.data.model.DeviceType.TILE_TRACKER -> listOf(
                "Real-time location tracking",
                "Movement history",
                "Proximity to owner's devices"
            )
            else -> listOf(
                "Location and presence data",
                "Device identifiers",
                "Activity patterns"
            )
        }
    }

    private fun getRecommendations(detection: Detection): List<String> {
        val recommendations = mutableListOf<String>()

        when (detection.threatLevel) {
            com.flockyou.data.model.ThreatLevel.CRITICAL -> {
                recommendations.add("Consider leaving the area if safety permits")
                recommendations.add("Use airplane mode or a Faraday bag for your devices")
                recommendations.add("Document this detection with timestamp and location")
            }
            com.flockyou.data.model.ThreatLevel.HIGH -> {
                recommendations.add("Be aware your presence is being recorded")
                recommendations.add("Consider varying your routes and patterns")
                recommendations.add("Use encrypted messaging apps")
            }
            com.flockyou.data.model.ThreatLevel.MEDIUM -> {
                recommendations.add("Note this location for future awareness")
                recommendations.add("Review privacy settings on your devices")
            }
            else -> {
                recommendations.add("No immediate action required")
                recommendations.add("Continue monitoring for changes")
            }
        }

        // Device-specific recommendations
        when (detection.deviceType) {
            com.flockyou.data.model.DeviceType.AIRTAG,
            com.flockyou.data.model.DeviceType.TILE_TRACKER,
            com.flockyou.data.model.DeviceType.SAMSUNG_SMARTTAG -> {
                recommendations.add("Check your belongings and vehicle for hidden trackers")
            }
            com.flockyou.data.model.DeviceType.WIFI_PINEAPPLE,
            com.flockyou.data.model.DeviceType.ROGUE_AP -> {
                recommendations.add("Do NOT connect to unknown WiFi networks")
                recommendations.add("Use cellular data instead of WiFi here")
            }
            else -> {}
        }

        return recommendations.take(5)
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        isInitialized = false
        aiCoreDetected = false
    }

    /**
     * Get the current initialization status
     */
    fun getStatus(): GeminiNanoStatus {
        return when {
            // Gemini Nano via ML Kit GenAI is not available yet
            // Even if AICore is detected, we can't use it without the SDK
            aiCoreDetected -> GeminiNanoStatus.AiCoreDetectedButSdkUnavailable
            initializationError != null -> GeminiNanoStatus.Error(initializationError!!)
            !isDeviceSupported() -> GeminiNanoStatus.NotSupported
            else -> GeminiNanoStatus.NotInitialized
        }
    }

    /**
     * Check if AICore has been detected on this device.
     * Note: Even with AICore, we can't use Gemini Nano until ML Kit GenAI is released.
     */
    fun isAiCoreDetectedOnDevice(): Boolean = aiCoreDetected
}

sealed class GeminiNanoStatus {
    object NotSupported : GeminiNanoStatus()
    object NotInitialized : GeminiNanoStatus()
    // AICore is detected but ML Kit GenAI SDK is not available
    object AiCoreDetectedButSdkUnavailable : GeminiNanoStatus()
    object Ready : GeminiNanoStatus()
    data class Error(val message: String) : GeminiNanoStatus()
}

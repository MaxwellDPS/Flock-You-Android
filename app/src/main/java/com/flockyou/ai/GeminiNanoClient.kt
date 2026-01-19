package com.flockyou.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.model.Detection
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for Google's Gemini Nano on-device model using ML Kit GenAI Prompt API.
 *
 * Gemini Nano runs entirely on-device via Android's AICore service on Pixel 8+ devices.
 * No data is sent to the cloud - all inference happens locally using the device's NPU.
 *
 * Requirements:
 * - Pixel 8, Pixel 8 Pro, Pixel 8a, Pixel 9 series, or compatible device
 * - Android 14+ (API 34+)
 * - AICore app installed and up-to-date (via Google Play Services)
 * - Device with locked bootloader (unlocked bootloaders not supported)
 *
 * Note: This API is offered in alpha and is not subject to any SLA or deprecation policy.
 * Changes may be made to this API that break backward compatibility.
 *
 * The model is managed by Google Play Services and may need to be downloaded on first use.
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

        // Timeouts
        private const val INIT_TIMEOUT_MS = 30000L
        private const val INFERENCE_TIMEOUT_MS = 60000L
        private const val DOWNLOAD_TIMEOUT_MS = 600000L // 10 minutes for model download
    }

    private var generativeModel: GenerativeModel? = null
    private var isInitialized = false
    private var initializationError: String? = null
    private val initMutex = Mutex()

    // Model status tracking
    private val _modelStatus = MutableStateFlow<GeminiNanoStatus>(GeminiNanoStatus.NotInitialized)
    val modelStatus: StateFlow<GeminiNanoStatus> = _modelStatus.asStateFlow()

    // Download progress tracking
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

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

        Log.d(TAG, "AICore not available on this device")
        false
    }

    /**
     * Check Gemini Nano model availability using ML Kit GenAI API.
     * Returns the feature status: AVAILABLE, DOWNLOADABLE, DOWNLOADING, or UNAVAILABLE.
     */
    suspend fun checkModelAvailability(): Int = withContext(Dispatchers.IO) {
        try {
            val client = Generation.getClient()
            val status = client.checkStatus()
            Log.d(TAG, "Gemini Nano model status: $status")
            status
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check model availability: ${e.message}")
            FeatureStatus.UNAVAILABLE
        }
    }

    /**
     * Download the Gemini Nano model if it's downloadable.
     * Returns true if the model is ready to use after this call.
     */
    suspend fun downloadModel(onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = Generation.getClient()
            val status = client.checkStatus()

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    Log.i(TAG, "Gemini Nano model is already available")
                    _downloadProgress.value = 100
                    onProgress(100)
                    return@withContext true
                }
                FeatureStatus.DOWNLOADING -> {
                    Log.i(TAG, "Gemini Nano model is currently downloading")
                    _modelStatus.value = GeminiNanoStatus.Downloading(0)
                    // Collect download status from the download flow
                    return@withContext collectDownloadStatus(client, onProgress)
                }
                FeatureStatus.DOWNLOADABLE -> {
                    Log.i(TAG, "Starting Gemini Nano model download")
                    _modelStatus.value = GeminiNanoStatus.Downloading(0)
                    _downloadProgress.value = 0
                    onProgress(0)
                    return@withContext startDownload(client, onProgress)
                }
                FeatureStatus.UNAVAILABLE -> {
                    Log.w(TAG, "Gemini Nano model is unavailable on this device")
                    _modelStatus.value = GeminiNanoStatus.NotSupported
                    return@withContext false
                }
                else -> {
                    Log.w(TAG, "Unknown model status: $status")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: ${e.message}", e)
            _modelStatus.value = GeminiNanoStatus.Error(e.message ?: "Download failed")
            false
        }
    }

    private suspend fun startDownload(client: GenerativeModel, onProgress: (Int) -> Unit): Boolean {
        return try {
            // Use the download() method which returns a Flow<DownloadStatus>
            client.download()
                .catch { e ->
                    Log.e(TAG, "Download failed: ${e.message}", e)
                    _modelStatus.value = GeminiNanoStatus.Error(e.message ?: "Download failed")
                }
                .collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            Log.d(TAG, "Download started")
                            _modelStatus.value = GeminiNanoStatus.Downloading(0)
                            onProgress(0)
                        }
                        is DownloadStatus.DownloadProgress -> {
                            // Calculate rough percentage from bytes
                            val bytes = status.totalBytesDownloaded
                            // Assume ~500MB for Gemini Nano
                            val progress = ((bytes * 100) / (500 * 1024 * 1024)).toInt().coerceIn(0, 99)
                            _downloadProgress.value = progress
                            _modelStatus.value = GeminiNanoStatus.Downloading(progress)
                            onProgress(progress)
                            Log.d(TAG, "Download progress: ${bytes / 1024 / 1024}MB")
                        }
                        DownloadStatus.DownloadCompleted -> {
                            Log.i(TAG, "Model download completed")
                            _downloadProgress.value = 100
                            onProgress(100)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            Log.e(TAG, "Download failed: ${status.e.message}")
                            _modelStatus.value = GeminiNanoStatus.Error(status.e.message ?: "Download failed")
                        }
                    }
                }

            // Check final status
            val finalStatus = client.checkStatus()
            finalStatus == FeatureStatus.AVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: ${e.message}", e)
            _modelStatus.value = GeminiNanoStatus.Error(e.message ?: "Download failed")
            false
        }
    }

    private suspend fun collectDownloadStatus(client: GenerativeModel, onProgress: (Int) -> Unit): Boolean {
        return try {
            // Collect status from download flow
            client.download()
                .catch { e ->
                    Log.e(TAG, "Error collecting download status: ${e.message}", e)
                }
                .collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            _modelStatus.value = GeminiNanoStatus.Downloading(0)
                            onProgress(0)
                        }
                        is DownloadStatus.DownloadProgress -> {
                            val bytes = status.totalBytesDownloaded
                            val progress = ((bytes * 100) / (500 * 1024 * 1024)).toInt().coerceIn(0, 99)
                            _downloadProgress.value = progress
                            _modelStatus.value = GeminiNanoStatus.Downloading(progress)
                            onProgress(progress)
                        }
                        DownloadStatus.DownloadCompleted -> {
                            _downloadProgress.value = 100
                            onProgress(100)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            _modelStatus.value = GeminiNanoStatus.Error(status.e.message ?: "Download failed")
                        }
                    }
                }

            val finalStatus = client.checkStatus()
            finalStatus == FeatureStatus.AVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for download: ${e.message}", e)
            false
        }
    }

    /**
     * Initialize the Gemini Nano model for on-device inference using ML Kit GenAI API.
     */
    suspend fun initialize(): Boolean = initMutex.withLock {
        // Double-check after acquiring lock
        if (isInitialized && generativeModel != null) {
            Log.d(TAG, "Already initialized")
            return@withLock true
        }

        // Reset error state on new initialization attempt
        initializationError = null
        _modelStatus.value = GeminiNanoStatus.Initializing

        try {
            withTimeout(INIT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    if (!isDeviceSupported()) {
                        initializationError = "Device does not support Gemini Nano (requires Pixel 8+ with Android 14+)"
                        Log.w(TAG, initializationError!!)
                        _modelStatus.value = GeminiNanoStatus.NotSupported
                        return@withContext false
                    }

                    // Get the ML Kit GenAI client
                    val client = Generation.getClient()
                    val status = client.checkStatus()
                    Log.d(TAG, "Model status: $status")

                    when (status) {
                        FeatureStatus.AVAILABLE -> {
                            // Warm up the model for faster first inference
                            try {
                                client.warmup()
                                Log.d(TAG, "Model warmup completed")
                            } catch (e: Exception) {
                                Log.w(TAG, "Model warmup failed (non-critical): ${e.message}")
                            }

                            generativeModel = client
                            isInitialized = true
                            _modelStatus.value = GeminiNanoStatus.Ready
                            Log.i(TAG, "Gemini Nano initialized successfully via ML Kit GenAI")
                            return@withContext true
                        }
                        FeatureStatus.DOWNLOADABLE -> {
                            initializationError = "Gemini Nano model needs to be downloaded first"
                            Log.w(TAG, initializationError!!)
                            _modelStatus.value = GeminiNanoStatus.NeedsDownload
                            return@withContext false
                        }
                        FeatureStatus.DOWNLOADING -> {
                            initializationError = "Gemini Nano model is currently downloading"
                            Log.w(TAG, initializationError!!)
                            _modelStatus.value = GeminiNanoStatus.Downloading(0)
                            return@withContext false
                        }
                        FeatureStatus.UNAVAILABLE -> {
                            initializationError = "Gemini Nano is not available on this device. " +
                                "This may be due to unsupported hardware, unlocked bootloader, or missing AICore."
                            Log.w(TAG, initializationError!!)
                            _modelStatus.value = GeminiNanoStatus.NotSupported
                            return@withContext false
                        }
                        else -> {
                            initializationError = "Unknown model status: $status"
                            Log.w(TAG, initializationError!!)
                            _modelStatus.value = GeminiNanoStatus.Error(initializationError!!)
                            return@withContext false
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            initializationError = "Initialization timed out after ${INIT_TIMEOUT_MS / 1000} seconds"
            Log.e(TAG, initializationError!!)
            _modelStatus.value = GeminiNanoStatus.Error(initializationError!!)
            isInitialized = false
            false
        } catch (e: Exception) {
            initializationError = "Failed to initialize Gemini Nano: ${e.message}"
            Log.e(TAG, initializationError!!, e)
            _modelStatus.value = GeminiNanoStatus.Error(initializationError!!)
            isInitialized = false
            false
        }
    }

    /**
     * Generate analysis for a detection using Gemini Nano.
     * All processing happens on-device via the NPU.
     */
    suspend fun analyzeDetection(detection: Detection): AiAnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val model = generativeModel
        if (!isInitialized || model == null) {
            return@withContext AiAnalysisResult(
                success = false,
                error = initializationError ?: "Gemini Nano not initialized. Please download and initialize the model first.",
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = "gemini-nano"
            )
        }

        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                // Build the prompt for surveillance device analysis
                val prompt = buildAnalysisPrompt(detection)
                Log.d(TAG, "Generating analysis with Gemini Nano (prompt length: ${prompt.length})")

                // Generate content using ML Kit GenAI
                val response = model.generateContent(prompt)

                // Extract text from response - access via candidates list
                val analysisText = response.candidates.firstOrNull()?.text ?: ""

                if (analysisText.isBlank()) {
                    Log.w(TAG, "Gemini Nano returned empty response")
                    return@withTimeout AiAnalysisResult(
                        success = false,
                        error = "Model returned empty response",
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        modelUsed = "gemini-nano"
                    )
                }

                Log.i(TAG, "Gemini Nano analysis completed (${analysisText.length} chars)")

                AiAnalysisResult(
                    success = true,
                    analysis = formatAnalysisResponse(analysisText, detection),
                    confidence = 0.9f,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    modelUsed = "gemini-nano",
                    wasOnDevice = true
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Inference timed out after ${INFERENCE_TIMEOUT_MS / 1000} seconds")
            AiAnalysisResult(
                success = false,
                error = "Analysis timed out",
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = "gemini-nano"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during Gemini Nano inference", e)
            AiAnalysisResult(
                success = false,
                error = "Inference failed: ${e.message}",
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = "gemini-nano"
            )
        }
    }

    /**
     * Build a prompt for surveillance device analysis
     */
    private fun buildAnalysisPrompt(detection: Detection): String {
        return """
Analyze this surveillance device detection and provide a privacy-focused assessment.

Device Information:
- Type: ${detection.deviceType.displayName}
- Protocol: ${detection.protocol.displayName}
- Signal Strength: ${detection.signalStrength.displayName} (${detection.rssi} dBm)
- Threat Level: ${detection.threatLevel.displayName}
${detection.manufacturer?.let { "- Manufacturer: $it" } ?: ""}
${detection.ssid?.let { "- Network Name: $it" } ?: ""}
${detection.deviceName?.let { "- Device Name: $it" } ?: ""}

Please provide:
1. A brief explanation of what this device does and its surveillance capabilities
2. What data it can collect about you
3. The privacy implications
4. Recommended actions to protect your privacy

Keep the response concise and actionable.
        """.trimIndent()
    }

    /**
     * Format the analysis response with a header
     */
    private fun formatAnalysisResponse(response: String, detection: Detection): String {
        return buildString {
            appendLine("## ${detection.deviceType.displayName} - AI Analysis")
            appendLine()
            appendLine("*Analyzed by Gemini Nano on-device*")
            appendLine()
            append(response.trim())
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        generativeModel?.close()
        generativeModel = null
        isInitialized = false
        _modelStatus.value = GeminiNanoStatus.NotInitialized
    }

    /**
     * Get the current initialization status
     */
    fun getStatus(): GeminiNanoStatus = _modelStatus.value

    /**
     * Check if the model is ready for inference
     */
    fun isReady(): Boolean = isInitialized && generativeModel != null
}

sealed class GeminiNanoStatus {
    object NotSupported : GeminiNanoStatus()
    object NotInitialized : GeminiNanoStatus()
    object Initializing : GeminiNanoStatus()
    object NeedsDownload : GeminiNanoStatus()
    data class Downloading(val progress: Int) : GeminiNanoStatus()
    object Ready : GeminiNanoStatus()
    data class Error(val message: String) : GeminiNanoStatus()
}

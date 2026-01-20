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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val statusMutex = Mutex()

    // Model status tracking
    private val _modelStatus = MutableStateFlow<GeminiNanoStatus>(GeminiNanoStatus.NotInitialized)
    val modelStatus: StateFlow<GeminiNanoStatus> = _modelStatus.asStateFlow()

    // Download progress tracking
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    // Download cancellation support
    private var downloadJob: Job? = null
    private val isDownloadCancelled = AtomicBoolean(false)

    // Estimated model size for progress calculation (updated dynamically if possible)
    private var estimatedModelSizeBytes: Long = 500L * 1024 * 1024 // Default 500MB

    /**
     * Check if the device supports Gemini Nano
     */
    fun isDeviceSupported(): Boolean {
        // Check for Pixel 8+ devices with Tensor G3/G4/G5
        val model = Build.MODEL.lowercase()
        val isPixel8OrNewer = model.contains("pixel 8") ||
                              model.contains("pixel 9") ||
                              model.contains("pixel 10") ||
                              model.contains("pixel 11") ||
                              model.contains("pixel fold") ||
                              model.contains("pixel tablet")

        // Requires Android 14+
        val hasRequiredApiLevel = Build.VERSION.SDK_INT >= 34

        Log.d(TAG, "isDeviceSupported check: model=$model, isPixel8OrNewer=$isPixel8OrNewer, apiLevel=${Build.VERSION.SDK_INT}, hasRequiredApiLevel=$hasRequiredApiLevel")

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
        isDownloadCancelled.set(false)

        try {
            val client = Generation.getClient()
            val status = client.checkStatus()

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    Log.i(TAG, "Gemini Nano model is already available")
                    updateProgress(100, onProgress)
                    updateStatus(GeminiNanoStatus.Ready)
                    return@withContext true
                }
                FeatureStatus.DOWNLOADING -> {
                    Log.i(TAG, "Gemini Nano model is currently downloading")
                    updateStatus(GeminiNanoStatus.Downloading(0))
                    // Wait for the existing download to complete by polling status
                    return@withContext waitForExistingDownload(client, onProgress)
                }
                FeatureStatus.DOWNLOADABLE -> {
                    Log.i(TAG, "Starting Gemini Nano model download")
                    updateStatus(GeminiNanoStatus.Downloading(0))
                    updateProgress(0, onProgress)
                    return@withContext startDownload(client, onProgress)
                }
                FeatureStatus.UNAVAILABLE -> {
                    Log.w(TAG, "Gemini Nano model is unavailable on this device")
                    updateStatus(GeminiNanoStatus.NotSupported)
                    return@withContext false
                }
                else -> {
                    Log.w(TAG, "Unknown model status: $status")
                    return@withContext false
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Download was cancelled")
            updateStatus(GeminiNanoStatus.NotInitialized)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: ${e.message}", e)
            updateStatus(GeminiNanoStatus.Error(e.message ?: "Download failed"))
            false
        }
    }

    /**
     * Cancel an ongoing download.
     */
    fun cancelDownload() {
        isDownloadCancelled.set(true)
        downloadJob?.cancel()
        downloadJob = null
        Log.i(TAG, "Download cancellation requested")
    }

    /**
     * Check if download is currently in progress.
     */
    fun isDownloading(): Boolean = _modelStatus.value is GeminiNanoStatus.Downloading

    /**
     * Thread-safe status update.
     */
    private suspend fun updateStatus(status: GeminiNanoStatus) = statusMutex.withLock {
        _modelStatus.value = status
    }

    /**
     * Thread-safe progress update.
     */
    private fun updateProgress(progress: Int, onProgress: (Int) -> Unit) {
        _downloadProgress.value = progress
        onProgress(progress)
    }

    /**
     * Calculate download progress with dynamic model size estimation.
     */
    private fun calculateProgress(downloadedBytes: Long): Int {
        // If we've downloaded more than our estimate, update the estimate
        if (downloadedBytes > estimatedModelSizeBytes * 0.9) {
            estimatedModelSizeBytes = (downloadedBytes * 1.1).toLong()
        }
        return ((downloadedBytes * 100) / estimatedModelSizeBytes).toInt().coerceIn(0, 99)
    }

    /**
     * Wait for an existing download to complete by polling status.
     * This avoids calling download() again which could cause issues.
     */
    private suspend fun waitForExistingDownload(client: GenerativeModel, onProgress: (Int) -> Unit): Boolean {
        var lastProgress = 0
        val pollIntervalMs = 1000L
        val maxPollAttempts = (DOWNLOAD_TIMEOUT_MS / pollIntervalMs).toInt()

        repeat(maxPollAttempts) { attempt ->
            if (isDownloadCancelled.get()) {
                Log.i(TAG, "Download wait cancelled")
                return false
            }

            val status = client.checkStatus()
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    Log.i(TAG, "Model download completed")
                    updateProgress(100, onProgress)
                    updateStatus(GeminiNanoStatus.Ready)
                    return true
                }
                FeatureStatus.DOWNLOADING -> {
                    // Increment progress slowly to show activity
                    lastProgress = (lastProgress + 1).coerceAtMost(95)
                    updateProgress(lastProgress, onProgress)
                    updateStatus(GeminiNanoStatus.Downloading(lastProgress))
                }
                FeatureStatus.UNAVAILABLE -> {
                    Log.w(TAG, "Model became unavailable during download")
                    updateStatus(GeminiNanoStatus.Error("Download failed - model unavailable"))
                    return false
                }
                else -> {
                    Log.w(TAG, "Unexpected status during download wait: $status")
                }
            }

            kotlinx.coroutines.delay(pollIntervalMs)
        }

        Log.e(TAG, "Download wait timed out")
        updateStatus(GeminiNanoStatus.Error("Download timed out"))
        return false
    }

    private suspend fun startDownload(client: GenerativeModel, onProgress: (Int) -> Unit): Boolean {
        var downloadSucceeded = false

        return try {
            withTimeout(DOWNLOAD_TIMEOUT_MS) {
                // Wrap entire Flow collection in try-catch to handle collection errors
                // that .catch{} operator doesn't capture
                try {
                    client.download()
                        .catch { e ->
                            Log.e(TAG, "Download flow error: ${e.message}", e)
                            updateStatus(GeminiNanoStatus.Error(e.message ?: "Download failed"))
                            throw e  // Re-throw to exit collection
                        }
                        .collect { status ->
                            // Check for cancellation
                            if (isDownloadCancelled.get()) {
                                Log.i(TAG, "Download cancelled during collection")
                                throw CancellationException("Download cancelled by user")
                            }

                            when (status) {
                                is DownloadStatus.DownloadStarted -> {
                                    Log.d(TAG, "Download started")
                                    updateStatus(GeminiNanoStatus.Downloading(0))
                                    updateProgress(0, onProgress)
                                }
                                is DownloadStatus.DownloadProgress -> {
                                    val bytes = status.totalBytesDownloaded
                                    val progress = calculateProgress(bytes)
                                    updateStatus(GeminiNanoStatus.Downloading(progress))
                                    updateProgress(progress, onProgress)
                                    Log.d(TAG, "Download progress: ${bytes / 1024 / 1024}MB ($progress%)")
                                }
                                DownloadStatus.DownloadCompleted -> {
                                    Log.i(TAG, "Model download completed")
                                    updateProgress(100, onProgress)
                                    updateStatus(GeminiNanoStatus.Ready)
                                    downloadSucceeded = true
                                }
                                is DownloadStatus.DownloadFailed -> {
                                    Log.e(TAG, "Download failed: ${status.e.message}")
                                    updateStatus(GeminiNanoStatus.Error(status.e.message ?: "Download failed"))
                                }
                            }
                        }
                } catch (e: CancellationException) {
                    throw e  // Propagate cancellation
                } catch (e: Exception) {
                    Log.e(TAG, "Download collection error: ${e.message}", e)
                    updateStatus(GeminiNanoStatus.Error(e.message ?: "Download failed"))
                    return@withTimeout false
                }
            }

            // Verify final status
            if (downloadSucceeded) {
                val finalStatus = client.checkStatus()
                if (finalStatus == FeatureStatus.AVAILABLE) {
                    true // Successfully downloaded
                } else {
                    Log.w(TAG, "Download reported complete but status is $finalStatus")
                    // Check final status as fallback
                    val fallbackStatus = client.checkStatus()
                    if (fallbackStatus == FeatureStatus.AVAILABLE) {
                        updateProgress(100, onProgress)
                        updateStatus(GeminiNanoStatus.Ready)
                        true
                    } else {
                        false
                    }
                }
            } else {
                // Check final status as fallback
                val finalStatus = client.checkStatus()
                if (finalStatus == FeatureStatus.AVAILABLE) {
                    updateProgress(100, onProgress)
                    updateStatus(GeminiNanoStatus.Ready)
                    true
                } else {
                    false
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Download timed out after ${DOWNLOAD_TIMEOUT_MS / 1000} seconds")
            updateStatus(GeminiNanoStatus.Error("Download timed out"))
            false
        } catch (e: CancellationException) {
            Log.i(TAG, "Download was cancelled")
            updateStatus(GeminiNanoStatus.NotInitialized)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: ${e.message}", e)
            updateStatus(GeminiNanoStatus.Error(e.message ?: "Download failed"))
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
        updateStatus(GeminiNanoStatus.Initializing)

        try {
            val result = withTimeout(INIT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    if (!isDeviceSupported()) {
                        initializationError = "Device does not support Gemini Nano (requires Pixel 8+ with Android 14+)"
                        Log.w(TAG, initializationError!!)
                        updateStatus(GeminiNanoStatus.NotSupported)
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
                            updateStatus(GeminiNanoStatus.Ready)
                            Log.i(TAG, "Gemini Nano initialized successfully via ML Kit GenAI")
                            return@withContext true
                        }
                        FeatureStatus.DOWNLOADABLE -> {
                            // Don't auto-trigger download during init - let the UI handle it
                            Log.i(TAG, "Gemini Nano model needs to be downloaded")
                            updateStatus(GeminiNanoStatus.NeedsDownload)
                            initializationError = "Gemini Nano model needs to be downloaded first"
                            return@withContext false
                        }
                        FeatureStatus.DOWNLOADING -> {
                            initializationError = "Gemini Nano model is currently downloading"
                            Log.i(TAG, initializationError!!)
                            updateStatus(GeminiNanoStatus.Downloading(0))
                            return@withContext false
                        }
                        FeatureStatus.UNAVAILABLE -> {
                            initializationError = "Gemini Nano is not available on this device. " +
                                "This may be due to unsupported hardware, unlocked bootloader, or missing AICore."
                            Log.w(TAG, initializationError!!)
                            updateStatus(GeminiNanoStatus.NotSupported)
                            return@withContext false
                        }
                        else -> {
                            initializationError = "Unknown model status: $status"
                            Log.w(TAG, initializationError!!)
                            updateStatus(GeminiNanoStatus.Error(initializationError!!))
                            return@withContext false
                        }
                    }
                }
            }
            // Return the result from the withTimeout block
            return@withLock result
        } catch (e: TimeoutCancellationException) {
            initializationError = "Initialization timed out after ${INIT_TIMEOUT_MS / 1000} seconds"
            Log.e(TAG, initializationError!!)
            updateStatus(GeminiNanoStatus.Error(initializationError!!))
            isInitialized = false
            return@withLock false
        } catch (e: Exception) {
            initializationError = "Failed to initialize Gemini Nano: ${e.message}"
            Log.e(TAG, initializationError!!, e)
            updateStatus(GeminiNanoStatus.Error(initializationError!!))
            isInitialized = false
            return@withLock false
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

            // Handle ML Kit GenAI specific error codes
            val errorMessage = parseMLKitError(e)

            AiAnalysisResult(
                success = false,
                error = errorMessage,
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = "gemini-nano"
            )
        }
    }

    /**
     * Parse ML Kit GenAI error codes and return user-friendly messages.
     * Based on the ML Kit GenAI documentation error codes.
     *
     * Uses regex patterns to avoid false positives from matching arbitrary numbers.
     */
    private fun parseMLKitError(e: Exception): String {
        val message = e.message ?: return "Unknown inference error"

        // Regex patterns for error codes - match "code: 601" or "ErrorCode: 601" or "error 601" patterns
        val errorCodePattern = Regex("""(?:error|code|Error|Code)[:\s]+(\d+)""", RegexOption.IGNORE_CASE)
        val errorCodeMatch = errorCodePattern.find(message)
        val errorCode = errorCodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        return when {
            // ErrorCode 601 - CONNECTION_ERROR: AICore binding failed
            errorCode == 601 || message.contains("CONNECTION_ERROR", ignoreCase = true) ->
                "Connection to AICore failed. Try updating or reinstalling Google Play Services, then reinstall the app."

            // ErrorCode 606 - PREPARATION_ERROR: Config not downloaded
            errorCode == 606 || message.contains("PREPARATION_ERROR", ignoreCase = true) ->
                "Model configuration not ready. Please wait for the model to sync (may take minutes to hours with internet connection)."

            // ErrorCode 0 with DOWNLOAD context - Network unavailable
            message.contains("DOWNLOAD_ERROR", ignoreCase = true) ->
                "Network unavailable for model download. Please check your internet connection and try again."

            // BACKGROUND_USE_BLOCKED: App not in foreground
            message.contains("BACKGROUND_USE_BLOCKED", ignoreCase = true) ->
                "Gemini Nano can only run when the app is in the foreground. Please open the app and try again."

            // BUSY: Per-app inference quota exceeded (match exact word to avoid false positives)
            Regex("""\bBUSY\b""", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
                "Inference quota exceeded. Please wait a moment before trying again (exponential backoff recommended)."

            // PER_APP_BATTERY_USE_QUOTA_EXCEEDED: Long-duration battery quota
            message.contains("BATTERY_USE_QUOTA", ignoreCase = true) ||
            message.contains("QUOTA_EXCEEDED", ignoreCase = true) ->
                "Battery usage quota exceeded. Please try again later (daily limits apply)."

            // Model not ready
            message.contains("not ready", ignoreCase = true) ||
            message.contains("not initialized", ignoreCase = true) ->
                "Model not ready. Please wait for initialization to complete."

            // Out of memory
            message.contains("OutOfMemory", ignoreCase = true) ||
            message.contains("OOM", ignoreCase = true) ->
                "Device ran out of memory. Try closing other apps and retry."

            // Generic fallback
            else -> "Inference failed: $message"
        }
    }

    /**
     * Generate a text response for a custom prompt.
     * This method is useful for pattern analysis, summarization, and other text generation tasks.
     *
     * @param prompt The prompt to send to the model
     * @return The generated text response, or null if generation failed
     */
    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        val model = generativeModel
        if (!isInitialized || model == null) {
            Log.w(TAG, "Cannot generate response: Gemini Nano not initialized")
            return@withContext null
        }

        try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                Log.d(TAG, "Generating response with Gemini Nano (prompt length: ${prompt.length})")
                val response = model.generateContent(prompt)
                val text = response.candidates.firstOrNull()?.text

                if (text.isNullOrBlank()) {
                    Log.w(TAG, "Gemini Nano returned empty response")
                    return@withTimeout null
                }

                Log.i(TAG, "Gemini Nano generated response (${text.length} chars)")
                text
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Response generation timed out")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response: ${e.message}", e)
            null
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

    /**
     * Get detailed diagnostics for troubleshooting AICore/Gemini Nano issues.
     * This method gathers comprehensive information about the device, AICore, and model status.
     */
    suspend fun getDiagnostics(): GeminiNanoDiagnostics = withContext(Dispatchers.IO) {
        val model = Build.MODEL
        val isPixel = model.lowercase().contains("pixel")
        val isPixel8OrNewer = model.lowercase().let {
            it.contains("pixel 8") || it.contains("pixel 9") ||
            it.contains("pixel 10") || it.contains("pixel 11") ||
            it.contains("pixel fold") || it.contains("pixel tablet")
        }

        // Check AICore installation
        var aiCoreInstalled = false
        var aiCoreVersion: String? = null
        for (packageName in AICORE_PACKAGES) {
            try {
                val packageInfo = context.packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
                aiCoreInstalled = true
                aiCoreVersion = packageInfo.versionName
                break
            } catch (e: PackageManager.NameNotFoundException) {
                // Package not found, continue checking
            }
        }

        // Check feature status
        val featureStatus = try {
            Generation.getClient().checkStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check feature status: ${e.message}")
            FeatureStatus.UNAVAILABLE
        }

        val featureStatusName = when (featureStatus) {
            FeatureStatus.AVAILABLE -> "AVAILABLE"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
            else -> "UNKNOWN ($featureStatus)"
        }

        GeminiNanoDiagnostics(
            deviceModel = model,
            androidVersion = Build.VERSION.SDK_INT,
            isPixelDevice = isPixel,
            isPixel8OrNewer = isPixel8OrNewer,
            isDeviceSupported = isDeviceSupported(),
            isAiCoreInstalled = aiCoreInstalled,
            aiCorePackageVersion = aiCoreVersion,
            featureStatus = featureStatus,
            featureStatusName = featureStatusName,
            isModelAvailable = featureStatus == FeatureStatus.AVAILABLE,
            isModelDownloadable = featureStatus == FeatureStatus.DOWNLOADABLE,
            isModelDownloading = featureStatus == FeatureStatus.DOWNLOADING,
            isInitialized = isInitialized,
            isReady = isReady(),
            lastError = initializationError,
            currentStatus = _modelStatus.value
        )
    }

    /**
     * Force retry initialization, clearing any cached error state.
     * Use this when the user wants to retry after a failed initialization.
     */
    suspend fun forceRetryInitialize(): Boolean = initMutex.withLock {
        Log.i(TAG, "Force retry initialization requested")

        // Reset all state
        isInitialized = false
        initializationError = null
        generativeModel?.close()
        generativeModel = null
        _modelStatus.value = GeminiNanoStatus.NotInitialized

        // Re-attempt initialization without holding the lock
        // (initialize() will acquire its own lock)
        return@withLock false // Release lock, then call initialize
    }.let {
        // Now call initialize() after releasing the lock
        initialize()
    }

    /**
     * Force retry/trigger model download, even if a download was previously attempted.
     * This resets any error state and attempts fresh download.
     *
     * @param onProgress Callback for download progress updates
     * @return true if the model is ready to use after this call
     */
    suspend fun forceRetryDownload(onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Force retry download requested")

        // Reset error state
        initializationError = null
        isDownloadCancelled.set(false)
        _modelStatus.value = GeminiNanoStatus.NotInitialized
        _downloadProgress.value = 0

        try {
            val client = Generation.getClient()
            val status = client.checkStatus()
            Log.d(TAG, "Force retry: Current model status = $status")

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    Log.i(TAG, "Model is already available")
                    updateProgress(100, onProgress)
                    updateStatus(GeminiNanoStatus.Ready)

                    // Also initialize the client
                    val initResult = initialize()
                    Log.d(TAG, "Force retry: Initialize result = $initResult")
                    return@withContext initResult
                }
                FeatureStatus.DOWNLOADING -> {
                    Log.i(TAG, "Model is already downloading, waiting for completion")
                    updateStatus(GeminiNanoStatus.Downloading(0))
                    val downloadResult = waitForExistingDownload(client, onProgress)
                    if (downloadResult) {
                        return@withContext initialize()
                    }
                    return@withContext false
                }
                FeatureStatus.DOWNLOADABLE -> {
                    Log.i(TAG, "Starting fresh model download")
                    updateStatus(GeminiNanoStatus.Downloading(0))
                    val downloadResult = startDownload(client, onProgress)
                    if (downloadResult) {
                        Log.i(TAG, "Download succeeded, initializing...")
                        return@withContext initialize()
                    }
                    return@withContext false
                }
                FeatureStatus.UNAVAILABLE -> {
                    val errMsg = "Gemini Nano is unavailable. This may be due to:\n" +
                            "• Unsupported device (requires Pixel 8+)\n" +
                            "• Unlocked bootloader\n" +
                            "• Missing or outdated AICore\n" +
                            "• Region restrictions\n\n" +
                            "Try updating Google Play Services and restarting your device."
                    Log.w(TAG, errMsg)
                    initializationError = errMsg
                    updateStatus(GeminiNanoStatus.Error(errMsg))
                    return@withContext false
                }
                else -> {
                    val errMsg = "Unknown model status: $status"
                    Log.w(TAG, errMsg)
                    initializationError = errMsg
                    updateStatus(GeminiNanoStatus.Error(errMsg))
                    return@withContext false
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Force retry was cancelled")
            updateStatus(GeminiNanoStatus.NotInitialized)
            throw e
        } catch (e: Exception) {
            val errMsg = "Force retry failed: ${e.message}"
            Log.e(TAG, errMsg, e)
            initializationError = errMsg
            updateStatus(GeminiNanoStatus.Error(errMsg))
            return@withContext false
        }
    }

    /**
     * Get a user-friendly status message for the current state.
     */
    fun getStatusMessage(): String = when (val status = _modelStatus.value) {
        GeminiNanoStatus.NotSupported -> "Your device doesn't support Gemini Nano (requires Pixel 8+ with Android 14+)"
        GeminiNanoStatus.NotInitialized -> "Gemini Nano is not initialized. Tap to initialize."
        GeminiNanoStatus.Initializing -> "Initializing Gemini Nano..."
        GeminiNanoStatus.NeedsDownload -> "Gemini Nano model needs to be downloaded (~500MB)"
        is GeminiNanoStatus.Downloading -> "Downloading Gemini Nano model... ${status.progress}%"
        GeminiNanoStatus.Ready -> "Gemini Nano is ready for on-device AI analysis"
        is GeminiNanoStatus.Error -> "Error: ${status.message}"
    }
}

sealed class GeminiNanoStatus {
    object NotSupported : GeminiNanoStatus()
    object NotInitialized : GeminiNanoStatus()
    object Initializing : GeminiNanoStatus()
    object NeedsDownload : GeminiNanoStatus()
    data class Downloading(val progress: Int) : GeminiNanoStatus()
    object Ready : GeminiNanoStatus()
    data class Error(val message: String) : GeminiNanoStatus()

    fun toDisplayString(): String = when (this) {
        NotSupported -> "Not Supported"
        NotInitialized -> "Not Initialized"
        Initializing -> "Initializing..."
        NeedsDownload -> "Needs Download"
        is Downloading -> "Downloading ($progress%)"
        Ready -> "Ready"
        is Error -> "Error: $message"
    }
}

/**
 * Detailed diagnostic information for Gemini Nano / AICore status.
 * Use this for troubleshooting when the model isn't working.
 */
data class GeminiNanoDiagnostics(
    val deviceModel: String,
    val androidVersion: Int,
    val isPixelDevice: Boolean,
    val isPixel8OrNewer: Boolean,
    val isDeviceSupported: Boolean,
    val isAiCoreInstalled: Boolean,
    val aiCorePackageVersion: String?,
    val featureStatus: Int, // FeatureStatus value
    val featureStatusName: String,
    val isModelAvailable: Boolean,
    val isModelDownloadable: Boolean,
    val isModelDownloading: Boolean,
    val isInitialized: Boolean,
    val isReady: Boolean,
    val lastError: String?,
    val currentStatus: GeminiNanoStatus
) {
    fun toDetailedReport(): String = buildString {
        appendLine("=== Gemini Nano Diagnostics ===")
        appendLine()
        appendLine("Device Info:")
        appendLine("  Model: $deviceModel")
        appendLine("  Android Version: $androidVersion (API)")
        appendLine("  Is Pixel: $isPixelDevice")
        appendLine("  Is Pixel 8+: $isPixel8OrNewer")
        appendLine("  Device Supported: $isDeviceSupported")
        appendLine()
        appendLine("AICore Status:")
        appendLine("  Installed: $isAiCoreInstalled")
        appendLine("  Package Version: ${aiCorePackageVersion ?: "N/A"}")
        appendLine()
        appendLine("Model Status:")
        appendLine("  Feature Status: $featureStatusName ($featureStatus)")
        appendLine("  Available: $isModelAvailable")
        appendLine("  Downloadable: $isModelDownloadable")
        appendLine("  Downloading: $isModelDownloading")
        appendLine()
        appendLine("Client Status:")
        appendLine("  Initialized: $isInitialized")
        appendLine("  Ready: $isReady")
        appendLine("  Current Status: ${currentStatus.toDisplayString()}")
        lastError?.let {
            appendLine("  Last Error: $it")
        }
    }

    fun getSummary(): String = when {
        !isDeviceSupported -> "Device not supported (requires Pixel 8+ with Android 14+)"
        !isAiCoreInstalled -> "AICore not installed - update Google Play Services"
        featureStatus == FeatureStatus.UNAVAILABLE -> "Model unavailable on this device"
        featureStatus == FeatureStatus.DOWNLOADABLE -> "Model ready to download"
        featureStatus == FeatureStatus.DOWNLOADING -> "Model downloading..."
        featureStatus == FeatureStatus.AVAILABLE && !isReady -> "Model available but not initialized"
        isReady -> "Ready for inference"
        else -> "Unknown state: ${currentStatus.toDisplayString()}"
    }
}

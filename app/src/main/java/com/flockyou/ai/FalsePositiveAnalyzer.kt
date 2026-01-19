package com.flockyou.ai

import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyzes detections for false positives using LLM reasoning and rule-based heuristics.
 *
 * False positives are filtered out by default but users can view them with explanations
 * of why the system believes they are not genuine surveillance devices.
 *
 * The analyzer uses MediaPipe LLM for enhanced analysis when available. If MediaPipe
 * is not ready, it can attempt lazy initialization via a provided callback, or fall
 * back to comprehensive rule-based detection.
 */
@Singleton
class FalsePositiveAnalyzer @Inject constructor(
    private val mediaPipeLlmClient: MediaPipeLlmClient
) {
    companion object {
        private const val TAG = "FalsePositiveAnalyzer"

        // FP confidence thresholds
        private const val HIGH_CONFIDENCE_FP_THRESHOLD = 0.8f   // Definitely FP
        private const val MEDIUM_CONFIDENCE_FP_THRESHOLD = 0.6f // Likely FP
        private const val LOW_CONFIDENCE_FP_THRESHOLD = 0.4f    // Possibly FP
    }

    /**
     * Optional callback to lazily initialize MediaPipe if not already ready.
     * This allows FP analysis to use LLM even when the main model is GeminiNano or rule-based.
     */
    private var lazyInitCallback: (suspend () -> Boolean)? = null

    /**
     * Set a callback for lazy MediaPipe initialization.
     * Called when FP analysis needs LLM but MediaPipe isn't ready.
     */
    fun setLazyInitCallback(callback: suspend () -> Boolean) {
        lazyInitCallback = callback
    }

    /**
     * Analyze a detection for false positive likelihood.
     * Returns FalsePositiveResult with reasoning.
     *
     * @param detection The detection to analyze
     * @param contextInfo Optional context about user's location/environment
     * @param tryLazyInit If true, attempt to initialize MediaPipe if not ready
     */
    suspend fun analyzeForFalsePositive(
        detection: Detection,
        contextInfo: FpContextInfo? = null,
        tryLazyInit: Boolean = true
    ): FalsePositiveResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // First, apply rule-based checks (fast)
        val ruleBasedResult = applyRuleBasedChecks(detection, contextInfo)

        // If rule-based is highly confident, skip LLM
        if (ruleBasedResult.confidence >= HIGH_CONFIDENCE_FP_THRESHOLD) {
            Log.d(TAG, "Rule-based FP detection confident (${ruleBasedResult.confidence}): ${ruleBasedResult.primaryReason}")
            return@withContext ruleBasedResult.copy(
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Try LLM analysis for more nuanced detection
        var mediaPipeReady = mediaPipeLlmClient.isReady()

        // If MediaPipe not ready but we have a lazy init callback, try to initialize
        if (!mediaPipeReady && tryLazyInit && lazyInitCallback != null) {
            Log.d(TAG, "MediaPipe not ready, attempting lazy initialization for FP analysis")
            try {
                mediaPipeReady = lazyInitCallback?.invoke() ?: false
                if (mediaPipeReady) {
                    Log.i(TAG, "Lazy initialization successful, MediaPipe now ready for FP analysis")
                } else {
                    Log.w(TAG, "Lazy initialization failed, falling back to rule-based FP analysis")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during lazy initialization: ${e.message}")
            }
        }

        if (mediaPipeReady) {
            val llmResult = analyzeFpWithLlm(detection, contextInfo, ruleBasedResult)
            if (llmResult != null) {
                return@withContext llmResult.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // Fall back to rule-based result
        ruleBasedResult.copy(
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Batch analyze multiple detections for false positives.
     */
    suspend fun analyzeMultiple(
        detections: List<Detection>,
        contextInfo: FpContextInfo? = null
    ): Map<String, FalsePositiveResult> = withContext(Dispatchers.IO) {
        detections.associate { detection ->
            detection.id to analyzeForFalsePositive(detection, contextInfo)
        }
    }

    /**
     * Filter a list of detections, removing likely false positives.
     * Returns pair of (validDetections, filteredFalsePositives)
     */
    suspend fun filterFalsePositives(
        detections: List<Detection>,
        threshold: Float = MEDIUM_CONFIDENCE_FP_THRESHOLD,
        contextInfo: FpContextInfo? = null
    ): FilteredDetections = withContext(Dispatchers.IO) {
        val results = analyzeMultiple(detections, contextInfo)

        val valid = mutableListOf<Detection>()
        val filtered = mutableListOf<Pair<Detection, FalsePositiveResult>>()

        for (detection in detections) {
            val fpResult = results[detection.id]
            if (fpResult != null && fpResult.isFalsePositive && fpResult.confidence >= threshold) {
                filtered.add(detection to fpResult)
            } else {
                valid.add(detection)
            }
        }

        FilteredDetections(
            validDetections = valid,
            filteredFalsePositives = filtered,
            totalAnalyzed = detections.size,
            filterThreshold = threshold
        )
    }

    // ==================== RULE-BASED CHECKS ====================

    private fun applyRuleBasedChecks(
        detection: Detection,
        contextInfo: FpContextInfo?
    ): FalsePositiveResult {
        val reasons = mutableListOf<FpReason>()
        var fpScore = 0f

        // Check 1: Known benign device patterns
        val benignPatternResult = checkBenignPatterns(detection)
        if (benignPatternResult != null) {
            reasons.add(benignPatternResult)
            fpScore += benignPatternResult.weight
        }

        // Check 2: Signal strength anomalies (too weak = likely noise)
        val signalResult = checkSignalStrength(detection)
        if (signalResult != null) {
            reasons.add(signalResult)
            fpScore += signalResult.weight
        }

        // Check 3: Common consumer device names
        val consumerDeviceResult = checkConsumerDevicePatterns(detection)
        if (consumerDeviceResult != null) {
            reasons.add(consumerDeviceResult)
            fpScore += consumerDeviceResult.weight
        }

        // Check 4: Known infrastructure (ISP routers, public WiFi)
        val infrastructureResult = checkKnownInfrastructure(detection)
        if (infrastructureResult != null) {
            reasons.add(infrastructureResult)
            fpScore += infrastructureResult.weight
        }

        // Check 5: Location context (home, work, known safe areas)
        if (contextInfo != null) {
            val locationResult = checkLocationContext(detection, contextInfo)
            if (locationResult != null) {
                reasons.add(locationResult)
                fpScore += locationResult.weight
            }
        }

        // Check 6: Transient detections (seen only once, briefly)
        val transientResult = checkTransientDetection(detection)
        if (transientResult != null) {
            reasons.add(transientResult)
            fpScore += transientResult.weight
        }

        // Check 7: Low threat score devices with common characteristics
        val lowThreatResult = checkLowThreatCommonDevice(detection)
        if (lowThreatResult != null) {
            reasons.add(lowThreatResult)
            fpScore += lowThreatResult.weight
        }

        val confidence = fpScore.coerceIn(0f, 1f)
        val isFp = confidence >= LOW_CONFIDENCE_FP_THRESHOLD

        return FalsePositiveResult(
            detectionId = detection.id,
            isFalsePositive = isFp,
            confidence = confidence,
            confidenceLevel = when {
                confidence >= HIGH_CONFIDENCE_FP_THRESHOLD -> FpConfidenceLevel.HIGH
                confidence >= MEDIUM_CONFIDENCE_FP_THRESHOLD -> FpConfidenceLevel.MEDIUM
                confidence >= LOW_CONFIDENCE_FP_THRESHOLD -> FpConfidenceLevel.LOW
                else -> FpConfidenceLevel.NONE
            },
            primaryReason = reasons.maxByOrNull { it.weight }?.description ?: "No false positive indicators",
            allReasons = reasons,
            bannerMessage = if (isFp) buildBannerMessage(reasons, detection) else null,
            analysisMethod = FpAnalysisMethod.RULE_BASED
        )
    }

    private fun checkBenignPatterns(detection: Detection): FpReason? {
        // Known benign SSID patterns
        val benignSsidPatterns = listOf(
            Regex("^(xfinity|XFINITY|Xfinity)", RegexOption.IGNORE_CASE) to "Xfinity/Comcast home router",
            Regex("^(ATT|AT&T|att)", RegexOption.IGNORE_CASE) to "AT&T home router",
            Regex("^(Verizon|FIOS)", RegexOption.IGNORE_CASE) to "Verizon home router",
            Regex("^(NETGEAR|Netgear)", RegexOption.IGNORE_CASE) to "Netgear consumer router",
            Regex("^(ASUS|asus)", RegexOption.IGNORE_CASE) to "ASUS consumer router",
            Regex("^(TP-Link|TP_Link|tplink)", RegexOption.IGNORE_CASE) to "TP-Link consumer router",
            Regex("^(Linksys|LINKSYS)", RegexOption.IGNORE_CASE) to "Linksys consumer router",
            Regex("^(Google Wifi|GoogleWifi)", RegexOption.IGNORE_CASE) to "Google Wifi mesh router",
            Regex("^(eero|Eero)", RegexOption.IGNORE_CASE) to "Amazon eero mesh router",
            Regex("^(DIRECT-)", RegexOption.IGNORE_CASE) to "WiFi Direct device (printer/TV)",
            Regex("^(HP-Print|EPSON|Canon|Brother)", RegexOption.IGNORE_CASE) to "Wireless printer",
            Regex("^(Chromecast|GoogleHome|Google-Home)", RegexOption.IGNORE_CASE) to "Google smart device",
            Regex("^(Amazon-|Echo-|FireTV)", RegexOption.IGNORE_CASE) to "Amazon smart device",
            Regex("^(Roku|AppleTV|Apple-TV)", RegexOption.IGNORE_CASE) to "Streaming device",
            Regex("^(PlayStation|Xbox|Nintendo)", RegexOption.IGNORE_CASE) to "Gaming console",
            Regex("^(iPhone|iPad|MacBook|iMac)", RegexOption.IGNORE_CASE) to "Apple personal device",
            Regex("^(Galaxy|Samsung|Pixel|OnePlus)", RegexOption.IGNORE_CASE) to "Personal smartphone"
        )

        detection.ssid?.let { ssid ->
            for ((pattern, description) in benignSsidPatterns) {
                if (pattern.containsMatchIn(ssid)) {
                    return FpReason(
                        category = FpCategory.BENIGN_DEVICE,
                        description = "Matches known consumer device: $description",
                        weight = 0.7f,
                        technicalDetail = "SSID '$ssid' matches pattern for $description"
                    )
                }
            }
        }

        // Check device name patterns
        detection.deviceName?.let { name ->
            for ((pattern, description) in benignSsidPatterns) {
                if (pattern.containsMatchIn(name)) {
                    return FpReason(
                        category = FpCategory.BENIGN_DEVICE,
                        description = "Device name indicates consumer product: $description",
                        weight = 0.6f,
                        technicalDetail = "Device name '$name' matches $description"
                    )
                }
            }
        }

        return null
    }

    private fun checkSignalStrength(detection: Detection): FpReason? {
        // Very weak signals are often noise or distant legitimate devices
        if (detection.rssi < -85) {
            return FpReason(
                category = FpCategory.WEAK_SIGNAL,
                description = "Very weak signal likely from distant legitimate device or interference",
                weight = 0.4f,
                technicalDetail = "Signal strength ${detection.rssi} dBm is below reliable detection threshold"
            )
        }
        return null
    }

    private fun checkConsumerDevicePatterns(detection: Detection): FpReason? {
        // Smart home devices that are often detected
        val smartHomeTypes = setOf(
            DeviceType.RING_DOORBELL,
            DeviceType.NEST_CAMERA,
            DeviceType.WYZE_CAMERA,
            DeviceType.ARLO_CAMERA,
            DeviceType.EUFY_CAMERA,
            DeviceType.BLINK_CAMERA,
            DeviceType.AMAZON_SIDEWALK
        )

        if (detection.deviceType in smartHomeTypes) {
            // These are consumer devices, but context matters
            if (detection.threatLevel == ThreatLevel.INFO || detection.threatLevel == ThreatLevel.LOW) {
                return FpReason(
                    category = FpCategory.CONSUMER_SMART_HOME,
                    description = "Consumer smart home device (likely belongs to neighbor or business)",
                    weight = 0.5f,
                    technicalDetail = "${detection.deviceType.displayName} is a common consumer product"
                )
            }
        }

        return null
    }

    private fun checkKnownInfrastructure(detection: Detection): FpReason? {
        val infrastructurePatterns = listOf(
            "guest" to "Guest WiFi network",
            "public" to "Public WiFi",
            "free" to "Free public WiFi",
            "starbucks" to "Starbucks store WiFi",
            "mcdonalds" to "McDonald's store WiFi",
            "walmart" to "Walmart store WiFi",
            "target" to "Target store WiFi",
            "library" to "Library public WiFi",
            "airport" to "Airport WiFi",
            "hotel" to "Hotel WiFi",
            "cafe" to "Cafe WiFi",
            "coffee" to "Coffee shop WiFi"
        )

        val ssidLower = detection.ssid?.lowercase() ?: ""
        val nameLower = detection.deviceName?.lowercase() ?: ""

        for ((keyword, description) in infrastructurePatterns) {
            if (keyword in ssidLower || keyword in nameLower) {
                return FpReason(
                    category = FpCategory.PUBLIC_INFRASTRUCTURE,
                    description = "Appears to be $description",
                    weight = 0.6f,
                    technicalDetail = "Network name suggests legitimate public infrastructure"
                )
            }
        }

        return null
    }

    private fun checkLocationContext(detection: Detection, context: FpContextInfo): FpReason? {
        // If user is at home and detection is within home radius
        if (context.isAtHome && detection.latitude != null && detection.longitude != null) {
            if (context.homeLatitude != null && context.homeLongitude != null) {
                val distance = haversineDistance(
                    detection.latitude, detection.longitude,
                    context.homeLatitude, context.homeLongitude
                )
                if (distance < 50) { // Within 50m of home
                    return FpReason(
                        category = FpCategory.HOME_LOCATION,
                        description = "Detected at your home location (likely your own or neighbor's device)",
                        weight = 0.5f,
                        technicalDetail = "Detection is ${distance.toInt()}m from registered home location"
                    )
                }
            }
        }

        // If at known work location
        if (context.isAtWork) {
            return FpReason(
                category = FpCategory.WORK_LOCATION,
                description = "Detected at your workplace (expected business infrastructure)",
                weight = 0.4f,
                technicalDetail = "User is at registered work location"
            )
        }

        return null
    }

    private fun checkTransientDetection(detection: Detection): FpReason? {
        // Only seen once and low threat
        if (detection.seenCount == 1 && detection.threatLevel <= ThreatLevel.LOW) {
            return FpReason(
                category = FpCategory.TRANSIENT,
                description = "Single brief detection (likely passing device or interference)",
                weight = 0.3f,
                technicalDetail = "Device seen only ${detection.seenCount} time(s)"
            )
        }
        return null
    }

    private fun checkLowThreatCommonDevice(detection: Detection): FpReason? {
        // INFO level threats with common detection methods
        if (detection.threatLevel == ThreatLevel.INFO) {
            val commonMethods = setOf(
                DetectionMethod.BLE_DEVICE_NAME,
                DetectionMethod.SSID_PATTERN,
                DetectionMethod.BEACON_FRAME
            )
            if (detection.detectionMethod in commonMethods) {
                return FpReason(
                    category = FpCategory.LOW_THREAT,
                    description = "Low-threat detection with common characteristics",
                    weight = 0.3f,
                    technicalDetail = "INFO-level threat detected via ${detection.detectionMethod.displayName}"
                )
            }
        }
        return null
    }

    // ==================== LLM-BASED ANALYSIS ====================

    private suspend fun analyzeFpWithLlm(
        detection: Detection,
        contextInfo: FpContextInfo?,
        ruleBasedResult: FalsePositiveResult
    ): FalsePositiveResult? {
        val prompt = buildFpAnalysisPrompt(detection, contextInfo, ruleBasedResult)

        return try {
            val response = mediaPipeLlmClient.generateResponse(prompt)
            if (response != null) {
                parseLlmFpResponse(response, detection, ruleBasedResult)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "LLM FP analysis failed: ${e.message}")
            null
        }
    }

    private fun buildFpAnalysisPrompt(
        detection: Detection,
        contextInfo: FpContextInfo?,
        ruleBasedResult: FalsePositiveResult
    ): String {
        val contextSection = contextInfo?.let {
            """
=== USER CONTEXT ===
At Home: ${it.isAtHome}
At Work: ${it.isAtWork}
Known Safe Area: ${it.isKnownSafeArea}
Time: ${if (it.isNightTime) "Night" else "Day"}
"""
        } ?: ""

        val ruleBasedSection = if (ruleBasedResult.allReasons.isNotEmpty()) {
            """
=== PRELIMINARY ANALYSIS ===
Initial FP likelihood: ${(ruleBasedResult.confidence * 100).toInt()}%
Indicators found:
${ruleBasedResult.allReasons.joinToString("\n") { "- ${it.description}" }}
"""
        } else ""

        return """<start_of_turn>user
Analyze this surveillance detection and determine if it's a FALSE POSITIVE (benign device incorrectly flagged).

=== DETECTION ===
Device Type: ${detection.deviceType.displayName}
Detection Method: ${detection.detectionMethod.displayName}
Protocol: ${detection.protocol.displayName}
Threat Level: ${detection.threatLevel.displayName}
Signal: ${detection.rssi} dBm (${detection.signalStrength.displayName})
${detection.ssid?.let { "SSID: $it" } ?: ""}
${detection.deviceName?.let { "Device Name: $it" } ?: ""}
${detection.manufacturer?.let { "Manufacturer: $it" } ?: ""}
Times Seen: ${detection.seenCount}
$contextSection
$ruleBasedSection

Determine:
1. Is this likely a FALSE POSITIVE? (YES/NO)
2. Confidence level (0-100%)
3. Primary reason for your determination
4. A user-friendly explanation (1-2 sentences)

Respond in this format:
FALSE_POSITIVE: YES or NO
CONFIDENCE: number 0-100
REASON: brief technical reason
EXPLANATION: user-friendly explanation
<end_of_turn>
<start_of_turn>model
"""
    }

    private fun parseLlmFpResponse(
        response: String,
        detection: Detection,
        ruleBasedResult: FalsePositiveResult
    ): FalsePositiveResult {
        val cleaned = response
            .replace("<end_of_turn>", "")
            .replace("<start_of_turn>model", "")
            .trim()

        // Parse FALSE_POSITIVE
        val isFp = Regex("""FALSE_POSITIVE:\s*(YES|NO)""", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.getOrNull(1)?.uppercase() == "YES"

        // Parse CONFIDENCE
        val confidence = Regex("""CONFIDENCE:\s*(\d+)""")
            .find(cleaned)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.div(100f)
            ?: ruleBasedResult.confidence

        // Parse REASON
        val reason = Regex("""REASON:\s*(.+?)(?=\n|EXPLANATION|$)""", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.getOrNull(1)?.trim()
            ?: ruleBasedResult.primaryReason

        // Parse EXPLANATION
        val explanation = Regex("""EXPLANATION:\s*(.+?)(?=\n\n|$)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(cleaned)?.groupValues?.getOrNull(1)?.trim()

        // Combine with rule-based reasons
        val combinedReasons = ruleBasedResult.allReasons.toMutableList()
        if (reason.isNotBlank() && reason != ruleBasedResult.primaryReason) {
            combinedReasons.add(FpReason(
                category = FpCategory.LLM_ANALYSIS,
                description = reason,
                weight = confidence,
                technicalDetail = "LLM analysis conclusion"
            ))
        }

        // Average confidence with rule-based
        val finalConfidence = (confidence + ruleBasedResult.confidence) / 2f

        return FalsePositiveResult(
            detectionId = detection.id,
            isFalsePositive = isFp && finalConfidence >= LOW_CONFIDENCE_FP_THRESHOLD,
            confidence = finalConfidence,
            confidenceLevel = when {
                finalConfidence >= HIGH_CONFIDENCE_FP_THRESHOLD -> FpConfidenceLevel.HIGH
                finalConfidence >= MEDIUM_CONFIDENCE_FP_THRESHOLD -> FpConfidenceLevel.MEDIUM
                finalConfidence >= LOW_CONFIDENCE_FP_THRESHOLD -> FpConfidenceLevel.LOW
                else -> FpConfidenceLevel.NONE
            },
            primaryReason = reason,
            allReasons = combinedReasons,
            bannerMessage = explanation ?: buildBannerMessage(combinedReasons, detection),
            analysisMethod = FpAnalysisMethod.LLM_ENHANCED,
            llmExplanation = explanation
        )
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun buildBannerMessage(reasons: List<FpReason>, detection: Detection): String {
        val topReason = reasons.maxByOrNull { it.weight }

        return when {
            topReason?.category == FpCategory.BENIGN_DEVICE ->
                "This appears to be a ${topReason.description.substringAfter(": ")}. Common consumer devices can trigger detection due to similar network signatures."

            topReason?.category == FpCategory.CONSUMER_SMART_HOME ->
                "This ${detection.deviceType.displayName} is likely a neighbor's or business's smart home device, not surveillance equipment targeting you."

            topReason?.category == FpCategory.PUBLIC_INFRASTRUCTURE ->
                "This appears to be legitimate public WiFi infrastructure. ${topReason.description}."

            topReason?.category == FpCategory.HOME_LOCATION ->
                "Detected at your home - this is likely your own device or a neighbor's. Home networks commonly show up in scans."

            topReason?.category == FpCategory.WORK_LOCATION ->
                "Detected at your workplace - business infrastructure and colleague devices are expected here."

            topReason?.category == FpCategory.WEAK_SIGNAL ->
                "This weak signal is likely from a distant legitimate device or RF interference, not targeted surveillance."

            topReason?.category == FpCategory.TRANSIENT ->
                "This was only detected briefly once - likely a passing device or temporary interference."

            else ->
                "Analysis suggests this is likely a false positive based on device characteristics and context."
        }
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }
}

// ==================== DATA CLASSES ====================

/**
 * Result of false positive analysis
 */
data class FalsePositiveResult(
    val detectionId: String,
    val isFalsePositive: Boolean,
    val confidence: Float,                    // 0.0-1.0
    val confidenceLevel: FpConfidenceLevel,
    val primaryReason: String,
    val allReasons: List<FpReason>,
    val bannerMessage: String?,               // User-friendly explanation for UI banner
    val analysisMethod: FpAnalysisMethod = FpAnalysisMethod.RULE_BASED,
    val llmExplanation: String? = null,       // LLM's user-friendly explanation
    val processingTimeMs: Long = 0
)

/**
 * Individual reason for FP classification
 */
data class FpReason(
    val category: FpCategory,
    val description: String,
    val weight: Float,                        // Contribution to overall FP score
    val technicalDetail: String? = null
)

/**
 * Categories of false positive indicators
 */
enum class FpCategory(val displayName: String) {
    BENIGN_DEVICE("Known Benign Device"),
    CONSUMER_SMART_HOME("Consumer Smart Home"),
    PUBLIC_INFRASTRUCTURE("Public Infrastructure"),
    HOME_LOCATION("Home Location"),
    WORK_LOCATION("Work Location"),
    WEAK_SIGNAL("Weak Signal"),
    TRANSIENT("Transient Detection"),
    LOW_THREAT("Low Threat Profile"),
    LLM_ANALYSIS("AI Analysis")
}

/**
 * Confidence level for FP determination
 */
enum class FpConfidenceLevel(val displayName: String) {
    HIGH("Definitely a false positive"),
    MEDIUM("Likely a false positive"),
    LOW("Possibly a false positive"),
    NONE("Not a false positive")
}

/**
 * Analysis method used
 */
enum class FpAnalysisMethod {
    RULE_BASED,
    LLM_ENHANCED
}

/**
 * Context information to help with FP analysis
 */
data class FpContextInfo(
    val isAtHome: Boolean = false,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val isAtWork: Boolean = false,
    val workLatitude: Double? = null,
    val workLongitude: Double? = null,
    val isKnownSafeArea: Boolean = false,
    val isNightTime: Boolean = false,
    val recentlyTraveled: Boolean = false
)

/**
 * Result of filtering detections for false positives
 */
data class FilteredDetections(
    val validDetections: List<Detection>,
    val filteredFalsePositives: List<Pair<Detection, FalsePositiveResult>>,
    val totalAnalyzed: Int,
    val filterThreshold: Float
) {
    val filteredCount: Int get() = filteredFalsePositives.size
    val validCount: Int get() = validDetections.size
}

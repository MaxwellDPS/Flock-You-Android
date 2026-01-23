package com.flockyou.detection.handler

import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.SignalStrength
import com.flockyou.data.model.ThreatLevel
import com.flockyou.detection.framework.TrackerDatabase
import com.flockyou.detection.framework.UltrasonicModulation
import com.flockyou.detection.framework.UltrasonicTrackerSignature
import com.flockyou.detection.framework.UltrasonicTrackingPurpose
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Handler for ultrasonic beacon detection and analysis.
 *
 * Processes ultrasonic frequency detections and generates:
 * - Detection records for the surveillance database
 * - AI prompts for detailed beacon analysis
 * - Tracking likelihood scores
 * - Source attribution for known beacon providers
 *
 * Supported detection methods:
 * - ULTRASONIC_TRACKING_BEACON: Cross-device tracking beacons
 * - ULTRASONIC_AD_BEACON: Advertising/TV tracking (SilverPush, Alphonso)
 * - ULTRASONIC_RETAIL_BEACON: Retail location tracking (Shopkick)
 * - ULTRASONIC_CONTINUOUS: Persistent ultrasonic transmission
 * - ULTRASONIC_CROSS_DEVICE: Multi-device linking (LISNR)
 * - ULTRASONIC_UNKNOWN: Unidentified ultrasonic source
 *
 * Known beacon sources and frequencies:
 * - SilverPush: 18 kHz (ad tracking)
 * - Alphonso: 18.5 kHz (TV attribution)
 * - Signal360: 19 kHz (proximity marketing)
 * - LISNR: 19.5 kHz (cross-device data transmission)
 * - Shopkick: 20 kHz (retail location verification)
 */
@Singleton
class UltrasonicDetectionHandler @Inject constructor() {

    companion object {
        private const val TAG = "UltrasonicDetectionHandler"

        // Frequency tolerance for signature matching (Hz)
        private const val FREQUENCY_TOLERANCE_HZ = 100

        // Beacon source frequencies (Hz)
        private const val FREQ_SILVERPUSH = 18000
        private const val FREQ_SILVERPUSH_ALT = 18200
        private const val FREQ_ALPHONSO = 18500
        private const val FREQ_ZAPR = 17500
        private const val FREQ_SIGNAL360 = 19000
        private const val FREQ_REALEYES = 19200
        private const val FREQ_LISNR = 19500
        private const val FREQ_TVISION = 19800
        private const val FREQ_SHOPKICK = 20000
        private const val FREQ_SAMBA_TV = 20200
        private const val FREQ_RETAIL_BAND_1 = 20500
        private const val FREQ_RETAIL_BAND_2 = 21000
        private const val FREQ_INSCAPE = 21500
        private const val FREQ_DATA_PLUS_MATH = 22000

        // Thresholds for risk assessment
        private const val HIGH_PERSISTENCE_THRESHOLD = 0.7f
        private const val FOLLOWING_LOCATION_THRESHOLD = 2
        private const val HIGH_SNR_THRESHOLD_DB = 20.0
        private const val STRONG_AMPLITUDE_THRESHOLD_DB = -35.0

        // Cached sets for enum comparisons to avoid allocation on every call
        private val SOPHISTICATED_MODULATION_TYPES = setOf(
            UltrasonicModulation.FSK,
            UltrasonicModulation.PSK,
            UltrasonicModulation.CHIRP
        )

        private val HIGH_THREAT_BEACON_CATEGORIES = setOf(
            BeaconCategory.ADVERTISING,
            BeaconCategory.TV_ATTRIBUTION,
            BeaconCategory.CROSS_DEVICE_LINKING
        )
    }

    /**
     * Context data for ultrasonic detection analysis.
     */
    data class UltrasonicDetectionContext(
        val frequencyHz: Int,
        val amplitudeDb: Double,
        val modulationType: UltrasonicModulation,
        val detectedLocations: Int,
        val persistenceScore: Float,
        val isFollowing: Boolean,
        val noiseFloorDb: Double = -60.0,
        val detectionCount: Int = 1,
        val firstDetectedMs: Long = System.currentTimeMillis(),
        val lastDetectedMs: Long = System.currentTimeMillis(),
        val latitude: Double? = null,
        val longitude: Double? = null,
        val amplitudeHistory: List<Double> = emptyList()
    ) {
        /**
         * Signal-to-noise ratio in dB
         */
        val snrDb: Double
            get() = amplitudeDb - noiseFloorDb

        /**
         * Detection duration in milliseconds
         */
        val durationMs: Long
            get() = lastDetectedMs - firstDetectedMs
    }

    /**
     * Beacon source attribution result.
     */
    data class BeaconSourceAttribution(
        val sourceName: String,
        val manufacturer: String,
        val confidence: Float,
        val category: BeaconCategory,
        val trackingPurpose: UltrasonicTrackingPurpose
    )

    /**
     * Categories of ultrasonic beacons.
     */
    enum class BeaconCategory(val displayName: String) {
        ADVERTISING("Advertising/Marketing"),
        TV_ATTRIBUTION("TV/Video Attribution"),
        RETAIL_ANALYTICS("Retail Analytics"),
        CROSS_DEVICE_LINKING("Cross-Device Linking"),
        LOCATION_VERIFICATION("Location Verification"),
        PRESENCE_DETECTION("Presence Detection"),
        UNKNOWN("Unknown Purpose")
    }

    /**
     * Handle an ultrasonic detection and create a Detection record.
     */
    fun handle(context: UltrasonicDetectionContext): Detection {
        val attribution = attributeBeaconSource(context.frequencyHz)
        val detectionMethod = determineDetectionMethod(context, attribution)
        val threatLevel = calculateThreatLevel(context, attribution)
        val trackingLikelihood = calculateTrackingLikelihood(context, attribution)

        return Detection(
            id = UUID.randomUUID().toString(),
            timestamp = context.lastDetectedMs,
            protocol = DetectionProtocol.AUDIO,
            detectionMethod = detectionMethod,
            deviceType = DeviceType.ULTRASONIC_BEACON,
            deviceName = buildDeviceName(context, attribution),
            macAddress = null,
            ssid = "${context.frequencyHz}Hz",
            rssi = context.amplitudeDb.toInt(),
            signalStrength = amplitudeToSignalStrength(context.amplitudeDb),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = threatLevel,
            threatScore = calculateThreatScore(context, attribution, trackingLikelihood),
            manufacturer = attribution.manufacturer,
            matchedPatterns = buildMatchedPatterns(context, attribution, trackingLikelihood)
        )
    }

    /**
     * Attribute the beacon source based on frequency.
     */
    fun attributeBeaconSource(frequencyHz: Int): BeaconSourceAttribution {
        // First check the unified tracker database
        val signature = TrackerDatabase.findUltrasonicByFrequency(frequencyHz, FREQUENCY_TOLERANCE_HZ)
        if (signature != null) {
            return BeaconSourceAttribution(
                sourceName = signature.name,
                manufacturer = signature.manufacturer,
                confidence = calculateSignatureConfidence(frequencyHz, signature),
                category = purposeToCategory(signature.trackingPurpose),
                trackingPurpose = signature.trackingPurpose
            )
        }

        // Fallback to manual frequency matching
        return when {
            isNearFrequency(frequencyHz, FREQ_SILVERPUSH) -> BeaconSourceAttribution(
                sourceName = "SilverPush Primary",
                manufacturer = "SilverPush Technologies",
                confidence = 85f,
                category = BeaconCategory.ADVERTISING,
                trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING
            )
            isNearFrequency(frequencyHz, FREQ_SILVERPUSH_ALT) -> BeaconSourceAttribution(
                sourceName = "SilverPush Secondary",
                manufacturer = "SilverPush Technologies",
                confidence = 80f,
                category = BeaconCategory.ADVERTISING,
                trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING
            )
            isNearFrequency(frequencyHz, FREQ_ALPHONSO) -> BeaconSourceAttribution(
                sourceName = "Alphonso TV Attribution",
                manufacturer = "Alphonso Inc",
                confidence = 85f,
                category = BeaconCategory.TV_ATTRIBUTION,
                trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
            )
            isNearFrequency(frequencyHz, FREQ_ZAPR) -> BeaconSourceAttribution(
                sourceName = "Zapr TV Attribution",
                manufacturer = "Zapr Media Labs",
                confidence = 75f,
                category = BeaconCategory.TV_ATTRIBUTION,
                trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
            )
            isNearFrequency(frequencyHz, FREQ_SIGNAL360) -> BeaconSourceAttribution(
                sourceName = "Signal360",
                manufacturer = "Signal360",
                confidence = 70f,
                category = BeaconCategory.LOCATION_VERIFICATION,
                trackingPurpose = UltrasonicTrackingPurpose.LOCATION_VERIFICATION
            )
            isNearFrequency(frequencyHz, FREQ_REALEYES) -> BeaconSourceAttribution(
                sourceName = "Realeyes Attention Tracking",
                manufacturer = "Realeyes",
                confidence = 70f,
                category = BeaconCategory.ADVERTISING,
                trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING
            )
            isNearFrequency(frequencyHz, FREQ_LISNR) -> BeaconSourceAttribution(
                sourceName = "LISNR",
                manufacturer = "LISNR Inc",
                confidence = 70f,
                category = BeaconCategory.CROSS_DEVICE_LINKING,
                trackingPurpose = UltrasonicTrackingPurpose.CROSS_DEVICE_LINKING
            )
            isNearFrequency(frequencyHz, FREQ_TVISION) -> BeaconSourceAttribution(
                sourceName = "TVision Viewership",
                manufacturer = "TVision Insights",
                confidence = 65f,
                category = BeaconCategory.TV_ATTRIBUTION,
                trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
            )
            isNearFrequency(frequencyHz, FREQ_SHOPKICK) -> BeaconSourceAttribution(
                sourceName = "Shopkick",
                manufacturer = "Shopkick/SK Telecom",
                confidence = 65f,
                category = BeaconCategory.RETAIL_ANALYTICS,
                trackingPurpose = UltrasonicTrackingPurpose.RETAIL_ANALYTICS
            )
            isNearFrequency(frequencyHz, FREQ_SAMBA_TV) -> BeaconSourceAttribution(
                sourceName = "Samba TV ACR",
                manufacturer = "Samba TV",
                confidence = 65f,
                category = BeaconCategory.TV_ATTRIBUTION,
                trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
            )
            isNearFrequency(frequencyHz, FREQ_RETAIL_BAND_1) ||
            isNearFrequency(frequencyHz, FREQ_RETAIL_BAND_2) -> BeaconSourceAttribution(
                sourceName = "Retail Beacon",
                manufacturer = "Unknown",
                confidence = 50f,
                category = BeaconCategory.RETAIL_ANALYTICS,
                trackingPurpose = UltrasonicTrackingPurpose.RETAIL_ANALYTICS
            )
            isNearFrequency(frequencyHz, FREQ_INSCAPE) -> BeaconSourceAttribution(
                sourceName = "Inscape Smart TV",
                manufacturer = "Inscape (Vizio)",
                confidence = 60f,
                category = BeaconCategory.TV_ATTRIBUTION,
                trackingPurpose = UltrasonicTrackingPurpose.TV_ATTRIBUTION
            )
            isNearFrequency(frequencyHz, FREQ_DATA_PLUS_MATH) -> BeaconSourceAttribution(
                sourceName = "Data Plus Math Attribution",
                manufacturer = "LiveRamp (Data Plus Math)",
                confidence = 60f,
                category = BeaconCategory.ADVERTISING,
                trackingPurpose = UltrasonicTrackingPurpose.AD_TRACKING
            )
            else -> BeaconSourceAttribution(
                sourceName = "Unknown Ultrasonic Source",
                manufacturer = "Unknown",
                confidence = 30f,
                category = BeaconCategory.UNKNOWN,
                trackingPurpose = UltrasonicTrackingPurpose.UNKNOWN
            )
        }
    }

    /**
     * Calculate tracking likelihood score (0-100%).
     */
    fun calculateTrackingLikelihood(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution
    ): Float {
        var likelihood = attribution.confidence * 0.4f

        // Cross-location tracking is highly suspicious
        if (context.isFollowing) {
            likelihood += 25f
        } else if (context.detectedLocations >= FOLLOWING_LOCATION_THRESHOLD) {
            likelihood += 15f
        }

        // Persistent signals suggest intentional tracking
        if (context.persistenceScore >= HIGH_PERSISTENCE_THRESHOLD) {
            likelihood += 15f
        } else if (context.persistenceScore >= 0.4f) {
            likelihood += 8f
        }

        // Strong signals suggest proximity to source
        if (context.amplitudeDb >= STRONG_AMPLITUDE_THRESHOLD_DB) {
            likelihood += 10f
        }

        // High SNR indicates deliberate transmission
        if (context.snrDb >= HIGH_SNR_THRESHOLD_DB) {
            likelihood += 10f
        }

        // Known tracking categories increase likelihood
        when (attribution.category) {
            BeaconCategory.CROSS_DEVICE_LINKING -> likelihood += 15f
            BeaconCategory.ADVERTISING -> likelihood += 10f
            BeaconCategory.TV_ATTRIBUTION -> likelihood += 10f
            BeaconCategory.RETAIL_ANALYTICS -> likelihood += 5f
            else -> {}
        }

        // Modulation type can indicate sophistication
        if (context.modulationType in SOPHISTICATED_MODULATION_TYPES) {
            likelihood += 5f
        }

        return likelihood.coerceIn(0f, 100f)
    }

    /**
     * Generate an AI prompt for detailed beacon analysis.
     */
    fun generateAiPrompt(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution,
        trackingLikelihood: Float
    ): String {
        return buildString {
            appendLine("Analyze this ultrasonic tracking beacon detection.")
            appendLine()
            appendLine("=== BEACON FINGERPRINT ===")
            appendLine("Frequency: ${context.frequencyHz} Hz")
            appendLine("Beacon Type: ${attribution.sourceName}")
            appendLine("Manufacturer: ${attribution.manufacturer}")
            appendLine("Category: ${attribution.category.displayName}")
            appendLine("Source Confidence: ${String.format("%.0f", attribution.confidence)}%")
            appendLine()
            appendLine("=== AMPLITUDE ANALYSIS ===")
            appendLine("Current Amplitude: ${String.format("%.1f", context.amplitudeDb)} dB")
            appendLine("Noise Floor: ${String.format("%.1f", context.noiseFloorDb)} dB")
            appendLine("SNR: ${String.format("%.1f", context.snrDb)} dB")
            appendLine("Modulation Type: ${context.modulationType.name}")
            if (context.amplitudeHistory.isNotEmpty()) {
                val avgAmplitude = context.amplitudeHistory.average()
                val variance = context.amplitudeHistory.map { (it - avgAmplitude) * (it - avgAmplitude) }.average()
                appendLine("Average Amplitude: ${String.format("%.1f", avgAmplitude)} dB")
                appendLine("Amplitude Variance: ${String.format("%.2f", variance)}")
            }
            appendLine()
            appendLine("=== CROSS-LOCATION TRACKING ===")
            appendLine("Following User: ${if (context.isFollowing) "YES - Detected at multiple of your locations" else "No"}")
            appendLine("Locations Detected: ${context.detectedLocations}")
            appendLine("Total Detections: ${context.detectionCount}")
            appendLine("Detection Duration: ${context.durationMs / 60000} minutes")
            appendLine("Persistence Score: ${String.format("%.0f", context.persistenceScore * 100)}%")
            appendLine()
            appendLine("=== RISK ASSESSMENT ===")
            appendLine("Tracking Likelihood: ${String.format("%.0f", trackingLikelihood)}%")
            appendLine("Risk Indicators:")
            buildRiskIndicators(context, attribution, trackingLikelihood).forEach {
                appendLine("- $it")
            }
            appendLine()
            appendLine("Based on this enriched data, explain:")
            appendLine("1. What this ultrasonic beacon is doing (in plain English)")
            appendLine("2. How it tracks users across devices (if applicable)")
            appendLine("3. What company/technology is likely behind it")
            appendLine("4. How to stop or avoid this tracking")
            appendLine()
            appendLine("Format as:")
            appendLine("## What's Happening")
            appendLine("[Explanation of the beacon]")
            appendLine()
            appendLine("## How It Tracks You")
            appendLine("[Tracking mechanism]")
            appendLine()
            appendLine("## Likely Source")
            appendLine("[Who is behind this]")
            appendLine()
            appendLine("## Protection Steps")
            appendLine("1. [Step 1]")
            appendLine("2. [Step 2]")
            appendLine("3. [Step 3]")
        }
    }

    /**
     * Generate a concise AI prompt for quick analysis.
     */
    fun generateQuickAiPrompt(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution
    ): String {
        return buildString {
            appendLine("Quick analysis of ultrasonic beacon:")
            appendLine("- Frequency: ${context.frequencyHz} Hz")
            appendLine("- Source: ${attribution.sourceName} (${String.format("%.0f", attribution.confidence)}% match)")
            appendLine("- Category: ${attribution.category.displayName}")
            appendLine("- Amplitude: ${String.format("%.1f", context.amplitudeDb)} dB (SNR: ${String.format("%.1f", context.snrDb)} dB)")
            appendLine("- Following: ${if (context.isFollowing) "YES" else "No"} (${context.detectedLocations} locations)")
            appendLine()
            appendLine("Provide a 2-sentence summary of the privacy risk and one key action to take.")
        }
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private fun isNearFrequency(detected: Int, target: Int): Boolean {
        return abs(detected - target) <= FREQUENCY_TOLERANCE_HZ
    }

    private fun calculateSignatureConfidence(
        frequencyHz: Int,
        signature: UltrasonicTrackerSignature
    ): Float {
        val primaryDiff = abs(frequencyHz - signature.primaryFrequencyHz)
        val primaryConfidence = (1f - primaryDiff.toFloat() / FREQUENCY_TOLERANCE_HZ) * 100f

        // Check secondary frequencies if available
        val secondaryConfidence = signature.secondaryFrequencies
            .map { abs(frequencyHz - it) }
            .minOrNull()
            ?.let { (1f - it.toFloat() / FREQUENCY_TOLERANCE_HZ) * 100f }

        return maxOf(primaryConfidence, secondaryConfidence ?: 0f).coerceIn(30f, 95f)
    }

    private fun purposeToCategory(purpose: UltrasonicTrackingPurpose): BeaconCategory {
        return when (purpose) {
            UltrasonicTrackingPurpose.AD_TRACKING -> BeaconCategory.ADVERTISING
            UltrasonicTrackingPurpose.TV_ATTRIBUTION -> BeaconCategory.TV_ATTRIBUTION
            UltrasonicTrackingPurpose.RETAIL_ANALYTICS -> BeaconCategory.RETAIL_ANALYTICS
            UltrasonicTrackingPurpose.CROSS_DEVICE_LINKING -> BeaconCategory.CROSS_DEVICE_LINKING
            UltrasonicTrackingPurpose.LOCATION_VERIFICATION -> BeaconCategory.LOCATION_VERIFICATION
            UltrasonicTrackingPurpose.PRESENCE_DETECTION -> BeaconCategory.PRESENCE_DETECTION
            UltrasonicTrackingPurpose.UNKNOWN -> BeaconCategory.UNKNOWN
        }
    }

    private fun determineDetectionMethod(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution
    ): DetectionMethod {
        return when (attribution.category) {
            BeaconCategory.ADVERTISING -> DetectionMethod.ULTRASONIC_AD_BEACON
            BeaconCategory.TV_ATTRIBUTION -> DetectionMethod.ULTRASONIC_AD_BEACON
            BeaconCategory.RETAIL_ANALYTICS -> DetectionMethod.ULTRASONIC_RETAIL_BEACON
            BeaconCategory.CROSS_DEVICE_LINKING -> DetectionMethod.ULTRASONIC_CROSS_DEVICE
            BeaconCategory.LOCATION_VERIFICATION -> DetectionMethod.ULTRASONIC_RETAIL_BEACON
            BeaconCategory.PRESENCE_DETECTION -> {
                if (context.persistenceScore >= HIGH_PERSISTENCE_THRESHOLD) {
                    DetectionMethod.ULTRASONIC_CONTINUOUS
                } else {
                    DetectionMethod.ULTRASONIC_TRACKING_BEACON
                }
            }
            BeaconCategory.UNKNOWN -> {
                when {
                    context.isFollowing -> DetectionMethod.ULTRASONIC_CROSS_DEVICE
                    context.persistenceScore >= HIGH_PERSISTENCE_THRESHOLD -> DetectionMethod.ULTRASONIC_CONTINUOUS
                    else -> DetectionMethod.ULTRASONIC_UNKNOWN
                }
            }
        }
    }

    private fun calculateThreatLevel(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution
    ): ThreatLevel {
        // Cross-device tracking while following is critical
        if (context.isFollowing && attribution.category == BeaconCategory.CROSS_DEVICE_LINKING) {
            return ThreatLevel.CRITICAL
        }

        // Following user across locations is high
        if (context.isFollowing) {
            return ThreatLevel.HIGH
        }

        // Known ad/TV tracking beacons are high threat
        if (attribution.category in HIGH_THREAT_BEACON_CATEGORIES && attribution.confidence >= 70f) {
            return ThreatLevel.HIGH
        }

        // Persistent unknown sources are medium-high
        if (context.persistenceScore >= HIGH_PERSISTENCE_THRESHOLD &&
            attribution.category == BeaconCategory.UNKNOWN) {
            return ThreatLevel.MEDIUM
        }

        // Known retail beacons are medium
        if (attribution.category == BeaconCategory.RETAIL_ANALYTICS) {
            return ThreatLevel.MEDIUM
        }

        // Unknown sources with some detection are low
        if (attribution.category == BeaconCategory.UNKNOWN) {
            return ThreatLevel.LOW
        }

        return ThreatLevel.MEDIUM
    }

    private fun calculateThreatScore(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution,
        trackingLikelihood: Float
    ): Int {
        var score = (trackingLikelihood * 0.6f).toInt()

        // Add points for threat indicators
        if (context.isFollowing) score += 20
        if (context.detectedLocations >= 3) score += 10
        if (context.persistenceScore >= HIGH_PERSISTENCE_THRESHOLD) score += 10
        if (attribution.confidence >= 80f) score += 10

        // Category-based adjustments
        when (attribution.category) {
            BeaconCategory.CROSS_DEVICE_LINKING -> score += 15
            BeaconCategory.ADVERTISING -> score += 10
            BeaconCategory.TV_ATTRIBUTION -> score += 10
            else -> {}
        }

        return score.coerceIn(0, 100)
    }

    private fun amplitudeToSignalStrength(amplitudeDb: Double): SignalStrength {
        return when {
            amplitudeDb > -30 -> SignalStrength.EXCELLENT
            amplitudeDb > -40 -> SignalStrength.GOOD
            amplitudeDb > -50 -> SignalStrength.MEDIUM
            amplitudeDb > -60 -> SignalStrength.WEAK
            else -> SignalStrength.VERY_WEAK
        }
    }

    private fun buildDeviceName(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution
    ): String {
        return if (attribution.manufacturer != "Unknown") {
            "${attribution.sourceName} (${context.frequencyHz}Hz)"
        } else {
            "Ultrasonic Beacon ${context.frequencyHz}Hz"
        }
    }

    private fun buildMatchedPatterns(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution,
        trackingLikelihood: Float
    ): String {
        val patterns = mutableListOf<String>()

        patterns.add("Frequency: ${context.frequencyHz}Hz")
        patterns.add("Source: ${attribution.sourceName}")
        patterns.add("Category: ${attribution.category.displayName}")
        patterns.add("Tracking Likelihood: ${String.format("%.0f", trackingLikelihood)}%")

        if (context.isFollowing) {
            patterns.add("FOLLOWING USER across ${context.detectedLocations} locations")
        }

        if (context.persistenceScore >= HIGH_PERSISTENCE_THRESHOLD) {
            patterns.add("Persistent signal (${String.format("%.0f", context.persistenceScore * 100)}%)")
        }

        return patterns.joinToString(", ")
    }

    private fun buildRiskIndicators(
        context: UltrasonicDetectionContext,
        attribution: BeaconSourceAttribution,
        trackingLikelihood: Float
    ): List<String> {
        val indicators = mutableListOf<String>()

        if (attribution.manufacturer != "Unknown") {
            indicators.add("Matches ${attribution.manufacturer} beacon signature (${String.format("%.0f", attribution.confidence)}% confidence)")
        }

        if (context.isFollowing) {
            indicators.add("Detected at ${context.detectedLocations} different locations you visited")
        }

        if (context.persistenceScore >= HIGH_PERSISTENCE_THRESHOLD) {
            indicators.add("Persistent signal (active for ${context.durationMs / 1000}s)")
        }

        if (context.snrDb >= HIGH_SNR_THRESHOLD_DB) {
            indicators.add("Strong signal above noise floor (SNR: ${String.format("%.1f", context.snrDb)} dB)")
        }

        if (context.amplitudeDb >= STRONG_AMPLITUDE_THRESHOLD_DB) {
            indicators.add("High amplitude suggests close proximity to source")
        }

        if (context.detectionCount > 10) {
            indicators.add("Repeatedly detected (${context.detectionCount} times)")
        }

        when (attribution.category) {
            BeaconCategory.CROSS_DEVICE_LINKING ->
                indicators.add("Cross-device linking - designed to track across multiple devices")
            BeaconCategory.ADVERTISING ->
                indicators.add("Advertising attribution - links TV/web ads to your phone")
            BeaconCategory.TV_ATTRIBUTION ->
                indicators.add("TV attribution - correlates TV viewing with mobile activity")
            BeaconCategory.RETAIL_ANALYTICS ->
                indicators.add("Retail analytics - tracks in-store location and behavior")
            else -> {}
        }

        if (context.modulationType in SOPHISTICATED_MODULATION_TYPES) {
            indicators.add("Sophisticated modulation (${context.modulationType.name}) - indicates data encoding")
        }

        if (trackingLikelihood >= 80f) {
            indicators.add("HIGH tracking likelihood (${String.format("%.0f", trackingLikelihood)}%)")
        }

        return indicators
    }
}

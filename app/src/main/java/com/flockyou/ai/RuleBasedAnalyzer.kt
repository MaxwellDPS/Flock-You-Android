package com.flockyou.ai

import android.util.Log
import com.flockyou.data.*
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.detection.profile.DeviceTypeProfile
import com.flockyou.detection.profile.DeviceTypeProfileRegistry
import com.flockyou.detection.profile.PrivacyImpact
import com.flockyou.detection.profile.RecommendationUrgency
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fast rule-based analyzer for instant feedback.
 * Provides quick analysis (< 10ms) based on:
 * - Pattern matching against known device types
 * - Threat level estimation based on device type and signal strength
 * - Basic recommendations based on threat category
 *
 * This analyzer is used as the first stage in the progressive analysis pipeline
 * to give users immediate feedback while LLM analysis runs in background.
 */
@Singleton
class RuleBasedAnalyzer @Inject constructor() {

    companion object {
        private const val TAG = "RuleBasedAnalyzer"
    }

    /**
     * Instant analysis based on known patterns (< 10ms).
     * Primary entry point for the progressive analysis pipeline.
     *
     * @param detection The detection to analyze
     * @return Quick analysis result based on rules and device profiles
     */
    fun analyzeQuick(detection: Detection): AiAnalysisResult {
        return analyzeQuickly(detection, null)
    }

    /**
     * Perform quick rule-based analysis on a detection.
     * This is designed to complete in < 10ms for instant user feedback.
     *
     * @param detection The detection to analyze
     * @param contextualInsights Optional context from location/history
     * @return Quick analysis result
     */
    fun analyzeQuickly(
        detection: Detection,
        contextualInsights: ContextualInsights? = null
    ): AiAnalysisResult {
        val startTime = System.currentTimeMillis()

        try {
            // Get device profile from registry (O(1) lookup)
            val profile = DeviceTypeProfileRegistry.getProfile(detection.deviceType)

            // Build analysis components
            val analysis = buildQuickAnalysis(detection, profile, contextualInsights)
            val recommendations = buildQuickRecommendations(detection, profile, contextualInsights)
            val structuredData = buildQuickStructuredData(detection, profile, contextualInsights)

            val processingTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Quick analysis completed in ${processingTime}ms for ${detection.deviceType}")

            return AiAnalysisResult(
                success = true,
                analysis = analysis,
                recommendations = recommendations,
                confidence = calculateQuickConfidence(detection, profile),
                processingTimeMs = processingTime,
                modelUsed = "rule-based-quick",
                wasOnDevice = true,
                structuredData = structuredData,
                simpleExplanation = buildSimpleExplanation(detection, profile),
                technicalDetails = buildTechnicalDetails(detection)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Quick analysis failed", e)
            return AiAnalysisResult(
                success = false,
                error = e.message ?: "Quick analysis failed",
                processingTimeMs = System.currentTimeMillis() - startTime,
                modelUsed = "rule-based-quick",
                wasOnDevice = true
            )
        }
    }

    /**
     * Build a quick analysis string from device profile and detection data.
     */
    private fun buildQuickAnalysis(
        detection: Detection,
        profile: DeviceTypeProfile,
        contextualInsights: ContextualInsights?
    ): String {
        return buildString {
            // Device identification
            appendLine("### ${detection.deviceType.displayName}")
            appendLine()
            appendLine(profile.description)
            appendLine()

            // Threat assessment
            appendLine("### Threat Assessment")
            appendLine("**Level:** ${detection.threatLevel.displayName}")
            appendLine("**Score:** ${detection.threatScore}/100")
            appendLine("**Privacy Impact:** ${profile.privacyImpact.displayName}")
            appendLine()

            // Signal information
            appendLine("### Signal Analysis")
            appendLine("- Signal Strength: ${detection.signalStrength.displayName} (${detection.rssi} dBm)")
            appendLine("- Detection Method: ${detection.detectionMethod.displayName}")
            appendLine("- Protocol: ${detection.protocol.displayName}")
            detection.manufacturer?.let { appendLine("- Manufacturer: $it") }
            appendLine()

            // Data collection
            if (profile.dataCollected.isNotEmpty()) {
                appendLine("### Data Collection")
                profile.dataCollected.take(4).forEach { data ->
                    appendLine("- $data")
                }
                if (profile.dataCollected.size > 4) {
                    appendLine("- _(${profile.dataCollected.size - 4} more...)_")
                }
                appendLine()
            }

            // Contextual information
            contextualInsights?.let { context ->
                appendLine("### Context")
                context.locationPattern?.let { appendLine("- Location: $it") }
                context.timePattern?.let { appendLine("- Time: $it") }
                context.clusterInfo?.let { appendLine("- Cluster: $it") }
                context.historicalContext?.let { appendLine("- History: $it") }
            }
        }
    }

    /**
     * Build quick recommendations from profile and threat level.
     */
    private fun buildQuickRecommendations(
        detection: Detection,
        profile: DeviceTypeProfile,
        contextualInsights: ContextualInsights?
    ): List<String> {
        val recommendations = mutableListOf<String>()

        // Threat-level based recommendations
        when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> {
                recommendations.add("Consider leaving the area if safety allows")
                recommendations.add("Enable airplane mode or use a Faraday bag")
                recommendations.add("Use only end-to-end encrypted communications")
            }
            ThreatLevel.HIGH -> {
                recommendations.add("Be aware your presence is being recorded")
                recommendations.add("Consider varying your routes and patterns")
                recommendations.add("Use VPN and encrypted messaging apps")
            }
            ThreatLevel.MEDIUM -> {
                recommendations.add("Note this location for future awareness")
                recommendations.add("Review privacy settings on your devices")
            }
            ThreatLevel.LOW, ThreatLevel.INFO -> {
                recommendations.add("No immediate action required")
            }
        }

        // Add profile-specific recommendations
        profile.recommendations
            .sortedBy { it.priority }
            .take(3)
            .forEach { rec ->
                if (rec.action !in recommendations) {
                    recommendations.add(rec.action)
                }
            }

        // Context-based recommendations
        contextualInsights?.clusterInfo?.let {
            recommendations.add("High-surveillance area - multiple devices detected")
        }

        return recommendations.distinct().take(6)
    }

    /**
     * Build structured analysis data quickly.
     */
    private fun buildQuickStructuredData(
        detection: Detection,
        profile: DeviceTypeProfile,
        contextualInsights: ContextualInsights?
    ): StructuredAnalysis {
        val riskFactors = mutableListOf<String>()

        // Add risk factors based on threat level
        when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> riskFactors.add("Active surveillance capability")
            ThreatLevel.HIGH -> riskFactors.add("Confirmed surveillance device")
            else -> {}
        }

        // Signal-based risk
        if (detection.signalStrength.ordinal <= 1) {
            riskFactors.add("Close proximity (strong signal)")
        }

        // Context-based risk
        contextualInsights?.let {
            if (it.clusterInfo != null) riskFactors.add("Part of surveillance network")
            if (it.isKnownLocation) riskFactors.add("Persistent presence at location")
        }

        if (detection.seenCount > 5) {
            riskFactors.add("Repeatedly detected (${detection.seenCount} times)")
        }

        // Calculate risk score
        var riskScore = detection.threatScore
        contextualInsights?.let {
            if (it.clusterInfo != null) riskScore += 10
            if (it.historicalContext?.contains("detected") == true) riskScore += 5
        }
        riskScore = riskScore.coerceIn(0, 100)

        // Build mitigation actions
        val actions = profile.recommendations
            .sortedBy { it.priority }
            .take(4)
            .mapIndexed { index, rec ->
                MitigationAction(
                    action = rec.action,
                    priority = when (index) {
                        0 -> if (rec.urgency == RecommendationUrgency.IMMEDIATE) ActionPriority.IMMEDIATE else ActionPriority.HIGH
                        1 -> ActionPriority.HIGH
                        2 -> ActionPriority.MEDIUM
                        else -> ActionPriority.LOW
                    },
                    description = rec.explanation ?: rec.action
                )
            }

        return StructuredAnalysis(
            deviceCategory = profile.category,
            surveillanceType = profile.surveillanceType,
            dataCollectionTypes = profile.dataCollected,
            riskScore = riskScore,
            riskFactors = riskFactors,
            mitigationActions = actions,
            contextualInsights = contextualInsights
        )
    }

    /**
     * Calculate confidence score for quick analysis.
     * Based on how well the detection matches known patterns.
     */
    private fun calculateQuickConfidence(
        detection: Detection,
        profile: DeviceTypeProfile
    ): Float {
        var confidence = 0.7f // Base confidence for rule-based

        // Higher confidence for known device types
        if (detection.deviceType != DeviceType.UNKNOWN_SURVEILLANCE) {
            confidence += 0.1f
        }

        // Higher confidence with manufacturer info
        if (detection.manufacturer != null) {
            confidence += 0.05f
        }

        // Higher confidence with stronger signals
        if (detection.signalStrength.ordinal <= 1) {
            confidence += 0.05f
        }

        // Lower confidence for very new detections
        if (detection.seenCount == 1) {
            confidence -= 0.05f
        }

        return confidence.coerceIn(0.5f, 0.9f)
    }

    /**
     * Build a simple, user-friendly explanation.
     */
    private fun buildSimpleExplanation(
        detection: Detection,
        profile: DeviceTypeProfile
    ): String {
        val deviceName = detection.deviceType.displayName

        return when (profile.privacyImpact) {
            PrivacyImpact.CRITICAL -> "A $deviceName was detected nearby. This is a high-priority surveillance device that may be actively monitoring communications or tracking your location. Consider taking protective measures."

            PrivacyImpact.HIGH -> "A $deviceName was found in your area. This device can collect information about your movements and activities. Be aware that your presence may be recorded."

            PrivacyImpact.MEDIUM -> "A $deviceName was detected. This device may collect some data about nearby activity, but poses moderate privacy concerns for most users."

            PrivacyImpact.LOW -> "A $deviceName was detected nearby. This device has limited surveillance capabilities and poses minimal immediate privacy concerns."

            PrivacyImpact.MINIMAL -> profile.simpleDescription
                ?: "A $deviceName was detected. This appears to be standard infrastructure or a consumer device with minimal privacy implications."
        }
    }

    /**
     * Build technical details for advanced users.
     */
    private fun buildTechnicalDetails(detection: Detection): String {
        return buildString {
            append("Type: ${detection.deviceType.name}")
            append(" | Protocol: ${detection.protocol.name}")
            append(" | Method: ${detection.detectionMethod.name}")
            append(" | RSSI: ${detection.rssi} dBm")
            append(" | Score: ${detection.threatScore}/100")
            detection.macAddress?.let { append(" | MAC: ${it.take(8)}...") }
            detection.matchedPatterns?.let { append(" | Patterns: $it") }
        }
    }

    /**
     * Check if a detection matches known surveillance patterns.
     * Returns a confidence score from 0-100.
     */
    fun getPatternMatchConfidence(detection: Detection): Int {
        val profile = DeviceTypeProfileRegistry.getProfile(detection.deviceType)

        var confidence = 50 // Base confidence

        // Known device type boost
        if (detection.deviceType != DeviceType.UNKNOWN_SURVEILLANCE) {
            confidence += 20
        }

        // Manufacturer verification boost
        if (detection.manufacturer != null) {
            confidence += 10
        }

        // Matched patterns boost
        if (!detection.matchedPatterns.isNullOrEmpty()) {
            confidence += 15
        }

        // Strong signal boost (closer = more confident)
        if (detection.rssi > -60) {
            confidence += 5
        }

        return confidence.coerceIn(0, 100)
    }

    /**
     * Estimate threat level based on device type and signal strength.
     * Used for quick categorization before full analysis.
     */
    fun estimateThreatLevel(detection: Detection): ThreatLevel {
        val profile = DeviceTypeProfileRegistry.getProfile(detection.deviceType)

        // Map privacy impact to threat level
        val baseThreat = when (profile.privacyImpact) {
            PrivacyImpact.CRITICAL -> ThreatLevel.CRITICAL
            PrivacyImpact.HIGH -> ThreatLevel.HIGH
            PrivacyImpact.MEDIUM -> ThreatLevel.MEDIUM
            PrivacyImpact.LOW -> ThreatLevel.LOW
            PrivacyImpact.MINIMAL -> ThreatLevel.INFO
        }

        // Adjust based on signal strength (closer = potentially higher threat)
        return if (detection.rssi > -50 && baseThreat.ordinal < ThreatLevel.CRITICAL.ordinal) {
            // Very close device - bump up threat by one level
            ThreatLevel.entries[baseThreat.ordinal + 1]
        } else {
            baseThreat
        }
    }

    /**
     * Get quick category for a detection.
     */
    fun getCategory(detection: Detection): String {
        return DeviceTypeProfileRegistry.getProfile(detection.deviceType).category
    }

    /**
     * Check if detection is likely benign based on device type.
     */
    fun isLikelyBenign(detection: Detection): Boolean {
        val profile = DeviceTypeProfileRegistry.getProfile(detection.deviceType)
        return profile.privacyImpact == PrivacyImpact.MINIMAL ||
               profile.privacyImpact == PrivacyImpact.LOW
    }

    // ==================== PROGRESSIVE ANALYSIS API ====================

    /**
     * Get threat assessment from device type.
     * Maps device type profile to threat level for quick categorization.
     *
     * @param deviceType The type of device detected
     * @return Estimated threat level based on device profile
     */
    fun assessThreatFromType(deviceType: DeviceType): ThreatLevel {
        val profile = DeviceTypeProfileRegistry.getProfile(deviceType)

        // Map privacy impact to threat level
        return when (profile.privacyImpact) {
            PrivacyImpact.CRITICAL -> ThreatLevel.CRITICAL
            PrivacyImpact.HIGH -> ThreatLevel.HIGH
            PrivacyImpact.MEDIUM -> ThreatLevel.MEDIUM
            PrivacyImpact.LOW -> ThreatLevel.LOW
            PrivacyImpact.MINIMAL -> ThreatLevel.INFO
        }
    }

    /**
     * Get data collection capabilities from device type.
     * Returns a list of data types that this device category typically collects.
     *
     * @param deviceType The type of device detected
     * @return List of data types collected by this device type
     */
    fun getDataTypesForDevice(deviceType: DeviceType): List<String> {
        val profile = DeviceTypeProfileRegistry.getProfile(deviceType)
        return profile.dataCollected.toList()
    }

    /**
     * Get basic recommendations based on threat level.
     * Provides generic, actionable advice appropriate for the given threat level.
     *
     * @param threatLevel The assessed threat level
     * @return List of mitigation actions appropriate for this threat level
     */
    fun getBasicRecommendations(threatLevel: ThreatLevel): List<MitigationAction> {
        return when (threatLevel) {
            ThreatLevel.CRITICAL -> listOf(
                MitigationAction(
                    action = "Consider leaving the area if safety allows",
                    priority = ActionPriority.IMMEDIATE,
                    description = "Your immediate safety and privacy may be at risk"
                ),
                MitigationAction(
                    action = "Enable airplane mode or use a Faraday bag",
                    priority = ActionPriority.IMMEDIATE,
                    description = "Block all wireless signals from your devices"
                ),
                MitigationAction(
                    action = "Use only end-to-end encrypted communications",
                    priority = ActionPriority.HIGH,
                    description = "Ensure your messages cannot be intercepted"
                ),
                MitigationAction(
                    action = "Document the detection for future reference",
                    priority = ActionPriority.MEDIUM,
                    description = "Record location, time, and device details"
                )
            )
            ThreatLevel.HIGH -> listOf(
                MitigationAction(
                    action = "Be aware your presence is being recorded",
                    priority = ActionPriority.HIGH,
                    description = "This device may be collecting data about you"
                ),
                MitigationAction(
                    action = "Consider varying your routes and patterns",
                    priority = ActionPriority.HIGH,
                    description = "Make your movements less predictable"
                ),
                MitigationAction(
                    action = "Use VPN and encrypted messaging apps",
                    priority = ActionPriority.MEDIUM,
                    description = "Add layers of protection to your communications"
                ),
                MitigationAction(
                    action = "Review privacy settings on your devices",
                    priority = ActionPriority.LOW,
                    description = "Minimize data exposure from your own devices"
                )
            )
            ThreatLevel.MEDIUM -> listOf(
                MitigationAction(
                    action = "Note this location for future awareness",
                    priority = ActionPriority.MEDIUM,
                    description = "Be aware of surveillance presence in this area"
                ),
                MitigationAction(
                    action = "Review privacy settings on your devices",
                    priority = ActionPriority.MEDIUM,
                    description = "Ensure your devices are not unnecessarily sharing data"
                ),
                MitigationAction(
                    action = "Consider using privacy-focused apps",
                    priority = ActionPriority.LOW,
                    description = "Switch to apps that minimize data collection"
                )
            )
            ThreatLevel.LOW -> listOf(
                MitigationAction(
                    action = "No immediate action required",
                    priority = ActionPriority.LOW,
                    description = "This device poses minimal privacy concerns"
                ),
                MitigationAction(
                    action = "Continue monitoring if this device reappears",
                    priority = ActionPriority.LOW,
                    description = "Repeated detection may indicate tracking"
                )
            )
            ThreatLevel.INFO -> listOf(
                MitigationAction(
                    action = "No action required",
                    priority = ActionPriority.LOW,
                    description = "This is informational only"
                )
            )
        }
    }
}

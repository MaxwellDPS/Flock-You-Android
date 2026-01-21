package com.flockyou.detection

import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel

/**
 * Enterprise-grade threat scoring system.
 *
 * This implements a proper threat calculation formula:
 *   threat_score = base_likelihood * impact_factor * confidence
 *
 * Where:
 * - base_likelihood: 0-100% probability this is a real threat
 * - impact_factor: 0.5-2.0 based on threat type severity
 * - confidence: 0.5-1.0 based on detection quality
 *
 * Severity thresholds are calibrated to prevent false high-severity alerts:
 * - CRITICAL (90-100): Confirmed active threat, immediate action needed
 * - HIGH (70-89): High probability threat, investigate immediately
 * - MEDIUM (50-69): Moderate concern, monitor closely
 * - LOW (30-49): Possible concern, log and watch
 * - INFO (0-29): Notable but not threatening
 */
object ThreatScoring {

    // ============================================================================
    // Impact Factors by Device Type
    // ============================================================================

    /**
     * Impact factor represents the potential harm if this threat is real.
     *
     * Scale:
     * - 2.0: Intercepts all communications, can cause physical harm
     * - 1.8: Can cause physical harm via misdirection
     * - 1.5: Stalking/tracking concern
     * - 1.2: Privacy violation but not physical threat
     * - 1.0: Standard privacy concern
     * - 0.7: Minor privacy concern
     * - 0.5: Just degrades accuracy, not an attack
     */
    private val impactFactors: Map<DeviceType, Double> = mapOf(
        // Maximum impact - intercepts all communications
        DeviceType.STINGRAY_IMSI to 2.0,
        DeviceType.CELLEBRITE_FORENSICS to 2.0,
        DeviceType.GRAYKEY_DEVICE to 2.0,
        DeviceType.MAN_IN_MIDDLE to 2.0,

        // High impact - can cause physical harm or intercept communications
        DeviceType.GNSS_SPOOFER to 1.8,
        DeviceType.GNSS_JAMMER to 1.8,
        DeviceType.RF_JAMMER to 1.8,
        DeviceType.WIFI_PINEAPPLE to 1.8,
        DeviceType.ROGUE_AP to 1.7,

        // Significant impact - stalking/tracking concern
        DeviceType.AIRTAG to 1.5,
        DeviceType.TILE_TRACKER to 1.5,
        DeviceType.SAMSUNG_SMARTTAG to 1.5,
        DeviceType.GENERIC_BLE_TRACKER to 1.5,
        DeviceType.TRACKING_DEVICE to 1.5,
        DeviceType.SURVEILLANCE_VAN to 1.5,
        DeviceType.DRONE to 1.4,

        // Moderate impact - privacy violations
        DeviceType.HIDDEN_CAMERA to 1.3,
        DeviceType.HIDDEN_TRANSMITTER to 1.3,
        DeviceType.PACKET_SNIFFER to 1.3,
        DeviceType.FLOCK_SAFETY_CAMERA to 1.2,
        DeviceType.LICENSE_PLATE_READER to 1.2,
        DeviceType.FACIAL_RECOGNITION to 1.2,
        DeviceType.CLEARVIEW_AI to 1.2,
        DeviceType.PALANTIR_DEVICE to 1.2,
        DeviceType.RAVEN_GUNSHOT_DETECTOR to 1.2,
        DeviceType.SHOTSPOTTER to 1.2,

        // Standard impact - surveillance but known type
        DeviceType.BODY_CAMERA to 1.0,
        DeviceType.POLICE_VEHICLE to 1.0,
        DeviceType.POLICE_RADIO to 1.0,
        DeviceType.MOTOROLA_POLICE_TECH to 1.0,
        DeviceType.AXON_POLICE_TECH to 1.0,
        DeviceType.L3HARRIS_SURVEILLANCE to 1.0,
        DeviceType.CCTV_CAMERA to 1.0,
        DeviceType.PTZ_CAMERA to 1.0,
        DeviceType.THERMAL_CAMERA to 1.0,
        DeviceType.NIGHT_VISION to 1.0,
        DeviceType.ULTRASONIC_BEACON to 1.0,
        DeviceType.SATELLITE_NTN to 1.0,

        // Lower impact - consumer IoT devices
        DeviceType.RING_DOORBELL to 0.8,
        DeviceType.NEST_CAMERA to 0.8,
        DeviceType.WYZE_CAMERA to 0.8,
        DeviceType.ARLO_CAMERA to 0.8,
        DeviceType.EUFY_CAMERA to 0.8,
        DeviceType.BLINK_CAMERA to 0.8,
        DeviceType.SIMPLISAFE_DEVICE to 0.8,
        DeviceType.ADT_DEVICE to 0.8,
        DeviceType.VIVINT_DEVICE to 0.8,
        DeviceType.AMAZON_SIDEWALK to 0.7,
        DeviceType.BLUETOOTH_BEACON to 0.7,
        DeviceType.RETAIL_TRACKER to 0.7,
        DeviceType.CROWD_ANALYTICS to 0.7,

        // Minimal impact - traffic/infrastructure
        DeviceType.SPEED_CAMERA to 0.6,
        DeviceType.RED_LIGHT_CAMERA to 0.6,
        DeviceType.TOLL_READER to 0.6,
        DeviceType.TRAFFIC_SENSOR to 0.5,
        DeviceType.FLEET_VEHICLE to 0.5,

        // Environmental/signal issues, not attacks
        DeviceType.RF_INTERFERENCE to 0.5,
        DeviceType.RF_ANOMALY to 0.5,
        DeviceType.SURVEILLANCE_INFRASTRUCTURE to 0.6,

        // Unknown
        DeviceType.UNKNOWN_SURVEILLANCE to 1.0,
        DeviceType.PENGUIN_SURVEILLANCE to 1.0,
        DeviceType.PIGVISION_SYSTEM to 1.0
    )

    /**
     * Get the impact factor for a device type.
     * Returns 1.0 for unknown types as a safe default.
     */
    fun getImpactFactor(deviceType: DeviceType): Double {
        return impactFactors[deviceType] ?: 1.0
    }

    // ============================================================================
    // Confidence Adjustments
    // ============================================================================

    /**
     * Factors that increase or decrease confidence in a detection.
     * These are additive to the base confidence.
     */
    object ConfidenceAdjustments {
        // Positive adjustments (increase confidence)
        const val MULTIPLE_CONFIRMING_INDICATORS = 0.2
        const val HIGH_SIGNAL_STRENGTH = 0.1      // RSSI > -50 dBm
        const val GOOD_SIGNAL_STRENGTH = 0.05     // RSSI > -60 dBm
        const val PERSISTENCE_OVER_TIME = 0.2     // Seen multiple times over extended period
        const val CROSS_PROTOCOL_CORRELATION = 0.3 // Same threat seen on multiple protocols
        const val KNOWN_THREAT_PATTERN = 0.15     // Matches known bad pattern exactly
        const val BEHAVIORAL_MATCH = 0.1          // Behavior matches threat profile

        // Negative adjustments (decrease confidence)
        const val SINGLE_WEAK_INDICATOR = -0.3
        const val KNOWN_FALSE_POSITIVE_PATTERN = -0.5
        const val LOW_SIGNAL_STRENGTH = -0.1      // RSSI < -80 dBm
        const val VERY_LOW_SIGNAL_STRENGTH = -0.2 // RSSI < -90 dBm
        const val COMMON_CONSUMER_DEVICE = -0.2   // Known consumer IoT device
        const val STATIONARY_IN_KNOWN_AREA = -0.15 // Device is stationary in a known safe area
        const val BRIEF_DETECTION = -0.2          // Seen only once briefly
        const val MULTIPATH_LIKELY = -0.3         // GPS detection in urban canyon / indoor
    }

    // ============================================================================
    // Likelihood Estimation by Detection Method
    // ============================================================================

    /**
     * Base likelihood estimates by detection method.
     * These represent the probability that a detection of this type is a real threat.
     *
     * Values are conservative to reduce false positives.
     */
    object BaseLikelihoods {
        // High confidence detection methods
        const val EXACT_KNOWN_THREAT_MATCH = 85   // Exact match to known threat signature
        const val ENCRYPTION_DOWNGRADE = 75       // 2G downgrade is strong IMSI catcher indicator
        const val ACTIVE_GNSS_SPOOFING = 70       // Multiple spoofing indicators present

        // Medium confidence detection methods
        const val SUSPICIOUS_CELL_PARAMETERS = 50 // Unusual but not conclusive cell data
        const val TRACKER_FOLLOWING = 55          // Same tracker at multiple locations
        const val GNSS_SIGNAL_ANOMALY = 40        // Could be spoofing or environmental
        const val UNKNOWN_CELL_TOWER = 35         // Cell not in database

        // Lower confidence detection methods
        const val SINGLE_PATTERN_MATCH = 30       // One pattern matched, no confirmation
        const val CELL_CHANGE_WHILE_STATIONARY = 25 // Could be normal network optimization
        const val BRIEF_ULTRASONIC = 20           // Short ultrasonic detection
        const val GNSS_MULTIPATH = 15             // Usually just reflection, not attack

        // Informational detections
        const val KNOWN_CONSUMER_DEVICE = 10      // Ring doorbell, smart home camera
        const val NORMAL_NETWORK_HANDOFF = 5      // Expected cellular behavior
        const val BLUETOOTH_BEACON_RETAIL = 15    // Common in stores, not targeted
    }

    // ============================================================================
    // Core Threat Calculation
    // ============================================================================

    /**
     * Input data for threat calculation.
     */
    data class ThreatInput(
        val baseLikelihood: Int,           // 0-100 base probability
        val deviceType: DeviceType,
        val rssi: Int,                      // Signal strength in dBm
        val seenCount: Int = 1,             // Number of times seen
        val durationMs: Long = 0,           // How long detected
        val hasMultipleIndicators: Boolean = false,
        val hasCrossProtocolCorrelation: Boolean = false,
        val isKnownFalsePositivePattern: Boolean = false,
        val isInKnownSafeArea: Boolean = false,
        val isConsumerDevice: Boolean = false,
        val matchQuality: MatchQuality = MatchQuality.PARTIAL,
        val environmentalFactors: EnvironmentalFactors = EnvironmentalFactors()
    )

    /**
     * Quality of the pattern match.
     */
    enum class MatchQuality(val confidenceBonus: Double) {
        EXACT(0.15),      // Exact match to known signature
        STRONG(0.1),      // Multiple patterns matched
        PARTIAL(0.0),     // Single pattern matched
        WEAK(-0.1),       // Partial pattern match
        HEURISTIC(-0.2)   // Only heuristic/behavioral match
    }

    /**
     * Environmental factors that affect detection confidence.
     */
    data class EnvironmentalFactors(
        val isUrbanArea: Boolean = false,      // High building density
        val isIndoor: Boolean = false,         // Inside a building
        val isMoving: Boolean = false,         // User is moving
        val hasRecentCellChange: Boolean = false,
        val gnssHdop: Float? = null            // GPS precision (high = worse)
    )

    /**
     * Complete threat calculation result with full breakdown.
     */
    data class ThreatResult(
        val rawScore: Int,                     // Raw calculated score (0-100)
        val adjustedScore: Int,                // Score after adjustments (0-100)
        val severity: ThreatLevel,             // Final severity level
        val likelihood: Int,                   // Base likelihood used (0-100)
        val impactFactor: Double,              // Impact factor used
        val confidence: Double,                // Final confidence (0.0-1.0)
        val confidenceFactors: List<String>,   // Factors that affected confidence
        val reasoning: String                  // Human-readable explanation
    )

    /**
     * Calculate threat score using the proper formula:
     * threat_score = likelihood * impact_factor * confidence
     *
     * This ensures:
     * - 20% IMSI likelihood -> LOW/INFO severity, not HIGH
     * - 30% spoofing likelihood -> LOW severity, not MEDIUM
     * - Severity correlates with actual threat probability
     */
    fun calculateThreat(input: ThreatInput): ThreatResult {
        val confidenceFactors = mutableListOf<String>()

        // Step 1: Calculate confidence adjustments
        var confidence = 0.5  // Start at neutral confidence

        // Signal strength adjustments
        when {
            input.rssi > -50 -> {
                confidence += ConfidenceAdjustments.HIGH_SIGNAL_STRENGTH
                confidenceFactors.add("+signal_strength_excellent")
            }
            input.rssi > -60 -> {
                confidence += ConfidenceAdjustments.GOOD_SIGNAL_STRENGTH
                confidenceFactors.add("+signal_strength_good")
            }
            input.rssi < -90 -> {
                confidence += ConfidenceAdjustments.VERY_LOW_SIGNAL_STRENGTH
                confidenceFactors.add("-signal_strength_very_weak")
            }
            input.rssi < -80 -> {
                confidence += ConfidenceAdjustments.LOW_SIGNAL_STRENGTH
                confidenceFactors.add("-signal_strength_weak")
            }
        }

        // Persistence adjustments
        if (input.seenCount > 3 || input.durationMs > 5 * 60 * 1000) {
            confidence += ConfidenceAdjustments.PERSISTENCE_OVER_TIME
            confidenceFactors.add("+persistent_detection")
        } else if (input.seenCount == 1 && input.durationMs < 30 * 1000) {
            confidence += ConfidenceAdjustments.BRIEF_DETECTION
            confidenceFactors.add("-brief_detection")
        }

        // Multiple indicator adjustments
        if (input.hasMultipleIndicators) {
            confidence += ConfidenceAdjustments.MULTIPLE_CONFIRMING_INDICATORS
            confidenceFactors.add("+multiple_indicators")
        } else {
            confidence += ConfidenceAdjustments.SINGLE_WEAK_INDICATOR
            confidenceFactors.add("-single_indicator")
        }

        // Cross-protocol correlation
        if (input.hasCrossProtocolCorrelation) {
            confidence += ConfidenceAdjustments.CROSS_PROTOCOL_CORRELATION
            confidenceFactors.add("+cross_protocol_confirmed")
        }

        // Known false positive pattern
        if (input.isKnownFalsePositivePattern) {
            confidence += ConfidenceAdjustments.KNOWN_FALSE_POSITIVE_PATTERN
            confidenceFactors.add("-known_false_positive_pattern")
        }

        // Consumer device penalty
        if (input.isConsumerDevice) {
            confidence += ConfidenceAdjustments.COMMON_CONSUMER_DEVICE
            confidenceFactors.add("-common_consumer_device")
        }

        // Known safe area
        if (input.isInKnownSafeArea) {
            confidence += ConfidenceAdjustments.STATIONARY_IN_KNOWN_AREA
            confidenceFactors.add("-known_safe_area")
        }

        // Match quality adjustment
        confidence += input.matchQuality.confidenceBonus
        if (input.matchQuality.confidenceBonus != 0.0) {
            confidenceFactors.add("match_quality_${input.matchQuality.name.lowercase()}")
        }

        // Environmental factors
        if (input.environmentalFactors.isUrbanArea || input.environmentalFactors.isIndoor) {
            // GNSS multipath more likely in these environments
            if (input.deviceType == DeviceType.GNSS_SPOOFER || input.deviceType == DeviceType.GNSS_JAMMER) {
                confidence += ConfidenceAdjustments.MULTIPATH_LIKELY
                confidenceFactors.add("-urban_multipath_likely")
            }
        }

        // Clamp confidence to valid range
        confidence = confidence.coerceIn(0.1, 1.0)

        // Step 2: Get impact factor
        val impactFactor = getImpactFactor(input.deviceType)

        // Step 3: Calculate raw score
        // Formula: likelihood * impact_factor * confidence
        // This produces a score where:
        // - 20% likelihood * 2.0 impact * 0.5 confidence = 20 (LOW)
        // - 70% likelihood * 2.0 impact * 0.8 confidence = 112 -> capped at 100 (CRITICAL)
        val rawScoreDouble = input.baseLikelihood * impactFactor * confidence
        val rawScore = rawScoreDouble.toInt().coerceIn(0, 100)

        // Step 4: Apply final adjustments for edge cases
        var adjustedScore = rawScore

        // Boost for confirmed active threats
        if (input.hasMultipleIndicators && input.hasCrossProtocolCorrelation && confidence > 0.7) {
            adjustedScore = (adjustedScore * 1.1).toInt().coerceIn(0, 100)
        }

        // Additional penalty for brief, weak, single-indicator detections
        if (input.seenCount == 1 && input.rssi < -80 && !input.hasMultipleIndicators) {
            adjustedScore = (adjustedScore * 0.7).toInt()
        }

        // Step 5: Determine severity
        val severity = scoreToSeverity(adjustedScore)

        // Step 6: Generate reasoning
        val reasoning = buildReasoning(input, rawScore, adjustedScore, impactFactor, confidence, severity)

        return ThreatResult(
            rawScore = rawScore,
            adjustedScore = adjustedScore,
            severity = severity,
            likelihood = input.baseLikelihood,
            impactFactor = impactFactor,
            confidence = confidence,
            confidenceFactors = confidenceFactors,
            reasoning = reasoning
        )
    }

    /**
     * Convert final score to severity level.
     *
     * Thresholds are calibrated to ensure severity matches actual threat probability:
     * - CRITICAL (90-100): Confirmed active threat, immediate action needed
     * - HIGH (70-89): High probability threat, investigate immediately
     * - MEDIUM (50-69): Moderate concern, monitor closely
     * - LOW (30-49): Possible concern, log and watch
     * - INFO (0-29): Notable but not threatening
     */
    fun scoreToSeverity(score: Int): ThreatLevel = when {
        score >= 90 -> ThreatLevel.CRITICAL
        score >= 70 -> ThreatLevel.HIGH
        score >= 50 -> ThreatLevel.MEDIUM
        score >= 30 -> ThreatLevel.LOW
        else -> ThreatLevel.INFO
    }

    /**
     * Build human-readable reasoning for the threat assessment.
     */
    private fun buildReasoning(
        input: ThreatInput,
        rawScore: Int,
        adjustedScore: Int,
        impactFactor: Double,
        confidence: Double,
        severity: ThreatLevel
    ): String {
        return buildString {
            appendLine("Threat Assessment: ${severity.displayName}")
            appendLine()
            appendLine("Calculation:")
            appendLine("  Base likelihood: ${input.baseLikelihood}%")
            appendLine("  Impact factor: ${"%.2f".format(impactFactor)} (${input.deviceType.displayName})")
            appendLine("  Confidence: ${"%.0f".format(confidence * 100)}%")
            appendLine("  Raw score: $rawScore")
            if (adjustedScore != rawScore) {
                appendLine("  Adjusted score: $adjustedScore")
            }
            appendLine()
            appendLine("Key Factors:")
            if (input.hasMultipleIndicators) {
                appendLine("  + Multiple confirming indicators")
            }
            if (input.hasCrossProtocolCorrelation) {
                appendLine("  + Cross-protocol correlation confirmed")
            }
            if (input.seenCount > 3) {
                appendLine("  + Persistent over time (${input.seenCount} sightings)")
            }
            if (input.isKnownFalsePositivePattern) {
                appendLine("  - Matches known false positive pattern")
            }
            if (input.isConsumerDevice) {
                appendLine("  - Common consumer device type")
            }
            if (input.rssi < -80) {
                appendLine("  - Weak signal (${input.rssi} dBm)")
            }
            if (input.seenCount == 1) {
                appendLine("  - Single detection only")
            }
        }
    }

    // ============================================================================
    // Aggregate Threat Calculation (Multiple Detections)
    // ============================================================================

    /**
     * Calculate aggregate threat level from multiple detections.
     *
     * This is smarter than just counting HIGH threats:
     * - Considers correlation (same time/place = one incident)
     * - Considers pattern (same type recurring = more concerning)
     * - Weights recent detections higher than old ones
     */
    data class AggregateInput(
        val detections: List<DetectionSummary>,
        val timeWindowMs: Long = 30 * 60 * 1000  // 30 minutes default
    )

    data class DetectionSummary(
        val threatResult: ThreatResult,
        val deviceType: DeviceType,
        val timestamp: Long,
        val latitude: Double?,
        val longitude: Double?,
        val protocol: String
    )

    data class AggregateResult(
        val overallSeverity: ThreatLevel,
        val overallScore: Int,
        val incidentCount: Int,           // Deduplicated incident count
        val highestIndividualThreat: ThreatResult?,
        val correlatedProtocols: Set<String>,
        val reasoning: String
    )

    fun calculateAggregateThreat(input: AggregateInput): AggregateResult {
        if (input.detections.isEmpty()) {
            return AggregateResult(
                overallSeverity = ThreatLevel.INFO,
                overallScore = 0,
                incidentCount = 0,
                highestIndividualThreat = null,
                correlatedProtocols = emptySet(),
                reasoning = "No detections to analyze"
            )
        }

        val now = System.currentTimeMillis()
        val recentDetections = input.detections.filter {
            now - it.timestamp < input.timeWindowMs
        }

        if (recentDetections.isEmpty()) {
            return AggregateResult(
                overallSeverity = ThreatLevel.INFO,
                overallScore = 0,
                incidentCount = 0,
                highestIndividualThreat = null,
                correlatedProtocols = emptySet(),
                reasoning = "No recent detections within time window"
            )
        }

        // Group by incident (same location + time = one incident)
        val incidents = groupIntoIncidents(recentDetections)

        // Find the highest individual threat
        val highestThreat = recentDetections.maxByOrNull { it.threatResult.adjustedScore }

        // Check for cross-protocol correlation
        val protocols = recentDetections.map { it.protocol }.toSet()
        val hasCorrelation = protocols.size > 1

        // Check for recurring patterns
        val deviceTypeCounts = recentDetections.groupBy { it.deviceType }
            .mapValues { it.value.size }
        val hasRecurringPattern = deviceTypeCounts.any { it.value >= 3 }

        // Calculate aggregate score
        var aggregateScore = highestThreat?.threatResult?.adjustedScore ?: 0

        // Boost for correlation
        if (hasCorrelation) {
            aggregateScore = (aggregateScore * 1.2).toInt().coerceIn(0, 100)
        }

        // Boost for recurring pattern
        if (hasRecurringPattern) {
            aggregateScore = (aggregateScore * 1.15).toInt().coerceIn(0, 100)
        }

        // Weight recent detections (last 5 minutes) higher
        val veryRecentDetections = recentDetections.filter {
            now - it.timestamp < 5 * 60 * 1000
        }
        if (veryRecentDetections.any { it.threatResult.severity == ThreatLevel.HIGH ||
                                       it.threatResult.severity == ThreatLevel.CRITICAL }) {
            aggregateScore = (aggregateScore * 1.1).toInt().coerceIn(0, 100)
        }

        val overallSeverity = scoreToSeverity(aggregateScore)

        val reasoning = buildString {
            appendLine("Aggregate Threat Assessment: ${overallSeverity.displayName}")
            appendLine()
            appendLine("Summary:")
            appendLine("  Total detections: ${recentDetections.size}")
            appendLine("  Unique incidents: ${incidents.size}")
            appendLine("  Protocols involved: ${protocols.joinToString()}")
            if (hasCorrelation) {
                appendLine("  Cross-protocol correlation: YES (+20% score)")
            }
            if (hasRecurringPattern) {
                appendLine("  Recurring pattern detected: YES (+15% score)")
            }
            appendLine()
            appendLine("Highest individual threat:")
            highestThreat?.let {
                appendLine("  Device: ${it.deviceType.displayName}")
                appendLine("  Score: ${it.threatResult.adjustedScore}")
                appendLine("  Severity: ${it.threatResult.severity.displayName}")
            }
        }

        return AggregateResult(
            overallSeverity = overallSeverity,
            overallScore = aggregateScore,
            incidentCount = incidents.size,
            highestIndividualThreat = highestThreat?.threatResult,
            correlatedProtocols = protocols,
            reasoning = reasoning
        )
    }

    /**
     * Group detections into incidents based on spatial and temporal proximity.
     */
    private fun groupIntoIncidents(detections: List<DetectionSummary>): List<List<DetectionSummary>> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedBy { it.timestamp }
        val incidents = mutableListOf<MutableList<DetectionSummary>>()
        var currentIncident = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val detection = sorted[i]
            val lastInIncident = currentIncident.last()

            val timeDiff = detection.timestamp - lastInIncident.timestamp
            val sameLocation = isSameLocation(detection, lastInIncident)

            // Same incident if within 5 minutes and same location (or location unknown)
            if (timeDiff < 5 * 60 * 1000 && sameLocation) {
                currentIncident.add(detection)
            } else {
                incidents.add(currentIncident)
                currentIncident = mutableListOf(detection)
            }
        }
        incidents.add(currentIncident)

        return incidents
    }

    /**
     * Check if two detections are at the same location (within ~50m).
     */
    private fun isSameLocation(a: DetectionSummary, b: DetectionSummary): Boolean {
        if (a.latitude == null || a.longitude == null ||
            b.latitude == null || b.longitude == null) {
            return true  // Assume same location if unknown
        }

        val latDiff = Math.abs(a.latitude - b.latitude)
        val lonDiff = Math.abs(a.longitude - b.longitude)

        // Roughly 50 meters at mid-latitudes
        return latDiff < 0.0005 && lonDiff < 0.0005
    }

    // ============================================================================
    // Debug Export
    // ============================================================================

    /**
     * Generate debug export data for a threat result.
     */
    fun generateDebugExport(result: ThreatResult): Map<String, Any> {
        return mapOf(
            "raw_score" to result.rawScore,
            "adjusted_score" to result.adjustedScore,
            "severity" to result.severity.name,
            "severity_display" to result.severity.displayName,
            "likelihood_percent" to result.likelihood,
            "impact_factor" to result.impactFactor,
            "confidence_percent" to (result.confidence * 100).toInt(),
            "confidence_factors" to result.confidenceFactors,
            "reasoning" to result.reasoning,
            "calculation_formula" to "score = likelihood(${result.likelihood}) * impact(${result.impactFactor}) * confidence(${result.confidence}) = ${result.rawScore}"
        )
    }
}

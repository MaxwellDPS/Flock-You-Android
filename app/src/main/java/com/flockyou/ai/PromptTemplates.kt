package com.flockyou.ai

import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomalyAnalysis
import com.flockyou.service.CellularMonitor.CellularAnomalyAnalysis
import com.flockyou.service.RogueWifiMonitor.FollowingNetworkAnalysis
import com.flockyou.service.UltrasonicDetector.BeaconAnalysis

/**
 * Centralized prompt templates for LLM-based analysis.
 *
 * Provides multiple prompt strategies:
 * - Chain-of-thought: For complex multi-step reasoning
 * - Few-shot: With examples for consistent output format
 * - Structured output: JSON-formatted responses for parsing
 * - Enriched prompts: Leveraging detector-specific analysis data
 */
object PromptTemplates {

    // ==================== PROMPT FORMATS ====================

    /**
     * Gemma instruction format wrapper
     */
    private fun wrapGemmaPrompt(userContent: String): String {
        return """<start_of_turn>user
$userContent
<end_of_turn>
<start_of_turn>model
"""
    }

    // ==================== CHAIN OF THOUGHT PROMPTS ====================

    /**
     * Build a chain-of-thought prompt for complex analysis.
     * Uses step-by-step reasoning to analyze surveillance detections.
     */
    fun buildChainOfThoughtPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData? = null
    ): String {
        val enrichedSection = enrichedData?.let { buildEnrichedDataSection(it) } ?: ""

        val content = """You are a privacy security expert analyzing a detected surveillance device.

Think through this step-by-step:

STEP 1 - Identify the Device:
What type of surveillance device is this? What is its primary purpose?

STEP 2 - Assess Capabilities:
What data can this device collect? How does it operate?

STEP 3 - Evaluate Risk:
Given the device type and signal strength, what is the actual privacy risk?

STEP 4 - Consider Context:
Based on the detection method and any enriched data, is this detection reliable?

STEP 5 - Recommend Actions:
What specific steps should the user take?

=== DETECTION DATA ===
Device Type: ${detection.deviceType.displayName}
Protocol: ${detection.protocol.displayName}
Detection Method: ${detection.detectionMethod.displayName}
Signal: ${detection.signalStrength.displayName} (${detection.rssi} dBm)
Threat Level: ${detection.threatLevel.displayName}
Threat Score: ${detection.threatScore}/100
${detection.manufacturer?.let { "Manufacturer: $it" } ?: ""}
${detection.deviceName?.let { "Device Name: $it" } ?: ""}
${detection.ssid?.let { "Network SSID: $it" } ?: ""}
${detection.matchedPatterns?.let { "Matched Patterns: $it" } ?: ""}
$enrichedSection

Provide your analysis following these steps."""

        return wrapGemmaPrompt(content)
    }

    // ==================== FEW-SHOT PROMPTS ====================

    /**
     * Build a few-shot prompt with examples for consistent output format.
     */
    fun buildFewShotPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData? = null
    ): String {
        val enrichedSection = enrichedData?.let { buildEnrichedDataSection(it) } ?: ""

        val content = """You are a surveillance detection expert. Analyze detected devices and provide clear, actionable guidance.

=== EXAMPLE 1 ===
Device: Flock Safety ALPR Camera
Signal: Good (-55 dBm)
Threat: HIGH

Analysis:
## Flock Safety ALPR Camera

**What It Does:** This is a license plate recognition camera that photographs every vehicle passing by. It records your plate number, vehicle make/model, color, and timestamp.

**Data Collected:**
- License plate numbers
- Vehicle descriptions
- Time and location of each pass
- Direction of travel

**Privacy Risk:** HIGH - Your movements are being logged in a database that may be shared with law enforcement and retained for years.

**Actions:**
1. Be aware this intersection is monitored
2. Consider alternate routes if privacy is a concern
3. Check if your city has an ALPR policy you can review

=== EXAMPLE 2 ===
Device: IMSI Catcher (Cell Site Simulator)
Signal: Strong (-45 dBm)
Threat: CRITICAL
Enriched: Encryption downgrade 5G→2G, IMSI Score 78%

Analysis:
## IMSI Catcher Detection

**What It Does:** This is a cell site simulator (StingRay) that forces your phone to connect to it instead of a real tower. It can intercept calls, texts, and track your location.

**Data Collected:**
- Phone IMSI/IMEI identifiers
- Call and text metadata
- Real-time location
- Potentially call/text content (on 2G)

**Privacy Risk:** CRITICAL - Your phone has been forced to 2G with weak encryption. Communications may be intercepted.

**Actions:**
1. IMMEDIATELY enable airplane mode
2. Leave this area if possible
3. Use end-to-end encrypted apps only (Signal, WhatsApp)
4. Report to EFF or ACLU if you suspect targeting

=== YOUR ANALYSIS ===
Device: ${detection.deviceType.displayName}
Signal: ${detection.signalStrength.displayName} (${detection.rssi} dBm)
Threat: ${detection.threatLevel.displayName}
${enrichedSection}

Provide your analysis in the same format as the examples above."""

        return wrapGemmaPrompt(content)
    }

    // ==================== STRUCTURED OUTPUT PROMPTS ====================

    /**
     * Build a prompt requesting JSON-structured output for parsing.
     */
    fun buildStructuredOutputPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData? = null
    ): String {
        val enrichedSection = enrichedData?.let { buildEnrichedDataSection(it) } ?: ""

        val content = """Analyze this surveillance detection and respond in JSON format only.

Detection:
- Type: ${detection.deviceType.displayName}
- Protocol: ${detection.protocol.displayName}
- Method: ${detection.detectionMethod.displayName}
- Signal: ${detection.rssi} dBm (${detection.signalStrength.displayName})
- Threat Level: ${detection.threatLevel.displayName}
- Score: ${detection.threatScore}/100
${detection.manufacturer?.let { "- Manufacturer: $it" } ?: ""}
${detection.deviceName?.let { "- Name: $it" } ?: ""}
$enrichedSection

Respond with this exact JSON structure:
{
  "headline": "5-word summary of threat",
  "device_purpose": "What this device does in 1-2 sentences",
  "data_types": ["type1", "type2", "type3"],
  "risk_level": "LOW|MEDIUM|HIGH|CRITICAL",
  "risk_explanation": "Why this risk level in 1 sentence",
  "actions": [
    {"priority": 1, "action": "Most important action"},
    {"priority": 2, "action": "Second action"},
    {"priority": 3, "action": "Third action"}
  ],
  "confidence": 0.0-1.0
}

JSON response:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== ENRICHED DETECTOR-SPECIFIC PROMPTS ====================

    /**
     * Build an enriched prompt for cellular/IMSI catcher detections.
     */
    fun buildCellularEnrichedPrompt(
        detection: Detection,
        analysis: CellularAnomalyAnalysis
    ): String {
        val content = """Analyze this potential IMSI catcher/cell site simulator detection.

=== IMSI CATCHER ANALYSIS ===
IMSI Catcher Likelihood: ${analysis.imsiCatcherScore}%
Encryption Chain: ${analysis.encryptionDowngradeChain.joinToString(" → ")}
Current Encryption: ${analysis.currentEncryption.displayName}
Encryption Downgraded: ${if (analysis.encryptionDowngraded) "YES - from ${analysis.previousEncryption?.displayName}" else "No"}
${analysis.vulnerabilityNote?.let { "⚠️ Vulnerability: $it" } ?: ""}

=== MOVEMENT CONTEXT ===
Movement Type: ${analysis.movementType.displayName}
Speed: ${String.format("%.1f", analysis.speedKmh)} km/h
Distance: ${String.format("%.0f", analysis.distanceMeters)} meters
Time Window: ${analysis.timeBetweenSamplesMs / 1000} seconds
Impossible Movement: ${if (analysis.impossibleSpeed) "YES - SUSPICIOUS" else "No"}

=== CELL TOWER TRUST ===
Cell Trust Score: ${analysis.cellTrustScore}%
Times Seen: ${analysis.cellSeenCount}
Cell Age: ${analysis.cellAgeSeconds / 60} minutes in database
Familiar Area: ${if (analysis.isInFamiliarArea) "Yes" else "No"}
Trusted Cells Nearby: ${analysis.nearbyTrustedCells}

=== SIGNAL ANALYSIS ===
Current Signal: ${analysis.currentSignalDbm} dBm (${analysis.signalQuality})
Signal Delta: ${if (analysis.signalDeltaDbm > 0) "+" else ""}${analysis.signalDeltaDbm} dBm
Signal Spike: ${if (analysis.signalSpikeDetected) "YES" else "No"}
Downgrade + Spike: ${if (analysis.downgradeWithSignalSpike) "YES - Classic IMSI signature" else "No"}
Downgrade + New Tower: ${if (analysis.downgradeWithNewTower) "YES - Suspicious" else "No"}

=== NETWORK CONTEXT ===
Network Change: ${analysis.networkGenerationChange ?: "None"}
LAC/TAC Changed: ${if (analysis.lacTacChanged) "YES - Unusual" else "No"}
Operator Changed: ${if (analysis.operatorChanged) "YES" else "No"}
Roaming: ${if (analysis.isRoaming) "Yes" else "No"}

Based on this enriched data, provide:
1. A plain-English explanation for a non-technical user about what's happening to their phone
2. Whether this indicates active IMSI catcher surveillance (yes/no with confidence percentage)
3. The top 3 SPECIFIC actions they should take RIGHT NOW
4. What data may have been captured

Format as:
## Assessment
[Your assessment]

## Actions
1. [Action 1]
2. [Action 2]
3. [Action 3]

## Data at Risk
[What may have been captured]"""

        return wrapGemmaPrompt(content)
    }

    /**
     * Build an enriched prompt for GNSS spoofing/jamming detections.
     */
    fun buildGnssEnrichedPrompt(
        detection: Detection,
        analysis: GnssAnomalyAnalysis
    ): String {
        val content = """Analyze this GNSS (GPS/satellite) anomaly detection.

=== SPOOFING/JAMMING LIKELIHOOD ===
Spoofing Likelihood: ${String.format("%.0f", analysis.spoofingLikelihood)}%
Jamming Likelihood: ${String.format("%.0f", analysis.jammingLikelihood)}%

=== CONSTELLATION ANALYSIS ===
Expected Constellations: ${analysis.expectedConstellations.joinToString { it.code }}
Observed Constellations: ${analysis.observedConstellations.joinToString { it.code }}
Missing Constellations: ${analysis.missingConstellations.joinToString { it.code }.ifEmpty { "None" }}
Constellation Match: ${analysis.constellationMatchScore}%
Unexpected Constellation: ${if (analysis.unexpectedConstellation) "YES" else "No"}

=== SIGNAL ANALYSIS (C/N0) ===
Historical Baseline: ${String.format("%.1f", analysis.historicalCn0Mean)} ± ${String.format("%.1f", analysis.historicalCn0StdDev)} dB-Hz
Current C/N0: ${String.format("%.1f", analysis.currentCn0Mean)} dB-Hz
Deviation: ${String.format("%.1f", analysis.cn0DeviationSigmas)}σ from baseline
Signal Uniformity: ${if (analysis.cn0TooUniform) "TOO UNIFORM - Spoofing indicator" else "Normal variance"}
Variance: ${String.format("%.2f", analysis.cn0Variance)}

=== SATELLITE GEOMETRY ===
Geometry Score: ${String.format("%.0f", analysis.geometryScore * 100)}%
Elevation Distribution: ${analysis.elevationDistribution}
Azimuth Coverage: ${String.format("%.0f", analysis.azimuthCoverage)}%
Low-Elev High-Signal: ${analysis.lowElevHighSignalCount} satellites (spoofing indicator if > 0)

=== CLOCK ANALYSIS ===
Cumulative Drift: ${analysis.cumulativeDriftNs / 1_000_000} ms
Drift Trend: ${analysis.driftTrend.displayName}
Drift Anomalous: ${if (analysis.driftAnomalous) "YES" else "No"}
Drift Jumps: ${analysis.driftJumpCount}

=== INDICATORS ===
Spoofing Indicators:
${analysis.spoofingIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None detected" }}

Jamming Indicators:
${analysis.jammingIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None detected" }}

Based on this enriched data, provide:
1. A clear explanation of whether the user's GPS/location is being manipulated
2. What this means for their safety and privacy
3. The top 3 actions they should take
4. Whether they should trust their current location on maps

Format as:
## Assessment
[Your assessment - is GPS being spoofed or jammed?]

## Impact
[What this means for the user]

## Actions
1. [Action 1]
2. [Action 2]
3. [Action 3]

## Location Trustworthiness
[Can they trust their GPS right now?]"""

        return wrapGemmaPrompt(content)
    }

    /**
     * Build an enriched prompt for ultrasonic beacon detections.
     */
    fun buildUltrasonicEnrichedPrompt(
        detection: Detection,
        analysis: BeaconAnalysis
    ): String {
        val content = """Analyze this ultrasonic tracking beacon detection.

=== BEACON FINGERPRINT ===
Frequency: ${analysis.frequencyHz} Hz
Beacon Type: ${analysis.matchedSource.displayName}
Category: ${analysis.sourceCategory.displayName}
Source Confidence: ${String.format("%.0f", analysis.sourceConfidence)}%

=== AMPLITUDE ANALYSIS ===
Peak Amplitude: ${String.format("%.1f", analysis.peakAmplitudeDb)} dB
Average Amplitude: ${String.format("%.1f", analysis.avgAmplitudeDb)} dB
Amplitude Variance: ${String.format("%.2f", analysis.amplitudeVariance)}
Amplitude Profile: ${analysis.amplitudeProfile.displayName}
SNR: ${String.format("%.1f", analysis.snrDb)} dB

=== CROSS-LOCATION TRACKING ===
Following User: ${if (analysis.followingUser) "YES - Detected at multiple of your locations" else "No"}
Locations Detected: ${analysis.locationsDetected}
Total Detections: ${analysis.totalDetectionCount}
Detection Duration: ${analysis.detectionDurationMs / 60000} minutes
Persistence Score: ${String.format("%.0f", analysis.persistenceScore * 100)}%

=== ENVIRONMENT CONTEXT ===
Noise Floor: ${String.format("%.1f", analysis.noiseFloorDb)} dB

=== RISK ASSESSMENT ===
Tracking Likelihood: ${String.format("%.0f", analysis.trackingLikelihood)}%
Risk Indicators:
${analysis.riskIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None" }}

Based on this enriched data, explain:
1. What this ultrasonic beacon is doing (in plain English)
2. How it tracks users across devices (if applicable)
3. What company/technology is likely behind it
4. How to stop or avoid this tracking

Format as:
## What's Happening
[Explanation of the beacon]

## How It Tracks You
[Tracking mechanism]

## Likely Source
[Who is behind this]

## Protection Steps
1. [Step 1]
2. [Step 2]
3. [Step 3]"""

        return wrapGemmaPrompt(content)
    }

    /**
     * Build an enriched prompt for following WiFi network detections.
     */
    fun buildWifiFollowingEnrichedPrompt(
        detection: Detection,
        analysis: FollowingNetworkAnalysis
    ): String {
        val content = """Analyze this "following network" detection - a WiFi network appearing at multiple locations.

=== FOLLOWING PATTERN ===
Network SSID: ${detection.ssid ?: "Unknown"}
MAC Address: ${detection.macAddress ?: "Unknown"}
Times Spotted: ${analysis.sightingCount}
Distinct Locations: ${analysis.distinctLocations}
Following Confidence: ${String.format("%.0f", analysis.followingConfidence)}%
Tracking Duration: ${analysis.trackingDurationMs / 60000} minutes

=== TEMPORAL PATTERNS ===
Time Pattern: ${analysis.timePattern.displayName}
Avg Time Between Sightings: ${analysis.avgTimeBetweenSightingsMs / 60000} minutes
Following Duration: ${analysis.followingDurationMs / 60000} minutes

=== MOVEMENT CORRELATION ===
Path Correlation: ${String.format("%.0f", analysis.pathCorrelation * 100)}%
Leads User: ${if (analysis.leadsUser) "YES - Appears BEFORE you arrive (very suspicious)" else "No"}
${analysis.lagTimeMs?.let { "Lag Time: ${it / 1000} seconds behind you" } ?: ""}

=== SIGNAL ANALYSIS ===
Signal Consistency: ${String.format("%.0f", analysis.signalConsistency * 100)}%
Signal Trend: ${analysis.signalTrend.displayName}
Average Signal: ${analysis.avgSignalStrength} dBm
Signal Variance: ${String.format("%.1f", analysis.signalVariance)}

=== DEVICE CLASSIFICATION ===
Likely Mobile Device: ${if (analysis.likelyMobile) "YES" else "No - Fixed location"}
Vehicle Mounted: ${if (analysis.vehicleMounted) "YES - Large movements suggest vehicle" else "No"}
Foot Surveillance: ${if (analysis.possibleFootSurveillance) "POSSIBLE - Slower, closer movements" else "No"}

=== RISK INDICATORS ===
${analysis.riskIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None" }}

Based on this enriched data, provide:
1. Whether this network is genuinely following the user or a coincidence
2. The likely type of device/vehicle carrying this network
3. Whether this indicates physical surveillance or stalking
4. Immediate safety recommendations

Format as:
## Assessment
[Is this network following the user?]

## Device Analysis
[What type of device is this likely?]

## Safety Concern
[Physical safety assessment]

## Immediate Actions
1. [Action 1]
2. [Action 2]
3. [Action 3]"""

        return wrapGemmaPrompt(content)
    }

    // ==================== USER-FRIENDLY EXPLANATION PROMPTS ====================

    /**
     * User explanation levels
     */
    enum class ExplanationLevel(val description: String) {
        SIMPLE("For users with no technical knowledge"),
        STANDARD("For general audience"),
        TECHNICAL("For tech-savvy users")
    }

    /**
     * Build a prompt for user-friendly explanations at different technical levels.
     */
    fun buildUserFriendlyPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData? = null,
        level: ExplanationLevel = ExplanationLevel.STANDARD
    ): String {
        val enrichedSection = enrichedData?.let { buildEnrichedDataSection(it) } ?: ""

        val levelInstructions = when (level) {
            ExplanationLevel.SIMPLE -> """
Write for someone with NO technical knowledge.
- Use simple, everyday words only
- Short sentences (max 15 words each)
- Use relatable analogies (like mail being read, being followed, etc.)
- NO technical terms at all
- Explain like you're talking to your grandmother"""

            ExplanationLevel.STANDARD -> """
Write for a general adult audience.
- Clear, accessible language
- Brief technical terms OK if explained
- Balance detail with clarity
- Focus on practical implications"""

            ExplanationLevel.TECHNICAL -> """
Write for a tech-savvy user.
- Include technical details
- Reference specific protocols/standards
- Explain attack vectors and mechanisms
- Provide detailed countermeasures"""
        }

        val content = """You are explaining a surveillance detection to a user.

$levelInstructions

=== DETECTION ===
Device: ${detection.deviceType.displayName}
Type: ${detection.protocol.displayName}
Threat: ${detection.threatLevel.displayName} (${detection.threatScore}/100)
Signal: ${detection.signalStrength.displayName}
${enrichedSection}

Provide:
1. HEADLINE: Maximum 5 words summarizing the situation
2. WHAT'S HAPPENING: ${if (level == ExplanationLevel.SIMPLE) "2 short sentences" else "2-3 sentences"}
3. WHY IT MATTERS: ${if (level == ExplanationLevel.SIMPLE) "1 sentence using an analogy" else "Privacy/safety implications"}
4. WHAT TO DO: 3 numbered actions, ${if (level == ExplanationLevel.SIMPLE) "very simple steps" else "specific steps"}
5. URGENCY: LOW, MEDIUM, HIGH, or IMMEDIATE

Format exactly as:
HEADLINE: [5 words max]

WHAT'S HAPPENING:
[explanation]

WHY IT MATTERS:
[implications]

WHAT TO DO:
1. [action]
2. [action]
3. [action]

URGENCY: [level]"""

        return wrapGemmaPrompt(content)
    }

    // ==================== HELPER FUNCTIONS ====================

    /**
     * Build the enriched data section based on what type of data is available.
     */
    private fun buildEnrichedDataSection(data: EnrichedDetectorData): String {
        return when (data) {
            is EnrichedDetectorData.Cellular -> {
                val a = data.analysis
                """
=== ENRICHED CELLULAR DATA ===
IMSI Catcher Score: ${a.imsiCatcherScore}%
Encryption: ${a.currentEncryption.displayName}
Movement: ${a.movementType.displayName} (${String.format("%.0f", a.speedKmh)} km/h)
Cell Trust: ${a.cellTrustScore}%
${if (a.impossibleSpeed) "⚠️ IMPOSSIBLE MOVEMENT DETECTED" else ""}
${if (a.encryptionDowngraded) "⚠️ ENCRYPTION DOWNGRADED" else ""}"""
            }
            is EnrichedDetectorData.Gnss -> {
                val a = data.analysis
                """
=== ENRICHED GNSS DATA ===
Spoofing Likelihood: ${String.format("%.0f", a.spoofingLikelihood)}%
Jamming Likelihood: ${String.format("%.0f", a.jammingLikelihood)}%
Geometry Score: ${String.format("%.0f", a.geometryScore * 100)}%
C/N0: ${String.format("%.1f", a.currentCn0Mean)} dB-Hz
${if (a.cn0TooUniform) "⚠️ SIGNAL UNIFORMITY SUSPICIOUS" else ""}
${if (a.lowElevHighSignalCount > 0) "⚠️ ${a.lowElevHighSignalCount} LOW-ELEV HIGH-SIGNAL SATELLITES" else ""}"""
            }
            is EnrichedDetectorData.Ultrasonic -> {
                val a = data.analysis
                """
=== ENRICHED ULTRASONIC DATA ===
Beacon Type: ${a.matchedSource.displayName}
Category: ${a.sourceCategory.displayName}
Frequency: ${a.frequencyHz} Hz
Cross-Location: ${if (a.followingUser) "YES - ${a.locationsDetected} locations" else "No"}
Tracking Likelihood: ${String.format("%.0f", a.trackingLikelihood)}%
Persistence: ${String.format("%.0f", a.persistenceScore * 100)}%"""
            }
            is EnrichedDetectorData.WifiFollowing -> {
                val a = data.analysis
                """
=== ENRICHED WIFI FOLLOWING DATA ===
Following Confidence: ${String.format("%.0f", a.followingConfidence)}%
Sightings: ${a.sightingCount} times at ${a.distinctLocations} locations
Path Correlation: ${String.format("%.0f", a.pathCorrelation * 100)}%
${if (a.vehicleMounted) "⚠️ VEHICLE MOUNTED DEVICE" else ""}
${if (a.possibleFootSurveillance) "⚠️ POSSIBLE FOOT SURVEILLANCE" else ""}
${if (a.leadsUser) "⚠️ NETWORK LEADS USER (arrives before you)" else ""}"""
            }
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> "${diff / 86400_000} days ago"
        }
    }

    // ==================== PATTERN RECOGNITION PROMPTS ====================

    /**
     * Build a prompt to analyze patterns across multiple detections.
     */
    fun buildPatternRecognitionPrompt(
        detections: List<Detection>,
        timeWindowDescription: String
    ): String {
        val detectionList = detections.take(10).mapIndexed { index, d ->
            """${index + 1}. ${d.deviceType.displayName}
   - Time: ${formatTimestamp(d.timestamp)}
   - Protocol: ${d.protocol.displayName}
   - Threat: ${d.threatLevel.displayName} (${d.threatScore})
   - Location: ${d.latitude?.let { "${String.format("%.4f", it)}, ${String.format("%.4f", d.longitude)}" } ?: "Unknown"}
   - Signal: ${d.rssi} dBm"""
        }.joinToString("\n\n")

        val content = """Analyze these surveillance detections for coordinated patterns.

Time Window: $timeWindowDescription
Total Detections: ${detections.size}

=== DETECTIONS ===
$detectionList

Look for these patterns:
1. COORDINATED SURVEILLANCE: Multiple devices working together
2. FOLLOWING PATTERN: Devices appearing wherever the user goes
3. TIMING CORRELATION: Devices activating at the same times
4. GEOGRAPHIC CLUSTERING: Multiple devices in a small area
5. ESCALATION: Threat levels increasing over time
6. MULTIMODAL: Different detection types targeting same area

For each pattern found, provide:
- Pattern type
- Which detections are involved (by number)
- Confidence level (LOW/MEDIUM/HIGH)
- What this pattern suggests
- Recommended response

Format as:
## Pattern Analysis

### [Pattern Name]
- Detections: [numbers]
- Confidence: [level]
- Interpretation: [what it means]
- Action: [what to do]

[Repeat for each pattern found]

If no significant patterns, state "No coordinated patterns detected" and explain why."""

        return wrapGemmaPrompt(content)
    }

    // ==================== SUMMARY PROMPTS ====================

    /**
     * Build a prompt for daily/weekly surveillance summaries.
     */
    fun buildSummaryPrompt(
        detections: List<Detection>,
        periodDescription: String,
        previousPeriodComparison: String? = null
    ): String {
        val byType = detections.groupBy { it.deviceType }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(5)

        val byThreat = detections.groupBy { it.threatLevel }
            .mapValues { it.value.size }

        val content = """Generate a surveillance exposure summary for the user.

Period: $periodDescription
Total Detections: ${detections.size}
${previousPeriodComparison?.let { "Comparison: $it" } ?: ""}

=== BY DEVICE TYPE ===
${byType.joinToString("\n") { "${it.first.displayName}: ${it.second}" }}

=== BY THREAT LEVEL ===
Critical: ${byThreat[ThreatLevel.CRITICAL] ?: 0}
High: ${byThreat[ThreatLevel.HIGH] ?: 0}
Medium: ${byThreat[ThreatLevel.MEDIUM] ?: 0}
Low: ${byThreat[ThreatLevel.LOW] ?: 0}
Info: ${byThreat[ThreatLevel.INFO] ?: 0}

=== HIGH-PRIORITY EVENTS ===
${detections.filter { it.threatLevel == ThreatLevel.CRITICAL || it.threatLevel == ThreatLevel.HIGH }
    .take(5)
    .mapIndexed { i, d -> "${i + 1}. ${d.deviceType.displayName} - ${d.threatLevel.displayName}" }
    .joinToString("\n")}

Generate a summary with:
1. HEADLINE: One sentence overview
2. KEY FINDINGS: 3-4 bullet points of most important observations
3. TREND: Is surveillance exposure increasing, decreasing, or stable?
4. HOTSPOTS: Any locations with concentrated surveillance
5. RECOMMENDATIONS: 2-3 actionable suggestions

Format as:
## $periodDescription Summary

**Headline:** [summary]

**Key Findings:**
- [finding 1]
- [finding 2]
- [finding 3]

**Trend:** [assessment]

**Hotspots:** [locations if any]

**Recommendations:**
1. [recommendation]
2. [recommendation]"""

        return wrapGemmaPrompt(content)
    }
}

/**
 * Sealed class representing enriched detector data for different detection types.
 */
sealed class EnrichedDetectorData {
    data class Cellular(val analysis: CellularAnomalyAnalysis) : EnrichedDetectorData()
    data class Gnss(val analysis: GnssAnomalyAnalysis) : EnrichedDetectorData()
    data class Ultrasonic(val analysis: BeaconAnalysis) : EnrichedDetectorData()
    data class WifiFollowing(val analysis: FollowingNetworkAnalysis) : EnrichedDetectorData()
    data class Satellite(
        val detectorType: String,
        val metadata: Map<String, String>,
        val signalCharacteristics: Map<String, String>,
        val riskIndicators: List<String>,
        val timestamp: Long
    ) : EnrichedDetectorData()
}

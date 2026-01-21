package com.flockyou.ai

import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
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

    // ==================== INPUT SANITIZATION ====================

    /**
     * Maximum length for user-provided strings to prevent prompt stuffing.
     */
    private const val MAX_INPUT_LENGTH = 256

    /**
     * Sanitize user-provided input to prevent prompt injection attacks.
     *
     * This function:
     * 1. Truncates excessively long strings
     * 2. Removes or escapes control characters
     * 3. Strips potential prompt injection markers
     * 4. Normalizes whitespace
     *
     * @param input The raw input from external sources (device names, SSIDs, etc.)
     * @param maxLength Maximum allowed length (default 256)
     * @return Sanitized string safe for prompt interpolation
     */
    private fun sanitize(input: String?, maxLength: Int = MAX_INPUT_LENGTH): String {
        if (input.isNullOrBlank()) return ""

        return input
            // Truncate to max length
            .take(maxLength)
            // Remove control characters except space, tab, newline
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            // Strip potential prompt injection markers
            .replace(Regex("</?(?:start_of_turn|end_of_turn|system|user|model|assistant|human)>", RegexOption.IGNORE_CASE), "[FILTERED]")
            .replace(Regex("\\[/?(?:INST|SYS|SYSTEM|USER)\\]", RegexOption.IGNORE_CASE), "[FILTERED]")
            // Normalize excessive whitespace
            .replace(Regex("\\s{3,}"), "  ")
            .trim()
    }

    /**
     * Sanitize a list of strings.
     */
    private fun sanitizeList(items: List<String>, maxLength: Int = MAX_INPUT_LENGTH): List<String> {
        return items.map { sanitize(it, maxLength) }.filter { it.isNotEmpty() }
    }

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
${sanitize(detection.manufacturer)?.takeIf { it.isNotEmpty() }?.let { "Manufacturer: $it" } ?: ""}
${sanitize(detection.deviceName)?.takeIf { it.isNotEmpty() }?.let { "Device Name: $it" } ?: ""}
${sanitize(detection.ssid)?.takeIf { it.isNotEmpty() }?.let { "Network SSID: $it" } ?: ""}
${sanitize(detection.matchedPatterns)?.takeIf { it.isNotEmpty() }?.let { "Matched Patterns: $it" } ?: ""}
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
     * Build a prompt requesting enterprise-grade JSON-structured output for parsing.
     * This prompt requests comprehensive, actionable intelligence including:
     * - False positive assessment
     * - Severity-aligned messaging
     * - User-friendly and technical explanations
     * - Contextual analysis
     */
    fun buildStructuredOutputPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData? = null
    ): String {
        val enrichedSection = enrichedData?.let { buildEnrichedDataSection(it) } ?: ""

        // Calculate estimated false positive likelihood for context
        val estimatedFpLikelihood = estimateFalsePositiveLikelihood(detection, enrichedData)

        val content = """Analyze this surveillance detection and respond in JSON format only.
IMPORTANT: Your analysis must be calibrated to the actual threat level and false positive likelihood.
- If false_positive_likelihood > 50%, treat this as LIKELY BENIGN and use reassuring language
- Match your headline and urgency to the actual threat - don't be alarmist for low-threat detections
- For LOW/INFO threat levels, use phrases like "minor", "normal", "routine", NOT "suspicious" or "detected"

Detection:
- Type: ${detection.deviceType.displayName}
- Protocol: ${detection.protocol.displayName}
- Method: ${detection.detectionMethod.displayName}
- Signal: ${detection.rssi} dBm (${detection.signalStrength.displayName})
- Threat Level: ${detection.threatLevel.displayName}
- Threat Score: ${detection.threatScore}/100
- Times Seen: ${detection.seenCount}
${sanitize(detection.manufacturer)?.takeIf { it.isNotEmpty() }?.let { "- Manufacturer: $it" } ?: ""}
${sanitize(detection.deviceName)?.takeIf { it.isNotEmpty() }?.let { "- Name: $it" } ?: ""}
$enrichedSection

Estimated False Positive Likelihood: ${estimatedFpLikelihood}%
${if (estimatedFpLikelihood > 50) "NOTE: This detection is MORE LIKELY to be a false alarm than a real threat." else ""}

Respond with this exact JSON structure:
{
  "headline": "5-10 word summary matching severity (reassuring if likely FP, urgent only if CRITICAL)",
  "threat_level_assessment": "${detection.threatLevel.displayName}",

  "what_detected": {
    "device_summary": "What this device is in 1 sentence",
    "device_purpose": "What it does in 1-2 sentences",
    "data_collection": "What data it can collect"
  },

  "why_flagged": {
    "trigger_indicators": ["specific indicator 1", "specific indicator 2"],
    "threat_reasoning": "Why this threat level was assigned"
  },

  "confidence_assessment": {
    "confidence_score": 0-100,
    "confidence_reasoning": "Why this confidence level",
    "false_positive_likelihood": 0-100,
    "false_positive_reasons": ["reason 1", "reason 2"],
    "is_most_likely_benign": true/false,
    "benign_explanation": "If likely benign, explain why (null if not benign)"
  },

  "actionable_intelligence": {
    "immediate_action": {
      "action": "What to do NOW (or null if no action needed)",
      "urgency": "IMMEDIATE|SOON|WHEN_CONVENIENT|FOR_AWARENESS|NONE",
      "reason": "Why this action"
    },
    "monitoring_recommendation": "How to monitor going forward",
    "documentation_suggestion": "Whether to document this (null if not needed)"
  },

  "explanations": {
    "simple_explanation": "Non-technical explanation for regular users using everyday analogies",
    "technical_details": "Technical details for advanced users"
  }
}

CRITICAL RULES:
1. If false_positive_likelihood > 50, set is_most_likely_benign to true and provide benign_explanation
2. If threat_level is LOW or INFO, use calm language - NOT "suspicious" or "detected threat"
3. Match immediate_action urgency to actual threat - only use IMMEDIATE for CRITICAL threats
4. Be specific in trigger_indicators - don't say "suspicious patterns" without details

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
        val fpSection = if (analysis.falsePositiveLikelihood > 30f) {
            """

=== FALSE POSITIVE ANALYSIS ===
FP Likelihood: ${String.format("%.0f", analysis.falsePositiveLikelihood)}%
Likely Normal Handoff: ${if (analysis.isLikelyNormalHandoff) "YES - Routine cell tower switch" else "No"}
Likely Carrier Behavior: ${if (analysis.isLikelyCarrierBehavior) "YES - This carrier has aggressive handoff patterns" else "No"}
Likely Edge Coverage: ${if (analysis.isLikelyEdgeCoverage) "YES - User at cell coverage boundary" else "No"}
Likely 5G Beam Steering: ${if (analysis.isLikely5gBeamSteering) "YES - Normal 5G beam management" else "No"}
FP Indicators:
${analysis.fpIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None" }}

IMPORTANT: This detection has a ${String.format("%.0f", analysis.falsePositiveLikelihood)}% chance of being a FALSE POSITIVE.
Consider these FP indicators when analyzing. If FP likelihood > 50%, lean toward normal cellular behavior."""
        } else ""

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
$fpSection

Based on this enriched data, provide:
1. A plain-English explanation for a non-technical user about what's happening to their phone - OR explain why this is likely a false positive
2. Whether this indicates active IMSI catcher surveillance (yes/no with confidence percentage)
3. The top 3 SPECIFIC actions they should take RIGHT NOW (or "No action needed" if FP)
4. What data may have been captured (or "No data at risk" if FP)

CRITICAL: If FP Likelihood > 50%, you MUST conclude this is likely NOT an IMSI catcher.
Common false positives include: normal handoffs while driving, carrier network optimization, 5G beam steering, entering/exiting buildings, areas with poor coverage.

Format as:
## Assessment
[Your assessment - OR why this is likely a false positive]

## Actions
1. [Action 1 - OR "No action needed"]
2. [Action 2]
3. [Action 3]

## Data at Risk
[What may have been captured - OR "No data at risk - likely normal network behavior"]"""

        return wrapGemmaPrompt(content)
    }

    /**
     * Build an enriched prompt for GNSS spoofing/jamming detections.
     */
    fun buildGnssEnrichedPrompt(
        detection: Detection,
        analysis: GnssAnomalyAnalysis
    ): String {
        val fpSection = if (analysis.falsePositiveLikelihood > 30f) {
            """

=== FALSE POSITIVE ANALYSIS ===
FP Likelihood: ${String.format("%.0f", analysis.falsePositiveLikelihood)}%
Likely Normal Operation: ${if (analysis.isLikelyNormalOperation) "YES" else "No"}
Likely Urban Multipath: ${if (analysis.isLikelyUrbanMultipath) "YES - Building reflections causing signal variance" else "No"}
Likely Indoor Signal Loss: ${if (analysis.isLikelyIndoorSignalLoss) "YES - Weak indoor reception" else "No"}
FP Indicators:
${analysis.fpIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None" }}

IMPORTANT: This detection has a ${String.format("%.0f", analysis.falsePositiveLikelihood)}% chance of being a FALSE POSITIVE.
Consider these FP indicators when analyzing. If FP likelihood > 50%, lean toward dismissing as normal GPS behavior."""
        } else ""

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
$fpSection

=== INDICATORS ===
Spoofing Indicators:
${analysis.spoofingIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None detected" }}

Jamming Indicators:
${analysis.jammingIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None detected" }}

Based on this enriched data, provide:
1. A clear explanation of whether the user's GPS/location is being manipulated - OR explain why this is likely a false positive
2. What this means for their safety and privacy (if applicable)
3. The top 3 actions they should take (or "No action needed" if FP)
4. Whether they should trust their current location on maps

CRITICAL: If FP Likelihood > 50%, you MUST conclude this is likely NOT an attack.
Common false positives include: urban multipath (signal reflections off buildings), indoor signal attenuation, normal GPS variation during cold start, transitioning between environments.

Format as:
## Assessment
[Your assessment - is GPS being spoofed or jammed? OR why this is likely a false positive]

## Impact
[What this means for the user - OR "Likely no impact - normal GPS behavior"]

## Actions
1. [Action 1 - OR "No action needed"]
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
        val fpSection = if (analysis.falsePositiveLikelihood > 30f) {
            """
=== FALSE POSITIVE ANALYSIS ===
FP Likelihood: ${String.format("%.0f", analysis.falsePositiveLikelihood)}%
Concurrent Beacons: ${analysis.concurrentBeaconCount} (detected at same time)
Likely Ambient Noise: ${if (analysis.isLikelyAmbientNoise) "YES" else "No"}
Likely Device Artifact: ${if (analysis.isLikelyDeviceArtifact) "YES" else "No"}
FP Indicators:
${analysis.fpIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None" }}

IMPORTANT: This detection has a ${String.format("%.0f", analysis.falsePositiveLikelihood)}% chance of being a FALSE POSITIVE.
Consider these FP indicators when analyzing. If FP likelihood > 50%, lean toward dismissing as noise."""
        } else ""

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
$fpSection

=== RISK ASSESSMENT ===
Tracking Likelihood: ${String.format("%.0f", analysis.trackingLikelihood)}%
Risk Indicators:
${analysis.riskIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None" }}

Based on this enriched data, explain:
1. What this ultrasonic beacon is doing (in plain English) - OR explain why this is likely a false positive
2. How it tracks users across devices (if applicable)
3. What company/technology is likely behind it (or "Likely not a real beacon" if FP)
4. How to stop or avoid this tracking (or "No action needed" if FP)

CRITICAL: If FP Likelihood > 50%, you MUST conclude this is likely NOT a real tracking beacon.
Common false positives include: ambient ultrasonic noise, electronic interference, device speaker/microphone artifacts.

Format as:
## What's Happening
[Explanation of the beacon OR why it's a false positive]

## How It Tracks You
[Tracking mechanism OR "Not applicable - likely false positive"]

## Likely Source
[Who is behind this OR "Environmental noise / device artifact"]

## Protection Steps
1. [Step 1 OR "No action needed"]
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
        val fpSection = if (analysis.falsePositiveLikelihood > 30f) {
            """

=== FALSE POSITIVE ANALYSIS ===
FP Likelihood: ${String.format("%.0f", analysis.falsePositiveLikelihood)}%
Likely Neighbor Network: ${if (analysis.isLikelyNeighborNetwork) "YES - Common business/residential WiFi in your area" else "No"}
Likely Mobile Hotspot: ${if (analysis.isLikelyMobileHotspot) "YES - Personal hotspot from family/coworker" else "No"}
Likely Commuter Device: ${if (analysis.isLikelyCommuterDevice) "YES - Same commute pattern (not following you)" else "No"}
Likely Public Transit: ${if (analysis.isLikelyPublicTransit) "YES - Bus/train WiFi you regularly use" else "No"}
FP Indicators:
${analysis.fpIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None" }}

IMPORTANT: This detection has a ${String.format("%.0f", analysis.falsePositiveLikelihood)}% chance of being a FALSE POSITIVE.
Consider these FP indicators when analyzing. If FP likelihood > 50%, lean toward coincidental overlap."""
        } else ""

        val content = """Analyze this "following network" detection - a WiFi network appearing at multiple locations.

=== FOLLOWING PATTERN ===
Network SSID: ${sanitize(detection.ssid, 64).ifEmpty { "Unknown" }}
MAC Address: ${sanitize(detection.macAddress, 32).ifEmpty { "Unknown" }}
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
$fpSection

Based on this enriched data, provide:
1. Whether this network is genuinely following the user or a coincidence - OR explain why this is likely a false positive
2. The likely type of device/vehicle carrying this network (if applicable)
3. Whether this indicates physical surveillance or stalking (if applicable)
4. Immediate safety recommendations (or "No action needed" if FP)

CRITICAL: If FP Likelihood > 50%, you MUST conclude this is likely NOT surveillance.
Common false positives include: neighbor's WiFi visible from multiple locations, coworker/family member's mobile hotspot, commuters on same route, public transit WiFi, nearby businesses.

Format as:
## Assessment
[Is this network following the user? OR why this is likely a coincidence]

## Device Analysis
[What type of device is this likely? OR "Likely benign - neighbor/commuter/family device"]

## Safety Concern
[Physical safety assessment OR "No safety concern - likely coincidental overlap"]

## Immediate Actions
1. [Action 1 - OR "No action needed"]
2. [Action 2]
3. [Action 3]"""

        return wrapGemmaPrompt(content)
    }

    /**
     * Build a prompt for satellite/NTN detection analysis with enriched data.
     */
    fun buildSatelliteEnrichedPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData.Satellite
    ): String {
        val content = """Analyze this satellite/NTN (Non-Terrestrial Network) detection for potential surveillance.

=== DETECTION INFO ===
Device Type: ${detection.deviceType.displayName}
Threat Level: ${detection.threatLevel.displayName}
Threat Score: ${detection.threatScore}
First Detected: ${formatTimestamp(detection.timestamp)}
${if (detection.latitude != null && detection.longitude != null) "Location: ${String.format("%.4f", detection.latitude)}, ${String.format("%.4f", detection.longitude)}" else "Location: Unknown"}

=== SATELLITE/NTN CHARACTERISTICS ===
Detector Type: ${enrichedData.detectorType}
${enrichedData.signalCharacteristics.entries.joinToString("\n") { "${it.key}: ${it.value}" }}

=== METADATA ===
${enrichedData.metadata.entries.joinToString("\n") { "${it.key}: ${it.value}" }}

=== RISK INDICATORS ===
${enrichedData.riskIndicators.joinToString("\n") { "- $it" }.ifEmpty { "- None identified" }}

Based on this enriched satellite/NTN data, provide:
1. Assessment of whether this indicates unauthorized satellite tracking
2. The likely origin and purpose of this satellite signal
3. Risk level for the user
4. Recommended actions

Format as:
## Assessment
[Is this satellite signal indicative of surveillance?]

## Signal Analysis
[Technical analysis of the satellite characteristics]

## Risk Level
[Overall risk assessment]

## Recommended Actions
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

        // Sanitize device name if we need to display it
        val sanitizedDeviceName = sanitize(detection.deviceName)

        val content = """You are explaining a surveillance detection to a user.

$levelInstructions

=== DETECTION ===
Device: ${detection.deviceType.displayName}${if (sanitizedDeviceName.isNotEmpty()) " ($sanitizedDeviceName)" else ""}
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
${if (a.encryptionDowngraded) "⚠️ ENCRYPTION DOWNGRADED" else ""}
${if (a.falsePositiveLikelihood > 30f) "⚠️ FP LIKELIHOOD: ${String.format("%.0f", a.falsePositiveLikelihood)}% - MAY BE NORMAL HANDOFF" else ""}
${if (a.isLikelyNormalHandoff) "⚠️ LIKELY NORMAL CELL HANDOFF" else ""}
${if (a.isLikely5gBeamSteering) "⚠️ LIKELY 5G BEAM STEERING" else ""}"""
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
${if (a.lowElevHighSignalCount > 0) "⚠️ ${a.lowElevHighSignalCount} LOW-ELEV HIGH-SIGNAL SATELLITES" else ""}
${if (a.falsePositiveLikelihood > 30f) "⚠️ FP LIKELIHOOD: ${String.format("%.0f", a.falsePositiveLikelihood)}% - MAY BE NORMAL GPS" else ""}
${if (a.isLikelyUrbanMultipath) "⚠️ LIKELY URBAN MULTIPATH (building reflections)" else ""}
${if (a.isLikelyIndoorSignalLoss) "⚠️ LIKELY INDOOR SIGNAL ATTENUATION" else ""}"""
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
Persistence: ${String.format("%.0f", a.persistenceScore * 100)}%
${if (a.falsePositiveLikelihood > 30f) "⚠️ FP LIKELIHOOD: ${String.format("%.0f", a.falsePositiveLikelihood)}% - MAY BE NOISE" else ""}
${if (a.isLikelyAmbientNoise) "⚠️ LIKELY AMBIENT NOISE (${a.concurrentBeaconCount} concurrent beacons)" else ""}
${if (a.isLikelyDeviceArtifact) "⚠️ LIKELY DEVICE ARTIFACT" else ""}"""
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
${if (a.leadsUser) "⚠️ NETWORK LEADS USER (arrives before you)" else ""}
${if (a.falsePositiveLikelihood > 30f) "⚠️ FP LIKELIHOOD: ${String.format("%.0f", a.falsePositiveLikelihood)}% - MAY BE COINCIDENCE" else ""}
${if (a.isLikelyNeighborNetwork) "⚠️ LIKELY NEIGHBOR/BUSINESS WIFI" else ""}
${if (a.isLikelyCommuterDevice) "⚠️ LIKELY COMMUTER ON SAME ROUTE" else ""}"""
            }
            is EnrichedDetectorData.Satellite -> {
                """
=== ENRICHED SATELLITE/NTN DATA ===
Detector Type: ${data.detectorType}
Timestamp: ${formatTimestamp(data.timestamp)}
${data.signalCharacteristics.entries.joinToString("\n") { "${it.key}: ${it.value}" }}
${if (data.riskIndicators.isNotEmpty()) "⚠️ RISK INDICATORS: ${data.riskIndicators.joinToString(", ")}" else ""}
${data.metadata.entries.joinToString("\n") { "${it.key}: ${it.value}" }}"""
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
   - Location: ${if (d.latitude != null && d.longitude != null) "${String.format("%.4f", d.latitude)}, ${String.format("%.4f", d.longitude)}" else "Unknown"}
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

    // ==================== ENTERPRISE DETECTION DESCRIPTION TEMPLATES ====================

    /**
     * Enterprise-grade detection description that provides comprehensive, actionable intelligence.
     * Every description answers: WHAT, WHY, HOW CONFIDENT, WHAT TO DO, and CONTEXT.
     */
    data class EnterpriseDetectionDescription(
        // Core identification
        val headline: String,                    // 5-10 word summary
        val threatLevel: String,                 // CRITICAL, HIGH, MEDIUM, LOW, INFO

        // WHAT was detected
        val deviceSummary: String,               // What this device is
        val devicePurpose: String,               // What it does
        val dataCollectionSummary: String,       // What data it can collect

        // WHY it's flagged
        val triggerIndicators: List<String>,     // Specific indicators that triggered detection
        val threatReasoning: String,             // Why this threat level was assigned

        // HOW CONFIDENT
        val confidenceScore: Int,                // 0-100
        val confidenceReasoning: String,         // Why this confidence level

        // FALSE POSITIVE assessment
        val falsePositiveLikelihood: Int,        // 0-100
        val falsePositiveReasons: List<String>,  // Why this might be a false alarm
        val isMostLikelyBenign: Boolean,         // If FP > 50%, this is true
        val benignExplanation: String?,          // If likely benign, explain why

        // CONTEXT
        val isNormalForLocation: Boolean?,       // Is this expected at this location?
        val isRecurring: Boolean,                // Seen before?
        val correlatedDetections: List<String>,  // Other detections that correlate
        val environmentalContext: String?,       // Near gov building, protest, etc.

        // WHAT TO DO - actionable intelligence
        val immediateAction: ActionItem?,        // Do this NOW if needed
        val monitoringRecommendation: String,    // How to monitor going forward
        val documentationSuggestion: String?,    // Screenshot, report, etc.
        val additionalResources: List<String>,   // Links/resources for more info

        // User-friendly versions
        val simpleExplanation: String,           // Non-technical explanation
        val technicalDetails: String             // For advanced users
    )

    /**
     * Actionable item with urgency level
     */
    data class ActionItem(
        val action: String,
        val urgency: ActionUrgency,
        val reason: String
    )

    enum class ActionUrgency {
        IMMEDIATE,   // Do this right now
        SOON,        // Within the next hour
        WHEN_CONVENIENT,  // When you have time
        FOR_AWARENESS     // Just know about this
    }

    /**
     * Generate enterprise-grade detection description based on detection data and analysis.
     */
    fun generateEnterpriseDescription(
        detection: Detection,
        enrichedData: EnrichedDetectorData? = null,
        contextualInsights: ContextualInsights? = null,
        falsePositiveResult: FalsePositiveAnalysisResult? = null
    ): EnterpriseDetectionDescription {
        val deviceType = detection.deviceType
        val threatLevel = detection.threatLevel
        val threatScore = detection.threatScore

        // Determine false positive likelihood
        val fpLikelihood = falsePositiveResult?.likelihood ?: estimateFalsePositiveLikelihood(detection, enrichedData)
        val isMostLikelyBenign = fpLikelihood > 50

        // Generate headline based on severity and FP likelihood
        val headline = generateHeadline(detection, isMostLikelyBenign)

        // Get device info
        val deviceInfo = getDeviceInfoForDescription(deviceType)

        // Generate trigger indicators
        val triggerIndicators = generateTriggerIndicators(detection, enrichedData)

        // Generate confidence reasoning
        val (confidenceScore, confidenceReasoning) = generateConfidenceAssessment(detection, enrichedData)

        // Generate false positive reasons
        val fpReasons = generateFalsePositiveReasons(detection, enrichedData)

        // Generate contextual assessment
        val (isNormalForLocation, environmentalContext) = assessContext(detection, contextualInsights)

        // Generate actions based on severity and FP likelihood
        val (immediateAction, monitoringRec, docSuggestion) = generateActionableIntelligence(
            detection, isMostLikelyBenign, threatLevel
        )

        // Generate explanations
        val simpleExplanation = generateSimpleExplanation(detection, isMostLikelyBenign)
        val technicalDetails = generateTechnicalDetails(detection, enrichedData)

        return EnterpriseDetectionDescription(
            headline = headline,
            threatLevel = threatLevel.displayName,
            deviceSummary = deviceInfo.summary,
            devicePurpose = deviceInfo.purpose,
            dataCollectionSummary = deviceInfo.dataCollection,
            triggerIndicators = triggerIndicators,
            threatReasoning = generateThreatReasoning(detection, enrichedData),
            confidenceScore = confidenceScore,
            confidenceReasoning = confidenceReasoning,
            falsePositiveLikelihood = fpLikelihood,
            falsePositiveReasons = fpReasons,
            isMostLikelyBenign = isMostLikelyBenign,
            benignExplanation = if (isMostLikelyBenign) generateBenignExplanation(detection, fpReasons) else null,
            isNormalForLocation = isNormalForLocation,
            isRecurring = detection.seenCount > 1,
            correlatedDetections = contextualInsights?.let { listOfNotNull(it.clusterInfo) } ?: emptyList(),
            environmentalContext = environmentalContext,
            immediateAction = immediateAction,
            monitoringRecommendation = monitoringRec,
            documentationSuggestion = docSuggestion,
            additionalResources = getResourcesForDeviceType(deviceType),
            simpleExplanation = simpleExplanation,
            technicalDetails = technicalDetails
        )
    }

    /**
     * Generate headline that accurately reflects severity and FP likelihood.
     */
    private fun generateHeadline(detection: Detection, isMostLikelyBenign: Boolean): String {
        val deviceName = detection.deviceType.displayName

        return when {
            isMostLikelyBenign -> when (detection.threatLevel) {
                ThreatLevel.CRITICAL, ThreatLevel.HIGH ->
                    "Possible $deviceName - Likely False Alarm"
                ThreatLevel.MEDIUM ->
                    "$deviceName Detected - Probably Normal"
                else ->
                    "$deviceName Nearby - Normal Activity"
            }
            else -> when (detection.threatLevel) {
                ThreatLevel.CRITICAL ->
                    "ALERT: Active $deviceName Detected"
                ThreatLevel.HIGH ->
                    "Warning: $deviceName Confirmed"
                ThreatLevel.MEDIUM ->
                    "$deviceName Detected - Monitor"
                ThreatLevel.LOW ->
                    "$deviceName Nearby - Low Concern"
                ThreatLevel.INFO ->
                    "$deviceName Observed"
            }
        }
    }

    private data class DeviceInfoForDescription(
        val summary: String,
        val purpose: String,
        val dataCollection: String
    )

    private fun getDeviceInfoForDescription(deviceType: DeviceType): DeviceInfoForDescription {
        return when (deviceType) {
            DeviceType.STINGRAY_IMSI -> DeviceInfoForDescription(
                summary = "Cell-site simulator (also known as IMSI catcher or StingRay)",
                purpose = "Forces mobile phones to connect to it instead of legitimate cell towers, enabling interception of communications and precise location tracking",
                dataCollection = "Phone identifiers (IMSI/IMEI), call metadata, text messages, real-time location, and potentially call/text content when forcing 2G downgrade"
            )
            DeviceType.GNSS_SPOOFER -> DeviceInfoForDescription(
                summary = "GPS/GNSS spoofing device",
                purpose = "Transmits fake satellite signals to manipulate location data on devices in range",
                dataCollection = "Does not collect data directly, but manipulates your device's reported location"
            )
            DeviceType.GNSS_JAMMER -> DeviceInfoForDescription(
                summary = "GPS/GNSS jamming device",
                purpose = "Blocks legitimate satellite signals to prevent GPS positioning",
                dataCollection = "Does not collect data, but denies GPS service to devices in range"
            )
            DeviceType.ULTRASONIC_BEACON -> DeviceInfoForDescription(
                summary = "Ultrasonic tracking beacon",
                purpose = "Emits inaudible sound (18-22 kHz) to track users across devices for advertising attribution",
                dataCollection = "Cross-device identity linking, app usage correlation, physical location presence"
            )
            DeviceType.RING_DOORBELL -> DeviceInfoForDescription(
                summary = "Amazon Ring smart doorbell/camera",
                purpose = "Consumer video doorbell that records visitors and can share footage with law enforcement",
                dataCollection = "Video/audio of visitors, motion events, and can be accessed by police through Neighbors program"
            )
            DeviceType.FLOCK_SAFETY_CAMERA -> DeviceInfoForDescription(
                summary = "Flock Safety automatic license plate recognition (ALPR) camera",
                purpose = "Captures license plates of passing vehicles for law enforcement searches",
                dataCollection = "License plate numbers, vehicle make/model/color, timestamps, direction of travel"
            )
            DeviceType.AIRTAG -> DeviceInfoForDescription(
                summary = "Apple AirTag Bluetooth tracker",
                purpose = "Item tracker using Apple's Find My network of billions of devices",
                dataCollection = "Precise location tracking through crowdsourced Bluetooth detection"
            )
            DeviceType.ROGUE_AP -> DeviceInfoForDescription(
                summary = "Suspicious or rogue WiFi access point",
                purpose = "May attempt to intercept network traffic through evil twin attacks",
                dataCollection = "Network traffic, credentials if connected without VPN, browsing activity"
            )
            else -> DeviceInfoForDescription(
                summary = "${deviceType.displayName}",
                purpose = "Surveillance or monitoring device with variable capabilities",
                dataCollection = "Depends on device type - may include location, identifiers, or behavioral data"
            )
        }
    }

    private fun generateTriggerIndicators(
        detection: Detection,
        enrichedData: EnrichedDetectorData?
    ): List<String> {
        val indicators = mutableListOf<String>()

        // Add matched patterns if available
        detection.matchedPatterns?.let {
            indicators.add("Pattern match: $it")
        }

        // Add signal-based indicators
        if (detection.rssi > -50) {
            indicators.add("Very strong signal (${detection.rssi} dBm) indicates close proximity")
        }

        // Add enriched data indicators
        when (enrichedData) {
            is EnrichedDetectorData.Cellular -> {
                val analysis = enrichedData.analysis
                if (analysis.encryptionDowngraded) {
                    indicators.add("Encryption downgrade detected: ${analysis.encryptionDowngradeChain.joinToString(" -> ")}")
                }
                if (analysis.impossibleSpeed) {
                    indicators.add("Impossible tower movement detected (suggests fake cell tower)")
                }
                if (analysis.downgradeWithSignalSpike) {
                    indicators.add("Classic IMSI catcher signature: encryption downgrade with signal spike")
                }
                if (analysis.cellTrustScore < 30) {
                    indicators.add("Unfamiliar cell tower (trust score: ${analysis.cellTrustScore}%)")
                }
            }
            is EnrichedDetectorData.Gnss -> {
                val analysis = enrichedData.analysis
                if (analysis.cn0TooUniform) {
                    indicators.add("Suspiciously uniform signal strength across satellites")
                }
                if (analysis.lowElevHighSignalCount > 0) {
                    indicators.add("${analysis.lowElevHighSignalCount} satellites with impossible signal characteristics")
                }
                if (analysis.missingConstellations.isNotEmpty()) {
                    indicators.add("Missing expected satellite constellations: ${analysis.missingConstellations.joinToString { it.code }}")
                }
            }
            is EnrichedDetectorData.Ultrasonic -> {
                val analysis = enrichedData.analysis
                if (analysis.followingUser) {
                    indicators.add("Same beacon detected at ${analysis.locationsDetected} different locations you visited")
                }
                indicators.add("Frequency: ${analysis.frequencyHz} Hz matches ${analysis.matchedSource.displayName} signature")
            }
            is EnrichedDetectorData.WifiFollowing -> {
                val analysis = enrichedData.analysis
                indicators.add("Network seen ${analysis.sightingCount} times at ${analysis.distinctLocations} locations")
                if (analysis.leadsUser) {
                    indicators.add("SUSPICIOUS: Network appears at locations BEFORE you arrive")
                }
                if (analysis.vehicleMounted) {
                    indicators.add("Movement pattern suggests vehicle-mounted device")
                }
            }
            else -> {}
        }

        // Add detection method
        indicators.add("Detected via: ${detection.detectionMethod.displayName}")

        return indicators
    }

    private fun generateConfidenceAssessment(
        detection: Detection,
        enrichedData: EnrichedDetectorData?
    ): Pair<Int, String> {
        var confidence = 50 // Base confidence
        val reasons = mutableListOf<String>()

        // Adjust based on detection method reliability
        when (detection.detectionMethod) {
            DetectionMethod.MANUFACTURER_OUI -> {
                confidence += 20
                reasons.add("Manufacturer fingerprint confirmed")
            }
            DetectionMethod.SSID_PATTERN -> {
                confidence += 15
                reasons.add("SSID matches known pattern")
            }
            DetectionMethod.BEHAVIOR_ANALYSIS -> {
                confidence += 10
                reasons.add("Behavioral analysis match")
            }
            else -> {}
        }

        // Adjust based on enriched data
        when (enrichedData) {
            is EnrichedDetectorData.Cellular -> {
                val analysis = enrichedData.analysis
                confidence = analysis.imsiCatcherScore
                if (analysis.imsiCatcherScore > 70) {
                    reasons.add("High IMSI catcher score (${analysis.imsiCatcherScore}%)")
                } else {
                    reasons.add("IMSI catcher score: ${analysis.imsiCatcherScore}%")
                }
            }
            is EnrichedDetectorData.Gnss -> {
                val analysis = enrichedData.analysis
                confidence = analysis.spoofingLikelihood.toInt()
                reasons.add("Spoofing likelihood: ${analysis.spoofingLikelihood.toInt()}%")
            }
            is EnrichedDetectorData.Ultrasonic -> {
                val analysis = enrichedData.analysis
                confidence = analysis.trackingLikelihood.toInt()
                reasons.add("Tracking likelihood: ${analysis.trackingLikelihood.toInt()}%")
            }
            else -> {}
        }

        // Adjust based on repeated sightings
        if (detection.seenCount > 3) {
            confidence += 10
            reasons.add("Detected ${detection.seenCount} times - consistent presence")
        }

        confidence = confidence.coerceIn(0, 100)
        val reasoning = reasons.joinToString("; ")

        return Pair(confidence, reasoning)
    }

    private fun generateFalsePositiveReasons(
        detection: Detection,
        enrichedData: EnrichedDetectorData?
    ): List<String> {
        val reasons = mutableListOf<String>()

        when (enrichedData) {
            is EnrichedDetectorData.Cellular -> {
                val analysis = enrichedData.analysis
                if (analysis.isLikelyNormalHandoff) {
                    reasons.add("Normal cell tower handoff while moving")
                }
                if (analysis.isLikelyCarrierBehavior) {
                    reasons.add("Known carrier network optimization behavior")
                }
                if (analysis.isLikelyEdgeCoverage) {
                    reasons.add("You are at the edge of cell coverage")
                }
                if (analysis.isLikely5gBeamSteering) {
                    reasons.add("Normal 5G beam steering/management")
                }
                reasons.addAll(analysis.fpIndicators)
            }
            is EnrichedDetectorData.Gnss -> {
                val analysis = enrichedData.analysis
                if (analysis.isLikelyUrbanMultipath) {
                    reasons.add("Urban multipath - GPS signals bouncing off buildings")
                }
                if (analysis.isLikelyIndoorSignalLoss) {
                    reasons.add("Indoor signal attenuation - normal for being inside")
                }
                if (analysis.isLikelyNormalOperation) {
                    reasons.add("Normal GPS variation during position calculation")
                }
                reasons.addAll(analysis.fpIndicators)
            }
            is EnrichedDetectorData.Ultrasonic -> {
                val analysis = enrichedData.analysis
                if (analysis.isLikelyAmbientNoise) {
                    reasons.add("Ambient ultrasonic noise (electronics, HVAC, etc.)")
                }
                if (analysis.isLikelyDeviceArtifact) {
                    reasons.add("Audio artifact from your device's hardware")
                }
                reasons.addAll(analysis.fpIndicators)
            }
            is EnrichedDetectorData.WifiFollowing -> {
                val analysis = enrichedData.analysis
                if (analysis.isLikelyNeighborNetwork) {
                    reasons.add("Common neighborhood WiFi visible from multiple spots")
                }
                if (analysis.isLikelyMobileHotspot) {
                    reasons.add("Family member or coworker's mobile hotspot")
                }
                if (analysis.isLikelyCommuterDevice) {
                    reasons.add("Someone on your same commute route (not following you)")
                }
                if (analysis.isLikelyPublicTransit) {
                    reasons.add("Public transit WiFi you use regularly")
                }
                reasons.addAll(analysis.fpIndicators)
            }
            else -> {
                // Generic false positive reasons based on device type
                when (detection.deviceType) {
                    DeviceType.RING_DOORBELL, DeviceType.NEST_CAMERA,
                    DeviceType.WYZE_CAMERA, DeviceType.ARLO_CAMERA -> {
                        reasons.add("Consumer home security device owned by neighbor")
                    }
                    DeviceType.BLUETOOTH_BEACON -> {
                        reasons.add("Common retail/commercial beacon for indoor navigation")
                    }
                    else -> {}
                }
            }
        }

        return reasons
    }

    private fun assessContext(
        detection: Detection,
        contextualInsights: ContextualInsights?
    ): Pair<Boolean?, String?> {
        var isNormalForLocation: Boolean? = null
        var environmentalContext: String? = null

        contextualInsights?.let {
            isNormalForLocation = it.isKnownLocation
            if (it.isKnownLocation) {
                environmentalContext = "This location is in your regular travel pattern"
            }
        }

        // TODO: In future, could integrate with location services to detect:
        // - Near government buildings
        // - Near protest locations
        // - Airport/transit hub areas
        // - High-security zones

        return Pair(isNormalForLocation, environmentalContext)
    }

    private fun generateActionableIntelligence(
        detection: Detection,
        isMostLikelyBenign: Boolean,
        threatLevel: ThreatLevel
    ): Triple<ActionItem?, String, String?> {
        // If likely benign, downgrade actions
        if (isMostLikelyBenign) {
            return Triple(
                null, // No immediate action needed
                "Continue normal monitoring. We're logging this for pattern analysis.",
                null // No documentation needed
            )
        }

        val immediateAction: ActionItem?
        val monitoringRec: String
        val docSuggestion: String?

        when (threatLevel) {
            ThreatLevel.CRITICAL -> {
                immediateAction = ActionItem(
                    action = when (detection.deviceType) {
                        DeviceType.STINGRAY_IMSI -> "Enable airplane mode or use a Faraday bag NOW"
                        DeviceType.GNSS_SPOOFER -> "DO NOT rely on GPS navigation - verify your location visually"
                        else -> "Consider leaving this area if safety allows"
                    },
                    urgency = ActionUrgency.IMMEDIATE,
                    reason = "Active surveillance device detected with high confidence"
                )
                monitoringRec = "High alert - check back frequently for changes"
                docSuggestion = "Screenshot this detection with timestamp for documentation"
            }
            ThreatLevel.HIGH -> {
                immediateAction = ActionItem(
                    action = "Use encrypted communications (Signal, WhatsApp) only",
                    urgency = ActionUrgency.SOON,
                    reason = "Your communications may be monitored"
                )
                monitoringRec = "Monitor for recurring detections in this area"
                docSuggestion = "Note this location as a surveillance hotspot"
            }
            ThreatLevel.MEDIUM -> {
                immediateAction = null
                monitoringRec = "Check this area periodically for changes"
                docSuggestion = "Optional: log this detection in your privacy journal"
            }
            else -> {
                immediateAction = null
                monitoringRec = "Standard monitoring - no special action needed"
                docSuggestion = null
            }
        }

        return Triple(immediateAction, monitoringRec, docSuggestion)
    }

    private fun getResourcesForDeviceType(deviceType: DeviceType): List<String> {
        return when (deviceType) {
            DeviceType.STINGRAY_IMSI -> listOf(
                "EFF Guide to IMSI Catchers: eff.org/pages/cell-site-simulatorsimsi-catchers",
                "ACLU StingRay Tracking Devices: aclu.org/issues/privacy-technology/surveillance-technologies/stingray-tracking-devices"
            )
            DeviceType.FLOCK_SAFETY_CAMERA, DeviceType.LICENSE_PLATE_READER -> listOf(
                "EFF Atlas of Surveillance: atlasofsurveillance.org",
                "ACLU You Are Being Tracked: aclu.org/issues/privacy-technology/location-tracking/you-are-being-tracked"
            )
            DeviceType.RING_DOORBELL -> listOf(
                "Ring & Police Partnerships: eff.org/deeplinks/2019/08/five-concerns-about-amazon-rings-deals-police"
            )
            DeviceType.AIRTAG, DeviceType.TILE_TRACKER, DeviceType.SAMSUNG_SMARTTAG -> listOf(
                "Apple AirTag Safety: support.apple.com/en-us/HT212227"
            )
            else -> emptyList()
        }
    }

    private fun generateSimpleExplanation(detection: Detection, isMostLikelyBenign: Boolean): String {
        val deviceType = detection.deviceType

        if (isMostLikelyBenign) {
            return when (deviceType) {
                DeviceType.STINGRAY_IMSI ->
                    "Your phone's connection changed, but this is probably just normal cell tower behavior. " +
                    "Think of it like switching lanes on a highway - happens all the time."
                DeviceType.GNSS_SPOOFER, DeviceType.GNSS_JAMMER ->
                    "Your GPS had some trouble, but this is most likely due to being indoors or near tall buildings. " +
                    "It's like how your car GPS sometimes loses signal in a parking garage."
                DeviceType.ULTRASONIC_BEACON ->
                    "We detected a high-frequency sound, but it's probably just background noise from electronics. " +
                    "Many everyday devices make sounds we can't hear."
                else ->
                    "We detected a ${deviceType.displayName}, but it's most likely a normal device that poses no threat to you."
            }
        }

        return when (deviceType) {
            DeviceType.STINGRAY_IMSI ->
                "A device that pretends to be a cell tower was detected. It can potentially see your phone's ID " +
                "and track your location. Think of it like someone setting up a fake checkpoint to see who passes by."
            DeviceType.GNSS_SPOOFER ->
                "Something is trying to trick your GPS into showing a wrong location. " +
                "It's like someone switching street signs to send you the wrong way."
            DeviceType.FLOCK_SAFETY_CAMERA ->
                "A camera that reads license plates was detected. It takes photos of every car that passes by " +
                "and stores them in a database that police can search."
            DeviceType.RING_DOORBELL ->
                "A Ring doorbell camera was detected nearby. These cameras record video and audio, " +
                "and the footage can be shared with police even without a warrant in some cases."
            DeviceType.AIRTAG ->
                "An Apple AirTag tracker was detected. If this isn't yours, someone might be tracking your location. " +
                "Check your belongings and car for a small round device."
            else ->
                "A ${deviceType.displayName} was detected. ${detection.detectionMethod.description}"
        }
    }

    private fun generateTechnicalDetails(detection: Detection, enrichedData: EnrichedDetectorData?): String {
        val details = StringBuilder()

        details.appendLine("=== Technical Detection Details ===")
        details.appendLine("Device Type: ${detection.deviceType.name}")
        details.appendLine("Protocol: ${detection.protocol.displayName}")
        details.appendLine("Detection Method: ${detection.detectionMethod.name}")
        details.appendLine("RSSI: ${detection.rssi} dBm")
        details.appendLine("Threat Score: ${detection.threatScore}/100")
        detection.macAddress?.let { details.appendLine("MAC: $it") }
        detection.manufacturer?.let { details.appendLine("Manufacturer OUI: $it") }
        detection.ssid?.let { details.appendLine("SSID: $it") }
        detection.matchedPatterns?.let { details.appendLine("Matched Patterns: $it") }

        when (enrichedData) {
            is EnrichedDetectorData.Cellular -> {
                val a = enrichedData.analysis
                details.appendLine("\n=== Cellular Analysis ===")
                details.appendLine("IMSI Catcher Score: ${a.imsiCatcherScore}%")
                details.appendLine("Encryption Chain: ${a.encryptionDowngradeChain.joinToString(" -> ")}")
                details.appendLine("Current Encryption: ${a.currentEncryption.displayName}")
                details.appendLine("Cell Trust Score: ${a.cellTrustScore}%")
                details.appendLine("Movement: ${a.movementType.displayName} (${String.format("%.1f", a.speedKmh)} km/h)")
                details.appendLine("False Positive Likelihood: ${String.format("%.0f", a.falsePositiveLikelihood)}%")
            }
            is EnrichedDetectorData.Gnss -> {
                val a = enrichedData.analysis
                details.appendLine("\n=== GNSS Analysis ===")
                details.appendLine("Spoofing Likelihood: ${String.format("%.0f", a.spoofingLikelihood)}%")
                details.appendLine("Jamming Likelihood: ${String.format("%.0f", a.jammingLikelihood)}%")
                details.appendLine("Constellation Match: ${a.constellationMatchScore}%")
                details.appendLine("C/N0: ${String.format("%.1f", a.currentCn0Mean)} dB-Hz")
                details.appendLine("Geometry Score: ${String.format("%.0f", a.geometryScore * 100)}%")
                details.appendLine("False Positive Likelihood: ${String.format("%.0f", a.falsePositiveLikelihood)}%")
            }
            is EnrichedDetectorData.Ultrasonic -> {
                val a = enrichedData.analysis
                details.appendLine("\n=== Ultrasonic Analysis ===")
                details.appendLine("Frequency: ${a.frequencyHz} Hz")
                details.appendLine("Matched Source: ${a.matchedSource.displayName}")
                details.appendLine("Source Category: ${a.sourceCategory.displayName}")
                details.appendLine("Tracking Likelihood: ${String.format("%.0f", a.trackingLikelihood)}%")
                details.appendLine("SNR: ${String.format("%.1f", a.snrDb)} dB")
                details.appendLine("Persistence: ${String.format("%.0f", a.persistenceScore * 100)}%")
            }
            is EnrichedDetectorData.WifiFollowing -> {
                val a = enrichedData.analysis
                details.appendLine("\n=== WiFi Following Analysis ===")
                details.appendLine("Following Confidence: ${String.format("%.0f", a.followingConfidence)}%")
                details.appendLine("Sightings: ${a.sightingCount} at ${a.distinctLocations} locations")
                details.appendLine("Path Correlation: ${String.format("%.0f", a.pathCorrelation * 100)}%")
                details.appendLine("Time Pattern: ${a.timePattern.displayName}")
                details.appendLine("Signal Consistency: ${String.format("%.0f", a.signalConsistency * 100)}%")
            }
            else -> {}
        }

        return details.toString()
    }

    private fun estimateFalsePositiveLikelihood(
        detection: Detection,
        enrichedData: EnrichedDetectorData?
    ): Int {
        // Use enriched data if available
        when (enrichedData) {
            is EnrichedDetectorData.Cellular -> return enrichedData.analysis.falsePositiveLikelihood.toInt()
            is EnrichedDetectorData.Gnss -> return enrichedData.analysis.falsePositiveLikelihood.toInt()
            is EnrichedDetectorData.Ultrasonic -> return enrichedData.analysis.falsePositiveLikelihood.toInt()
            is EnrichedDetectorData.WifiFollowing -> return enrichedData.analysis.falsePositiveLikelihood.toInt()
            else -> {}
        }

        // Estimate based on device type and threat level
        return when (detection.deviceType) {
            // Consumer devices - high FP likelihood
            DeviceType.RING_DOORBELL, DeviceType.NEST_CAMERA, DeviceType.WYZE_CAMERA,
            DeviceType.ARLO_CAMERA, DeviceType.EUFY_CAMERA, DeviceType.BLINK_CAMERA -> 80

            // Trackers - medium FP if weak signal
            DeviceType.AIRTAG, DeviceType.TILE_TRACKER, DeviceType.SAMSUNG_SMARTTAG ->
                if (detection.rssi < -70) 60 else 20

            // Infrastructure - high FP
            DeviceType.BLUETOOTH_BEACON, DeviceType.RETAIL_TRACKER -> 75

            // Serious threats - low FP if high confidence
            DeviceType.STINGRAY_IMSI, DeviceType.GNSS_SPOOFER, DeviceType.GNSS_JAMMER ->
                if (detection.threatScore > 70) 20 else 50

            else -> 50 // Unknown - 50/50
        }
    }

    private fun generateThreatReasoning(detection: Detection, enrichedData: EnrichedDetectorData?): String {
        val threatLevel = detection.threatLevel
        val deviceType = detection.deviceType

        val baseReason = when (threatLevel) {
            ThreatLevel.CRITICAL -> "This device type can actively intercept or manipulate data"
            ThreatLevel.HIGH -> "This device can collect identifying information about you"
            ThreatLevel.MEDIUM -> "This device may track your presence or behavior"
            ThreatLevel.LOW -> "This device has limited surveillance capability"
            ThreatLevel.INFO -> "This device poses minimal direct privacy risk"
        }

        val specificReason = when (enrichedData) {
            is EnrichedDetectorData.Cellular -> {
                val a = enrichedData.analysis
                when {
                    a.encryptionDowngraded && a.downgradeWithSignalSpike ->
                        "Classic IMSI catcher signature detected: forced encryption downgrade with simultaneous signal spike"
                    a.encryptionDowngraded ->
                        "Your phone's encryption was downgraded, which could allow interception"
                    a.imsiCatcherScore > 70 ->
                        "Multiple indicators suggest cell-site simulator activity"
                    else ->
                        "Some cellular anomalies detected but not conclusive"
                }
            }
            is EnrichedDetectorData.Gnss -> {
                val a = enrichedData.analysis
                when {
                    a.spoofingLikelihood > 70 ->
                        "Satellite signals show characteristics of spoofed/fake signals"
                    a.jammingLikelihood > 70 ->
                        "GPS signal blockage pattern consistent with intentional jamming"
                    else ->
                        "GPS anomalies detected but may be environmental"
                }
            }
            else -> ""
        }

        return if (specificReason.isNotEmpty()) {
            "$baseReason. $specificReason"
        } else {
            baseReason
        }
    }

    private fun generateBenignExplanation(detection: Detection, fpReasons: List<String>): String {
        val mainReason = fpReasons.firstOrNull() ?: "Environmental factors"

        return "This is most likely NOT a real threat. $mainReason. " +
               "We're logging this detection for pattern analysis, but no action is needed. " +
               "Common causes include: ${fpReasons.take(3).joinToString(", ").ifEmpty { "normal network behavior" }}."
    }

    /**
     * Format the enterprise description as a user-facing string.
     */
    fun formatEnterpriseDescriptionForUser(desc: EnterpriseDetectionDescription): String {
        return buildString {
            appendLine("## ${desc.headline}")
            appendLine()

            if (desc.isMostLikelyBenign) {
                appendLine("### Likely False Alarm")
                appendLine(desc.benignExplanation ?: "This detection is probably not a real threat.")
                appendLine()
                appendLine("**Why we think this:**")
                desc.falsePositiveReasons.take(3).forEach { appendLine("- $it") }
                appendLine()
            }

            appendLine("### What Was Detected")
            appendLine(desc.deviceSummary)
            appendLine()
            appendLine("**Purpose:** ${desc.devicePurpose}")
            appendLine()
            appendLine("**Data Collection:** ${desc.dataCollectionSummary}")
            appendLine()

            appendLine("### Why This Was Flagged")
            desc.triggerIndicators.forEach { appendLine("- $it") }
            appendLine()

            appendLine("### Confidence Assessment")
            appendLine("- Confidence: ${desc.confidenceScore}%")
            appendLine("- ${desc.confidenceReasoning}")
            appendLine("- False positive likelihood: ${desc.falsePositiveLikelihood}%")
            appendLine()

            if (!desc.isMostLikelyBenign) {
                appendLine("### Recommended Actions")
                desc.immediateAction?.let {
                    appendLine("**${it.urgency.name}:** ${it.action}")
                    appendLine("*Reason: ${it.reason}*")
                    appendLine()
                }
                appendLine("**Monitoring:** ${desc.monitoringRecommendation}")
                desc.documentationSuggestion?.let { appendLine("**Documentation:** $it") }
                appendLine()
            } else {
                appendLine("### No Action Needed")
                appendLine(desc.monitoringRecommendation)
                appendLine()
            }

            if (desc.additionalResources.isNotEmpty()) {
                appendLine("### Learn More")
                desc.additionalResources.forEach { appendLine("- $it") }
            }
        }
    }

    /**
     * Data class for contextual insights used in enterprise descriptions.
     */
    data class ContextualInsights(
        val isKnownLocation: Boolean,
        val locationPattern: String?,
        val timePattern: String?,
        val clusterInfo: String?,
        val historicalContext: String?
    )

    /**
     * Data class for false positive analysis result.
     */
    data class FalsePositiveAnalysisResult(
        val likelihood: Int,
        val reasons: List<String>
    )

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

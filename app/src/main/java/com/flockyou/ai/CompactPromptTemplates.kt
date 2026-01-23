package com.flockyou.ai

import com.flockyou.data.model.Detection
import com.flockyou.data.model.ThreatLevel
import com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomalyAnalysis
import com.flockyou.service.CellularMonitor.CellularAnomalyAnalysis
import com.flockyou.service.RogueWifiMonitor.FollowingNetworkAnalysis
import com.flockyou.service.UltrasonicDetector.BeaconAnalysis

/**
 * Compact prompt templates for LLM-based analysis.
 *
 * These prompts are compressed by 50-70% compared to PromptTemplates for faster inference
 * on resource-constrained devices or when using smaller models.
 *
 * Compression strategies:
 * - Abbreviations with legend (e.g., "det" for detection, "dev" for device)
 * - Structured JSON-like output format
 * - Minimal instructions, maximum signal
 * - Removed verbose explanations
 *
 * Token estimates (vs PromptTemplates):
 * - compactAnalysisPrompt: ~400 tokens (vs ~1200)
 * - compactFalsePositivePrompt: ~250 tokens (vs ~800)
 * - compactSummaryPrompt: ~350 tokens (vs ~900)
 */
object CompactPromptTemplates {

    // ==================== ABBREVIATION LEGEND ====================

    /**
     * Legend for abbreviations used in compact prompts.
     * Placed at the start of prompts so LLM understands the shorthand.
     */
    private const val ABBREVIATION_LEGEND = """KEY: det=detection, dev=device, sig=signal, thr=threat, FP=false positive, enc=encryption, loc=location, conf=confidence"""

    // ==================== INPUT SANITIZATION ====================

    private const val MAX_INPUT_LENGTH = 128

    private fun sanitize(input: String?, maxLength: Int = MAX_INPUT_LENGTH): String {
        if (input.isNullOrBlank()) return ""
        return input
            .take(maxLength)
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            .replace(Regex("</?(?:start_of_turn|end_of_turn|system|user|model|assistant|human)>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[/?(?:INST|SYS|SYSTEM|USER)\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    // ==================== PROMPT WRAPPER ====================

    private fun wrapGemmaPrompt(content: String): String {
        return """<start_of_turn>user
$content
<end_of_turn>
<start_of_turn>model
"""
    }

    // ==================== COMPACT ANALYSIS PROMPT ====================

    /**
     * Compact analysis prompt - ~400 tokens (target: <500)
     *
     * Returns structured output for easy parsing.
     */
    fun compactAnalysisPrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData? = null
    ): String {
        val enrichedSection = enrichedData?.let { buildCompactEnrichedSection(it) } ?: ""
        val fpLikelihood = estimateFalsePositiveLikelihood(detection, enrichedData)

        val content = """$ABBREVIATION_LEGEND

Analyze det, respond JSON only:
{
"headline":"5-10 words",
"thr_level":"${detection.threatLevel.displayName}",
"what":"dev purpose in 1 sentence",
"data":["data type 1","data type 2"],
"fp_pct":0-100,
"fp_likely":true/false,
"action":"what to do or null",
"urgency":"IMMEDIATE|SOON|NONE",
"simple":"non-tech explanation"
}

DET:
type=${detection.deviceType.displayName}
proto=${detection.protocol.displayName}
method=${detection.detectionMethod.displayName}
sig=${detection.rssi}dBm(${detection.signalStrength.displayName})
thr=${detection.threatLevel.displayName}(${detection.threatScore}/100)
seen=${detection.seenCount}
${sanitize(detection.manufacturer)?.takeIf { it.isNotEmpty() }?.let { "mfr=$it" } ?: ""}
${sanitize(detection.deviceName)?.takeIf { it.isNotEmpty() }?.let { "name=$it" } ?: ""}
$enrichedSection
est_fp=$fpLikelihood%

RULES:
-fp_pct>50:fp_likely=true,calm tone
-LOW/INFO thr:no alarm words
-CRITICAL only:urgency=IMMEDIATE

JSON:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== COMPACT FALSE POSITIVE PROMPT ====================

    /**
     * Compact false positive check prompt - ~250 tokens (target: <300)
     */
    fun compactFalsePositivePrompt(
        detection: Detection,
        enrichedData: EnrichedDetectorData? = null
    ): String {
        val enrichedSection = enrichedData?.let { buildCompactEnrichedSection(it) } ?: ""

        val content = """$ABBREVIATION_LEGEND

Is det likely FP? Respond JSON:
{"fp_pct":0-100,"reasons":["reason1","reason2"],"is_benign":true/false,"explain":"why if benign"}

DET:
type=${detection.deviceType.displayName}
proto=${detection.protocol.displayName}
sig=${detection.rssi}dBm
thr=${detection.threatLevel.displayName}(${detection.threatScore})
seen=${detection.seenCount}
$enrichedSection

Common FPs:normal cell handoff,urban GPS multipath,neighbor wifi,device noise

JSON:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== COMPACT SUMMARY PROMPT ====================

    /**
     * Compact summary prompt - ~350 tokens (target: <400)
     */
    fun compactSummaryPrompt(
        detections: List<Detection>,
        periodDescription: String,
        previousPeriodComparison: String? = null
    ): String {
        val byType = detections.groupBy { it.deviceType }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .joinToString(",") { "${it.first.displayName}:${it.second}" }

        val byThreat = mapOf(
            "C" to detections.count { it.threatLevel == ThreatLevel.CRITICAL },
            "H" to detections.count { it.threatLevel == ThreatLevel.HIGH },
            "M" to detections.count { it.threatLevel == ThreatLevel.MEDIUM },
            "L" to detections.count { it.threatLevel == ThreatLevel.LOW }
        ).filter { it.value > 0 }
            .map { "${it.key}:${it.value}" }
            .joinToString(",")

        val content = """$ABBREVIATION_LEGEND

Generate surveillance summary, respond JSON:
{"headline":"1 sentence","findings":["point1","point2","point3"],"trend":"UP|DOWN|STABLE","hotspots":"locations or none","actions":["action1","action2"]}

PERIOD:$periodDescription
TOTAL:${detections.size}
${previousPeriodComparison?.let { "VS_PREV:$it" } ?: ""}
BY_TYPE:$byType
BY_THR:$byThreat(C=critical,H=high,M=medium,L=low)

JSON:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== COMPACT CELLULAR/IMSI PROMPT ====================

    /**
     * Compact IMSI catcher analysis prompt - ~350 tokens
     */
    fun compactCellularPrompt(
        detection: Detection,
        analysis: CellularAnomalyAnalysis
    ): String {
        val content = """$ABBREVIATION_LEGEND

IMSI catcher det analysis. Respond JSON:
{"is_imsi":true/false,"conf":0-100,"explain":"1-2 sentences","data_risk":["type1","type2"],"action":"what to do"}

CELL_DATA:
imsi_score=${analysis.imsiCatcherScore}%
enc_chain=${analysis.encryptionDowngradeChain.joinToString(">")}
enc_down=${analysis.encryptionDowngraded}
move=${analysis.movementType.displayName}@${String.format("%.1f", analysis.speedKmh)}kmh
impossible_speed=${analysis.impossibleSpeed}
cell_trust=${analysis.cellTrustScore}%
sig_spike=${analysis.signalSpikeDetected}
down+spike=${analysis.downgradeWithSignalSpike}
fp_pct=${String.format("%.0f", analysis.falsePositiveLikelihood)}%
normal_handoff=${analysis.isLikelyNormalHandoff}
5g_beam=${analysis.isLikely5gBeamSteering}

RULES:fp_pct>50=likely NOT imsi catcher

JSON:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== COMPACT GNSS PROMPT ====================

    /**
     * Compact GNSS spoofing/jamming prompt - ~300 tokens
     */
    fun compactGnssPrompt(
        detection: Detection,
        analysis: GnssAnomalyAnalysis
    ): String {
        val content = """$ABBREVIATION_LEGEND

GPS anomaly analysis. Respond JSON:
{"is_attack":true/false,"type":"SPOOF|JAM|NONE","conf":0-100,"trust_gps":true/false,"explain":"1-2 sentences","action":"what to do"}

GNSS_DATA:
spoof_pct=${String.format("%.0f", analysis.spoofingLikelihood)}%
jam_pct=${String.format("%.0f", analysis.jammingLikelihood)}%
cn0=${String.format("%.1f", analysis.currentCn0Mean)}dB
cn0_uniform=${analysis.cn0TooUniform}
low_elev_high_sig=${analysis.lowElevHighSignalCount}
geom_score=${String.format("%.0f", analysis.geometryScore * 100)}%
missing_const=${analysis.missingConstellations.joinToString(",") { it.code }}
fp_pct=${String.format("%.0f", analysis.falsePositiveLikelihood)}%
urban_multipath=${analysis.isLikelyUrbanMultipath}
indoor=${analysis.isLikelyIndoorSignalLoss}

RULES:fp_pct>50=likely normal GPS

JSON:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== COMPACT ULTRASONIC PROMPT ====================

    /**
     * Compact ultrasonic beacon prompt - ~280 tokens
     */
    fun compactUltrasonicPrompt(
        detection: Detection,
        analysis: BeaconAnalysis
    ): String {
        val content = """$ABBREVIATION_LEGEND

Ultrasonic beacon det. Respond JSON:
{"is_tracking":true/false,"source":"${analysis.matchedSource.displayName}","conf":0-100,"following":${analysis.followingUser},"explain":"1-2 sentences","action":"what to do"}

BEACON_DATA:
freq=${analysis.frequencyHz}Hz
type=${analysis.matchedSource.displayName}
cat=${analysis.sourceCategory.displayName}
source_conf=${String.format("%.0f", analysis.sourceConfidence)}%
snr=${String.format("%.1f", analysis.snrDb)}dB
following=${analysis.followingUser}
locs=${analysis.locationsDetected}
persist=${String.format("%.0f", analysis.persistenceScore * 100)}%
fp_pct=${String.format("%.0f", analysis.falsePositiveLikelihood)}%
ambient_noise=${analysis.isLikelyAmbientNoise}

RULES:fp_pct>50=likely noise not beacon

JSON:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== COMPACT WIFI FOLLOWING PROMPT ====================

    /**
     * Compact WiFi following network prompt - ~300 tokens
     */
    fun compactWifiFollowingPrompt(
        detection: Detection,
        analysis: FollowingNetworkAnalysis
    ): String {
        val content = """$ABBREVIATION_LEGEND

WiFi network following user? Respond JSON:
{"is_following":true/false,"conf":0-100,"dev_type":"vehicle|foot|fixed|unknown","safety_concern":true/false,"explain":"1-2 sentences","action":"what to do"}

WIFI_DATA:
ssid=${sanitize(detection.ssid, 32).ifEmpty { "hidden" }}
sightings=${analysis.sightingCount}
locations=${analysis.distinctLocations}
follow_conf=${String.format("%.0f", analysis.followingConfidence)}%
path_corr=${String.format("%.0f", analysis.pathCorrelation * 100)}%
leads_user=${analysis.leadsUser}
vehicle=${analysis.vehicleMounted}
foot_surv=${analysis.possibleFootSurveillance}
fp_pct=${String.format("%.0f", analysis.falsePositiveLikelihood)}%
neighbor=${analysis.isLikelyNeighborNetwork}
commuter=${analysis.isLikelyCommuterDevice}

RULES:fp_pct>50=likely coincidence

JSON:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== COMPACT PATTERN RECOGNITION PROMPT ====================

    /**
     * Compact pattern recognition prompt - ~400 tokens
     */
    fun compactPatternPrompt(
        detections: List<Detection>,
        timeWindowDescription: String
    ): String {
        val detectionList = detections.take(8).mapIndexed { i, d ->
            "${i + 1}:${d.deviceType.displayName},${d.threatLevel.displayName},${d.rssi}dBm"
        }.joinToString(";")

        val content = """$ABBREVIATION_LEGEND

Find patterns in dets. Respond JSON:
{"patterns":[{"type":"COORD|FOLLOW|TIME|GEO|ESCALATE|MULTI","dets":[1,2],"conf":"LOW|MED|HIGH","what":"meaning","action":"response"}],"no_patterns":true/false}

WINDOW:$timeWindowDescription
COUNT:${detections.size}
DETS:$detectionList

PATTERN_TYPES:
COORD=multiple devs working together
FOLLOW=devs appearing where user goes
TIME=devs activating same times
GEO=cluster in small area
ESCALATE=threat increasing
MULTI=different det types same area

JSON:"""

        return wrapGemmaPrompt(content)
    }

    // ==================== COMPACT USER EXPLANATION PROMPT ====================

    /**
     * Compact user-friendly explanation prompt - ~250 tokens
     */
    fun compactUserExplanationPrompt(
        detection: Detection,
        level: PromptTemplates.ExplanationLevel = PromptTemplates.ExplanationLevel.SIMPLE
    ): String {
        val levelInstr = when (level) {
            PromptTemplates.ExplanationLevel.SIMPLE -> "simple words,short sentences,analogies"
            PromptTemplates.ExplanationLevel.STANDARD -> "clear language,brief tech terms ok"
            PromptTemplates.ExplanationLevel.TECHNICAL -> "include tech details,protocols,mechanisms"
        }

        val content = """$ABBREVIATION_LEGEND

Explain det to user($levelInstr). Respond:
HEADLINE:5 words max
HAPPENING:1-2 sentences
MATTERS:why care
DO:
1.action
2.action
3.action
URGENCY:LOW|MED|HIGH|IMMEDIATE

DET:
${detection.deviceType.displayName}
${detection.protocol.displayName}
${detection.threatLevel.displayName}(${detection.threatScore}/100)
${detection.signalStrength.displayName}"""

        return wrapGemmaPrompt(content)
    }

    // ==================== HELPER FUNCTIONS ====================

    /**
     * Build compact enriched data section.
     */
    private fun buildCompactEnrichedSection(data: EnrichedDetectorData): String {
        return when (data) {
            is EnrichedDetectorData.Cellular -> {
                val a = data.analysis
                "CELL:imsi=${a.imsiCatcherScore}%,enc=${a.currentEncryption.displayName},move=${a.movementType.displayName},trust=${a.cellTrustScore}%,fp=${String.format("%.0f", a.falsePositiveLikelihood)}%"
            }
            is EnrichedDetectorData.Gnss -> {
                val a = data.analysis
                "GNSS:spoof=${String.format("%.0f", a.spoofingLikelihood)}%,jam=${String.format("%.0f", a.jammingLikelihood)}%,geom=${String.format("%.0f", a.geometryScore * 100)}%,fp=${String.format("%.0f", a.falsePositiveLikelihood)}%"
            }
            is EnrichedDetectorData.Ultrasonic -> {
                val a = data.analysis
                "SONIC:type=${a.matchedSource.displayName},track=${String.format("%.0f", a.trackingLikelihood)}%,follow=${a.followingUser},fp=${String.format("%.0f", a.falsePositiveLikelihood)}%"
            }
            is EnrichedDetectorData.WifiFollowing -> {
                val a = data.analysis
                "WIFI:follow=${String.format("%.0f", a.followingConfidence)}%,seen=${a.sightingCount}x${a.distinctLocations}loc,path=${String.format("%.0f", a.pathCorrelation * 100)}%,fp=${String.format("%.0f", a.falsePositiveLikelihood)}%"
            }
            is EnrichedDetectorData.Satellite -> {
                "SAT:type=${data.detectorType},${data.riskIndicators.take(2).joinToString(",")}"
            }
        }
    }

    /**
     * Estimate false positive likelihood for a detection.
     */
    private fun estimateFalsePositiveLikelihood(
        detection: Detection,
        enrichedData: EnrichedDetectorData?
    ): Int {
        when (enrichedData) {
            is EnrichedDetectorData.Cellular -> return enrichedData.analysis.falsePositiveLikelihood.toInt()
            is EnrichedDetectorData.Gnss -> return enrichedData.analysis.falsePositiveLikelihood.toInt()
            is EnrichedDetectorData.Ultrasonic -> return enrichedData.analysis.falsePositiveLikelihood.toInt()
            is EnrichedDetectorData.WifiFollowing -> return enrichedData.analysis.falsePositiveLikelihood.toInt()
            else -> {}
        }

        return when (detection.deviceType) {
            com.flockyou.data.model.DeviceType.RING_DOORBELL,
            com.flockyou.data.model.DeviceType.NEST_CAMERA,
            com.flockyou.data.model.DeviceType.WYZE_CAMERA,
            com.flockyou.data.model.DeviceType.ARLO_CAMERA,
            com.flockyou.data.model.DeviceType.EUFY_CAMERA,
            com.flockyou.data.model.DeviceType.BLINK_CAMERA -> 80
            com.flockyou.data.model.DeviceType.AIRTAG,
            com.flockyou.data.model.DeviceType.TILE_TRACKER,
            com.flockyou.data.model.DeviceType.SAMSUNG_SMARTTAG ->
                if (detection.rssi < -70) 60 else 20
            com.flockyou.data.model.DeviceType.BLUETOOTH_BEACON,
            com.flockyou.data.model.DeviceType.RETAIL_TRACKER -> 75
            com.flockyou.data.model.DeviceType.STINGRAY_IMSI,
            com.flockyou.data.model.DeviceType.GNSS_SPOOFER,
            com.flockyou.data.model.DeviceType.GNSS_JAMMER ->
                if (detection.threatScore > 70) 20 else 50
            else -> 50
        }
    }
}

/**
 * Mode for prompt generation.
 */
enum class PromptMode(val displayName: String, val description: String) {
    VERBOSE("Verbose", "Full detailed prompts for maximum quality (~1500+ tokens)"),
    COMPACT("Compact", "Compressed prompts for faster inference (~400 tokens)"),
    AUTO("Auto", "Automatically select based on model and settings")
}

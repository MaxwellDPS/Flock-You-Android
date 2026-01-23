package com.flockyou.ai

import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.ThreatLevel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Robust parser for LLM outputs.
 *
 * Handles multiple output formats:
 * - JSON structured responses
 * - Markdown formatted text
 * - Free-form text with heuristic extraction
 *
 * Extracts structured data like:
 * - Data types at risk
 * - Recommended actions
 * - Risk levels and confidence
 * - Headlines and summaries
 */
object LlmOutputParser {

    private const val TAG = "LlmOutputParser"

    // ==================== STRUCTURED ANALYSIS ====================

    /**
     * Parse an LLM response into a structured analysis result.
     * Tries JSON parsing first, then falls back to heuristic parsing.
     */
    fun parseParsedLlmAnalysis(
        llmResponse: String,
        detection: Detection
    ): ParsedLlmAnalysis {
        // Clean up the response first
        val cleaned = cleanLlmResponse(llmResponse)

        // Try JSON parsing first
        tryParseJson(cleaned)?.let { return it }

        // Fall back to heuristic parsing
        return parseHeuristically(cleaned, detection)
    }

    /**
     * Try to parse the response as JSON.
     */
    private fun tryParseJson(response: String): ParsedLlmAnalysis? {
        // Find JSON object in response
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')

        if (jsonStart < 0 || jsonEnd <= jsonStart) return null

        return try {
            val jsonStr = response.substring(jsonStart, jsonEnd + 1)
            val json = JSONObject(jsonStr)

            ParsedLlmAnalysis(
                headline = json.optString("headline", ""),
                devicePurpose = json.optString("device_purpose", ""),
                dataTypes = parseJsonStringArray(json.optJSONArray("data_types")),
                riskLevel = parseRiskLevel(json.optString("risk_level", "")),
                riskExplanation = json.optString("risk_explanation", ""),
                actions = parseActions(json.optJSONArray("actions")),
                confidence = json.optDouble("confidence", 0.5).toFloat(),
                rawResponse = response
            )
        } catch (e: JSONException) {
            Log.d(TAG, "JSON parsing failed: ${e.message}")
            null
        }
    }

    /**
     * Parse response heuristically when JSON is not available.
     */
    private fun parseHeuristically(
        response: String,
        detection: Detection
    ): ParsedLlmAnalysis {
        return ParsedLlmAnalysis(
            headline = extractHeadline(response, detection),
            devicePurpose = extractDevicePurpose(response, detection),
            dataTypes = extractDataTypes(response),
            riskLevel = extractRiskLevel(response, detection),
            riskExplanation = extractRiskExplanation(response),
            actions = extractActions(response),
            confidence = estimateConfidence(response, detection),
            rawResponse = response
        )
    }

    // ==================== EXTRACTION FUNCTIONS ====================

    /**
     * Extract a headline from the response.
     */
    private fun extractHeadline(response: String, detection: Detection): String {
        // Look for explicit headline markers
        val headlinePatterns = listOf(
            Regex("""HEADLINE:\s*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE),
            Regex("""##\s+(.+?)(?:\n|$)"""),
            Regex("""\*\*(.+?)\*\*""")
        )

        for (pattern in headlinePatterns) {
            pattern.find(response)?.groupValues?.getOrNull(1)?.let { headline ->
                if (headline.length in 3..60) {
                    return headline.trim()
                }
            }
        }

        // Generate from detection if not found
        return "${detection.deviceType.displayName} Detected"
    }

    /**
     * Extract device purpose description.
     */
    private fun extractDevicePurpose(response: String, detection: Detection): String {
        // Look for "What it does" or similar sections
        val purposePatterns = listOf(
            Regex("""(?:what\s+(?:it|this)\s+does|device\s+purpose|purpose)[:.\s]+(.+?)(?:\n\n|\z)""", RegexOption.IGNORE_CASE),
            Regex("""(?:this\s+is\s+a?|this\s+device\s+is)(.+?)(?:\.|$)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in purposePatterns) {
            pattern.find(response)?.groupValues?.getOrNull(1)?.let { purpose ->
                val cleaned = purpose.trim()
                if (cleaned.length > 10) {
                    return cleaned.take(300)
                }
            }
        }

        // Use detection method description as fallback
        return detection.detectionMethod.description
    }

    /**
     * Extract data types that may be collected.
     */
    fun extractDataTypes(response: String): List<String> {
        val foundTypes = mutableListOf<String>()

        // Keyword mapping for data type detection
        val dataTypeKeywords = mapOf(
            "location" to "Location tracking",
            "gps" to "GPS coordinates",
            "imsi" to "Phone IMSI/IMEI",
            "imei" to "Phone IMSI/IMEI",
            "call" to "Call metadata",
            "text" to "Text message metadata",
            "sms" to "SMS content",
            "license plate" to "License plate numbers",
            "plate" to "License plate numbers",
            "video" to "Video recordings",
            "audio" to "Audio recordings",
            "voice" to "Voice recordings",
            "conversation" to "Conversations",
            "face" to "Facial recognition data",
            "facial" to "Facial recognition data",
            "biometric" to "Biometric data",
            "bluetooth" to "Bluetooth device IDs",
            "wifi" to "WiFi network history",
            "mac address" to "Device MAC addresses",
            "browsing" to "Browsing history",
            "traffic" to "Network traffic",
            "credential" to "Login credentials",
            "password" to "Passwords",
            "movement" to "Movement patterns",
            "behavior" to "Behavioral patterns",
            "timestamp" to "Timestamps",
            "metadata" to "Metadata",
            "identity" to "Personal identity",
            "phone number" to "Phone numbers",
            "contact" to "Contact information"
        )

        val lowerResponse = response.lowercase()

        for ((keyword, dataType) in dataTypeKeywords) {
            if (keyword in lowerResponse && dataType !in foundTypes) {
                foundTypes.add(dataType)
            }
        }

        // Also look for explicit bullet lists
        val bulletPattern = Regex("""[-•*]\s*(.+?)(?:\n|$)""")
        val matches = bulletPattern.findAll(response)
        for (match in matches) {
            val item = match.groupValues[1].trim()
            // Check if this looks like a data type (short, not a sentence)
            if (item.length in 5..40 && !item.contains('.') && item.first().isUpperCase()) {
                if (item !in foundTypes) {
                    foundTypes.add(item)
                }
            }
        }

        return foundTypes.take(8) // Limit to 8 data types
    }

    /**
     * Extract risk level from response.
     */
    private fun extractRiskLevel(response: String, detection: Detection): ThreatLevel {
        val lowerResponse = response.lowercase()

        // Look for explicit risk level mentions
        return when {
            "critical" in lowerResponse || "immediate" in lowerResponse || "emergency" in lowerResponse ->
                ThreatLevel.CRITICAL
            "high risk" in lowerResponse || "significant risk" in lowerResponse || "serious" in lowerResponse ->
                ThreatLevel.HIGH
            "medium risk" in lowerResponse || "moderate risk" in lowerResponse || "some risk" in lowerResponse ->
                ThreatLevel.MEDIUM
            "low risk" in lowerResponse || "minimal risk" in lowerResponse || "unlikely" in lowerResponse ->
                ThreatLevel.LOW
            else -> detection.threatLevel // Use detection's level as fallback
        }
    }

    /**
     * Extract risk explanation.
     */
    private fun extractRiskExplanation(response: String): String {
        val riskPatterns = listOf(
            Regex("""(?:risk|privacy|danger|threat)(?:\s+(?:level|assessment))?[:.\s]+(.+?)(?:\n\n|\z)""", RegexOption.IGNORE_CASE),
            Regex("""(?:why\s+(?:it|this)\s+matters|implications?)[:.\s]+(.+?)(?:\n\n|\z)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in riskPatterns) {
            pattern.find(response)?.groupValues?.getOrNull(1)?.let { explanation ->
                val cleaned = explanation.trim()
                if (cleaned.length > 20) {
                    return cleaned.take(200)
                }
            }
        }

        return ""
    }

    /**
     * Extract recommended actions from response.
     */
    fun extractActions(response: String): List<ParsedMitigationAction> {
        val actions = mutableListOf<ParsedMitigationAction>()

        // Look for numbered actions
        val numberedPattern = Regex("""(\d+)[.)]\s*(.+?)(?=\n\d+[.)]|\n\n|\z)""", RegexOption.DOT_MATCHES_ALL)
        val numberedMatches = numberedPattern.findAll(response)

        for (match in numberedMatches) {
            val priority = match.groupValues[1].toIntOrNull() ?: (actions.size + 1)
            val action = match.groupValues[2].trim()
                .replace(Regex("""\s+"""), " ")
                .take(150)

            if (action.length > 10) {
                actions.add(ParsedMitigationAction(
                    priority = priority.coerceIn(1, 10),
                    action = action,
                    urgency = inferUrgency(action)
                ))
            }
        }

        // Also look for bullet points if we didn't find numbered items
        if (actions.isEmpty()) {
            val bulletPattern = Regex("""[-•*]\s*(.+?)(?:\n[-•*]|\n\n|\z)""", RegexOption.DOT_MATCHES_ALL)
            val bulletMatches = bulletPattern.findAll(response)

            var priority = 1
            for (match in bulletMatches) {
                val action = match.groupValues[1].trim()
                    .replace(Regex("""\s+"""), " ")
                    .take(150)

                if (action.length > 10 && isActionLike(action)) {
                    actions.add(ParsedMitigationAction(
                        priority = priority++,
                        action = action,
                        urgency = inferUrgency(action)
                    ))
                }
            }
        }

        // Sort by priority and limit
        return actions.sortedBy { it.priority }.take(5)
    }

    /**
     * Check if a string looks like an action recommendation.
     */
    private fun isActionLike(text: String): Boolean {
        val actionVerbs = listOf(
            "enable", "disable", "turn", "use", "avoid", "leave", "check",
            "consider", "be aware", "report", "contact", "move", "stay",
            "do not", "don't", "immediately", "should", "must", "need to",
            "make sure", "ensure", "verify", "switch", "change"
        )
        val lowerText = text.lowercase()
        return actionVerbs.any { lowerText.startsWith(it) || " $it " in lowerText }
    }

    /**
     * Infer urgency level from action text.
     */
    private fun inferUrgency(action: String): ActionUrgency {
        val lowerAction = action.lowercase()
        return when {
            "immediately" in lowerAction || "now" in lowerAction || "emergency" in lowerAction ->
                ActionUrgency.IMMEDIATE
            "should" in lowerAction || "important" in lowerAction || "soon" in lowerAction ->
                ActionUrgency.HIGH
            "consider" in lowerAction || "may want" in lowerAction || "optional" in lowerAction ->
                ActionUrgency.LOW
            else -> ActionUrgency.NORMAL
        }
    }

    /**
     * Estimate confidence in the analysis based on response quality.
     */
    private fun estimateConfidence(response: String, detection: Detection): Float {
        var confidence = 0.5f

        // More specific language increases confidence
        if (detection.deviceType.displayName.lowercase() in response.lowercase()) {
            confidence += 0.1f
        }

        // Presence of structured sections increases confidence
        if ("##" in response || "**" in response) {
            confidence += 0.1f
        }

        // Numbered actions increases confidence
        if (Regex("""\d+[.)]\s*""").containsMatchIn(response)) {
            confidence += 0.1f
        }

        // Long, detailed response increases confidence
        if (response.length > 500) {
            confidence += 0.1f
        }

        // Technical terms relevant to detection type
        val technicalTerms = when (detection.protocol) {
            com.flockyou.data.model.DetectionProtocol.CELLULAR -> listOf("imsi", "encryption", "2g", "tower", "handoff")
            com.flockyou.data.model.DetectionProtocol.GNSS -> listOf("satellite", "spoofing", "jamming", "c/n0", "constellation")
            com.flockyou.data.model.DetectionProtocol.WIFI -> listOf("ssid", "mac", "beacon", "probe", "deauth")
            com.flockyou.data.model.DetectionProtocol.AUDIO -> listOf("ultrasonic", "frequency", "beacon", "modulation")
            else -> emptyList()
        }

        val lowerResponse = response.lowercase()
        val termMatches = technicalTerms.count { it in lowerResponse }
        confidence += (termMatches * 0.05f)

        return confidence.coerceIn(0.1f, 0.95f)
    }

    // ==================== UTILITY FUNCTIONS ====================

    /**
     * Clean up LLM response by removing control tokens and normalizing whitespace.
     */
    private fun cleanLlmResponse(response: String): String {
        return response
            .replace("<end_of_turn>", "")
            .replace("<start_of_turn>model", "")
            .replace("<start_of_turn>user", "")
            .replace("<eos>", "")
            .replace(Regex("""<\|.*?\|>"""), "") // Remove other control tokens
            .replace(Regex("""\n{3,}"""), "\n\n") // Normalize multiple newlines
            .trim()
    }

    /**
     * Parse risk level string to ThreatLevel.
     */
    private fun parseRiskLevel(level: String): ThreatLevel {
        return when (level.uppercase()) {
            "CRITICAL", "IMMEDIATE" -> ThreatLevel.CRITICAL
            "HIGH", "SEVERE" -> ThreatLevel.HIGH
            "MEDIUM", "MODERATE" -> ThreatLevel.MEDIUM
            "LOW", "MINIMAL" -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }
    }

    /**
     * Parse JSON string array to List<String>.
     */
    private fun parseJsonStringArray(jsonArray: JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()

        val result = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            jsonArray.optString(i)?.let { result.add(it) }
        }
        return result
    }

    /**
     * Parse actions from JSON array.
     */
    private fun parseActions(jsonArray: JSONArray?): List<ParsedMitigationAction> {
        if (jsonArray == null) return emptyList()

        val actions = mutableListOf<ParsedMitigationAction>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val priority = obj.optInt("priority", i + 1)
            val action = obj.optString("action", "") ?: continue

            if (action.isNotBlank()) {
                actions.add(ParsedMitigationAction(
                    priority = priority,
                    action = action,
                    urgency = inferUrgency(action)
                ))
            }
        }
        return actions.sortedBy { it.priority }
    }

    // ==================== PATTERN ANALYSIS PARSING ====================

    /**
     * Parse pattern analysis results from LLM response.
     */
    fun parsePatternAnalysis(response: String): List<PatternInsight> {
        val patterns = mutableListOf<PatternInsight>()
        val cleaned = cleanLlmResponse(response)

        // Check for "no patterns" response
        if ("no coordinated patterns" in cleaned.lowercase() ||
            "no significant patterns" in cleaned.lowercase()) {
            return emptyList()
        }

        // Parse pattern sections
        val patternSectionRegex = Regex(
            """###\s*(.+?)\n(.+?)(?=###|\z)""",
            RegexOption.DOT_MATCHES_ALL
        )

        for (match in patternSectionRegex.findAll(cleaned)) {
            val patternName = match.groupValues[1].trim()
            val content = match.groupValues[2].trim()

            val patternType = inferPatternType(patternName)
            val detectionNums = extractDetectionNumbers(content)
            val confidence = extractPatternConfidence(content)
            val interpretation = extractInterpretation(content)
            val action = extractPatternAction(content)

            if (patternType != null) {
                patterns.add(PatternInsight(
                    patternType = patternType,
                    affectedDetections = detectionNums,
                    description = interpretation,
                    implication = action,
                    confidence = confidence
                ))
            }
        }

        return patterns
    }

    private fun inferPatternType(name: String): PatternType? {
        val lowerName = name.lowercase()
        return when {
            "coordinated" in lowerName -> PatternType.COORDINATED_SURVEILLANCE
            "following" in lowerName -> PatternType.FOLLOWING_PATTERN
            "timing" in lowerName -> PatternType.TIMING_CORRELATION
            "geographic" in lowerName || "cluster" in lowerName -> PatternType.GEOGRAPHIC_CLUSTERING
            "escalat" in lowerName -> PatternType.ESCALATION_PATTERN
            "multimodal" in lowerName || "multi-modal" in lowerName -> PatternType.MULTIMODAL_SURVEILLANCE
            else -> null
        }
    }

    private fun extractDetectionNumbers(content: String): List<String> {
        val pattern = Regex("""detections?:\s*([\d,\s]+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(content) ?: return emptyList()

        return match.groupValues[1]
            .split(Regex("""[,\s]+"""))
            .filter { it.isNotBlank() }
            .map { it.trim() }
    }

    private fun extractPatternConfidence(content: String): Float {
        val lowerContent = content.lowercase()
        return when {
            "high" in lowerContent -> 0.8f
            "medium" in lowerContent -> 0.6f
            "low" in lowerContent -> 0.4f
            else -> 0.5f
        }
    }

    private fun extractInterpretation(content: String): String {
        val pattern = Regex("""interpretation:\s*(.+?)(?:\n|action:|$)""", RegexOption.IGNORE_CASE)
        return pattern.find(content)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private fun extractPatternAction(content: String): String {
        val pattern = Regex("""action:\s*(.+?)(?:\n\n|\z)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return pattern.find(content)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    // ==================== USER EXPLANATION PARSING ====================

    /**
     * Parse user-friendly explanation from LLM response.
     */
    fun parseUserFriendlyExplanation(response: String): UserFriendlyExplanation {
        val cleaned = cleanLlmResponse(response)

        return UserFriendlyExplanation(
            headline = extractSection(cleaned, "HEADLINE") ?: "Surveillance Alert",
            whatIsHappening = extractSection(cleaned, "WHAT'S HAPPENING") ?: "",
            whyItMatters = extractSection(cleaned, "WHY IT MATTERS") ?: "",
            whatToDo = extractNumberedList(cleaned, "WHAT TO DO"),
            urgency = extractUrgencyLevel(cleaned)
        )
    }

    private fun extractSection(content: String, sectionName: String): String? {
        val pattern = Regex(
            """$sectionName:\s*(.+?)(?=\n[A-Z]+:|\z)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.find(content)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractNumberedList(content: String, sectionName: String): List<String> {
        val sectionStart = content.indexOf(sectionName, ignoreCase = true)
        if (sectionStart < 0) return emptyList()

        val sectionContent = content.substring(sectionStart)
        val nextSection = Regex("""\n[A-Z]+:""").find(sectionContent, sectionName.length)
        val sectionEnd = nextSection?.range?.first ?: sectionContent.length

        val section = sectionContent.substring(0, sectionEnd)

        val pattern = Regex("""\d+[.)]\s*(.+?)(?=\n\d+[.)]|\z)""", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(section)
            .map { it.groupValues[1].trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun extractUrgencyLevel(content: String): UrgencyLevel {
        val pattern = Regex("""URGENCY:\s*(\w+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(content)?.groupValues?.getOrNull(1)?.uppercase() ?: ""

        return when (match) {
            "IMMEDIATE" -> UrgencyLevel.IMMEDIATE
            "HIGH" -> UrgencyLevel.HIGH
            "MEDIUM" -> UrgencyLevel.MEDIUM
            "LOW" -> UrgencyLevel.LOW
            else -> UrgencyLevel.MEDIUM
        }
    }

    // ==================== BATCH ANALYSIS PARSING ====================

    /**
     * Parse batch analysis results from LLM response.
     * Maps individual detection results back to their IDs.
     *
     * @param response Raw LLM response from batch analysis prompt
     * @param detectionIds List of detection IDs that were in the batch
     * @return Map of detection ID to parsed result, plus optional batch-level insights
     */
    fun parseBatchAnalysis(
        response: String,
        detectionIds: List<String>
    ): BatchAnalysisParseResult {
        val cleaned = cleanLlmResponse(response)
        val results = mutableMapOf<String, BatchDetectionResult>()

        Log.d(TAG, "Parsing batch analysis for ${detectionIds.size} detections")

        // Parse individual results using [RESULT_N] format
        val resultPattern = Regex(
            """\[RESULT_\d+\]\s*ID:\s*(\S+)\s*THREAT:\s*(\w+)\s*CONFIDENCE:\s*(\d+)\s*FP_LIKELIHOOD:\s*(\d+)\s*SUMMARY:\s*(.+?)\s*ACTION:\s*(.+?)(?=\[RESULT_|\[BATCH_|BATCH_PATTERN|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        for (match in resultPattern.findAll(cleaned)) {
            val detectionId = match.groupValues[1].trim()
            val threatStr = match.groupValues[2].trim()
            val confidence = match.groupValues[3].toIntOrNull() ?: 50
            val fpLikelihood = match.groupValues[4].toIntOrNull() ?: 30
            val summary = match.groupValues[5].trim().replace(Regex("""\s+"""), " ")
            val action = match.groupValues[6].trim().replace(Regex("""\s+"""), " ")

            // Match detection ID (exact match or partial match)
            val matchedId = detectionIds.find { it == detectionId || it.contains(detectionId) || detectionId.contains(it) }
            if (matchedId != null) {
                results[matchedId] = BatchDetectionResult(
                    detectionId = matchedId,
                    threatLevel = parseThreatLevel(threatStr),
                    confidence = confidence,
                    falsePositiveLikelihood = fpLikelihood,
                    summary = summary,
                    recommendedAction = action,
                    isFalsePositiveLikely = fpLikelihood > 50
                )
                Log.d(TAG, "Parsed result for $matchedId: threat=$threatStr, FP=$fpLikelihood%")
            } else {
                Log.w(TAG, "Could not match detection ID: $detectionId")
            }
        }

        // Also try alternate format for simplified batch responses
        if (results.isEmpty()) {
            val sharedPattern = Regex(
                """SHARED_ANALYSIS:\s*THREAT:\s*(\w+)\s*CONFIDENCE:\s*(\d+)\s*FP_LIKELIHOOD:\s*(\d+)\s*SUMMARY:\s*(.+?)\s*APPLIES_TO_IDS:\s*(.+?)\s*ACTION:\s*(.+?)(?=EXCEPTION|$)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

            sharedPattern.find(cleaned)?.let { match ->
                val threatStr = match.groupValues[1].trim()
                val confidence = match.groupValues[2].toIntOrNull() ?: 50
                val fpLikelihood = match.groupValues[3].toIntOrNull() ?: 30
                val summary = match.groupValues[4].trim().replace(Regex("""\s+"""), " ")
                val idsStr = match.groupValues[5].trim()
                val action = match.groupValues[6].trim().replace(Regex("""\s+"""), " ")

                // Apply to all listed IDs
                val appliedIds = idsStr.split(",").map { it.trim() }
                for (idStr in appliedIds) {
                    val matchedId = detectionIds.find { it == idStr || it.contains(idStr) || idStr.contains(it) }
                    if (matchedId != null) {
                        results[matchedId] = BatchDetectionResult(
                            detectionId = matchedId,
                            threatLevel = parseThreatLevel(threatStr),
                            confidence = confidence,
                            falsePositiveLikelihood = fpLikelihood,
                            summary = summary,
                            recommendedAction = action,
                            isFalsePositiveLikely = fpLikelihood > 50
                        )
                    }
                }
                Log.d(TAG, "Parsed shared analysis for ${results.size} detections")
            }
        }

        // Also try tracker-specific format
        if (results.isEmpty()) {
            val trackerPattern = Regex(
                """\[TRACKER_RESULT_\d+\]\s*ID:\s*(\S+)\s*OWNERSHIP:\s*(\w+)\s*FOLLOWING_RISK:\s*(\w+)\s*DURATION:\s*(.+?)\s*ACTION:\s*(.+?)\s*REASON:\s*(.+?)(?=\[TRACKER_|COMBINED_|$)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

            for (match in trackerPattern.findAll(cleaned)) {
                val detectionId = match.groupValues[1].trim()
                val ownership = match.groupValues[2].trim()
                val followingRisk = match.groupValues[3].trim()
                val duration = match.groupValues[4].trim()
                val action = match.groupValues[5].trim().replace(Regex("""\s+"""), " ")
                val reason = match.groupValues[6].trim().replace(Regex("""\s+"""), " ")

                val matchedId = detectionIds.find { it == detectionId || it.contains(detectionId) || detectionId.contains(it) }
                if (matchedId != null) {
                    // Convert ownership to threat level
                    val threatLevel = when {
                        followingRisk == "HIGH" -> ThreatLevel.HIGH
                        followingRisk == "MEDIUM" -> ThreatLevel.MEDIUM
                        ownership == "LIKELY_OTHERS" -> ThreatLevel.MEDIUM
                        else -> ThreatLevel.LOW
                    }

                    val fpLikelihood = when (ownership) {
                        "LIKELY_YOURS" -> 80
                        "UNKNOWN" -> 50
                        "LIKELY_OTHERS" -> 20
                        else -> 40
                    }

                    results[matchedId] = BatchDetectionResult(
                        detectionId = matchedId,
                        threatLevel = threatLevel,
                        confidence = 70,
                        falsePositiveLikelihood = fpLikelihood,
                        summary = "Ownership: $ownership, Following Risk: $followingRisk. $reason",
                        recommendedAction = action,
                        isFalsePositiveLikely = fpLikelihood > 50
                    )
                }
            }
        }

        // Parse batch-level insights
        val batchPattern = extractSection(cleaned, "BATCH_PATTERN")
        val batchRisk = extractSection(cleaned, "BATCH_RISK")
        val batchAction = extractSection(cleaned, "BATCH_ACTION")

        val batchInsight = if (batchPattern != null || batchRisk != null) {
            BatchInsight(
                pattern = batchPattern ?: "",
                overallRisk = batchRisk ?: "",
                coordinatedAction = batchAction ?: ""
            )
        } else null

        // Fill in missing detections with defaults
        for (id in detectionIds) {
            if (id !in results) {
                Log.w(TAG, "Detection $id not found in parsed results, using default")
                results[id] = BatchDetectionResult(
                    detectionId = id,
                    threatLevel = ThreatLevel.INFO,
                    confidence = 30,
                    falsePositiveLikelihood = 50,
                    summary = "Analysis could not be parsed from batch response",
                    recommendedAction = "Review manually",
                    isFalsePositiveLikely = false
                )
            }
        }

        Log.i(TAG, "Batch parsing complete: ${results.size}/${detectionIds.size} detections parsed")

        return BatchAnalysisParseResult(
            results = results,
            batchInsight = batchInsight,
            parseSuccessRate = results.count { it.value.confidence > 30 }.toFloat() / detectionIds.size,
            rawResponse = cleaned
        )
    }

    /**
     * Parse threat level string to ThreatLevel enum.
     */
    private fun parseThreatLevel(level: String): ThreatLevel {
        return when (level.uppercase()) {
            "CRITICAL", "IMMEDIATE" -> ThreatLevel.CRITICAL
            "HIGH", "SEVERE" -> ThreatLevel.HIGH
            "MEDIUM", "MODERATE" -> ThreatLevel.MEDIUM
            "LOW", "MINIMAL" -> ThreatLevel.LOW
            "INFO", "NONE" -> ThreatLevel.INFO
            else -> ThreatLevel.INFO
        }
    }

    /**
     * Verify batch parsing quality and determine if individual fallback is needed.
     */
    fun shouldFallbackToIndividual(parseResult: BatchAnalysisParseResult): Boolean {
        // Fallback if less than 50% parsed successfully
        if (parseResult.parseSuccessRate < 0.5f) {
            Log.w(TAG, "Low batch parse success rate: ${parseResult.parseSuccessRate}")
            return true
        }

        // Fallback if too many results have default confidence
        val lowConfidenceCount = parseResult.results.count { it.value.confidence <= 30 }
        if (lowConfidenceCount > parseResult.results.size / 2) {
            Log.w(TAG, "Too many low-confidence results: $lowConfidenceCount/${parseResult.results.size}")
            return true
        }

        return false
    }
}

// ==================== DATA CLASSES ====================

/**
 * Structured analysis result from parsing LLM output.
 */
data class ParsedLlmAnalysis(
    val headline: String,
    val devicePurpose: String,
    val dataTypes: List<String>,
    val riskLevel: ThreatLevel,
    val riskExplanation: String,
    val actions: List<ParsedMitigationAction>,
    val confidence: Float,
    val rawResponse: String
)

/**
 * A recommended mitigation action.
 */
data class ParsedMitigationAction(
    val priority: Int,
    val action: String,
    val urgency: ActionUrgency = ActionUrgency.NORMAL
)

/**
 * Urgency level for an action.
 */
enum class ActionUrgency {
    IMMEDIATE,  // Do right now
    HIGH,       // Do as soon as possible
    NORMAL,     // Do when convenient
    LOW         // Consider doing
}

/**
 * A detected surveillance pattern across multiple detections.
 */
data class PatternInsight(
    val patternType: PatternType,
    val affectedDetections: List<String>,
    val description: String,
    val implication: String,
    val confidence: Float
)

/**
 * Types of surveillance patterns that can be detected.
 */
enum class PatternType(val displayName: String) {
    COORDINATED_SURVEILLANCE("Coordinated Surveillance"),
    FOLLOWING_PATTERN("Following Pattern"),
    TIMING_CORRELATION("Timing Correlation"),
    GEOGRAPHIC_CLUSTERING("Geographic Clustering"),
    ESCALATION_PATTERN("Escalation Pattern"),
    MULTIMODAL_SURVEILLANCE("Multimodal Surveillance")
}

/**
 * User-friendly explanation of a detection.
 */
data class UserFriendlyExplanation(
    val headline: String,
    val whatIsHappening: String,
    val whyItMatters: String,
    val whatToDo: List<String>,
    val urgency: UrgencyLevel,
    val analogies: List<String>? = null
)

/**
 * Urgency level for user explanations.
 */
enum class UrgencyLevel(val displayName: String) {
    IMMEDIATE("Act Now"),
    HIGH("High Priority"),
    MEDIUM("Be Aware"),
    LOW("For Your Information")
}

// ==================== BATCH ANALYSIS DATA CLASSES ====================

/**
 * Result of parsing a batch analysis LLM response.
 */
data class BatchAnalysisParseResult(
    val results: Map<String, BatchDetectionResult>,
    val batchInsight: BatchInsight?,
    val parseSuccessRate: Float, // 0.0-1.0, percentage of detections successfully parsed
    val rawResponse: String
)

/**
 * Parsed result for a single detection in a batch.
 */
data class BatchDetectionResult(
    val detectionId: String,
    val threatLevel: ThreatLevel,
    val confidence: Int, // 0-100
    val falsePositiveLikelihood: Int, // 0-100
    val summary: String,
    val recommendedAction: String,
    val isFalsePositiveLikely: Boolean
)

/**
 * Batch-level insight from analyzing multiple detections together.
 */
data class BatchInsight(
    val pattern: String, // Cross-detection pattern observed
    val overallRisk: String, // Combined risk assessment
    val coordinatedAction: String // Action that addresses all detections
)

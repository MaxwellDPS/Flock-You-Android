package com.flockyou.ai

import android.util.Log
import com.flockyou.data.SurveillanceHotspot
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.repository.DetectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates daily and weekly surveillance summaries for the user.
 *
 * Provides high-level insights about surveillance exposure trends,
 * hotspots, key events, and recommendations.
 */
@Singleton
class SurveillanceSummarizer @Inject constructor(
    private val detectionRepository: DetectionRepository,
    private val mediaPipeLlmClient: MediaPipeLlmClient
) {
    companion object {
        private const val TAG = "SurveillanceSummarizer"
        private const val DAY_MS = 86_400_000L
        private const val WEEK_MS = 7 * DAY_MS
    }

    /**
     * Generate a summary for the past day.
     */
    suspend fun generateDailySummary(): SurveillanceSummary = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val detections = detectionRepository.getAllDetectionsSnapshot()
            .filter { it.timestamp >= now - DAY_MS }

        val previousDayDetections = detectionRepository.getAllDetectionsSnapshot()
            .filter { it.timestamp in (now - 2 * DAY_MS) until (now - DAY_MS) }

        generateSummary(
            detections = detections,
            period = SummaryPeriod.DAILY,
            periodDescription = "Today's",
            previousPeriodDetections = previousDayDetections
        )
    }

    /**
     * Generate a summary for the past week.
     */
    suspend fun generateWeeklySummary(): SurveillanceSummary = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val detections = detectionRepository.getAllDetectionsSnapshot()
            .filter { it.timestamp >= now - WEEK_MS }

        val previousWeekDetections = detectionRepository.getAllDetectionsSnapshot()
            .filter { it.timestamp in (now - 2 * WEEK_MS) until (now - WEEK_MS) }

        generateSummary(
            detections = detections,
            period = SummaryPeriod.WEEKLY,
            periodDescription = "This Week's",
            previousPeriodDetections = previousWeekDetections
        )
    }

    /**
     * Generate a custom summary for a specific time range.
     */
    suspend fun generateCustomSummary(
        startTime: Long,
        endTime: Long
    ): SurveillanceSummary = withContext(Dispatchers.IO) {
        val detections = detectionRepository.getAllDetectionsSnapshot()
            .filter { it.timestamp in startTime..endTime }

        val duration = endTime - startTime
        val previousDetections = detectionRepository.getAllDetectionsSnapshot()
            .filter { it.timestamp in (startTime - duration) until startTime }

        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val periodDescription = "${dateFormat.format(Date(startTime))} - ${dateFormat.format(Date(endTime))}"

        generateSummary(
            detections = detections,
            period = SummaryPeriod.CUSTOM,
            periodDescription = periodDescription,
            previousPeriodDetections = previousDetections
        )
    }

    private suspend fun generateSummary(
        detections: List<Detection>,
        period: SummaryPeriod,
        periodDescription: String,
        previousPeriodDetections: List<Detection>
    ): SurveillanceSummary {
        if (detections.isEmpty()) {
            return SurveillanceSummary(
                period = period,
                headline = "No surveillance detected $periodDescription",
                keyFindings = emptyList(),
                trendAnalysis = TrendAnalysis(
                    overallTrend = Trend.STABLE,
                    dominantDeviceTypes = emptyList(),
                    timeOfDayPattern = null,
                    locationPattern = null
                ),
                hotspots = emptyList(),
                recommendations = listOf("Continue monitoring your environment"),
                comparisonToPrevious = if (previousPeriodDetections.isNotEmpty())
                    "Down from ${previousPeriodDetections.size} detections in the previous period"
                else null,
                totalDetections = 0,
                criticalCount = 0,
                highCount = 0
            )
        }

        // Try LLM-based summary if available
        if (mediaPipeLlmClient.isReady()) {
            val comparisonNote = if (previousPeriodDetections.isNotEmpty()) {
                val percentChange = ((detections.size - previousPeriodDetections.size).toFloat() /
                    previousPeriodDetections.size * 100).toInt()
                when {
                    percentChange > 20 -> "Up ${percentChange}% from previous period"
                    percentChange < -20 -> "Down ${-percentChange}% from previous period"
                    else -> "Similar to previous period"
                }
            } else null

            val prompt = PromptTemplates.buildSummaryPrompt(detections, periodDescription, comparisonNote)
            val response = mediaPipeLlmClient.generateResponse(prompt)

            if (response != null) {
                val llmSummary = parseLlmSummary(response, detections, period, previousPeriodDetections)
                if (llmSummary != null) {
                    return llmSummary
                }
            }
        }

        // Fall back to rule-based summary
        return generateRuleBasedSummary(detections, period, periodDescription, previousPeriodDetections)
    }

    private fun generateRuleBasedSummary(
        detections: List<Detection>,
        period: SummaryPeriod,
        periodDescription: String,
        previousPeriodDetections: List<Detection>
    ): SurveillanceSummary {
        val criticalCount = detections.count { it.threatLevel == ThreatLevel.CRITICAL }
        val highCount = detections.count { it.threatLevel == ThreatLevel.HIGH }

        // Generate headline
        val headline = when {
            criticalCount > 0 -> "⚠️ ${criticalCount} critical surveillance events detected"
            highCount > 3 -> "Elevated surveillance activity with ${highCount} high-threat detections"
            detections.size > 10 -> "Active surveillance area: ${detections.size} devices detected"
            else -> "${detections.size} surveillance devices detected $periodDescription"
        }

        // Key findings
        val keyFindings = buildKeyFindings(detections)

        // Trend analysis
        val trendAnalysis = analyzeTrends(detections, previousPeriodDetections)

        // Hotspots
        val hotspots = identifyHotspots(detections)

        // Recommendations
        val recommendations = generateRecommendations(detections, trendAnalysis, hotspots)

        // Comparison to previous period
        val comparison = if (previousPeriodDetections.isNotEmpty()) {
            val change = detections.size - previousPeriodDetections.size
            when {
                change > 0 -> "Up from ${previousPeriodDetections.size} detections (+$change)"
                change < 0 -> "Down from ${previousPeriodDetections.size} detections ($change)"
                else -> "Same as previous period (${previousPeriodDetections.size})"
            }
        } else null

        return SurveillanceSummary(
            period = period,
            headline = headline,
            keyFindings = keyFindings,
            trendAnalysis = trendAnalysis,
            hotspots = hotspots,
            recommendations = recommendations,
            comparisonToPrevious = comparison,
            totalDetections = detections.size,
            criticalCount = criticalCount,
            highCount = highCount
        )
    }

    private fun buildKeyFindings(detections: List<Detection>): List<KeyFinding> {
        val findings = mutableListOf<KeyFinding>()

        // Most common device type
        val byType = detections.groupBy { it.deviceType }
            .maxByOrNull { it.value.size }
        byType?.let { (type, list) ->
            findings.add(KeyFinding(
                title = "Most Common: ${type.displayName}",
                detail = "Detected ${list.size} times",
                severity = if (list.any { it.threatLevel == ThreatLevel.HIGH || it.threatLevel == ThreatLevel.CRITICAL })
                    FindingSeverity.HIGH else FindingSeverity.MEDIUM
            ))
        }

        // Critical events
        val critical = detections.filter { it.threatLevel == ThreatLevel.CRITICAL }
        if (critical.isNotEmpty()) {
            findings.add(KeyFinding(
                title = "Critical Events",
                detail = "${critical.size} critical-level detections requiring attention",
                severity = FindingSeverity.CRITICAL
            ))
        }

        // Repeat devices
        val repeatDevices = detections.groupBy { it.macAddress ?: it.ssid }
            .filter { it.key != null && it.value.size > 2 }
        if (repeatDevices.isNotEmpty()) {
            findings.add(KeyFinding(
                title = "Repeat Devices",
                detail = "${repeatDevices.size} devices seen multiple times",
                severity = FindingSeverity.MEDIUM
            ))
        }

        // Time patterns
        val hourCounts = detections.groupBy {
            Calendar.getInstance().apply { timeInMillis = it.timestamp }
                .get(Calendar.HOUR_OF_DAY)
        }
        val peakHour = hourCounts.maxByOrNull { it.value.size }
        peakHour?.let { (hour, list) ->
            val timeOfDay = when (hour) {
                in 0..5 -> "late night"
                in 6..11 -> "morning"
                in 12..17 -> "afternoon"
                else -> "evening"
            }
            findings.add(KeyFinding(
                title = "Peak Activity: $timeOfDay",
                detail = "${list.size} detections around ${hour}:00",
                severity = FindingSeverity.LOW
            ))
        }

        return findings.take(5)
    }

    private fun analyzeTrends(
        current: List<Detection>,
        previous: List<Detection>
    ): TrendAnalysis {
        // Overall trend
        val trend = when {
            previous.isEmpty() -> Trend.STABLE
            current.size > previous.size * 1.2 -> Trend.INCREASING
            current.size < previous.size * 0.8 -> Trend.DECREASING
            else -> Trend.STABLE
        }

        // Dominant device types
        val dominantTypes = current.groupBy { it.deviceType }
            .toList()
            .sortedByDescending { it.second.size }
            .take(3)
            .map { it.first }

        // Time of day pattern
        val hourCounts = current.groupBy {
            Calendar.getInstance().apply { timeInMillis = it.timestamp }
                .get(Calendar.HOUR_OF_DAY)
        }
        val timePattern = hourCounts.maxByOrNull { it.value.size }?.let { (hour, _) ->
            when (hour) {
                in 0..5 -> "Most activity late night (12am-6am)"
                in 6..11 -> "Most activity in morning (6am-12pm)"
                in 12..17 -> "Most activity in afternoon (12pm-6pm)"
                else -> "Most activity in evening (6pm-12am)"
            }
        }

        // Location pattern (if we have GPS data)
        val locatedDetections = current.filter { it.latitude != null && it.longitude != null }
        val locationPattern = if (locatedDetections.size >= 3) {
            "Detections spread across ${estimateUniqueLocations(locatedDetections)} distinct areas"
        } else null

        return TrendAnalysis(
            overallTrend = trend,
            dominantDeviceTypes = dominantTypes,
            timeOfDayPattern = timePattern,
            locationPattern = locationPattern
        )
    }

    private fun estimateUniqueLocations(detections: List<Detection>): Int {
        if (detections.isEmpty()) return 0

        // Simple clustering: count locations more than 100m apart
        val locations = detections.mapNotNull {
            if (it.latitude != null && it.longitude != null) it.latitude to it.longitude
            else null
        }

        var uniqueCount = 1
        val used = mutableSetOf(0)

        for (i in 1 until locations.size) {
            val (lat, lon) = locations[i]
            val isUnique = used.none { j ->
                val (otherLat, otherLon) = locations[j]
                haversineDistance(lat, lon, otherLat, otherLon) < 100.0
            }
            if (isUnique) {
                uniqueCount++
                used.add(i)
            }
        }

        return uniqueCount
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

    private fun identifyHotspots(detections: List<Detection>): List<SurveillanceHotspot> {
        val locatedDetections = detections.filter { it.latitude != null && it.longitude != null }
        if (locatedDetections.size < 3) return emptyList()

        val hotspots = mutableListOf<SurveillanceHotspot>()
        val used = mutableSetOf<String>()

        for (detection in locatedDetections) {
            if (detection.id in used) continue

            val nearby = locatedDetections.filter { other ->
                other.id !in used &&
                haversineDistance(detection.latitude!!, detection.longitude!!,
                    other.latitude!!, other.longitude!!) < 100.0
            }

            if (nearby.size >= 3) {
                // Calculate centroid
                val avgLat = nearby.map { it.latitude!! }.average()
                val avgLon = nearby.map { it.longitude!! }.average()

                // Get dominant type
                val dominantType = nearby.groupBy { it.deviceType }
                    .maxByOrNull { it.value.size }?.key ?: nearby.first().deviceType

                // Get max threat
                val maxThreat = nearby.maxOfOrNull { it.threatScore } ?: 0

                hotspots.add(SurveillanceHotspot(
                    latitude = avgLat,
                    longitude = avgLon,
                    radiusMeters = 100,
                    deviceCount = nearby.size,
                    threatLevel = when {
                        maxThreat >= 90 -> "CRITICAL"
                        maxThreat >= 70 -> "HIGH"
                        maxThreat >= 50 -> "MEDIUM"
                        else -> "LOW"
                    },
                    dominantDeviceType = dominantType.displayName
                ))

                used.addAll(nearby.map { it.id })
            }
        }

        return hotspots.sortedByDescending { it.deviceCount }.take(5)
    }

    private fun generateRecommendations(
        detections: List<Detection>,
        trendAnalysis: TrendAnalysis,
        hotspots: List<SurveillanceHotspot>
    ): List<String> {
        val recommendations = mutableListOf<String>()

        // Based on trend
        when (trendAnalysis.overallTrend) {
            Trend.INCREASING -> recommendations.add(
                "Surveillance activity is increasing. Consider varying your routes and timing."
            )
            Trend.STABLE -> recommendations.add(
                "Monitor for any changes in surveillance patterns."
            )
            Trend.DECREASING -> recommendations.add(
                "Surveillance activity has decreased. Remain vigilant."
            )
        }

        // Based on critical detections
        val criticalDetections = detections.filter { it.threatLevel == ThreatLevel.CRITICAL }
        if (criticalDetections.isNotEmpty()) {
            recommendations.add(
                "Review the ${criticalDetections.size} critical detections and take appropriate precautions."
            )
        }

        // Based on hotspots
        if (hotspots.isNotEmpty()) {
            recommendations.add(
                "Be aware of ${hotspots.size} surveillance hotspot${if (hotspots.size > 1) "s" else ""} in your area."
            )
        }

        // Based on dominant device types
        if (trendAnalysis.dominantDeviceTypes.any {
                it == DeviceType.STINGRAY_IMSI || it == DeviceType.GNSS_SPOOFER
            }) {
            recommendations.add(
                "High-capability surveillance devices detected. Use encrypted communications."
            )
        }

        if (trendAnalysis.dominantDeviceTypes.any {
                it == DeviceType.FLOCK_SAFETY_CAMERA || it == DeviceType.LICENSE_PLATE_READER
            }) {
            recommendations.add(
                "ALPR cameras detected. Your vehicle movements may be logged."
            )
        }

        return recommendations.take(4)
    }

    private fun parseLlmSummary(
        response: String,
        detections: List<Detection>,
        period: SummaryPeriod,
        previousPeriodDetections: List<Detection>
    ): SurveillanceSummary? {
        // Try to extract key parts from LLM response
        val cleaned = response
            .replace("<end_of_turn>", "")
            .replace("<start_of_turn>model", "")
            .trim()

        // Extract headline
        val headlineMatch = Regex("""(?:Headline|\*\*Headline\*\*|##\s*\w+\s*Summary):\s*(.+?)(?:\n|$)""", RegexOption.IGNORE_CASE)
            .find(cleaned)
        val headline = headlineMatch?.groupValues?.getOrNull(1)?.trim()
            ?: cleaned.lines().firstOrNull { it.isNotBlank() }?.take(80)
            ?: "Surveillance Summary"

        // Extract key findings
        val findingsMatch = Regex("""Key Findings:(.+?)(?=\n\*\*|\n##|\z)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(cleaned)
        val findingsText = findingsMatch?.groupValues?.getOrNull(1) ?: ""
        val findings = Regex("""[-•*]\s*(.+?)(?=\n[-•*]|\z)""")
            .findAll(findingsText)
            .map { KeyFinding(
                title = it.groupValues[1].take(50).trim(),
                detail = "",
                severity = FindingSeverity.MEDIUM
            ) }
            .toList()
            .take(5)

        // If we couldn't extract useful info, return null to fall back to rule-based
        if (headline.length < 10 && findings.isEmpty()) {
            return null
        }

        val criticalCount = detections.count { it.threatLevel == ThreatLevel.CRITICAL }
        val highCount = detections.count { it.threatLevel == ThreatLevel.HIGH }

        // Build from LLM + rule-based fill-ins
        return SurveillanceSummary(
            period = period,
            headline = headline,
            keyFindings = findings.ifEmpty { buildKeyFindings(detections) },
            trendAnalysis = analyzeTrends(detections, previousPeriodDetections),
            hotspots = identifyHotspots(detections),
            recommendations = generateRecommendations(detections,
                analyzeTrends(detections, previousPeriodDetections),
                identifyHotspots(detections)),
            comparisonToPrevious = if (previousPeriodDetections.isNotEmpty()) {
                val change = detections.size - previousPeriodDetections.size
                when {
                    change > 0 -> "Up from ${previousPeriodDetections.size} (+$change)"
                    change < 0 -> "Down from ${previousPeriodDetections.size} ($change)"
                    else -> "Same as previous period"
                }
            } else null,
            totalDetections = detections.size,
            criticalCount = criticalCount,
            highCount = highCount
        )
    }
}

// ==================== DATA CLASSES ====================

enum class SummaryPeriod { DAILY, WEEKLY, CUSTOM }

data class SurveillanceSummary(
    val period: SummaryPeriod,
    val headline: String,
    val keyFindings: List<KeyFinding>,
    val trendAnalysis: TrendAnalysis,
    val hotspots: List<SurveillanceHotspot>,
    val recommendations: List<String>,
    val comparisonToPrevious: String?,
    val totalDetections: Int,
    val criticalCount: Int,
    val highCount: Int
)

data class KeyFinding(
    val title: String,
    val detail: String,
    val severity: FindingSeverity
)

enum class FindingSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class TrendAnalysis(
    val overallTrend: Trend,
    val dominantDeviceTypes: List<DeviceType>,
    val timeOfDayPattern: String?,
    val locationPattern: String?
)

enum class Trend(val displayName: String) {
    INCREASING("Increasing"),
    STABLE("Stable"),
    DECREASING("Decreasing")
}

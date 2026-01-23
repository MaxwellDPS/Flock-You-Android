package com.flockyou.ai.correlation

import com.flockyou.data.model.Detection
import com.flockyou.data.model.ThreatLevel

/**
 * Cross-Domain Correlation Analysis Models
 *
 * These data classes represent coordinated multi-vector surveillance patterns
 * detected by correlating signals across BLE, WiFi, Cellular, and GNSS domains.
 *
 * Key correlation patterns detected:
 * - IMSI Catcher + GNSS Jamming (common StingRay deployment pattern)
 * - Multiple trackers from same entity (AirTag networks)
 * - Following patterns (same device at multiple locations)
 * - Coordinated surveillance (multiple device types working together)
 */

/**
 * Represents a correlated threat detected across multiple detection domains.
 *
 * @param id Unique identifier for this correlated threat
 * @param detections List of individual detections that correlate
 * @param correlationType The type of correlation pattern detected
 * @param correlationScore Confidence score for the correlation (0.0 - 1.0)
 * @param timeWindow Time window in milliseconds over which detections occurred
 * @param spatialProximity Distance in meters between detections (null if no location data)
 * @param analysis LLM-generated analysis of the correlated threat
 * @param combinedThreatLevel Aggregated threat level based on all correlated detections
 * @param recommendations List of actionable recommendations for the user
 * @param timestamp When this correlation was detected
 * @param metadata Additional context-specific data
 */
data class CorrelatedThreat(
    val id: String,
    val detections: List<Detection>,
    val correlationType: CorrelationType,
    val correlationScore: Float, // 0.0 - 1.0
    val timeWindow: Long, // milliseconds
    val spatialProximity: Float?, // meters
    val analysis: String,
    val combinedThreatLevel: ThreatLevel,
    val recommendations: List<String>,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: CorrelationMetadata? = null
) {
    /**
     * Returns the primary threat level indicator based on correlation type
     */
    val primaryThreatIndicator: String
        get() = when (correlationType) {
            CorrelationType.IMSI_CATCHER_COMBO -> "Active surveillance deployment detected"
            CorrelationType.FOLLOWING_PATTERN -> "Device following you across locations"
            CorrelationType.COORDINATED_SURVEILLANCE -> "Multiple surveillance vectors active"
            CorrelationType.TRACKER_NETWORK -> "Network of tracking devices detected"
            CorrelationType.TIMING_CORRELATION -> "Synchronized surveillance activity"
            CorrelationType.SPATIAL_CLUSTERING -> "Concentrated surveillance zone"
        }

    /**
     * Get domain types involved in this correlation
     */
    val involvedDomains: Set<String>
        get() = detections.map { it.protocol.displayName }.toSet()

    /**
     * Check if this correlation involves cellular threats
     */
    val involvesCellular: Boolean
        get() = detections.any { it.protocol.name == "CELLULAR" }

    /**
     * Check if this correlation involves GNSS threats
     */
    val involvesGnss: Boolean
        get() = detections.any { it.protocol.name == "GNSS" }
}

/**
 * Types of correlation patterns that can be detected across domains.
 */
enum class CorrelationType(
    val displayName: String,
    val description: String,
    val severityMultiplier: Float
) {
    /**
     * Same device seen at 3+ distinct locations where the user has been.
     * Strong indicator of a personal tracker or stalking.
     */
    FOLLOWING_PATTERN(
        displayName = "Following Pattern",
        description = "Same device detected at multiple locations you have visited. " +
                "This is a strong indicator that a tracker is following you.",
        severityMultiplier = 1.5f
    ),

    /**
     * Multiple device types working together within spatial and temporal proximity.
     * Could indicate organized surveillance operation.
     */
    COORDINATED_SURVEILLANCE(
        displayName = "Coordinated Surveillance",
        description = "Multiple different surveillance technologies detected working together. " +
                "This pattern suggests an organized surveillance operation.",
        severityMultiplier = 2.0f
    ),

    /**
     * Cell anomaly combined with GNSS spoofing/jamming within a short time window.
     * Classic IMSI catcher (StingRay) deployment pattern - they often use GPS jammers
     * to prevent location logging.
     */
    IMSI_CATCHER_COMBO(
        displayName = "IMSI Catcher Combo",
        description = "Cell site simulator detected alongside GNSS interference. " +
                "This is a classic pattern for StingRay/IMSI catcher deployment, " +
                "where GPS is jammed to prevent location logging.",
        severityMultiplier = 2.5f
    ),

    /**
     * Multiple trackers (AirTags, SmartTags, Tiles) showing similar behavior patterns.
     * Could indicate a network of trackers from the same entity.
     */
    TRACKER_NETWORK(
        displayName = "Tracker Network",
        description = "Multiple Bluetooth trackers detected with similar behavior patterns. " +
                "This could indicate multiple trackers planted by the same person.",
        severityMultiplier = 1.8f
    ),

    /**
     * Detections synchronized in time across different domains.
     * May indicate coordinated activation or surveillance timing.
     */
    TIMING_CORRELATION(
        displayName = "Timing Correlation",
        description = "Multiple surveillance devices activated at the same time. " +
                "This synchronization suggests coordinated surveillance.",
        severityMultiplier = 1.4f
    ),

    /**
     * Multiple threats concentrated in a small geographic area.
     * Indicates a surveillance-heavy zone.
     */
    SPATIAL_CLUSTERING(
        displayName = "Spatial Clustering",
        description = "High concentration of surveillance devices in a small area. " +
                "This location appears to be a surveillance hotspot.",
        severityMultiplier = 1.3f
    )
}

/**
 * Additional metadata for correlation analysis
 */
data class CorrelationMetadata(
    // For FOLLOWING_PATTERN
    val locationCount: Int? = null,
    val followingDurationMs: Long? = null,
    val trackerIdentifier: String? = null, // MAC, UUID, etc.

    // For IMSI_CATCHER_COMBO
    val encryptionDowngradeDetected: Boolean? = null,
    val gnssJammingScore: Float? = null,
    val gnssSpoofingScore: Float? = null,

    // For TRACKER_NETWORK
    val trackerCount: Int? = null,
    val trackerTypes: List<String>? = null,
    val sharedManufacturer: String? = null,

    // For COORDINATED_SURVEILLANCE
    val domainCount: Int? = null,
    val signalStrengthProfile: Map<String, Int>? = null,

    // For SPATIAL_CLUSTERING
    val clusterCenterLat: Double? = null,
    val clusterCenterLon: Double? = null,
    val clusterRadiusMeters: Float? = null,
    val devicesInCluster: Int? = null,

    // General
    val confidenceFactors: List<String>? = null,
    val falsePositiveIndicators: List<String>? = null,
    val rawCorrelationData: Map<String, Any>? = null
)

/**
 * Result of running cross-domain correlation analysis
 */
data class CorrelationAnalysisResult(
    val correlatedThreats: List<CorrelatedThreat>,
    val analysisTimestamp: Long = System.currentTimeMillis(),
    val totalDetectionsAnalyzed: Int,
    val timeWindowMs: Long,
    val highestThreatLevel: ThreatLevel?,
    val summary: String,
    val llmEnhanced: Boolean = false
) {
    /**
     * Get the most critical correlation
     */
    val mostCriticalCorrelation: CorrelatedThreat?
        get() = correlatedThreats.maxByOrNull {
            it.combinedThreatLevel.ordinal * it.correlationScore
        }

    /**
     * Check if any critical correlations were found
     */
    val hasCriticalCorrelations: Boolean
        get() = correlatedThreats.any {
            it.combinedThreatLevel == ThreatLevel.CRITICAL ||
                    it.correlationType == CorrelationType.IMSI_CATCHER_COMBO
        }

    /**
     * Get count of correlations by type
     */
    val correlationsByType: Map<CorrelationType, Int>
        get() = correlatedThreats.groupingBy { it.correlationType }.eachCount()
}

/**
 * Configuration for correlation analysis
 */
data class CorrelationConfig(
    // Time windows for different correlation types
    val imsiCatcherComboWindowMs: Long = 5 * 60 * 1000L, // 5 minutes
    val coordinatedSurveillanceWindowMs: Long = 10 * 60 * 1000L, // 10 minutes
    val timingCorrelationWindowMs: Long = 60 * 1000L, // 1 minute

    // Spatial thresholds
    val coordinatedSurveillanceRadiusMeters: Float = 100f,
    val spatialClusteringRadiusMeters: Float = 200f,

    // Detection thresholds
    val followingPatternMinLocations: Int = 3,
    val trackerNetworkMinTrackers: Int = 2,
    val spatialClusteringMinDevices: Int = 3,

    // Correlation score thresholds
    val minCorrelationScore: Float = 0.4f,
    val highConfidenceThreshold: Float = 0.7f,

    // LLM analysis
    val enableLlmEnhancement: Boolean = true,
    val llmAnalysisTimeoutMs: Long = 30_000L
)

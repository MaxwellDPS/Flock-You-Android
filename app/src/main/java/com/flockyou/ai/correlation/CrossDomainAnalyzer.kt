package com.flockyou.ai.correlation

import android.util.Log
import com.flockyou.ai.LlmEngineManager
import com.flockyou.ai.PromptTemplates
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cross-Domain Analyzer for detecting coordinated multi-vector surveillance.
 *
 * This analyzer correlates signals across BLE, WiFi, Cellular, and GNSS domains
 * to identify sophisticated surveillance operations that use multiple technologies.
 *
 * Key correlation patterns:
 * 1. IMSI Catcher Combo: Cell anomaly + GNSS spoofing/jamming within 5 minutes
 * 2. Following Pattern: Same BLE device at 3+ locations
 * 3. Coordinated Surveillance: WiFi + BLE + Cell anomalies within 100m and 10 minutes
 * 4. Tracker Network: Multiple AirTags/SmartTags with similar behavior
 *
 * The analyzer uses LLM-enhanced analysis when available to provide detailed
 * explanations and recommendations.
 */
@Singleton
class CrossDomainAnalyzer @Inject constructor(
    private val llmEngineManager: LlmEngineManager
) {
    companion object {
        private const val TAG = "CrossDomainAnalyzer"

        // Detection storage limits
        private const val MAX_RECENT_DETECTIONS = 500
        private const val CLEANUP_AGE_MS = 4 * 60 * 60 * 1000L // 4 hours

        // Haversine constants
        private const val EARTH_RADIUS_METERS = 6371000.0
    }

    // Configuration
    private var config = CorrelationConfig()

    // Recent detections storage by domain
    private val recentDetections = ConcurrentHashMap<String, MutableList<Detection>>()

    // Correlation results
    private val _correlatedThreats = MutableStateFlow<List<CorrelatedThreat>>(emptyList())
    val correlatedThreats: StateFlow<List<CorrelatedThreat>> = _correlatedThreats.asStateFlow()

    // Analysis status
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    // Tracker following history: MAC/UUID -> List of (lat, lon, timestamp)
    private val trackerLocationHistory = ConcurrentHashMap<String, MutableList<LocationSighting>>()

    private data class LocationSighting(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long
    )

    init {
        // Initialize detection storage by protocol
        DetectionProtocol.entries.forEach { protocol ->
            recentDetections[protocol.name] = mutableListOf()
        }
    }

    /**
     * Update configuration settings
     */
    fun updateConfig(newConfig: CorrelationConfig) {
        config = newConfig
    }

    /**
     * Register a new detection for correlation analysis.
     * This should be called for each new detection from any scanner.
     */
    fun registerDetection(detection: Detection) {
        val protocolKey = detection.protocol.name
        val list = recentDetections.getOrPut(protocolKey) { mutableListOf() }

        synchronized(list) {
            list.add(detection)

            // Enforce size limit
            if (list.size > MAX_RECENT_DETECTIONS) {
                list.removeAt(0)
            }
        }

        // Track location for tracker following analysis
        if (isTracker(detection) && detection.latitude != null && detection.longitude != null) {
            val trackerId = detection.macAddress ?: detection.deviceName ?: return
            val history = trackerLocationHistory.getOrPut(trackerId) { mutableListOf() }
            synchronized(history) {
                history.add(
                    LocationSighting(
                        detection.latitude,
                        detection.longitude,
                        detection.timestamp
                    )
                )
                // Keep only recent history
                val cutoff = System.currentTimeMillis() - CLEANUP_AGE_MS
                history.removeAll { it.timestamp < cutoff }
            }
        }

        Log.d(TAG, "Registered detection: ${detection.deviceType.displayName} (${detection.protocol.name})")
    }

    /**
     * Analyze recent detections for cross-domain correlations.
     *
     * @param recentDetections List of recent detections to analyze
     * @param timeWindowMs Time window for correlation analysis (default 10 minutes)
     * @return CorrelationAnalysisResult containing all detected correlations
     */
    suspend fun analyzeCorrelations(
        recentDetections: List<Detection>,
        timeWindowMs: Long = 600_000 // 10 minutes
    ): CorrelationAnalysisResult = withContext(Dispatchers.Default) {
        _isAnalyzing.value = true
        Log.i(TAG, "Starting correlation analysis on ${recentDetections.size} detections")

        try {
            val now = System.currentTimeMillis()
            val correlations = mutableListOf<CorrelatedThreat>()

            // Filter to time window
            val windowedDetections = recentDetections.filter {
                now - it.timestamp <= timeWindowMs
            }

            if (windowedDetections.isEmpty()) {
                return@withContext CorrelationAnalysisResult(
                    correlatedThreats = emptyList(),
                    totalDetectionsAnalyzed = 0,
                    timeWindowMs = timeWindowMs,
                    highestThreatLevel = null,
                    summary = "No recent detections to analyze"
                )
            }

            // Group detections by protocol
            val byProtocol = windowedDetections.groupBy { it.protocol }

            // 1. Check for IMSI Catcher Combo
            val imsiCatcherCombos = detectImsiCatcherCombo(
                byProtocol[DetectionProtocol.CELLULAR] ?: emptyList(),
                byProtocol[DetectionProtocol.GNSS] ?: emptyList()
            )
            correlations.addAll(imsiCatcherCombos)

            // 2. Check for Following Patterns
            val followingPatterns = detectFollowingPattern(
                byProtocol[DetectionProtocol.BLUETOOTH_LE] ?: emptyList()
            )
            correlations.addAll(followingPatterns)

            // 3. Check for Coordinated Surveillance
            val coordinatedSurveillance = detectCoordinatedSurveillance(windowedDetections)
            correlations.addAll(coordinatedSurveillance)

            // 4. Check for Tracker Networks
            val trackerNetworks = detectTrackerNetwork(
                byProtocol[DetectionProtocol.BLUETOOTH_LE] ?: emptyList()
            )
            correlations.addAll(trackerNetworks)

            // 5. Check for Timing Correlations
            val timingCorrelations = detectTimingCorrelation(windowedDetections)
            correlations.addAll(timingCorrelations)

            // 6. Check for Spatial Clustering
            val spatialClusters = detectSpatialClustering(windowedDetections)
            correlations.addAll(spatialClusters)

            // Filter by minimum correlation score
            val significantCorrelations = correlations.filter {
                it.correlationScore >= config.minCorrelationScore
            }

            // Enhance with LLM if enabled
            val enhancedCorrelations = if (config.enableLlmEnhancement) {
                significantCorrelations.map { enhanceWithLlm(it) }
            } else {
                significantCorrelations
            }

            // Update state
            _correlatedThreats.value = enhancedCorrelations

            // Generate summary
            val summary = generateSummary(enhancedCorrelations, windowedDetections.size)

            CorrelationAnalysisResult(
                correlatedThreats = enhancedCorrelations,
                totalDetectionsAnalyzed = windowedDetections.size,
                timeWindowMs = timeWindowMs,
                highestThreatLevel = enhancedCorrelations.maxOfOrNull { it.combinedThreatLevel },
                summary = summary,
                llmEnhanced = config.enableLlmEnhancement && enhancedCorrelations.any {
                    it.analysis.length > 100
                }
            )
        } finally {
            _isAnalyzing.value = false
        }
    }

    /**
     * Detect IMSI Catcher + GNSS jamming/spoofing combo.
     *
     * This is a classic pattern where cell site simulators (StingRay) are deployed
     * with GPS jammers to prevent location logging.
     */
    private fun detectImsiCatcherCombo(
        cellularDetections: List<Detection>,
        gnssDetections: List<Detection>
    ): List<CorrelatedThreat> {
        val correlations = mutableListOf<CorrelatedThreat>()

        // Look for cellular anomalies that suggest IMSI catcher
        val imsiSuspects = cellularDetections.filter {
            it.deviceType == DeviceType.STINGRAY_IMSI ||
                    it.detectionMethod == DetectionMethod.CELL_ENCRYPTION_DOWNGRADE ||
                    it.threatLevel >= ThreatLevel.HIGH
        }

        // Look for GNSS jamming/spoofing
        val gnssAnomalies = gnssDetections.filter {
            it.deviceType == DeviceType.GNSS_JAMMER ||
                    it.deviceType == DeviceType.GNSS_SPOOFER ||
                    it.detectionMethod == DetectionMethod.GNSS_JAMMING ||
                    it.detectionMethod == DetectionMethod.GNSS_SPOOFING
        }

        if (imsiSuspects.isEmpty() || gnssAnomalies.isEmpty()) {
            return emptyList()
        }

        // Check temporal proximity
        for (cellular in imsiSuspects) {
            for (gnss in gnssAnomalies) {
                val timeDiff = abs(cellular.timestamp - gnss.timestamp)

                if (timeDiff <= config.imsiCatcherComboWindowMs) {
                    // Calculate correlation score
                    val temporalScore = 1.0f - (timeDiff.toFloat() / config.imsiCatcherComboWindowMs)
                    val spatialScore = calculateSpatialScore(cellular, gnss)
                    val threatScore = (cellular.threatLevel.ordinal + gnss.threatLevel.ordinal) / 8f

                    val correlationScore = (temporalScore * 0.4f + spatialScore * 0.3f + threatScore * 0.3f)
                        .coerceIn(0f, 1f)

                    if (correlationScore >= config.minCorrelationScore) {
                        val metadata = CorrelationMetadata(
                            encryptionDowngradeDetected = cellular.detectionMethod == DetectionMethod.CELL_ENCRYPTION_DOWNGRADE,
                            gnssJammingScore = if (gnss.deviceType == DeviceType.GNSS_JAMMER)
                                gnss.threatScore / 100f else null,
                            gnssSpoofingScore = if (gnss.deviceType == DeviceType.GNSS_SPOOFER)
                                gnss.threatScore / 100f else null,
                            confidenceFactors = buildList {
                                add("Cell anomaly within ${timeDiff / 1000}s of GNSS anomaly")
                                if (cellular.detectionMethod == DetectionMethod.CELL_ENCRYPTION_DOWNGRADE) {
                                    add("Encryption downgrade detected (classic IMSI catcher signature)")
                                }
                                if (gnss.deviceType == DeviceType.GNSS_JAMMER) {
                                    add("GPS jamming detected (common StingRay deployment pattern)")
                                }
                            }
                        )

                        correlations.add(
                            CorrelatedThreat(
                                id = "imsi-combo-${UUID.randomUUID()}",
                                detections = listOf(cellular, gnss),
                                correlationType = CorrelationType.IMSI_CATCHER_COMBO,
                                correlationScore = correlationScore,
                                timeWindow = timeDiff,
                                spatialProximity = calculateDistance(cellular, gnss),
                                analysis = buildImsiCatcherAnalysis(cellular, gnss, metadata),
                                combinedThreatLevel = ThreatLevel.CRITICAL,
                                recommendations = listOf(
                                    "IMMEDIATELY enable airplane mode to prevent further tracking",
                                    "Leave the area if safety permits",
                                    "Use only end-to-end encrypted communications (Signal, WhatsApp)",
                                    "Do not make phone calls or send SMS in this area",
                                    "Document the time and location for potential FOIA requests"
                                ),
                                metadata = metadata
                            )
                        )
                    }
                }
            }
        }

        return correlations
    }

    /**
     * Detect Following Pattern - same device seen at multiple locations.
     */
    private fun detectFollowingPattern(
        bleDetections: List<Detection>
    ): List<CorrelatedThreat> {
        val correlations = mutableListOf<CorrelatedThreat>()

        // Group by device identifier (MAC or name)
        val byDevice = bleDetections
            .filter { it.macAddress != null || it.deviceName != null }
            .groupBy { it.macAddress ?: it.deviceName!! }

        for ((deviceId, detections) in byDevice) {
            // Get distinct locations
            val locationsWithTimestamp = detections
                .filter { it.latitude != null && it.longitude != null }
                .map { Triple(it.latitude!!, it.longitude!!, it.timestamp) }

            // Find distinct locations (at least 50m apart)
            val distinctLocations = mutableListOf<Triple<Double, Double, Long>>()
            for (loc in locationsWithTimestamp) {
                val isDistinct = distinctLocations.none { existing ->
                    haversineDistance(loc.first, loc.second, existing.first, existing.second) < 50
                }
                if (isDistinct) {
                    distinctLocations.add(loc)
                }
            }

            if (distinctLocations.size >= config.followingPatternMinLocations) {
                val firstDetection = detections.minByOrNull { it.timestamp }!!
                val lastDetection = detections.maxByOrNull { it.timestamp }!!
                val followingDuration = lastDetection.timestamp - firstDetection.timestamp

                // Calculate correlation score based on location count and duration
                val locationScore = (distinctLocations.size.toFloat() / 10f).coerceAtMost(1f)
                val durationScore = (followingDuration.toFloat() / (4 * 60 * 60 * 1000L)).coerceAtMost(1f)
                val correlationScore = (locationScore * 0.6f + durationScore * 0.4f).coerceIn(0f, 1f)

                val isTracker = detections.any { isTracker(it) }
                val threatLevel = if (isTracker) ThreatLevel.HIGH else ThreatLevel.MEDIUM

                val metadata = CorrelationMetadata(
                    locationCount = distinctLocations.size,
                    followingDurationMs = followingDuration,
                    trackerIdentifier = deviceId,
                    confidenceFactors = buildList {
                        add("Same device seen at ${distinctLocations.size} distinct locations")
                        add("Following duration: ${followingDuration / 60000} minutes")
                        if (isTracker) add("Device type is a known tracker")
                    }
                )

                correlations.add(
                    CorrelatedThreat(
                        id = "following-${UUID.randomUUID()}",
                        detections = detections,
                        correlationType = CorrelationType.FOLLOWING_PATTERN,
                        correlationScore = correlationScore,
                        timeWindow = followingDuration,
                        spatialProximity = null,
                        analysis = buildFollowingAnalysis(deviceId, detections, metadata),
                        combinedThreatLevel = threatLevel,
                        recommendations = buildList {
                            add("Search your belongings and vehicle for hidden trackers")
                            if (isTracker) {
                                add("Check pockets, bags, wheel wells, and under seats")
                                add("Use Apple's Find My or Android's tracker detection to locate the device")
                            }
                            add("If found, do NOT destroy it - document and report to authorities")
                            add("Consider varying your routes and schedule")
                            add("If stalking is suspected, contact local authorities or the National Domestic Violence Hotline")
                        },
                        metadata = metadata
                    )
                )
            }
        }

        return correlations
    }

    /**
     * Detect Coordinated Surveillance - multiple device types working together.
     */
    private fun detectCoordinatedSurveillance(
        allDetections: List<Detection>
    ): List<CorrelatedThreat> {
        val correlations = mutableListOf<CorrelatedThreat>()

        // Look for threats from different domains within spatial and temporal proximity
        val threatDetections = allDetections.filter { it.threatLevel >= ThreatLevel.MEDIUM }

        if (threatDetections.size < 2) return emptyList()

        // Group by approximate location
        val locationGroups = mutableMapOf<String, MutableList<Detection>>()

        for (detection in threatDetections) {
            if (detection.latitude == null || detection.longitude == null) continue

            // Create location key with ~100m resolution
            val latKey = (detection.latitude * 1000).toInt()
            val lonKey = (detection.longitude * 1000).toInt()
            val key = "$latKey,$lonKey"

            locationGroups.getOrPut(key) { mutableListOf() }.add(detection)
        }

        // Check each location group for multi-protocol coordination
        for ((_, detections) in locationGroups) {
            val protocols = detections.map { it.protocol }.toSet()

            if (protocols.size >= 2) {
                // Check temporal proximity
                val timestamps = detections.map { it.timestamp }
                val timeSpan = (timestamps.maxOrNull() ?: 0L) - (timestamps.minOrNull() ?: 0L)

                if (timeSpan <= config.coordinatedSurveillanceWindowMs) {
                    val threatLevels = detections.map { it.threatLevel }
                    val maxThreat = threatLevels.maxOrNull() ?: ThreatLevel.LOW

                    // Calculate correlation score
                    val protocolDiversity = protocols.size.toFloat() / DetectionProtocol.entries.size
                    val temporalScore = 1f - (timeSpan.toFloat() / config.coordinatedSurveillanceWindowMs)
                    val threatScore = threatLevels.sumOf { it.ordinal } / (threatLevels.size * 4f)
                    val correlationScore = (protocolDiversity * 0.4f + temporalScore * 0.3f + threatScore * 0.3f)
                        .coerceIn(0f, 1f)

                    if (correlationScore >= config.minCorrelationScore) {
                        val combinedThreatLevel = when {
                            protocols.contains(DetectionProtocol.CELLULAR) &&
                                    (protocols.contains(DetectionProtocol.WIFI) ||
                                            protocols.contains(DetectionProtocol.BLUETOOTH_LE)) -> ThreatLevel.CRITICAL
                            maxThreat >= ThreatLevel.HIGH -> ThreatLevel.HIGH
                            else -> ThreatLevel.MEDIUM
                        }

                        val metadata = CorrelationMetadata(
                            domainCount = protocols.size,
                            signalStrengthProfile = detections.associate { it.protocol.name to it.rssi },
                            confidenceFactors = buildList {
                                add("${protocols.size} different surveillance domains active")
                                add("All detected within ${timeSpan / 1000}s and ~100m radius")
                                protocols.forEach { add("${it.displayName} surveillance detected") }
                            }
                        )

                        correlations.add(
                            CorrelatedThreat(
                                id = "coordinated-${UUID.randomUUID()}",
                                detections = detections,
                                correlationType = CorrelationType.COORDINATED_SURVEILLANCE,
                                correlationScore = correlationScore,
                                timeWindow = timeSpan,
                                spatialProximity = config.coordinatedSurveillanceRadiusMeters,
                                analysis = buildCoordinatedAnalysis(detections, protocols, metadata),
                                combinedThreatLevel = combinedThreatLevel,
                                recommendations = listOf(
                                    "This area has multiple active surveillance systems",
                                    "Assume all communications are being monitored",
                                    "Use encrypted apps and VPN if you must communicate",
                                    "Consider leaving this area if possible",
                                    "Document this location as a surveillance hotspot"
                                ),
                                metadata = metadata
                            )
                        )
                    }
                }
            }
        }

        return correlations
    }

    /**
     * Detect Tracker Network - multiple trackers with similar behavior.
     */
    private fun detectTrackerNetwork(
        bleDetections: List<Detection>
    ): List<CorrelatedThreat> {
        val correlations = mutableListOf<CorrelatedThreat>()

        // Filter to tracker types
        val trackers = bleDetections.filter { isTracker(it) }

        if (trackers.size < config.trackerNetworkMinTrackers) {
            return emptyList()
        }

        // Group by manufacturer if available
        val byManufacturer = trackers
            .filter { it.manufacturer != null }
            .groupBy { it.manufacturer!! }

        for ((manufacturer, manufacturerTrackers) in byManufacturer) {
            if (manufacturerTrackers.size >= config.trackerNetworkMinTrackers) {
                // Check for similar behavior (all seen at similar times/locations)
                val timestamps = manufacturerTrackers.map { it.timestamp }
                val timeSpan = (timestamps.maxOrNull() ?: 0L) - (timestamps.minOrNull() ?: 0L)

                if (timeSpan <= config.coordinatedSurveillanceWindowMs) {
                    val correlationScore = (manufacturerTrackers.size.toFloat() / 5f).coerceAtMost(1f)

                    val trackerTypes = manufacturerTrackers.map { it.deviceType.displayName }.distinct()

                    val metadata = CorrelationMetadata(
                        trackerCount = manufacturerTrackers.size,
                        trackerTypes = trackerTypes,
                        sharedManufacturer = manufacturer,
                        confidenceFactors = buildList {
                            add("${manufacturerTrackers.size} trackers from same manufacturer")
                            add("All detected within ${timeSpan / 1000}s")
                            add("Types: ${trackerTypes.joinToString(", ")}")
                        }
                    )

                    correlations.add(
                        CorrelatedThreat(
                            id = "tracker-network-${UUID.randomUUID()}",
                            detections = manufacturerTrackers,
                            correlationType = CorrelationType.TRACKER_NETWORK,
                            correlationScore = correlationScore,
                            timeWindow = timeSpan,
                            spatialProximity = null,
                            analysis = buildTrackerNetworkAnalysis(manufacturerTrackers, metadata),
                            combinedThreatLevel = ThreatLevel.HIGH,
                            recommendations = listOf(
                                "Multiple trackers detected - thorough search recommended",
                                "Check vehicle thoroughly (wheel wells, bumpers, undercarriage)",
                                "Search all bags, coats, and frequently carried items",
                                "Consider professional counter-surveillance sweep",
                                "Document all found devices before removing"
                            ),
                            metadata = metadata
                        )
                    )
                }
            }
        }

        return correlations
    }

    /**
     * Detect Timing Correlation - synchronized activations.
     */
    private fun detectTimingCorrelation(
        allDetections: List<Detection>
    ): List<CorrelatedThreat> {
        val correlations = mutableListOf<CorrelatedThreat>()

        // Sort by timestamp
        val sorted = allDetections.sortedBy { it.timestamp }

        // Find clusters of detections within short time window
        var i = 0
        while (i < sorted.size) {
            val cluster = mutableListOf(sorted[i])
            var j = i + 1

            while (j < sorted.size &&
                sorted[j].timestamp - sorted[i].timestamp <= config.timingCorrelationWindowMs
            ) {
                cluster.add(sorted[j])
                j++
            }

            // Check if cluster has multiple protocols and threat detections
            if (cluster.size >= 3) {
                val protocols = cluster.map { it.protocol }.toSet()
                val threatCount = cluster.count { it.threatLevel >= ThreatLevel.MEDIUM }

                if (protocols.size >= 2 && threatCount >= 2) {
                    val timeSpan = cluster.maxOf { it.timestamp } - cluster.minOf { it.timestamp }
                    val correlationScore = (cluster.size.toFloat() / 10f * protocols.size / DetectionProtocol.entries.size)
                        .coerceAtMost(1f)

                    if (correlationScore >= config.minCorrelationScore) {
                        correlations.add(
                            CorrelatedThreat(
                                id = "timing-${UUID.randomUUID()}",
                                detections = cluster,
                                correlationType = CorrelationType.TIMING_CORRELATION,
                                correlationScore = correlationScore,
                                timeWindow = timeSpan,
                                spatialProximity = null,
                                analysis = "Multiple surveillance devices activated within ${timeSpan / 1000} seconds. " +
                                        "This synchronized timing suggests coordinated surveillance activity.",
                                combinedThreatLevel = if (cluster.any { it.threatLevel >= ThreatLevel.HIGH })
                                    ThreatLevel.HIGH else ThreatLevel.MEDIUM,
                                recommendations = listOf(
                                    "Note the time - this appears to be a coordinated activation",
                                    "Monitor for recurring patterns at the same time",
                                    "Consider what triggered this activation (arriving at location, etc.)"
                                ),
                                metadata = CorrelationMetadata(
                                    confidenceFactors = listOf(
                                        "${cluster.size} detections within ${timeSpan / 1000}s",
                                        "${protocols.size} different protocols involved"
                                    )
                                )
                            )
                        )
                    }
                }
            }

            i = j
        }

        return correlations
    }

    /**
     * Detect Spatial Clustering - concentration of threats in an area.
     */
    private fun detectSpatialClustering(
        allDetections: List<Detection>
    ): List<CorrelatedThreat> {
        val correlations = mutableListOf<CorrelatedThreat>()

        // Filter to detections with location
        val withLocation = allDetections.filter {
            it.latitude != null && it.longitude != null && it.threatLevel >= ThreatLevel.LOW
        }

        if (withLocation.size < config.spatialClusteringMinDevices) {
            return emptyList()
        }

        // Simple clustering: find points with many neighbors
        for (center in withLocation) {
            val neighbors = withLocation.filter { other ->
                other != center && haversineDistance(
                    center.latitude!!, center.longitude!!,
                    other.latitude!!, other.longitude!!
                ) <= config.spatialClusteringRadiusMeters
            }

            if (neighbors.size + 1 >= config.spatialClusteringMinDevices) {
                val cluster = listOf(center) + neighbors
                val clusterCenterLat = cluster.mapNotNull { it.latitude }.average()
                val clusterCenterLon = cluster.mapNotNull { it.longitude }.average()

                // Calculate max radius
                val maxRadius = cluster.mapNotNull {
                    if (it.latitude != null && it.longitude != null) {
                        haversineDistance(clusterCenterLat, clusterCenterLon, it.latitude, it.longitude)
                    } else null
                }.maxOrNull() ?: config.spatialClusteringRadiusMeters.toDouble()

                val correlationScore = (cluster.size.toFloat() / 10f).coerceAtMost(1f)
                val maxThreatLevel = cluster.maxOf { it.threatLevel }

                val metadata = CorrelationMetadata(
                    clusterCenterLat = clusterCenterLat,
                    clusterCenterLon = clusterCenterLon,
                    clusterRadiusMeters = maxRadius.toFloat(),
                    devicesInCluster = cluster.size,
                    confidenceFactors = listOf(
                        "${cluster.size} surveillance devices within ${maxRadius.toInt()}m",
                        "Highest threat level: ${maxThreatLevel.displayName}"
                    )
                )

                correlations.add(
                    CorrelatedThreat(
                        id = "cluster-${UUID.randomUUID()}",
                        detections = cluster,
                        correlationType = CorrelationType.SPATIAL_CLUSTERING,
                        correlationScore = correlationScore,
                        timeWindow = cluster.maxOf { it.timestamp } - cluster.minOf { it.timestamp },
                        spatialProximity = maxRadius.toFloat(),
                        analysis = "High concentration of surveillance devices detected. " +
                                "${cluster.size} devices within ${maxRadius.toInt()}m radius. " +
                                "This area appears to be a surveillance hotspot.",
                        combinedThreatLevel = if (cluster.size >= 5) ThreatLevel.HIGH else maxThreatLevel,
                        recommendations = listOf(
                            "This location has elevated surveillance presence",
                            "Consider limiting sensitive activities in this area",
                            "Use end-to-end encryption for any communications",
                            "Mark this location for future awareness"
                        ),
                        metadata = metadata
                    )
                )
            }
        }

        // Deduplicate overlapping clusters (keep highest scoring)
        return correlations
            .sortedByDescending { it.correlationScore }
            .distinctBy { (it.metadata?.clusterCenterLat?.toInt() ?: 0) * 1000 +
                    (it.metadata?.clusterCenterLon?.toInt() ?: 0) }
    }

    /**
     * Enhance correlation analysis with LLM.
     */
    private suspend fun enhanceWithLlm(correlation: CorrelatedThreat): CorrelatedThreat {
        if (!config.enableLlmEnhancement) return correlation

        return try {
            withTimeout(config.llmAnalysisTimeoutMs) {
                val prompt = PromptTemplates.buildCorrelationAnalysisPrompt(
                    correlation.detections,
                    correlation.correlationType.displayName,
                    correlation.metadata?.confidenceFactors ?: emptyList()
                )

                val llmAnalysis = llmEngineManager.generateResponse(prompt)

                if (llmAnalysis != null && llmAnalysis.length > 50) {
                    correlation.copy(
                        analysis = llmAnalysis,
                        recommendations = parseRecommendations(llmAnalysis) ?: correlation.recommendations
                    )
                } else {
                    correlation
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "LLM analysis timed out for correlation ${correlation.id}")
            correlation
        } catch (e: Exception) {
            Log.e(TAG, "LLM enhancement failed for correlation ${correlation.id}", e)
            correlation
        }
    }

    /**
     * Parse recommendations from LLM output.
     */
    private fun parseRecommendations(llmOutput: String): List<String>? {
        // Look for numbered list or bullet points
        val recommendations = mutableListOf<String>()

        // Try numbered format: 1. xxx, 2. xxx
        val numberedPattern = Regex("""(\d+\.\s+)([^\d\n]+)""")
        numberedPattern.findAll(llmOutput).forEach {
            recommendations.add(it.groupValues[2].trim())
        }

        if (recommendations.isNotEmpty()) return recommendations

        // Try bullet format: - xxx or * xxx
        val bulletPattern = Regex("""[-*]\s+(.+)""")
        bulletPattern.findAll(llmOutput).forEach {
            recommendations.add(it.groupValues[1].trim())
        }

        return recommendations.takeIf { it.isNotEmpty() }
    }

    // ==================== Helper Functions ====================

    private fun isTracker(detection: Detection): Boolean {
        return detection.deviceType in setOf(
            DeviceType.AIRTAG,
            DeviceType.TILE_TRACKER,
            DeviceType.SAMSUNG_SMARTTAG,
            DeviceType.GENERIC_BLE_TRACKER,
            DeviceType.TRACKING_DEVICE
        )
    }

    private fun calculateSpatialScore(d1: Detection, d2: Detection): Float {
        val distance = calculateDistance(d1, d2)
        return if (distance != null) {
            (1f - (distance / config.coordinatedSurveillanceRadiusMeters)).coerceIn(0f, 1f)
        } else {
            0.5f // Unknown - neutral score
        }
    }

    private fun calculateDistance(d1: Detection, d2: Detection): Float? {
        if (d1.latitude == null || d1.longitude == null ||
            d2.latitude == null || d2.longitude == null
        ) {
            return null
        }
        return haversineDistance(d1.latitude, d1.longitude, d2.latitude, d2.longitude).toFloat()
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    // ==================== Analysis Text Builders ====================

    private fun buildImsiCatcherAnalysis(
        cellular: Detection,
        gnss: Detection,
        metadata: CorrelationMetadata
    ): String {
        return buildString {
            appendLine("## CRITICAL: IMSI Catcher Deployment Detected")
            appendLine()
            appendLine("A cell site simulator (StingRay/IMSI catcher) appears to be operating in this area, ")
            appendLine("combined with GPS interference. This is a classic law enforcement surveillance pattern.")
            appendLine()
            appendLine("### Why This Is Serious")
            if (metadata.encryptionDowngradeDetected == true) {
                appendLine("- Your phone's encryption was DOWNGRADED, meaning calls and texts can be intercepted")
            }
            if (metadata.gnssJammingScore != null && metadata.gnssJammingScore > 0.5f) {
                appendLine("- GPS jamming detected - this prevents your phone from logging its location")
            }
            if (metadata.gnssSpoofingScore != null && metadata.gnssSpoofingScore > 0.5f) {
                appendLine("- GPS spoofing detected - your reported location may be manipulated")
            }
            appendLine()
            appendLine("### What Data May Be Compromised")
            appendLine("- Your phone's IMSI/IMEI identifiers")
            appendLine("- Call metadata (who you call, when, duration)")
            appendLine("- Text message content (if 2G downgrade)")
            appendLine("- Your precise real-time location")
        }
    }

    private fun buildFollowingAnalysis(
        deviceId: String,
        detections: List<Detection>,
        metadata: CorrelationMetadata
    ): String {
        val deviceType = detections.firstOrNull()?.deviceType?.displayName ?: "Unknown device"
        return buildString {
            appendLine("## Device Following You: $deviceType")
            appendLine()
            appendLine("A ${deviceType.lowercase()} with identifier '$deviceId' has been detected ")
            appendLine("at ${metadata.locationCount} distinct locations where you have been.")
            appendLine()
            if (metadata.followingDurationMs != null) {
                val hours = metadata.followingDurationMs / (1000 * 60 * 60)
                val minutes = (metadata.followingDurationMs / (1000 * 60)) % 60
                appendLine("This device has been following for approximately ${hours}h ${minutes}m.")
            }
            appendLine()
            appendLine("### This Could Indicate")
            appendLine("- A tracking device hidden in your belongings")
            appendLine("- A tracker attached to your vehicle")
            appendLine("- Someone using a personal tracker to monitor your movements")
        }
    }

    private fun buildCoordinatedAnalysis(
        detections: List<Detection>,
        protocols: Set<DetectionProtocol>,
        metadata: CorrelationMetadata
    ): String {
        return buildString {
            appendLine("## Multi-Vector Surveillance Detected")
            appendLine()
            appendLine("${protocols.size} different surveillance technologies are operating in coordination:")
            appendLine()
            for (protocol in protocols) {
                val count = detections.count { it.protocol == protocol }
                appendLine("- **${protocol.displayName}**: $count detection(s)")
            }
            appendLine()
            appendLine("### Assessment")
            appendLine("This pattern of multiple surveillance vectors suggests either:")
            appendLine("- An organized surveillance operation")
            appendLine("- A high-security zone with overlapping monitoring systems")
            appendLine("- Targeted surveillance using multiple approaches")
        }
    }

    private fun buildTrackerNetworkAnalysis(
        trackers: List<Detection>,
        metadata: CorrelationMetadata
    ): String {
        return buildString {
            appendLine("## Multiple Trackers Detected")
            appendLine()
            appendLine("${metadata.trackerCount} tracking devices detected with similar characteristics:")
            appendLine()
            metadata.trackerTypes?.forEach { type ->
                appendLine("- $type")
            }
            if (metadata.sharedManufacturer != null) {
                appendLine()
                appendLine("All trackers appear to be from the same manufacturer: ${metadata.sharedManufacturer}")
                appendLine("This suggests they may have been placed by the same person.")
            }
        }
    }

    private fun generateSummary(correlations: List<CorrelatedThreat>, totalDetections: Int): String {
        if (correlations.isEmpty()) {
            return "No cross-domain correlations detected in $totalDetections recent detections."
        }

        val critical = correlations.count { it.combinedThreatLevel == ThreatLevel.CRITICAL }
        val high = correlations.count { it.combinedThreatLevel == ThreatLevel.HIGH }

        return buildString {
            append("Analyzed $totalDetections detections. ")
            append("Found ${correlations.size} correlation pattern(s)")
            if (critical > 0) append(" including $critical CRITICAL")
            if (high > 0) append(if (critical > 0) " and $high HIGH" else " including $high HIGH")
            append(" threat(s).")
        }
    }

    /**
     * Clear all stored data
     */
    fun clear() {
        recentDetections.values.forEach { it.clear() }
        trackerLocationHistory.clear()
        _correlatedThreats.value = emptyList()
    }

    /**
     * Cleanup old data
     */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - CLEANUP_AGE_MS

        recentDetections.values.forEach { list ->
            synchronized(list) {
                list.removeAll { it.timestamp < cutoff }
            }
        }

        trackerLocationHistory.values.forEach { list ->
            synchronized(list) {
                list.removeAll { it.timestamp < cutoff }
            }
        }

        // Remove empty tracker histories
        trackerLocationHistory.entries.removeIf { it.value.isEmpty() }
    }
}

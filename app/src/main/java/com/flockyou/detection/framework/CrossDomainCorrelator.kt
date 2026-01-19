package com.flockyou.detection.framework

import android.util.Log
import com.flockyou.data.model.ThreatLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-Domain Threat Correlator
 *
 * Correlates detections across multiple domains (BLE, WiFi, RF, Ultrasonic)
 * to identify sophisticated tracking that uses multiple technologies or
 * to increase confidence in single-domain detections.
 *
 * Correlation methods:
 * 1. Temporal correlation - detections occurring at the same time
 * 2. Spatial correlation - detections at the same location
 * 3. Behavioral correlation - similar patterns across domains
 * 4. Signature correlation - known multi-protocol device signatures
 */
class CrossDomainCorrelator {

    companion object {
        private const val TAG = "CrossDomainCorrelator"

        // Correlation thresholds
        private const val TEMPORAL_WINDOW_MS = 60_000L  // 1 minute
        private const val SPATIAL_THRESHOLD_M = 50.0     // 50 meters
        private const val MIN_CORRELATION_SCORE = 0.4f   // Minimum to report
        private const val MAX_DETECTIONS = 1000
        private const val CLEANUP_AGE_MS = 2 * 60 * 60 * 1000L  // 2 hours
    }

    // Detection stores by domain
    private val bleDetections = ConcurrentHashMap<String, DomainDetection>()
    private val wifiDetections = ConcurrentHashMap<String, DomainDetection>()
    private val rfDetections = ConcurrentHashMap<String, DomainDetection>()
    private val ultrasonicDetections = ConcurrentHashMap<String, DomainDetection>()

    // Correlation results
    private val correlatedThreats = ConcurrentHashMap<String, CorrelatedThreat>()

    // State flows
    private val _threats = MutableStateFlow<List<CorrelatedThreat>>(emptyList())
    val threats: StateFlow<List<CorrelatedThreat>> = _threats.asStateFlow()

    // ============================================================================
    // Detection Registration
    // ============================================================================

    /**
     * Register a BLE detection
     */
    fun registerBleDetection(
        id: String,
        macAddress: String,
        deviceName: String?,
        manufacturerId: Int?,
        serviceUuids: List<String>,
        rssi: Int,
        latitude: Double?,
        longitude: Double?,
        threatLevel: ThreatLevel,
        indicators: List<String>
    ) {
        val detection = DomainDetection(
            id = id,
            domain = DetectionDomain.BLE,
            timestamp = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            signalStrength = rssi,
            threatLevel = threatLevel,
            indicators = indicators,
            metadata = mapOf(
                "mac" to macAddress,
                "name" to (deviceName ?: ""),
                "manufacturer" to (manufacturerId?.toString() ?: ""),
                "uuids" to serviceUuids.joinToString(",")
            )
        )

        bleDetections[id] = detection
        enforceLimit(bleDetections)
        correlate(detection)
    }

    /**
     * Register a WiFi detection
     */
    fun registerWifiDetection(
        id: String,
        ssid: String,
        bssid: String,
        channel: Int,
        rssi: Int,
        latitude: Double?,
        longitude: Double?,
        threatLevel: ThreatLevel,
        indicators: List<String>
    ) {
        val detection = DomainDetection(
            id = id,
            domain = DetectionDomain.WIFI,
            timestamp = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            signalStrength = rssi,
            threatLevel = threatLevel,
            indicators = indicators,
            metadata = mapOf(
                "ssid" to ssid,
                "bssid" to bssid,
                "channel" to channel.toString()
            )
        )

        wifiDetections[id] = detection
        enforceLimit(wifiDetections)
        correlate(detection)
    }

    /**
     * Register an RF/Sub-GHz detection
     */
    fun registerRfDetection(
        id: String,
        frequency: Long,
        modulation: String,
        protocolName: String?,
        rssi: Int,
        latitude: Double?,
        longitude: Double?,
        threatLevel: ThreatLevel,
        indicators: List<String>
    ) {
        val detection = DomainDetection(
            id = id,
            domain = DetectionDomain.RF,
            timestamp = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            signalStrength = rssi,
            threatLevel = threatLevel,
            indicators = indicators,
            metadata = mapOf(
                "frequency" to frequency.toString(),
                "modulation" to modulation,
                "protocol" to (protocolName ?: "unknown")
            )
        )

        rfDetections[id] = detection
        enforceLimit(rfDetections)
        correlate(detection)
    }

    /**
     * Register an ultrasonic detection
     */
    fun registerUltrasonicDetection(
        id: String,
        frequency: Int,
        amplitudeDb: Double,
        source: String,
        latitude: Double?,
        longitude: Double?,
        threatLevel: ThreatLevel,
        indicators: List<String>
    ) {
        val detection = DomainDetection(
            id = id,
            domain = DetectionDomain.ULTRASONIC,
            timestamp = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            signalStrength = amplitudeDb.toInt(),
            threatLevel = threatLevel,
            indicators = indicators,
            metadata = mapOf(
                "frequency" to frequency.toString(),
                "amplitude" to amplitudeDb.toString(),
                "source" to source
            )
        )

        ultrasonicDetections[id] = detection
        enforceLimit(ultrasonicDetections)
        correlate(detection)
    }

    // ============================================================================
    // Correlation Logic
    // ============================================================================

    /**
     * Correlate a new detection with existing detections
     */
    private fun correlate(newDetection: DomainDetection) {
        val now = newDetection.timestamp
        val correlations = mutableListOf<CorrelationMatch>()

        // Find temporally and spatially close detections in other domains
        val candidateSets = listOf(
            DetectionDomain.BLE to bleDetections,
            DetectionDomain.WIFI to wifiDetections,
            DetectionDomain.RF to rfDetections,
            DetectionDomain.ULTRASONIC to ultrasonicDetections
        ).filter { it.first != newDetection.domain }

        for ((domain, detections) in candidateSets) {
            for ((_, detection) in detections) {
                // Skip old detections
                if (now - detection.timestamp > TEMPORAL_WINDOW_MS * 2) continue

                val match = calculateCorrelation(newDetection, detection)
                if (match.score >= MIN_CORRELATION_SCORE) {
                    correlations.add(match)
                }
            }
        }

        // If we found correlations, create or update a correlated threat
        if (correlations.isNotEmpty()) {
            val bestCorrelation = correlations.maxByOrNull { it.score }!!
            createOrUpdateCorrelatedThreat(newDetection, bestCorrelation, correlations)
        }

        // Periodic cleanup
        if (bleDetections.size + wifiDetections.size + rfDetections.size + ultrasonicDetections.size > MAX_DETECTIONS * 2) {
            cleanup()
        }
    }

    /**
     * Calculate correlation score between two detections
     */
    private fun calculateCorrelation(
        d1: DomainDetection,
        d2: DomainDetection
    ): CorrelationMatch {
        var score = 0f
        val factors = mutableListOf<String>()

        // Temporal correlation (0-0.4)
        val timeDiff = kotlin.math.abs(d1.timestamp - d2.timestamp)
        val temporalScore = when {
            timeDiff < 5_000 -> 0.4f
            timeDiff < 15_000 -> 0.3f
            timeDiff < 30_000 -> 0.2f
            timeDiff < 60_000 -> 0.1f
            else -> 0f
        }
        if (temporalScore > 0) {
            score += temporalScore
            factors.add("Temporal: ${timeDiff / 1000}s apart")
        }

        // Spatial correlation (0-0.4)
        if (d1.latitude != null && d1.longitude != null &&
            d2.latitude != null && d2.longitude != null) {
            val distance = haversineDistance(
                d1.latitude, d1.longitude,
                d2.latitude, d2.longitude
            )
            val spatialScore = when {
                distance < 10 -> 0.4f
                distance < 25 -> 0.3f
                distance < 50 -> 0.2f
                distance < 100 -> 0.1f
                else -> 0f
            }
            if (spatialScore > 0) {
                score += spatialScore
                factors.add("Spatial: ${distance.toInt()}m apart")
            }
        }

        // Threat level correlation (0-0.2)
        if (d1.threatLevel == d2.threatLevel) {
            score += 0.15f
            factors.add("Same threat level: ${d1.threatLevel}")
        } else if (kotlin.math.abs(d1.threatLevel.ordinal - d2.threatLevel.ordinal) == 1) {
            score += 0.1f
            factors.add("Similar threat levels")
        }

        // Pattern correlation - check for known multi-protocol signatures
        val signatureScore = checkSignatureCorrelation(d1, d2)
        if (signatureScore > 0) {
            score += signatureScore
            factors.add("Known multi-protocol signature match")
        }

        return CorrelationMatch(
            detection1 = d1,
            detection2 = d2,
            score = score.coerceIn(0f, 1f),
            temporalOverlap = temporalScore / 0.4f,
            spatialProximity = if (d1.latitude != null) (score - temporalScore) / 0.4f else 0f,
            factors = factors
        )
    }

    /**
     * Check for known multi-protocol tracker signatures
     */
    private fun checkSignatureCorrelation(d1: DomainDetection, d2: DomainDetection): Float {
        // Example: GPS tracker with BLE beacon component
        val domains = setOf(d1.domain, d2.domain)

        // BLE + RF often indicates GPS tracker with BLE ping
        if (domains == setOf(DetectionDomain.BLE, DetectionDomain.RF)) {
            // Check if RF frequency matches known tracker bands
            val rfFreq = (if (d1.domain == DetectionDomain.RF) d1 else d2)
                .metadata["frequency"]?.toLongOrNull()
            if (rfFreq != null) {
                val rfMatches = TrackerDatabase.findRfByFrequency(rfFreq)
                if (rfMatches.any { it.category == TrackerCategory.GPS_TRACKER }) {
                    return 0.2f
                }
            }
        }

        // BLE + Ultrasonic could indicate sophisticated tracking
        if (domains == setOf(DetectionDomain.BLE, DetectionDomain.ULTRASONIC)) {
            return 0.15f
        }

        // WiFi + BLE surveillance van scenario
        if (domains == setOf(DetectionDomain.WIFI, DetectionDomain.BLE)) {
            val wifiDet = if (d1.domain == DetectionDomain.WIFI) d1 else d2
            val bleDet = if (d1.domain == DetectionDomain.BLE) d1 else d2

            // Check for mobile hotspot pattern with surveillance indicators
            if (wifiDet.threatLevel >= ThreatLevel.MEDIUM &&
                bleDet.threatLevel >= ThreatLevel.MEDIUM) {
                return 0.15f
            }
        }

        return 0f
    }

    /**
     * Create or update a correlated threat
     */
    private fun createOrUpdateCorrelatedThreat(
        newDetection: DomainDetection,
        bestMatch: CorrelationMatch,
        allMatches: List<CorrelationMatch>
    ) {
        val threatId = generateThreatId(newDetection, bestMatch)

        val existing = correlatedThreats[threatId]

        // Gather all linked detections
        val linkedDetections = allMatches.flatMap { listOf(it.detection1, it.detection2) }
            .plus(newDetection)
            .distinctBy { it.id }

        // Extract domain-specific info
        val bleInfo = linkedDetections.find { it.domain == DetectionDomain.BLE }?.let {
            BleDetectionInfo(
                macAddress = it.metadata["mac"] ?: "",
                deviceName = it.metadata["name"]?.takeIf { n -> n.isNotEmpty() },
                manufacturerId = it.metadata["manufacturer"]?.toIntOrNull(),
                serviceUuids = it.metadata["uuids"]?.split(",")?.filter { u -> u.isNotEmpty() } ?: emptyList(),
                rssi = it.signalStrength,
                timestamp = it.timestamp
            )
        }

        val wifiInfo = linkedDetections.find { it.domain == DetectionDomain.WIFI }?.let {
            WifiDetectionInfo(
                ssid = it.metadata["ssid"] ?: "",
                bssid = it.metadata["bssid"] ?: "",
                rssi = it.signalStrength,
                channel = it.metadata["channel"]?.toIntOrNull() ?: 0,
                timestamp = it.timestamp
            )
        }

        val rfInfo = linkedDetections.find { it.domain == DetectionDomain.RF }?.let {
            RfDetectionInfo(
                frequency = it.metadata["frequency"]?.toLongOrNull() ?: 0,
                modulation = it.metadata["modulation"] ?: "",
                rssi = it.signalStrength,
                protocolName = it.metadata["protocol"]?.takeIf { p -> p != "unknown" },
                timestamp = it.timestamp
            )
        }

        val ultrasonicInfo = linkedDetections.find { it.domain == DetectionDomain.ULTRASONIC }?.let {
            UltrasonicDetectionInfo(
                frequency = it.metadata["frequency"]?.toIntOrNull() ?: 0,
                amplitudeDb = it.metadata["amplitude"]?.toDoubleOrNull() ?: -100.0,
                source = it.metadata["source"] ?: "",
                timestamp = it.timestamp
            )
        }

        // Calculate aggregated threat level
        val maxThreatLevel = linkedDetections.maxOfOrNull { it.threatLevel } ?: ThreatLevel.INFO
        val aggregatedLevel = if (allMatches.size >= 2 && bestMatch.score > 0.6f) {
            // Upgrade threat level if multiple strong correlations
            upgradesThreatLevel(maxThreatLevel)
        } else {
            maxThreatLevel
        }

        // Gather all indicators
        val allIndicators = linkedDetections.flatMap { it.indicators }
            .plus(bestMatch.factors.map { "Correlation: $it" })
            .distinct()

        // Gather locations
        val locations = linkedDetections
            .filter { it.latitude != null && it.longitude != null }
            .map { LocationPoint(it.latitude!!, it.longitude!!, it.timestamp) }
            .distinctBy { "${it.latitude.toInt()}_${it.longitude.toInt()}" }

        val isFollowing = locations.size >= 3

        val threat = CorrelatedThreat(
            id = threatId,
            timestamp = System.currentTimeMillis(),
            primarySource = newDetection.domain.toProtocolType(),
            bleDetection = bleInfo,
            wifiDetection = wifiInfo,
            rfDetection = rfInfo,
            ultrasonicDetection = ultrasonicInfo,
            correlationScore = bestMatch.score,
            temporalOverlap = bestMatch.temporalOverlap,
            spatialProximity = bestMatch.spatialProximity,
            sharedLocations = locations,
            aggregatedThreatLevel = aggregatedLevel,
            threatIndicators = allIndicators,
            isFollowing = isFollowing,
            followDurationMs = if (existing != null) {
                System.currentTimeMillis() - existing.timestamp
            } else 0,
            uniqueLocationsFollowed = locations.size
        )

        correlatedThreats[threatId] = threat
        updateThreatsFlow()

        Log.i(TAG, "Correlated threat: ${linkedDetections.size} domains, score=${bestMatch.score}, level=$aggregatedLevel")
    }

    private fun generateThreatId(detection: DomainDetection, match: CorrelationMatch): String {
        // Use location as primary key if available
        if (detection.latitude != null && detection.longitude != null) {
            val locKey = "${detection.latitude.toInt()}_${detection.longitude.toInt()}"
            return "threat_${locKey}_${detection.timestamp / 60000}"
        }

        // Fallback to detection IDs
        return "threat_${detection.id}_${match.detection2.id}"
    }

    private fun upgradesThreatLevel(level: ThreatLevel): ThreatLevel {
        return when (level) {
            ThreatLevel.INFO -> ThreatLevel.LOW
            ThreatLevel.LOW -> ThreatLevel.MEDIUM
            ThreatLevel.MEDIUM -> ThreatLevel.HIGH
            ThreatLevel.HIGH -> ThreatLevel.CRITICAL
            ThreatLevel.CRITICAL -> ThreatLevel.CRITICAL
        }
    }

    private fun updateThreatsFlow() {
        val sortedThreats = correlatedThreats.values
            .sortedByDescending { it.timestamp }
            .take(50)
        _threats.value = sortedThreats
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    private fun <K, V> enforceLimit(map: ConcurrentHashMap<K, V>) {
        if (map.size > MAX_DETECTIONS) {
            val toRemove = map.size - MAX_DETECTIONS
            map.keys.take(toRemove).forEach { map.remove(it) }
        }
    }

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - CLEANUP_AGE_MS

        listOf(bleDetections, wifiDetections, rfDetections, ultrasonicDetections).forEach { detections ->
            val old = detections.entries.filter {
                (it.value as DomainDetection).timestamp < cutoff
            }
            old.forEach { detections.remove(it.key) }
        }

        val oldThreats = correlatedThreats.entries.filter { it.value.timestamp < cutoff }
        oldThreats.forEach { correlatedThreats.remove(it.key) }

        updateThreatsFlow()
    }

    /**
     * Clear all correlation data
     */
    fun clear() {
        bleDetections.clear()
        wifiDetections.clear()
        rfDetections.clear()
        ultrasonicDetections.clear()
        correlatedThreats.clear()
        _threats.value = emptyList()
    }

    /**
     * Get correlation statistics
     */
    fun getStats(): CorrelationStats {
        return CorrelationStats(
            bleDetectionCount = bleDetections.size,
            wifiDetectionCount = wifiDetections.size,
            rfDetectionCount = rfDetections.size,
            ultrasonicDetectionCount = ultrasonicDetections.size,
            correlatedThreatCount = correlatedThreats.size,
            multiDomainThreatCount = correlatedThreats.values.count {
                listOfNotNull(it.bleDetection, it.wifiDetection, it.rfDetection, it.ultrasonicDetection).size >= 2
            }
        )
    }

    // ============================================================================
    // Inner Classes
    // ============================================================================

    private data class DomainDetection(
        val id: String,
        val domain: DetectionDomain,
        val timestamp: Long,
        val latitude: Double?,
        val longitude: Double?,
        val signalStrength: Int,
        val threatLevel: ThreatLevel,
        val indicators: List<String>,
        val metadata: Map<String, String>
    )

    private data class CorrelationMatch(
        val detection1: DomainDetection,
        val detection2: DomainDetection,
        val score: Float,
        val temporalOverlap: Float,
        val spatialProximity: Float,
        val factors: List<String>
    )
}

// ============================================================================
// Enums and Data Classes
// ============================================================================

/**
 * Detection domains for correlation
 */
enum class DetectionDomain {
    BLE,
    WIFI,
    RF,
    ULTRASONIC;

    fun toProtocolType(): DetectionProtocolType = when (this) {
        BLE -> DetectionProtocolType.BLE
        WIFI -> DetectionProtocolType.WIFI
        RF -> DetectionProtocolType.SUBGHZ_RF
        ULTRASONIC -> DetectionProtocolType.ULTRASONIC
    }
}

/**
 * Correlation statistics
 */
data class CorrelationStats(
    val bleDetectionCount: Int,
    val wifiDetectionCount: Int,
    val rfDetectionCount: Int,
    val ultrasonicDetectionCount: Int,
    val correlatedThreatCount: Int,
    val multiDomainThreatCount: Int
)

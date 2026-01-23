package com.flockyou.ai

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache for enriched detector data associated with detections.
 *
 * When monitors (Cellular, GNSS, WiFi, Ultrasonic, etc.) create detections, they also
 * store the detailed heuristics analysis here. When the LLM analyzes a detection, it
 * retrieves this enriched data to provide more accurate and contextual analysis.
 *
 * Features:
 * - Thread-safe using ConcurrentHashMap
 * - Time-based expiry (configurable TTL)
 * - Size-limited to prevent memory issues
 * - Automatic cleanup of expired entries
 */
@Singleton
class EnrichedDataCache @Inject constructor() {

    companion object {
        private const val TAG = "EnrichedDataCache"
        private const val DEFAULT_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CACHE_SIZE = 200
        private const val CLEANUP_THRESHOLD = 250
    }

    private data class CacheEntry(
        val data: EnrichedDetectorData,
        val timestamp: Long = System.currentTimeMillis(),
        val ttlMs: Long = DEFAULT_TTL_MS
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Store enriched data for a detection.
     *
     * @param detectionId The unique ID of the detection
     * @param data The enriched detector data (CellularAnomalyAnalysis, GnssAnomalyAnalysis, etc.)
     * @param ttlMs Optional custom TTL in milliseconds (default 5 minutes)
     */
    fun put(detectionId: String, data: EnrichedDetectorData, ttlMs: Long = DEFAULT_TTL_MS) {
        // Clean up if cache is getting too large
        if (cache.size > CLEANUP_THRESHOLD) {
            cleanup()
        }

        cache[detectionId] = CacheEntry(data, ttlMs = ttlMs)
        Log.d(TAG, "Cached enriched data for detection: $detectionId (type: ${data.javaClass.simpleName})")
    }

    /**
     * Retrieve enriched data for a detection.
     * Returns null if not found or expired.
     *
     * @param detectionId The unique ID of the detection
     * @return The enriched data, or null if not found/expired
     */
    fun get(detectionId: String): EnrichedDetectorData? {
        val entry = cache[detectionId]

        if (entry == null) {
            Log.d(TAG, "No enriched data found for detection: $detectionId")
            return null
        }

        if (entry.isExpired()) {
            cache.remove(detectionId)
            Log.d(TAG, "Enriched data expired for detection: $detectionId")
            return null
        }

        Log.d(TAG, "Retrieved enriched data for detection: $detectionId (type: ${entry.data.javaClass.simpleName})")
        return entry.data
    }

    /**
     * Store cellular anomaly analysis for a detection.
     */
    fun putCellular(detectionId: String, analysis: com.flockyou.service.CellularMonitor.CellularAnomalyAnalysis) {
        put(detectionId, EnrichedDetectorData.Cellular(analysis))
    }

    /**
     * Store GNSS anomaly analysis for a detection.
     */
    fun putGnss(detectionId: String, analysis: com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomalyAnalysis) {
        put(detectionId, EnrichedDetectorData.Gnss(analysis))
    }

    /**
     * Store ultrasonic beacon analysis for a detection.
     */
    fun putUltrasonic(detectionId: String, analysis: com.flockyou.service.UltrasonicDetector.BeaconAnalysis) {
        put(detectionId, EnrichedDetectorData.Ultrasonic(analysis))
    }

    /**
     * Store WiFi following analysis for a detection.
     */
    fun putWifiFollowing(detectionId: String, analysis: com.flockyou.service.RogueWifiMonitor.FollowingNetworkAnalysis) {
        put(detectionId, EnrichedDetectorData.WifiFollowing(analysis))
    }

    /**
     * Store satellite/NTN enriched data for a detection.
     */
    fun putSatellite(
        detectionId: String,
        detectorType: String,
        metadata: Map<String, String>,
        signalCharacteristics: Map<String, String>,
        riskIndicators: List<String>,
        timestamp: Long = System.currentTimeMillis()
    ) {
        put(detectionId, EnrichedDetectorData.Satellite(
            detectorType = detectorType,
            metadata = metadata,
            signalCharacteristics = signalCharacteristics,
            riskIndicators = riskIndicators,
            timestamp = timestamp
        ))
    }

    /**
     * Remove enriched data for a detection.
     */
    fun remove(detectionId: String) {
        cache.remove(detectionId)
    }

    /**
     * Clear all cached data.
     */
    fun clear() {
        cache.clear()
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Get the current cache size.
     */
    fun size(): Int = cache.size

    /**
     * Clean up expired entries and trim to max size.
     */
    fun cleanup() {
        val expiredCount = cache.entries.count { it.value.isExpired() }
        cache.entries.removeIf { it.value.isExpired() }

        // If still over max size, remove oldest entries
        if (cache.size > MAX_CACHE_SIZE) {
            val toRemove = cache.entries
                .sortedBy { it.value.timestamp }
                .take(cache.size - MAX_CACHE_SIZE)
                .map { it.key }

            toRemove.forEach { cache.remove(it) }
            Log.d(TAG, "Removed ${toRemove.size} oldest entries to maintain max size")
        }

        if (expiredCount > 0) {
            Log.d(TAG, "Cleaned up $expiredCount expired entries, current size: ${cache.size}")
        }
    }

    /**
     * Get statistics about the cache.
     */
    fun getStats(): CacheStats {
        val now = System.currentTimeMillis()
        var expiredCount = 0
        var cellularCount = 0
        var gnssCount = 0
        var ultrasonicCount = 0
        var wifiCount = 0
        var satelliteCount = 0

        cache.values.forEach { entry ->
            if (entry.isExpired()) expiredCount++
            when (entry.data) {
                is EnrichedDetectorData.Cellular -> cellularCount++
                is EnrichedDetectorData.Gnss -> gnssCount++
                is EnrichedDetectorData.Ultrasonic -> ultrasonicCount++
                is EnrichedDetectorData.WifiFollowing -> wifiCount++
                is EnrichedDetectorData.Satellite -> satelliteCount++
            }
        }

        return CacheStats(
            totalEntries = cache.size,
            expiredEntries = expiredCount,
            cellularEntries = cellularCount,
            gnssEntries = gnssCount,
            ultrasonicEntries = ultrasonicCount,
            wifiEntries = wifiCount,
            satelliteEntries = satelliteCount
        )
    }

    data class CacheStats(
        val totalEntries: Int,
        val expiredEntries: Int,
        val cellularEntries: Int,
        val gnssEntries: Int,
        val ultrasonicEntries: Int,
        val wifiEntries: Int,
        val satelliteEntries: Int
    )
}

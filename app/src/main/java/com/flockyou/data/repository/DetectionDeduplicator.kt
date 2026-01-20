package com.flockyou.data.repository

import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detection Deduplicator
 *
 * Provides enhanced deduplication logic for detection storage:
 * - Throttling rapid detections of the same device
 * - Composite key matching for devices without unique identifiers
 * - Service UUID matching for BLE devices
 * - RSSI-based proximity matching
 *
 * This helps reduce duplicate detections while ensuring legitimate
 * re-detections are properly tracked.
 */
@Singleton
class DetectionDeduplicator @Inject constructor() {

    companion object {
        private const val TAG = "DetectionDeduplicator"

        // Throttling configuration
        private const val DEFAULT_THROTTLE_WINDOW_MS = 5_000L  // 5 seconds
        private const val BLE_THROTTLE_WINDOW_MS = 3_000L     // 3 seconds for BLE (faster scanning)
        private const val WIFI_THROTTLE_WINDOW_MS = 10_000L   // 10 seconds for WiFi

        // Matching thresholds
        private const val RSSI_PROXIMITY_THRESHOLD = 15       // dBm difference for "same device"
        private const val NAME_SIMILARITY_THRESHOLD = 0.85f   // 85% match for fuzzy name matching
        private const val COMPOSITE_MATCH_THRESHOLD = 0.7f    // 70% match score for composite key

        // Cleanup settings
        private const val THROTTLE_CACHE_CLEANUP_INTERVAL_MS = 60_000L  // 1 minute
        private const val MAX_THROTTLE_CACHE_SIZE = 1000
    }

    // Track last detection time for each device key (MAC, SSID, or composite)
    private val lastDetectionTimes = ConcurrentHashMap<String, Long>()
    private var lastCleanupTime = System.currentTimeMillis()

    /**
     * Check if a detection should be throttled (rapid detection suppression).
     *
     * Returns true if the same device was detected very recently and should
     * be suppressed to avoid flooding the database with duplicate entries.
     *
     * @param detection The detection to check
     * @return true if the detection should be throttled, false otherwise
     */
    fun shouldThrottle(detection: Detection): Boolean {
        val now = System.currentTimeMillis()

        // Periodic cleanup
        if (now - lastCleanupTime > THROTTLE_CACHE_CLEANUP_INTERVAL_MS) {
            cleanupThrottleCache()
            lastCleanupTime = now
        }

        // Generate throttle key from available identifiers
        val throttleKey = generateThrottleKey(detection)

        // Get appropriate throttle window based on protocol
        val throttleWindow = getThrottleWindow(detection)

        val lastSeen = lastDetectionTimes[throttleKey]
        if (lastSeen != null && (now - lastSeen) < throttleWindow) {
            return true  // Throttle - too recent
        }

        // Update last seen time
        lastDetectionTimes[throttleKey] = now

        // Enforce cache size limit
        if (lastDetectionTimes.size > MAX_THROTTLE_CACHE_SIZE) {
            evictOldestEntries()
        }

        return false  // Don't throttle
    }

    /**
     * Find a matching detection from a list of candidates using composite key matching.
     *
     * This is used when primary identifiers (MAC, SSID, service UUID) don't match,
     * but the detection might still be the same device based on multiple factors.
     *
     * @param detection The new detection to match
     * @param candidates List of potential matching detections from the database
     * @return The matching detection if found, null otherwise
     */
    fun findMatch(detection: Detection, candidates: List<Detection>): Detection? {
        if (candidates.isEmpty()) return null

        var bestMatch: Detection? = null
        var bestScore = 0f

        for (candidate in candidates) {
            val score = calculateMatchScore(detection, candidate)
            if (score > bestScore && score >= COMPOSITE_MATCH_THRESHOLD) {
                bestScore = score
                bestMatch = candidate
            }
        }

        if (bestMatch != null) {
            Log.d(TAG, "Composite match found: score=$bestScore, deviceType=${detection.deviceType}")
        }

        return bestMatch
    }

    /**
     * Calculate a match score between two detections based on multiple factors.
     *
     * Factors considered:
     * - Device name similarity
     * - Manufacturer match
     * - Device type match
     * - RSSI proximity
     * - Service UUIDs overlap
     * - Detection method
     *
     * @return Score from 0.0 to 1.0 indicating likelihood of being the same device
     */
    private fun calculateMatchScore(detection: Detection, candidate: Detection): Float {
        var score = 0f
        var maxPossibleScore = 0f

        // Device type must match (required)
        if (detection.deviceType != candidate.deviceType) {
            return 0f
        }

        // Device name similarity (high weight)
        if (detection.deviceName != null && candidate.deviceName != null) {
            maxPossibleScore += 0.35f
            val nameSimilarity = calculateStringSimilarity(detection.deviceName, candidate.deviceName)
            if (nameSimilarity >= NAME_SIMILARITY_THRESHOLD) {
                score += 0.35f * nameSimilarity
            }
        }

        // Manufacturer match (medium weight)
        if (detection.manufacturer != null && candidate.manufacturer != null) {
            maxPossibleScore += 0.2f
            if (detection.manufacturer.equals(candidate.manufacturer, ignoreCase = true)) {
                score += 0.2f
            }
        }

        // RSSI proximity (medium weight - similar signal strength suggests same device)
        maxPossibleScore += 0.15f
        val rssiDiff = kotlin.math.abs(detection.rssi - candidate.rssi)
        if (rssiDiff <= RSSI_PROXIMITY_THRESHOLD) {
            score += 0.15f * (1 - rssiDiff.toFloat() / RSSI_PROXIMITY_THRESHOLD)
        }

        // Service UUIDs overlap (high weight for BLE devices)
        val detectionUuids = detection.serviceUuids?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
        val candidateUuids = candidate.serviceUuids?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
        if (detectionUuids.isNotEmpty() && candidateUuids.isNotEmpty()) {
            maxPossibleScore += 0.25f
            val overlap = detectionUuids.intersect(candidateUuids).size
            val maxUuids = maxOf(detectionUuids.size, candidateUuids.size)
            if (overlap > 0) {
                score += 0.25f * (overlap.toFloat() / maxUuids)
            }
        }

        // Detection method match (low weight)
        maxPossibleScore += 0.1f
        if (detection.detectionMethod == candidate.detectionMethod) {
            score += 0.1f
        }

        // Protocol match (low weight, usually same if method matches)
        maxPossibleScore += 0.05f
        if (detection.protocol == candidate.protocol) {
            score += 0.05f
        }

        // Normalize score based on available matching criteria
        return if (maxPossibleScore > 0) {
            (score / maxPossibleScore).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    /**
     * Generate a throttle key from detection identifiers.
     * Uses the most specific identifier available.
     */
    private fun generateThrottleKey(detection: Detection): String {
        return when {
            // MAC address is most unique
            detection.macAddress != null -> "mac:${detection.macAddress}"

            // SSID for WiFi devices
            detection.ssid != null -> "ssid:${detection.ssid}"

            // Service UUIDs for BLE devices without MAC
            !detection.serviceUuids.isNullOrEmpty() -> {
                val primaryUuid = detection.serviceUuids.split(",").first().trim()
                "uuid:$primaryUuid:${detection.deviceType}"
            }

            // Device name + type as fallback
            detection.deviceName != null -> "name:${detection.deviceName}:${detection.deviceType}"

            // Last resort: device type + manufacturer
            detection.manufacturer != null -> "mfr:${detection.manufacturer}:${detection.deviceType}"

            // Ultimate fallback: just device type (will throttle all unknown devices together)
            else -> "type:${detection.deviceType}"
        }
    }

    /**
     * Get the appropriate throttle window based on detection protocol.
     */
    private fun getThrottleWindow(detection: Detection): Long {
        return when (detection.protocol) {
            com.flockyou.data.model.DetectionProtocol.BLUETOOTH_LE -> BLE_THROTTLE_WINDOW_MS
            com.flockyou.data.model.DetectionProtocol.WIFI -> WIFI_THROTTLE_WINDOW_MS
            else -> DEFAULT_THROTTLE_WINDOW_MS
        }
    }

    /**
     * Calculate string similarity using Levenshtein distance.
     * Returns a value from 0.0 (completely different) to 1.0 (identical).
     */
    private fun calculateStringSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0f

        val str1 = s1.lowercase()
        val str2 = s2.lowercase()

        // Quick check for containment
        if (str1.contains(str2) || str2.contains(str1)) {
            return minOf(str1.length, str2.length).toFloat() / maxOf(str1.length, str2.length)
        }

        // Levenshtein distance
        val distance = levenshteinDistance(str1, str2)
        val maxLength = maxOf(str1.length, str2.length)

        return 1.0f - (distance.toFloat() / maxLength)
    }

    /**
     * Calculate Levenshtein edit distance between two strings.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) {
            dp[i][0] = i
        }
        for (j in 0..s2.length) {
            dp[0][j] = j
        }

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[s1.length][s2.length]
    }

    /**
     * Clean up old entries from the throttle cache.
     */
    private fun cleanupThrottleCache() {
        val cutoff = System.currentTimeMillis() - WIFI_THROTTLE_WINDOW_MS * 2  // Keep 2x longest window
        val iterator = lastDetectionTimes.entries.iterator()
        var removed = 0

        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < cutoff) {
                iterator.remove()
                removed++
            }
        }

        if (removed > 0) {
            Log.d(TAG, "Cleaned up $removed stale throttle entries, ${lastDetectionTimes.size} remaining")
        }
    }

    /**
     * Evict oldest entries when cache is full.
     */
    private fun evictOldestEntries() {
        val entriesToRemove = lastDetectionTimes.entries
            .sortedBy { it.value }
            .take(MAX_THROTTLE_CACHE_SIZE / 4)
            .map { it.key }

        entriesToRemove.forEach { lastDetectionTimes.remove(it) }
        Log.d(TAG, "Evicted ${entriesToRemove.size} oldest throttle entries")
    }

    /**
     * Clear all throttle state. Call when starting a new scan session.
     */
    fun clearThrottleState() {
        lastDetectionTimes.clear()
        Log.d(TAG, "Throttle state cleared")
    }

    /**
     * Get current throttle cache size (for diagnostics).
     */
    fun getThrottleCacheSize(): Int = lastDetectionTimes.size
}

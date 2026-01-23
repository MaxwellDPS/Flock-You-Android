package com.flockyou.ai

import android.util.Log
import com.flockyou.data.AiAnalysisResult
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min

/**
 * Features extracted from a detection for similarity comparison.
 * These features are designed to capture the semantic essence of a detection
 * while normalizing variable fields like timestamps and exact signal strengths.
 */
data class DetectionFeatures(
    val deviceType: DeviceType,
    val protocol: String,
    val signalStrengthBucket: Int, // -90 to -80 = 0, -80 to -70 = 1, etc.
    val threatLevel: ThreatLevel,
    val hasName: Boolean,
    val namePattern: String?, // Normalized pattern (lowercase, stripped of numbers/special chars)
    val matchedPatterns: Set<String>,
    val detectionMethod: String,
    val manufacturer: String?
) {
    companion object {
        /**
         * Extract features from a detection for similarity comparison.
         */
        fun fromDetection(detection: Detection): DetectionFeatures {
            return DetectionFeatures(
                deviceType = detection.deviceType,
                protocol = detection.protocol.name,
                signalStrengthBucket = rssiToBucket(detection.rssi),
                threatLevel = detection.threatLevel,
                hasName = !detection.deviceName.isNullOrBlank(),
                namePattern = normalizeNamePattern(detection.deviceName ?: detection.ssid),
                matchedPatterns = parseMatchedPatterns(detection.matchedPatterns),
                detectionMethod = detection.detectionMethod.name,
                manufacturer = detection.manufacturer?.lowercase()
            )
        }

        /**
         * Convert RSSI to a bucket for similarity comparison.
         * Buckets: 0 = very weak (<-90), 1 = weak (-90 to -80), 2 = medium (-80 to -70),
         *          3 = good (-70 to -60), 4 = strong (-60 to -50), 5 = excellent (>-50)
         */
        private fun rssiToBucket(rssi: Int): Int = when {
            rssi < -90 -> 0
            rssi < -80 -> 1
            rssi < -70 -> 2
            rssi < -60 -> 3
            rssi < -50 -> 4
            else -> 5
        }

        /**
         * Normalize a device name/SSID pattern for comparison.
         * Removes numbers and special characters, lowercases, and extracts word patterns.
         */
        private fun normalizeNamePattern(name: String?): String? {
            if (name.isNullOrBlank()) return null
            // Keep only letters and spaces, lowercase
            return name.lowercase()
                .replace(Regex("[^a-z\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.isNotBlank() }
        }

        /**
         * Parse matched patterns from JSON string to a set.
         */
        private fun parseMatchedPatterns(patterns: String?): Set<String> {
            if (patterns.isNullOrBlank()) return emptySet()
            return try {
                // Patterns are stored as JSON array: ["pattern1", "pattern2"]
                patterns.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (e: Exception) {
                emptySet()
            }
        }
    }
}

/**
 * A cache entry storing a detection's features, analysis result, and metadata.
 */
data class SimilarityCacheEntry(
    val detection: Detection,
    val features: DetectionFeatures,
    val analysis: AiAnalysisResult,
    val timestamp: Long,
    val lastAccessTime: AtomicLong = AtomicLong(System.currentTimeMillis())
) {
    fun touch() {
        lastAccessTime.set(System.currentTimeMillis())
    }
}

/**
 * Cache statistics for monitoring hit rates and performance.
 */
data class SimilarityCacheStats(
    var totalRequests: Long = 0,
    var exactHits: Long = 0,
    var similarityHits: Long = 0,
    var misses: Long = 0,
    var evictions: Long = 0,
    var averageSimilarityOnHit: Float = 0f,
    private var similaritySum: Double = 0.0,
    private var similarityCount: Long = 0
) {
    val hitRate: Float
        get() = if (totalRequests > 0) (exactHits + similarityHits).toFloat() / totalRequests else 0f

    val exactHitRate: Float
        get() = if (totalRequests > 0) exactHits.toFloat() / totalRequests else 0f

    val similarityHitRate: Float
        get() = if (totalRequests > 0) similarityHits.toFloat() / totalRequests else 0f

    val missRate: Float
        get() = if (totalRequests > 0) misses.toFloat() / totalRequests else 0f

    fun recordSimilarityHit(similarity: Float) {
        similarityHits++
        similaritySum += similarity
        similarityCount++
        averageSimilarityOnHit = (similaritySum / similarityCount).toFloat()
    }

    fun reset() {
        totalRequests = 0
        exactHits = 0
        similarityHits = 0
        misses = 0
        evictions = 0
        averageSimilarityOnHit = 0f
        similaritySum = 0.0
        similarityCount = 0
    }

    override fun toString(): String {
        return "CacheStats(requests=$totalRequests, hitRate=${(hitRate * 100).toInt()}%, " +
            "exactHits=$exactHits, similarityHits=$similarityHits, misses=$misses, " +
            "avgSimilarity=${(averageSimilarityOnHit * 100).toInt()}%)"
    }
}

/**
 * Result of a similarity cache lookup.
 */
sealed class SimilarityCacheLookupResult {
    data class ExactHit(val analysis: AiAnalysisResult) : SimilarityCacheLookupResult()
    data class SimilarityHit(
        val analysis: AiAnalysisResult,
        val similarity: Float,
        val originalDetectionId: String
    ) : SimilarityCacheLookupResult()
    object Miss : SimilarityCacheLookupResult()
}

/**
 * Smarter caching system using semantic similarity to find similar past analyses.
 *
 * This cache achieves higher hit rates by finding detections with similar characteristics
 * instead of requiring exact matches. It uses:
 * - Feature extraction for semantic comparison
 * - Device type indexing for efficient lookup
 * - Levenshtein distance for name pattern matching
 * - LRU eviction when cache is full
 *
 * Similarity calculation weights:
 * - Exact match on device type: +0.4
 * - Same protocol: +0.2
 * - Same signal strength bucket: +0.1
 * - Same threat level: +0.15
 * - Similar name pattern (Levenshtein): +0.15
 * - Overlapping matched patterns: +0.1 per overlap (max 0.2)
 */
class SimilarityCache(
    private val maxSize: Int = 500,
    private val similarityThreshold: Float = 0.85f,
    private val cacheExpiryMs: Long = 30 * 60 * 1000L // 30 minutes default
) {
    companion object {
        private const val TAG = "SimilarityCache"

        // Similarity weights
        private const val WEIGHT_DEVICE_TYPE = 0.40f
        private const val WEIGHT_PROTOCOL = 0.20f
        private const val WEIGHT_SIGNAL_BUCKET = 0.10f
        private const val WEIGHT_THREAT_LEVEL = 0.15f
        private const val WEIGHT_NAME_PATTERN = 0.15f
        private const val WEIGHT_MATCHED_PATTERNS_PER_OVERLAP = 0.05f
        private const val WEIGHT_MATCHED_PATTERNS_MAX = 0.10f

        // Levenshtein similarity thresholds
        private const val NAME_SIMILARITY_THRESHOLD = 0.7f
    }

    // Main cache storage indexed by detection ID
    private val cacheById = ConcurrentHashMap<String, SimilarityCacheEntry>()

    // Secondary index by device type for efficient lookup
    private val cacheByDeviceType = ConcurrentHashMap<DeviceType, MutableSet<String>>()

    // Cache statistics
    val stats = SimilarityCacheStats()

    /**
     * Find a similar cached analysis for the given detection.
     * Returns the cached analysis if a detection with similar characteristics exists.
     *
     * @param detection The detection to find a similar analysis for
     * @return SimilarityCacheLookupResult indicating exact hit, similarity hit, or miss
     */
    fun findSimilar(detection: Detection): SimilarityCacheLookupResult {
        stats.totalRequests++
        val now = System.currentTimeMillis()
        val features = DetectionFeatures.fromDetection(detection)

        // First, check for exact ID match
        cacheById[detection.id]?.let { entry ->
            if (now - entry.timestamp < getExpiryForDevice(detection.deviceType)) {
                entry.touch()
                stats.exactHits++
                Log.d(TAG, "Exact cache hit for detection ${detection.id}")
                return SimilarityCacheLookupResult.ExactHit(entry.analysis)
            } else {
                // Expired, remove it
                removeEntry(detection.id)
            }
        }

        // Look for similar detections of the same device type
        val candidateIds = cacheByDeviceType[detection.deviceType] ?: emptySet()

        var bestMatch: SimilarityCacheEntry? = null
        var bestSimilarity = 0f

        for (candidateId in candidateIds) {
            val entry = cacheById[candidateId] ?: continue

            // Skip expired entries
            val expiry = getExpiryForDevice(entry.detection.deviceType)
            if (now - entry.timestamp > expiry) {
                removeEntry(candidateId)
                continue
            }

            val similarity = calculateSimilarity(features, entry.features)
            if (similarity >= similarityThreshold && similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatch = entry
            }
        }

        if (bestMatch != null) {
            bestMatch.touch()
            stats.recordSimilarityHit(bestSimilarity)
            Log.d(TAG, "Similarity cache hit! Score: ${(bestSimilarity * 100).toInt()}% " +
                "for ${detection.deviceType.displayName}")

            // Adapt the cached analysis for the new detection
            val adaptedAnalysis = adaptAnalysisForDetection(bestMatch.analysis, detection)
            return SimilarityCacheLookupResult.SimilarityHit(
                analysis = adaptedAnalysis,
                similarity = bestSimilarity,
                originalDetectionId = bestMatch.detection.id
            )
        }

        stats.misses++
        Log.d(TAG, "Cache miss for ${detection.deviceType.displayName}")
        return SimilarityCacheLookupResult.Miss
    }

    /**
     * Add a new analysis to the cache.
     *
     * @param detection The detection that was analyzed
     * @param analysis The analysis result to cache
     */
    fun put(detection: Detection, analysis: AiAnalysisResult) {
        // Evict entries if cache is full
        if (cacheById.size >= maxSize) {
            evictLruEntries(maxSize / 10) // Evict 10% of entries
        }

        val features = DetectionFeatures.fromDetection(detection)
        val entry = SimilarityCacheEntry(
            detection = detection,
            features = features,
            analysis = analysis,
            timestamp = System.currentTimeMillis()
        )

        cacheById[detection.id] = entry
        cacheByDeviceType.computeIfAbsent(detection.deviceType) {
            ConcurrentHashMap.newKeySet()
        }.add(detection.id)

        Log.d(TAG, "Cached analysis for ${detection.deviceType.displayName} (id=${detection.id})")
    }

    /**
     * Calculate similarity between two detection features.
     *
     * @return Similarity score between 0.0 and 1.0
     */
    fun calculateSimilarity(a: DetectionFeatures, b: DetectionFeatures): Float {
        var score = 0f

        // Device type must match (required for similarity consideration)
        // This is enforced by the lookup filtering, but we add weight here
        if (a.deviceType == b.deviceType) {
            score += WEIGHT_DEVICE_TYPE
        } else {
            // Different device types are never similar enough
            return 0f
        }

        // Protocol match
        if (a.protocol == b.protocol) {
            score += WEIGHT_PROTOCOL
        }

        // Signal strength bucket match
        if (a.signalStrengthBucket == b.signalStrengthBucket) {
            score += WEIGHT_SIGNAL_BUCKET
        } else if (abs(a.signalStrengthBucket - b.signalStrengthBucket) == 1) {
            // Adjacent buckets get partial credit
            score += WEIGHT_SIGNAL_BUCKET * 0.5f
        }

        // Threat level match
        if (a.threatLevel == b.threatLevel) {
            score += WEIGHT_THREAT_LEVEL
        } else {
            // Adjacent threat levels get partial credit
            val threatLevels = ThreatLevel.entries
            val aIndex = threatLevels.indexOf(a.threatLevel)
            val bIndex = threatLevels.indexOf(b.threatLevel)
            if (abs(aIndex - bIndex) == 1) {
                score += WEIGHT_THREAT_LEVEL * 0.5f
            }
        }

        // Name pattern similarity using Levenshtein distance
        if (a.namePattern != null && b.namePattern != null) {
            val nameSimilarity = calculateLevenshteinSimilarity(a.namePattern, b.namePattern)
            if (nameSimilarity >= NAME_SIMILARITY_THRESHOLD) {
                score += WEIGHT_NAME_PATTERN * nameSimilarity
            }
        } else if (a.hasName == b.hasName) {
            // Both have names or both don't - partial credit
            score += WEIGHT_NAME_PATTERN * 0.3f
        }

        // Overlapping matched patterns
        if (a.matchedPatterns.isNotEmpty() && b.matchedPatterns.isNotEmpty()) {
            val overlap = a.matchedPatterns.intersect(b.matchedPatterns).size
            val patternScore = min(
                overlap * WEIGHT_MATCHED_PATTERNS_PER_OVERLAP,
                WEIGHT_MATCHED_PATTERNS_MAX
            )
            score += patternScore
        }

        // Detection method match (bonus, not part of base score)
        if (a.detectionMethod == b.detectionMethod) {
            score += 0.05f // Small bonus for same detection method
        }

        // Manufacturer match (bonus)
        if (a.manufacturer != null && b.manufacturer != null && a.manufacturer == b.manufacturer) {
            score += 0.05f
        }

        // Normalize to 0-1 range (max possible with bonuses is ~1.15, but typically <= 1.0)
        return score.coerceIn(0f, 1f)
    }

    /**
     * Calculate Levenshtein similarity between two strings.
     * Returns a value between 0.0 (completely different) and 1.0 (identical).
     */
    private fun calculateLevenshteinSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1f
        if (s1.isEmpty() || s2.isEmpty()) return 0f

        val maxLength = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return 1f - (distance.toFloat() / maxLength)
    }

    /**
     * Calculate Levenshtein distance between two strings.
     * Uses dynamic programming with O(min(m,n)) space.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        // Ensure s1 is the shorter string for space optimization
        val (shorter, longer) = if (s1.length <= s2.length) s1 to s2 else s2 to s1

        var previousRow = IntArray(shorter.length + 1) { it }
        var currentRow = IntArray(shorter.length + 1)

        for (i in 1..longer.length) {
            currentRow[0] = i

            for (j in 1..shorter.length) {
                val cost = if (longer[i - 1] == shorter[j - 1]) 0 else 1
                currentRow[j] = minOf(
                    currentRow[j - 1] + 1,      // Insertion
                    previousRow[j] + 1,          // Deletion
                    previousRow[j - 1] + cost    // Substitution
                )
            }

            // Swap rows
            val temp = previousRow
            previousRow = currentRow
            currentRow = temp
        }

        return previousRow[shorter.length]
    }

    /**
     * Adapt a cached analysis for a new detection.
     * Updates device-specific fields while preserving the core analysis.
     */
    private fun adaptAnalysisForDetection(
        cachedAnalysis: AiAnalysisResult,
        newDetection: Detection
    ): AiAnalysisResult {
        return cachedAnalysis.copy(
            analysis = cachedAnalysis.analysis?.let { analysis ->
                "$analysis\n\n_[Analysis adapted from similar ${newDetection.deviceType.displayName} detection]_"
            }
        )
    }

    /**
     * Get cache expiry time based on device type.
     * Consumer devices have longer expiry, suspicious devices shorter.
     */
    private fun getExpiryForDevice(deviceType: DeviceType): Long {
        return when (deviceType) {
            // Consumer IoT devices - stable, longer cache
            DeviceType.RING_DOORBELL,
            DeviceType.NEST_CAMERA,
            DeviceType.WYZE_CAMERA,
            DeviceType.ARLO_CAMERA,
            DeviceType.EUFY_CAMERA,
            DeviceType.BLINK_CAMERA,
            DeviceType.SIMPLISAFE_DEVICE,
            DeviceType.ADT_DEVICE,
            DeviceType.VIVINT_DEVICE,
            DeviceType.AMAZON_SIDEWALK -> 2 * 60 * 60 * 1000L // 2 hours

            // Infrastructure - stable, longer cache
            DeviceType.BLUETOOTH_BEACON,
            DeviceType.RETAIL_TRACKER,
            DeviceType.TRAFFIC_SENSOR,
            DeviceType.TOLL_READER -> 2 * 60 * 60 * 1000L // 2 hours

            // Suspicious/threat devices - shorter cache for fresh analysis
            DeviceType.STINGRAY_IMSI,
            DeviceType.GNSS_SPOOFER,
            DeviceType.GNSS_JAMMER,
            DeviceType.FLIPPER_ZERO,
            DeviceType.FLIPPER_ZERO_SPAM,
            DeviceType.WIFI_PINEAPPLE,
            DeviceType.ROGUE_AP,
            DeviceType.RF_JAMMER -> 15 * 60 * 1000L // 15 minutes

            // Default expiry
            else -> cacheExpiryMs
        }
    }

    /**
     * Remove an entry from all cache structures.
     */
    private fun removeEntry(id: String) {
        val entry = cacheById.remove(id)
        if (entry != null) {
            cacheByDeviceType[entry.detection.deviceType]?.remove(id)
        }
    }

    /**
     * Evict the least recently used entries.
     */
    private fun evictLruEntries(count: Int) {
        val toEvict = cacheById.entries
            .sortedBy { it.value.lastAccessTime.get() }
            .take(count)
            .map { it.key }

        toEvict.forEach { removeEntry(it) }
        stats.evictions += toEvict.size
        Log.d(TAG, "Evicted $count LRU entries from cache")
    }

    /**
     * Clear all cache entries.
     */
    fun clear() {
        cacheById.clear()
        cacheByDeviceType.clear()
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Clear expired entries from the cache.
     */
    fun clearExpired() {
        val now = System.currentTimeMillis()
        val expiredIds = cacheById.entries
            .filter { (_, entry) ->
                now - entry.timestamp > getExpiryForDevice(entry.detection.deviceType)
            }
            .map { it.key }

        expiredIds.forEach { removeEntry(it) }
        if (expiredIds.isNotEmpty()) {
            Log.d(TAG, "Cleared ${expiredIds.size} expired entries")
        }
    }

    /**
     * Get current cache size.
     */
    val size: Int
        get() = cacheById.size

    /**
     * Get cache size for a specific device type.
     */
    fun sizeForDeviceType(deviceType: DeviceType): Int {
        return cacheByDeviceType[deviceType]?.size ?: 0
    }

    /**
     * Pre-populate cache with a detection and analysis.
     * Useful for warming up the cache with common patterns.
     */
    fun prepopulate(detection: Detection, analysis: AiAnalysisResult) {
        put(detection, analysis)
    }

    /**
     * Get a summary of cache contents by device type.
     */
    fun getDeviceTypeSummary(): Map<DeviceType, Int> {
        return cacheByDeviceType.mapValues { it.value.size }
    }
}

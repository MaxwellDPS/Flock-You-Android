package com.flockyou.detection

import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionProtocol
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Data class representing a composite deduplication key for a detection.
 * Uses multiple identifiers to create a stable key even when individual IDs change
 * (e.g., BLE devices with randomized MACs).
 */
data class DedupeKey(
    val macAddress: String?,
    val ssid: String?,
    val serviceUuids: String?,        // From BLE service UUIDs
    val compositeKey: String,         // deviceType:protocol:manufacturer hash
    val ssidPrefix: String?           // First part of SSID before numbers/dashes
)

/**
 * Comprehensive deduplication logic for detection events.
 *
 * This class provides multiple deduplication strategies to handle various
 * scenarios including:
 * - Standard MAC-based deduplication
 * - BLE devices with randomized MACs (using service UUIDs, manufacturer data)
 * - SSID-based fuzzy matching for WiFi devices
 * - Location proximity matching for nearby devices
 * - Time-window throttling to prevent rapid duplicate detections
 *
 * Thread-safe: Uses ConcurrentHashMap for recentDetections tracking.
 */
@Singleton
class DetectionDeduplicator @Inject constructor() {

    companion object {
        private const val TAG = "DetectionDeduplicator"

        // Time window for throttling rapid detections (5 seconds)
        private const val RAPID_DETECTION_WINDOW_MS = 5000L

        // Location proximity threshold (10 meters)
        private const val LOCATION_PROXIMITY_METERS = 10.0

        // SSID fuzzy match threshold (85% similarity)
        private const val SSID_SIMILARITY_THRESHOLD = 0.85f

        // Earth radius in meters for haversine calculation
        private const val EARTH_RADIUS_METERS = 6371000.0

        // Cleanup threshold: remove entries older than 2x detection window
        private const val CLEANUP_THRESHOLD_MS = RAPID_DETECTION_WINDOW_MS * 2

        // MAC OUI prefix length (first 3 octets, e.g., "AA:BB:CC")
        private const val OUI_PREFIX_LENGTH = 8
    }

    // Track recent detections for time-window throttling
    // Key: deduplication key string, Value: timestamp of last detection
    private val recentDetections = ConcurrentHashMap<String, Long>()

    /**
     * Generate a composite deduplication key for a detection.
     * Uses multiple identifiers to create a stable key even when individual IDs change.
     *
     * The key includes:
     * - MAC address (if available)
     * - SSID (if available)
     * - Service UUIDs (for BLE devices)
     * - Composite key from device type, protocol, manufacturer, and MAC OUI
     * - SSID prefix (for fuzzy matching)
     *
     * @param detection The detection to generate a key for
     * @return A DedupeKey containing all available identifiers
     */
    fun generateDedupeKey(detection: Detection): DedupeKey {
        val macAddress = detection.macAddress?.uppercase()?.trim()
        val ssid = detection.ssid?.trim()
        val serviceUuids = detection.serviceUuids?.trim()

        // Extract MAC OUI (first 3 octets) for manufacturer identification
        val macOui = macAddress?.take(OUI_PREFIX_LENGTH)

        // Build composite key from stable device characteristics
        val compositeComponents = listOfNotNull(
            detection.deviceType.name,
            detection.protocol.name,
            detection.manufacturer?.trim()?.uppercase(),
            macOui
        )
        val compositeKey = generateHash(compositeComponents.joinToString(":"))

        // Extract SSID prefix (before numbers/dashes) for fuzzy matching
        val ssidPrefix = extractSsidPrefix(ssid)

        return DedupeKey(
            macAddress = macAddress,
            ssid = ssid,
            serviceUuids = serviceUuids,
            compositeKey = compositeKey,
            ssidPrefix = ssidPrefix
        )
    }

    /**
     * Check if this detection should be throttled (detected too recently).
     * Returns true if detection was seen within RAPID_DETECTION_WINDOW_MS.
     *
     * This prevents UI flooding when a device is continuously broadcasting.
     *
     * @param detection The detection to check
     * @return true if the detection should be throttled (suppressed)
     */
    fun shouldThrottle(detection: Detection): Boolean {
        val key = generateThrottleKey(detection)
        val now = System.currentTimeMillis()

        val lastSeen = recentDetections[key]
        if (lastSeen != null && (now - lastSeen) < RAPID_DETECTION_WINDOW_MS) {
            return true
        }

        // Update the timestamp for this detection
        recentDetections[key] = now
        return false
    }

    /**
     * Find matching detection from existing list using multiple strategies.
     * Priority order:
     * 1. MAC exact match (most reliable for non-randomized MACs)
     * 2. Service UUID match (for BLE devices with randomized MACs)
     * 3. Composite key match (deviceType + protocol + manufacturer + OUI)
     * 4. Location + Type proximity match (same type within LOCATION_PROXIMITY_METERS)
     *
     * @param detection The new detection to match
     * @param existing List of existing detections to search
     * @return The matching detection, or null if no match found
     */
    fun findMatch(detection: Detection, existing: List<Detection>): Detection? {
        if (existing.isEmpty()) return null

        val dedupeKey = generateDedupeKey(detection)

        // Strategy 1: MAC exact match (highest confidence)
        if (!dedupeKey.macAddress.isNullOrBlank()) {
            val macMatch = existing.find { existingDetection ->
                existingDetection.macAddress?.uppercase()?.trim() == dedupeKey.macAddress
            }
            if (macMatch != null) return macMatch
        }

        // Strategy 2: Service UUID match (for BLE devices with randomized MACs)
        if (!dedupeKey.serviceUuids.isNullOrBlank() && detection.protocol == DetectionProtocol.BLUETOOTH_LE) {
            val uuidMatch = findServiceUuidMatch(detection, existing)
            if (uuidMatch != null) return uuidMatch
        }

        // Strategy 3: Composite key match
        val compositeMatch = existing.find { existingDetection ->
            val existingKey = generateDedupeKey(existingDetection)
            existingKey.compositeKey == dedupeKey.compositeKey
        }
        if (compositeMatch != null) return compositeMatch

        // Strategy 4: Location + Type proximity match
        val proximityMatch = existing.find { existingDetection ->
            isLocationProximityMatch(detection, existingDetection)
        }
        if (proximityMatch != null) return proximityMatch

        // Strategy 5: SSID similarity match (for WiFi devices)
        if (!dedupeKey.ssid.isNullOrBlank()) {
            val ssidMatch = existing.find { existingDetection ->
                val similarity = ssidSimilarity(detection.ssid, existingDetection.ssid)
                similarity >= SSID_SIMILARITY_THRESHOLD &&
                        detection.deviceType == existingDetection.deviceType
            }
            if (ssidMatch != null) return ssidMatch
        }

        return null
    }

    /**
     * Find a match based on BLE service UUIDs.
     * Handles BLE devices with randomized MACs by matching on advertised services.
     *
     * @param detection The new detection
     * @param existing List of existing detections
     * @return Matching detection or null
     */
    private fun findServiceUuidMatch(detection: Detection, existing: List<Detection>): Detection? {
        val detectionUuids = parseServiceUuids(detection.serviceUuids)
        if (detectionUuids.isEmpty()) return null

        return existing.find { existingDetection ->
            if (existingDetection.protocol != DetectionProtocol.BLUETOOTH_LE) return@find false

            val existingUuids = parseServiceUuids(existingDetection.serviceUuids)
            if (existingUuids.isEmpty()) return@find false

            // Check if there's significant overlap in service UUIDs
            val intersection = detectionUuids.intersect(existingUuids)
            val unionSize = (detectionUuids + existingUuids).size

            // Jaccard similarity: intersection / union >= 0.5
            intersection.isNotEmpty() && (intersection.size.toFloat() / unionSize >= 0.5f)
        }
    }

    /**
     * Parse service UUIDs from JSON string or comma-separated format.
     *
     * @param serviceUuids The service UUIDs string
     * @return Set of normalized UUIDs
     */
    private fun parseServiceUuids(serviceUuids: String?): Set<String> {
        if (serviceUuids.isNullOrBlank()) return emptySet()

        // Handle both JSON array format ["uuid1", "uuid2"] and comma-separated
        val cleaned = serviceUuids
            .replace("[", "")
            .replace("]", "")
            .replace("\"", "")
            .replace("'", "")

        return cleaned.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Calculate SSID similarity score (0.0 to 1.0).
     * Uses Levenshtein distance normalized by the maximum length.
     *
     * @param ssid1 First SSID
     * @param ssid2 Second SSID
     * @return Similarity score between 0.0 and 1.0
     */
    fun ssidSimilarity(ssid1: String?, ssid2: String?): Float {
        if (ssid1.isNullOrBlank() || ssid2.isNullOrBlank()) return 0.0f

        val s1 = ssid1.trim().uppercase()
        val s2 = ssid2.trim().uppercase()

        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f

        val distance = levenshteinDistance(s1, s2)
        val maxLength = maxOf(s1.length, s2.length)

        return 1.0f - (distance.toFloat() / maxLength)
    }

    /**
     * Calculate Levenshtein distance between two strings.
     * Uses dynamic programming with O(min(m,n)) space complexity.
     *
     * @param s1 First string
     * @param s2 Second string
     * @return Edit distance between the strings
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        // Optimize by ensuring s1 is the shorter string
        val (short, long) = if (s1.length <= s2.length) s1 to s2 else s2 to s1

        // Use two rows instead of full matrix for space efficiency
        var previousRow = IntArray(short.length + 1) { it }
        var currentRow = IntArray(short.length + 1)

        for (i in 1..long.length) {
            currentRow[0] = i

            for (j in 1..short.length) {
                val cost = if (long[i - 1] == short[j - 1]) 0 else 1
                currentRow[j] = minOf(
                    previousRow[j] + 1,        // deletion
                    currentRow[j - 1] + 1,     // insertion
                    previousRow[j - 1] + cost  // substitution
                )
            }

            // Swap rows
            val temp = previousRow
            previousRow = currentRow
            currentRow = temp
        }

        return previousRow[short.length]
    }

    /**
     * Check if two detections are likely the same device based on location proximity.
     * Same device type + within LOCATION_PROXIMITY_METERS = likely match.
     *
     * @param d1 First detection
     * @param d2 Second detection
     * @return true if detections are likely from the same device
     */
    fun isLocationProximityMatch(d1: Detection, d2: Detection): Boolean {
        // Must be same device type
        if (d1.deviceType != d2.deviceType) return false

        // Must be same protocol
        if (d1.protocol != d2.protocol) return false

        // Both must have location data
        val lat1 = d1.latitude ?: return false
        val lon1 = d1.longitude ?: return false
        val lat2 = d2.latitude ?: return false
        val lon2 = d2.longitude ?: return false

        // Calculate distance using haversine formula
        val distance = haversineDistance(lat1, lon1, lat2, lon2)

        return distance <= LOCATION_PROXIMITY_METERS
    }

    /**
     * Calculate the great-circle distance between two points using the Haversine formula.
     *
     * @param lat1 Latitude of first point in degrees
     * @param lon1 Longitude of first point in degrees
     * @param lat2 Latitude of second point in degrees
     * @param lon2 Longitude of second point in degrees
     * @return Distance in meters
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(radLat1) * cos(radLat2) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Clean up stale entries from recentDetections map.
     * Removes entries older than 2x RAPID_DETECTION_WINDOW_MS.
     *
     * Should be called periodically (e.g., every minute) to prevent memory leaks.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val cutoff = now - CLEANUP_THRESHOLD_MS

        val iterator = recentDetections.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < cutoff) {
                iterator.remove()
            }
        }
    }

    /**
     * Get the current size of the recentDetections map (for monitoring/debugging).
     *
     * @return Number of entries in the throttling cache
     */
    fun getThrottleCacheSize(): Int = recentDetections.size

    /**
     * Clear all throttling data (useful for testing or reset).
     */
    fun clearThrottleCache() {
        recentDetections.clear()
    }

    // ========== BLE Randomized MAC Handling ==========

    /**
     * Generate a stable identifier for BLE devices with randomized MACs.
     * Uses multiple signals to create a consistent key even when MAC changes.
     *
     * @param detection The BLE detection
     * @return A stable identifier string, or null if no stable signals available
     */
    fun generateBleStableIdentifier(detection: Detection): String? {
        if (detection.protocol != DetectionProtocol.BLUETOOTH_LE) return null

        val components = mutableListOf<String>()

        // 1. Service UUIDs (most stable identifier for BLE)
        detection.serviceUuids?.let { uuids ->
            val parsed = parseServiceUuids(uuids)
            if (parsed.isNotEmpty()) {
                components.add("SVC:${parsed.sorted().joinToString(",")}")
            }
        }

        // 2. Manufacturer data pattern (from rawData if available)
        detection.rawData?.let { raw ->
            extractManufacturerPattern(raw)?.let { pattern ->
                components.add("MFG:$pattern")
            }
        }

        // 3. Device name (if not generic)
        detection.deviceName?.let { name ->
            if (!isGenericBleName(name)) {
                components.add("NAME:${name.uppercase().trim()}")
            }
        }

        // 4. Device type and manufacturer
        components.add("TYPE:${detection.deviceType.name}")
        detection.manufacturer?.let { mfg ->
            components.add("MFG_NAME:${mfg.uppercase().trim()}")
        }

        // 5. Advertising payload hash (from rawData)
        detection.rawData?.let { raw ->
            val payloadHash = generateAdvertisingPayloadHash(raw)
            if (payloadHash != null) {
                components.add("PAYLOAD:$payloadHash")
            }
        }

        return if (components.size >= 2) {
            generateHash(components.sorted().joinToString("|"))
        } else {
            null
        }
    }

    /**
     * Extract manufacturer-specific data pattern from raw advertising data.
     * Looks for manufacturer data type (0xFF) in the advertising payload.
     *
     * @param rawData Hex string of raw advertising data
     * @return Manufacturer pattern string, or null if not found
     */
    private fun extractManufacturerPattern(rawData: String): String? {
        try {
            // Look for manufacturer data in advertising payload
            // Format: length byte, type byte (0xFF for manufacturer), company ID (2 bytes), data
            val hex = rawData.uppercase().replace(" ", "").replace(":", "")

            // Simple pattern: find 0xFF type and extract company ID
            var i = 0
            while (i < hex.length - 6) {
                val length = hex.substring(i, i + 2).toIntOrNull(16) ?: break
                if (i + 2 >= hex.length) break

                val type = hex.substring(i + 2, i + 4)
                if (type == "FF" && i + 8 <= hex.length) {
                    // Found manufacturer data, extract company ID
                    val companyId = hex.substring(i + 4, i + 8)
                    return companyId
                }

                // Move to next AD structure
                i += 2 + (length * 2)
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        return null
    }

    /**
     * Generate a hash of the advertising payload for stable identification.
     * Uses first N bytes that typically contain static device info.
     *
     * @param rawData Hex string of raw advertising data
     * @return Hash string, or null if data is insufficient
     */
    private fun generateAdvertisingPayloadHash(rawData: String): String? {
        try {
            val hex = rawData.uppercase().replace(" ", "").replace(":", "")
            if (hex.length < 16) return null

            // Use first 32 hex chars (16 bytes) for stability
            val prefix = hex.take(min(32, hex.length))
            return generateHash(prefix).take(8)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Check if a BLE device name is generic (not useful for identification).
     *
     * @param name The device name to check
     * @return true if the name is generic
     */
    private fun isGenericBleName(name: String): Boolean {
        val genericPatterns = listOf(
            "^BLE.*",
            "^Bluetooth.*",
            "^Unknown.*",
            "^Device.*",
            "^[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}.*",
            "^$"
        )

        val upperName = name.uppercase().trim()
        return genericPatterns.any { pattern ->
            upperName.matches(Regex(pattern, RegexOption.IGNORE_CASE))
        }
    }

    // ========== Private Helper Methods ==========

    /**
     * Generate a throttle key for rapid detection checking.
     * Uses the most stable identifier available.
     */
    private fun generateThrottleKey(detection: Detection): String {
        // Prefer MAC address if available and not randomized
        detection.macAddress?.let { mac ->
            if (!isRandomizedMac(mac)) {
                return "MAC:${mac.uppercase()}"
            }
        }

        // For BLE with randomized MAC, use stable identifier
        if (detection.protocol == DetectionProtocol.BLUETOOTH_LE) {
            generateBleStableIdentifier(detection)?.let { stableId ->
                return "BLE:$stableId"
            }
        }

        // Fall back to composite key
        val dedupeKey = generateDedupeKey(detection)
        return "COMPOSITE:${dedupeKey.compositeKey}"
    }

    /**
     * Check if a MAC address appears to be randomized (locally administered).
     * Randomized MACs have the second-least-significant bit of the first octet set to 1.
     *
     * @param mac The MAC address to check
     * @return true if the MAC appears to be randomized
     */
    private fun isRandomizedMac(mac: String): Boolean {
        try {
            val firstOctet = mac.take(2).toIntOrNull(16) ?: return false
            // Check if locally administered bit (bit 1) is set
            return (firstOctet and 0x02) != 0
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Extract SSID prefix (portion before numbers, dashes, or underscores).
     * Useful for matching SSIDs like "Flock-12345" and "Flock-67890".
     *
     * @param ssid The SSID to extract prefix from
     * @return The prefix, or null if SSID is null/blank
     */
    private fun extractSsidPrefix(ssid: String?): String? {
        if (ssid.isNullOrBlank()) return null

        // Extract prefix before numbers, dashes, or underscores
        val match = Regex("^([A-Za-z]+)").find(ssid.trim())
        return match?.groupValues?.get(1)?.uppercase()
    }

    /**
     * Generate a SHA-256 hash of a string and return first 16 hex characters.
     *
     * @param input The string to hash
     * @return Truncated hex hash string
     */
    private fun generateHash(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            hashBytes.take(8).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback to simple hash if SHA-256 unavailable
            abs(input.hashCode()).toString(16).padStart(16, '0').take(16)
        }
    }
}

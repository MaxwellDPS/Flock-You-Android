package com.flockyou.detection.framework

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Address Tracker
 *
 * Tracks BLE device addresses over time to detect:
 * - Rotating MAC addresses (privacy feature abused by trackers)
 * - Devices following the user across locations
 * - Consistent advertisement payloads from different addresses
 * - Suspicious address rotation patterns
 */
class BleAddressTracker {

    companion object {
        private const val TAG = "BleAddressTracker"

        // Tracking thresholds
        private const val ROTATION_DETECTION_WINDOW_MS = 30 * 60 * 1000L  // 30 minutes
        private const val MIN_ADDRESSES_FOR_ROTATION = 3  // Need at least 3 different MACs
        private const val MAX_TRACKED_DEVICES = 500
        private const val LOCATION_DISTANCE_THRESHOLD_M = 100.0  // 100m = different location
        private const val FOLLOWING_LOCATION_THRESHOLD = 3  // 3+ locations = following
        private const val CLEANUP_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
        private const val MAX_AGE_MS = 2 * 60 * 60 * 1000L  // 2 hours max tracking
    }

    // Track devices by their consistent identifiers (payload hash, service UUIDs)
    private val devicesByPayloadHash = ConcurrentHashMap<String, TrackedDevice>()

    // Track devices by MAC address for quick lookup
    private val devicesByMac = ConcurrentHashMap<String, TrackedDevice>()

    // State flows
    private val _suspiciousDevices = MutableStateFlow<List<SuspiciousDevice>>(emptyList())
    val suspiciousDevices: StateFlow<List<SuspiciousDevice>> = _suspiciousDevices.asStateFlow()

    private val _followingDevices = MutableStateFlow<List<FollowingDevice>>(emptyList())
    val followingDevices: StateFlow<List<FollowingDevice>> = _followingDevices.asStateFlow()

    private var lastCleanupTime = System.currentTimeMillis()

    /**
     * Process a BLE device advertisement
     */
    fun processAdvertisement(
        macAddress: String,
        deviceName: String?,
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<String>,
        rssi: Int,
        latitude: Double?,
        longitude: Double?
    ): BleTrackingAnalysis {
        val now = System.currentTimeMillis()

        // Periodic cleanup
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanup()
            lastCleanupTime = now
        }

        // Classify address type
        val macBytes = parseMacAddress(macAddress)
        val addressCategory = classifyBleAddress(macBytes)

        // Generate payload hash for correlation across MAC rotations
        val payloadHash = generatePayloadHash(
            deviceName,
            manufacturerId,
            manufacturerData,
            serviceUuids
        )

        // Find or create tracked device
        val trackedDevice = findOrCreateDevice(
            macAddress = macAddress,
            payloadHash = payloadHash,
            deviceName = deviceName,
            manufacturerId = manufacturerId,
            serviceUuids = serviceUuids
        )

        // Update device tracking
        trackedDevice.updateSighting(
            macAddress = macAddress,
            rssi = rssi,
            latitude = latitude,
            longitude = longitude,
            timestamp = now
        )

        // Analyze for suspicious behavior
        return analyzeDevice(trackedDevice, addressCategory)
    }

    /**
     * Find existing device or create new one
     */
    private fun findOrCreateDevice(
        macAddress: String,
        payloadHash: String,
        deviceName: String?,
        manufacturerId: Int?,
        serviceUuids: List<String>
    ): TrackedDevice {
        // First check by payload hash (survives MAC rotation)
        val existingByPayload = devicesByPayloadHash[payloadHash]
        if (existingByPayload != null) {
            devicesByMac[macAddress] = existingByPayload
            return existingByPayload
        }

        // Check by MAC address
        val existingByMac = devicesByMac[macAddress]
        if (existingByMac != null) {
            devicesByPayloadHash[payloadHash] = existingByMac
            return existingByMac
        }

        // Create new device
        val newDevice = TrackedDevice(
            primaryPayloadHash = payloadHash,
            deviceName = deviceName,
            manufacturerId = manufacturerId,
            serviceUuids = serviceUuids.toMutableSet()
        )

        // Store in both maps
        devicesByPayloadHash[payloadHash] = newDevice
        devicesByMac[macAddress] = newDevice

        // Enforce max size
        if (devicesByPayloadHash.size > MAX_TRACKED_DEVICES) {
            cleanupOldest()
        }

        return newDevice
    }

    /**
     * Generate a hash of the advertisement payload for correlation
     */
    private fun generatePayloadHash(
        deviceName: String?,
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<String>
    ): String {
        val components = mutableListOf<String>()

        // Include manufacturer ID if present
        manufacturerId?.let { components.add("mfr:$it") }

        // Include first few bytes of manufacturer data (stable portion)
        manufacturerData?.let { data ->
            if (data.size >= 4) {
                // Use first 4 bytes as they're often stable identifiers
                val prefix = data.take(4).toByteArray().toHexString()
                components.add("mfrdata:$prefix")
            }
        }

        // Include service UUIDs (very stable identifier)
        if (serviceUuids.isNotEmpty()) {
            components.add("uuids:${serviceUuids.sorted().joinToString(",")}")
        }

        // Device name can be stable for some trackers
        deviceName?.takeIf { it.isNotEmpty() }?.let {
            components.add("name:$it")
        }

        return if (components.isNotEmpty()) {
            components.sorted().joinToString("|").hashCode().toString(16)
        } else {
            // Fallback - no stable identifiers
            "unknown_${System.currentTimeMillis()}"
        }
    }

    /**
     * Analyze a tracked device for suspicious behavior
     */
    private fun analyzeDevice(
        device: TrackedDevice,
        addressCategory: BleAddressCategory
    ): BleTrackingAnalysis {
        val now = System.currentTimeMillis()

        // Calculate metrics
        val uniqueMacs = device.getUniqueMacsInWindow(ROTATION_DETECTION_WINDOW_MS)
        val isRotating = uniqueMacs >= MIN_ADDRESSES_FOR_ROTATION

        val rotationInterval = device.estimateRotationInterval()
        val uniqueLocations = device.getUniqueLocations(LOCATION_DISTANCE_THRESHOLD_M)
        val isFollowing = uniqueLocations >= FOLLOWING_LOCATION_THRESHOLD

        // Calculate following score (0-1)
        val followingScore = calculateFollowingScore(device)

        // Calculate consistency score (same payload across MACs)
        val consistencyScore = device.getPayloadConsistency()

        // Determine threat indicators
        val indicators = mutableListOf<String>()

        if (isRotating) {
            indicators.add("MAC address rotating ($uniqueMacs addresses in ${ROTATION_DETECTION_WINDOW_MS / 60000} min)")
        }

        if (addressCategory == BleAddressCategory.RANDOM_RESOLVABLE) {
            indicators.add("Using resolvable private address (tracker-like behavior)")
        }

        if (isFollowing) {
            indicators.add("Detected at $uniqueLocations different locations")
        }

        if (rotationInterval != null && rotationInterval < 20 * 60 * 1000L) {
            indicators.add("Fast address rotation (every ${rotationInterval / 60000} min)")
        }

        if (consistencyScore > 0.8f && uniqueMacs > 1) {
            indicators.add("Same payload from multiple MAC addresses (correlation: ${(consistencyScore * 100).toInt()}%)")
        }

        // Check against known tracker patterns
        val matchedSignatures = findMatchingSignatures(device)
        matchedSignatures.forEach { sig ->
            indicators.add("Matches ${sig.name} signature")
        }

        // Determine overall threat level
        val threatLevel = calculateThreatLevel(
            isRotating = isRotating,
            isFollowing = isFollowing,
            addressCategory = addressCategory,
            matchedSignatures = matchedSignatures,
            consistencyScore = consistencyScore
        )

        // Update suspicious devices state if needed
        if (threatLevel.ordinal >= com.flockyou.data.model.ThreatLevel.MEDIUM.ordinal) {
            updateSuspiciousDevices(device, threatLevel, indicators)
        }

        if (isFollowing) {
            updateFollowingDevices(device, uniqueLocations, followingScore)
        }

        return BleTrackingAnalysis(
            payloadHash = device.primaryPayloadHash,
            deviceName = device.deviceName,
            addressCategory = addressCategory,
            isRotatingAddress = isRotating,
            uniqueAddressCount = uniqueMacs,
            rotationIntervalMs = rotationInterval,
            isFollowing = isFollowing,
            uniqueLocationsCount = uniqueLocations,
            followingScore = followingScore,
            payloadConsistencyScore = consistencyScore,
            matchedSignatures = matchedSignatures,
            threatLevel = threatLevel,
            threatIndicators = indicators,
            firstSeen = device.firstSeen,
            lastSeen = device.lastSeen,
            totalSightings = device.sightings.size
        )
    }

    /**
     * Calculate following score based on location pattern
     */
    private fun calculateFollowingScore(device: TrackedDevice): Float {
        val locations = device.getLocationHistory()
        if (locations.size < 2) return 0f

        var score = 0f

        // Factor 1: Number of unique locations
        val uniqueLocations = device.getUniqueLocations(LOCATION_DISTANCE_THRESHOLD_M)
        score += (uniqueLocations.coerceAtMost(10) / 10f) * 0.4f

        // Factor 2: Duration of tracking
        val durationHours = (device.lastSeen - device.firstSeen) / (60 * 60 * 1000f)
        score += (durationHours.coerceAtMost(4f) / 4f) * 0.3f

        // Factor 3: Consistency of detections
        val avgTimeBetweenSightings = if (device.sightings.size > 1) {
            (device.lastSeen - device.firstSeen) / (device.sightings.size - 1f)
        } else 0f
        if (avgTimeBetweenSightings in 1000f..300000f) {  // 1s to 5min = consistent
            score += 0.3f
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * Find tracker signatures that match this device
     */
    private fun findMatchingSignatures(device: TrackedDevice): List<BleTrackerSignature> {
        val matches = mutableListOf<BleTrackerSignature>()

        // Check by manufacturer ID
        device.manufacturerId?.let { mfrId ->
            matches.addAll(TrackerDatabase.findByManufacturerId(mfrId))
        }

        // Check by service UUIDs
        device.serviceUuids.forEach { uuid ->
            matches.addAll(TrackerDatabase.findByServiceUuid(uuid))
        }

        // Check by device name
        device.deviceName?.let { name ->
            matches.addAll(TrackerDatabase.findByDeviceName(name))
        }

        return matches.distinctBy { it.id }
    }

    /**
     * Calculate overall threat level
     */
    private fun calculateThreatLevel(
        isRotating: Boolean,
        isFollowing: Boolean,
        addressCategory: BleAddressCategory,
        matchedSignatures: List<BleTrackerSignature>,
        consistencyScore: Float
    ): com.flockyou.data.model.ThreatLevel {
        var score = 0

        // High impact factors
        if (isFollowing) score += 40
        if (matchedSignatures.any { it.threatLevel == com.flockyou.data.model.ThreatLevel.HIGH }) score += 30

        // Medium impact factors
        if (isRotating) score += 20
        if (addressCategory == BleAddressCategory.RANDOM_RESOLVABLE) score += 15

        // Low impact factors
        if (consistencyScore > 0.8f) score += 10
        if (matchedSignatures.isNotEmpty()) score += 10

        return when {
            score >= 70 -> com.flockyou.data.model.ThreatLevel.CRITICAL
            score >= 50 -> com.flockyou.data.model.ThreatLevel.HIGH
            score >= 30 -> com.flockyou.data.model.ThreatLevel.MEDIUM
            score >= 15 -> com.flockyou.data.model.ThreatLevel.LOW
            else -> com.flockyou.data.model.ThreatLevel.INFO
        }
    }

    /**
     * Update suspicious devices state flow
     */
    private fun updateSuspiciousDevices(
        device: TrackedDevice,
        threatLevel: com.flockyou.data.model.ThreatLevel,
        indicators: List<String>
    ) {
        val suspicious = SuspiciousDevice(
            id = device.primaryPayloadHash,
            deviceName = device.deviceName,
            macAddresses = device.getMacAddresses().toList(),
            manufacturerId = device.manufacturerId,
            serviceUuids = device.serviceUuids.toList(),
            threatLevel = threatLevel,
            indicators = indicators,
            firstSeen = device.firstSeen,
            lastSeen = device.lastSeen,
            sightingCount = device.sightings.size
        )

        val currentList = _suspiciousDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == suspicious.id }

        if (existingIndex >= 0) {
            currentList[existingIndex] = suspicious
        } else {
            currentList.add(0, suspicious)
        }

        // Keep list manageable
        if (currentList.size > 50) {
            currentList.removeAt(currentList.size - 1)
        }

        _suspiciousDevices.value = currentList
    }

    /**
     * Update following devices state flow
     */
    private fun updateFollowingDevices(
        device: TrackedDevice,
        locationCount: Int,
        followingScore: Float
    ) {
        val following = FollowingDevice(
            id = device.primaryPayloadHash,
            deviceName = device.deviceName,
            currentMac = device.getLatestMac(),
            locationCount = locationCount,
            followingScore = followingScore,
            firstSeen = device.firstSeen,
            lastSeen = device.lastSeen,
            locations = device.getLocationHistory()
        )

        val currentList = _followingDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == following.id }

        if (existingIndex >= 0) {
            currentList[existingIndex] = following
        } else {
            currentList.add(0, following)
        }

        // Keep list manageable
        if (currentList.size > 20) {
            currentList.removeAt(currentList.size - 1)
        }

        _followingDevices.value = currentList
    }

    /**
     * Clean up old devices
     */
    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS

        val oldPayloadHashes = devicesByPayloadHash.entries
            .filter { it.value.lastSeen < cutoff }
            .map { it.key }

        oldPayloadHashes.forEach { hash ->
            val device = devicesByPayloadHash.remove(hash)
            device?.getMacAddresses()?.forEach { mac ->
                devicesByMac.remove(mac)
            }
        }

        Log.d(TAG, "Cleaned up ${oldPayloadHashes.size} old devices, ${devicesByPayloadHash.size} remaining")
    }

    /**
     * Remove oldest entries when over capacity
     */
    private fun cleanupOldest() {
        val oldest = devicesByPayloadHash.entries
            .sortedBy { it.value.lastSeen }
            .take(MAX_TRACKED_DEVICES / 4)

        oldest.forEach { entry ->
            devicesByPayloadHash.remove(entry.key)
            entry.value.getMacAddresses().forEach { mac ->
                devicesByMac.remove(mac)
            }
        }
    }

    /**
     * Clear all tracking data
     */
    fun clear() {
        devicesByPayloadHash.clear()
        devicesByMac.clear()
        _suspiciousDevices.value = emptyList()
        _followingDevices.value = emptyList()
    }

    // ============================================================================
    // Inner Classes
    // ============================================================================

    /**
     * Tracked BLE device with sighting history
     */
    private class TrackedDevice(
        val primaryPayloadHash: String,
        var deviceName: String?,
        var manufacturerId: Int?,
        val serviceUuids: MutableSet<String>
    ) {
        val firstSeen: Long = System.currentTimeMillis()
        var lastSeen: Long = firstSeen
        val sightings = mutableListOf<DeviceSighting>()
        private val macAddresses = mutableSetOf<String>()

        fun updateSighting(
            macAddress: String,
            rssi: Int,
            latitude: Double?,
            longitude: Double?,
            timestamp: Long
        ) {
            lastSeen = timestamp
            macAddresses.add(macAddress)

            sightings.add(DeviceSighting(
                macAddress = macAddress,
                rssi = rssi,
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp
            ))

            // Keep sighting history manageable
            if (sightings.size > 200) {
                sightings.removeAt(0)
            }
        }

        fun getMacAddresses(): Set<String> = macAddresses.toSet()

        fun getLatestMac(): String = sightings.lastOrNull()?.macAddress ?: ""

        fun getUniqueMacsInWindow(windowMs: Long): Int {
            val cutoff = System.currentTimeMillis() - windowMs
            return sightings
                .filter { it.timestamp > cutoff }
                .map { it.macAddress }
                .distinct()
                .size
        }

        fun estimateRotationInterval(): Long? {
            if (macAddresses.size < 2) return null

            // Find times when MAC address changed
            val changeTimestamps = mutableListOf<Long>()
            var lastMac: String? = null

            for (sighting in sightings.sortedBy { it.timestamp }) {
                if (lastMac != null && sighting.macAddress != lastMac) {
                    changeTimestamps.add(sighting.timestamp)
                }
                lastMac = sighting.macAddress
            }

            if (changeTimestamps.size < 2) return null

            // Calculate average interval between changes
            var totalInterval = 0L
            for (i in 1 until changeTimestamps.size) {
                totalInterval += changeTimestamps[i] - changeTimestamps[i - 1]
            }

            return totalInterval / (changeTimestamps.size - 1)
        }

        fun getUniqueLocations(thresholdMeters: Double): Int {
            val locationsWithCoords = sightings
                .filter { it.latitude != null && it.longitude != null }
                .map { LocationPoint(it.latitude!!, it.longitude!!, it.timestamp) }

            if (locationsWithCoords.isEmpty()) return 0

            val uniqueLocations = mutableListOf<LocationPoint>()
            for (loc in locationsWithCoords) {
                val isUnique = uniqueLocations.none { existing ->
                    haversineDistance(loc.latitude, loc.longitude, existing.latitude, existing.longitude) < thresholdMeters
                }
                if (isUnique) {
                    uniqueLocations.add(loc)
                }
            }

            return uniqueLocations.size
        }

        fun getLocationHistory(): List<LocationPoint> {
            return sightings
                .filter { it.latitude != null && it.longitude != null }
                .map { LocationPoint(it.latitude!!, it.longitude!!, it.timestamp) }
                .distinctBy { "${it.latitude.toInt()}_${it.longitude.toInt()}" }
        }

        fun getPayloadConsistency(): Float {
            // If we have multiple MACs but same payload hash, consistency is high
            if (macAddresses.size <= 1) return 0f
            // The fact that we're tracking by payload hash means we already detected consistency
            return 0.9f
        }

        private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6371000.0  // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                    kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                    kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            return R * c
        }
    }

    private data class DeviceSighting(
        val macAddress: String,
        val rssi: Int,
        val latitude: Double?,
        val longitude: Double?,
        val timestamp: Long
    )
}

// ============================================================================
// Result Data Classes
// ============================================================================

/**
 * Complete analysis result for a BLE device
 */
data class BleTrackingAnalysis(
    val payloadHash: String,
    val deviceName: String?,
    val addressCategory: BleAddressCategory,

    // Address rotation analysis
    val isRotatingAddress: Boolean,
    val uniqueAddressCount: Int,
    val rotationIntervalMs: Long?,

    // Following analysis
    val isFollowing: Boolean,
    val uniqueLocationsCount: Int,
    val followingScore: Float,

    // Correlation analysis
    val payloadConsistencyScore: Float,

    // Signature matching
    val matchedSignatures: List<BleTrackerSignature>,

    // Threat assessment
    val threatLevel: com.flockyou.data.model.ThreatLevel,
    val threatIndicators: List<String>,

    // Timing
    val firstSeen: Long,
    val lastSeen: Long,
    val totalSightings: Int
)

/**
 * Suspicious device summary for UI
 */
data class SuspiciousDevice(
    val id: String,
    val deviceName: String?,
    val macAddresses: List<String>,
    val manufacturerId: Int?,
    val serviceUuids: List<String>,
    val threatLevel: com.flockyou.data.model.ThreatLevel,
    val indicators: List<String>,
    val firstSeen: Long,
    val lastSeen: Long,
    val sightingCount: Int
)

/**
 * Device that appears to be following the user
 */
data class FollowingDevice(
    val id: String,
    val deviceName: String?,
    val currentMac: String,
    val locationCount: Int,
    val followingScore: Float,
    val firstSeen: Long,
    val lastSeen: Long,
    val locations: List<LocationPoint>
)

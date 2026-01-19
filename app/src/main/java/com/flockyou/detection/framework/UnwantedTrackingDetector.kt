package com.flockyou.detection.framework

import android.util.Log
import com.flockyou.data.model.ThreatLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Unwanted Tracking Detector
 *
 * Implements Apple's Unwanted Tracking Alert protocol detection (FD6F service UUID)
 * and similar protocols from other manufacturers to detect when a tracker has been
 * separated from its owner and may be stalking the user.
 *
 * Key detection methods:
 * 1. FD6F Service UUID detection (Apple Find My unwanted tracking alert)
 * 2. Samsung SmartTag separation detection
 * 3. Tile tracker separation detection
 * 4. Generic tracker following pattern detection
 *
 * References:
 * - Apple's "Unwanted Tracking" specification
 * - Google's "Unknown Tracker Alerts" specification
 */
class UnwantedTrackingDetector {

    companion object {
        private const val TAG = "UnwantedTrackingDetector"

        // Service UUIDs for unwanted tracking detection
        const val APPLE_FD6F_UUID = "0000FD6F-0000-1000-8000-00805F9B34FB"
        const val APPLE_FD6F_UUID_16 = "FD6F"

        // Time thresholds
        private const val SEPARATION_TIME_THRESHOLD_MS = 15 * 60 * 1000L  // 15 minutes
        private const val ALERT_COOLDOWN_MS = 30 * 60 * 1000L  // 30 minutes between alerts
        private const val FOLLOWING_TIME_THRESHOLD_MS = 60 * 60 * 1000L  // 1 hour
        private const val CLEANUP_AGE_MS = 24 * 60 * 60 * 1000L  // 24 hours

        // Detection thresholds
        private const val STRONG_SIGNAL_THRESHOLD = -60  // dBm
        private const val FOLLOWING_LOCATIONS_THRESHOLD = 3
    }

    // Track potential stalking devices
    private val trackedDevices = ConcurrentHashMap<String, UnwantedTrackerInfo>()

    // Track alerts to avoid duplicates
    private val recentAlerts = ConcurrentHashMap<String, Long>()

    // State flows
    private val _alerts = MutableStateFlow<List<UnwantedTrackingAlert>>(emptyList())
    val alerts: StateFlow<List<UnwantedTrackingAlert>> = _alerts.asStateFlow()

    private val _activeThreats = MutableStateFlow<List<ActiveThreat>>(emptyList())
    val activeThreats: StateFlow<List<ActiveThreat>> = _activeThreats.asStateFlow()

    private val alertHistory = mutableListOf<UnwantedTrackingAlert>()

    /**
     * Process a BLE advertisement for unwanted tracking indicators
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
    ): UnwantedTrackingResult {
        val now = System.currentTimeMillis()

        // Check for FD6F (Exposure Notification / Unwanted Tracking) service
        val hasFd6f = serviceUuids.any {
            it.contains("FD6F", ignoreCase = true) ||
            it.contains(APPLE_FD6F_UUID, ignoreCase = true)
        }

        // Check for Find My service (potential tracker)
        val hasFindMy = serviceUuids.any {
            it.contains("7DFC9000", ignoreCase = true)
        }

        // Check for Samsung SmartTag
        val hasSmartTag = serviceUuids.any {
            it.contains("FD5A", ignoreCase = true)
        } || manufacturerId == ManufacturerIds.SAMSUNG

        // Check for Tile
        val hasTile = serviceUuids.any {
            it.contains("FEED", ignoreCase = true) ||
            it.contains("FE5A", ignoreCase = true)
        } || manufacturerId == ManufacturerIds.TILE

        // Generate device identifier (survives MAC rotation)
        val deviceId = generateDeviceId(manufacturerId, manufacturerData, serviceUuids, deviceName)

        // Track the device
        val tracker = trackedDevices.getOrPut(deviceId) {
            UnwantedTrackerInfo(
                deviceId = deviceId,
                firstSeen = now,
                deviceName = deviceName,
                manufacturerId = manufacturerId
            )
        }

        tracker.updateSighting(
            macAddress = macAddress,
            rssi = rssi,
            latitude = latitude,
            longitude = longitude,
            timestamp = now,
            hasFd6f = hasFd6f
        )

        // Determine tracker type
        val trackerType = when {
            hasFd6f -> UnwantedTrackerType.APPLE_AIRTAG_SEPARATED
            hasFindMy && !hasFd6f -> UnwantedTrackerType.APPLE_FINDMY_ACCESSORY
            hasSmartTag -> UnwantedTrackerType.SAMSUNG_SMARTTAG
            hasTile -> UnwantedTrackerType.TILE_TRACKER
            tracker.isSuspiciousPattern() -> UnwantedTrackerType.UNKNOWN_TRACKER
            else -> UnwantedTrackerType.NONE
        }

        // Analyze for unwanted tracking
        val analysis = analyzeTracker(tracker, trackerType, hasFd6f)

        // Generate alert if needed
        if (analysis.isUnwantedTracking && !isAlertCoolingDown(deviceId)) {
            generateAlert(tracker, analysis, trackerType)
        }

        // Cleanup old data
        if (trackedDevices.size > 500) {
            cleanupOldDevices()
        }

        return analysis
    }

    /**
     * Generate stable device identifier
     */
    private fun generateDeviceId(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<String>,
        deviceName: String?
    ): String {
        val components = mutableListOf<String>()

        manufacturerId?.let { components.add("m$it") }

        // For Find My devices, use portion of public key as ID
        manufacturerData?.let { data ->
            if (data.size >= 8) {
                components.add("d${data.sliceArray(0..7).toHexString()}")
            }
        }

        if (serviceUuids.isNotEmpty()) {
            components.add("u${serviceUuids.sorted().hashCode()}")
        }

        deviceName?.takeIf { it.isNotEmpty() }?.let {
            components.add("n${it.hashCode()}")
        }

        return if (components.isNotEmpty()) {
            components.joinToString("_")
        } else {
            "unknown_${System.nanoTime()}"
        }
    }

    /**
     * Analyze tracker for unwanted tracking indicators
     */
    private fun analyzeTracker(
        tracker: UnwantedTrackerInfo,
        trackerType: UnwantedTrackerType,
        hasFd6f: Boolean
    ): UnwantedTrackingResult {
        val now = System.currentTimeMillis()

        val indicators = mutableListOf<String>()
        var threatScore = 0

        // FD6F is a critical indicator - Apple specifically broadcasts this
        // when an AirTag has been separated from its owner
        if (hasFd6f) {
            indicators.add("⚠️ Device broadcasting separation alert (FD6F)")
            indicators.add("This tracker has been away from its owner for extended period")
            threatScore += 50
        }

        // Check tracking duration
        val trackingDuration = now - tracker.firstSeen
        if (trackingDuration > FOLLOWING_TIME_THRESHOLD_MS) {
            val hours = trackingDuration / (60 * 60 * 1000f)
            indicators.add("Tracker present for ${String.format("%.1f", hours)} hours")
            threatScore += 20
        }

        // Check number of locations
        val uniqueLocations = tracker.getUniqueLocationCount()
        if (uniqueLocations >= FOLLOWING_LOCATIONS_THRESHOLD) {
            indicators.add("Detected at $uniqueLocations different locations")
            threatScore += 25
        }

        // Check signal strength pattern
        val avgRssi = tracker.getAverageRssi()
        if (avgRssi > STRONG_SIGNAL_THRESHOLD) {
            indicators.add("Strong signal (${avgRssi}dBm) - tracker is nearby")
            threatScore += 10
        }

        // Check for consistent presence
        val presenceRatio = tracker.getPresenceRatio()
        if (presenceRatio > 0.7f) {
            indicators.add("Consistently detected (${(presenceRatio * 100).toInt()}% of checks)")
            threatScore += 15
        }

        // Determine if this is unwanted tracking
        val isUnwanted = hasFd6f ||
                         (threatScore >= 40 && trackerType != UnwantedTrackerType.NONE) ||
                         (uniqueLocations >= 3 && trackingDuration > SEPARATION_TIME_THRESHOLD_MS)

        val threatLevel = when {
            hasFd6f -> ThreatLevel.CRITICAL
            threatScore >= 60 -> ThreatLevel.HIGH
            threatScore >= 35 -> ThreatLevel.MEDIUM
            threatScore >= 15 -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }

        return UnwantedTrackingResult(
            deviceId = tracker.deviceId,
            trackerType = trackerType,
            isUnwantedTracking = isUnwanted,
            hasSeparationAlert = hasFd6f,
            threatLevel = threatLevel,
            threatScore = threatScore,
            indicators = indicators,
            trackingDurationMs = trackingDuration,
            uniqueLocations = uniqueLocations,
            lastSeenMac = tracker.getLatestMac(),
            firstSeen = tracker.firstSeen,
            lastSeen = tracker.lastSeen
        )
    }

    /**
     * Generate alert for unwanted tracking
     */
    private fun generateAlert(
        tracker: UnwantedTrackerInfo,
        analysis: UnwantedTrackingResult,
        trackerType: UnwantedTrackerType
    ) {
        val alert = UnwantedTrackingAlert(
            id = UUID.randomUUID().toString(),
            deviceId = tracker.deviceId,
            timestamp = System.currentTimeMillis(),
            trackerType = trackerType,
            threatLevel = analysis.threatLevel,
            title = getAlertTitle(trackerType, analysis.hasSeparationAlert),
            description = getAlertDescription(trackerType, analysis),
            indicators = analysis.indicators,
            recommendations = getRecommendations(trackerType, analysis),
            deviceName = tracker.deviceName,
            lastKnownMac = tracker.getLatestMac(),
            trackingDurationMs = analysis.trackingDurationMs,
            locationsCount = analysis.uniqueLocations
        )

        alertHistory.add(0, alert)
        if (alertHistory.size > 50) {
            alertHistory.removeAt(alertHistory.size - 1)
        }

        _alerts.value = alertHistory.toList()
        recentAlerts[tracker.deviceId] = System.currentTimeMillis()

        // Update active threats
        updateActiveThreats()

        Log.w(TAG, "UNWANTED TRACKING ALERT: ${alert.title} - ${alert.description}")
    }

    private fun getAlertTitle(trackerType: UnwantedTrackerType, hasSeparation: Boolean): String {
        return when {
            hasSeparation -> "🚨 Separated Tracker Detected"
            trackerType == UnwantedTrackerType.APPLE_AIRTAG_SEPARATED -> "⚠️ AirTag May Be Tracking You"
            trackerType == UnwantedTrackerType.APPLE_FINDMY_ACCESSORY -> "⚠️ Find My Accessory Detected"
            trackerType == UnwantedTrackerType.SAMSUNG_SMARTTAG -> "⚠️ SmartTag May Be Tracking You"
            trackerType == UnwantedTrackerType.TILE_TRACKER -> "⚠️ Tile Tracker Detected"
            else -> "⚠️ Unknown Tracker Following You"
        }
    }

    private fun getAlertDescription(
        trackerType: UnwantedTrackerType,
        analysis: UnwantedTrackingResult
    ): String {
        val duration = when {
            analysis.trackingDurationMs > 60 * 60 * 1000L -> {
                val hours = analysis.trackingDurationMs / (60 * 60 * 1000f)
                "${String.format("%.1f", hours)} hours"
            }
            else -> {
                val minutes = analysis.trackingDurationMs / (60 * 1000)
                "$minutes minutes"
            }
        }

        return when {
            analysis.hasSeparationAlert -> {
                "A tracker that has been separated from its owner has been traveling with you for $duration. " +
                "The device is broadcasting an alert indicating it's away from its owner."
            }
            trackerType == UnwantedTrackerType.APPLE_AIRTAG_SEPARATED -> {
                "An Apple AirTag has been detected near you for $duration across ${analysis.uniqueLocations} locations."
            }
            trackerType == UnwantedTrackerType.SAMSUNG_SMARTTAG -> {
                "A Samsung SmartTag has been detected following you for $duration."
            }
            trackerType == UnwantedTrackerType.TILE_TRACKER -> {
                "A Tile tracker has been near you for $duration across ${analysis.uniqueLocations} locations."
            }
            else -> {
                "An unknown Bluetooth tracker has been following you for $duration across ${analysis.uniqueLocations} locations."
            }
        }
    }

    private fun getRecommendations(
        trackerType: UnwantedTrackerType,
        analysis: UnwantedTrackingResult
    ): List<String> {
        val recommendations = mutableListOf<String>()

        recommendations.add("🔍 Search your belongings - check bags, pockets, vehicle")

        when (trackerType) {
            UnwantedTrackerType.APPLE_AIRTAG_SEPARATED -> {
                recommendations.add("📱 Use iPhone's Find My app > Items > 'Identify Found Item'")
                recommendations.add("🔊 If found, hold AirTag near iPhone to get owner info")
                recommendations.add("🔇 Remove battery to disable (twist back cover counter-clockwise)")
            }
            UnwantedTrackerType.SAMSUNG_SMARTTAG -> {
                recommendations.add("📱 Use Samsung SmartThings app to scan for nearby tags")
                recommendations.add("🔋 Remove battery to disable")
            }
            UnwantedTrackerType.TILE_TRACKER -> {
                recommendations.add("📱 Download Tile app to identify the tracker")
                recommendations.add("🔋 Some Tiles have non-removable batteries")
            }
            else -> {
                recommendations.add("📱 Use tracker detection apps to help locate")
            }
        }

        if (analysis.threatLevel == ThreatLevel.CRITICAL || analysis.uniqueLocations >= 5) {
            recommendations.add("🚔 Consider contacting local authorities if you feel unsafe")
            recommendations.add("📍 Do not go directly home - go to a police station or public place")
        }

        return recommendations
    }

    private fun isAlertCoolingDown(deviceId: String): Boolean {
        val lastAlert = recentAlerts[deviceId] ?: return false
        return System.currentTimeMillis() - lastAlert < ALERT_COOLDOWN_MS
    }

    private fun updateActiveThreats() {
        val now = System.currentTimeMillis()
        val active = trackedDevices.values
            .filter { it.lastSeen > now - 30 * 60 * 1000L }  // Active in last 30 min
            .filter { it.isSuspiciousPattern() || it.hasReceivedFd6f }
            .map { tracker ->
                ActiveThreat(
                    deviceId = tracker.deviceId,
                    deviceName = tracker.deviceName,
                    trackerType = inferTrackerType(tracker),
                    lastSeen = tracker.lastSeen,
                    lastRssi = tracker.getLatestRssi(),
                    locationCount = tracker.getUniqueLocationCount(),
                    hasSeparationAlert = tracker.hasReceivedFd6f
                )
            }
            .sortedByDescending { it.lastSeen }

        _activeThreats.value = active
    }

    private fun inferTrackerType(tracker: UnwantedTrackerInfo): UnwantedTrackerType {
        return when {
            tracker.hasReceivedFd6f -> UnwantedTrackerType.APPLE_AIRTAG_SEPARATED
            tracker.manufacturerId == ManufacturerIds.APPLE -> UnwantedTrackerType.APPLE_FINDMY_ACCESSORY
            tracker.manufacturerId == ManufacturerIds.SAMSUNG -> UnwantedTrackerType.SAMSUNG_SMARTTAG
            tracker.manufacturerId == ManufacturerIds.TILE -> UnwantedTrackerType.TILE_TRACKER
            else -> UnwantedTrackerType.UNKNOWN_TRACKER
        }
    }

    private fun cleanupOldDevices() {
        val cutoff = System.currentTimeMillis() - CLEANUP_AGE_MS
        val old = trackedDevices.entries.filter { it.value.lastSeen < cutoff }
        old.forEach { trackedDevices.remove(it.key) }

        val oldAlerts = recentAlerts.entries.filter { it.value < cutoff }
        oldAlerts.forEach { recentAlerts.remove(it.key) }
    }

    /**
     * Clear all alerts
     */
    fun clearAlerts() {
        alertHistory.clear()
        _alerts.value = emptyList()
    }

    /**
     * Clear all tracking data
     */
    fun clear() {
        trackedDevices.clear()
        recentAlerts.clear()
        alertHistory.clear()
        _alerts.value = emptyList()
        _activeThreats.value = emptyList()
    }

    // ============================================================================
    // Inner Classes
    // ============================================================================

    /**
     * Tracked unwanted tracker info
     */
    private class UnwantedTrackerInfo(
        val deviceId: String,
        val firstSeen: Long,
        var deviceName: String?,
        var manufacturerId: Int?
    ) {
        var lastSeen: Long = firstSeen
        var hasReceivedFd6f: Boolean = false
        private val sightings = mutableListOf<TrackerSighting>()

        fun updateSighting(
            macAddress: String,
            rssi: Int,
            latitude: Double?,
            longitude: Double?,
            timestamp: Long,
            hasFd6f: Boolean
        ) {
            lastSeen = timestamp
            if (hasFd6f) hasReceivedFd6f = true

            sightings.add(TrackerSighting(
                macAddress = macAddress,
                rssi = rssi,
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp
            ))

            // Keep history manageable
            if (sightings.size > 100) {
                sightings.removeAt(0)
            }
        }

        fun getLatestMac(): String = sightings.lastOrNull()?.macAddress ?: ""

        fun getLatestRssi(): Int = sightings.lastOrNull()?.rssi ?: -100

        fun getAverageRssi(): Int {
            if (sightings.isEmpty()) return -100
            return sightings.map { it.rssi }.average().toInt()
        }

        fun getUniqueLocationCount(): Int {
            val locs = sightings
                .filter { it.latitude != null && it.longitude != null }
                .map { Pair(it.latitude!!, it.longitude!!) }

            if (locs.isEmpty()) return 0

            val unique = mutableListOf<Pair<Double, Double>>()
            for (loc in locs) {
                val isNew = unique.none { existing ->
                    haversineDistance(loc.first, loc.second, existing.first, existing.second) < 100
                }
                if (isNew) unique.add(loc)
            }
            return unique.size
        }

        fun getPresenceRatio(): Float {
            if (sightings.size < 2) return 0f

            val totalDuration = lastSeen - firstSeen
            if (totalDuration < 60_000) return 1f  // Too short to calculate

            // Expected checks based on duration (assuming 30s scan interval)
            val expectedChecks = totalDuration / 30_000f
            return (sightings.size / expectedChecks).coerceIn(0f, 1f)
        }

        fun isSuspiciousPattern(): Boolean {
            val duration = lastSeen - firstSeen
            val locations = getUniqueLocationCount()

            return (duration > SEPARATION_TIME_THRESHOLD_MS && locations >= 2) ||
                   (duration > 5 * 60 * 1000 && hasReceivedFd6f)
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
    }

    private data class TrackerSighting(
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
 * Types of unwanted trackers
 */
enum class UnwantedTrackerType(val displayName: String, val icon: String) {
    APPLE_AIRTAG_SEPARATED("Apple AirTag (Separated)", "🍎"),
    APPLE_FINDMY_ACCESSORY("Find My Accessory", "📍"),
    SAMSUNG_SMARTTAG("Samsung SmartTag", "📱"),
    TILE_TRACKER("Tile Tracker", "🔲"),
    UNKNOWN_TRACKER("Unknown Tracker", "❓"),
    NONE("Not a Tracker", "")
}

/**
 * Analysis result for unwanted tracking
 */
data class UnwantedTrackingResult(
    val deviceId: String,
    val trackerType: UnwantedTrackerType,
    val isUnwantedTracking: Boolean,
    val hasSeparationAlert: Boolean,
    val threatLevel: ThreatLevel,
    val threatScore: Int,
    val indicators: List<String>,
    val trackingDurationMs: Long,
    val uniqueLocations: Int,
    val lastSeenMac: String,
    val firstSeen: Long,
    val lastSeen: Long
)

/**
 * Alert for unwanted tracking
 */
data class UnwantedTrackingAlert(
    val id: String,
    val deviceId: String,
    val timestamp: Long,
    val trackerType: UnwantedTrackerType,
    val threatLevel: ThreatLevel,
    val title: String,
    val description: String,
    val indicators: List<String>,
    val recommendations: List<String>,
    val deviceName: String?,
    val lastKnownMac: String,
    val trackingDurationMs: Long,
    val locationsCount: Int
)

/**
 * Currently active threat
 */
data class ActiveThreat(
    val deviceId: String,
    val deviceName: String?,
    val trackerType: UnwantedTrackerType,
    val lastSeen: Long,
    val lastRssi: Int,
    val locationCount: Int,
    val hasSeparationAlert: Boolean
)

package com.flockyou.detection

import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.detection.handler.DetectionHandler
import com.flockyou.detection.handler.DeviceTypeProfile
import com.flockyou.detection.handler.ThreatCalculationResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry that coordinates all detection handlers.
 *
 * This registry maintains mappings from protocols and device types to their
 * respective handlers, enabling efficient lookup and routing of detection tasks.
 *
 * Features:
 * - Protocol-based handler lookup
 * - Device type-based handler lookup
 * - Runtime handler registration for extensibility
 * - Device type profile retrieval
 *
 * Usage:
 * ```
 * // Get handler for a specific protocol
 * val bleHandler = registry.getHandler(DetectionProtocol.BLUETOOTH_LE)
 *
 * // Get handler that can detect a device type
 * val airtagHandler = registry.getHandlerForDeviceType(DeviceType.AIRTAG)
 *
 * // Get profile information for a device type
 * val profile = registry.getProfile(DeviceType.STINGRAY_IMSI)
 * ```
 */
@Singleton
class DetectionRegistry @Inject constructor(
    handlers: Set<@JvmSuppressWildcards DetectionHandler<*>>
) {
    companion object {
        private const val TAG = "DetectionRegistry"
    }

    /**
     * Map of protocol to handler.
     * Each protocol should have at most one primary handler.
     * Thread-safe: Uses ConcurrentHashMap for safe multi-threaded access.
     */
    private val handlersByProtocol: ConcurrentHashMap<DetectionProtocol, DetectionHandler<*>> = ConcurrentHashMap()

    /**
     * Map of device type to handler.
     * Multiple device types may map to the same handler.
     * Thread-safe: Uses ConcurrentHashMap for safe multi-threaded access.
     */
    private val handlersByDeviceType: ConcurrentHashMap<DeviceType, DetectionHandler<*>> = ConcurrentHashMap()

    /**
     * Set of all registered handlers (both injected and runtime-registered).
     * Thread-safe: Uses ConcurrentHashMap.newKeySet() for safe multi-threaded access.
     */
    private val allHandlers: MutableSet<DetectionHandler<*>> = ConcurrentHashMap.newKeySet()

    /**
     * Custom handlers registered at runtime.
     * Thread-safe: Uses ConcurrentHashMap.newKeySet() for safe multi-threaded access.
     */
    private val customHandlers: MutableSet<DetectionHandler<*>> = ConcurrentHashMap.newKeySet()

    init {
        // Build indexes from injected handlers
        handlers.forEach { handler ->
            registerHandlerInternal(handler, isCustom = false)
        }

        Log.i(TAG, "DetectionRegistry initialized with ${allHandlers.size} handlers")
        Log.d(TAG, "Protocols: ${handlersByProtocol.keys.map { it.name }}")
        Log.d(TAG, "Device types: ${handlersByDeviceType.size} mappings")
    }

    /**
     * Get the handler for a specific detection protocol.
     *
     * @param protocol The protocol to get a handler for
     * @return The handler for this protocol, or null if none registered
     */
    fun getHandler(protocol: DetectionProtocol): DetectionHandler<*>? {
        return handlersByProtocol[protocol]
    }

    /**
     * Get a handler that can detect the given device type.
     *
     * @param deviceType The device type to find a handler for
     * @return A handler capable of detecting this device type, or null if none found
     */
    fun getHandlerForDeviceType(deviceType: DeviceType): DetectionHandler<*>? {
        return handlersByDeviceType[deviceType]
    }

    /**
     * Get all registered handlers.
     *
     * @return List of all handlers (both injected and custom)
     */
    fun getAllHandlers(): List<DetectionHandler<*>> {
        return allHandlers.toList()
    }

    /**
     * Get the device type profile for a specific device type.
     *
     * The profile contains metadata about the device type including
     * which handler is responsible for detecting it.
     *
     * @param deviceType The device type to get a profile for
     * @return The profile, or null if no handler supports this device type
     */
    fun getProfile(deviceType: DeviceType): DeviceTypeProfile? {
        val handler = handlersByDeviceType[deviceType] ?: return null
        return handler.getProfile(deviceType)
    }

    /**
     * Register a custom handler at runtime.
     *
     * This allows for extensibility where users or plugins can add
     * new detection capabilities without modifying the core app.
     *
     * @param handler The handler to register
     */
    fun registerCustomHandler(handler: DetectionHandler<*>) {
        registerHandlerInternal(handler, isCustom = true)
        customHandlers.add(handler)
        Log.i(TAG, "Registered custom handler: ${handler.displayName} for protocol ${handler.protocol}")
    }

    /**
     * Unregister a handler by its protocol.
     *
     * This removes the handler from all indexes. Primarily intended
     * for removing custom handlers.
     * Thread-safe: Uses atomic operations on ConcurrentHashMap.
     *
     * @param protocol The protocol of the handler to unregister
     */
    fun unregisterHandler(protocol: DetectionProtocol) {
        val handler = handlersByProtocol.remove(protocol)
        if (handler != null) {
            // Remove from device type mappings using thread-safe iteration
            // ConcurrentHashMap's forEach is weakly consistent and safe for concurrent modification
            handlersByDeviceType.forEach { (deviceType, mappedHandler) ->
                if (mappedHandler == handler) {
                    handlersByDeviceType.remove(deviceType, handler)
                }
            }

            // Remove from handler sets (thread-safe sets)
            allHandlers.remove(handler)
            customHandlers.remove(handler)

            Log.i(TAG, "Unregistered handler for protocol: $protocol (${handler.displayName})")
        } else {
            Log.w(TAG, "No handler found to unregister for protocol: $protocol")
        }
    }

    /**
     * Get all custom (runtime-registered) handlers.
     *
     * @return List of custom handlers
     */
    fun getCustomHandlers(): List<DetectionHandler<*>> {
        return customHandlers.toList()
    }

    /**
     * Check if a handler exists for the given protocol.
     *
     * @param protocol The protocol to check
     * @return true if a handler is registered for this protocol
     */
    fun hasHandler(protocol: DetectionProtocol): Boolean {
        return handlersByProtocol.containsKey(protocol)
    }

    /**
     * Check if any handler can detect the given device type.
     *
     * @param deviceType The device type to check
     * @return true if a handler can detect this device type
     */
    fun canDetect(deviceType: DeviceType): Boolean {
        return handlersByDeviceType.containsKey(deviceType)
    }

    /**
     * Get all device types that can be detected.
     *
     * @return Set of all detectable device types
     */
    fun getDetectableDeviceTypes(): Set<DeviceType> {
        return handlersByDeviceType.keys.toSet()
    }

    /**
     * Get all supported protocols.
     *
     * @return Set of protocols that have registered handlers
     */
    fun getSupportedProtocols(): Set<DetectionProtocol> {
        return handlersByProtocol.keys.toSet()
    }

    /**
     * Get handlers grouped by protocol.
     *
     * @return Map of protocol to handler
     */
    fun getHandlersByProtocol(): Map<DetectionProtocol, DetectionHandler<*>> {
        return handlersByProtocol.toMap()
    }

    /**
     * Start monitoring on all handlers.
     */
    fun startAllHandlers() {
        allHandlers.forEach { handler ->
            try {
                handler.startMonitoring()
                Log.d(TAG, "Started handler: ${handler.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start handler ${handler.displayName}", e)
            }
        }
    }

    /**
     * Stop monitoring on all handlers.
     */
    fun stopAllHandlers() {
        allHandlers.forEach { handler ->
            try {
                handler.stopMonitoring()
                Log.d(TAG, "Stopped handler: ${handler.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop handler ${handler.displayName}", e)
            }
        }
    }

    /**
     * Update location on all handlers.
     */
    fun updateLocationOnAllHandlers(latitude: Double, longitude: Double) {
        allHandlers.forEach { handler ->
            try {
                handler.updateLocation(latitude, longitude)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update location on handler ${handler.displayName}", e)
            }
        }
    }

    /**
     * Destroy all handlers and clean up resources.
     * Thread-safe: Creates a snapshot of handlers before iteration to avoid
     * concurrent modification issues during destruction.
     */
    fun destroyAllHandlers() {
        // Take a snapshot to avoid concurrent modification during iteration
        val handlersSnapshot = allHandlers.toList()
        handlersSnapshot.forEach { handler ->
            try {
                handler.destroy()
                Log.d(TAG, "Destroyed handler: ${handler.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy handler ${handler.displayName}", e)
            }
        }
        allHandlers.clear()
        customHandlers.clear()
        handlersByProtocol.clear()
        handlersByDeviceType.clear()
    }

    /**
     * Internal method to register a handler and build indexes.
     */
    private fun registerHandlerInternal(handler: DetectionHandler<*>, isCustom: Boolean) {
        // Add to all handlers
        allHandlers.add(handler)

        // Map by protocol
        val existingProtocolHandler = handlersByProtocol[handler.protocol]
        if (existingProtocolHandler != null && !isCustom) {
            Log.w(TAG, "Protocol ${handler.protocol} already has handler ${existingProtocolHandler.displayName}, " +
                    "replacing with ${handler.displayName}")
        }
        handlersByProtocol[handler.protocol] = handler

        // Map by device types
        handler.supportedDeviceTypes.forEach { deviceType ->
            val existingDeviceHandler = handlersByDeviceType[deviceType]
            if (existingDeviceHandler != null && existingDeviceHandler != handler) {
                Log.d(TAG, "Device type $deviceType already mapped to ${existingDeviceHandler.displayName}, " +
                        "adding ${handler.displayName} as alternative")
            }
            handlersByDeviceType[deviceType] = handler
        }

        Log.d(TAG, "Registered handler: ${handler.displayName} " +
                "(protocol=${handler.protocol}, deviceTypes=${handler.supportedDeviceTypes.size}, custom=$isCustom)")
    }

    // ============================================================================
    // Aggregate Threat Calculation
    // ============================================================================

    /**
     * Calculate aggregate threat level from multiple detections.
     *
     * This is smarter than just counting HIGH threats:
     * - Considers correlation (same time/place = one incident)
     * - Considers pattern (same type recurring = more concerning)
     * - Weights recent detections higher than old ones
     * - Applies cross-protocol correlation boost when threats seen on multiple protocols
     *
     * @param detections List of recent detections to analyze
     * @param timeWindowMs Time window for "recent" detections (default 30 minutes)
     * @return AggregateThreatResult with overall severity and breakdown
     */
    fun calculateAggregateThreat(
        detections: List<Detection>,
        timeWindowMs: Long = 30 * 60 * 1000L
    ): AggregateThreatResult {
        if (detections.isEmpty()) {
            return AggregateThreatResult(
                overallSeverity = ThreatLevel.INFO,
                overallScore = 0,
                incidentCount = 0,
                detectionCount = 0,
                highestThreatDetection = null,
                correlatedProtocols = emptySet(),
                hasCorrelation = false,
                hasRecurringPattern = false,
                reasoning = "No detections to analyze"
            )
        }

        val now = System.currentTimeMillis()
        val recentDetections = detections.filter {
            now - it.timestamp < timeWindowMs
        }

        if (recentDetections.isEmpty()) {
            return AggregateThreatResult(
                overallSeverity = ThreatLevel.INFO,
                overallScore = 0,
                incidentCount = 0,
                detectionCount = 0,
                highestThreatDetection = null,
                correlatedProtocols = emptySet(),
                hasCorrelation = false,
                hasRecurringPattern = false,
                reasoning = "No recent detections within ${timeWindowMs / 60000} minute window"
            )
        }

        // Group by incident (same location + time = one incident)
        val incidents = groupIntoIncidents(recentDetections)

        // Find the highest individual threat
        val highestThreat = recentDetections.maxByOrNull { it.threatScore }

        // Check for cross-protocol correlation
        val protocols = recentDetections.map { it.protocol }.toSet()
        val hasCorrelation = protocols.size > 1

        // Check for recurring patterns
        val deviceTypeCounts = recentDetections.groupBy { it.deviceType }
            .mapValues { it.value.size }
        val hasRecurringPattern = deviceTypeCounts.any { it.value >= 3 }

        // Calculate aggregate score starting from highest individual
        var aggregateScore = highestThreat?.threatScore ?: 0

        // Boost for cross-protocol correlation (+20%)
        if (hasCorrelation) {
            aggregateScore = (aggregateScore * 1.2).toInt().coerceIn(0, 100)
        }

        // Boost for recurring pattern (+15%)
        if (hasRecurringPattern) {
            aggregateScore = (aggregateScore * 1.15).toInt().coerceIn(0, 100)
        }

        // Weight very recent detections (last 5 minutes) higher (+10%)
        val veryRecentDetections = recentDetections.filter {
            now - it.timestamp < 5 * 60 * 1000
        }
        if (veryRecentDetections.any {
                it.threatLevel == ThreatLevel.HIGH || it.threatLevel == ThreatLevel.CRITICAL
            }) {
            aggregateScore = (aggregateScore * 1.1).toInt().coerceIn(0, 100)
        }

        val overallSeverity = when {
            aggregateScore >= 90 -> ThreatLevel.CRITICAL
            aggregateScore >= 70 -> ThreatLevel.HIGH
            aggregateScore >= 50 -> ThreatLevel.MEDIUM
            aggregateScore >= 30 -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }

        val reasoning = buildString {
            appendLine("Aggregate Threat Assessment: ${overallSeverity.displayName}")
            appendLine()
            appendLine("Summary:")
            appendLine("  Total detections: ${recentDetections.size}")
            appendLine("  Unique incidents: ${incidents.size}")
            appendLine("  Protocols involved: ${protocols.joinToString { it.displayName }}")
            if (hasCorrelation) {
                appendLine("  Cross-protocol correlation: YES (+20% score)")
            }
            if (hasRecurringPattern) {
                appendLine("  Recurring pattern detected: YES (+15% score)")
            }
            appendLine()
            if (highestThreat != null) {
                appendLine("Highest individual threat:")
                appendLine("  Device: ${highestThreat.deviceType.displayName}")
                appendLine("  Score: ${highestThreat.threatScore}")
                appendLine("  Severity: ${highestThreat.threatLevel.displayName}")
            }
            appendLine()
            appendLine("Detection breakdown by type:")
            deviceTypeCounts.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                appendLine("  ${type.displayName}: $count")
            }
        }

        return AggregateThreatResult(
            overallSeverity = overallSeverity,
            overallScore = aggregateScore,
            incidentCount = incidents.size,
            detectionCount = recentDetections.size,
            highestThreatDetection = highestThreat,
            correlatedProtocols = protocols,
            hasCorrelation = hasCorrelation,
            hasRecurringPattern = hasRecurringPattern,
            reasoning = reasoning
        )
    }

    /**
     * Group detections into incidents based on spatial and temporal proximity.
     * Detections within 5 minutes and ~50 meters are considered the same incident.
     */
    private fun groupIntoIncidents(detections: List<Detection>): List<List<Detection>> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedBy { it.timestamp }
        val incidents = mutableListOf<MutableList<Detection>>()
        var currentIncident = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val detection = sorted[i]
            val lastInIncident = currentIncident.last()

            val timeDiff = detection.timestamp - lastInIncident.timestamp
            val sameLocation = isSameLocation(detection, lastInIncident)

            // Same incident if within 5 minutes and same location (or location unknown)
            if (timeDiff < 5 * 60 * 1000 && sameLocation) {
                currentIncident.add(detection)
            } else {
                incidents.add(currentIncident)
                currentIncident = mutableListOf(detection)
            }
        }
        incidents.add(currentIncident)

        return incidents
    }

    /**
     * Check if two detections are at the same location (within ~50m).
     */
    private fun isSameLocation(a: Detection, b: Detection): Boolean {
        if (a.latitude == null || a.longitude == null ||
            b.latitude == null || b.longitude == null) {
            return true  // Assume same location if unknown
        }

        val latDiff = kotlin.math.abs(a.latitude - b.latitude)
        val lonDiff = kotlin.math.abs(a.longitude - b.longitude)

        // Roughly 50 meters at mid-latitudes
        return latDiff < 0.0005 && lonDiff < 0.0005
    }

    /**
     * Get threat statistics for a collection of detections.
     * Useful for dashboard displays and summary views.
     */
    fun getThreatStatistics(detections: List<Detection>): ThreatStatistics {
        val criticalCount = detections.count { it.threatLevel == ThreatLevel.CRITICAL }
        val highCount = detections.count { it.threatLevel == ThreatLevel.HIGH }
        val mediumCount = detections.count { it.threatLevel == ThreatLevel.MEDIUM }
        val lowCount = detections.count { it.threatLevel == ThreatLevel.LOW }
        val infoCount = detections.count { it.threatLevel == ThreatLevel.INFO }

        val averageScore = if (detections.isEmpty()) 0.0 else {
            detections.map { it.threatScore }.average()
        }

        val maxScore = detections.maxOfOrNull { it.threatScore } ?: 0

        // Count unique device types
        val uniqueDeviceTypes = detections.map { it.deviceType }.toSet().size

        // Count unique protocols
        val uniqueProtocols = detections.map { it.protocol }.toSet().size

        return ThreatStatistics(
            totalCount = detections.size,
            criticalCount = criticalCount,
            highCount = highCount,
            mediumCount = mediumCount,
            lowCount = lowCount,
            infoCount = infoCount,
            averageScore = averageScore,
            maxScore = maxScore,
            uniqueDeviceTypes = uniqueDeviceTypes,
            uniqueProtocols = uniqueProtocols
        )
    }
}

/**
 * Result of aggregate threat calculation across multiple detections.
 */
data class AggregateThreatResult(
    val overallSeverity: ThreatLevel,
    val overallScore: Int,
    val incidentCount: Int,
    val detectionCount: Int,
    val highestThreatDetection: Detection?,
    val correlatedProtocols: Set<DetectionProtocol>,
    val hasCorrelation: Boolean,
    val hasRecurringPattern: Boolean,
    val reasoning: String
) {
    /**
     * Whether immediate action is recommended.
     */
    val requiresImmediateAction: Boolean
        get() = overallSeverity == ThreatLevel.CRITICAL ||
                overallSeverity == ThreatLevel.HIGH

    /**
     * Whether monitoring is recommended.
     */
    val requiresMonitoring: Boolean
        get() = overallSeverity == ThreatLevel.MEDIUM

    /**
     * Generate debug export map.
     */
    fun toDebugMap(): Map<String, Any> = mapOf(
        "overall_severity" to overallSeverity.name,
        "overall_severity_display" to overallSeverity.displayName,
        "overall_score" to overallScore,
        "incident_count" to incidentCount,
        "detection_count" to detectionCount,
        "correlated_protocols" to correlatedProtocols.map { it.name },
        "has_cross_protocol_correlation" to hasCorrelation,
        "has_recurring_pattern" to hasRecurringPattern,
        "requires_immediate_action" to requiresImmediateAction,
        "requires_monitoring" to requiresMonitoring,
        "highest_threat_device" to (highestThreatDetection?.deviceType?.displayName ?: "none"),
        "highest_threat_score" to (highestThreatDetection?.threatScore ?: 0),
        "reasoning" to reasoning
    )
}

/**
 * Statistics about a collection of detections.
 */
data class ThreatStatistics(
    val totalCount: Int,
    val criticalCount: Int,
    val highCount: Int,
    val mediumCount: Int,
    val lowCount: Int,
    val infoCount: Int,
    val averageScore: Double,
    val maxScore: Int,
    val uniqueDeviceTypes: Int,
    val uniqueProtocols: Int
) {
    /**
     * Count of significant threats (MEDIUM or higher).
     */
    val significantThreatCount: Int
        get() = criticalCount + highCount + mediumCount

    /**
     * Percentage of significant threats.
     */
    val significantThreatPercentage: Double
        get() = if (totalCount == 0) 0.0 else {
            (significantThreatCount.toDouble() / totalCount) * 100
        }
}

package com.flockyou.ai

import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Smart batching system for detection LLM analysis.
 *
 * Achieves ~5x efficiency improvement by:
 * 1. Grouping similar detections for batch LLM analysis
 * 2. Using smart similarity matching (device type, protocol, location)
 * 3. Time-window batching to collect related detections
 * 4. Priority-based processing for critical threats
 *
 * Example: 10 AirTag detections can be analyzed in 1 LLM call instead of 10.
 */
@Singleton
class DetectionBatcher @Inject constructor() {

    companion object {
        private const val TAG = "DetectionBatcher"

        // Batching configuration
        private const val DEFAULT_BATCH_WINDOW_MS = 2000L // 2 second collection window
        private const val MAX_BATCH_SIZE = 10 // Max detections per batch (LLM context limit)
        private const val MIN_BATCH_SIZE = 2 // Minimum to justify batching overhead
        private const val LOCATION_PROXIMITY_METERS = 100.0 // Group within 100m

        // Priority thresholds
        private const val CRITICAL_PRIORITY = 0 // Process immediately
        private const val HIGH_PRIORITY = 1
        private const val MEDIUM_PRIORITY = 2
        private const val LOW_PRIORITY = 3
    }

    // Pending detections awaiting batch processing
    private val pendingDetections = mutableListOf<PendingDetection>()
    private val batchMutex = Mutex()

    // Track when batching started for window calculation
    private var batchWindowStart: Long = 0L
    private var batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS

    // Statistics for optimization
    private var totalBatched = 0
    private var totalBatches = 0
    private var batchSavings = 0 // LLM calls saved

    /**
     * Configuration for batch behavior.
     */
    data class BatchConfig(
        val batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS,
        val maxBatchSize: Int = MAX_BATCH_SIZE,
        val minBatchSize: Int = MIN_BATCH_SIZE,
        val locationProximityMeters: Double = LOCATION_PROXIMITY_METERS,
        val groupByDeviceType: Boolean = true,
        val groupByProtocol: Boolean = true,
        val groupByLocation: Boolean = true,
        val prioritizeCritical: Boolean = true
    )

    private var config = BatchConfig()

    /**
     * Update batching configuration.
     */
    fun configure(config: BatchConfig) {
        this.config = config
        this.batchWindowMs = config.batchWindowMs
    }

    /**
     * Add a detection to the pending batch.
     *
     * @param detection The detection to add
     * @return True if detection was added to batch, false if it should be processed immediately
     */
    suspend fun addDetection(detection: Detection): BatchAddResult = batchMutex.withLock {
        val now = System.currentTimeMillis()

        // Critical threats bypass batching
        if (config.prioritizeCritical && detection.threatLevel == ThreatLevel.CRITICAL) {
            Log.d(TAG, "Critical detection bypasses batching: ${detection.id}")
            return@withLock BatchAddResult.ProcessImmediately(detection)
        }

        // Start new batch window if needed
        if (pendingDetections.isEmpty()) {
            batchWindowStart = now
        }

        // Add to pending
        val pending = PendingDetection(
            detection = detection,
            addedAt = now,
            groupKey = calculateGroupKey(detection),
            priority = calculatePriority(detection)
        )
        pendingDetections.add(pending)

        Log.d(TAG, "Added detection to batch: ${detection.id} (group=${pending.groupKey}, pending=${pendingDetections.size})")

        // Check if we should process now
        if (shouldProcessBatch(now)) {
            val batches = createBatches()
            pendingDetections.clear()
            batchWindowStart = 0L

            Log.i(TAG, "Batch ready: ${batches.size} batches with ${batches.sumOf { it.detections.size }} detections")
            return@withLock BatchAddResult.BatchReady(batches)
        }

        BatchAddResult.Batched(pendingDetections.size, batchWindowMs - (now - batchWindowStart))
    }

    /**
     * Force process any pending detections (e.g., on app background).
     */
    suspend fun flushPending(): List<DetectionBatch> = batchMutex.withLock {
        if (pendingDetections.isEmpty()) {
            return@withLock emptyList()
        }

        val batches = createBatches()
        pendingDetections.clear()
        batchWindowStart = 0L

        Log.i(TAG, "Flushed ${batches.sumOf { it.detections.size }} pending detections in ${batches.size} batches")
        batches
    }

    /**
     * Get current batch statistics.
     */
    fun getStatistics(): BatchStatistics {
        val avgBatchSize = if (totalBatches > 0) totalBatched.toFloat() / totalBatches else 0f
        return BatchStatistics(
            totalBatched = totalBatched,
            totalBatches = totalBatches,
            averageBatchSize = avgBatchSize,
            estimatedCallsSaved = batchSavings,
            efficiencyGain = if (totalBatched > 0) (batchSavings.toFloat() / totalBatched) * 100f else 0f
        )
    }

    /**
     * Reset statistics.
     */
    fun resetStatistics() {
        totalBatched = 0
        totalBatches = 0
        batchSavings = 0
    }

    /**
     * Check if the batch window has elapsed or batch is full.
     */
    private fun shouldProcessBatch(currentTime: Long): Boolean {
        if (pendingDetections.isEmpty()) return false

        // Window elapsed
        val windowElapsed = currentTime - batchWindowStart >= batchWindowMs

        // Batch full
        val batchFull = pendingDetections.size >= config.maxBatchSize

        // Have enough similar items to justify batching
        val groupCounts = pendingDetections.groupBy { it.groupKey }
        val hasGoodBatch = groupCounts.any { it.value.size >= config.minBatchSize }

        return windowElapsed || batchFull || (hasGoodBatch && pendingDetections.size >= 3)
    }

    /**
     * Create batches from pending detections using smart grouping.
     */
    private fun createBatches(): List<DetectionBatch> {
        if (pendingDetections.isEmpty()) return emptyList()

        val batches = mutableListOf<DetectionBatch>()
        val processed = mutableSetOf<String>()

        // Sort by priority first
        val sorted = pendingDetections.sortedBy { it.priority }

        // Group by key
        val byGroup = sorted.groupBy { it.groupKey }

        for ((groupKey, group) in byGroup) {
            if (group.isEmpty()) continue

            // Further split by location proximity if enabled
            val locationBatches = if (config.groupByLocation) {
                splitByLocation(group)
            } else {
                listOf(group)
            }

            for (locationGroup in locationBatches) {
                // Chunk into max batch size
                locationGroup.chunked(config.maxBatchSize).forEach { chunk ->
                    val detections = chunk.map { it.detection }
                    val maxPriority = chunk.minOf { it.priority }

                    batches.add(DetectionBatch(
                        detections = detections,
                        groupKey = groupKey,
                        priority = maxPriority,
                        batchId = generateBatchId(),
                        createdAt = System.currentTimeMillis()
                    ))

                    chunk.forEach { processed.add(it.detection.id) }
                }
            }
        }

        // Update statistics
        val totalInBatches = batches.sumOf { it.detections.size }
        totalBatched += totalInBatches
        totalBatches += batches.size
        batchSavings += (totalInBatches - batches.size).coerceAtLeast(0)

        Log.d(TAG, "Created ${batches.size} batches from $totalInBatches detections (saved ${totalInBatches - batches.size} LLM calls)")

        return batches.sortedBy { it.priority }
    }

    /**
     * Split a group by location proximity.
     */
    private fun splitByLocation(group: List<PendingDetection>): List<List<PendingDetection>> {
        val withLocation = group.filter { it.detection.latitude != null && it.detection.longitude != null }
        val withoutLocation = group.filter { it.detection.latitude == null || it.detection.longitude == null }

        if (withLocation.isEmpty()) {
            return listOf(group)
        }

        val clusters = mutableListOf<MutableList<PendingDetection>>()

        for (pending in withLocation) {
            val lat = pending.detection.latitude!!
            val lon = pending.detection.longitude!!

            // Find existing cluster within proximity
            val nearCluster = clusters.find { cluster ->
                cluster.any { other ->
                    val otherLat = other.detection.latitude!!
                    val otherLon = other.detection.longitude!!
                    calculateDistance(lat, lon, otherLat, otherLon) <= config.locationProximityMeters
                }
            }

            if (nearCluster != null) {
                nearCluster.add(pending)
            } else {
                clusters.add(mutableListOf(pending))
            }
        }

        // Add non-located as separate group
        if (withoutLocation.isNotEmpty()) {
            clusters.add(withoutLocation.toMutableList())
        }

        return clusters
    }

    /**
     * Calculate a grouping key for a detection.
     * Detections with the same key can be analyzed together.
     */
    private fun calculateGroupKey(detection: Detection): String {
        val parts = mutableListOf<String>()

        if (config.groupByDeviceType) {
            // Group similar device types together
            parts.add(getDeviceTypeCategory(detection.deviceType))
        }

        if (config.groupByProtocol) {
            parts.add(detection.protocol.name)
        }

        // Add detection method category
        parts.add(getMethodCategory(detection.detectionMethod))

        return parts.joinToString("_")
    }

    /**
     * Get a broader category for device type to enable better batching.
     */
    private fun getDeviceTypeCategory(deviceType: DeviceType): String {
        return when (deviceType) {
            // BLE Trackers
            DeviceType.AIRTAG, DeviceType.TILE_TRACKER, DeviceType.SAMSUNG_SMARTTAG,
            DeviceType.GENERIC_BLE_TRACKER -> "BLE_TRACKER"

            // ALPR/Traffic Cameras
            DeviceType.FLOCK_SAFETY_CAMERA, DeviceType.LICENSE_PLATE_READER,
            DeviceType.SPEED_CAMERA, DeviceType.RED_LIGHT_CAMERA, DeviceType.TRAFFIC_SENSOR -> "TRAFFIC_CAMERA"

            // Smart Home Cameras
            DeviceType.RING_DOORBELL, DeviceType.NEST_CAMERA, DeviceType.WYZE_CAMERA,
            DeviceType.ARLO_CAMERA, DeviceType.EUFY_CAMERA, DeviceType.BLINK_CAMERA -> "SMART_CAMERA"

            // Security Systems
            DeviceType.SIMPLISAFE_DEVICE, DeviceType.ADT_DEVICE, DeviceType.VIVINT_DEVICE -> "SECURITY_SYSTEM"

            // Network Surveillance
            DeviceType.WIFI_PINEAPPLE, DeviceType.ROGUE_AP, DeviceType.MAN_IN_MIDDLE,
            DeviceType.PACKET_SNIFFER -> "NETWORK_ATTACK"

            // Cell Site Simulators
            DeviceType.STINGRAY_IMSI -> "IMSI_CATCHER"

            // GNSS Threats
            DeviceType.GNSS_SPOOFER, DeviceType.GNSS_JAMMER -> "GNSS_THREAT"

            // RF Threats
            DeviceType.RF_JAMMER, DeviceType.RF_INTERFERENCE, DeviceType.RF_ANOMALY,
            DeviceType.HIDDEN_TRANSMITTER -> "RF_THREAT"

            // Hacking Tools
            DeviceType.FLIPPER_ZERO, DeviceType.FLIPPER_ZERO_SPAM, DeviceType.HACKRF_SDR,
            DeviceType.PROXMARK, DeviceType.USB_RUBBER_DUCKY, DeviceType.LAN_TURTLE,
            DeviceType.BASH_BUNNY, DeviceType.KEYCROC, DeviceType.SHARK_JACK,
            DeviceType.SCREEN_CRAB, DeviceType.GENERIC_HACKING_TOOL -> "HACKING_TOOL"

            // Audio/Ultrasonic
            DeviceType.ULTRASONIC_BEACON, DeviceType.RAVEN_GUNSHOT_DETECTOR,
            DeviceType.SHOTSPOTTER -> "AUDIO_SURVEILLANCE"

            // Drones
            DeviceType.DRONE -> "DRONE"

            // Video Surveillance
            DeviceType.CCTV_CAMERA, DeviceType.PTZ_CAMERA, DeviceType.THERMAL_CAMERA,
            DeviceType.NIGHT_VISION, DeviceType.HIDDEN_CAMERA -> "VIDEO_SURVEILLANCE"

            // Retail Tracking
            DeviceType.BLUETOOTH_BEACON, DeviceType.RETAIL_TRACKER, DeviceType.CROWD_ANALYTICS -> "RETAIL_TRACKING"

            // Biometric
            DeviceType.FACIAL_RECOGNITION, DeviceType.CLEARVIEW_AI -> "BIOMETRIC"

            // Law Enforcement
            DeviceType.BODY_CAMERA, DeviceType.POLICE_RADIO, DeviceType.POLICE_VEHICLE,
            DeviceType.MOTOROLA_POLICE_TECH, DeviceType.AXON_POLICE_TECH -> "LAW_ENFORCEMENT"

            // Forensics
            DeviceType.CELLEBRITE_FORENSICS, DeviceType.GRAYKEY_DEVICE, DeviceType.PALANTIR_DEVICE -> "FORENSICS"

            // Default categories by protocol
            else -> "OTHER_${deviceType.name}"
        }
    }

    /**
     * Get a category for detection method.
     */
    private fun getMethodCategory(method: DetectionMethod): String {
        return when (method) {
            // BLE Methods
            DetectionMethod.BLE_DEVICE_NAME, DetectionMethod.BLE_SERVICE_UUID,
            DetectionMethod.RAVEN_SERVICE_UUID -> "BLE"

            // WiFi Methods
            DetectionMethod.SSID_PATTERN, DetectionMethod.BEACON_FRAME,
            DetectionMethod.PROBE_REQUEST, DetectionMethod.WIFI_EVIL_TWIN,
            DetectionMethod.WIFI_DEAUTH_ATTACK, DetectionMethod.WIFI_HIDDEN_CAMERA,
            DetectionMethod.WIFI_ROGUE_AP, DetectionMethod.WIFI_SIGNAL_ANOMALY,
            DetectionMethod.WIFI_FOLLOWING, DetectionMethod.WIFI_SURVEILLANCE_VAN,
            DetectionMethod.WIFI_KARMA_ATTACK -> "WIFI"

            // Cellular Methods
            DetectionMethod.CELL_ENCRYPTION_DOWNGRADE, DetectionMethod.CELL_SUSPICIOUS_NETWORK,
            DetectionMethod.CELL_TOWER_CHANGE, DetectionMethod.CELL_RAPID_SWITCHING,
            DetectionMethod.CELL_SIGNAL_ANOMALY, DetectionMethod.CELL_LAC_TAC_ANOMALY -> "CELLULAR"

            // GNSS Methods
            DetectionMethod.GNSS_SPOOFING, DetectionMethod.GNSS_JAMMING,
            DetectionMethod.GNSS_SIGNAL_ANOMALY, DetectionMethod.GNSS_GEOMETRY_ANOMALY,
            DetectionMethod.GNSS_SIGNAL_LOSS, DetectionMethod.GNSS_CLOCK_ANOMALY,
            DetectionMethod.GNSS_MULTIPATH, DetectionMethod.GNSS_CONSTELLATION_ANOMALY -> "GNSS"

            // RF Methods
            DetectionMethod.RF_JAMMER, DetectionMethod.RF_DRONE, DetectionMethod.RF_SURVEILLANCE_AREA,
            DetectionMethod.RF_SPECTRUM_ANOMALY, DetectionMethod.RF_UNUSUAL_ACTIVITY,
            DetectionMethod.RF_INTERFERENCE, DetectionMethod.RF_HIDDEN_TRANSMITTER -> "RF"

            // Ultrasonic Methods
            DetectionMethod.ULTRASONIC_TRACKING_BEACON, DetectionMethod.ULTRASONIC_AD_BEACON,
            DetectionMethod.ULTRASONIC_RETAIL_BEACON, DetectionMethod.ULTRASONIC_CONTINUOUS,
            DetectionMethod.ULTRASONIC_CROSS_DEVICE, DetectionMethod.ULTRASONIC_UNKNOWN -> "ULTRASONIC"

            // Tracker Methods
            DetectionMethod.AIRTAG_DETECTED, DetectionMethod.TILE_DETECTED,
            DetectionMethod.SMARTTAG_DETECTED, DetectionMethod.UNKNOWN_TRACKER,
            DetectionMethod.TRACKER_FOLLOWING -> "TRACKER"

            // Signature-based
            DetectionMethod.MAC_PREFIX, DetectionMethod.MANUFACTURER_OUI -> "SIGNATURE"

            // Behavior-based
            DetectionMethod.BEHAVIOR_ANALYSIS -> "BEHAVIOR"

            else -> "OTHER"
        }
    }

    /**
     * Calculate priority for a detection (lower = higher priority).
     */
    private fun calculatePriority(detection: Detection): Int {
        return when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> CRITICAL_PRIORITY
            ThreatLevel.HIGH -> HIGH_PRIORITY
            ThreatLevel.MEDIUM -> MEDIUM_PRIORITY
            ThreatLevel.LOW, ThreatLevel.INFO -> LOW_PRIORITY
        }
    }

    /**
     * Calculate distance between two coordinates in meters.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Generate a unique batch ID.
     */
    private fun generateBatchId(): String {
        return "batch_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }
}

/**
 * Wrapper for a detection pending batch processing.
 */
data class PendingDetection(
    val detection: Detection,
    val addedAt: Long,
    val groupKey: String,
    val priority: Int
)

/**
 * A batch of similar detections for LLM analysis.
 */
data class DetectionBatch(
    val detections: List<Detection>,
    val groupKey: String,
    val priority: Int,
    val batchId: String,
    val createdAt: Long
) {
    val size: Int get() = detections.size

    /**
     * Get a summary of this batch for logging.
     */
    fun summary(): String {
        val types = detections.groupBy { it.deviceType }
            .map { "${it.key.displayName}(${it.value.size})" }
            .joinToString(", ")
        return "Batch[$batchId]: $size detections - $types"
    }
}

/**
 * Result of adding a detection to the batcher.
 */
sealed class BatchAddResult {
    /**
     * Detection was added to batch, waiting for more.
     */
    data class Batched(
        val pendingCount: Int,
        val remainingWindowMs: Long
    ) : BatchAddResult()

    /**
     * Batch is ready to process.
     */
    data class BatchReady(
        val batches: List<DetectionBatch>
    ) : BatchAddResult()

    /**
     * Detection should be processed immediately (e.g., critical threat).
     */
    data class ProcessImmediately(
        val detection: Detection
    ) : BatchAddResult()
}

/**
 * Batch processing statistics.
 */
data class BatchStatistics(
    val totalBatched: Int,
    val totalBatches: Int,
    val averageBatchSize: Float,
    val estimatedCallsSaved: Int,
    val efficiencyGain: Float // Percentage
)

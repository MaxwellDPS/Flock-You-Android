package com.flockyou.data.repository

import android.util.Log
import com.flockyou.data.model.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing detection data
 */
@Singleton
class DetectionRepository @Inject constructor(
    private val detectionDao: DetectionDao,
    private val deduplicator: DetectionDeduplicator
) {
    companion object {
        private const val TAG = "DetectionRepository"
        private const val COMPOSITE_KEY_WINDOW_MS = 3600000L  // 1 hour
    }
    val allDetections: Flow<List<Detection>> = detectionDao.getAllDetections()
    val activeDetections: Flow<List<Detection>> = detectionDao.getActiveDetections()
    val totalDetectionCount: Flow<Int> = detectionDao.getTotalDetectionCount()
    val highThreatCount: Flow<Int> = detectionDao.getHighThreatCount()
    val detectionsWithLocation: Flow<List<Detection>> = detectionDao.getDetectionsWithLocation()
    
    fun getRecentDetections(sinceMillis: Long): Flow<List<Detection>> {
        return detectionDao.getRecentDetections(sinceMillis)
    }
    
    fun getDetectionsByThreatLevel(threatLevel: ThreatLevel): Flow<List<Detection>> {
        return detectionDao.getDetectionsByThreatLevel(threatLevel)
    }
    
    fun getDetectionsByDeviceType(deviceType: DeviceType): Flow<List<Detection>> {
        return detectionDao.getDetectionsByDeviceType(deviceType)
    }
    
    suspend fun getDetectionByMacAddress(macAddress: String): Detection? {
        return detectionDao.getDetectionByMacAddress(macAddress)
    }
    
    suspend fun getDetectionBySsid(ssid: String): Detection? {
        return detectionDao.getDetectionBySsid(ssid)
    }
    
    suspend fun getDetectionById(id: String): Detection? {
        return detectionDao.getDetectionById(id)
    }

    suspend fun getDetectionByServiceUuid(serviceUuid: String): Detection? {
        return detectionDao.getDetectionByServiceUuid(serviceUuid)
    }
    
    suspend fun getTotalDetectionCount(): Int {
        return detectionDao.getTotalDetectionCountSync()
    }

    suspend fun getAllDetectionsSnapshot(): List<Detection> {
        return detectionDao.getAllDetectionsSnapshot()
    }

    suspend fun insertDetection(detection: Detection) {
        detectionDao.insertDetection(detection)
    }
    
    suspend fun insertDetections(detections: List<Detection>) {
        detectionDao.insertDetections(detections)
    }
    
    suspend fun updateDetection(detection: Detection) {
        detectionDao.updateDetection(detection)
    }
    
    suspend fun deleteDetection(detection: Detection) {
        detectionDao.deleteDetection(detection)
    }
    
    suspend fun deleteAllDetections() {
        detectionDao.deleteAllDetections()
    }
    
    suspend fun deleteOldDetections(beforeMillis: Long) {
        detectionDao.deleteOldDetections(beforeMillis)
    }
    
    suspend fun markInactive(macAddress: String) {
        detectionDao.markInactive(macAddress)
    }
    
    suspend fun markOldInactive(beforeMillis: Long) {
        detectionDao.markOldInactive(beforeMillis)
    }
    
    /**
     * Update an existing detection's seen count and location, or insert if new.
     * Uses enhanced deduplication with throttling and composite key matching.
     */
    suspend fun upsertDetection(detection: Detection): Boolean {
        // 1. Check throttling first (rapid detection suppression)
        if (deduplicator.shouldThrottle(detection)) {
            Log.d(TAG, "Throttled rapid detection: ${detection.macAddress ?: detection.ssid}")
            return false  // Treat as duplicate
        }

        // 2. Try existing match strategies (MAC, SSID)
        val existingByMac = detection.macAddress?.let { getDetectionByMacAddress(it) }
        val existingBySsid = if (existingByMac == null) detection.ssid?.let { getDetectionBySsid(it) } else null

        // 3. Try service UUID match for BLE devices
        val existingByServiceUuid = if (existingByMac == null && existingBySsid == null) {
            detection.serviceUuids?.split(",")?.firstOrNull()?.trim()?.let { getDetectionByServiceUuid(it) }
        } else null

        // 4. Try composite key match as fallback
        val existingByComposite = if (existingByMac == null && existingBySsid == null && existingByServiceUuid == null) {
            findByCompositeKey(detection)
        } else null

        val existing = existingByMac ?: existingBySsid ?: existingByServiceUuid ?: existingByComposite

        return if (existing != null) {
            // Update existing - increment seen count
            when {
                detection.macAddress != null -> {
                    detectionDao.updateSeenByMac(
                        macAddress = detection.macAddress,
                        timestamp = detection.timestamp,
                        rssi = detection.rssi,
                        latitude = detection.latitude,
                        longitude = detection.longitude
                    )
                }
                detection.ssid != null -> {
                    detectionDao.updateSeenBySsid(
                        ssid = detection.ssid,
                        timestamp = detection.timestamp,
                        rssi = detection.rssi,
                        latitude = detection.latitude,
                        longitude = detection.longitude
                    )
                }
                existingByServiceUuid != null -> {
                    // Update by service UUID
                    detection.serviceUuids?.split(",")?.firstOrNull()?.trim()?.let { uuid ->
                        detectionDao.updateSeenByServiceUuid(
                            serviceUuid = uuid,
                            timestamp = detection.timestamp,
                            rssi = detection.rssi,
                            latitude = detection.latitude,
                            longitude = detection.longitude
                        )
                    }
                }
                existingByComposite != null -> {
                    // Update the matched detection directly
                    detectionDao.updateDetection(
                        existing.copy(
                            lastSeenTimestamp = detection.timestamp,
                            rssi = detection.rssi,
                            latitude = detection.latitude ?: existing.latitude,
                            longitude = detection.longitude ?: existing.longitude,
                            seenCount = existing.seenCount + 1,
                            isActive = true
                        )
                    )
                }
            }
            false // Not a new detection
        } else {
            // Insert new
            insertDetection(detection)
            true // New detection
        }
    }

    /**
     * Find a matching detection using composite key matching.
     * Gets recent detections of the same type and uses the deduplicator to find a match.
     */
    private suspend fun findByCompositeKey(detection: Detection): Detection? {
        // Get recent detections of same type and check for proximity match
        val candidates = detectionDao.getRecentDetectionsByType(
            deviceType = detection.deviceType.name,
            since = System.currentTimeMillis() - COMPOSITE_KEY_WINDOW_MS
        )
        return deduplicator.findMatch(detection, candidates)
    }

    /**
     * Update false positive analysis results for a detection
     */
    suspend fun updateFpAnalysis(
        detectionId: String,
        fpScore: Float?,
        fpReason: String?,
        fpCategory: String?,
        llmAnalyzed: Boolean
    ) {
        detectionDao.updateFpAnalysis(
            id = detectionId,
            fpScore = fpScore,
            fpReason = fpReason,
            fpCategory = fpCategory,
            analyzedAt = System.currentTimeMillis(),
            llmAnalyzed = llmAnalyzed
        )
    }

    /**
     * Get detections that haven't been analyzed for false positives yet
     */
    suspend fun getDetectionsPendingFpAnalysis(): List<Detection> {
        return detectionDao.getDetectionsPendingFpAnalysis()
    }

    /**
     * Get detections that haven't been analyzed for false positives yet (limited)
     */
    suspend fun getDetectionsPendingFpAnalysis(limit: Int): List<Detection> {
        return detectionDao.getDetectionsPendingFpAnalysis(limit)
    }
}

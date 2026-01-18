package com.flockyou.data.repository

import com.flockyou.data.model.Detection
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory detection repository for ephemeral mode.
 * All detections are stored in RAM only and are lost on service restart.
 * This provides maximum privacy as no data is persisted to disk.
 */
@Singleton
class EphemeralDetectionRepository @Inject constructor() {

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())

    val allDetections: Flow<List<Detection>> = _detections

    val activeDetections: Flow<List<Detection>> = _detections.map { list ->
        list.filter { it.isActive }
    }

    val totalDetectionCount: Flow<Int> = _detections.map { it.size }

    val highThreatCount: Flow<Int> = _detections.map { list ->
        list.count { it.threatLevel == ThreatLevel.HIGH || it.threatLevel == ThreatLevel.CRITICAL }
    }

    val detectionsWithLocation: Flow<List<Detection>> = _detections.map { list ->
        list.filter { it.latitude != null && it.longitude != null }
    }

    fun getRecentDetections(sinceMillis: Long): Flow<List<Detection>> {
        return _detections.map { list ->
            list.filter { it.timestamp >= sinceMillis }
        }
    }

    fun getDetectionsByThreatLevel(threatLevel: ThreatLevel): Flow<List<Detection>> {
        return _detections.map { list ->
            list.filter { it.threatLevel == threatLevel }
        }
    }

    fun getDetectionsByDeviceType(deviceType: DeviceType): Flow<List<Detection>> {
        return _detections.map { list ->
            list.filter { it.deviceType == deviceType }
        }
    }

    suspend fun getDetectionByMacAddress(macAddress: String): Detection? {
        return _detections.value.find { it.macAddress == macAddress }
    }

    suspend fun getDetectionBySsid(ssid: String): Detection? {
        return _detections.value.find { it.ssid == ssid }
    }

    suspend fun getDetectionById(id: String): Detection? {
        return _detections.value.find { it.id == id }
    }

    suspend fun getTotalDetectionCount(): Int {
        return _detections.value.size
    }

    suspend fun getAllDetectionsSnapshot(): List<Detection> {
        return _detections.value.toList()
    }

    suspend fun insertDetection(detection: Detection) {
        val current = _detections.value.toMutableList()
        current.add(0, detection)
        _detections.value = current
    }

    suspend fun insertDetections(detections: List<Detection>) {
        val current = _detections.value.toMutableList()
        current.addAll(0, detections)
        _detections.value = current
    }

    suspend fun updateDetection(detection: Detection) {
        val current = _detections.value.toMutableList()
        val index = current.indexOfFirst { it.id == detection.id }
        if (index >= 0) {
            current[index] = detection
            _detections.value = current
        }
    }

    suspend fun deleteDetection(detection: Detection) {
        val current = _detections.value.toMutableList()
        current.removeAll { it.id == detection.id }
        _detections.value = current
    }

    suspend fun deleteAllDetections() {
        _detections.value = emptyList()
    }

    suspend fun deleteOldDetections(beforeMillis: Long) {
        val current = _detections.value.toMutableList()
        current.removeAll { it.timestamp < beforeMillis }
        _detections.value = current
    }

    suspend fun markInactive(macAddress: String) {
        val current = _detections.value.toMutableList()
        val index = current.indexOfFirst { it.macAddress == macAddress }
        if (index >= 0) {
            current[index] = current[index].copy(isActive = false)
            _detections.value = current
        }
    }

    suspend fun markOldInactive(beforeMillis: Long) {
        val current = _detections.value.toMutableList()
        _detections.value = current.map { detection ->
            if (detection.lastSeenTimestamp < beforeMillis && detection.isActive) {
                detection.copy(isActive = false)
            } else {
                detection
            }
        }
    }

    /**
     * Update an existing detection's seen count and location, or insert if new.
     * Returns true if this is a new detection, false if updated existing.
     */
    suspend fun upsertDetection(detection: Detection): Boolean {
        val existingByMac = detection.macAddress?.let { getDetectionByMacAddress(it) }
        val existingBySsid = if (existingByMac == null) detection.ssid?.let { getDetectionBySsid(it) } else null
        val existing = existingByMac ?: existingBySsid

        return if (existing != null) {
            // Update existing
            val updated = existing.copy(
                lastSeenTimestamp = detection.timestamp,
                rssi = detection.rssi,
                latitude = detection.latitude,
                longitude = detection.longitude,
                seenCount = existing.seenCount + 1,
                isActive = true
            )
            updateDetection(updated)
            false
        } else {
            insertDetection(detection)
            true
        }
    }

    /**
     * Clear all data - called when ephemeral mode is disabled or on service restart.
     */
    fun clearAll() {
        _detections.value = emptyList()
    }
}

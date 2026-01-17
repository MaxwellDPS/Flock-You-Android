package com.flockyou.fake

import com.flockyou.data.model.Detection
import com.flockyou.data.repository.DetectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake implementation of DetectionRepository for testing.
 * Stores detections in memory and provides controllable behavior.
 */
class FakeDetectionRepository : DetectionRepository {
    
    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    private val detections: List<Detection> get() = _detections.value
    
    // Track method calls for verification
    var insertCount = 0
        private set
    var updateCount = 0
        private set
    var deleteCount = 0
        private set
    
    // Control behavior
    var shouldThrowOnInsert = false
    var shouldThrowOnUpdate = false
    var shouldThrowOnDelete = false
    
    /**
     * Pre-populate the repository with test data.
     */
    fun setDetections(detections: List<Detection>) {
        _detections.value = detections.toList()
    }
    
    /**
     * Reset the repository to initial state.
     */
    fun reset() {
        _detections.value = emptyList()
        insertCount = 0
        updateCount = 0
        deleteCount = 0
        shouldThrowOnInsert = false
        shouldThrowOnUpdate = false
        shouldThrowOnDelete = false
    }
    
    override fun getAllDetections(): Flow<List<Detection>> = _detections
    
    override fun getActiveDetections(): Flow<List<Detection>> = 
        _detections.map { list -> list.filter { it.isActive } }
    
    override fun getDetectionsByType(type: String): Flow<List<Detection>> =
        _detections.map { list -> list.filter { it.deviceType.name == type } }
    
    override suspend fun getDetectionById(id: String): Detection? =
        detections.find { it.id == id }
    
    override suspend fun getDetectionByMacAddress(macAddress: String): Detection? =
        detections.find { it.macAddress == macAddress }
    
    override suspend fun insertDetection(detection: Detection) {
        if (shouldThrowOnInsert) {
            throw RuntimeException("Simulated insert failure")
        }
        _detections.value = detections + detection
        insertCount++
    }
    
    override suspend fun updateDetection(detection: Detection) {
        if (shouldThrowOnUpdate) {
            throw RuntimeException("Simulated update failure")
        }
        _detections.value = detections.map { 
            if (it.id == detection.id) detection else it 
        }
        updateCount++
    }
    
    override suspend fun deleteDetection(detection: Detection) {
        if (shouldThrowOnDelete) {
            throw RuntimeException("Simulated delete failure")
        }
        _detections.value = detections.filter { it.id != detection.id }
        deleteCount++
    }
    
    override suspend fun deleteAllDetections() {
        _detections.value = emptyList()
        deleteCount++
    }
    
    override suspend fun markOldInactive(timestamp: Long) {
        _detections.value = detections.map { detection ->
            if (detection.timestamp < timestamp && detection.isActive) {
                detection.copy(isActive = false)
            } else {
                detection
            }
        }
    }
    
    override suspend fun getDetectionCount(): Int = detections.size
    
    override suspend fun getActiveDetectionCount(): Int = 
        detections.count { it.isActive }
}

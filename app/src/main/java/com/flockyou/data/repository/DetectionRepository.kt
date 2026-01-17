package com.flockyou.data.repository

import com.flockyou.data.model.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing detection data
 */
@Singleton
class DetectionRepository @Inject constructor(
    private val detectionDao: DetectionDao
) {
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
    
    suspend fun getDetectionById(id: String): Detection? {
        return detectionDao.getDetectionById(id)
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
}

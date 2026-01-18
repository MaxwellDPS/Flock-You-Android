package com.flockyou.data.repository

import android.content.Context
import androidx.room.*
import com.flockyou.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Type converters for Room database
 */
class Converters {
    @TypeConverter
    fun fromDetectionProtocol(value: DetectionProtocol): String = value.name
    
    @TypeConverter
    fun toDetectionProtocol(value: String): DetectionProtocol = DetectionProtocol.valueOf(value)
    
    @TypeConverter
    fun fromDetectionMethod(value: DetectionMethod): String = value.name
    
    @TypeConverter
    fun toDetectionMethod(value: String): DetectionMethod = DetectionMethod.valueOf(value)
    
    @TypeConverter
    fun fromDeviceType(value: DeviceType): String = value.name
    
    @TypeConverter
    fun toDeviceType(value: String): DeviceType = DeviceType.valueOf(value)
    
    @TypeConverter
    fun fromSignalStrength(value: SignalStrength): String = value.name
    
    @TypeConverter
    fun toSignalStrength(value: String): SignalStrength = SignalStrength.valueOf(value)
    
    @TypeConverter
    fun fromThreatLevel(value: ThreatLevel): String = value.name
    
    @TypeConverter
    fun toThreatLevel(value: String): ThreatLevel = ThreatLevel.valueOf(value)
}

/**
 * Data Access Object for detections
 */
@Dao
interface DetectionDao {
    @Query("SELECT * FROM detections ORDER BY lastSeenTimestamp DESC")
    fun getAllDetections(): Flow<List<Detection>>
    
    @Query("SELECT * FROM detections WHERE isActive = 1 ORDER BY lastSeenTimestamp DESC")
    fun getActiveDetections(): Flow<List<Detection>>
    
    @Query("SELECT * FROM detections WHERE timestamp > :since ORDER BY lastSeenTimestamp DESC")
    fun getRecentDetections(since: Long): Flow<List<Detection>>
    
    @Query("SELECT * FROM detections WHERE threatLevel = :threatLevel ORDER BY lastSeenTimestamp DESC")
    fun getDetectionsByThreatLevel(threatLevel: ThreatLevel): Flow<List<Detection>>
    
    @Query("SELECT * FROM detections WHERE deviceType = :deviceType ORDER BY lastSeenTimestamp DESC")
    fun getDetectionsByDeviceType(deviceType: DeviceType): Flow<List<Detection>>
    
    @Query("SELECT * FROM detections WHERE macAddress = :macAddress ORDER BY lastSeenTimestamp DESC LIMIT 1")
    suspend fun getDetectionByMacAddress(macAddress: String): Detection?
    
    @Query("SELECT * FROM detections WHERE ssid = :ssid ORDER BY lastSeenTimestamp DESC LIMIT 1")
    suspend fun getDetectionBySsid(ssid: String): Detection?
    
    @Query("SELECT * FROM detections WHERE id = :id")
    suspend fun getDetectionById(id: String): Detection?
    
    @Query("SELECT COUNT(*) FROM detections")
    suspend fun getTotalDetectionCountSync(): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetection(detection: Detection)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetections(detections: List<Detection>)
    
    @Update
    suspend fun updateDetection(detection: Detection)
    
    @Delete
    suspend fun deleteDetection(detection: Detection)
    
    @Query("DELETE FROM detections")
    suspend fun deleteAllDetections()
    
    @Query("DELETE FROM detections WHERE timestamp < :before")
    suspend fun deleteOldDetections(before: Long)
    
    @Query("UPDATE detections SET isActive = 0 WHERE macAddress = :macAddress")
    suspend fun markInactive(macAddress: String)
    
    @Query("UPDATE detections SET isActive = 0 WHERE lastSeenTimestamp < :before")
    suspend fun markOldInactive(before: Long)
    
    @Query("UPDATE detections SET isActive = 1, seenCount = seenCount + 1, lastSeenTimestamp = :timestamp, rssi = :rssi, latitude = :latitude, longitude = :longitude WHERE macAddress = :macAddress")
    suspend fun updateSeenByMac(macAddress: String, timestamp: Long, rssi: Int, latitude: Double?, longitude: Double?)
    
    @Query("UPDATE detections SET isActive = 1, seenCount = seenCount + 1, lastSeenTimestamp = :timestamp, rssi = :rssi, latitude = :latitude, longitude = :longitude WHERE ssid = :ssid")
    suspend fun updateSeenBySsid(ssid: String, timestamp: Long, rssi: Int, latitude: Double?, longitude: Double?)
    
    @Query("SELECT COUNT(*) FROM detections")
    fun getTotalDetectionCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM detections WHERE threatLevel = 'CRITICAL' OR threatLevel = 'HIGH'")
    fun getHighThreatCount(): Flow<Int>
    
    @Query("SELECT * FROM detections WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY lastSeenTimestamp DESC")
    fun getDetectionsWithLocation(): Flow<List<Detection>>
}

/**
 * Room database for storing detections
 */
@Database(entities = [Detection::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class FlockYouDatabase : RoomDatabase() {
    abstract fun detectionDao(): DetectionDao
    
    companion object {
        @Volatile
        private var INSTANCE: FlockYouDatabase? = null
        
        fun getDatabase(context: Context): FlockYouDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FlockYouDatabase::class.java,
                    "flockyou_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

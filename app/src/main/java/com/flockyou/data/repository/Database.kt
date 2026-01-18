package com.flockyou.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.flockyou.data.model.*
import com.flockyou.data.model.OuiEntry
import kotlinx.coroutines.flow.Flow
import net.sqlcipher.database.SupportFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    @Query("SELECT * FROM detections ORDER BY lastSeenTimestamp DESC")
    suspend fun getAllDetectionsSnapshot(): List<Detection>

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
 * Secure key manager for database encryption passphrase.
 * Uses Android Keystore to protect the SQLCipher passphrase.
 */
object DatabaseKeyManager {
    private const val TAG = "DatabaseKeyManager"
    private const val KEYSTORE_ALIAS = "flockyou_db_key"
    private const val PREFS_NAME = "flockyou_secure_prefs"
    private const val PREFS_KEY_PASSPHRASE = "encrypted_db_passphrase"
    private const val PREFS_KEY_IV = "db_passphrase_iv"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * Get or create the database passphrase.
     * The passphrase is generated once and stored encrypted using Android Keystore.
     */
    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val encryptedPassphrase = prefs.getString(PREFS_KEY_PASSPHRASE, null)
        val ivString = prefs.getString(PREFS_KEY_IV, null)

        return if (encryptedPassphrase != null && ivString != null) {
            // Decrypt existing passphrase
            try {
                decryptPassphrase(
                    Base64.decode(encryptedPassphrase, Base64.NO_WRAP),
                    Base64.decode(ivString, Base64.NO_WRAP)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt passphrase, generating new one", e)
                generateAndStoreNewPassphrase(context, prefs)
            }
        } else {
            // Generate new passphrase
            generateAndStoreNewPassphrase(context, prefs)
        }
    }

    private fun generateAndStoreNewPassphrase(
        context: Context,
        prefs: android.content.SharedPreferences
    ): ByteArray {
        // Generate a random 32-byte passphrase
        val passphrase = ByteArray(32)
        SecureRandom().nextBytes(passphrase)

        // Create or get the key from Android Keystore
        val secretKey = getOrCreateSecretKey()

        // Encrypt the passphrase
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedPassphrase = cipher.doFinal(passphrase)

        // Store encrypted passphrase and IV
        prefs.edit()
            .putString(PREFS_KEY_PASSPHRASE, Base64.encodeToString(encryptedPassphrase, Base64.NO_WRAP))
            .putString(PREFS_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()

        Log.d(TAG, "Generated and stored new database passphrase")
        return passphrase
    }

    private fun decryptPassphrase(encryptedPassphrase: ByteArray, iv: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(encryptedPassphrase)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Check if key already exists
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        // Generate new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
}

/**
 * Room database for storing detections.
 * Uses SQLCipher for encryption to protect sensitive detection data.
 */
@Database(entities = [Detection::class, OuiEntry::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class FlockYouDatabase : RoomDatabase() {
    abstract fun detectionDao(): DetectionDao
    abstract fun ouiDao(): OuiDao

    companion object {
        private const val TAG = "FlockYouDatabase"

        @Volatile
        private var INSTANCE: FlockYouDatabase? = null

        // Migration from version 3 to 4 - adds indices
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create indices for better query performance
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_macAddress ON detections(macAddress)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_ssid ON detections(ssid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_threatLevel ON detections(threatLevel)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_deviceType ON detections(deviceType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_timestamp ON detections(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_lastSeenTimestamp ON detections(lastSeenTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_isActive ON detections(isActive)")
            }
        }

        // Migration from version 4 to 5 - adds OUI entries table
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS oui_entries (
                        ouiPrefix TEXT NOT NULL PRIMARY KEY,
                        organizationName TEXT NOT NULL,
                        registry TEXT NOT NULL DEFAULT 'MA-L',
                        address TEXT,
                        lastUpdated INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_oui_entries_ouiPrefix ON oui_entries(ouiPrefix)")
            }
        }

        fun getDatabase(context: Context): FlockYouDatabase {
            return INSTANCE ?: synchronized(this) {
                // Get or create encryption passphrase
                val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
                val factory = SupportFactory(passphrase)

                Log.d(TAG, "Creating encrypted database")

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FlockYouDatabase::class.java,
                    "flockyou_database_encrypted"  // New name to avoid conflicts with old unencrypted DB
                )
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration() // Only as last resort
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

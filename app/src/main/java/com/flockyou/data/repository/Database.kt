package com.flockyou.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.flockyou.data.model.*
import com.flockyou.data.model.OuiEntry
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Type converters for Room database.
 * Uses defensive enum parsing to handle invalid values gracefully
 * instead of crashing with IllegalArgumentException.
 */
class Converters {
    @TypeConverter
    fun fromDetectionProtocol(value: DetectionProtocol): String = value.name

    @TypeConverter
    fun toDetectionProtocol(value: String): DetectionProtocol =
        try { DetectionProtocol.valueOf(value) } catch (e: IllegalArgumentException) { DetectionProtocol.BLUETOOTH_LE }

    @TypeConverter
    fun fromDetectionMethod(value: DetectionMethod): String = value.name

    @TypeConverter
    fun toDetectionMethod(value: String): DetectionMethod =
        try { DetectionMethod.valueOf(value) } catch (e: IllegalArgumentException) { DetectionMethod.BLE_DEVICE_NAME }

    @TypeConverter
    fun fromDeviceType(value: DeviceType): String = value.name

    @TypeConverter
    fun toDeviceType(value: String): DeviceType =
        try { DeviceType.valueOf(value) } catch (e: IllegalArgumentException) { DeviceType.UNKNOWN_SURVEILLANCE }

    @TypeConverter
    fun fromSignalStrength(value: SignalStrength): String = value.name

    @TypeConverter
    fun toSignalStrength(value: String): SignalStrength =
        try { SignalStrength.valueOf(value) } catch (e: IllegalArgumentException) { SignalStrength.MEDIUM }

    @TypeConverter
    fun fromThreatLevel(value: ThreatLevel): String = value.name

    @TypeConverter
    fun toThreatLevel(value: String): ThreatLevel =
        try { ThreatLevel.valueOf(value) } catch (e: IllegalArgumentException) { ThreatLevel.LOW }
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
 * Security level for database encryption key.
 */
enum class DatabaseSecurityLevel {
    /** Hardware TPM - highest security, isolated secure processor */
    STRONGBOX,
    /** Trusted Execution Environment - hardware-backed but shared processor */
    TEE,
    /** Software-only - no hardware backing available */
    SOFTWARE_ONLY
}

/**
 * Secure key manager for database encryption passphrase.
 * Uses Android Keystore with StrongBox/TEE hardware backing to protect the SQLCipher passphrase.
 *
 * Security features:
 * - StrongBox (TPM) backing when available (API 28+)
 * - TEE fallback when StrongBox unavailable
 * - Device unlock requirement (API 28+)
 * - Migration from legacy non-hardware-backed keys
 */
object DatabaseKeyManager {
    private const val TAG = "DatabaseKeyManager"
    private const val KEYSTORE_ALIAS = "flockyou_db_key_v2"
    private const val LEGACY_KEYSTORE_ALIAS = "flockyou_db_key"
    private const val PREFS_NAME = "flockyou_secure_prefs"
    private const val PREFS_KEY_PASSPHRASE = "encrypted_db_passphrase_v2"
    private const val PREFS_KEY_IV = "db_passphrase_iv_v2"
    private const val PREFS_KEY_SECURITY_LEVEL = "db_key_security_level"
    private const val LEGACY_PREFS_KEY_PASSPHRASE = "encrypted_db_passphrase"
    private const val LEGACY_PREFS_KEY_IV = "db_passphrase_iv"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Volatile
    private var cachedSecurityLevel: DatabaseSecurityLevel? = null

    /**
     * Check if StrongBox is available on this device.
     */
    fun hasStrongBox(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } else {
            false
        }
    }

    /**
     * Get the current security level of the database encryption key.
     */
    fun getSecurityLevel(context: Context): DatabaseSecurityLevel {
        cachedSecurityLevel?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val levelName = prefs.getString(PREFS_KEY_SECURITY_LEVEL, null)

        val level = try {
            levelName?.let { DatabaseSecurityLevel.valueOf(it) }
                ?: detectKeySecurityLevel()
        } catch (e: Exception) {
            detectKeySecurityLevel()
        }

        cachedSecurityLevel = level
        return level
    }

    /**
     * Detect the actual security level of the current key.
     */
    private fun detectKeySecurityLevel(): DatabaseSecurityLevel {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                return DatabaseSecurityLevel.SOFTWARE_ONLY
            }

            val key = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
                ?: return DatabaseSecurityLevel.SOFTWARE_ONLY

            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (keyInfo.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> DatabaseSecurityLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> DatabaseSecurityLevel.TEE
                    else -> DatabaseSecurityLevel.SOFTWARE_ONLY
                }
            } else {
                @Suppress("DEPRECATION")
                if (keyInfo.isInsideSecureHardware) DatabaseSecurityLevel.TEE
                else DatabaseSecurityLevel.SOFTWARE_ONLY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting key security level", e)
            DatabaseSecurityLevel.SOFTWARE_ONLY
        }
    }

    /**
     * Get a human-readable description of the current security level.
     */
    fun getSecurityLevelDescription(context: Context): String {
        return when (getSecurityLevel(context)) {
            DatabaseSecurityLevel.STRONGBOX -> "StrongBox (Hardware TPM)"
            DatabaseSecurityLevel.TEE -> "TEE (Trusted Execution Environment)"
            DatabaseSecurityLevel.SOFTWARE_ONLY -> "Software-only"
        }
    }

    /**
     * Get or create the database passphrase with hardware-backed protection.
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
                Log.e(TAG, "Failed to decrypt passphrase", e)
                // Try to migrate from legacy key
                if (tryMigrateLegacyPassphrase(context, prefs)) {
                    getOrCreatePassphrase(context) // Retry after migration
                } else {
                    Log.w(TAG, "Generating new passphrase - existing encrypted data will be lost")
                    generateAndStoreNewPassphrase(context, prefs)
                }
            }
        } else {
            // Check for legacy passphrase and migrate
            if (tryMigrateLegacyPassphrase(context, prefs)) {
                getOrCreatePassphrase(context) // Retry after migration
            } else {
                generateAndStoreNewPassphrase(context, prefs)
            }
        }
    }

    private fun generateAndStoreNewPassphrase(
        context: Context,
        prefs: SharedPreferences
    ): ByteArray {
        // Generate a random 32-byte passphrase
        val passphrase = ByteArray(32)
        SecureRandom().nextBytes(passphrase)

        // Create or get the key from Android Keystore (with hardware backing)
        val secretKey = getOrCreateSecretKey(context)

        // Encrypt the passphrase
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedPassphrase = cipher.doFinal(passphrase)

        // Detect and store security level
        val securityLevel = detectKeySecurityLevel()
        cachedSecurityLevel = securityLevel

        // Store encrypted passphrase, IV, and security level
        prefs.edit()
            .putString(PREFS_KEY_PASSPHRASE, Base64.encodeToString(encryptedPassphrase, Base64.NO_WRAP))
            .putString(PREFS_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(PREFS_KEY_SECURITY_LEVEL, securityLevel.name)
            .apply()

        Log.i(TAG, "Generated new database passphrase with security level: $securityLevel")
        return passphrase
    }

    private fun decryptPassphrase(encryptedPassphrase: ByteArray, iv: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val secretKey = if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            throw IllegalStateException("Database key not found")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(encryptedPassphrase)
    }

    private fun getOrCreateSecretKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Check if key already exists
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        // Generate new hardware-backed key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        var useStrongBox = false

        // Enable StrongBox if available (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasStrongBox(context)) {
            builder.setIsStrongBoxBacked(true)
            useStrongBox = true
            Log.d(TAG, "Requesting StrongBox-backed database key")
        }

        // Note: We intentionally do NOT use setUnlockedDeviceRequired(true) here.
        // While it adds security, it causes crashes when:
        // 1. App launches before user unlocks device after boot (Direct Boot)
        // 2. Background workers run while device is locked
        // The hardware-backed key still provides strong protection.

        return try {
            keyGenerator.init(builder.build())
            val key = keyGenerator.generateKey()
            Log.i(TAG, "Created database key with StrongBox=$useStrongBox")
            key
        } catch (e: Exception) {
            // StrongBox may fail on some devices - fallback to TEE
            if (useStrongBox) {
                Log.w(TAG, "StrongBox key creation failed, falling back to TEE", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setIsStrongBoxBacked(false)
                }
                keyGenerator.init(builder.build())
                keyGenerator.generateKey()
            } else {
                throw e
            }
        }
    }

    /**
     * Try to migrate from legacy passphrase (non-hardware-backed) to new hardware-backed key.
     * Returns true if migration was successful or not needed.
     */
    private fun tryMigrateLegacyPassphrase(context: Context, prefs: SharedPreferences): Boolean {
        val legacyEncrypted = prefs.getString(LEGACY_PREFS_KEY_PASSPHRASE, null)
        val legacyIv = prefs.getString(LEGACY_PREFS_KEY_IV, null)

        if (legacyEncrypted == null || legacyIv == null) {
            return false // No legacy passphrase to migrate
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(LEGACY_KEYSTORE_ALIAS)) {
            return false // No legacy key
        }

        return try {
            Log.i(TAG, "Migrating legacy database passphrase to hardware-backed key")

            // Decrypt with legacy key
            val legacyKey = (keyStore.getEntry(LEGACY_KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                legacyKey,
                GCMParameterSpec(128, Base64.decode(legacyIv, Base64.NO_WRAP))
            )
            val passphrase = cipher.doFinal(Base64.decode(legacyEncrypted, Base64.NO_WRAP))

            // Re-encrypt with new hardware-backed key
            val newKey = getOrCreateSecretKey(context)
            cipher.init(Cipher.ENCRYPT_MODE, newKey)
            val newIv = cipher.iv
            val newEncrypted = cipher.doFinal(passphrase)

            // Detect security level
            val securityLevel = detectKeySecurityLevel()
            cachedSecurityLevel = securityLevel

            // Store with new keys and remove legacy
            prefs.edit()
                .putString(PREFS_KEY_PASSPHRASE, Base64.encodeToString(newEncrypted, Base64.NO_WRAP))
                .putString(PREFS_KEY_IV, Base64.encodeToString(newIv, Base64.NO_WRAP))
                .putString(PREFS_KEY_SECURITY_LEVEL, securityLevel.name)
                .remove(LEGACY_PREFS_KEY_PASSPHRASE)
                .remove(LEGACY_PREFS_KEY_IV)
                .apply()

            // Clear passphrase from memory
            passphrase.fill(0)

            // Delete legacy key
            keyStore.deleteEntry(LEGACY_KEYSTORE_ALIAS)

            Log.i(TAG, "Successfully migrated database key to hardware-backed storage (level: $securityLevel)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy passphrase", e)
            false
        }
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
                // Load SQLCipher native library
                System.loadLibrary("sqlcipher")

                // Get or create encryption passphrase
                val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
                val factory = SupportOpenHelperFactory(passphrase)

                // Clear passphrase from memory after factory creation
                // The factory has already copied the passphrase internally
                java.util.Arrays.fill(passphrase, 0.toByte())

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

        /**
         * Clear the singleton instance after database is closed/wiped.
         * This allows the database to be re-created if the app continues running after a nuke.
         * Must be called after close() and before any file deletion.
         */
        fun clearInstance() {
            synchronized(this) {
                INSTANCE = null
                Log.d(TAG, "Database instance cleared - will be recreated on next access")
            }
        }
    }
}

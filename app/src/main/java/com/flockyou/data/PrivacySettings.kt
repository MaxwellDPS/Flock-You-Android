package com.flockyou.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.flockyou.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.privacyDataStore: DataStore<Preferences> by preferencesDataStore(name = "privacy_settings")

/**
 * Data retention period options for detection history
 */
enum class RetentionPeriod(val hours: Int, val displayName: String) {
    FOUR_HOURS(4, "4 hours"),
    ONE_DAY(24, "1 day"),
    THREE_DAYS(72, "3 days"),
    SEVEN_DAYS(168, "7 days"),
    THIRTY_DAYS(720, "30 days");

    companion object {
        fun fromHours(hours: Int): RetentionPeriod {
            return entries.find { it.hours == hours } ?: THREE_DAYS
        }
    }
}

/**
 * Privacy settings for the app.
 * These settings control how detection data is stored and managed.
 */
data class PrivacySettings(
    /**
     * Priority 1: Ephemeral Mode
     * When enabled, detections are stored in RAM only and cleared on service restart.
     * No data is persisted to the database.
     */
    val ephemeralModeEnabled: Boolean = false,

    /**
     * Priority 3: Data Retention Period
     * How long to keep detection history before automatic deletion.
     * Default is 3 days for privacy-focused operation.
     */
    val retentionPeriod: RetentionPeriod = RetentionPeriod.THREE_DAYS,

    /**
     * Priority 4: Store Location with Detections
     * When disabled, latitude/longitude are NOT stored with detections.
     * Default: OFF for OEM builds, ON for sideload builds.
     */
    val storeLocationWithDetections: Boolean = !BuildConfig.IS_OEM_BUILD,

    /**
     * Priority 5: Screen Lock Auto-Purge
     * When enabled, all detection history is automatically deleted when the screen locks.
     */
    val autoPurgeOnScreenLock: Boolean = false,

    /**
     * Quick Wipe confirmation
     * When enabled, quick wipe requires confirmation before deleting data.
     */
    val quickWipeRequiresConfirmation: Boolean = true,

    /**
     * Ultrasonic Detection Opt-In
     * When enabled, the app will use the microphone to detect ultrasonic tracking beacons.
     * IMPORTANT: This feature requires explicit user consent due to microphone access.
     * Default: OFF - user must explicitly opt-in after understanding the risks.
     */
    val ultrasonicDetectionEnabled: Boolean = false,

    /**
     * User has explicitly acknowledged the ultrasonic detection risks.
     * This flag is set when the user reads and accepts the consent dialog.
     */
    val ultrasonicConsentAcknowledged: Boolean = false
)

@Singleton
class PrivacySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val EPHEMERAL_MODE_ENABLED = booleanPreferencesKey("ephemeral_mode_enabled")
        val RETENTION_PERIOD_HOURS = intPreferencesKey("retention_period_hours")
        val STORE_LOCATION = booleanPreferencesKey("store_location_with_detections")
        val AUTO_PURGE_ON_SCREEN_LOCK = booleanPreferencesKey("auto_purge_on_screen_lock")
        val QUICK_WIPE_REQUIRES_CONFIRMATION = booleanPreferencesKey("quick_wipe_requires_confirmation")
        val LOCATION_DEFAULT_SET = booleanPreferencesKey("location_default_set")
        val ULTRASONIC_DETECTION_ENABLED = booleanPreferencesKey("ultrasonic_detection_enabled")
        val ULTRASONIC_CONSENT_ACKNOWLEDGED = booleanPreferencesKey("ultrasonic_consent_acknowledged")
    }

    val settings: Flow<PrivacySettings> = context.privacyDataStore.data.map { preferences ->
        // Handle default for store location based on build type
        val locationDefault = if (preferences[PreferencesKeys.LOCATION_DEFAULT_SET] == true) {
            preferences[PreferencesKeys.STORE_LOCATION] ?: true
        } else {
            // First run: set default based on build type
            !BuildConfig.IS_OEM_BUILD
        }

        PrivacySettings(
            ephemeralModeEnabled = preferences[PreferencesKeys.EPHEMERAL_MODE_ENABLED] ?: false,
            retentionPeriod = RetentionPeriod.fromHours(
                preferences[PreferencesKeys.RETENTION_PERIOD_HOURS] ?: RetentionPeriod.THREE_DAYS.hours
            ),
            storeLocationWithDetections = locationDefault,
            autoPurgeOnScreenLock = preferences[PreferencesKeys.AUTO_PURGE_ON_SCREEN_LOCK] ?: false,
            quickWipeRequiresConfirmation = preferences[PreferencesKeys.QUICK_WIPE_REQUIRES_CONFIRMATION] ?: true,
            ultrasonicDetectionEnabled = preferences[PreferencesKeys.ULTRASONIC_DETECTION_ENABLED] ?: false,
            ultrasonicConsentAcknowledged = preferences[PreferencesKeys.ULTRASONIC_CONSENT_ACKNOWLEDGED] ?: false
        )
    }

    suspend fun setEphemeralModeEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.EPHEMERAL_MODE_ENABLED] = enabled
        }
    }

    suspend fun setRetentionPeriod(period: RetentionPeriod) {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.RETENTION_PERIOD_HOURS] = period.hours
        }
    }

    suspend fun setStoreLocationWithDetections(enabled: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.STORE_LOCATION] = enabled
            preferences[PreferencesKeys.LOCATION_DEFAULT_SET] = true
        }
    }

    suspend fun setAutoPurgeOnScreenLock(enabled: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_PURGE_ON_SCREEN_LOCK] = enabled
        }
    }

    suspend fun setQuickWipeRequiresConfirmation(required: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.QUICK_WIPE_REQUIRES_CONFIRMATION] = required
        }
    }

    /**
     * Enable or disable ultrasonic detection.
     * Requires consent to be acknowledged first.
     */
    suspend fun setUltrasonicDetectionEnabled(enabled: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.ULTRASONIC_DETECTION_ENABLED] = enabled
        }
    }

    /**
     * Set whether user has acknowledged the ultrasonic detection consent/risks.
     */
    suspend fun setUltrasonicConsentAcknowledged(acknowledged: Boolean) {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.ULTRASONIC_CONSENT_ACKNOWLEDGED] = acknowledged
        }
    }

    /**
     * Enable ultrasonic detection with consent acknowledgment in a single atomic operation.
     */
    suspend fun enableUltrasonicWithConsent() {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.ULTRASONIC_CONSENT_ACKNOWLEDGED] = true
            preferences[PreferencesKeys.ULTRASONIC_DETECTION_ENABLED] = true
        }
    }

    /**
     * Disable ultrasonic detection (keeps consent acknowledged for future re-enablement).
     */
    suspend fun disableUltrasonic() {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.ULTRASONIC_DETECTION_ENABLED] = false
        }
    }

    /**
     * Revoke ultrasonic consent and disable detection.
     */
    suspend fun revokeUltrasonicConsent() {
        context.privacyDataStore.edit { preferences ->
            preferences[PreferencesKeys.ULTRASONIC_CONSENT_ACKNOWLEDGED] = false
            preferences[PreferencesKeys.ULTRASONIC_DETECTION_ENABLED] = false
        }
    }

    suspend fun updateSettings(
        ephemeralModeEnabled: Boolean? = null,
        retentionPeriod: RetentionPeriod? = null,
        storeLocationWithDetections: Boolean? = null,
        autoPurgeOnScreenLock: Boolean? = null,
        quickWipeRequiresConfirmation: Boolean? = null
    ) {
        context.privacyDataStore.edit { preferences ->
            ephemeralModeEnabled?.let { preferences[PreferencesKeys.EPHEMERAL_MODE_ENABLED] = it }
            retentionPeriod?.let { preferences[PreferencesKeys.RETENTION_PERIOD_HOURS] = it.hours }
            storeLocationWithDetections?.let {
                preferences[PreferencesKeys.STORE_LOCATION] = it
                preferences[PreferencesKeys.LOCATION_DEFAULT_SET] = true
            }
            autoPurgeOnScreenLock?.let { preferences[PreferencesKeys.AUTO_PURGE_ON_SCREEN_LOCK] = it }
            quickWipeRequiresConfirmation?.let { preferences[PreferencesKeys.QUICK_WIPE_REQUIRES_CONFIRMATION] = it }
        }
    }
}

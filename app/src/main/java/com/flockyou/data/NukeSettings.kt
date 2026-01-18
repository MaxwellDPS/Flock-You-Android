package com.flockyou.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nukeSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nuke_settings")

/**
 * Represents a geographic danger zone that triggers a nuke when entered.
 */
data class DangerZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val enabled: Boolean = true
)

/**
 * Complete settings for all nuke/wipe triggers.
 * All triggers are toggleable and thresholds are tunable.
 */
data class NukeSettings(
    // Master enable - disables all nuke triggers when false
    val nukeEnabled: Boolean = false,

    // ========================================
    // USB/ADB Connection Trigger
    // ========================================
    val usbTriggerEnabled: Boolean = false,
    /** Trigger on USB data connection (not just charging) */
    val usbTriggerOnDataConnection: Boolean = true,
    /** Trigger when ADB/USB debugging is detected */
    val usbTriggerOnAdbConnection: Boolean = true,
    /** Delay before nuke (allows user to cancel) in seconds */
    val usbTriggerDelaySeconds: Int = 5,

    // ========================================
    // Failed Authentication Trigger
    // ========================================
    val failedAuthTriggerEnabled: Boolean = false,
    /** Number of failed attempts before triggering nuke */
    val failedAuthThreshold: Int = 10,
    /** Reset counter after this many hours */
    val failedAuthResetHours: Int = 24,

    // ========================================
    // Dead Man's Switch (Time Bomb)
    // ========================================
    val deadManSwitchEnabled: Boolean = false,
    /** Hours without authentication before nuke (minimum 1 hour) */
    val deadManSwitchHours: Int = 72,
    /** Show warning notification before nuke */
    val deadManSwitchWarningEnabled: Boolean = true,
    /** Hours before nuke to show warning */
    val deadManSwitchWarningHours: Int = 12,

    // ========================================
    // Network Isolation Detection
    // ========================================
    val networkIsolationTriggerEnabled: Boolean = false,
    /** Hours of airplane mode + no network before nuke */
    val networkIsolationHours: Int = 4,
    /** Only trigger if both airplane mode AND no connectivity */
    val networkIsolationRequireBoth: Boolean = true,

    // ========================================
    // SIM Removal Detection
    // ========================================
    val simRemovalTriggerEnabled: Boolean = false,
    /** Delay after SIM removal before nuke (in seconds) */
    val simRemovalDelaySeconds: Int = 300, // 5 minutes
    /** Trigger if SIM was present at last boot but now absent */
    val simRemovalTriggerOnPreviouslyPresent: Boolean = true,

    // ========================================
    // Boot Count / Rapid Reboot Detection
    // ========================================
    val rapidRebootTriggerEnabled: Boolean = false,
    /** Number of reboots within threshold period to trigger */
    val rapidRebootCount: Int = 3,
    /** Time window in minutes for counting rapid reboots */
    val rapidRebootWindowMinutes: Int = 10,

    // ========================================
    // Geofence (Location-Based) Trigger
    // ========================================
    val geofenceTriggerEnabled: Boolean = false,
    /** List of danger zones (serialized as JSON) */
    val dangerZonesJson: String = "[]",
    /** Delay before nuke when entering danger zone (seconds) */
    val geofenceTriggerDelaySeconds: Int = 30,

    // ========================================
    // Duress PIN
    // ========================================
    val duressPinEnabled: Boolean = false,
    /** Hash of the duress PIN (stored securely, not plain text) */
    val duressPinHash: String = "",
    /** Salt for duress PIN hash */
    val duressPinSalt: String = "",
    /** Show fake empty app after duress PIN entry */
    val duressPinShowFakeApp: Boolean = true,

    // ========================================
    // What to wipe
    // ========================================
    /** Wipe the detection database */
    val wipeDatabase: Boolean = true,
    /** Wipe all app settings/preferences */
    val wipeSettings: Boolean = true,
    /** Wipe cached data */
    val wipeCache: Boolean = true,
    /** Overwrite with random data before deletion (more secure but slower) */
    val secureWipe: Boolean = true,
    /** Number of overwrite passes for secure wipe */
    val secureWipePasses: Int = 3
) {
    /**
     * Parse danger zones from JSON.
     */
    fun getDangerZones(): List<DangerZone> {
        return try {
            val type = object : TypeToken<List<DangerZone>>() {}.type
            Gson().fromJson<List<DangerZone>>(dangerZonesJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Check if any trigger is enabled.
     */
    fun hasAnyTriggerEnabled(): Boolean {
        return nukeEnabled && (
            usbTriggerEnabled ||
            failedAuthTriggerEnabled ||
            deadManSwitchEnabled ||
            networkIsolationTriggerEnabled ||
            simRemovalTriggerEnabled ||
            rapidRebootTriggerEnabled ||
            geofenceTriggerEnabled ||
            duressPinEnabled
        )
    }
}

/**
 * Repository for managing nuke settings via DataStore.
 */
class NukeSettingsRepository(
    private val context: Context
) {
    private object PreferencesKeys {
        val NUKE_ENABLED = booleanPreferencesKey("nuke_enabled")

        // USB trigger
        val USB_TRIGGER_ENABLED = booleanPreferencesKey("usb_trigger_enabled")
        val USB_TRIGGER_ON_DATA_CONNECTION = booleanPreferencesKey("usb_trigger_on_data_connection")
        val USB_TRIGGER_ON_ADB_CONNECTION = booleanPreferencesKey("usb_trigger_on_adb_connection")
        val USB_TRIGGER_DELAY_SECONDS = intPreferencesKey("usb_trigger_delay_seconds")

        // Failed auth trigger
        val FAILED_AUTH_TRIGGER_ENABLED = booleanPreferencesKey("failed_auth_trigger_enabled")
        val FAILED_AUTH_THRESHOLD = intPreferencesKey("failed_auth_threshold")
        val FAILED_AUTH_RESET_HOURS = intPreferencesKey("failed_auth_reset_hours")

        // Dead man's switch
        val DEAD_MAN_SWITCH_ENABLED = booleanPreferencesKey("dead_man_switch_enabled")
        val DEAD_MAN_SWITCH_HOURS = intPreferencesKey("dead_man_switch_hours")
        val DEAD_MAN_SWITCH_WARNING_ENABLED = booleanPreferencesKey("dead_man_switch_warning_enabled")
        val DEAD_MAN_SWITCH_WARNING_HOURS = intPreferencesKey("dead_man_switch_warning_hours")

        // Network isolation
        val NETWORK_ISOLATION_TRIGGER_ENABLED = booleanPreferencesKey("network_isolation_trigger_enabled")
        val NETWORK_ISOLATION_HOURS = intPreferencesKey("network_isolation_hours")
        val NETWORK_ISOLATION_REQUIRE_BOTH = booleanPreferencesKey("network_isolation_require_both")

        // SIM removal
        val SIM_REMOVAL_TRIGGER_ENABLED = booleanPreferencesKey("sim_removal_trigger_enabled")
        val SIM_REMOVAL_DELAY_SECONDS = intPreferencesKey("sim_removal_delay_seconds")
        val SIM_REMOVAL_TRIGGER_ON_PREVIOUSLY_PRESENT = booleanPreferencesKey("sim_removal_trigger_on_previously_present")

        // Rapid reboot
        val RAPID_REBOOT_TRIGGER_ENABLED = booleanPreferencesKey("rapid_reboot_trigger_enabled")
        val RAPID_REBOOT_COUNT = intPreferencesKey("rapid_reboot_count")
        val RAPID_REBOOT_WINDOW_MINUTES = intPreferencesKey("rapid_reboot_window_minutes")

        // Geofence
        val GEOFENCE_TRIGGER_ENABLED = booleanPreferencesKey("geofence_trigger_enabled")
        val DANGER_ZONES_JSON = stringPreferencesKey("danger_zones_json")
        val GEOFENCE_TRIGGER_DELAY_SECONDS = intPreferencesKey("geofence_trigger_delay_seconds")

        // Duress PIN
        val DURESS_PIN_ENABLED = booleanPreferencesKey("duress_pin_enabled")
        val DURESS_PIN_HASH = stringPreferencesKey("duress_pin_hash")
        val DURESS_PIN_SALT = stringPreferencesKey("duress_pin_salt")
        val DURESS_PIN_SHOW_FAKE_APP = booleanPreferencesKey("duress_pin_show_fake_app")

        // Wipe options
        val WIPE_DATABASE = booleanPreferencesKey("wipe_database")
        val WIPE_SETTINGS = booleanPreferencesKey("wipe_settings")
        val WIPE_CACHE = booleanPreferencesKey("wipe_cache")
        val SECURE_WIPE = booleanPreferencesKey("secure_wipe")
        val SECURE_WIPE_PASSES = intPreferencesKey("secure_wipe_passes")
    }

    val settings: Flow<NukeSettings> = context.nukeSettingsDataStore.data.map { preferences ->
        NukeSettings(
            nukeEnabled = preferences[PreferencesKeys.NUKE_ENABLED] ?: false,

            // USB trigger
            usbTriggerEnabled = preferences[PreferencesKeys.USB_TRIGGER_ENABLED] ?: false,
            usbTriggerOnDataConnection = preferences[PreferencesKeys.USB_TRIGGER_ON_DATA_CONNECTION] ?: true,
            usbTriggerOnAdbConnection = preferences[PreferencesKeys.USB_TRIGGER_ON_ADB_CONNECTION] ?: true,
            usbTriggerDelaySeconds = preferences[PreferencesKeys.USB_TRIGGER_DELAY_SECONDS] ?: 5,

            // Failed auth trigger
            failedAuthTriggerEnabled = preferences[PreferencesKeys.FAILED_AUTH_TRIGGER_ENABLED] ?: false,
            failedAuthThreshold = preferences[PreferencesKeys.FAILED_AUTH_THRESHOLD] ?: 10,
            failedAuthResetHours = preferences[PreferencesKeys.FAILED_AUTH_RESET_HOURS] ?: 24,

            // Dead man's switch
            deadManSwitchEnabled = preferences[PreferencesKeys.DEAD_MAN_SWITCH_ENABLED] ?: false,
            deadManSwitchHours = preferences[PreferencesKeys.DEAD_MAN_SWITCH_HOURS] ?: 72,
            deadManSwitchWarningEnabled = preferences[PreferencesKeys.DEAD_MAN_SWITCH_WARNING_ENABLED] ?: true,
            deadManSwitchWarningHours = preferences[PreferencesKeys.DEAD_MAN_SWITCH_WARNING_HOURS] ?: 12,

            // Network isolation
            networkIsolationTriggerEnabled = preferences[PreferencesKeys.NETWORK_ISOLATION_TRIGGER_ENABLED] ?: false,
            networkIsolationHours = preferences[PreferencesKeys.NETWORK_ISOLATION_HOURS] ?: 4,
            networkIsolationRequireBoth = preferences[PreferencesKeys.NETWORK_ISOLATION_REQUIRE_BOTH] ?: true,

            // SIM removal
            simRemovalTriggerEnabled = preferences[PreferencesKeys.SIM_REMOVAL_TRIGGER_ENABLED] ?: false,
            simRemovalDelaySeconds = preferences[PreferencesKeys.SIM_REMOVAL_DELAY_SECONDS] ?: 300,
            simRemovalTriggerOnPreviouslyPresent = preferences[PreferencesKeys.SIM_REMOVAL_TRIGGER_ON_PREVIOUSLY_PRESENT] ?: true,

            // Rapid reboot
            rapidRebootTriggerEnabled = preferences[PreferencesKeys.RAPID_REBOOT_TRIGGER_ENABLED] ?: false,
            rapidRebootCount = preferences[PreferencesKeys.RAPID_REBOOT_COUNT] ?: 3,
            rapidRebootWindowMinutes = preferences[PreferencesKeys.RAPID_REBOOT_WINDOW_MINUTES] ?: 10,

            // Geofence
            geofenceTriggerEnabled = preferences[PreferencesKeys.GEOFENCE_TRIGGER_ENABLED] ?: false,
            dangerZonesJson = preferences[PreferencesKeys.DANGER_ZONES_JSON] ?: "[]",
            geofenceTriggerDelaySeconds = preferences[PreferencesKeys.GEOFENCE_TRIGGER_DELAY_SECONDS] ?: 30,

            // Duress PIN
            duressPinEnabled = preferences[PreferencesKeys.DURESS_PIN_ENABLED] ?: false,
            duressPinHash = preferences[PreferencesKeys.DURESS_PIN_HASH] ?: "",
            duressPinSalt = preferences[PreferencesKeys.DURESS_PIN_SALT] ?: "",
            duressPinShowFakeApp = preferences[PreferencesKeys.DURESS_PIN_SHOW_FAKE_APP] ?: true,

            // Wipe options
            wipeDatabase = preferences[PreferencesKeys.WIPE_DATABASE] ?: true,
            wipeSettings = preferences[PreferencesKeys.WIPE_SETTINGS] ?: true,
            wipeCache = preferences[PreferencesKeys.WIPE_CACHE] ?: true,
            secureWipe = preferences[PreferencesKeys.SECURE_WIPE] ?: true,
            secureWipePasses = preferences[PreferencesKeys.SECURE_WIPE_PASSES] ?: 3
        )
    }

    // ========================================
    // Master Enable
    // ========================================

    suspend fun setNukeEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.NUKE_ENABLED] = enabled
        }
    }

    // ========================================
    // USB Trigger Settings
    // ========================================

    suspend fun setUsbTriggerEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.USB_TRIGGER_ENABLED] = enabled
        }
    }

    suspend fun setUsbTriggerOnDataConnection(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.USB_TRIGGER_ON_DATA_CONNECTION] = enabled
        }
    }

    suspend fun setUsbTriggerOnAdbConnection(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.USB_TRIGGER_ON_ADB_CONNECTION] = enabled
        }
    }

    suspend fun setUsbTriggerDelaySeconds(seconds: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.USB_TRIGGER_DELAY_SECONDS] = seconds.coerceIn(0, 60)
        }
    }

    // ========================================
    // Failed Auth Trigger Settings
    // ========================================

    suspend fun setFailedAuthTriggerEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.FAILED_AUTH_TRIGGER_ENABLED] = enabled
        }
    }

    suspend fun setFailedAuthThreshold(threshold: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.FAILED_AUTH_THRESHOLD] = threshold.coerceIn(3, 100)
        }
    }

    suspend fun setFailedAuthResetHours(hours: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.FAILED_AUTH_RESET_HOURS] = hours.coerceIn(1, 168)
        }
    }

    // ========================================
    // Dead Man's Switch Settings
    // ========================================

    suspend fun setDeadManSwitchEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DEAD_MAN_SWITCH_ENABLED] = enabled
        }
    }

    suspend fun setDeadManSwitchHours(hours: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DEAD_MAN_SWITCH_HOURS] = hours.coerceIn(1, 720) // 1 hour to 30 days
        }
    }

    suspend fun setDeadManSwitchWarningEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DEAD_MAN_SWITCH_WARNING_ENABLED] = enabled
        }
    }

    suspend fun setDeadManSwitchWarningHours(hours: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DEAD_MAN_SWITCH_WARNING_HOURS] = hours.coerceIn(1, 48)
        }
    }

    // ========================================
    // Network Isolation Settings
    // ========================================

    suspend fun setNetworkIsolationTriggerEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.NETWORK_ISOLATION_TRIGGER_ENABLED] = enabled
        }
    }

    suspend fun setNetworkIsolationHours(hours: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.NETWORK_ISOLATION_HOURS] = hours.coerceIn(1, 168)
        }
    }

    suspend fun setNetworkIsolationRequireBoth(requireBoth: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.NETWORK_ISOLATION_REQUIRE_BOTH] = requireBoth
        }
    }

    // ========================================
    // SIM Removal Settings
    // ========================================

    suspend fun setSimRemovalTriggerEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.SIM_REMOVAL_TRIGGER_ENABLED] = enabled
        }
    }

    suspend fun setSimRemovalDelaySeconds(seconds: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.SIM_REMOVAL_DELAY_SECONDS] = seconds.coerceIn(0, 3600)
        }
    }

    suspend fun setSimRemovalTriggerOnPreviouslyPresent(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.SIM_REMOVAL_TRIGGER_ON_PREVIOUSLY_PRESENT] = enabled
        }
    }

    // ========================================
    // Rapid Reboot Settings
    // ========================================

    suspend fun setRapidRebootTriggerEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.RAPID_REBOOT_TRIGGER_ENABLED] = enabled
        }
    }

    suspend fun setRapidRebootCount(count: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.RAPID_REBOOT_COUNT] = count.coerceIn(2, 10)
        }
    }

    suspend fun setRapidRebootWindowMinutes(minutes: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.RAPID_REBOOT_WINDOW_MINUTES] = minutes.coerceIn(1, 60)
        }
    }

    // ========================================
    // Geofence Settings
    // ========================================

    suspend fun setGeofenceTriggerEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.GEOFENCE_TRIGGER_ENABLED] = enabled
        }
    }

    suspend fun setDangerZones(zones: List<DangerZone>) {
        val json = Gson().toJson(zones)
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DANGER_ZONES_JSON] = json
        }
    }

    suspend fun addDangerZone(zone: DangerZone) {
        context.nukeSettingsDataStore.edit { preferences ->
            val currentJson = preferences[PreferencesKeys.DANGER_ZONES_JSON] ?: "[]"
            val zones = try {
                val type = object : TypeToken<List<DangerZone>>() {}.type
                Gson().fromJson<List<DangerZone>>(currentJson, type)?.toMutableList() ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
            zones.add(zone)
            preferences[PreferencesKeys.DANGER_ZONES_JSON] = Gson().toJson(zones)
        }
    }

    suspend fun removeDangerZone(zoneId: String) {
        context.nukeSettingsDataStore.edit { preferences ->
            val currentJson = preferences[PreferencesKeys.DANGER_ZONES_JSON] ?: "[]"
            val zones = try {
                val type = object : TypeToken<List<DangerZone>>() {}.type
                Gson().fromJson<List<DangerZone>>(currentJson, type)?.toMutableList() ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
            zones.removeAll { it.id == zoneId }
            preferences[PreferencesKeys.DANGER_ZONES_JSON] = Gson().toJson(zones)
        }
    }

    suspend fun setGeofenceTriggerDelaySeconds(seconds: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.GEOFENCE_TRIGGER_DELAY_SECONDS] = seconds.coerceIn(0, 300)
        }
    }

    // ========================================
    // Duress PIN Settings
    // ========================================

    suspend fun setDuressPinEnabled(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DURESS_PIN_ENABLED] = enabled
        }
    }

    suspend fun setDuressPinHash(hash: String, salt: String) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DURESS_PIN_HASH] = hash
            preferences[PreferencesKeys.DURESS_PIN_SALT] = salt
        }
    }

    suspend fun clearDuressPin() {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DURESS_PIN_HASH] = ""
            preferences[PreferencesKeys.DURESS_PIN_SALT] = ""
            preferences[PreferencesKeys.DURESS_PIN_ENABLED] = false
        }
    }

    suspend fun setDuressPinShowFakeApp(showFakeApp: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.DURESS_PIN_SHOW_FAKE_APP] = showFakeApp
        }
    }

    // ========================================
    // Wipe Options
    // ========================================

    suspend fun setWipeDatabase(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.WIPE_DATABASE] = enabled
        }
    }

    suspend fun setWipeSettings(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.WIPE_SETTINGS] = enabled
        }
    }

    suspend fun setWipeCache(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.WIPE_CACHE] = enabled
        }
    }

    suspend fun setSecureWipe(enabled: Boolean) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.SECURE_WIPE] = enabled
        }
    }

    suspend fun setSecureWipePasses(passes: Int) {
        context.nukeSettingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.SECURE_WIPE_PASSES] = passes.coerceIn(1, 7)
        }
    }

    // ========================================
    // Batch Updates
    // ========================================

    suspend fun updateUsbTriggerSettings(
        enabled: Boolean? = null,
        onDataConnection: Boolean? = null,
        onAdbConnection: Boolean? = null,
        delaySeconds: Int? = null
    ) {
        context.nukeSettingsDataStore.edit { preferences ->
            enabled?.let { preferences[PreferencesKeys.USB_TRIGGER_ENABLED] = it }
            onDataConnection?.let { preferences[PreferencesKeys.USB_TRIGGER_ON_DATA_CONNECTION] = it }
            onAdbConnection?.let { preferences[PreferencesKeys.USB_TRIGGER_ON_ADB_CONNECTION] = it }
            delaySeconds?.let { preferences[PreferencesKeys.USB_TRIGGER_DELAY_SECONDS] = it.coerceIn(0, 60) }
        }
    }

    suspend fun updateFailedAuthSettings(
        enabled: Boolean? = null,
        threshold: Int? = null,
        resetHours: Int? = null
    ) {
        context.nukeSettingsDataStore.edit { preferences ->
            enabled?.let { preferences[PreferencesKeys.FAILED_AUTH_TRIGGER_ENABLED] = it }
            threshold?.let { preferences[PreferencesKeys.FAILED_AUTH_THRESHOLD] = it.coerceIn(3, 100) }
            resetHours?.let { preferences[PreferencesKeys.FAILED_AUTH_RESET_HOURS] = it.coerceIn(1, 168) }
        }
    }

    suspend fun updateDeadManSwitchSettings(
        enabled: Boolean? = null,
        hours: Int? = null,
        warningEnabled: Boolean? = null,
        warningHours: Int? = null
    ) {
        context.nukeSettingsDataStore.edit { preferences ->
            enabled?.let { preferences[PreferencesKeys.DEAD_MAN_SWITCH_ENABLED] = it }
            hours?.let { preferences[PreferencesKeys.DEAD_MAN_SWITCH_HOURS] = it.coerceIn(1, 720) }
            warningEnabled?.let { preferences[PreferencesKeys.DEAD_MAN_SWITCH_WARNING_ENABLED] = it }
            warningHours?.let { preferences[PreferencesKeys.DEAD_MAN_SWITCH_WARNING_HOURS] = it.coerceIn(1, 48) }
        }
    }

    suspend fun updateWipeOptions(
        wipeDatabase: Boolean? = null,
        wipeSettings: Boolean? = null,
        wipeCache: Boolean? = null,
        secureWipe: Boolean? = null,
        secureWipePasses: Int? = null
    ) {
        context.nukeSettingsDataStore.edit { preferences ->
            wipeDatabase?.let { preferences[PreferencesKeys.WIPE_DATABASE] = it }
            wipeSettings?.let { preferences[PreferencesKeys.WIPE_SETTINGS] = it }
            wipeCache?.let { preferences[PreferencesKeys.WIPE_CACHE] = it }
            secureWipe?.let { preferences[PreferencesKeys.SECURE_WIPE] = it }
            secureWipePasses?.let { preferences[PreferencesKeys.SECURE_WIPE_PASSES] = it.coerceIn(1, 7) }
        }
    }
}

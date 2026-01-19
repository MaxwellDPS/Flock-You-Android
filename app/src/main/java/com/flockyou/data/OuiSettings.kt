package com.flockyou.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ouiDataStore: DataStore<Preferences> by preferencesDataStore(name = "oui_settings")

data class OuiSettings(
    val autoUpdateEnabled: Boolean = true,
    val updateIntervalHours: Int = 168, // 7 days default
    val lastUpdateTimestamp: Long = 0L,
    val lastUpdateSuccess: Boolean = true,
    val lastUpdateError: String? = null,
    val totalEntries: Int = 0,
    val useWifiOnly: Boolean = true, // Only download on WiFi
    val lastUpdateFromBundled: Boolean = false // Was last update from bundled assets
)

enum class OuiUpdateInterval(val hours: Int, val displayName: String) {
    DAILY(24, "Daily"),
    WEEKLY(168, "Weekly"),
    BIWEEKLY(336, "Every 2 Weeks"),
    MONTHLY(720, "Monthly"),
    MANUAL(0, "Manual Only")
}

@Singleton
class OuiSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val AUTO_UPDATE_ENABLED = booleanPreferencesKey("oui_auto_update_enabled")
        val UPDATE_INTERVAL_HOURS = intPreferencesKey("oui_update_interval_hours")
        val LAST_UPDATE_TIMESTAMP = longPreferencesKey("oui_last_update_timestamp")
        val LAST_UPDATE_SUCCESS = booleanPreferencesKey("oui_last_update_success")
        val LAST_UPDATE_ERROR = stringPreferencesKey("oui_last_update_error")
        val TOTAL_ENTRIES = intPreferencesKey("oui_total_entries")
        val USE_WIFI_ONLY = booleanPreferencesKey("oui_use_wifi_only")
        val LAST_UPDATE_FROM_BUNDLED = booleanPreferencesKey("oui_last_update_from_bundled")
    }

    val settings: Flow<OuiSettings> = context.ouiDataStore.data.map { prefs ->
        OuiSettings(
            autoUpdateEnabled = prefs[PreferencesKeys.AUTO_UPDATE_ENABLED] ?: true,
            updateIntervalHours = prefs[PreferencesKeys.UPDATE_INTERVAL_HOURS] ?: 168,
            lastUpdateTimestamp = prefs[PreferencesKeys.LAST_UPDATE_TIMESTAMP] ?: 0L,
            lastUpdateSuccess = prefs[PreferencesKeys.LAST_UPDATE_SUCCESS] ?: true,
            lastUpdateError = prefs[PreferencesKeys.LAST_UPDATE_ERROR],
            totalEntries = prefs[PreferencesKeys.TOTAL_ENTRIES] ?: 0,
            useWifiOnly = prefs[PreferencesKeys.USE_WIFI_ONLY] ?: true,
            lastUpdateFromBundled = prefs[PreferencesKeys.LAST_UPDATE_FROM_BUNDLED] ?: false
        )
    }

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        context.ouiDataStore.edit { prefs ->
            prefs[PreferencesKeys.AUTO_UPDATE_ENABLED] = enabled
        }
    }

    suspend fun setUpdateInterval(hours: Int) {
        context.ouiDataStore.edit { prefs ->
            prefs[PreferencesKeys.UPDATE_INTERVAL_HOURS] = hours
        }
    }

    suspend fun setUseWifiOnly(wifiOnly: Boolean) {
        context.ouiDataStore.edit { prefs ->
            prefs[PreferencesKeys.USE_WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun recordUpdateResult(success: Boolean, error: String? = null, entryCount: Int = 0, fromBundled: Boolean = false) {
        context.ouiDataStore.edit { prefs ->
            prefs[PreferencesKeys.LAST_UPDATE_TIMESTAMP] = System.currentTimeMillis()
            prefs[PreferencesKeys.LAST_UPDATE_SUCCESS] = success
            prefs[PreferencesKeys.LAST_UPDATE_FROM_BUNDLED] = fromBundled
            if (error != null) {
                prefs[PreferencesKeys.LAST_UPDATE_ERROR] = error
            } else {
                prefs.remove(PreferencesKeys.LAST_UPDATE_ERROR)
            }
            if (entryCount > 0) {
                prefs[PreferencesKeys.TOTAL_ENTRIES] = entryCount
            }
        }
    }
}

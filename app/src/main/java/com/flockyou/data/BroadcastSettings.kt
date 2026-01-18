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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "broadcast_settings")

/**
 * Settings for broadcasting detection events to automation apps like Tasker, Automate, etc.
 */
data class BroadcastSettings(
    val enabled: Boolean = false,
    val broadcastOnDetection: Boolean = true,
    val broadcastOnCellularAnomaly: Boolean = true,
    val broadcastOnSatelliteAnomaly: Boolean = true,
    val broadcastOnWifiAnomaly: Boolean = true,
    val broadcastOnRfAnomaly: Boolean = true,
    val broadcastOnUltrasonic: Boolean = true,
    val includeLocation: Boolean = false,
    val minThreatLevel: String = "LOW" // LOW, MEDIUM, HIGH, CRITICAL
) {
    companion object {
        // Broadcast action constants
        const val ACTION_DETECTION = "com.flockyou.DETECTION"
        const val ACTION_CELLULAR_ANOMALY = "com.flockyou.CELLULAR_ANOMALY"
        const val ACTION_SATELLITE_ANOMALY = "com.flockyou.SATELLITE_ANOMALY"
        const val ACTION_WIFI_ANOMALY = "com.flockyou.WIFI_ANOMALY"
        const val ACTION_RF_ANOMALY = "com.flockyou.RF_ANOMALY"
        const val ACTION_ULTRASONIC = "com.flockyou.ULTRASONIC"

        // Extra keys for broadcast intents
        const val EXTRA_DETECTION_ID = "detection_id"
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_MAC_ADDRESS = "mac_address"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_THREAT_LEVEL = "threat_level"
        const val EXTRA_THREAT_SCORE = "threat_score"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_DETECTION_METHOD = "detection_method"
        const val EXTRA_SIGNAL_STRENGTH = "signal_strength"
        const val EXTRA_RSSI = "rssi"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_MANUFACTURER = "manufacturer"
        const val EXTRA_ANOMALY_TYPE = "anomaly_type"
        const val EXTRA_ANOMALY_DESCRIPTION = "anomaly_description"
    }
}

@Singleton
class BroadcastSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val ENABLED = booleanPreferencesKey("broadcast_enabled")
        val BROADCAST_ON_DETECTION = booleanPreferencesKey("broadcast_on_detection")
        val BROADCAST_ON_CELLULAR = booleanPreferencesKey("broadcast_on_cellular_anomaly")
        val BROADCAST_ON_SATELLITE = booleanPreferencesKey("broadcast_on_satellite_anomaly")
        val BROADCAST_ON_WIFI = booleanPreferencesKey("broadcast_on_wifi_anomaly")
        val BROADCAST_ON_RF = booleanPreferencesKey("broadcast_on_rf_anomaly")
        val BROADCAST_ON_ULTRASONIC = booleanPreferencesKey("broadcast_on_ultrasonic")
        val INCLUDE_LOCATION = booleanPreferencesKey("broadcast_include_location")
        val MIN_THREAT_LEVEL = stringPreferencesKey("broadcast_min_threat_level")
    }

    val settings: Flow<BroadcastSettings> = context.dataStore.data.map { preferences ->
        BroadcastSettings(
            enabled = preferences[PreferencesKeys.ENABLED] ?: false,
            broadcastOnDetection = preferences[PreferencesKeys.BROADCAST_ON_DETECTION] ?: true,
            broadcastOnCellularAnomaly = preferences[PreferencesKeys.BROADCAST_ON_CELLULAR] ?: true,
            broadcastOnSatelliteAnomaly = preferences[PreferencesKeys.BROADCAST_ON_SATELLITE] ?: true,
            broadcastOnWifiAnomaly = preferences[PreferencesKeys.BROADCAST_ON_WIFI] ?: true,
            broadcastOnRfAnomaly = preferences[PreferencesKeys.BROADCAST_ON_RF] ?: true,
            broadcastOnUltrasonic = preferences[PreferencesKeys.BROADCAST_ON_ULTRASONIC] ?: true,
            includeLocation = preferences[PreferencesKeys.INCLUDE_LOCATION] ?: false,
            minThreatLevel = preferences[PreferencesKeys.MIN_THREAT_LEVEL] ?: "LOW"
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ENABLED] = enabled
        }
    }

    suspend fun setBroadcastOnDetection(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BROADCAST_ON_DETECTION] = enabled
        }
    }

    suspend fun setBroadcastOnCellular(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BROADCAST_ON_CELLULAR] = enabled
        }
    }

    suspend fun setBroadcastOnSatellite(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BROADCAST_ON_SATELLITE] = enabled
        }
    }

    suspend fun setBroadcastOnWifi(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BROADCAST_ON_WIFI] = enabled
        }
    }

    suspend fun setBroadcastOnRf(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BROADCAST_ON_RF] = enabled
        }
    }

    suspend fun setBroadcastOnUltrasonic(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BROADCAST_ON_ULTRASONIC] = enabled
        }
    }

    suspend fun setIncludeLocation(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INCLUDE_LOCATION] = enabled
        }
    }

    suspend fun setMinThreatLevel(level: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MIN_THREAT_LEVEL] = level
        }
    }
}

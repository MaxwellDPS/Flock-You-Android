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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "scan_settings")

data class ScanSettings(
    val wifiScanIntervalSeconds: Int = 35,
    val bleScanDurationSeconds: Int = 10,
    val inactiveTimeoutSeconds: Int = 60,
    val seenDeviceTimeoutMinutes: Int = 5,
    val enableBleScanning: Boolean = true,
    val enableWifiScanning: Boolean = true,
    val trackSeenDevices: Boolean = true,
    // RF detection timing
    val rfScanIntervalSeconds: Int = 30,
    // Ultrasonic detection timing
    val ultrasonicScanIntervalSeconds: Int = 30,
    val ultrasonicScanDurationSeconds: Int = 5,
    // GNSS/Satellite detection timing
    val gnssScanIntervalSeconds: Int = 5,
    val satelliteScanIntervalSeconds: Int = 10,
    // Cellular detection timing
    val cellularScanIntervalSeconds: Int = 5
)

@Singleton
class ScanSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val WIFI_SCAN_INTERVAL = intPreferencesKey("wifi_scan_interval_seconds")
        val BLE_SCAN_DURATION = intPreferencesKey("ble_scan_duration_seconds")
        val INACTIVE_TIMEOUT = intPreferencesKey("inactive_timeout_seconds")
        val SEEN_DEVICE_TIMEOUT = intPreferencesKey("seen_device_timeout_minutes")
        val ENABLE_BLE = booleanPreferencesKey("enable_ble_scanning")
        val ENABLE_WIFI = booleanPreferencesKey("enable_wifi_scanning")
        val TRACK_SEEN_DEVICES = booleanPreferencesKey("track_seen_devices")
        // RF detection timing
        val RF_SCAN_INTERVAL = intPreferencesKey("rf_scan_interval_seconds")
        // Ultrasonic detection timing
        val ULTRASONIC_SCAN_INTERVAL = intPreferencesKey("ultrasonic_scan_interval_seconds")
        val ULTRASONIC_SCAN_DURATION = intPreferencesKey("ultrasonic_scan_duration_seconds")
        // GNSS/Satellite detection timing
        val GNSS_SCAN_INTERVAL = intPreferencesKey("gnss_scan_interval_seconds")
        val SATELLITE_SCAN_INTERVAL = intPreferencesKey("satellite_scan_interval_seconds")
        // Cellular detection timing
        val CELLULAR_SCAN_INTERVAL = intPreferencesKey("cellular_scan_interval_seconds")
    }
    
    val settings: Flow<ScanSettings> = context.dataStore.data.map { preferences ->
        ScanSettings(
            wifiScanIntervalSeconds = preferences[PreferencesKeys.WIFI_SCAN_INTERVAL] ?: 35,
            bleScanDurationSeconds = preferences[PreferencesKeys.BLE_SCAN_DURATION] ?: 10,
            inactiveTimeoutSeconds = preferences[PreferencesKeys.INACTIVE_TIMEOUT] ?: 60,
            seenDeviceTimeoutMinutes = preferences[PreferencesKeys.SEEN_DEVICE_TIMEOUT] ?: 5,
            enableBleScanning = preferences[PreferencesKeys.ENABLE_BLE] ?: true,
            enableWifiScanning = preferences[PreferencesKeys.ENABLE_WIFI] ?: true,
            trackSeenDevices = preferences[PreferencesKeys.TRACK_SEEN_DEVICES] ?: true,
            rfScanIntervalSeconds = preferences[PreferencesKeys.RF_SCAN_INTERVAL] ?: 30,
            ultrasonicScanIntervalSeconds = preferences[PreferencesKeys.ULTRASONIC_SCAN_INTERVAL] ?: 30,
            ultrasonicScanDurationSeconds = preferences[PreferencesKeys.ULTRASONIC_SCAN_DURATION] ?: 5,
            gnssScanIntervalSeconds = preferences[PreferencesKeys.GNSS_SCAN_INTERVAL] ?: 5,
            satelliteScanIntervalSeconds = preferences[PreferencesKeys.SATELLITE_SCAN_INTERVAL] ?: 10,
            cellularScanIntervalSeconds = preferences[PreferencesKeys.CELLULAR_SCAN_INTERVAL] ?: 5
        )
    }
    
    suspend fun updateWifiScanInterval(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WIFI_SCAN_INTERVAL] = seconds.coerceIn(30, 120)
        }
    }
    
    suspend fun updateBleScanDuration(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BLE_SCAN_DURATION] = seconds.coerceIn(5, 30)
        }
    }
    
    suspend fun updateInactiveTimeout(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INACTIVE_TIMEOUT] = seconds.coerceIn(30, 300)
        }
    }
    
    suspend fun updateSeenDeviceTimeout(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SEEN_DEVICE_TIMEOUT] = minutes.coerceIn(1, 30)
        }
    }
    
    suspend fun setEnableBle(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ENABLE_BLE] = enabled
        }
    }
    
    suspend fun setEnableWifi(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ENABLE_WIFI] = enabled
        }
    }
    
    suspend fun setTrackSeenDevices(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRACK_SEEN_DEVICES] = enabled
        }
    }

    suspend fun updateRfScanInterval(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RF_SCAN_INTERVAL] = seconds.coerceIn(10, 120)
        }
    }

    suspend fun updateUltrasonicScanInterval(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ULTRASONIC_SCAN_INTERVAL] = seconds.coerceIn(15, 120)
        }
    }

    suspend fun updateUltrasonicScanDuration(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ULTRASONIC_SCAN_DURATION] = seconds.coerceIn(3, 15)
        }
    }

    suspend fun updateGnssScanInterval(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GNSS_SCAN_INTERVAL] = seconds.coerceIn(1, 30)
        }
    }

    suspend fun updateSatelliteScanInterval(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SATELLITE_SCAN_INTERVAL] = seconds.coerceIn(5, 60)
        }
    }

    suspend fun updateCellularScanInterval(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CELLULAR_SCAN_INTERVAL] = seconds.coerceIn(1, 30)
        }
    }
}

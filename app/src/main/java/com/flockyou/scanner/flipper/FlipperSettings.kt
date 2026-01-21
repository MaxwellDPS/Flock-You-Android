package com.flockyou.scanner.flipper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.flipperDataStore: DataStore<Preferences> by preferencesDataStore(name = "flipper_settings")

/**
 * Settings for Flipper Zero integration.
 */
data class FlipperSettings(
    // Connection settings
    val flipperEnabled: Boolean = false,
    val autoConnectUsb: Boolean = true,
    val autoConnectBluetooth: Boolean = false,
    val savedBluetoothAddress: String? = null,
    val preferredConnection: FlipperConnectionPreference = FlipperConnectionPreference.USB_PREFERRED,

    // Scan settings
    val enableWifiScanning: Boolean = true,
    val enableSubGhzScanning: Boolean = true,
    val enableBleScanning: Boolean = true,
    val enableIrScanning: Boolean = false,
    val enableNfcScanning: Boolean = false,

    // Sub-GHz frequency range
    val subGhzFrequencyStart: Long = 300_000_000L,
    val subGhzFrequencyEnd: Long = 928_000_000L,

    // WIPS (Wireless Intrusion Prevention System) settings
    val wipsEnabled: Boolean = true,
    val wipsEvilTwinDetection: Boolean = true,
    val wipsDeauthDetection: Boolean = true,
    val wipsKarmaDetection: Boolean = true,
    val wipsRogueApDetection: Boolean = true,

    // Timing settings
    val wifiScanIntervalSeconds: Int = 30,
    val subGhzScanIntervalSeconds: Int = 15,
    val bleScanIntervalSeconds: Int = 20,
    val heartbeatIntervalSeconds: Int = 5,

    // Threat assessment thresholds
    val strongSignalThresholdDbm: Int = -50,
    val trackerFrequencyToleranceHz: Long = 1_000_000L
)

enum class FlipperConnectionPreference {
    USB_PREFERRED,
    BLUETOOTH_PREFERRED,
    USB_ONLY,
    BLUETOOTH_ONLY
}

@Singleton
class FlipperSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        // Connection
        val FLIPPER_ENABLED = booleanPreferencesKey("flipper_enabled")
        val AUTO_CONNECT_USB = booleanPreferencesKey("auto_connect_usb")
        val AUTO_CONNECT_BLUETOOTH = booleanPreferencesKey("auto_connect_bluetooth")
        val SAVED_BLUETOOTH_ADDRESS = stringPreferencesKey("saved_bluetooth_address")
        val PREFERRED_CONNECTION = stringPreferencesKey("preferred_connection")

        // Scan toggles
        val ENABLE_WIFI_SCANNING = booleanPreferencesKey("flipper_enable_wifi")
        val ENABLE_SUBGHZ_SCANNING = booleanPreferencesKey("flipper_enable_subghz")
        val ENABLE_BLE_SCANNING = booleanPreferencesKey("flipper_enable_ble")
        val ENABLE_IR_SCANNING = booleanPreferencesKey("flipper_enable_ir")
        val ENABLE_NFC_SCANNING = booleanPreferencesKey("flipper_enable_nfc")

        // Sub-GHz frequency range
        val SUBGHZ_FREQ_START = longPreferencesKey("subghz_freq_start")
        val SUBGHZ_FREQ_END = longPreferencesKey("subghz_freq_end")

        // WIPS settings
        val WIPS_ENABLED = booleanPreferencesKey("wips_enabled")
        val WIPS_EVIL_TWIN = booleanPreferencesKey("wips_evil_twin")
        val WIPS_DEAUTH = booleanPreferencesKey("wips_deauth")
        val WIPS_KARMA = booleanPreferencesKey("wips_karma")
        val WIPS_ROGUE_AP = booleanPreferencesKey("wips_rogue_ap")

        // Timing
        val WIFI_SCAN_INTERVAL = intPreferencesKey("flipper_wifi_interval")
        val SUBGHZ_SCAN_INTERVAL = intPreferencesKey("flipper_subghz_interval")
        val BLE_SCAN_INTERVAL = intPreferencesKey("flipper_ble_interval")
        val HEARTBEAT_INTERVAL = intPreferencesKey("flipper_heartbeat_interval")

        // Thresholds
        val STRONG_SIGNAL_THRESHOLD = intPreferencesKey("strong_signal_threshold")
        val TRACKER_FREQ_TOLERANCE = longPreferencesKey("tracker_freq_tolerance")
    }

    val settings: Flow<FlipperSettings> = context.flipperDataStore.data.map { preferences ->
        FlipperSettings(
            flipperEnabled = preferences[PreferencesKeys.FLIPPER_ENABLED] ?: false,
            autoConnectUsb = preferences[PreferencesKeys.AUTO_CONNECT_USB] ?: true,
            autoConnectBluetooth = preferences[PreferencesKeys.AUTO_CONNECT_BLUETOOTH] ?: false,
            savedBluetoothAddress = preferences[PreferencesKeys.SAVED_BLUETOOTH_ADDRESS],
            preferredConnection = preferences[PreferencesKeys.PREFERRED_CONNECTION]?.let {
                try { FlipperConnectionPreference.valueOf(it) } catch (_: Exception) { null }
            } ?: FlipperConnectionPreference.USB_PREFERRED,

            enableWifiScanning = preferences[PreferencesKeys.ENABLE_WIFI_SCANNING] ?: true,
            enableSubGhzScanning = preferences[PreferencesKeys.ENABLE_SUBGHZ_SCANNING] ?: true,
            enableBleScanning = preferences[PreferencesKeys.ENABLE_BLE_SCANNING] ?: true,
            enableIrScanning = preferences[PreferencesKeys.ENABLE_IR_SCANNING] ?: false,
            enableNfcScanning = preferences[PreferencesKeys.ENABLE_NFC_SCANNING] ?: false,

            subGhzFrequencyStart = preferences[PreferencesKeys.SUBGHZ_FREQ_START] ?: 300_000_000L,
            subGhzFrequencyEnd = preferences[PreferencesKeys.SUBGHZ_FREQ_END] ?: 928_000_000L,

            wipsEnabled = preferences[PreferencesKeys.WIPS_ENABLED] ?: true,
            wipsEvilTwinDetection = preferences[PreferencesKeys.WIPS_EVIL_TWIN] ?: true,
            wipsDeauthDetection = preferences[PreferencesKeys.WIPS_DEAUTH] ?: true,
            wipsKarmaDetection = preferences[PreferencesKeys.WIPS_KARMA] ?: true,
            wipsRogueApDetection = preferences[PreferencesKeys.WIPS_ROGUE_AP] ?: true,

            wifiScanIntervalSeconds = preferences[PreferencesKeys.WIFI_SCAN_INTERVAL] ?: 20,
            subGhzScanIntervalSeconds = preferences[PreferencesKeys.SUBGHZ_SCAN_INTERVAL] ?: 10,
            bleScanIntervalSeconds = preferences[PreferencesKeys.BLE_SCAN_INTERVAL] ?: 15,
            heartbeatIntervalSeconds = preferences[PreferencesKeys.HEARTBEAT_INTERVAL] ?: 5,

            strongSignalThresholdDbm = preferences[PreferencesKeys.STRONG_SIGNAL_THRESHOLD] ?: -50,
            trackerFrequencyToleranceHz = preferences[PreferencesKeys.TRACKER_FREQ_TOLERANCE] ?: 1_000_000L
        )
    }

    // Connection settings
    suspend fun setFlipperEnabled(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.FLIPPER_ENABLED] = enabled }
    }

    suspend fun setAutoConnectUsb(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.AUTO_CONNECT_USB] = enabled }
    }

    suspend fun setAutoConnectBluetooth(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.AUTO_CONNECT_BLUETOOTH] = enabled }
    }

    suspend fun setSavedBluetoothAddress(address: String?) {
        context.flipperDataStore.edit {
            if (address != null) {
                it[PreferencesKeys.SAVED_BLUETOOTH_ADDRESS] = address
            } else {
                it.remove(PreferencesKeys.SAVED_BLUETOOTH_ADDRESS)
            }
        }
    }

    suspend fun setPreferredConnection(preference: FlipperConnectionPreference) {
        context.flipperDataStore.edit { it[PreferencesKeys.PREFERRED_CONNECTION] = preference.name }
    }

    // Scan toggles
    suspend fun setEnableWifiScanning(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.ENABLE_WIFI_SCANNING] = enabled }
    }

    suspend fun setEnableSubGhzScanning(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.ENABLE_SUBGHZ_SCANNING] = enabled }
    }

    suspend fun setEnableBleScanning(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.ENABLE_BLE_SCANNING] = enabled }
    }

    suspend fun setEnableIrScanning(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.ENABLE_IR_SCANNING] = enabled }
    }

    suspend fun setEnableNfcScanning(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.ENABLE_NFC_SCANNING] = enabled }
    }

    // Sub-GHz frequency range
    suspend fun setSubGhzFrequencyRange(startHz: Long, endHz: Long) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.SUBGHZ_FREQ_START] = startHz.coerceIn(300_000_000L, 928_000_000L)
            it[PreferencesKeys.SUBGHZ_FREQ_END] = endHz.coerceIn(300_000_000L, 928_000_000L)
        }
    }

    // WIPS settings
    suspend fun setWipsEnabled(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.WIPS_ENABLED] = enabled }
    }

    suspend fun setWipsEvilTwinDetection(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.WIPS_EVIL_TWIN] = enabled }
    }

    suspend fun setWipsDeauthDetection(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.WIPS_DEAUTH] = enabled }
    }

    suspend fun setWipsKarmaDetection(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.WIPS_KARMA] = enabled }
    }

    suspend fun setWipsRogueApDetection(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.WIPS_ROGUE_AP] = enabled }
    }

    // Timing settings
    suspend fun setWifiScanInterval(seconds: Int) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.WIFI_SCAN_INTERVAL] = seconds.coerceIn(10, 120)
        }
    }

    suspend fun setSubGhzScanInterval(seconds: Int) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.SUBGHZ_SCAN_INTERVAL] = seconds.coerceIn(5, 60)
        }
    }

    suspend fun setBleScanInterval(seconds: Int) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.BLE_SCAN_INTERVAL] = seconds.coerceIn(10, 60)
        }
    }

    suspend fun setHeartbeatInterval(seconds: Int) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.HEARTBEAT_INTERVAL] = seconds.coerceIn(1, 30)
        }
    }

    // Threshold settings
    suspend fun setStrongSignalThreshold(dbm: Int) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.STRONG_SIGNAL_THRESHOLD] = dbm.coerceIn(-100, -20)
        }
    }

    suspend fun setTrackerFrequencyTolerance(hz: Long) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.TRACKER_FREQ_TOLERANCE] = hz.coerceIn(100_000L, 10_000_000L)
        }
    }
}

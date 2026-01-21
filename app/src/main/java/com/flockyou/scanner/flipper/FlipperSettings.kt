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
 * Haptic feedback pattern for Flipper detections.
 * Different patterns for different severity levels.
 */
enum class FlipperHapticPattern(val displayName: String, val pattern: LongArray) {
    /** Single short buzz for low-severity detections */
    LOW_SINGLE_BUZZ("Single Buzz", longArrayOf(0, 100)),
    /** Double buzz for medium-severity detections */
    MEDIUM_DOUBLE_BUZZ("Double Buzz", longArrayOf(0, 100, 100, 100)),
    /** Triple buzz for high-severity detections */
    HIGH_TRIPLE_BUZZ("Triple Buzz", longArrayOf(0, 150, 100, 150, 100, 150)),
    /** Long vibration pattern for critical detections */
    CRITICAL_LONG("Long Pattern", longArrayOf(0, 300, 100, 300, 100, 500)),
    /** SOS pattern for emergency alerts */
    EMERGENCY_SOS("SOS Pattern", longArrayOf(0, 100, 100, 100, 100, 100, 300, 100, 300, 100, 300, 100, 100, 100, 100, 100, 100))
}

/**
 * Alert sound type for Flipper detections.
 */
enum class FlipperAlertSound(val displayName: String) {
    /** Use system default notification sound */
    SYSTEM_DEFAULT("System Default"),
    /** Use system alarm sound for urgent alerts */
    SYSTEM_ALARM("System Alarm"),
    /** Use system notification sound */
    SYSTEM_NOTIFICATION("System Notification"),
    /** Silent - no sound */
    SILENT("Silent")
}

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
    val trackerFrequencyToleranceHz: Long = 1_000_000L,

    // ==================== Alert & Notification Settings ====================

    // Haptic feedback settings
    val hapticFeedbackEnabled: Boolean = true,
    val hapticForLowSeverity: Boolean = false,
    val hapticForMediumSeverity: Boolean = true,
    val hapticForHighSeverity: Boolean = true,
    val hapticForCriticalSeverity: Boolean = true,

    // Sound settings
    val alertSoundsEnabled: Boolean = true,
    val soundForLowSeverity: FlipperAlertSound = FlipperAlertSound.SILENT,
    val soundForMediumSeverity: FlipperAlertSound = FlipperAlertSound.SYSTEM_NOTIFICATION,
    val soundForHighSeverity: FlipperAlertSound = FlipperAlertSound.SYSTEM_DEFAULT,
    val soundForCriticalSeverity: FlipperAlertSound = FlipperAlertSound.SYSTEM_ALARM,
    val respectSilentMode: Boolean = true,

    // Notification settings
    val notificationsEnabled: Boolean = true,
    val showQuickActions: Boolean = true,
    val groupNotifications: Boolean = true
)

enum class FlipperConnectionPreference {
    USB_PREFERRED,
    BLUETOOTH_PREFERRED,
    USB_ONLY,
    BLUETOOTH_ONLY
}

/**
 * Represents a recently connected Flipper device for connection history.
 */
data class RecentFlipperDevice(
    val address: String,
    val name: String,
    val lastConnectedTimestamp: Long,
    val connectionType: String // "BLUETOOTH" or "USB"
)

/**
 * Auto-reconnect state information.
 */
data class AutoReconnectState(
    val isReconnecting: Boolean = false,
    val attemptNumber: Int = 0,
    val maxAttempts: Int = 5,
    val lastAttemptTimestamp: Long = 0,
    val nextAttemptDelayMs: Long = 0
)

@Singleton
class FlipperSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_RECENT_DEVICES = 5
    }

    private object PreferencesKeys {
        // Connection
        val FLIPPER_ENABLED = booleanPreferencesKey("flipper_enabled")
        val AUTO_CONNECT_USB = booleanPreferencesKey("auto_connect_usb")
        val AUTO_CONNECT_BLUETOOTH = booleanPreferencesKey("auto_connect_bluetooth")
        val SAVED_BLUETOOTH_ADDRESS = stringPreferencesKey("saved_bluetooth_address")
        val PREFERRED_CONNECTION = stringPreferencesKey("preferred_connection")

        // Connection history - stored as JSON-like string set
        val RECENT_DEVICES = stringSetPreferencesKey("recent_flipper_devices")

        // Auto-reconnect settings
        val AUTO_RECONNECT_ENABLED = booleanPreferencesKey("auto_reconnect_enabled")
        val AUTO_RECONNECT_MAX_ATTEMPTS = intPreferencesKey("auto_reconnect_max_attempts")

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

        // Alert & Notification settings
        val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("flipper_haptic_enabled")
        val HAPTIC_FOR_LOW = booleanPreferencesKey("flipper_haptic_low")
        val HAPTIC_FOR_MEDIUM = booleanPreferencesKey("flipper_haptic_medium")
        val HAPTIC_FOR_HIGH = booleanPreferencesKey("flipper_haptic_high")
        val HAPTIC_FOR_CRITICAL = booleanPreferencesKey("flipper_haptic_critical")

        val ALERT_SOUNDS_ENABLED = booleanPreferencesKey("flipper_sounds_enabled")
        val SOUND_FOR_LOW = stringPreferencesKey("flipper_sound_low")
        val SOUND_FOR_MEDIUM = stringPreferencesKey("flipper_sound_medium")
        val SOUND_FOR_HIGH = stringPreferencesKey("flipper_sound_high")
        val SOUND_FOR_CRITICAL = stringPreferencesKey("flipper_sound_critical")
        val RESPECT_SILENT_MODE = booleanPreferencesKey("flipper_respect_silent_mode")

        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("flipper_notifications_enabled")
        val SHOW_QUICK_ACTIONS = booleanPreferencesKey("flipper_show_quick_actions")
        val GROUP_NOTIFICATIONS = booleanPreferencesKey("flipper_group_notifications")
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
            trackerFrequencyToleranceHz = preferences[PreferencesKeys.TRACKER_FREQ_TOLERANCE] ?: 1_000_000L,

            // Alert & notification settings
            hapticFeedbackEnabled = preferences[PreferencesKeys.HAPTIC_FEEDBACK_ENABLED] ?: true,
            hapticForLowSeverity = preferences[PreferencesKeys.HAPTIC_FOR_LOW] ?: false,
            hapticForMediumSeverity = preferences[PreferencesKeys.HAPTIC_FOR_MEDIUM] ?: true,
            hapticForHighSeverity = preferences[PreferencesKeys.HAPTIC_FOR_HIGH] ?: true,
            hapticForCriticalSeverity = preferences[PreferencesKeys.HAPTIC_FOR_CRITICAL] ?: true,

            alertSoundsEnabled = preferences[PreferencesKeys.ALERT_SOUNDS_ENABLED] ?: true,
            soundForLowSeverity = preferences[PreferencesKeys.SOUND_FOR_LOW]?.let {
                try { FlipperAlertSound.valueOf(it) } catch (_: Exception) { null }
            } ?: FlipperAlertSound.SILENT,
            soundForMediumSeverity = preferences[PreferencesKeys.SOUND_FOR_MEDIUM]?.let {
                try { FlipperAlertSound.valueOf(it) } catch (_: Exception) { null }
            } ?: FlipperAlertSound.SYSTEM_NOTIFICATION,
            soundForHighSeverity = preferences[PreferencesKeys.SOUND_FOR_HIGH]?.let {
                try { FlipperAlertSound.valueOf(it) } catch (_: Exception) { null }
            } ?: FlipperAlertSound.SYSTEM_DEFAULT,
            soundForCriticalSeverity = preferences[PreferencesKeys.SOUND_FOR_CRITICAL]?.let {
                try { FlipperAlertSound.valueOf(it) } catch (_: Exception) { null }
            } ?: FlipperAlertSound.SYSTEM_ALARM,
            respectSilentMode = preferences[PreferencesKeys.RESPECT_SILENT_MODE] ?: true,

            notificationsEnabled = preferences[PreferencesKeys.NOTIFICATIONS_ENABLED] ?: true,
            showQuickActions = preferences[PreferencesKeys.SHOW_QUICK_ACTIONS] ?: true,
            groupNotifications = preferences[PreferencesKeys.GROUP_NOTIFICATIONS] ?: true
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

    // ========== Connection History ==========

    /**
     * Get list of recently connected Flipper devices.
     */
    val recentDevices: Flow<List<RecentFlipperDevice>> = context.flipperDataStore.data.map { preferences ->
        val deviceStrings = preferences[PreferencesKeys.RECENT_DEVICES] ?: emptySet()
        deviceStrings.mapNotNull { parseRecentDevice(it) }
            .sortedByDescending { it.lastConnectedTimestamp }
            .take(MAX_RECENT_DEVICES)
    }

    /**
     * Add or update a device in connection history.
     */
    suspend fun addRecentDevice(address: String, name: String, connectionType: String) {
        context.flipperDataStore.edit { preferences ->
            val existing = preferences[PreferencesKeys.RECENT_DEVICES]?.toMutableSet() ?: mutableSetOf()

            // Remove existing entry for this address (if any)
            existing.removeAll { it.startsWith("$address|") }

            // Add new entry
            val entry = "$address|$name|${System.currentTimeMillis()}|$connectionType"
            existing.add(entry)

            // Keep only the most recent MAX_RECENT_DEVICES
            val sorted = existing.mapNotNull { parseRecentDevice(it) }
                .sortedByDescending { it.lastConnectedTimestamp }
                .take(MAX_RECENT_DEVICES)
                .map { "${it.address}|${it.name}|${it.lastConnectedTimestamp}|${it.connectionType}" }
                .toSet()

            preferences[PreferencesKeys.RECENT_DEVICES] = sorted
        }
    }

    /**
     * Remove a device from connection history.
     */
    suspend fun removeRecentDevice(address: String) {
        context.flipperDataStore.edit { preferences ->
            val existing = preferences[PreferencesKeys.RECENT_DEVICES]?.toMutableSet() ?: return@edit
            existing.removeAll { it.startsWith("$address|") }
            preferences[PreferencesKeys.RECENT_DEVICES] = existing
        }
    }

    /**
     * Clear all connection history.
     */
    suspend fun clearRecentDevices() {
        context.flipperDataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.RECENT_DEVICES)
        }
    }

    private fun parseRecentDevice(entry: String): RecentFlipperDevice? {
        val parts = entry.split("|")
        if (parts.size < 4) return null
        return try {
            RecentFlipperDevice(
                address = parts[0],
                name = parts[1],
                lastConnectedTimestamp = parts[2].toLong(),
                connectionType = parts[3]
            )
        } catch (e: Exception) {
            null
        }
    }

    // ========== Auto-Reconnect Settings ==========

    /**
     * Check if auto-reconnect is enabled.
     */
    val autoReconnectEnabled: Flow<Boolean> = context.flipperDataStore.data.map { preferences ->
        preferences[PreferencesKeys.AUTO_RECONNECT_ENABLED] ?: true
    }

    /**
     * Get max auto-reconnect attempts.
     */
    val autoReconnectMaxAttempts: Flow<Int> = context.flipperDataStore.data.map { preferences ->
        preferences[PreferencesKeys.AUTO_RECONNECT_MAX_ATTEMPTS] ?: 5
    }

    /**
     * Set auto-reconnect enabled state.
     */
    suspend fun setAutoReconnectEnabled(enabled: Boolean) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.AUTO_RECONNECT_ENABLED] = enabled
        }
    }

    /**
     * Set max auto-reconnect attempts.
     */
    suspend fun setAutoReconnectMaxAttempts(maxAttempts: Int) {
        context.flipperDataStore.edit {
            it[PreferencesKeys.AUTO_RECONNECT_MAX_ATTEMPTS] = maxAttempts.coerceIn(1, 10)
        }
    }

    // ========== Alert & Notification Settings ==========

    /**
     * Enable/disable haptic feedback for Flipper detections.
     */
    suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.HAPTIC_FEEDBACK_ENABLED] = enabled }
    }

    /**
     * Enable/disable haptic feedback for specific severity levels.
     */
    suspend fun setHapticForLowSeverity(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.HAPTIC_FOR_LOW] = enabled }
    }

    suspend fun setHapticForMediumSeverity(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.HAPTIC_FOR_MEDIUM] = enabled }
    }

    suspend fun setHapticForHighSeverity(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.HAPTIC_FOR_HIGH] = enabled }
    }

    suspend fun setHapticForCriticalSeverity(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.HAPTIC_FOR_CRITICAL] = enabled }
    }

    /**
     * Enable/disable alert sounds for Flipper detections.
     */
    suspend fun setAlertSoundsEnabled(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.ALERT_SOUNDS_ENABLED] = enabled }
    }

    /**
     * Set alert sound type for specific severity levels.
     */
    suspend fun setSoundForLowSeverity(sound: FlipperAlertSound) {
        context.flipperDataStore.edit { it[PreferencesKeys.SOUND_FOR_LOW] = sound.name }
    }

    suspend fun setSoundForMediumSeverity(sound: FlipperAlertSound) {
        context.flipperDataStore.edit { it[PreferencesKeys.SOUND_FOR_MEDIUM] = sound.name }
    }

    suspend fun setSoundForHighSeverity(sound: FlipperAlertSound) {
        context.flipperDataStore.edit { it[PreferencesKeys.SOUND_FOR_HIGH] = sound.name }
    }

    suspend fun setSoundForCriticalSeverity(sound: FlipperAlertSound) {
        context.flipperDataStore.edit { it[PreferencesKeys.SOUND_FOR_CRITICAL] = sound.name }
    }

    /**
     * Set whether to respect system silent mode.
     */
    suspend fun setRespectSilentMode(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.RESPECT_SILENT_MODE] = enabled }
    }

    /**
     * Enable/disable notifications for Flipper detections.
     */
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.NOTIFICATIONS_ENABLED] = enabled }
    }

    /**
     * Enable/disable quick action buttons in notifications.
     */
    suspend fun setShowQuickActions(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.SHOW_QUICK_ACTIONS] = enabled }
    }

    /**
     * Enable/disable notification grouping.
     */
    suspend fun setGroupNotifications(enabled: Boolean) {
        context.flipperDataStore.edit { it[PreferencesKeys.GROUP_NOTIFICATIONS] = enabled }
    }
}

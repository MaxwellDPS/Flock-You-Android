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

private val Context.detectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "detection_settings")

// ==================== Detection Pattern Settings ====================

/**
 * Cellular detection pattern identifiers
 */
enum class CellularPattern(val displayName: String, val description: String, val defaultEnabled: Boolean = true) {
    ENCRYPTION_DOWNGRADE("Encryption Downgrade", "Detects forced downgrade from 4G/5G to 2G", true),
    SUSPICIOUS_NETWORK_ID("Suspicious Network ID", "Detects test/invalid MCC/MNC codes", true),
    RAPID_CELL_SWITCHING("Rapid Cell Switching", "Detects abnormal cell tower handoff rates", true),
    SIGNAL_SPIKE("Signal Spike", "Detects sudden signal strength changes", true),
    LAC_TAC_ANOMALY("LAC/TAC Anomaly", "Detects location area changes without cell change", true),
    UNKNOWN_CELL_TOWER("Unknown Cell Tower", "Alerts on connection to untrusted cell towers", true),
    CELL_ID_CHANGE("Cell ID Change", "Logs all cell tower changes", false),
    ROAMING_ANOMALY("Roaming Anomaly", "Detects unexpected roaming state changes", true)
}

/**
 * Satellite detection pattern identifiers
 */
enum class SatellitePattern(val displayName: String, val description: String, val defaultEnabled: Boolean = true) {
    UNEXPECTED_SATELLITE("Unexpected Satellite", "Satellite connection when terrestrial available", true),
    FORCED_HANDOFF("Forced Satellite Handoff", "Rapid or suspicious handoff to satellite", true),
    SUSPICIOUS_NTN_PARAMS("Suspicious NTN Parameters", "Unusual NTN config suggesting spoofing", true),
    UNKNOWN_SATELLITE_NETWORK("Unknown Satellite Network", "Unrecognized satellite network name", true),
    SATELLITE_IN_COVERAGE("Satellite in Coverage Area", "Satellite used despite good terrestrial coverage", true),
    RAPID_SATELLITE_SWITCHING("Rapid Satellite Switching", "Abnormal satellite handoff patterns", true),
    NTN_BAND_MISMATCH("NTN Band Mismatch", "Claimed satellite but wrong frequency band", true),
    TIMING_ANOMALY("Timing Advance Anomaly", "NTN timing doesn't match claimed orbit", true),
    DOWNGRADE_TO_SATELLITE("Downgrade to Satellite", "Forced from better tech to satellite", true)
}

/**
 * BLE detection pattern identifiers
 */
enum class BlePattern(val displayName: String, val description: String, val defaultEnabled: Boolean = true) {
    FLOCK_SAFETY_ALPR("Flock Safety ALPR", "Flock Safety license plate reader cameras", true),
    FLOCK_RAVEN("Flock Raven Audio", "Flock Raven gunshot/audio sensors", true),
    SHOTSPOTTER("ShotSpotter", "ShotSpotter acoustic detection devices", true),
    AXON_DEVICES("Axon/Taser Devices", "Axon body cameras and Taser devices", true),
    MOTOROLA_POLICE("Motorola Police Radios", "Motorola law enforcement equipment", true),
    HARRIS_STINGRAY("Harris StingRay", "Harris Corporation cell site simulators", true),
    L3HARRIS("L3Harris Equipment", "L3Harris surveillance equipment", true),
    CELLEBRITE("Cellebrite Devices", "Cellebrite mobile forensics equipment", true),
    GRAYKEY("GrayKey Devices", "GrayKey iPhone unlocking devices", true),
    GENERIC_SURVEILLANCE("Generic Surveillance", "Other surveillance device patterns", true)
}

/**
 * WiFi detection pattern identifiers
 */
enum class WifiPattern(val displayName: String, val description: String, val defaultEnabled: Boolean = true) {
    POLICE_HOTSPOT("Police Mobile Hotspot", "Law enforcement mobile hotspot SSIDs", true),
    SURVEILLANCE_VAN("Surveillance Van", "Known surveillance vehicle WiFi patterns", true),
    STINGRAY_WIFI("StingRay WiFi", "Cell site simulator WiFi signatures", true),
    BODY_CAM_WIFI("Body Camera WiFi", "Body-worn camera WiFi hotspots", true),
    DRONE_WIFI("Drone WiFi", "Police/surveillance drone WiFi patterns", true),
    GENERIC_SUSPECT("Generic Suspicious", "Other suspicious WiFi patterns", false)
}

/**
 * Threshold settings for cellular detection
 */
data class CellularThresholds(
    val signalSpikeThreshold: Int = 25,           // dBm change to trigger spike alert
    val rapidSwitchCountStationary: Int = 3,      // Max switches/min while stationary
    val rapidSwitchCountMoving: Int = 8,          // Max switches/min while moving
    val trustedCellThreshold: Int = 5,            // Times seen before cell is trusted
    val minAnomalyIntervalMs: Long = 60000L,      // Min time between same anomaly
    val movementSpeedThreshold: Float = 0.0005f   // Movement detection threshold
)

/**
 * Threshold settings for satellite detection
 */
data class SatelliteThresholds(
    val unexpectedSatelliteThresholdMs: Long = 5000L,  // Time window for unexpected satellite
    val rapidHandoffThresholdMs: Long = 2000L,          // Time for rapid handoff detection
    val minSignalForTerrestrial: Int = -100,            // Min signal (dBm) for good terrestrial
    val rapidSwitchingWindowMs: Long = 60000L,          // Window for rapid switching detection
    val rapidSwitchingCount: Int = 3                    // Count for rapid switching in window
)

/**
 * Threshold settings for BLE detection
 */
data class BleThresholds(
    val minRssiForAlert: Int = -80,              // Min RSSI to trigger alert
    val proximityAlertRssi: Int = -50,           // RSSI threshold for proximity warning
    val trackingDurationMs: Long = 300000L,      // Time before tracking alert (5 min)
    val minSeenCountForTracking: Int = 3         // Min sightings for tracking alert
)

/**
 * Threshold settings for WiFi detection
 */
data class WifiThresholds(
    val minSignalForAlert: Int = -70,            // Min signal level for alert
    val strongSignalThreshold: Int = -50,        // Signal level for strong signal alert
    val trackingDurationMs: Long = 300000L,      // Time before tracking alert
    val minSeenCountForTracking: Int = 3,        // Min sightings for tracking alert
    val minTrackingDistanceMeters: Double = 1609.0  // Min distance traveled (1 mile) for tracking alert
)

/**
 * Complete detection settings
 */
data class DetectionSettings(
    // Cellular patterns
    val enabledCellularPatterns: Set<CellularPattern> = CellularPattern.values().filter { it.defaultEnabled }.toSet(),
    val cellularThresholds: CellularThresholds = CellularThresholds(),

    // Satellite patterns
    val enabledSatellitePatterns: Set<SatellitePattern> = SatellitePattern.values().filter { it.defaultEnabled }.toSet(),
    val satelliteThresholds: SatelliteThresholds = SatelliteThresholds(),

    // BLE patterns
    val enabledBlePatterns: Set<BlePattern> = BlePattern.values().filter { it.defaultEnabled }.toSet(),
    val bleThresholds: BleThresholds = BleThresholds(),

    // WiFi patterns
    val enabledWifiPatterns: Set<WifiPattern> = WifiPattern.values().filter { it.defaultEnabled }.toSet(),
    val wifiThresholds: WifiThresholds = WifiThresholds(),

    // Global settings
    val enableCellularDetection: Boolean = true,
    val enableSatelliteDetection: Boolean = true,
    val enableBleDetection: Boolean = true,
    val enableWifiDetection: Boolean = true,
    val enableHiddenNetworkRfAnomaly: Boolean = false,  // Disabled by default - high false positive rate

    // UI settings
    val advancedMode: Boolean = false,
    val showAdvancedSettings: Boolean = false,

    // Protection preset
    val currentPreset: ProtectionPreset = ProtectionPreset.BALANCED
) {
    /**
     * Returns a new DetectionSettings instance with the specified preset applied.
     * This updates all patterns and thresholds according to the preset configuration.
     */
    fun withPreset(preset: ProtectionPreset): DetectionSettings {
        return copy(
            enabledCellularPatterns = preset.getEnabledCellularPatterns(),
            enabledSatellitePatterns = preset.getEnabledSatellitePatterns(),
            enabledBlePatterns = preset.getEnabledBlePatterns(),
            enabledWifiPatterns = preset.getEnabledWifiPatterns(),
            cellularThresholds = preset.getCellularThresholds(),
            satelliteThresholds = preset.getSatelliteThresholds(),
            bleThresholds = preset.getBleThresholds(),
            wifiThresholds = preset.getWifiThresholds(),
            enableHiddenNetworkRfAnomaly = preset.getEnableHiddenNetworkRfAnomaly(),
            currentPreset = preset
        )
    }

    /**
     * Detects the current preset based on the settings configuration.
     * Returns CUSTOM if the settings don't match any predefined preset.
     */
    fun detectCurrentPreset(): ProtectionPreset {
        return ProtectionPreset.detectPreset(this)
    }

    /**
     * Returns true if the current settings match the stored preset.
     * If false, the preset should be updated to CUSTOM.
     */
    fun matchesCurrentPreset(): Boolean {
        return detectCurrentPreset() == currentPreset
    }
}

@Singleton
class DetectionSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        // Global toggles
        val ENABLE_CELLULAR = booleanPreferencesKey("detection_cellular_enabled")
        val ENABLE_SATELLITE = booleanPreferencesKey("detection_satellite_enabled")
        val ENABLE_BLE = booleanPreferencesKey("detection_ble_enabled")
        val ENABLE_WIFI = booleanPreferencesKey("detection_wifi_enabled")
        val ENABLE_HIDDEN_NETWORK_RF_ANOMALY = booleanPreferencesKey("detection_hidden_network_rf_anomaly_enabled")

        // UI settings
        val ADVANCED_MODE = booleanPreferencesKey("ui_advanced_mode")
        val SHOW_ADVANCED_SETTINGS = booleanPreferencesKey("ui_show_advanced_settings")

        // Protection preset
        val CURRENT_PRESET = stringPreferencesKey("current_protection_preset")
        
        // Pattern toggles (stored as comma-separated disabled patterns)
        val DISABLED_CELLULAR_PATTERNS = stringPreferencesKey("disabled_cellular_patterns")
        val DISABLED_SATELLITE_PATTERNS = stringPreferencesKey("disabled_satellite_patterns")
        val DISABLED_BLE_PATTERNS = stringPreferencesKey("disabled_ble_patterns")
        val DISABLED_WIFI_PATTERNS = stringPreferencesKey("disabled_wifi_patterns")
        
        // Cellular thresholds
        val CELL_SIGNAL_SPIKE_THRESHOLD = intPreferencesKey("cell_signal_spike_threshold")
        val CELL_RAPID_SWITCH_STATIONARY = intPreferencesKey("cell_rapid_switch_stationary")
        val CELL_RAPID_SWITCH_MOVING = intPreferencesKey("cell_rapid_switch_moving")
        val CELL_TRUSTED_THRESHOLD = intPreferencesKey("cell_trusted_threshold")
        val CELL_ANOMALY_INTERVAL = longPreferencesKey("cell_anomaly_interval")
        
        // Satellite thresholds
        val SAT_UNEXPECTED_THRESHOLD = longPreferencesKey("sat_unexpected_threshold")
        val SAT_RAPID_HANDOFF_THRESHOLD = longPreferencesKey("sat_rapid_handoff_threshold")
        val SAT_MIN_TERRESTRIAL_SIGNAL = intPreferencesKey("sat_min_terrestrial_signal")
        val SAT_RAPID_SWITCH_WINDOW = longPreferencesKey("sat_rapid_switch_window")
        val SAT_RAPID_SWITCH_COUNT = intPreferencesKey("sat_rapid_switch_count")
        
        // BLE thresholds
        val BLE_MIN_RSSI = intPreferencesKey("ble_min_rssi")
        val BLE_PROXIMITY_RSSI = intPreferencesKey("ble_proximity_rssi")
        val BLE_TRACKING_DURATION = longPreferencesKey("ble_tracking_duration")
        val BLE_TRACKING_COUNT = intPreferencesKey("ble_tracking_count")
        
        // WiFi thresholds
        val WIFI_MIN_SIGNAL = intPreferencesKey("wifi_min_signal")
        val WIFI_STRONG_SIGNAL = intPreferencesKey("wifi_strong_signal")
        val WIFI_TRACKING_DURATION = longPreferencesKey("wifi_tracking_duration")
        val WIFI_TRACKING_COUNT = intPreferencesKey("wifi_tracking_count")
        val WIFI_MIN_TRACKING_DISTANCE = doublePreferencesKey("wifi_min_tracking_distance_meters")
    }
    
    val settings: Flow<DetectionSettings> = context.detectionDataStore.data.map { prefs ->
        // Parse disabled patterns
        val disabledCellular = prefs[Keys.DISABLED_CELLULAR_PATTERNS]?.split(",")?.mapNotNull {
            try { CellularPattern.valueOf(it) } catch (e: Exception) { null }
        }?.toSet() ?: emptySet()

        val disabledSatellite = prefs[Keys.DISABLED_SATELLITE_PATTERNS]?.split(",")?.mapNotNull {
            try { SatellitePattern.valueOf(it) } catch (e: Exception) { null }
        }?.toSet() ?: emptySet()

        val disabledBle = prefs[Keys.DISABLED_BLE_PATTERNS]?.split(",")?.mapNotNull {
            try { BlePattern.valueOf(it) } catch (e: Exception) { null }
        }?.toSet() ?: emptySet()

        val disabledWifi = prefs[Keys.DISABLED_WIFI_PATTERNS]?.split(",")?.mapNotNull {
            try { WifiPattern.valueOf(it) } catch (e: Exception) { null }
        }?.toSet() ?: emptySet()

        // Parse current preset
        val currentPreset = prefs[Keys.CURRENT_PRESET]?.let {
            try { ProtectionPreset.valueOf(it) } catch (e: Exception) { null }
        } ?: ProtectionPreset.BALANCED

        DetectionSettings(
            enableCellularDetection = prefs[Keys.ENABLE_CELLULAR] ?: true,
            enableSatelliteDetection = prefs[Keys.ENABLE_SATELLITE] ?: true,
            enableBleDetection = prefs[Keys.ENABLE_BLE] ?: true,
            enableWifiDetection = prefs[Keys.ENABLE_WIFI] ?: true,
            enableHiddenNetworkRfAnomaly = prefs[Keys.ENABLE_HIDDEN_NETWORK_RF_ANOMALY] ?: false,
            advancedMode = prefs[Keys.ADVANCED_MODE] ?: false,
            showAdvancedSettings = prefs[Keys.SHOW_ADVANCED_SETTINGS] ?: false,

            enabledCellularPatterns = CellularPattern.values().filter { it !in disabledCellular }.toSet(),
            enabledSatellitePatterns = SatellitePattern.values().filter { it !in disabledSatellite }.toSet(),
            enabledBlePatterns = BlePattern.values().filter { it !in disabledBle }.toSet(),
            enabledWifiPatterns = WifiPattern.values().filter { it !in disabledWifi }.toSet(),

            cellularThresholds = CellularThresholds(
                signalSpikeThreshold = prefs[Keys.CELL_SIGNAL_SPIKE_THRESHOLD] ?: 25,
                rapidSwitchCountStationary = prefs[Keys.CELL_RAPID_SWITCH_STATIONARY] ?: 3,
                rapidSwitchCountMoving = prefs[Keys.CELL_RAPID_SWITCH_MOVING] ?: 8,
                trustedCellThreshold = prefs[Keys.CELL_TRUSTED_THRESHOLD] ?: 5,
                minAnomalyIntervalMs = prefs[Keys.CELL_ANOMALY_INTERVAL] ?: 60000L
            ),

            satelliteThresholds = SatelliteThresholds(
                unexpectedSatelliteThresholdMs = prefs[Keys.SAT_UNEXPECTED_THRESHOLD] ?: 5000L,
                rapidHandoffThresholdMs = prefs[Keys.SAT_RAPID_HANDOFF_THRESHOLD] ?: 2000L,
                minSignalForTerrestrial = prefs[Keys.SAT_MIN_TERRESTRIAL_SIGNAL] ?: -100,
                rapidSwitchingWindowMs = prefs[Keys.SAT_RAPID_SWITCH_WINDOW] ?: 60000L,
                rapidSwitchingCount = prefs[Keys.SAT_RAPID_SWITCH_COUNT] ?: 3
            ),

            bleThresholds = BleThresholds(
                minRssiForAlert = prefs[Keys.BLE_MIN_RSSI] ?: -80,
                proximityAlertRssi = prefs[Keys.BLE_PROXIMITY_RSSI] ?: -50,
                trackingDurationMs = prefs[Keys.BLE_TRACKING_DURATION] ?: 300000L,
                minSeenCountForTracking = prefs[Keys.BLE_TRACKING_COUNT] ?: 3
            ),

            wifiThresholds = WifiThresholds(
                minSignalForAlert = prefs[Keys.WIFI_MIN_SIGNAL] ?: -70,
                strongSignalThreshold = prefs[Keys.WIFI_STRONG_SIGNAL] ?: -50,
                trackingDurationMs = prefs[Keys.WIFI_TRACKING_DURATION] ?: 300000L,
                minSeenCountForTracking = prefs[Keys.WIFI_TRACKING_COUNT] ?: 3,
                minTrackingDistanceMeters = prefs[Keys.WIFI_MIN_TRACKING_DISTANCE] ?: 1609.0
            ),

            currentPreset = currentPreset
        )
    }
    
    // Toggle individual patterns
    suspend fun toggleCellularPattern(pattern: CellularPattern, enabled: Boolean) {
        context.detectionDataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_CELLULAR_PATTERNS]?.split(",")?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            if (enabled) {
                current.remove(pattern.name)
            } else {
                current.add(pattern.name)
            }
            prefs[Keys.DISABLED_CELLULAR_PATTERNS] = current.joinToString(",")
        }
    }
    
    suspend fun toggleSatellitePattern(pattern: SatellitePattern, enabled: Boolean) {
        context.detectionDataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_SATELLITE_PATTERNS]?.split(",")?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            if (enabled) {
                current.remove(pattern.name)
            } else {
                current.add(pattern.name)
            }
            prefs[Keys.DISABLED_SATELLITE_PATTERNS] = current.joinToString(",")
        }
    }
    
    suspend fun toggleBlePattern(pattern: BlePattern, enabled: Boolean) {
        context.detectionDataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_BLE_PATTERNS]?.split(",")?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            if (enabled) {
                current.remove(pattern.name)
            } else {
                current.add(pattern.name)
            }
            prefs[Keys.DISABLED_BLE_PATTERNS] = current.joinToString(",")
        }
    }
    
    suspend fun toggleWifiPattern(pattern: WifiPattern, enabled: Boolean) {
        context.detectionDataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_WIFI_PATTERNS]?.split(",")?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
            if (enabled) {
                current.remove(pattern.name)
            } else {
                current.add(pattern.name)
            }
            prefs[Keys.DISABLED_WIFI_PATTERNS] = current.joinToString(",")
        }
    }
    
    // Toggle global detection types
    suspend fun setGlobalDetectionEnabled(
        cellular: Boolean? = null,
        satellite: Boolean? = null,
        ble: Boolean? = null,
        wifi: Boolean? = null
    ) {
        context.detectionDataStore.edit { prefs ->
            cellular?.let { prefs[Keys.ENABLE_CELLULAR] = it }
            satellite?.let { prefs[Keys.ENABLE_SATELLITE] = it }
            ble?.let { prefs[Keys.ENABLE_BLE] = it }
            wifi?.let { prefs[Keys.ENABLE_WIFI] = it }
        }
    }

    // Toggle advanced mode
    suspend fun setAdvancedMode(enabled: Boolean) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.ADVANCED_MODE] = enabled
        }
    }

    // Toggle show advanced settings section
    suspend fun setShowAdvancedSettings(enabled: Boolean) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.SHOW_ADVANCED_SETTINGS] = enabled
        }
    }
    
    // Update cellular thresholds (all at once - use individual methods when possible)
    suspend fun updateCellularThresholds(thresholds: CellularThresholds) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.CELL_SIGNAL_SPIKE_THRESHOLD] = thresholds.signalSpikeThreshold
            prefs[Keys.CELL_RAPID_SWITCH_STATIONARY] = thresholds.rapidSwitchCountStationary
            prefs[Keys.CELL_RAPID_SWITCH_MOVING] = thresholds.rapidSwitchCountMoving
            prefs[Keys.CELL_TRUSTED_THRESHOLD] = thresholds.trustedCellThreshold
            prefs[Keys.CELL_ANOMALY_INTERVAL] = thresholds.minAnomalyIntervalMs
        }
    }

    // Individual cellular threshold updates (prevents race conditions)
    suspend fun updateCellularSignalSpikeThreshold(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.CELL_SIGNAL_SPIKE_THRESHOLD] = value
        }
    }

    suspend fun updateCellularRapidSwitchStationary(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.CELL_RAPID_SWITCH_STATIONARY] = value
        }
    }

    suspend fun updateCellularRapidSwitchMoving(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.CELL_RAPID_SWITCH_MOVING] = value
        }
    }

    suspend fun updateCellularTrustedThreshold(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.CELL_TRUSTED_THRESHOLD] = value
        }
    }

    suspend fun updateCellularAnomalyInterval(value: Long) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.CELL_ANOMALY_INTERVAL] = value
        }
    }

    // Update satellite thresholds (all at once - use individual methods when possible)
    suspend fun updateSatelliteThresholds(thresholds: SatelliteThresholds) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.SAT_UNEXPECTED_THRESHOLD] = thresholds.unexpectedSatelliteThresholdMs
            prefs[Keys.SAT_RAPID_HANDOFF_THRESHOLD] = thresholds.rapidHandoffThresholdMs
            prefs[Keys.SAT_MIN_TERRESTRIAL_SIGNAL] = thresholds.minSignalForTerrestrial
            prefs[Keys.SAT_RAPID_SWITCH_WINDOW] = thresholds.rapidSwitchingWindowMs
            prefs[Keys.SAT_RAPID_SWITCH_COUNT] = thresholds.rapidSwitchingCount
        }
    }

    // Individual satellite threshold updates (prevents race conditions)
    suspend fun updateSatelliteUnexpectedThreshold(value: Long) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.SAT_UNEXPECTED_THRESHOLD] = value
        }
    }

    suspend fun updateSatelliteRapidHandoffThreshold(value: Long) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.SAT_RAPID_HANDOFF_THRESHOLD] = value
        }
    }

    suspend fun updateSatelliteMinTerrestrialSignal(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.SAT_MIN_TERRESTRIAL_SIGNAL] = value
        }
    }

    suspend fun updateSatelliteRapidSwitchWindow(value: Long) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.SAT_RAPID_SWITCH_WINDOW] = value
        }
    }

    suspend fun updateSatelliteRapidSwitchCount(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.SAT_RAPID_SWITCH_COUNT] = value
        }
    }

    // Update BLE thresholds (all at once - use individual methods when possible)
    suspend fun updateBleThresholds(thresholds: BleThresholds) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.BLE_MIN_RSSI] = thresholds.minRssiForAlert
            prefs[Keys.BLE_PROXIMITY_RSSI] = thresholds.proximityAlertRssi
            prefs[Keys.BLE_TRACKING_DURATION] = thresholds.trackingDurationMs
            prefs[Keys.BLE_TRACKING_COUNT] = thresholds.minSeenCountForTracking
        }
    }

    // Individual BLE threshold updates (prevents race conditions)
    suspend fun updateBleMinRssi(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.BLE_MIN_RSSI] = value
        }
    }

    suspend fun updateBleProximityRssi(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.BLE_PROXIMITY_RSSI] = value
        }
    }

    suspend fun updateBleTrackingDuration(value: Long) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.BLE_TRACKING_DURATION] = value
        }
    }

    suspend fun updateBleTrackingCount(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.BLE_TRACKING_COUNT] = value
        }
    }

    // Update WiFi thresholds (all at once - use individual methods when possible)
    suspend fun updateWifiThresholds(thresholds: WifiThresholds) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.WIFI_MIN_SIGNAL] = thresholds.minSignalForAlert
            prefs[Keys.WIFI_STRONG_SIGNAL] = thresholds.strongSignalThreshold
            prefs[Keys.WIFI_TRACKING_DURATION] = thresholds.trackingDurationMs
            prefs[Keys.WIFI_TRACKING_COUNT] = thresholds.minSeenCountForTracking
            prefs[Keys.WIFI_MIN_TRACKING_DISTANCE] = thresholds.minTrackingDistanceMeters
        }
    }

    // Individual WiFi threshold updates (prevents race conditions)
    suspend fun updateWifiMinSignal(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.WIFI_MIN_SIGNAL] = value
        }
    }

    suspend fun updateWifiStrongSignal(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.WIFI_STRONG_SIGNAL] = value
        }
    }

    suspend fun updateWifiTrackingDuration(value: Long) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.WIFI_TRACKING_DURATION] = value
        }
    }

    suspend fun updateWifiTrackingCount(value: Int) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.WIFI_TRACKING_COUNT] = value
        }
    }

    suspend fun updateWifiMinTrackingDistance(value: Double) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.WIFI_MIN_TRACKING_DISTANCE] = value
        }
    }

    // Toggle hidden network RF anomaly detection
    suspend fun setHiddenNetworkRfAnomalyEnabled(enabled: Boolean) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.ENABLE_HIDDEN_NETWORK_RF_ANOMALY] = enabled
        }
    }
    
    // Reset all to defaults
    suspend fun resetToDefaults() {
        context.detectionDataStore.edit { prefs ->
            prefs.clear()
        }
    }

    /**
     * Apply a protection preset, updating all patterns and thresholds accordingly.
     * This is the primary method for switching between preset configurations.
     */
    suspend fun applyPreset(preset: ProtectionPreset) {
        context.detectionDataStore.edit { prefs ->
            // Store the current preset
            prefs[Keys.CURRENT_PRESET] = preset.name

            // Calculate which patterns should be disabled for this preset
            val enabledCellular = preset.getEnabledCellularPatterns()
            val disabledCellular = CellularPattern.values().filter { it !in enabledCellular }
            prefs[Keys.DISABLED_CELLULAR_PATTERNS] = disabledCellular.joinToString(",") { it.name }

            val enabledSatellite = preset.getEnabledSatellitePatterns()
            val disabledSatellite = SatellitePattern.values().filter { it !in enabledSatellite }
            prefs[Keys.DISABLED_SATELLITE_PATTERNS] = disabledSatellite.joinToString(",") { it.name }

            val enabledBle = preset.getEnabledBlePatterns()
            val disabledBle = BlePattern.values().filter { it !in enabledBle }
            prefs[Keys.DISABLED_BLE_PATTERNS] = disabledBle.joinToString(",") { it.name }

            val enabledWifi = preset.getEnabledWifiPatterns()
            val disabledWifi = WifiPattern.values().filter { it !in enabledWifi }
            prefs[Keys.DISABLED_WIFI_PATTERNS] = disabledWifi.joinToString(",") { it.name }

            // Apply thresholds
            val cellularThresholds = preset.getCellularThresholds()
            prefs[Keys.CELL_SIGNAL_SPIKE_THRESHOLD] = cellularThresholds.signalSpikeThreshold
            prefs[Keys.CELL_RAPID_SWITCH_STATIONARY] = cellularThresholds.rapidSwitchCountStationary
            prefs[Keys.CELL_RAPID_SWITCH_MOVING] = cellularThresholds.rapidSwitchCountMoving
            prefs[Keys.CELL_TRUSTED_THRESHOLD] = cellularThresholds.trustedCellThreshold
            prefs[Keys.CELL_ANOMALY_INTERVAL] = cellularThresholds.minAnomalyIntervalMs

            val satelliteThresholds = preset.getSatelliteThresholds()
            prefs[Keys.SAT_UNEXPECTED_THRESHOLD] = satelliteThresholds.unexpectedSatelliteThresholdMs
            prefs[Keys.SAT_RAPID_HANDOFF_THRESHOLD] = satelliteThresholds.rapidHandoffThresholdMs
            prefs[Keys.SAT_MIN_TERRESTRIAL_SIGNAL] = satelliteThresholds.minSignalForTerrestrial
            prefs[Keys.SAT_RAPID_SWITCH_WINDOW] = satelliteThresholds.rapidSwitchingWindowMs
            prefs[Keys.SAT_RAPID_SWITCH_COUNT] = satelliteThresholds.rapidSwitchingCount

            val bleThresholds = preset.getBleThresholds()
            prefs[Keys.BLE_MIN_RSSI] = bleThresholds.minRssiForAlert
            prefs[Keys.BLE_PROXIMITY_RSSI] = bleThresholds.proximityAlertRssi
            prefs[Keys.BLE_TRACKING_DURATION] = bleThresholds.trackingDurationMs
            prefs[Keys.BLE_TRACKING_COUNT] = bleThresholds.minSeenCountForTracking

            val wifiThresholds = preset.getWifiThresholds()
            prefs[Keys.WIFI_MIN_SIGNAL] = wifiThresholds.minSignalForAlert
            prefs[Keys.WIFI_STRONG_SIGNAL] = wifiThresholds.strongSignalThreshold
            prefs[Keys.WIFI_TRACKING_DURATION] = wifiThresholds.trackingDurationMs
            prefs[Keys.WIFI_TRACKING_COUNT] = wifiThresholds.minSeenCountForTracking
            prefs[Keys.WIFI_MIN_TRACKING_DISTANCE] = wifiThresholds.minTrackingDistanceMeters

            // Apply hidden network RF anomaly setting
            prefs[Keys.ENABLE_HIDDEN_NETWORK_RF_ANOMALY] = preset.getEnableHiddenNetworkRfAnomaly()
        }
    }

    /**
     * Update only the current preset indicator without changing other settings.
     * Use this when user modifications should switch the preset to CUSTOM.
     */
    suspend fun setCurrentPreset(preset: ProtectionPreset) {
        context.detectionDataStore.edit { prefs ->
            prefs[Keys.CURRENT_PRESET] = preset.name
        }
    }

    /**
     * Marks the current settings as custom if they don't match the stored preset.
     * Call this after any manual setting change to ensure the preset indicator is accurate.
     */
    suspend fun markAsCustomIfModified(currentSettings: DetectionSettings) {
        if (!currentSettings.matchesCurrentPreset() && currentSettings.currentPreset != ProtectionPreset.CUSTOM) {
            setCurrentPreset(ProtectionPreset.CUSTOM)
        }
    }
}

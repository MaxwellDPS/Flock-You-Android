package com.flockyou.detection.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.flockyou.data.*
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.ThreatLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.detectionConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "detection_config_v2"
)

// ============================================================================
// Core Configuration Classes
// ============================================================================

/**
 * Unified detection configuration that consolidates all scattered threshold
 * and setting configurations across the app.
 *
 * This is the single source of truth for all detection-related settings.
 */
@Serializable
data class DetectionConfig(
    val globalSettings: GlobalDetectionSettings = GlobalDetectionSettings(),
    val protocolConfigs: Map<String, ProtocolConfigWrapper> = defaultProtocolConfigs(),
    val deviceTypeOverrides: Map<String, DeviceTypeOverride> = emptyMap()
) {
    /**
     * Get protocol config for a specific protocol
     */
    fun getProtocolConfig(protocol: DetectionProtocol): ProtocolConfig? {
        return protocolConfigs[protocol.name]?.config
    }

    /**
     * Get BLE protocol config with type safety
     */
    fun getBleConfig(): BleProtocolConfig {
        return (protocolConfigs[DetectionProtocol.BLUETOOTH_LE.name]?.config as? BleProtocolConfig)
            ?: BleProtocolConfig()
    }

    /**
     * Get WiFi protocol config with type safety
     */
    fun getWifiConfig(): WifiProtocolConfig {
        return (protocolConfigs[DetectionProtocol.WIFI.name]?.config as? WifiProtocolConfig)
            ?: WifiProtocolConfig()
    }

    /**
     * Get Cellular protocol config with type safety
     */
    fun getCellularConfig(): CellularProtocolConfig {
        return (protocolConfigs[DetectionProtocol.CELLULAR.name]?.config as? CellularProtocolConfig)
            ?: CellularProtocolConfig()
    }

    /**
     * Get Satellite protocol config with type safety
     */
    fun getSatelliteConfig(): SatelliteProtocolConfig {
        return (protocolConfigs[DetectionProtocol.SATELLITE.name]?.config as? SatelliteProtocolConfig)
            ?: SatelliteProtocolConfig()
    }

    /**
     * Get Ultrasonic protocol config with type safety
     */
    fun getUltrasonicConfig(): UltrasonicProtocolConfig {
        return (protocolConfigs[DetectionProtocol.AUDIO.name]?.config as? UltrasonicProtocolConfig)
            ?: UltrasonicProtocolConfig()
    }

    /**
     * Get GNSS protocol config with type safety
     */
    fun getGnssConfig(): GnssProtocolConfig {
        return (protocolConfigs[DetectionProtocol.GNSS.name]?.config as? GnssProtocolConfig)
            ?: GnssProtocolConfig()
    }

    /**
     * Get RF protocol config with type safety
     */
    fun getRfConfig(): RfProtocolConfig {
        return (protocolConfigs[DetectionProtocol.RF.name]?.config as? RfProtocolConfig)
            ?: RfProtocolConfig()
    }

    /**
     * Check if a specific protocol is enabled
     */
    fun isProtocolEnabled(protocol: DetectionProtocol): Boolean {
        if (!globalSettings.enableDetection) return false
        return protocolConfigs[protocol.name]?.config?.enabled ?: true
    }

    /**
     * Get device type override if exists
     */
    fun getDeviceTypeOverride(deviceType: DeviceType): DeviceTypeOverride? {
        return deviceTypeOverrides[deviceType.name]
    }

    companion object {
        fun defaultProtocolConfigs(): Map<String, ProtocolConfigWrapper> = mapOf(
            DetectionProtocol.BLUETOOTH_LE.name to ProtocolConfigWrapper(
                type = ProtocolConfigType.BLE,
                config = BleProtocolConfig()
            ),
            DetectionProtocol.WIFI.name to ProtocolConfigWrapper(
                type = ProtocolConfigType.WIFI,
                config = WifiProtocolConfig()
            ),
            DetectionProtocol.CELLULAR.name to ProtocolConfigWrapper(
                type = ProtocolConfigType.CELLULAR,
                config = CellularProtocolConfig()
            ),
            DetectionProtocol.SATELLITE.name to ProtocolConfigWrapper(
                type = ProtocolConfigType.SATELLITE,
                config = SatelliteProtocolConfig()
            ),
            DetectionProtocol.AUDIO.name to ProtocolConfigWrapper(
                type = ProtocolConfigType.ULTRASONIC,
                config = UltrasonicProtocolConfig()
            ),
            DetectionProtocol.GNSS.name to ProtocolConfigWrapper(
                type = ProtocolConfigType.GNSS,
                config = GnssProtocolConfig()
            ),
            DetectionProtocol.RF.name to ProtocolConfigWrapper(
                type = ProtocolConfigType.RF,
                config = RfProtocolConfig()
            )
        )
    }
}

// ============================================================================
// Global Detection Settings
// ============================================================================

/**
 * Global settings that apply across all detection protocols
 */
@Serializable
data class GlobalDetectionSettings(
    /** Master switch for all detection */
    val enableDetection: Boolean = true,

    /** Enable advanced mode with more detailed info and raw data display */
    val advancedMode: Boolean = false,

    /** Track and correlate seen devices over time */
    val trackSeenDevices: Boolean = true,

    /** Ephemeral mode - don't persist any detection data */
    val ephemeralMode: Boolean = false,

    /** Default minimum threat level to show in UI */
    val defaultThreatLevelFilter: ThreatLevel = ThreatLevel.INFO,

    /** Enable cross-protocol correlation for enhanced tracking detection */
    val enableCorrelation: Boolean = true,

    /** Maximum age (ms) for detections to be considered "active" */
    val activeDetectionWindowMs: Long = 300_000L, // 5 minutes

    /** Enable AI-powered analysis of detections */
    val enableAiAnalysis: Boolean = false,

    /** Show notifications for new detections */
    val showNotifications: Boolean = true,

    /** Minimum threat level for notifications */
    val notificationThreatLevel: ThreatLevel = ThreatLevel.MEDIUM
)

// ============================================================================
// Protocol Configuration Types
// ============================================================================

/**
 * Wrapper to handle polymorphic serialization of protocol configs
 */
@Serializable
data class ProtocolConfigWrapper(
    val type: ProtocolConfigType,
    val config: ProtocolConfig
)

@Serializable
enum class ProtocolConfigType {
    BLE, WIFI, CELLULAR, SATELLITE, ULTRASONIC, GNSS, RF
}

/**
 * Base sealed class for all protocol-specific configurations
 */
@Serializable
sealed class ProtocolConfig {
    abstract val enabled: Boolean
}

/**
 * Bluetooth Low Energy protocol configuration
 */
@Serializable
data class BleProtocolConfig(
    override val enabled: Boolean = true,

    /** Minimum RSSI to trigger any alert */
    val minRssi: Int = -80,

    /** RSSI threshold for proximity warning (very close) */
    val proximityAlertRssi: Int = -50,

    /** Duration (ms) before triggering a tracking alert */
    val trackingDuration: Long = 300_000L, // 5 minutes

    /** Minimum number of sightings before tracking alert */
    val minSeenCount: Int = 3,

    /** Enabled BLE detection patterns */
    val enabledPatterns: Set<String> = BlePattern.values()
        .filter { it.defaultEnabled }
        .map { it.name }
        .toSet(),

    /** Enable address rotation tracking to detect trackers */
    val enableAddressRotationTracking: Boolean = true,

    /** Address rotation interval threshold (ms) */
    val addressRotationThreshold: Long = 900_000L, // 15 minutes

    /** Enable beacon protocol detection (iBeacon, Eddystone, etc.) */
    val enableBeaconDetection: Boolean = true,

    /** Strong signal threshold for immediate alerts */
    val strongSignalThreshold: Int = -40
) : ProtocolConfig()

/**
 * WiFi protocol configuration
 */
@Serializable
data class WifiProtocolConfig(
    override val enabled: Boolean = true,

    /** Minimum signal level to trigger alert */
    val minSignal: Int = -70,

    /** Signal level for strong signal alert */
    val strongSignalThreshold: Int = -50,

    /** Duration (ms) before tracking alert */
    val trackingDuration: Long = 300_000L, // 5 minutes

    /** Minimum number of sightings before tracking alert */
    val minSeenCount: Int = 3,

    /** Minimum distance traveled (meters) for tracking alert */
    val minTrackingDistance: Double = 1609.0, // 1 mile

    /** Enabled WiFi detection patterns */
    val enabledPatterns: Set<String> = WifiPattern.values()
        .filter { it.defaultEnabled }
        .map { it.name }
        .toSet(),

    /** Enable evil twin detection */
    val enableEvilTwinDetection: Boolean = true,

    /** Enable deauth attack detection */
    val enableDeauthDetection: Boolean = true,

    /** Deauth threshold (count per minute) */
    val deauthThresholdPerMinute: Int = 5,

    /** Enable hidden network detection */
    val enableHiddenNetworkDetection: Boolean = false,

    /** Enable karma attack detection */
    val enableKarmaDetection: Boolean = true
) : ProtocolConfig()

/**
 * Cellular protocol configuration
 */
@Serializable
data class CellularProtocolConfig(
    override val enabled: Boolean = true,

    /** dBm change to trigger spike alert */
    val signalSpikeThreshold: Int = 25,

    /** Max cell tower switches per minute while stationary */
    val rapidSwitchCountStationary: Int = 3,

    /** Max cell tower switches per minute while moving */
    val rapidSwitchCountMoving: Int = 8,

    /** Times seen before cell tower is considered trusted */
    val trustedCellThreshold: Int = 5,

    /** Minimum time (ms) between same anomaly type */
    val minAnomalyInterval: Long = 60_000L, // 1 minute

    /** Enabled cellular detection patterns */
    val enabledPatterns: Set<String> = CellularPattern.values()
        .filter { it.defaultEnabled }
        .map { it.name }
        .toSet(),

    /** Movement speed threshold for stationary vs moving */
    val movementSpeedThreshold: Float = 0.0005f,

    /** Enable encryption downgrade detection */
    val enableEncryptionDowngradeDetection: Boolean = true,

    /** Enable IMSI catcher / StingRay detection */
    val enableImsiCatcherDetection: Boolean = true,

    /** Suspicious MCC/MNC code list (comma-separated) */
    val suspiciousMccMncCodes: String = "001-01,001-02,999-99"
) : ProtocolConfig()

/**
 * Satellite protocol configuration
 */
@Serializable
data class SatelliteProtocolConfig(
    override val enabled: Boolean = true,

    /** Time window (ms) for unexpected satellite connection */
    val unexpectedSatelliteThreshold: Long = 5_000L,

    /** Time (ms) for rapid handoff detection */
    val rapidHandoffThreshold: Long = 2_000L,

    /** Minimum signal (dBm) for good terrestrial coverage */
    val minSignalForTerrestrial: Int = -100,

    /** Enabled satellite detection patterns */
    val enabledPatterns: Set<String> = SatellitePattern.values()
        .filter { it.defaultEnabled }
        .map { it.name }
        .toSet(),

    /** Window (ms) for rapid switching detection */
    val rapidSwitchingWindow: Long = 60_000L,

    /** Count threshold for rapid switching in window */
    val rapidSwitchingCount: Int = 3,

    /** Enable NTN (Non-Terrestrial Network) parameter validation */
    val enableNtnValidation: Boolean = true,

    /** Enable timing anomaly detection */
    val enableTimingAnomalyDetection: Boolean = true
) : ProtocolConfig()

/**
 * Ultrasonic/Audio protocol configuration
 */
@Serializable
data class UltrasonicProtocolConfig(
    override val enabled: Boolean = true,

    /** Minimum amplitude (dB) to trigger detection */
    val minAmplitude: Double = -35.0,

    /** Duration (ms) before tracking alert */
    val trackingDuration: Long = 60_000L, // 1 minute

    /** Enabled ultrasonic source types */
    val enabledSources: Set<String> = setOf(
        "AD_TRACKING",
        "TV_ATTRIBUTION",
        "RETAIL_ANALYTICS",
        "CROSS_DEVICE_LINKING",
        "LOCATION_VERIFICATION"
    ),

    /** Primary frequency range start (Hz) */
    val frequencyRangeStart: Int = 18_000,

    /** Primary frequency range end (Hz) */
    val frequencyRangeEnd: Int = 22_000,

    /** FFT analysis window size */
    val fftWindowSize: Int = 4096,

    /** Enable continuous monitoring */
    val enableContinuousMonitoring: Boolean = false,

    /** Sample rate for audio analysis */
    val sampleRate: Int = 44100
) : ProtocolConfig()

/**
 * GNSS/GPS protocol configuration
 */
@Serializable
data class GnssProtocolConfig(
    override val enabled: Boolean = true,

    /** C/N0 deviation threshold (dB-Hz) for spoofing detection */
    val cn0DeviationThreshold: Double = 10.0,

    /** Clock drift threshold (ns) for anomaly detection */
    val clockDriftThreshold: Double = 100.0,

    /** Minimum satellites required for valid fix */
    val minSatellites: Int = 4,

    /** Enable pseudorange validation */
    val enablePseudorangeValidation: Boolean = true,

    /** Enable carrier phase validation */
    val enableCarrierPhaseValidation: Boolean = true,

    /** Position jump threshold (meters) for spoofing detection */
    val positionJumpThreshold: Double = 100.0,

    /** Enable multipath detection */
    val enableMultipathDetection: Boolean = true,

    /** Enable constellation cross-check */
    val enableConstellationCrossCheck: Boolean = true,

    /** Supported constellations for cross-check */
    val enabledConstellations: Set<String> = setOf("GPS", "GLONASS", "GALILEO", "BEIDOU")
) : ProtocolConfig()

/**
 * RF analysis protocol configuration
 */
@Serializable
data class RfProtocolConfig(
    override val enabled: Boolean = false, // Disabled by default - requires Flipper or system privileges

    /** Hidden network threshold for anomaly detection */
    val hiddenNetworkThreshold: Int = 5,

    /** Enable hidden network anomaly detection */
    val enableHiddenNetworkAnomaly: Boolean = false,

    /** Enable spectrum analysis (requires hardware) */
    val enableSpectrumAnalysis: Boolean = false,

    /** Jammer detection threshold (signal drop in dB) */
    val jammerDetectionThreshold: Int = 30,

    /** Enable sub-GHz scanning (requires Flipper) */
    val enableSubGhzScanning: Boolean = false,

    /** Sub-GHz frequency ranges to scan (comma-separated MHz values) */
    val subGhzFrequencies: String = "315,433,868,915",

    /** Enable drone detection via WiFi patterns */
    val enableDroneDetection: Boolean = true
) : ProtocolConfig()

// ============================================================================
// Device Type Override
// ============================================================================

/**
 * Per-device-type configuration overrides
 */
@Serializable
data class DeviceTypeOverride(
    /** Whether this device type is enabled for detection */
    val enabled: Boolean = true,

    /** Override the default threat score (null = use default) */
    val threatScoreOverride: Int? = null,

    /** Custom recommendations for this device type */
    val customRecommendations: List<String>? = null,

    /** Custom notification settings */
    val showNotification: Boolean? = null,

    /** Custom notification priority */
    val notificationPriority: NotificationPriorityLevel? = null,

    /** Custom alert sound enabled */
    val alertSoundEnabled: Boolean? = null,

    /** Whether to auto-dismiss notifications for this type */
    val autoDismissNotification: Boolean? = null
)

@Serializable
enum class NotificationPriorityLevel {
    LOW, DEFAULT, HIGH, URGENT
}

// ============================================================================
// Detection Config Repository Interface
// ============================================================================

/**
 * Repository interface for managing detection configuration
 */
interface DetectionConfigRepository {
    /**
     * Flow of the current detection configuration
     */
    val configFlow: Flow<DetectionConfig>

    /**
     * Update global detection settings
     */
    suspend fun updateGlobalSettings(settings: GlobalDetectionSettings)

    /**
     * Update a specific protocol's configuration
     */
    suspend fun updateProtocolConfig(protocol: DetectionProtocol, config: ProtocolConfig)

    /**
     * Set an override for a specific device type
     */
    suspend fun setDeviceTypeOverride(deviceType: DeviceType, override: DeviceTypeOverride)

    /**
     * Remove device type override
     */
    suspend fun removeDeviceTypeOverride(deviceType: DeviceType)

    /**
     * Reset all settings to defaults
     */
    suspend fun resetToDefaults()

    /**
     * Migrate from legacy DetectionSettings
     */
    suspend fun migrateFromLegacySettings(legacySettings: DetectionSettings)

    /**
     * Get current config synchronously (for non-coroutine contexts)
     */
    suspend fun getCurrentConfig(): DetectionConfig

    /**
     * Export configuration as JSON string
     */
    suspend fun exportConfig(): String

    /**
     * Import configuration from JSON string
     */
    suspend fun importConfig(json: String): Boolean
}

// ============================================================================
// Detection Config Repository Implementation
// ============================================================================

@Singleton
class DetectionConfigRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : DetectionConfigRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private object Keys {
        val CONFIG_JSON = stringPreferencesKey("detection_config_json")
        val MIGRATION_COMPLETED = booleanPreferencesKey("migration_from_legacy_completed")
        val CONFIG_VERSION = intPreferencesKey("config_version")
    }

    companion object {
        const val CURRENT_CONFIG_VERSION = 1
    }

    override val configFlow: Flow<DetectionConfig> = context.detectionConfigDataStore.data.map { prefs ->
        val configJson = prefs[Keys.CONFIG_JSON]
        if (configJson != null) {
            try {
                json.decodeFromString<DetectionConfig>(configJson)
            } catch (e: Exception) {
                DetectionConfig()
            }
        } else {
            DetectionConfig()
        }
    }

    override suspend fun updateGlobalSettings(settings: GlobalDetectionSettings) {
        context.detectionConfigDataStore.edit { prefs ->
            val currentConfig = getCurrentConfigFromPrefs(prefs)
            val updatedConfig = currentConfig.copy(globalSettings = settings)
            prefs[Keys.CONFIG_JSON] = json.encodeToString(updatedConfig)
        }
    }

    override suspend fun updateProtocolConfig(protocol: DetectionProtocol, config: ProtocolConfig) {
        context.detectionConfigDataStore.edit { prefs ->
            val currentConfig = getCurrentConfigFromPrefs(prefs)
            val type = when (config) {
                is BleProtocolConfig -> ProtocolConfigType.BLE
                is WifiProtocolConfig -> ProtocolConfigType.WIFI
                is CellularProtocolConfig -> ProtocolConfigType.CELLULAR
                is SatelliteProtocolConfig -> ProtocolConfigType.SATELLITE
                is UltrasonicProtocolConfig -> ProtocolConfigType.ULTRASONIC
                is GnssProtocolConfig -> ProtocolConfigType.GNSS
                is RfProtocolConfig -> ProtocolConfigType.RF
            }
            val updatedConfigs = currentConfig.protocolConfigs.toMutableMap()
            updatedConfigs[protocol.name] = ProtocolConfigWrapper(type, config)
            val updatedConfig = currentConfig.copy(protocolConfigs = updatedConfigs)
            prefs[Keys.CONFIG_JSON] = json.encodeToString(updatedConfig)
        }
    }

    override suspend fun setDeviceTypeOverride(deviceType: DeviceType, override: DeviceTypeOverride) {
        context.detectionConfigDataStore.edit { prefs ->
            val currentConfig = getCurrentConfigFromPrefs(prefs)
            val updatedOverrides = currentConfig.deviceTypeOverrides.toMutableMap()
            updatedOverrides[deviceType.name] = override
            val updatedConfig = currentConfig.copy(deviceTypeOverrides = updatedOverrides)
            prefs[Keys.CONFIG_JSON] = json.encodeToString(updatedConfig)
        }
    }

    override suspend fun removeDeviceTypeOverride(deviceType: DeviceType) {
        context.detectionConfigDataStore.edit { prefs ->
            val currentConfig = getCurrentConfigFromPrefs(prefs)
            val updatedOverrides = currentConfig.deviceTypeOverrides.toMutableMap()
            updatedOverrides.remove(deviceType.name)
            val updatedConfig = currentConfig.copy(deviceTypeOverrides = updatedOverrides)
            prefs[Keys.CONFIG_JSON] = json.encodeToString(updatedConfig)
        }
    }

    override suspend fun resetToDefaults() {
        context.detectionConfigDataStore.edit { prefs ->
            prefs[Keys.CONFIG_JSON] = json.encodeToString(DetectionConfig())
            prefs[Keys.CONFIG_VERSION] = CURRENT_CONFIG_VERSION
        }
    }

    override suspend fun migrateFromLegacySettings(legacySettings: DetectionSettings) {
        context.detectionConfigDataStore.edit { prefs ->
            // Check if migration already completed
            if (prefs[Keys.MIGRATION_COMPLETED] == true) {
                return@edit
            }

            val migratedConfig = DetectionConfig(
                globalSettings = GlobalDetectionSettings(
                    enableDetection = true,
                    advancedMode = legacySettings.advancedMode,
                    trackSeenDevices = true,
                    ephemeralMode = false,
                    defaultThreatLevelFilter = ThreatLevel.INFO
                ),
                protocolConfigs = mapOf(
                    DetectionProtocol.BLUETOOTH_LE.name to ProtocolConfigWrapper(
                        type = ProtocolConfigType.BLE,
                        config = BleProtocolConfig(
                            enabled = legacySettings.enableBleDetection,
                            minRssi = legacySettings.bleThresholds.minRssiForAlert,
                            proximityAlertRssi = legacySettings.bleThresholds.proximityAlertRssi,
                            trackingDuration = legacySettings.bleThresholds.trackingDurationMs,
                            minSeenCount = legacySettings.bleThresholds.minSeenCountForTracking,
                            enabledPatterns = legacySettings.enabledBlePatterns.map { it.name }.toSet()
                        )
                    ),
                    DetectionProtocol.WIFI.name to ProtocolConfigWrapper(
                        type = ProtocolConfigType.WIFI,
                        config = WifiProtocolConfig(
                            enabled = legacySettings.enableWifiDetection,
                            minSignal = legacySettings.wifiThresholds.minSignalForAlert,
                            strongSignalThreshold = legacySettings.wifiThresholds.strongSignalThreshold,
                            trackingDuration = legacySettings.wifiThresholds.trackingDurationMs,
                            minSeenCount = legacySettings.wifiThresholds.minSeenCountForTracking,
                            minTrackingDistance = legacySettings.wifiThresholds.minTrackingDistanceMeters,
                            enabledPatterns = legacySettings.enabledWifiPatterns.map { it.name }.toSet()
                        )
                    ),
                    DetectionProtocol.CELLULAR.name to ProtocolConfigWrapper(
                        type = ProtocolConfigType.CELLULAR,
                        config = CellularProtocolConfig(
                            enabled = legacySettings.enableCellularDetection,
                            signalSpikeThreshold = legacySettings.cellularThresholds.signalSpikeThreshold,
                            rapidSwitchCountStationary = legacySettings.cellularThresholds.rapidSwitchCountStationary,
                            rapidSwitchCountMoving = legacySettings.cellularThresholds.rapidSwitchCountMoving,
                            trustedCellThreshold = legacySettings.cellularThresholds.trustedCellThreshold,
                            minAnomalyInterval = legacySettings.cellularThresholds.minAnomalyIntervalMs,
                            enabledPatterns = legacySettings.enabledCellularPatterns.map { it.name }.toSet()
                        )
                    ),
                    DetectionProtocol.SATELLITE.name to ProtocolConfigWrapper(
                        type = ProtocolConfigType.SATELLITE,
                        config = SatelliteProtocolConfig(
                            enabled = legacySettings.enableSatelliteDetection,
                            unexpectedSatelliteThreshold = legacySettings.satelliteThresholds.unexpectedSatelliteThresholdMs,
                            rapidHandoffThreshold = legacySettings.satelliteThresholds.rapidHandoffThresholdMs,
                            minSignalForTerrestrial = legacySettings.satelliteThresholds.minSignalForTerrestrial,
                            enabledPatterns = legacySettings.enabledSatellitePatterns.map { it.name }.toSet(),
                            rapidSwitchingWindow = legacySettings.satelliteThresholds.rapidSwitchingWindowMs,
                            rapidSwitchingCount = legacySettings.satelliteThresholds.rapidSwitchingCount
                        )
                    ),
                    DetectionProtocol.AUDIO.name to ProtocolConfigWrapper(
                        type = ProtocolConfigType.ULTRASONIC,
                        config = UltrasonicProtocolConfig()
                    ),
                    DetectionProtocol.GNSS.name to ProtocolConfigWrapper(
                        type = ProtocolConfigType.GNSS,
                        config = GnssProtocolConfig()
                    ),
                    DetectionProtocol.RF.name to ProtocolConfigWrapper(
                        type = ProtocolConfigType.RF,
                        config = RfProtocolConfig(
                            enableHiddenNetworkAnomaly = legacySettings.enableHiddenNetworkRfAnomaly
                        )
                    )
                ),
                deviceTypeOverrides = emptyMap()
            )

            prefs[Keys.CONFIG_JSON] = json.encodeToString(migratedConfig)
            prefs[Keys.MIGRATION_COMPLETED] = true
            prefs[Keys.CONFIG_VERSION] = CURRENT_CONFIG_VERSION
        }
    }

    override suspend fun getCurrentConfig(): DetectionConfig {
        return configFlow.first()
    }

    override suspend fun exportConfig(): String {
        val config = getCurrentConfig()
        return json.encodeToString(config)
    }

    override suspend fun importConfig(jsonString: String): Boolean {
        return try {
            val importedConfig = json.decodeFromString<DetectionConfig>(jsonString)
            context.detectionConfigDataStore.edit { prefs ->
                prefs[Keys.CONFIG_JSON] = json.encodeToString(importedConfig)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentConfigFromPrefs(prefs: Preferences): DetectionConfig {
        val configJson = prefs[Keys.CONFIG_JSON]
        return if (configJson != null) {
            try {
                json.decodeFromString<DetectionConfig>(configJson)
            } catch (e: Exception) {
                DetectionConfig()
            }
        } else {
            DetectionConfig()
        }
    }
}

// ============================================================================
// Migration Helper
// ============================================================================

/**
 * Helper class to facilitate migration from legacy DetectionSettings
 */
class DetectionConfigMigrationHelper @Inject constructor(
    private val legacyRepository: DetectionSettingsRepository,
    private val newRepository: DetectionConfigRepository
) {
    /**
     * Perform migration if needed
     */
    suspend fun migrateIfNeeded() {
        val legacySettings = legacyRepository.settings.first()
        newRepository.migrateFromLegacySettings(legacySettings)
    }
}

// ============================================================================
// Extension Functions for Convenience
// ============================================================================

/**
 * Check if a specific BLE pattern is enabled
 */
fun DetectionConfig.isBlePatternEnabled(pattern: BlePattern): Boolean {
    val bleConfig = getBleConfig()
    return bleConfig.enabled && pattern.name in bleConfig.enabledPatterns
}

/**
 * Check if a specific WiFi pattern is enabled
 */
fun DetectionConfig.isWifiPatternEnabled(pattern: WifiPattern): Boolean {
    val wifiConfig = getWifiConfig()
    return wifiConfig.enabled && pattern.name in wifiConfig.enabledPatterns
}

/**
 * Check if a specific Cellular pattern is enabled
 */
fun DetectionConfig.isCellularPatternEnabled(pattern: CellularPattern): Boolean {
    val cellularConfig = getCellularConfig()
    return cellularConfig.enabled && pattern.name in cellularConfig.enabledPatterns
}

/**
 * Check if a specific Satellite pattern is enabled
 */
fun DetectionConfig.isSatellitePatternEnabled(pattern: SatellitePattern): Boolean {
    val satelliteConfig = getSatelliteConfig()
    return satelliteConfig.enabled && pattern.name in satelliteConfig.enabledPatterns
}

/**
 * Get effective threat score for a device type (considering overrides)
 */
fun DetectionConfig.getEffectiveThreatScore(deviceType: DeviceType, defaultScore: Int): Int {
    val override = getDeviceTypeOverride(deviceType)
    return override?.threatScoreOverride ?: defaultScore
}

/**
 * Check if a device type is enabled (considering overrides)
 */
fun DetectionConfig.isDeviceTypeEnabled(deviceType: DeviceType): Boolean {
    val override = getDeviceTypeOverride(deviceType)
    return override?.enabled ?: true
}

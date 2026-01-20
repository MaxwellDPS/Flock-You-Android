package com.flockyou.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationDataStore: DataStore<Preferences> by preferencesDataStore(name = "notification_settings")
private val Context.ruleDataStore: DataStore<Preferences> by preferencesDataStore(name = "rule_settings")

// ==================== Notification Settings ====================

data class NotificationSettings(
    val enabled: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val vibratePattern: VibratePattern = VibratePattern.DEFAULT,
    val showOnLockScreen: Boolean = true,
    val persistentNotification: Boolean = true,
    val bypassDnd: Boolean = true, // Allow critical alerts to bypass Do Not Disturb
    val emergencyPopupEnabled: Boolean = true, // Show full-screen CMAS/WEA-style emergency popup for critical alerts
    val criticalAlertsEnabled: Boolean = true,
    val highAlertsEnabled: Boolean = true,
    val mediumAlertsEnabled: Boolean = true,
    val lowAlertsEnabled: Boolean = false,
    val infoAlertsEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: Int = 22, // 10 PM
    val quietHoursEnd: Int = 7 // 7 AM
)

enum class VibratePattern(val displayName: String, val pattern: LongArray) {
    DEFAULT("Default", longArrayOf(0, 100, 100, 100)),
    URGENT("Urgent", longArrayOf(0, 200, 100, 200, 100, 200)),
    GENTLE("Gentle", longArrayOf(0, 50, 50)),
    LONG("Long", longArrayOf(0, 500)),
    SOS("SOS", longArrayOf(0, 100, 100, 100, 100, 100, 300, 100, 300, 100, 300, 100, 100, 100, 100, 100, 100))
}

@Singleton
class NotificationSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("notifications_enabled")
        val SOUND = booleanPreferencesKey("notifications_sound")
        val VIBRATE = booleanPreferencesKey("notifications_vibrate")
        val VIBRATE_PATTERN = stringPreferencesKey("notifications_vibrate_pattern")
        val SHOW_LOCK_SCREEN = booleanPreferencesKey("notifications_lock_screen")
        val PERSISTENT = booleanPreferencesKey("notifications_persistent")
        val BYPASS_DND = booleanPreferencesKey("notifications_bypass_dnd")
        val EMERGENCY_POPUP = booleanPreferencesKey("notifications_emergency_popup")
        val CRITICAL_ALERTS = booleanPreferencesKey("alerts_critical")
        val HIGH_ALERTS = booleanPreferencesKey("alerts_high")
        val MEDIUM_ALERTS = booleanPreferencesKey("alerts_medium")
        val LOW_ALERTS = booleanPreferencesKey("alerts_low")
        val INFO_ALERTS = booleanPreferencesKey("alerts_info")
        val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start")
        val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end")
    }
    
    val settings: Flow<NotificationSettings> = context.notificationDataStore.data.map { prefs ->
        NotificationSettings(
            enabled = prefs[Keys.ENABLED] ?: true,
            sound = prefs[Keys.SOUND] ?: true,
            vibrate = prefs[Keys.VIBRATE] ?: true,
            vibratePattern = prefs[Keys.VIBRATE_PATTERN]?.let {
                try { VibratePattern.valueOf(it) } catch (e: Exception) { VibratePattern.DEFAULT }
            } ?: VibratePattern.DEFAULT,
            showOnLockScreen = prefs[Keys.SHOW_LOCK_SCREEN] ?: true,
            persistentNotification = prefs[Keys.PERSISTENT] ?: true,
            bypassDnd = prefs[Keys.BYPASS_DND] ?: true,
            emergencyPopupEnabled = prefs[Keys.EMERGENCY_POPUP] ?: true,
            criticalAlertsEnabled = prefs[Keys.CRITICAL_ALERTS] ?: true,
            highAlertsEnabled = prefs[Keys.HIGH_ALERTS] ?: true,
            mediumAlertsEnabled = prefs[Keys.MEDIUM_ALERTS] ?: true,
            lowAlertsEnabled = prefs[Keys.LOW_ALERTS] ?: false,
            infoAlertsEnabled = prefs[Keys.INFO_ALERTS] ?: false,
            quietHoursEnabled = prefs[Keys.QUIET_HOURS_ENABLED] ?: false,
            quietHoursStart = prefs[Keys.QUIET_HOURS_START] ?: 22,
            quietHoursEnd = prefs[Keys.QUIET_HOURS_END] ?: 7
        )
    }
    
    suspend fun updateSettings(update: (NotificationSettings) -> NotificationSettings) {
        context.notificationDataStore.edit { prefs ->
            val current = NotificationSettings(
                enabled = prefs[Keys.ENABLED] ?: true,
                sound = prefs[Keys.SOUND] ?: true,
                vibrate = prefs[Keys.VIBRATE] ?: true,
                vibratePattern = prefs[Keys.VIBRATE_PATTERN]?.let {
                    try { VibratePattern.valueOf(it) } catch (e: Exception) { VibratePattern.DEFAULT }
                } ?: VibratePattern.DEFAULT,
                showOnLockScreen = prefs[Keys.SHOW_LOCK_SCREEN] ?: true,
                persistentNotification = prefs[Keys.PERSISTENT] ?: true,
                bypassDnd = prefs[Keys.BYPASS_DND] ?: true,
                emergencyPopupEnabled = prefs[Keys.EMERGENCY_POPUP] ?: true,
                criticalAlertsEnabled = prefs[Keys.CRITICAL_ALERTS] ?: true,
                highAlertsEnabled = prefs[Keys.HIGH_ALERTS] ?: true,
                mediumAlertsEnabled = prefs[Keys.MEDIUM_ALERTS] ?: true,
                lowAlertsEnabled = prefs[Keys.LOW_ALERTS] ?: false,
                infoAlertsEnabled = prefs[Keys.INFO_ALERTS] ?: false,
                quietHoursEnabled = prefs[Keys.QUIET_HOURS_ENABLED] ?: false,
                quietHoursStart = prefs[Keys.QUIET_HOURS_START] ?: 22,
                quietHoursEnd = prefs[Keys.QUIET_HOURS_END] ?: 7
            )
            val updated = update(current)
            prefs[Keys.ENABLED] = updated.enabled
            prefs[Keys.SOUND] = updated.sound
            prefs[Keys.VIBRATE] = updated.vibrate
            prefs[Keys.VIBRATE_PATTERN] = updated.vibratePattern.name
            prefs[Keys.SHOW_LOCK_SCREEN] = updated.showOnLockScreen
            prefs[Keys.PERSISTENT] = updated.persistentNotification
            prefs[Keys.BYPASS_DND] = updated.bypassDnd
            prefs[Keys.EMERGENCY_POPUP] = updated.emergencyPopupEnabled
            prefs[Keys.CRITICAL_ALERTS] = updated.criticalAlertsEnabled
            prefs[Keys.HIGH_ALERTS] = updated.highAlertsEnabled
            prefs[Keys.MEDIUM_ALERTS] = updated.mediumAlertsEnabled
            prefs[Keys.LOW_ALERTS] = updated.lowAlertsEnabled
            prefs[Keys.INFO_ALERTS] = updated.infoAlertsEnabled
            prefs[Keys.QUIET_HOURS_ENABLED] = updated.quietHoursEnabled
            prefs[Keys.QUIET_HOURS_START] = updated.quietHoursStart
            prefs[Keys.QUIET_HOURS_END] = updated.quietHoursEnd
        }
    }
    
    fun shouldAlert(threatLevel: ThreatLevel, settings: NotificationSettings): Boolean {
        if (!settings.enabled) return false
        
        // Check quiet hours
        if (settings.quietHoursEnabled) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val inQuietHours = if (settings.quietHoursStart > settings.quietHoursEnd) {
                // Spans midnight (e.g., 22:00 to 07:00)
                hour >= settings.quietHoursStart || hour < settings.quietHoursEnd
            } else {
                hour >= settings.quietHoursStart && hour < settings.quietHoursEnd
            }
            if (inQuietHours && threatLevel != ThreatLevel.CRITICAL) return false
        }
        
        return when (threatLevel) {
            ThreatLevel.CRITICAL -> settings.criticalAlertsEnabled
            ThreatLevel.HIGH -> settings.highAlertsEnabled
            ThreatLevel.MEDIUM -> settings.mediumAlertsEnabled
            ThreatLevel.LOW -> settings.lowAlertsEnabled
            ThreatLevel.INFO -> settings.infoAlertsEnabled
        }
    }
}

// ==================== Rule Settings ====================

/**
 * Scanner type categories for custom rules
 */
enum class ScannerType(val displayName: String, val description: String) {
    WIFI("WiFi", "WiFi network and access point detection"),
    BLUETOOTH("Bluetooth LE", "Bluetooth Low Energy device detection"),
    CELLULAR("Cellular", "Cell tower and IMSI catcher detection"),
    SATELLITE("Satellite", "NTN and satellite connection detection"),
    GNSS("GNSS/GPS", "GPS spoofing and jamming detection"),
    RF("RF Analysis", "Radio frequency signal analysis"),
    ULTRASONIC("Ultrasonic", "Ultrasonic beacon detection")
}

/**
 * Rule types for pattern-based matching across all scanner types
 */
enum class RuleType(val displayName: String, val scannerType: ScannerType, val patternHint: String) {
    // WiFi rules
    SSID_REGEX("WiFi SSID", ScannerType.WIFI, "(?i)^surveillance[_-]?.*"),
    MAC_PREFIX("MAC Prefix", ScannerType.WIFI, "AA:BB:CC"),

    // Bluetooth rules
    BLE_NAME_REGEX("BLE Device Name", ScannerType.BLUETOOTH, "(?i)^tracker[_-]?.*"),
    BLE_SERVICE_UUID("BLE Service UUID", ScannerType.BLUETOOTH, "0000XXXX-0000-1000-8000-00805f9b34fb (replace XXXX with 16-bit UUID)"),

    // Cellular rules
    CELLULAR_MCC_MNC("Cell MCC-MNC", ScannerType.CELLULAR, "001-01"),
    CELLULAR_LAC_RANGE("Cell LAC Range", ScannerType.CELLULAR, "1000-2000"),
    CELLULAR_CELL_ID("Cell ID Pattern", ScannerType.CELLULAR, "12345*"),

    // Satellite rules
    SATELLITE_NETWORK_ID("Satellite Network ID", ScannerType.SATELLITE, "STARLINK-*"),

    // RF rules
    RF_FREQUENCY_RANGE("RF Frequency Range", ScannerType.RF, "2400-2500"),

    // Ultrasonic rules
    ULTRASONIC_FREQUENCY("Ultrasonic Frequency", ScannerType.ULTRASONIC, "18000-22000")
}

/**
 * Pattern-based custom rule for signature matching
 */
data class CustomRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val pattern: String,
    val type: RuleType,
    val deviceType: DeviceType = DeviceType.UNKNOWN_SURVEILLANCE,
    val threatScore: Int = 50,
    val description: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val scannerType: ScannerType get() = type.scannerType
}

/**
 * Heuristic condition operators for behavioral rules
 */
enum class HeuristicOperator(val displayName: String, val symbol: String) {
    GREATER_THAN("Greater than", ">"),
    LESS_THAN("Less than", "<"),
    EQUALS("Equals", "="),
    NOT_EQUALS("Not equals", "≠"),
    GREATER_EQUAL("Greater or equal", "≥"),
    LESS_EQUAL("Less or equal", "≤"),
    BETWEEN("Between", "↔"),
    CONTAINS("Contains", "∈"),
    MATCHES_REGEX("Matches regex", "~")
}

/**
 * Heuristic field types for behavioral conditions
 */
enum class HeuristicField(
    val displayName: String,
    val scannerType: ScannerType,
    val description: String,
    val unit: String = "",
    val defaultThreshold: String = ""
) {
    // Cellular heuristics
    CELL_SIGNAL_CHANGE("Signal Change", ScannerType.CELLULAR, "Sudden change in signal strength", "dBm", "25"),
    CELL_SWITCH_COUNT("Cell Switches", ScannerType.CELLULAR, "Cell tower switches per minute", "/min", "5"),
    CELL_ENCRYPTION_LEVEL("Encryption Level", ScannerType.CELLULAR, "Network encryption (5=5G, 4=4G, 3=3G, 2=2G)", "", "3"),
    CELL_LAC_CHANGE("LAC Change", ScannerType.CELLULAR, "Location Area Code changed without cell change", "", "0"),
    CELL_ROAMING_STATE("Roaming State", ScannerType.CELLULAR, "Unexpected roaming status", "", ""),

    // Satellite heuristics
    SAT_CONNECTION_TIME("Satellite Connection", ScannerType.SATELLITE, "Time connected to satellite when terrestrial available", "ms", "5000"),
    SAT_HANDOFF_SPEED("Handoff Speed", ScannerType.SATELLITE, "Speed of satellite handoffs", "ms", "2000"),
    SAT_SIGNAL_TERRESTRIAL("Terrestrial Signal", ScannerType.SATELLITE, "Minimum signal for terrestrial", "dBm", "-100"),
    SAT_SWITCH_COUNT("Rapid Switches", ScannerType.SATELLITE, "Satellite switches in window", "/min", "3"),

    // WiFi heuristics
    WIFI_SIGNAL_CHANGE("WiFi Signal Change", ScannerType.WIFI, "Sudden WiFi signal strength change", "dBm", "20"),
    WIFI_SAME_SSID_MACS("Same SSID MACs", ScannerType.WIFI, "Number of MACs broadcasting same SSID", "", "2"),
    WIFI_DEAUTH_COUNT("Deauth Count", ScannerType.WIFI, "Deauthentication frames detected", "/min", "3"),
    WIFI_SEEN_LOCATIONS("Seen Locations", ScannerType.WIFI, "Network seen at multiple locations", "", "3"),

    // BLE heuristics
    BLE_RSSI_THRESHOLD("BLE RSSI", ScannerType.BLUETOOTH, "BLE signal strength threshold", "dBm", "-50"),
    BLE_TRACKING_DURATION("Tracking Duration", ScannerType.BLUETOOTH, "Device following for duration", "ms", "300000"),
    BLE_SEEN_COUNT("Seen Count", ScannerType.BLUETOOTH, "Times device has been seen", "", "3"),
    BLE_SERVICE_COUNT("Service Count", ScannerType.BLUETOOTH, "Number of matching service UUIDs", "", "2"),

    // RF heuristics
    RF_SIGNAL_DROP("Signal Drop", ScannerType.RF, "Sudden drop in wireless signals (jamming)", "dBm", "25"),
    RF_BASELINE_DEVIATION("Baseline Deviation", ScannerType.RF, "Deviation from RF baseline", "%", "50"),
    RF_ANOMALY_COUNT("Anomaly Count", ScannerType.RF, "Consecutive anomalous readings", "", "3"),
    RF_CAMERA_DENSITY("Camera Density", ScannerType.RF, "Surveillance camera concentration", "", "5"),

    // GNSS heuristics
    GNSS_POSITION_JUMP("Position Jump", ScannerType.GNSS, "Impossible position change", "m", "1000"),
    GNSS_SIGNAL_QUALITY("Signal Quality", ScannerType.GNSS, "GNSS signal quality threshold", "dB-Hz", "30"),
    GNSS_SATELLITE_COUNT("Satellite Count", ScannerType.GNSS, "Visible satellites below threshold", "", "4"),
    GNSS_CLOCK_DRIFT("Clock Drift", ScannerType.GNSS, "Clock timing anomaly threshold", "ns", "1000"),

    // Ultrasonic heuristics
    ULTRASONIC_DB_THRESHOLD("dB Threshold", ScannerType.ULTRASONIC, "Detection threshold above noise floor", "dB", "-40"),
    ULTRASONIC_FREQUENCY_RANGE("Frequency Range", ScannerType.ULTRASONIC, "Ultrasonic frequency to detect", "Hz", "18000-22000"),
    ULTRASONIC_DURATION("Duration", ScannerType.ULTRASONIC, "Signal duration threshold", "ms", "500")
}

/**
 * A single condition within a heuristic rule
 */
data class HeuristicCondition(
    val field: HeuristicField,
    val operator: HeuristicOperator,
    val value: String,
    val secondValue: String? = null // For BETWEEN operator
)

/**
 * Logical combination for multiple conditions
 */
enum class ConditionLogic(val displayName: String) {
    AND("All conditions (AND)"),
    OR("Any condition (OR)")
}

/**
 * User-defined heuristic rule for behavioral/anomaly detection
 */
data class HeuristicRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val scannerType: ScannerType,
    val conditions: List<HeuristicCondition>,
    val conditionLogic: ConditionLogic = ConditionLogic.AND,
    val deviceType: DeviceType = DeviceType.UNKNOWN_SURVEILLANCE,
    val threatScore: Int = 50,
    val cooldownMs: Long = 60000L,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun evaluate(fieldValues: Map<HeuristicField, Any>): Boolean {
        val results = conditions.map { condition ->
            val actualValue = fieldValues[condition.field] ?: return@map false
            evaluateCondition(condition, actualValue)
        }
        return when (conditionLogic) {
            ConditionLogic.AND -> results.all { it }
            ConditionLogic.OR -> results.any { it }
        }
    }

    private fun evaluateCondition(condition: HeuristicCondition, actualValue: Any): Boolean {
        return try {
            when (condition.operator) {
                HeuristicOperator.GREATER_THAN -> (actualValue as? Number)?.toDouble()?.let { it > condition.value.toDouble() } ?: false
                HeuristicOperator.LESS_THAN -> (actualValue as? Number)?.toDouble()?.let { it < condition.value.toDouble() } ?: false
                HeuristicOperator.EQUALS -> actualValue.toString() == condition.value
                HeuristicOperator.NOT_EQUALS -> actualValue.toString() != condition.value
                HeuristicOperator.GREATER_EQUAL -> (actualValue as? Number)?.toDouble()?.let { it >= condition.value.toDouble() } ?: false
                HeuristicOperator.LESS_EQUAL -> (actualValue as? Number)?.toDouble()?.let { it <= condition.value.toDouble() } ?: false
                HeuristicOperator.BETWEEN -> {
                    val num = (actualValue as? Number)?.toDouble() ?: return false
                    val min = condition.value.toDouble()
                    val max = condition.secondValue?.toDouble() ?: return false
                    num in min..max
                }
                HeuristicOperator.CONTAINS -> actualValue.toString().contains(condition.value, ignoreCase = true)
                HeuristicOperator.MATCHES_REGEX -> Regex(condition.value).containsMatchIn(actualValue.toString())
            }
        } catch (e: Exception) { false }
    }
}

enum class BuiltInRuleCategory(val displayName: String, val description: String) {
    FLOCK_SAFETY("Flock Safety", "ALPR cameras and Raven audio sensors"),
    POLICE_TECH("Police Technology", "Law enforcement equipment manufacturers"),
    ACOUSTIC_SENSORS("Acoustic Sensors", "Gunshot detection and audio surveillance"),
    GENERIC_SURVEILLANCE("Generic Surveillance", "Other surveillance patterns")
}

@Singleton
class RuleSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private object Keys {
        val DISABLED_BUILTIN_RULES = stringPreferencesKey("disabled_builtin_rules")
        val CUSTOM_RULES = stringPreferencesKey("custom_rules")
        val HEURISTIC_RULES = stringPreferencesKey("heuristic_rules")
        val FLOCK_SAFETY_ENABLED = booleanPreferencesKey("category_flock_safety")
        val POLICE_TECH_ENABLED = booleanPreferencesKey("category_police_tech")
        val ACOUSTIC_SENSORS_ENABLED = booleanPreferencesKey("category_acoustic_sensors")
        val GENERIC_SURVEILLANCE_ENABLED = booleanPreferencesKey("category_generic")
    }

    data class RuleSettings(
        val disabledBuiltinRules: Set<String> = emptySet(),
        val customRules: List<CustomRule> = emptyList(),
        val heuristicRules: List<HeuristicRule> = emptyList(),
        val flockSafetyEnabled: Boolean = true,
        val policeTechEnabled: Boolean = true,
        val acousticSensorsEnabled: Boolean = true,
        val genericSurveillanceEnabled: Boolean = true
    ) {
        val enabledCustomRules: List<CustomRule> get() = customRules.filter { it.enabled }
        val enabledHeuristicRules: List<HeuristicRule> get() = heuristicRules.filter { it.enabled }

        fun getCustomRulesByScannerType(scannerType: ScannerType): List<CustomRule> =
            enabledCustomRules.filter { it.scannerType == scannerType }

        fun getHeuristicRulesByScannerType(scannerType: ScannerType): List<HeuristicRule> =
            enabledHeuristicRules.filter { it.scannerType == scannerType }
    }

    val settings: Flow<RuleSettings> = context.ruleDataStore.data.map { prefs ->
        val disabledRulesJson = prefs[Keys.DISABLED_BUILTIN_RULES] ?: "[]"
        val customRulesJson = prefs[Keys.CUSTOM_RULES] ?: "[]"
        val heuristicRulesJson = prefs[Keys.HEURISTIC_RULES] ?: "[]"

        val disabledRules: Set<String> = try {
            gson.fromJson(disabledRulesJson, object : TypeToken<Set<String>>() {}.type)
        } catch (e: Exception) { emptySet() }

        val customRules: List<CustomRule> = try {
            gson.fromJson(customRulesJson, object : TypeToken<List<CustomRule>>() {}.type)
        } catch (e: Exception) { emptyList() }

        val heuristicRules: List<HeuristicRule> = try {
            gson.fromJson(heuristicRulesJson, object : TypeToken<List<HeuristicRule>>() {}.type)
        } catch (e: Exception) { emptyList() }

        RuleSettings(
            disabledBuiltinRules = disabledRules,
            customRules = customRules,
            heuristicRules = heuristicRules,
            flockSafetyEnabled = prefs[Keys.FLOCK_SAFETY_ENABLED] ?: true,
            policeTechEnabled = prefs[Keys.POLICE_TECH_ENABLED] ?: true,
            acousticSensorsEnabled = prefs[Keys.ACOUSTIC_SENSORS_ENABLED] ?: true,
            genericSurveillanceEnabled = prefs[Keys.GENERIC_SURVEILLANCE_ENABLED] ?: true
        )
    }

    suspend fun toggleBuiltinRule(ruleId: String, enabled: Boolean) {
        context.ruleDataStore.edit { prefs ->
            val current: Set<String> = try {
                gson.fromJson(prefs[Keys.DISABLED_BUILTIN_RULES] ?: "[]", object : TypeToken<Set<String>>() {}.type)
            } catch (e: Exception) { emptySet() }

            val updated = if (enabled) {
                current - ruleId
            } else {
                current + ruleId
            }
            prefs[Keys.DISABLED_BUILTIN_RULES] = gson.toJson(updated)
        }
    }

    suspend fun setCategoryEnabled(category: BuiltInRuleCategory, enabled: Boolean) {
        context.ruleDataStore.edit { prefs ->
            when (category) {
                BuiltInRuleCategory.FLOCK_SAFETY -> prefs[Keys.FLOCK_SAFETY_ENABLED] = enabled
                BuiltInRuleCategory.POLICE_TECH -> prefs[Keys.POLICE_TECH_ENABLED] = enabled
                BuiltInRuleCategory.ACOUSTIC_SENSORS -> prefs[Keys.ACOUSTIC_SENSORS_ENABLED] = enabled
                BuiltInRuleCategory.GENERIC_SURVEILLANCE -> prefs[Keys.GENERIC_SURVEILLANCE_ENABLED] = enabled
            }
        }
    }

    // Custom Rule CRUD operations
    suspend fun addCustomRule(rule: CustomRule) {
        context.ruleDataStore.edit { prefs ->
            val current: List<CustomRule> = try {
                gson.fromJson(prefs[Keys.CUSTOM_RULES] ?: "[]", object : TypeToken<List<CustomRule>>() {}.type)
            } catch (e: Exception) { emptyList() }

            prefs[Keys.CUSTOM_RULES] = gson.toJson(current + rule)
        }
    }

    suspend fun updateCustomRule(rule: CustomRule) {
        context.ruleDataStore.edit { prefs ->
            val current: List<CustomRule> = try {
                gson.fromJson(prefs[Keys.CUSTOM_RULES] ?: "[]", object : TypeToken<List<CustomRule>>() {}.type)
            } catch (e: Exception) { emptyList() }

            val updated = current.map { if (it.id == rule.id) rule else it }
            prefs[Keys.CUSTOM_RULES] = gson.toJson(updated)
        }
    }

    suspend fun deleteCustomRule(ruleId: String) {
        context.ruleDataStore.edit { prefs ->
            val current: List<CustomRule> = try {
                gson.fromJson(prefs[Keys.CUSTOM_RULES] ?: "[]", object : TypeToken<List<CustomRule>>() {}.type)
            } catch (e: Exception) { emptyList() }

            prefs[Keys.CUSTOM_RULES] = gson.toJson(current.filter { it.id != ruleId })
        }
    }

    suspend fun toggleCustomRule(ruleId: String, enabled: Boolean) {
        context.ruleDataStore.edit { prefs ->
            val current: List<CustomRule> = try {
                gson.fromJson(prefs[Keys.CUSTOM_RULES] ?: "[]", object : TypeToken<List<CustomRule>>() {}.type)
            } catch (e: Exception) { emptyList() }

            val updated = current.map {
                if (it.id == ruleId) it.copy(enabled = enabled) else it
            }
            prefs[Keys.CUSTOM_RULES] = gson.toJson(updated)
        }
    }

    // Heuristic Rule CRUD operations
    suspend fun addHeuristicRule(rule: HeuristicRule) {
        context.ruleDataStore.edit { prefs ->
            val current: List<HeuristicRule> = try {
                gson.fromJson(prefs[Keys.HEURISTIC_RULES] ?: "[]", object : TypeToken<List<HeuristicRule>>() {}.type)
            } catch (e: Exception) { emptyList() }

            prefs[Keys.HEURISTIC_RULES] = gson.toJson(current + rule)
        }
    }

    suspend fun updateHeuristicRule(rule: HeuristicRule) {
        context.ruleDataStore.edit { prefs ->
            val current: List<HeuristicRule> = try {
                gson.fromJson(prefs[Keys.HEURISTIC_RULES] ?: "[]", object : TypeToken<List<HeuristicRule>>() {}.type)
            } catch (e: Exception) { emptyList() }

            val updated = current.map { if (it.id == rule.id) rule else it }
            prefs[Keys.HEURISTIC_RULES] = gson.toJson(updated)
        }
    }

    suspend fun deleteHeuristicRule(ruleId: String) {
        context.ruleDataStore.edit { prefs ->
            val current: List<HeuristicRule> = try {
                gson.fromJson(prefs[Keys.HEURISTIC_RULES] ?: "[]", object : TypeToken<List<HeuristicRule>>() {}.type)
            } catch (e: Exception) { emptyList() }

            prefs[Keys.HEURISTIC_RULES] = gson.toJson(current.filter { it.id != ruleId })
        }
    }

    suspend fun toggleHeuristicRule(ruleId: String, enabled: Boolean) {
        context.ruleDataStore.edit { prefs ->
            val current: List<HeuristicRule> = try {
                gson.fromJson(prefs[Keys.HEURISTIC_RULES] ?: "[]", object : TypeToken<List<HeuristicRule>>() {}.type)
            } catch (e: Exception) { emptyList() }

            val updated = current.map {
                if (it.id == ruleId) it.copy(enabled = enabled) else it
            }
            prefs[Keys.HEURISTIC_RULES] = gson.toJson(updated)
        }
    }
}

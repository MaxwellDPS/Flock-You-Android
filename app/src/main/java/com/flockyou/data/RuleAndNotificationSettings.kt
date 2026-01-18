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
)

enum class RuleType(val displayName: String) {
    SSID_REGEX("WiFi SSID"),
    BLE_NAME_REGEX("BLE Device Name"),
    MAC_PREFIX("MAC Prefix")
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
        val FLOCK_SAFETY_ENABLED = booleanPreferencesKey("category_flock_safety")
        val POLICE_TECH_ENABLED = booleanPreferencesKey("category_police_tech")
        val ACOUSTIC_SENSORS_ENABLED = booleanPreferencesKey("category_acoustic_sensors")
        val GENERIC_SURVEILLANCE_ENABLED = booleanPreferencesKey("category_generic")
    }
    
    data class RuleSettings(
        val disabledBuiltinRules: Set<String> = emptySet(),
        val customRules: List<CustomRule> = emptyList(),
        val flockSafetyEnabled: Boolean = true,
        val policeTechEnabled: Boolean = true,
        val acousticSensorsEnabled: Boolean = true,
        val genericSurveillanceEnabled: Boolean = true
    )
    
    val settings: Flow<RuleSettings> = context.ruleDataStore.data.map { prefs ->
        val disabledRulesJson = prefs[Keys.DISABLED_BUILTIN_RULES] ?: "[]"
        val customRulesJson = prefs[Keys.CUSTOM_RULES] ?: "[]"
        
        val disabledRules: Set<String> = try {
            gson.fromJson(disabledRulesJson, object : TypeToken<Set<String>>() {}.type)
        } catch (e: Exception) { emptySet() }
        
        val customRules: List<CustomRule> = try {
            gson.fromJson(customRulesJson, object : TypeToken<List<CustomRule>>() {}.type)
        } catch (e: Exception) { emptyList() }
        
        RuleSettings(
            disabledBuiltinRules = disabledRules,
            customRules = customRules,
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
}

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

private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(name = "security_settings")

enum class LockMethod {
    NONE,
    PIN,
    BIOMETRIC,
    PIN_OR_BIOMETRIC
}

data class SecuritySettings(
    val appLockEnabled: Boolean = false,
    val lockMethod: LockMethod = LockMethod.NONE,
    val lockOnBackground: Boolean = true,
    val lockTimeoutSeconds: Int = 0,  // 0 = immediately, -1 = never (only on app restart)
    val requireAuthForSettings: Boolean = true
)

@Singleton
class SecuritySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val LOCK_METHOD = stringPreferencesKey("lock_method")
        val LOCK_ON_BACKGROUND = booleanPreferencesKey("lock_on_background")
        val LOCK_TIMEOUT_SECONDS = intPreferencesKey("lock_timeout_seconds")
        val REQUIRE_AUTH_FOR_SETTINGS = booleanPreferencesKey("require_auth_for_settings")
    }

    val settings: Flow<SecuritySettings> = context.securityDataStore.data.map { preferences ->
        SecuritySettings(
            appLockEnabled = preferences[PreferencesKeys.APP_LOCK_ENABLED] ?: false,
            lockMethod = try {
                LockMethod.valueOf(preferences[PreferencesKeys.LOCK_METHOD] ?: LockMethod.NONE.name)
            } catch (e: IllegalArgumentException) {
                LockMethod.NONE
            },
            lockOnBackground = preferences[PreferencesKeys.LOCK_ON_BACKGROUND] ?: true,
            lockTimeoutSeconds = preferences[PreferencesKeys.LOCK_TIMEOUT_SECONDS] ?: 0,
            requireAuthForSettings = preferences[PreferencesKeys.REQUIRE_AUTH_FOR_SETTINGS] ?: true
        )
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.securityDataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LOCK_ENABLED] = enabled
        }
    }

    suspend fun setLockMethod(method: LockMethod) {
        context.securityDataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCK_METHOD] = method.name
        }
    }

    suspend fun setLockOnBackground(enabled: Boolean) {
        context.securityDataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCK_ON_BACKGROUND] = enabled
        }
    }

    suspend fun setLockTimeoutSeconds(seconds: Int) {
        context.securityDataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCK_TIMEOUT_SECONDS] = seconds.coerceIn(-1, 300)
        }
    }

    suspend fun setRequireAuthForSettings(required: Boolean) {
        context.securityDataStore.edit { preferences ->
            preferences[PreferencesKeys.REQUIRE_AUTH_FOR_SETTINGS] = required
        }
    }

    suspend fun updateSettings(
        appLockEnabled: Boolean? = null,
        lockMethod: LockMethod? = null,
        lockOnBackground: Boolean? = null,
        lockTimeoutSeconds: Int? = null,
        requireAuthForSettings: Boolean? = null
    ) {
        context.securityDataStore.edit { preferences ->
            appLockEnabled?.let { preferences[PreferencesKeys.APP_LOCK_ENABLED] = it }
            lockMethod?.let { preferences[PreferencesKeys.LOCK_METHOD] = it.name }
            lockOnBackground?.let { preferences[PreferencesKeys.LOCK_ON_BACKGROUND] = it }
            lockTimeoutSeconds?.let { preferences[PreferencesKeys.LOCK_TIMEOUT_SECONDS] = it.coerceIn(-1, 300) }
            requireAuthForSettings?.let { preferences[PreferencesKeys.REQUIRE_AUTH_FOR_SETTINGS] = it }
        }
    }
}

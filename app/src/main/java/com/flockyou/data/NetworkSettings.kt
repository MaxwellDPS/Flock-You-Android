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

private val Context.networkDataStore: DataStore<Preferences> by preferencesDataStore(name = "network_settings")

data class NetworkSettings(
    val useTorProxy: Boolean = false,
    val torProxyHost: String = "127.0.0.1",
    val torProxyPort: Int = 9050
)

@Singleton
class NetworkSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val USE_TOR_PROXY = booleanPreferencesKey("use_tor_proxy")
        val TOR_PROXY_HOST = stringPreferencesKey("tor_proxy_host")
        val TOR_PROXY_PORT = intPreferencesKey("tor_proxy_port")
    }

    val settings: Flow<NetworkSettings> = context.networkDataStore.data.map { preferences ->
        NetworkSettings(
            useTorProxy = preferences[PreferencesKeys.USE_TOR_PROXY] ?: false,
            torProxyHost = preferences[PreferencesKeys.TOR_PROXY_HOST] ?: "127.0.0.1",
            torProxyPort = preferences[PreferencesKeys.TOR_PROXY_PORT] ?: 9050
        )
    }

    suspend fun setUseTorProxy(enabled: Boolean) {
        context.networkDataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_TOR_PROXY] = enabled
        }
    }

    suspend fun setTorProxyHost(host: String) {
        context.networkDataStore.edit { preferences ->
            preferences[PreferencesKeys.TOR_PROXY_HOST] = host
        }
    }

    suspend fun setTorProxyPort(port: Int) {
        context.networkDataStore.edit { preferences ->
            preferences[PreferencesKeys.TOR_PROXY_PORT] = port.coerceIn(1, 65535)
        }
    }

    suspend fun updateSettings(
        useTorProxy: Boolean? = null,
        torProxyHost: String? = null,
        torProxyPort: Int? = null
    ) {
        context.networkDataStore.edit { preferences ->
            useTorProxy?.let { preferences[PreferencesKeys.USE_TOR_PROXY] = it }
            torProxyHost?.let { preferences[PreferencesKeys.TOR_PROXY_HOST] = it }
            torProxyPort?.let { preferences[PreferencesKeys.TOR_PROXY_PORT] = it.coerceIn(1, 65535) }
        }
    }
}

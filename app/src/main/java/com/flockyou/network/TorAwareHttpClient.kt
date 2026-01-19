package com.flockyou.network

import android.util.Log
import com.flockyou.data.NetworkSettings
import com.flockyou.data.NetworkSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorAwareHttpClient @Inject constructor(
    private val networkSettingsRepository: NetworkSettingsRepository,
    private val orbotHelper: OrbotHelper
) {
    companion object {
        private const val TAG = "TorAwareHttpClient"
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 120L
        private const val WRITE_TIMEOUT_SECONDS = 30L
    }

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // Cache Tor clients by host:port to avoid recreating them
    private val torClientCache = mutableMapOf<String, OkHttpClient>()

    /**
     * DNS resolver that prevents DNS leaks when using Tor.
     * Returns a fake address - the SOCKS5 proxy will handle DNS resolution.
     */
    private object TorSafeDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // Return a placeholder - SOCKS5 proxy handles actual DNS
            // This prevents DNS queries from leaking outside Tor
            return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        }
    }

    private fun createTorClient(host: String, port: Int): OkHttpClient {
        val cacheKey = "$host:$port"
        return torClientCache.getOrPut(cacheKey) {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
            OkHttpClient.Builder()
                .proxy(proxy)
                .dns(TorSafeDns) // Prevent DNS leaks
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Get an OkHttpClient configured based on current network settings.
     * If Tor is enabled and Orbot is running, returns a SOCKS proxy client.
     * Otherwise returns a direct connection client.
     */
    suspend fun getClient(): OkHttpClient {
        val settings = networkSettingsRepository.settings.first()
        return getClientForSettings(settings)
    }

    /**
     * Get an OkHttpClient for specific settings.
     */
    suspend fun getClientForSettings(settings: NetworkSettings): OkHttpClient {
        if (!settings.useTorProxy) {
            Log.d(TAG, "Tor proxy disabled, using direct connection")
            return directClient
        }

        if (!orbotHelper.isOrbotInstalled()) {
            Log.w(TAG, "Tor enabled but Orbot not installed, falling back to direct connection")
            return directClient
        }

        val isRunning = orbotHelper.isOrbotRunning(settings.torProxyHost, settings.torProxyPort)
        if (!isRunning) {
            Log.w(TAG, "Tor enabled but Orbot not running on ${settings.torProxyHost}:${settings.torProxyPort}, falling back to direct connection")
            return directClient
        }

        Log.d(TAG, "Using Tor proxy at ${settings.torProxyHost}:${settings.torProxyPort}")
        return createTorClient(settings.torProxyHost, settings.torProxyPort)
    }

    /**
     * Synchronous version for use in contexts where suspend is not available.
     * Use sparingly - prefer the suspend version when possible.
     */
    fun getClientSync(): OkHttpClient {
        return runBlocking { getClient() }
    }

    /**
     * Check if Tor routing is currently active and available.
     */
    suspend fun isTorActive(): Boolean {
        val settings = networkSettingsRepository.settings.first()
        if (!settings.useTorProxy) return false
        if (!orbotHelper.isOrbotInstalled()) return false
        return orbotHelper.isOrbotRunning(settings.torProxyHost, settings.torProxyPort)
    }
}

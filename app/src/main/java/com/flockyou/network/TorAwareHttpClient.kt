package com.flockyou.network

import android.util.Log
import com.flockyou.data.NetworkSettings
import com.flockyou.data.NetworkSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a Tor connectivity test.
 */
data class TorConnectionStatus(
    val isConnected: Boolean,
    val isTor: Boolean,
    val exitIp: String?,
    val country: String?,
    val countryCode: String?,
    val error: String? = null
)

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

    /**
     * Test Tor connectivity by checking the exit IP via check.torproject.org.
     * Returns connection status including exit IP and country.
     */
    suspend fun testTorConnection(): TorConnectionStatus = withContext(Dispatchers.IO) {
        try {
            val settings = networkSettingsRepository.settings.first()

            if (!settings.useTorProxy) {
                return@withContext TorConnectionStatus(
                    isConnected = false,
                    isTor = false,
                    exitIp = null,
                    country = null,
                    countryCode = null,
                    error = "Tor proxy not enabled"
                )
            }

            if (!orbotHelper.isOrbotInstalled()) {
                return@withContext TorConnectionStatus(
                    isConnected = false,
                    isTor = false,
                    exitIp = null,
                    country = null,
                    countryCode = null,
                    error = "Orbot not installed"
                )
            }

            val isRunning = orbotHelper.isOrbotRunning(settings.torProxyHost, settings.torProxyPort)
            if (!isRunning) {
                return@withContext TorConnectionStatus(
                    isConnected = false,
                    isTor = false,
                    exitIp = null,
                    country = null,
                    countryCode = null,
                    error = "Orbot not running"
                )
            }

            val client = createTorClient(settings.torProxyHost, settings.torProxyPort)

            // Use Tor Project's check API
            val request = Request.Builder()
                .url("https://check.torproject.org/api/ip")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext TorConnectionStatus(
                    isConnected = false,
                    isTor = false,
                    exitIp = null,
                    country = null,
                    countryCode = null,
                    error = "HTTP ${response.code}"
                )
            }

            val body = response.body?.string() ?: return@withContext TorConnectionStatus(
                isConnected = false,
                isTor = false,
                exitIp = null,
                country = null,
                countryCode = null,
                error = "Empty response"
            )

            val json = JSONObject(body)
            val isTor = json.optBoolean("IsTor", false)
            val ip = if (json.has("IP")) json.getString("IP") else null

            // If connected via Tor, get geolocation info
            val (country, countryCode) = if (isTor && !ip.isNullOrEmpty()) {
                getIpCountry(client, ip)
            } else {
                Pair(null, null)
            }

            Log.d(TAG, "Tor connection test: isTor=$isTor, ip=$ip, country=$country")

            TorConnectionStatus(
                isConnected = true,
                isTor = isTor,
                exitIp = ip,
                country = country,
                countryCode = countryCode,
                error = if (!isTor) "Not using Tor network" else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tor connection test failed", e)
            TorConnectionStatus(
                isConnected = false,
                isTor = false,
                exitIp = null,
                country = null,
                countryCode = null,
                error = e.message ?: "Connection failed"
            )
        }
    }

    /**
     * Get country information for an IP address using ip-api.com.
     */
    private fun getIpCountry(client: OkHttpClient, ip: String): Pair<String?, String?> {
        return try {
            val request = Request.Builder()
                .url("http://ip-api.com/json/$ip?fields=country,countryCode")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    Pair(
                        if (json.has("country")) json.getString("country") else null,
                        if (json.has("countryCode")) json.getString("countryCode") else null
                    )
                } else {
                    Pair(null, null)
                }
            } else {
                Pair(null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get IP country: ${e.message}")
            Pair(null, null)
        }
    }
}

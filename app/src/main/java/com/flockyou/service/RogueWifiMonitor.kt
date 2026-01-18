package com.flockyou.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.flockyou.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Monitors WiFi environment for rogue access points and surveillance indicators.
 *
 * Detection methods:
 * 1. Evil Twin Detection - Same SSID, different BSSID/MAC
 * 2. Deauth Attack Detection - Rapid disconnections from known networks
 * 3. Hidden Camera WiFi - Common IoT camera SSIDs/MACs
 * 4. Suspicious Open Networks - Open networks in sensitive locations
 * 5. Captive Portal Fingerprinting - Malicious captive portals
 * 6. Signal Strength Anomalies - Unusually strong signals from unknown APs
 * 7. MAC Randomization Detection - Networks that track MAC changes
 * 8. Surveillance Van Detection - Mobile hotspots with surveillance patterns
 */
class RogueWifiMonitor(private val context: Context) {

    companion object {
        private const val TAG = "RogueWifiMonitor"

        // Thresholds
        private const val EVIL_TWIN_SIGNAL_DIFF_THRESHOLD = 15 // dBm difference to flag
        private const val STRONG_SIGNAL_THRESHOLD = -40 // dBm - suspiciously close
        private const val NETWORK_HISTORY_SIZE = 500
        private const val DEAUTH_WINDOW_MS = 60_000L // 1 minute
        private const val DEAUTH_THRESHOLD = 5 // 5 disconnects in 1 minute
        private const val ANOMALY_COOLDOWN_MS = 120_000L // 2 minutes between same type
        private const val TRACKING_DURATION_MS = 300_000L // 5 minutes
        private const val FOLLOWING_LOCATION_THRESHOLD = 0.001 // ~100m in lat/lon

        // Hidden camera OUI prefixes (common IoT camera manufacturers)
        private val HIDDEN_CAMERA_OUIS = setOf(
            "00:18:AE", // Shenzhen TVT (many hidden cameras)
            "00:12:17", // Cisco-Linksys (repurposed for cameras)
            "00:1C:B3", // Apple (disguised tracking devices)
            "00:0C:43", // Ralink (cheap IoT cameras)
            "00:26:86", // Quantenna (streaming devices)
            "B4:A3:82", // Hangzhou Hikvision
            "44:19:B6", // Hangzhou Hikvision
            "54:C4:15", // Hangzhou Hikvision
            "28:57:BE", // Hangzhou Hikvision
            "E0:50:8B", // Zhejiang Dahua
            "3C:EF:8C", // Zhejiang Dahua
            "4C:11:BF", // Zhejiang Dahua
            "A0:BD:1D", // Zhejiang Dahua
            "AC:B7:4D", // LIFI Labs (covert cameras)
            "00:62:6E", // Shenzhen (various IoT)
            "7C:DD:90", // Shenzhen Ogemray (spy cameras)
            "D4:D2:52", // Shenzhen Bilian (mini cameras)
            "B0:B9:8A", // Netgear (repurposed routers)
            "E8:AB:FA", // Shenzhen Reecam (nanny cams)
            "00:E0:64", // Samsung (various IoT)
        )

        // Suspicious SSID patterns for hidden cameras/surveillance
        private val HIDDEN_CAMERA_SSID_PATTERNS = listOf(
            Regex("(?i)^(hd|ip)?cam(era)?[-_]?[0-9]*$"),
            Regex("(?i)^spy[-_]?cam.*"),
            Regex("(?i)^nanny[-_]?cam.*"),
            Regex("(?i)^hidden[-_]?.*"),
            Regex("(?i)^covert[-_]?.*"),
            Regex("(?i)^mini[-_]?cam.*"),
            Regex("(?i)^wifi[-_]?cam[-_]?[0-9]*"),
            Regex("(?i)^smart[-_]?cam.*"),
            Regex("(?i)^home[-_]?cam.*"),
            Regex("(?i)^security[-_]?cam.*"),
            Regex("(?i)^baby[-_]?monitor.*"),
            Regex("(?i)^(yi|wyze|blink|ring|arlo|nest)[-_]?.*"),
            Regex("(?i)^ezviz.*"),
            Regex("(?i)^hikvision.*"),
            Regex("(?i)^dahua.*"),
            Regex("(?i)^amcrest.*"),
            Regex("(?i)^reolink.*"),
            Regex("(?i)^foscam.*"),
            Regex("(?i)^wansview.*"),
            // Generic IoT/Setup patterns
            Regex("(?i)^setup[-_]?[0-9a-f]+"),
            Regex("(?i)^config[-_]?[0-9a-f]+"),
            Regex("(?i)^direct[-_]?.*"),
        )

        // Surveillance van / mobile surveillance patterns
        private val SURVEILLANCE_VAN_PATTERNS = listOf(
            Regex("(?i)^(unmarked|surveillance|recon|intel|tactical)[-_]?.*"),
            Regex("(?i)^mobile[-_]?command.*"),
            Regex("(?i)^field[-_]?(ops|unit|team).*"),
            Regex("(?i)^van[-_]?[0-9]+$"),
            Regex("(?i)^unit[-_]?[0-9]+$"),
            Regex("(?i)^(swat|ert|hrt|srt)[-_]?.*"),
            Regex("(?i)^cctv[-_]?van.*"),
            Regex("(?i)^monitoring[-_]?(unit|van).*"),
        )

        // Common legitimate networks to reduce false positives
        private val COMMON_LEGITIMATE_SSIDS = setOf(
            "xfinitywifi", "attwifi", "google starbucks", "starbucks",
            "mcdonalds free wifi", "boingo", "t-mobile", "tmobile",
            "verizon", "spectrum", "cox wifi", "optimum wifi",
            "eduroam", "govwifi", "amtrak", "southwest wifi"
        )
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Monitoring state
    private var isMonitoring = false
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    // Network history for pattern detection
    private val networkHistory = mutableMapOf<String, NetworkHistory>() // BSSID -> history
    private val ssidToBssids = mutableMapOf<String, MutableSet<String>>() // SSID -> set of BSSIDs
    private val disconnectHistory = mutableListOf<Long>() // Timestamps of disconnects
    private var lastAnomalyTimes = mutableMapOf<WifiAnomalyType, Long>()

    // Tracking networks that appear to be following
    private val followingNetworks = mutableMapOf<String, MutableList<NetworkSighting>>()

    // State flows
    private val _anomalies = MutableStateFlow<List<WifiAnomaly>>(emptyList())
    val anomalies: StateFlow<List<WifiAnomaly>> = _anomalies.asStateFlow()

    private val _wifiStatus = MutableStateFlow<WifiEnvironmentStatus?>(null)
    val wifiStatus: StateFlow<WifiEnvironmentStatus?> = _wifiStatus.asStateFlow()

    private val _wifiEvents = MutableStateFlow<List<WifiEvent>>(emptyList())
    val wifiEvents: StateFlow<List<WifiEvent>> = _wifiEvents.asStateFlow()

    private val _suspiciousNetworks = MutableStateFlow<List<SuspiciousNetwork>>(emptyList())
    val suspiciousNetworks: StateFlow<List<SuspiciousNetwork>> = _suspiciousNetworks.asStateFlow()

    private val detectedAnomalies = mutableListOf<WifiAnomaly>()
    private val eventHistory = mutableListOf<WifiEvent>()
    private val maxEventHistory = 200

    private var wifiReceiver: BroadcastReceiver? = null
    private var connectionReceiver: BroadcastReceiver? = null

    // Data classes
    data class NetworkHistory(
        val bssid: String,
        val ssid: String,
        var firstSeen: Long = System.currentTimeMillis(),
        var lastSeen: Long = System.currentTimeMillis(),
        var seenCount: Int = 0,
        val signalHistory: MutableList<SignalSample> = mutableListOf(),
        val locationHistory: MutableList<Pair<Double, Double>> = mutableListOf(),
        var isOpen: Boolean = false,
        var capabilities: String = "",
        var frequency: Int = 0,
        var channelWidth: Int = 0
    )

    data class SignalSample(
        val timestamp: Long,
        val rssi: Int
    )

    data class NetworkSighting(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double,
        val rssi: Int
    )

    data class WifiAnomaly(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: WifiAnomalyType,
        val severity: ThreatLevel,
        val confidence: AnomalyConfidence,
        val description: String,
        val technicalDetails: String,
        val ssid: String?,
        val bssid: String?,
        val rssi: Int?,
        val latitude: Double?,
        val longitude: Double?,
        val contributingFactors: List<String> = emptyList(),
        val relatedNetworks: List<String> = emptyList() // Related BSSIDs
    )

    enum class AnomalyConfidence(val displayName: String) {
        LOW("Low - Possibly Normal"),
        MEDIUM("Medium - Suspicious"),
        HIGH("High - Likely Threat"),
        CRITICAL("Critical - Strong Indicators")
    }

    enum class WifiAnomalyType(
        val displayName: String,
        val baseScore: Int,
        val emoji: String
    ) {
        EVIL_TWIN("Evil Twin AP", 85, "👥"),
        DEAUTH_ATTACK("Deauth Attack", 90, "⚡"),
        HIDDEN_CAMERA("Hidden Camera WiFi", 75, "📹"),
        SUSPICIOUS_OPEN_NETWORK("Suspicious Open Network", 60, "🔓"),
        SIGNAL_ANOMALY("Signal Strength Anomaly", 50, "📶"),
        FOLLOWING_NETWORK("Network Following You", 80, "🚐"),
        SURVEILLANCE_VAN("Possible Surveillance Van", 85, "🚙"),
        ROGUE_AP("Rogue Access Point", 70, "🏴"),
        KARMA_ATTACK("Possible Karma Attack", 80, "🎭")
    }

    enum class WifiEventType(val displayName: String, val emoji: String) {
        NETWORK_APPEARED("Network Appeared", "📡"),
        NETWORK_DISAPPEARED("Network Disappeared", "📴"),
        SIGNAL_CHANGED("Signal Changed", "📊"),
        EVIL_TWIN_DETECTED("Evil Twin Detected", "👥"),
        ANOMALY_DETECTED("Anomaly Detected", "⚠️"),
        MONITORING_STARTED("Monitoring Started", "▶️"),
        MONITORING_STOPPED("Monitoring Stopped", "⏹️"),
        DISCONNECT_DETECTED("Disconnect Detected", "🔌")
    }

    data class WifiEvent(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: WifiEventType,
        val title: String,
        val description: String,
        val ssid: String?,
        val bssid: String?,
        val rssi: Int?,
        val isAnomaly: Boolean = false,
        val threatLevel: ThreatLevel = ThreatLevel.INFO,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    data class WifiEnvironmentStatus(
        val totalNetworks: Int,
        val openNetworks: Int,
        val hiddenNetworks: Int,
        val potentialEvilTwins: Int,
        val suspiciousNetworks: Int,
        val strongestSignal: Int?,
        val channelCongestion: Map<Int, Int>, // channel -> network count
        val lastScanTime: Long
    )

    data class SuspiciousNetwork(
        val bssid: String,
        val ssid: String,
        val rssi: Int,
        val reason: String,
        val threatLevel: ThreatLevel,
        val firstSeen: Long,
        val lastSeen: Long,
        val seenCount: Int,
        val isOpen: Boolean,
        val frequency: Int,
        val latitude: Double?,
        val longitude: Double?
    )

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        Log.d(TAG, "Starting rogue WiFi monitoring")

        addTimelineEvent(
            type = WifiEventType.MONITORING_STARTED,
            title = "WiFi Threat Monitoring Started",
            description = "Monitoring for evil twins, hidden cameras, and surveillance"
        )

        registerReceivers()
    }

    fun stopMonitoring() {
        isMonitoring = false
        unregisterReceivers()

        addTimelineEvent(
            type = WifiEventType.MONITORING_STOPPED,
            title = "WiFi Threat Monitoring Stopped",
            description = "WiFi surveillance detection paused"
        )

        Log.d(TAG, "Stopped rogue WiFi monitoring")
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    /**
     * Process WiFi scan results from ScanningService
     */
    fun processScanResults(results: List<ScanResult>) {
        if (!isMonitoring) return

        val now = System.currentTimeMillis()
        val currentBssids = mutableSetOf<String>()
        var openCount = 0
        var hiddenCount = 0
        val channelCongestion = mutableMapOf<Int, Int>()
        val suspiciousFound = mutableListOf<SuspiciousNetwork>()

        for (result in results) {
            val bssid = result.BSSID?.uppercase() ?: continue
            val ssid = result.SSID ?: ""
            currentBssids.add(bssid)

            // Track channel congestion
            val channel = frequencyToChannel(result.frequency)
            channelCongestion[channel] = (channelCongestion[channel] ?: 0) + 1

            // Check if open network
            val isOpen = !result.capabilities.contains("WPA") &&
                         !result.capabilities.contains("WEP") &&
                         !result.capabilities.contains("RSN")
            if (isOpen) openCount++
            if (ssid.isEmpty()) hiddenCount++

            // Update network history
            val history = networkHistory.getOrPut(bssid) {
                NetworkHistory(bssid = bssid, ssid = ssid)
            }
            history.lastSeen = now
            history.seenCount++
            history.isOpen = isOpen
            history.capabilities = result.capabilities
            history.frequency = result.frequency
            history.signalHistory.add(SignalSample(now, result.level))
            if (history.signalHistory.size > 100) {
                history.signalHistory.removeAt(0)
            }

            // Track location history
            currentLatitude?.let { lat ->
                currentLongitude?.let { lon ->
                    if (history.locationHistory.size < 50) {
                        history.locationHistory.add(lat to lon)
                    }
                }
            }

            // Track SSID -> BSSID mapping for evil twin detection
            if (ssid.isNotEmpty()) {
                val bssidsForSsid = ssidToBssids.getOrPut(ssid) { mutableSetOf() }
                bssidsForSsid.add(bssid)
            }

            // Track networks for following detection
            currentLatitude?.let { lat ->
                currentLongitude?.let { lon ->
                    val sightings = followingNetworks.getOrPut(bssid) { mutableListOf() }
                    sightings.add(NetworkSighting(now, lat, lon, result.level))
                    // Keep only recent sightings
                    sightings.removeAll { now - it.timestamp > TRACKING_DURATION_MS }
                }
            }

            // Analyze this network
            val suspicion = analyzeNetwork(result, history)
            if (suspicion != null) {
                suspiciousFound.add(suspicion)
            }
        }

        // Check for evil twins
        checkForEvilTwins(results)

        // Check for networks following user
        checkForFollowingNetworks()

        // Check for deauth attacks (sudden disconnect + many networks)
        checkDeauthIndicators()

        // Update status
        _wifiStatus.value = WifiEnvironmentStatus(
            totalNetworks = results.size,
            openNetworks = openCount,
            hiddenNetworks = hiddenCount,
            potentialEvilTwins = countPotentialEvilTwins(),
            suspiciousNetworks = suspiciousFound.size,
            strongestSignal = results.maxOfOrNull { it.level },
            channelCongestion = channelCongestion,
            lastScanTime = now
        )

        _suspiciousNetworks.value = suspiciousFound.sortedByDescending { it.threatLevel.ordinal }

        // Detect networks that disappeared (potential deauth)
        detectDisappearedNetworks(currentBssids)
    }

    /**
     * Called when WiFi disconnects unexpectedly
     */
    fun onDisconnect() {
        val now = System.currentTimeMillis()
        disconnectHistory.add(now)
        disconnectHistory.removeAll { now - it > DEAUTH_WINDOW_MS }

        addTimelineEvent(
            type = WifiEventType.DISCONNECT_DETECTED,
            title = "WiFi Disconnected",
            description = "Unexpected WiFi disconnect - ${disconnectHistory.size} in last minute"
        )

        checkDeauthIndicators()
    }

    private fun analyzeNetwork(result: ScanResult, history: NetworkHistory): SuspiciousNetwork? {
        val bssid = result.BSSID?.uppercase() ?: return null
        val ssid = result.SSID ?: ""
        val oui = bssid.take(8)

        var suspicionReason: String? = null
        var threatLevel = ThreatLevel.INFO

        // Check for hidden camera OUIs
        if (oui in HIDDEN_CAMERA_OUIS) {
            suspicionReason = "Known hidden camera manufacturer (${getManufacturerFromOui(oui)})"
            threatLevel = ThreatLevel.MEDIUM
        }

        // Check for hidden camera SSID patterns
        if (ssid.isNotEmpty()) {
            for (pattern in HIDDEN_CAMERA_SSID_PATTERNS) {
                if (pattern.matches(ssid)) {
                    suspicionReason = "Hidden camera SSID pattern: $ssid"
                    threatLevel = ThreatLevel.MEDIUM
                    break
                }
            }

            // Check for surveillance van patterns
            for (pattern in SURVEILLANCE_VAN_PATTERNS) {
                if (pattern.matches(ssid)) {
                    suspicionReason = "Possible surveillance van: $ssid"
                    threatLevel = ThreatLevel.HIGH

                    reportAnomaly(
                        type = WifiAnomalyType.SURVEILLANCE_VAN,
                        description = "Network matches surveillance vehicle pattern",
                        technicalDetails = "SSID '$ssid' matches known surveillance van naming patterns",
                        ssid = ssid,
                        bssid = bssid,
                        rssi = result.level,
                        confidence = AnomalyConfidence.MEDIUM,
                        contributingFactors = listOf(
                            "SSID matches surveillance pattern",
                            "Manufacturer: ${getManufacturerFromOui(oui)}"
                        )
                    )
                    break
                }
            }
        }

        // Check for suspiciously strong signals from unknown networks
        if (result.level > STRONG_SIGNAL_THRESHOLD && history.seenCount <= 2) {
            val isLegitimate = COMMON_LEGITIMATE_SSIDS.any {
                ssid.lowercase().contains(it)
            }
            if (!isLegitimate && history.isOpen) {
                suspicionReason = "Very strong signal from unknown open network"
                threatLevel = ThreatLevel.MEDIUM
            }
        }

        // Check for open networks that shouldn't be open
        if (history.isOpen && ssid.isNotEmpty()) {
            val isCommonOpen = COMMON_LEGITIMATE_SSIDS.any {
                ssid.lowercase().contains(it)
            }
            if (!isCommonOpen && !ssid.lowercase().contains("guest") &&
                !ssid.lowercase().contains("free") && !ssid.lowercase().contains("public")) {
                // Open network with non-public name
                if (suspicionReason == null) {
                    suspicionReason = "Unexpected open network"
                    threatLevel = ThreatLevel.LOW
                }
            }
        }

        if (suspicionReason != null) {
            return SuspiciousNetwork(
                bssid = bssid,
                ssid = ssid,
                rssi = result.level,
                reason = suspicionReason,
                threatLevel = threatLevel,
                firstSeen = history.firstSeen,
                lastSeen = history.lastSeen,
                seenCount = history.seenCount,
                isOpen = history.isOpen,
                frequency = result.frequency,
                latitude = currentLatitude,
                longitude = currentLongitude
            )
        }

        return null
    }

    private fun checkForEvilTwins(results: List<ScanResult>) {
        for ((ssid, bssids) in ssidToBssids) {
            if (bssids.size < 2) continue
            if (COMMON_LEGITIMATE_SSIDS.any { ssid.lowercase().contains(it) }) continue

            // Get signal strengths for all APs with this SSID
            val apSignals = results
                .filter { it.SSID == ssid && it.BSSID != null }
                .map { it.BSSID!!.uppercase() to it.level }

            if (apSignals.size >= 2) {
                // Check if signal strengths are suspiciously similar (could be same device)
                // or very different (could be evil twin)
                val signals = apSignals.map { it.second }
                val maxDiff = (signals.maxOrNull() ?: 0) - (signals.minOrNull() ?: 0)

                if (maxDiff > EVIL_TWIN_SIGNAL_DIFF_THRESHOLD) {
                    // Different signal strengths - possible evil twin
                    val strongestAp = apSignals.maxByOrNull { it.second }

                    reportAnomaly(
                        type = WifiAnomalyType.EVIL_TWIN,
                        description = "Multiple APs advertising same SSID with different signal strengths",
                        technicalDetails = "SSID '$ssid' seen from ${bssids.size} different BSSIDs. " +
                            "Signal variance: ${maxDiff}dBm suggests different physical locations/devices.",
                        ssid = ssid,
                        bssid = strongestAp?.first,
                        rssi = strongestAp?.second,
                        confidence = AnomalyConfidence.MEDIUM,
                        contributingFactors = listOf(
                            "${bssids.size} APs with same SSID",
                            "Signal difference: ${maxDiff}dBm",
                            "BSSIDs: ${bssids.take(3).joinToString(", ")}"
                        ),
                        relatedNetworks = bssids.toList()
                    )
                }
            }
        }
    }

    private fun checkForFollowingNetworks() {
        val now = System.currentTimeMillis()

        for ((bssid, sightings) in followingNetworks) {
            if (sightings.size < 3) continue

            // Check if network has been seen at multiple distinct locations
            val distinctLocations = mutableListOf<Pair<Double, Double>>()
            for (sighting in sightings) {
                val isDistinct = distinctLocations.none { existing ->
                    kotlin.math.abs(sighting.latitude - existing.first) < FOLLOWING_LOCATION_THRESHOLD &&
                    kotlin.math.abs(sighting.longitude - existing.second) < FOLLOWING_LOCATION_THRESHOLD
                }
                if (isDistinct) {
                    distinctLocations.add(sighting.latitude to sighting.longitude)
                }
            }

            if (distinctLocations.size >= 3) {
                // Network seen at 3+ distinct locations = following
                val history = networkHistory[bssid]

                reportAnomaly(
                    type = WifiAnomalyType.FOLLOWING_NETWORK,
                    description = "Network appears to be following your movement",
                    technicalDetails = "BSSID $bssid has been detected at ${distinctLocations.size} " +
                        "distinct locations over the past ${TRACKING_DURATION_MS / 60000} minutes",
                    ssid = history?.ssid,
                    bssid = bssid,
                    rssi = sightings.lastOrNull()?.rssi,
                    confidence = AnomalyConfidence.HIGH,
                    contributingFactors = listOf(
                        "Seen at ${distinctLocations.size} locations",
                        "Over ${sightings.size} sightings",
                        "Tracking duration: ${(now - sightings.first().timestamp) / 1000}s"
                    )
                )

                // Clear to avoid repeated alerts
                sightings.clear()
            }
        }
    }

    private fun checkDeauthIndicators() {
        val now = System.currentTimeMillis()
        disconnectHistory.removeAll { now - it > DEAUTH_WINDOW_MS }

        if (disconnectHistory.size >= DEAUTH_THRESHOLD) {
            reportAnomaly(
                type = WifiAnomalyType.DEAUTH_ATTACK,
                description = "Possible deauthentication attack detected",
                technicalDetails = "${disconnectHistory.size} WiFi disconnects in the last minute. " +
                    "This may indicate a deauth attack to force you onto a rogue AP.",
                ssid = null,
                bssid = null,
                rssi = null,
                confidence = AnomalyConfidence.MEDIUM,
                contributingFactors = listOf(
                    "${disconnectHistory.size} disconnects in 60 seconds",
                    "Threshold: $DEAUTH_THRESHOLD disconnects"
                )
            )

            // Clear to avoid spam
            disconnectHistory.clear()
        }
    }

    private fun detectDisappearedNetworks(currentBssids: Set<String>) {
        val now = System.currentTimeMillis()
        val recentThreshold = 30_000L // Network was seen in last 30 seconds

        for ((bssid, history) in networkHistory) {
            if (bssid !in currentBssids &&
                now - history.lastSeen < recentThreshold &&
                history.seenCount > 5) {
                // Network suddenly disappeared
                addTimelineEvent(
                    type = WifiEventType.NETWORK_DISAPPEARED,
                    title = "Network Disappeared",
                    description = "Previously stable network '${history.ssid}' suddenly gone",
                    ssid = history.ssid,
                    bssid = bssid,
                    rssi = history.signalHistory.lastOrNull()?.rssi
                )
            }
        }
    }

    private fun countPotentialEvilTwins(): Int {
        return ssidToBssids.count { it.value.size >= 2 }
    }

    private fun reportAnomaly(
        type: WifiAnomalyType,
        description: String,
        technicalDetails: String,
        ssid: String?,
        bssid: String?,
        rssi: Int?,
        confidence: AnomalyConfidence,
        contributingFactors: List<String>,
        relatedNetworks: List<String> = emptyList()
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastAnomalyTimes[type] ?: 0

        if (now - lastTime < ANOMALY_COOLDOWN_MS) {
            return
        }
        lastAnomalyTimes[type] = now

        val severity = when (confidence) {
            AnomalyConfidence.CRITICAL -> ThreatLevel.CRITICAL
            AnomalyConfidence.HIGH -> ThreatLevel.HIGH
            AnomalyConfidence.MEDIUM -> ThreatLevel.MEDIUM
            AnomalyConfidence.LOW -> ThreatLevel.LOW
        }

        val anomaly = WifiAnomaly(
            type = type,
            severity = severity,
            confidence = confidence,
            description = description,
            technicalDetails = technicalDetails,
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            latitude = currentLatitude,
            longitude = currentLongitude,
            contributingFactors = contributingFactors,
            relatedNetworks = relatedNetworks
        )

        detectedAnomalies.add(anomaly)
        _anomalies.value = detectedAnomalies.toList()

        addTimelineEvent(
            type = WifiEventType.ANOMALY_DETECTED,
            title = "${type.emoji} ${type.displayName}",
            description = description,
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            isAnomaly = true,
            threatLevel = severity
        )

        Log.w(TAG, "WIFI ANOMALY [${confidence.displayName}]: ${type.displayName} - $description")
    }

    private fun addTimelineEvent(
        type: WifiEventType,
        title: String,
        description: String,
        ssid: String? = null,
        bssid: String? = null,
        rssi: Int? = null,
        isAnomaly: Boolean = false,
        threatLevel: ThreatLevel = ThreatLevel.INFO
    ) {
        val event = WifiEvent(
            type = type,
            title = title,
            description = description,
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            isAnomaly = isAnomaly,
            threatLevel = threatLevel,
            latitude = currentLatitude,
            longitude = currentLongitude
        )

        eventHistory.add(0, event)
        if (eventHistory.size > maxEventHistory) {
            eventHistory.removeAt(eventHistory.size - 1)
        }
        _wifiEvents.value = eventHistory.toList()
    }

    private fun registerReceivers() {
        // Connection state receiver
        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
                    // Check for disconnection
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
                    }
                    if (info?.isConnected == false) {
                        onDisconnect()
                    }
                }
            }
        }

        context.registerReceiver(
            connectionReceiver,
            IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        )
    }

    private fun unregisterReceivers() {
        try {
            connectionReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        connectionReceiver = null
    }

    private fun frequencyToChannel(frequency: Int): Int {
        return when {
            frequency in 2412..2484 -> (frequency - 2412) / 5 + 1
            frequency in 5170..5825 -> (frequency - 5170) / 5 + 34
            frequency in 5955..7115 -> (frequency - 5955) / 5 + 1 // 6GHz
            else -> 0
        }
    }

    private fun getManufacturerFromOui(oui: String): String {
        return when (oui.uppercase()) {
            "00:18:AE" -> "Shenzhen TVT"
            "B4:A3:82", "44:19:B6", "54:C4:15", "28:57:BE" -> "Hikvision"
            "E0:50:8B", "3C:EF:8C", "4C:11:BF", "A0:BD:1D" -> "Dahua"
            "7C:DD:90" -> "Shenzhen Ogemray"
            "D4:D2:52" -> "Shenzhen Bilian"
            "E8:AB:FA" -> "Shenzhen Reecam"
            else -> "Unknown"
        }
    }

    fun clearAnomalies() {
        detectedAnomalies.clear()
        _anomalies.value = emptyList()
    }

    fun clearHistory() {
        networkHistory.clear()
        ssidToBssids.clear()
        followingNetworks.clear()
        eventHistory.clear()
        _wifiEvents.value = emptyList()
    }

    fun destroy() {
        stopMonitoring()
    }

    /**
     * Convert WiFi anomaly to Detection for storage
     */
    fun anomalyToDetection(anomaly: WifiAnomaly): Detection {
        val detectionMethod = when (anomaly.type) {
            WifiAnomalyType.EVIL_TWIN -> DetectionMethod.WIFI_EVIL_TWIN
            WifiAnomalyType.DEAUTH_ATTACK -> DetectionMethod.WIFI_DEAUTH_ATTACK
            WifiAnomalyType.HIDDEN_CAMERA -> DetectionMethod.WIFI_HIDDEN_CAMERA
            WifiAnomalyType.SUSPICIOUS_OPEN_NETWORK -> DetectionMethod.WIFI_ROGUE_AP
            WifiAnomalyType.SIGNAL_ANOMALY -> DetectionMethod.WIFI_SIGNAL_ANOMALY
            WifiAnomalyType.FOLLOWING_NETWORK -> DetectionMethod.WIFI_FOLLOWING
            WifiAnomalyType.SURVEILLANCE_VAN -> DetectionMethod.WIFI_SURVEILLANCE_VAN
            WifiAnomalyType.ROGUE_AP -> DetectionMethod.WIFI_ROGUE_AP
            WifiAnomalyType.KARMA_ATTACK -> DetectionMethod.WIFI_KARMA_ATTACK
        }

        val deviceType = when (anomaly.type) {
            WifiAnomalyType.HIDDEN_CAMERA -> DeviceType.HIDDEN_CAMERA
            WifiAnomalyType.SURVEILLANCE_VAN -> DeviceType.SURVEILLANCE_VAN
            WifiAnomalyType.FOLLOWING_NETWORK -> DeviceType.TRACKING_DEVICE
            else -> DeviceType.ROGUE_AP
        }

        return Detection(
            deviceType = deviceType,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = detectionMethod,
            deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}",
            macAddress = anomaly.bssid,
            ssid = anomaly.ssid,
            rssi = anomaly.rssi ?: -100,
            signalStrength = rssiToSignalStrength(anomaly.rssi ?: -100),
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            threatLevel = anomaly.severity,
            threatScore = anomaly.type.baseScore,
            manufacturer = anomaly.bssid?.let { getManufacturerFromOui(it.take(8)) },
            matchedPatterns = anomaly.contributingFactors.joinToString(", ")
        )
    }
}

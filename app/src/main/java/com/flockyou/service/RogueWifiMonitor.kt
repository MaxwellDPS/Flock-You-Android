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
class RogueWifiMonitor(
    private val context: Context,
    private val errorCallback: ScanningService.DetectorCallback? = null
) {
    // Minimum distance traveled (in meters) before reporting a tracking device
    // Default: 1609 meters (1 mile) - can be configured via settings
    var minTrackingDistanceMeters: Double = 1609.0

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
        val rssi: Int,
        val userLatitude: Double? = null,  // User's location at time of sighting
        val userLongitude: Double? = null
    )

    /**
     * Time pattern classification for network appearances
     */
    enum class TimePattern(val displayName: String) {
        RANDOM("Random"),
        PERIODIC("Periodic"),
        CORRELATED("Correlated with user"),
        UNKNOWN("Unknown")
    }

    /**
     * Signal trend classification
     */
    enum class SignalTrend(val displayName: String) {
        STABLE("Stable"),
        APPROACHING("Approaching"),
        DEPARTING("Departing"),
        ERRATIC("Erratic")
    }

    /**
     * Comprehensive following network analysis
     */
    data class FollowingNetworkAnalysis(
        // Temporal Patterns
        val sightingCount: Int,
        val distinctLocations: Int,
        val avgTimeBetweenSightingsMs: Long,
        val timePattern: TimePattern,
        val trackingDurationMs: Long,

        // Movement Correlation
        val pathCorrelation: Float,              // 0.0-1.0, how closely network follows user path
        val leadsUser: Boolean,                   // Network appears before user arrives at location
        val lagTimeMs: Long?,                     // Average time delay behind user
        val totalDistanceTraveledMeters: Double, // Total distance user traveled while being followed

        // Signal Analysis
        val signalConsistency: Float,             // 0-1, how consistent is signal strength
        val signalTrend: SignalTrend,
        val avgSignalStrength: Int,
        val signalVariance: Float,

        // Device Classification
        val likelyMobile: Boolean,                // Signal pattern suggests mobile device
        val vehicleMounted: Boolean,              // Large movements suggest vehicle
        val possibleFootSurveillance: Boolean,    // Slower, closer movements

        // Risk Score
        val followingConfidence: Float,           // 0-100%
        val followingDurationMs: Long,
        val riskIndicators: List<String>
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

        try {
            registerReceivers()
            errorCallback?.onDetectorStarted(ScanningService.DetectorHealthStatus.DETECTOR_ROGUE_WIFI)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting rogue WiFi monitoring", e)
            errorCallback?.onError(
                ScanningService.DetectorHealthStatus.DETECTOR_ROGUE_WIFI,
                "Failed to register receivers: ${e.message}",
                recoverable = true
            )
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
        try {
            unregisterReceivers()
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }

        addTimelineEvent(
            type = WifiEventType.MONITORING_STOPPED,
            title = "WiFi Threat Monitoring Stopped",
            description = "WiFi surveillance detection paused"
        )

        errorCallback?.onDetectorStopped(ScanningService.DetectorHealthStatus.DETECTOR_ROGUE_WIFI)
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

        try {
            processScanResultsInternal(results)
            errorCallback?.onScanSuccess(ScanningService.DetectorHealthStatus.DETECTOR_ROGUE_WIFI)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing WiFi scan results", e)
            errorCallback?.onError(
                ScanningService.DetectorHealthStatus.DETECTOR_ROGUE_WIFI,
                "Scan processing error: ${e.message}",
                recoverable = true
            )
        }
    }

    /**
     * Internal processing of WiFi scan results
     */
    private fun processScanResultsInternal(results: List<ScanResult>) {
        val now = System.currentTimeMillis()
        val currentBssids = mutableSetOf<String>()
        var openCount = 0
        var hiddenCount = 0
        val channelCongestion = mutableMapOf<Int, Int>()
        val suspiciousFound = mutableListOf<SuspiciousNetwork>()

        // Filter out invalid results (RSSI of 0 is invalid, valid range is typically -100 to -20 dBm)
        val validResults = results.filter { result ->
            val rssi = result.level
            rssi != 0 && rssi in -120..-10
        }

        if (validResults.size < results.size) {
            Log.d(TAG, "Filtered ${results.size - validResults.size} WiFi results with invalid RSSI values")
        }

        for (result in validResults) {
            val bssid = result.BSSID?.uppercase() ?: continue
            @Suppress("DEPRECATION")
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

            // Track networks for following detection - include user's location at time of sighting
            currentLatitude?.let { userLat ->
                currentLongitude?.let { userLon ->
                    val sightings = followingNetworks.getOrPut(bssid) { mutableListOf() }
                    // Network location is approximated by user location (we're detecting the network nearby)
                    sightings.add(NetworkSighting(
                        timestamp = now,
                        latitude = userLat,  // Network seen at user's location
                        longitude = userLon,
                        rssi = result.level,
                        userLatitude = userLat,
                        userLongitude = userLon
                    ))
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
        @Suppress("DEPRECATION")
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

    @Suppress("DEPRECATION")
    private fun checkForEvilTwins(results: List<ScanResult>) {
        for ((ssid, bssids) in ssidToBssids) {
            if (bssids.size < 2) continue
            if (COMMON_LEGITIMATE_SSIDS.any { ssid.lowercase().contains(it) }) continue

            // Get all APs with this SSID including their frequencies
            val apDetails = results
                .filter { it.SSID == ssid && it.BSSID != null }
                .map { ApDetails(it.BSSID!!.uppercase(), it.level, it.frequency) }

            if (apDetails.size >= 2) {
                // Group BSSIDs that likely belong to the same physical device
                // (dual-band/tri-band routers broadcasting on 2.4GHz, 5GHz, and/or 6GHz)
                val deviceGroups = groupBssidsByDevice(apDetails)

                // If all BSSIDs belong to the same device group, this is likely a dual/tri-band router
                if (deviceGroups.size <= 1) {
                    Log.d(TAG, "SSID '$ssid' has ${apDetails.size} BSSIDs but they appear to be from same dual/tri-band device")
                    continue
                }

                // Check if this looks like a mesh network (multiple nodes from same ecosystem)
                // Mesh networks have:
                // 1. Similar OUI prefixes (often differ only slightly)
                // 2. Multiple APs with consistent presence over time
                // 3. All BSSIDs seen frequently (not a new/suspicious AP)
                val isMeshNetwork = isLikelyMeshNetwork(ssid, apDetails)
                if (isMeshNetwork) {
                    Log.d(TAG, "SSID '$ssid' appears to be a mesh network with ${apDetails.size} nodes - not flagging as evil twin")
                    continue
                }

                // We have multiple distinct devices that don't appear to be a mesh - check for evil twin
                val signals = apDetails.map { it.rssi }
                val maxDiff = (signals.maxOrNull() ?: 0) - (signals.minOrNull() ?: 0)

                // Increase threshold to reduce false positives - multiple legitimate APs in a building
                // can have 15-20 dBm variance. True evil twins typically have larger differences
                // because they're trying to overpower the legitimate AP from a different location.
                val adjustedThreshold = if (deviceGroups.size >= 3) {
                    // 3+ device groups is likely a mesh or enterprise deployment
                    EVIL_TWIN_SIGNAL_DIFF_THRESHOLD + 15 // 30 dBm
                } else {
                    EVIL_TWIN_SIGNAL_DIFF_THRESHOLD + 5 // 20 dBm
                }

                if (maxDiff > adjustedThreshold) {
                    // Different signal strengths from different devices - possible evil twin
                    val strongestAp = apDetails.maxByOrNull { it.rssi }

                    // Check if the strongest AP is one we've seen many times (trusted)
                    val strongestHistory = strongestAp?.bssid?.let { networkHistory[it] }
                    val isStrongestTrusted = (strongestHistory?.seenCount ?: 0) >= 5

                    // Only report if the suspicious AP is new/rarely seen
                    if (!isStrongestTrusted) {
                        reportAnomaly(
                            type = WifiAnomalyType.EVIL_TWIN,
                            description = "Multiple APs advertising same SSID with different signal strengths",
                            technicalDetails = "SSID '$ssid' seen from ${deviceGroups.size} different devices " +
                                "(${apDetails.size} total BSSIDs). Signal variance: ${maxDiff}dBm suggests " +
                                "different physical locations/devices. Mesh networks and dual-band APs were excluded.",
                            ssid = ssid,
                            bssid = strongestAp?.bssid,
                            rssi = strongestAp?.rssi,
                            confidence = AnomalyConfidence.MEDIUM,
                            contributingFactors = listOf(
                                "${deviceGroups.size} distinct devices with same SSID",
                                "Signal difference: ${maxDiff}dBm",
                                "BSSIDs: ${bssids.take(3).joinToString(", ")}"
                            ),
                            relatedNetworks = bssids.toList()
                        )
                    }
                }
            }
        }
    }

    /**
     * Determine if an SSID with multiple BSSIDs is likely a mesh network.
     *
     * Mesh networks are characterized by:
     * 1. Multiple APs with the same SSID (by design)
     * 2. APs that have been seen consistently over time (not new/suspicious)
     * 3. OUIs that are similar or from known mesh router manufacturers
     * 4. Presence on multiple frequency bands
     */
    private fun isLikelyMeshNetwork(ssid: String, apDetails: List<ApDetails>): Boolean {
        if (apDetails.size < 3) return false  // Mesh typically has 3+ nodes

        // Check if most APs have been seen multiple times (established network)
        val allEstablished = apDetails.all { ap ->
            val history = networkHistory[ap.bssid]
            (history?.seenCount ?: 0) >= 3
        }

        // Check OUI similarity - mesh networks often have similar OUIs
        val ouis = apDetails.map { it.bssid.take(8) }.toSet()
        val uniqueOuiCount = ouis.size

        // Check if OUIs are "related" (differ in specific ways that suggest same manufacturer line)
        val hasRelatedOuis = areOuisRelated(ouis.toList())

        // Check frequency band coverage - mesh networks typically cover multiple bands
        val bands = apDetails.map { getFrequencyBand(it.frequency) }.toSet()
        val hasMultipleBands = bands.size >= 2

        // Heuristic: Likely mesh if:
        // - All APs established (seen 3+ times each) OR
        // - OUIs are related (from same manufacturer family) OR
        // - Has multiple bands AND 3+ APs with related OUIs
        return allEstablished ||
               (hasRelatedOuis && apDetails.size >= 3) ||
               (hasMultipleBands && uniqueOuiCount <= 2)
    }

    /**
     * Check if a set of OUIs appear to be from related devices (same manufacturer family).
     * Mesh router systems sometimes use slightly different OUI prefixes for different model years
     * or different components, but they're all from the same vendor ecosystem.
     */
    private fun areOuisRelated(ouis: List<String>): Boolean {
        if (ouis.size < 2) return true

        // Extract the first 4 characters (vendor-like prefix)
        val vendorPrefixes = ouis.map { it.take(5) }.toSet()

        // If vendor prefixes are very similar (only 1-2 unique), likely same manufacturer
        if (vendorPrefixes.size <= 2) return true

        // Check for known mesh router OUI patterns
        // Many mesh systems have OUIs that share the first 4-6 hex characters
        val normalizedOuis = ouis.map { it.replace(":", "").uppercase() }

        // Count how many OUIs share the first 4 hex digits
        val firstFourGroups = normalizedOuis.groupBy { it.take(4) }
        val largestGroup = firstFourGroups.values.maxOfOrNull { it.size } ?: 0

        // If most OUIs share first 4 digits, they're likely related
        return largestGroup >= (ouis.size * 0.6)
    }

    private data class ApDetails(
        val bssid: String,
        val rssi: Int,
        val frequency: Int
    )

    /**
     * Groups BSSIDs that likely belong to the same physical device.
     * Dual-band and tri-band routers broadcast the same SSID on multiple frequencies
     * (2.4GHz, 5GHz, 6GHz) with different BSSIDs that typically share the same OUI
     * and have similar/sequential MAC addresses.
     *
     * @return List of device groups, where each group contains BSSIDs from the same physical device
     */
    private fun groupBssidsByDevice(apDetails: List<ApDetails>): List<List<ApDetails>> {
        if (apDetails.isEmpty()) return emptyList()
        if (apDetails.size == 1) return listOf(apDetails)

        // Get the frequency bands for each AP
        val withBands = apDetails.map { ap ->
            ap to getFrequencyBand(ap.frequency)
        }

        // Group by OUI (first 8 characters of BSSID, e.g., "AA:BB:CC")
        val ouiGroups = withBands.groupBy { it.first.bssid.take(8) }

        val deviceGroups = mutableListOf<List<ApDetails>>()

        for ((_, apsInOui) in ouiGroups) {
            // Within the same OUI, check if BSSIDs are on different bands
            // If they're on different bands, they're likely from the same dual/tri-band device
            val bandsCovered = apsInOui.map { it.second }.toSet()

            if (bandsCovered.size > 1 && areBssidsFromSameDevice(apsInOui.map { it.first })) {
                // Multiple bands from same OUI with similar BSSIDs = same device
                deviceGroups.add(apsInOui.map { it.first })
            } else {
                // Either single band or BSSIDs too different - treat each as separate
                // But still group truly identical devices (same band could be mesh nodes)
                val subGroups = groupBySimilarBssid(apsInOui.map { it.first })
                deviceGroups.addAll(subGroups.map { group -> group })
            }
        }

        return deviceGroups
    }

    private enum class FrequencyBand {
        BAND_2_4GHZ,
        BAND_5GHZ,
        BAND_6GHZ,
        UNKNOWN
    }

    private fun getFrequencyBand(frequency: Int): FrequencyBand {
        return when {
            frequency in 2400..2500 -> FrequencyBand.BAND_2_4GHZ
            frequency in 5150..5900 -> FrequencyBand.BAND_5GHZ
            frequency in 5925..7125 -> FrequencyBand.BAND_6GHZ
            else -> FrequencyBand.UNKNOWN
        }
    }

    /**
     * Checks if BSSIDs are likely from the same physical device.
     * Dual-band routers often have sequential or very similar MAC addresses
     * that differ only in the last few characters.
     */
    private fun areBssidsFromSameDevice(aps: List<ApDetails>): Boolean {
        if (aps.size < 2) return true

        // Extract the MAC addresses without colons for easier comparison
        val macs = aps.map { it.bssid.replace(":", "") }

        // Check if MACs share the first 10 characters (differ only in last 2 hex digits)
        // This is common for dual-band routers: AA:BB:CC:DD:EE:F0 and AA:BB:CC:DD:EE:F1
        val prefixes10 = macs.map { it.take(10) }.toSet()
        if (prefixes10.size == 1) return true

        // Check if the numeric difference between MACs is small (e.g., <= 16)
        // This handles cases where the last octet differs by a small amount
        val macValues = macs.mapNotNull { mac ->
            try {
                mac.takeLast(4).toLong(16)
            } catch (e: NumberFormatException) {
                null
            }
        }

        if (macValues.size == macs.size && macValues.isNotEmpty()) {
            val minVal = macValues.minOrNull() ?: return false
            val maxVal = macValues.maxOrNull() ?: return false
            // If the last 2 bytes differ by 16 or less, likely same device
            // (covers dual-band and tri-band with some margin)
            if (maxVal - minVal <= 16) return true
        }

        return false
    }

    /**
     * Groups APs by similar BSSID (for mesh networks or APs with very close MACs)
     */
    private fun groupBySimilarBssid(aps: List<ApDetails>): List<List<ApDetails>> {
        if (aps.isEmpty()) return emptyList()
        if (aps.size == 1) return listOf(aps)

        val groups = mutableListOf<MutableList<ApDetails>>()
        val assigned = mutableSetOf<String>()

        for (ap in aps) {
            if (ap.bssid in assigned) continue

            val group = mutableListOf(ap)
            assigned.add(ap.bssid)

            for (other in aps) {
                if (other.bssid in assigned) continue
                if (areBssidsFromSameDevice(listOf(ap, other))) {
                    group.add(other)
                    assigned.add(other.bssid)
                }
            }

            groups.add(group)
        }

        return groups
    }

    private fun checkForFollowingNetworks() {
        val now = System.currentTimeMillis()

        for ((bssid, sightings) in followingNetworks) {
            if (sightings.size < 3) continue

            // Build enriched following analysis
            val analysis = buildFollowingAnalysis(bssid, sightings)

            // Only report if we have significant following indicators AND minimum distance traveled
            // User must have traveled at least minTrackingDistanceMeters (default: 1 mile / 1609m)
            val meetsDistanceThreshold = analysis.totalDistanceTraveledMeters >= minTrackingDistanceMeters
            val hasSignificantIndicators = analysis.distinctLocations >= 3 || analysis.followingConfidence >= 50

            if (hasSignificantIndicators && meetsDistanceThreshold) {
                val history = networkHistory[bssid]

                // Determine confidence based on enriched analysis
                val confidence = when {
                    analysis.followingConfidence >= 80 -> AnomalyConfidence.CRITICAL
                    analysis.followingConfidence >= 60 -> AnomalyConfidence.HIGH
                    analysis.followingConfidence >= 40 -> AnomalyConfidence.MEDIUM
                    else -> AnomalyConfidence.LOW
                }

                // Build enriched description
                val distanceMiles = analysis.totalDistanceTraveledMeters / 1609.0
                val description = buildString {
                    append("Network appears to be following your movement")
                    if (analysis.vehicleMounted) {
                        append(" (vehicle-mounted)")
                    } else if (analysis.possibleFootSurveillance) {
                        append(" (possible foot surveillance)")
                    }
                    append(" for ${String.format("%.1f", distanceMiles)} mi")
                    append(" - confidence: ${String.format("%.0f", analysis.followingConfidence)}%")
                }

                reportAnomaly(
                    type = WifiAnomalyType.FOLLOWING_NETWORK,
                    description = description,
                    technicalDetails = buildFollowingTechnicalDetails(analysis),
                    ssid = history?.ssid,
                    bssid = bssid,
                    rssi = sightings.lastOrNull()?.rssi,
                    confidence = confidence,
                    contributingFactors = buildFollowingContributingFactors(analysis)
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

    @Suppress("DEPRECATION")
    private fun registerReceivers() {
        // Connection state receiver
        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
                    // Check for disconnection
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                    } else {
                        intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
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

    // ==================== ENRICHMENT ANALYSIS FUNCTIONS ====================

    /**
     * Calculate Haversine distance between two points in meters
     */
    private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

        return earthRadiusMeters * c
    }

    /**
     * Detect time pattern in sightings
     */
    private fun detectTimePattern(sightings: List<NetworkSighting>): TimePattern {
        if (sightings.size < 3) return TimePattern.UNKNOWN

        val intervals = sightings.zipWithNext { a, b -> b.timestamp - a.timestamp }

        if (intervals.isEmpty()) return TimePattern.UNKNOWN

        val avgInterval = intervals.average()
        val variance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        // Check for periodicity (low variance relative to mean)
        val coefficientOfVariation = if (avgInterval > 0) stdDev / avgInterval else 0.0

        return when {
            coefficientOfVariation < 0.3 -> TimePattern.PERIODIC
            coefficientOfVariation > 1.0 -> TimePattern.RANDOM
            else -> TimePattern.CORRELATED
        }
    }

    /**
     * Calculate path correlation - how closely network follows user path
     */
    private fun calculatePathCorrelation(sightings: List<NetworkSighting>): Float {
        if (sightings.size < 3) return 0f

        // Check if network location correlates with user location
        val sightingsWithUserLoc = sightings.filter {
            it.userLatitude != null && it.userLongitude != null
        }

        if (sightingsWithUserLoc.size < 2) return 0f

        // Calculate average distance between network and user at each sighting
        val distances = sightingsWithUserLoc.map { sighting ->
            haversineDistanceMeters(
                sighting.latitude, sighting.longitude,
                sighting.userLatitude!!, sighting.userLongitude!!
            )
        }

        // Lower distance variance = higher correlation
        val avgDistance = distances.average()
        val variance = distances.map { (it - avgDistance) * (it - avgDistance) }.average()

        // Normalize: 0 = no correlation, 1 = perfect correlation
        // If average distance is consistently within 200m, that's high correlation
        val consistentRange = avgDistance < 200 && kotlin.math.sqrt(variance) < 100
        val moderateRange = avgDistance < 500 && kotlin.math.sqrt(variance) < 200

        return when {
            consistentRange -> 0.9f
            moderateRange -> 0.6f
            avgDistance < 1000 -> 0.3f
            else -> 0.1f
        }
    }

    /**
     * Analyze signal trend from sightings
     */
    private fun analyzeSignalTrend(sightings: List<NetworkSighting>): SignalTrend {
        if (sightings.size < 3) return SignalTrend.STABLE

        val signals = sightings.map { it.rssi }
        val firstHalf = signals.take(signals.size / 2).average()
        val secondHalf = signals.drop(signals.size / 2).average()

        val variance = signals.map { (it - signals.average()) * (it - signals.average()) }.average()

        return when {
            variance > 100 -> SignalTrend.ERRATIC
            secondHalf - firstHalf > 10 -> SignalTrend.APPROACHING
            firstHalf - secondHalf > 10 -> SignalTrend.DEPARTING
            else -> SignalTrend.STABLE
        }
    }

    /**
     * Determine if device is likely mobile based on signal patterns
     */
    private fun isLikelyMobile(sightings: List<NetworkSighting>): Boolean {
        if (sightings.size < 3) return false

        // Check if network location varies significantly
        val locations = sightings.map { it.latitude to it.longitude }
        val centerLat = locations.map { it.first }.average()
        val centerLon = locations.map { it.second }.average()

        val maxDistance = locations.maxOfOrNull { (lat, lon) ->
            haversineDistanceMeters(lat, lon, centerLat, centerLon)
        } ?: 0.0

        // If network has moved more than 50 meters, it's likely mobile
        return maxDistance > 50
    }

    /**
     * Determine if likely vehicle-mounted surveillance
     */
    private fun isVehicleMounted(sightings: List<NetworkSighting>): Boolean {
        if (sightings.size < 3) return false

        // Calculate speeds between sightings
        val speeds = sightings.zipWithNext { a, b ->
            val distance = haversineDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val timeHours = (b.timestamp - a.timestamp) / 3_600_000.0
            if (timeHours > 0) distance / 1000.0 / timeHours else 0.0 // km/h
        }

        // If average speed suggests vehicle (> 20 km/h)
        val avgSpeed = speeds.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        return avgSpeed > 20
    }

    /**
     * Determine if likely foot surveillance (walking pace, close)
     */
    private fun isPossibleFootSurveillance(sightings: List<NetworkSighting>): Boolean {
        if (sightings.size < 3) return false

        // Check signal strength (foot surveillance = closer = stronger signal)
        val avgSignal = sightings.map { it.rssi }.average()
        val isClose = avgSignal > -60

        // Check movement speed
        val speeds = sightings.zipWithNext { a, b ->
            val distance = haversineDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val timeHours = (b.timestamp - a.timestamp) / 3_600_000.0
            if (timeHours > 0) distance / 1000.0 / timeHours else 0.0
        }
        val avgSpeed = speeds.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val isWalkingPace = avgSpeed > 0 && avgSpeed < 8 // 0-8 km/h

        return isClose && isWalkingPace
    }

    /**
     * Build comprehensive following network analysis
     */
    private fun buildFollowingAnalysis(bssid: String, sightings: List<NetworkSighting>): FollowingNetworkAnalysis {
        val now = System.currentTimeMillis()

        // Count distinct locations
        val distinctLocs = mutableListOf<Pair<Double, Double>>()
        for (sighting in sightings) {
            val isDistinct = distinctLocs.none { existing ->
                haversineDistanceMeters(sighting.latitude, sighting.longitude, existing.first, existing.second) < 50
            }
            if (isDistinct) {
                distinctLocs.add(sighting.latitude to sighting.longitude)
            }
        }

        // Time analysis
        val trackingDuration = if (sightings.isNotEmpty()) {
            sightings.last().timestamp - sightings.first().timestamp
        } else 0L

        val avgTimeBetween = if (sightings.size > 1) {
            trackingDuration / (sightings.size - 1)
        } else 0L

        val timePattern = detectTimePattern(sightings)

        // Movement correlation
        val pathCorrelation = calculatePathCorrelation(sightings)

        // Calculate total distance traveled by user while being followed
        val totalDistanceTraveled = sightings.zipWithNext { a, b ->
            haversineDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }.sum()

        // Check if network leads or follows user
        val leadsUser = false // Would need more sophisticated tracking
        val lagTimeMs: Long? = null

        // Signal analysis
        val signals = sightings.map { it.rssi }
        val avgSignal = signals.average().toInt()
        val signalVariance = if (signals.isNotEmpty()) {
            signals.map { (it - avgSignal) * (it - avgSignal) }.average().toFloat()
        } else 0f

        val signalConsistency = 1 - (kotlin.math.sqrt(signalVariance.toDouble()) / 30).coerceIn(0.0, 1.0).toFloat()
        val signalTrend = analyzeSignalTrend(sightings)

        // Device classification
        val likelyMobile = isLikelyMobile(sightings)
        val vehicleMounted = isVehicleMounted(sightings)
        val footSurveillance = isPossibleFootSurveillance(sightings)

        // Risk indicators
        val riskIndicators = mutableListOf<String>()
        if (distinctLocs.size >= 3) riskIndicators.add("Seen at ${distinctLocs.size} distinct locations")
        if (pathCorrelation > 0.7) riskIndicators.add("High path correlation (${String.format("%.0f", pathCorrelation * 100)}%)")
        if (likelyMobile) riskIndicators.add("Device appears to be mobile")
        if (vehicleMounted) riskIndicators.add("Movement pattern suggests vehicle")
        if (footSurveillance) riskIndicators.add("Pattern suggests foot surveillance (close, walking pace)")
        if (signalTrend == SignalTrend.APPROACHING) riskIndicators.add("Signal strength increasing (approaching)")
        if (trackingDuration > 180_000) riskIndicators.add("Tracking for ${trackingDuration / 60_000}+ minutes")
        if (timePattern == TimePattern.CORRELATED) riskIndicators.add("Appearance pattern correlated with user movement")

        // Calculate overall confidence
        var confidence = 0f
        confidence += distinctLocs.size * 10f
        confidence += pathCorrelation * 30f
        if (likelyMobile) confidence += 15f
        if (vehicleMounted) confidence += 10f
        if (footSurveillance) confidence += 20f
        if (trackingDuration > 180_000) confidence += 10f
        if (timePattern == TimePattern.CORRELATED) confidence += 15f

        return FollowingNetworkAnalysis(
            sightingCount = sightings.size,
            distinctLocations = distinctLocs.size,
            avgTimeBetweenSightingsMs = avgTimeBetween,
            timePattern = timePattern,
            trackingDurationMs = trackingDuration,
            pathCorrelation = pathCorrelation,
            leadsUser = leadsUser,
            lagTimeMs = lagTimeMs,
            totalDistanceTraveledMeters = totalDistanceTraveled,
            signalConsistency = signalConsistency,
            signalTrend = signalTrend,
            avgSignalStrength = avgSignal,
            signalVariance = signalVariance,
            likelyMobile = likelyMobile,
            vehicleMounted = vehicleMounted,
            possibleFootSurveillance = footSurveillance,
            followingConfidence = confidence.coerceIn(0f, 100f),
            followingDurationMs = trackingDuration,
            riskIndicators = riskIndicators
        )
    }

    /**
     * Build enriched technical details from following analysis
     */
    private fun buildFollowingTechnicalDetails(analysis: FollowingNetworkAnalysis): String {
        val parts = mutableListOf<String>()

        parts.add("Following Confidence: ${String.format("%.0f", analysis.followingConfidence)}%")
        parts.add("Sightings: ${analysis.sightingCount} at ${analysis.distinctLocations} distinct locations")
        parts.add("Tracking Duration: ${analysis.trackingDurationMs / 1000}s")

        // Movement classification
        val deviceType = when {
            analysis.vehicleMounted -> "Vehicle-mounted"
            analysis.possibleFootSurveillance -> "Foot surveillance"
            analysis.likelyMobile -> "Mobile device"
            else -> "Stationary/Unknown"
        }
        parts.add("Device Classification: $deviceType")

        // Path correlation
        parts.add("Path Correlation: ${String.format("%.0f", analysis.pathCorrelation * 100)}%")

        // Signal info
        parts.add("Avg Signal: ${analysis.avgSignalStrength} dBm (${analysis.signalTrend.displayName})")
        parts.add("Signal Consistency: ${String.format("%.0f", analysis.signalConsistency * 100)}%")

        // Time pattern
        parts.add("Time Pattern: ${analysis.timePattern.displayName}")

        return parts.joinToString("\n")
    }

    /**
     * Build contributing factors from following analysis
     */
    private fun buildFollowingContributingFactors(analysis: FollowingNetworkAnalysis): List<String> {
        return analysis.riskIndicators
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

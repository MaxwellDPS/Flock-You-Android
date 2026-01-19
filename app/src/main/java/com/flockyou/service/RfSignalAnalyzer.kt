package com.flockyou.service

import android.content.Context
import android.net.wifi.ScanResult
import android.util.Log
import com.flockyou.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Analyzes RF signal environment for anomalies indicating surveillance or jamming.
 *
 * Detection methods:
 * 1. Jammer Detection - Sudden WiFi/BT signal dropouts
 * 2. Unusual 2.4GHz/5GHz Activity - Dense signal patterns suggesting surveillance
 * 3. Signal Fingerprinting - Matching known surveillance device RF signatures
 * 4. Spectrum Anomalies - Broadband interference patterns
 * 5. Drone Detection - Common drone WiFi patterns (DJI, etc.)
 * 6. Hidden Transmitter Detection - Continuous RF sources
 *
 * Note: Android has limited RF access. This uses WiFi/BLE scan data as a proxy
 * for RF environment analysis. True spectrum analysis requires SDR hardware.
 */
class RfSignalAnalyzer(
    private val context: Context,
    private val errorCallback: ScanningService.DetectorCallback? = null
) {
    // Setting to control hidden network RF anomaly detection (disabled by default)
    var enableHiddenNetworkRfAnomaly: Boolean = false

    companion object {
        private const val TAG = "RfSignalAnalyzer"

        // Thresholds - tuned to reduce false positives
        private const val JAMMER_DETECTION_WINDOW_MS = 30_000L // 30 seconds
        private const val NORMAL_NETWORK_FLOOR = 5 // Minimum networks for reliable baseline
        private const val SIGNAL_HISTORY_SIZE = 120 // ~2 minutes for stable baseline
        private const val JAMMER_SIGNAL_DROP_THRESHOLD = 25 // dBm drop required
        private const val ANOMALY_COOLDOWN_MS = 180_000L // 3 minutes between same alert type
        private const val DENSE_NETWORK_THRESHOLD = 40 // High network density threshold
        private const val DRONE_DETECTION_COOLDOWN_MS = 300_000L // 5 minutes

        // Minimum observations to reduce transient false positives
        private const val MIN_BASELINE_SAMPLES = 10 // Samples needed for reliable baseline
        private const val MIN_CONSECUTIVE_ANOMALOUS_READINGS = 3 // Consecutive readings before alert
        private const val MIN_DRONE_SIGHTINGS = 2 // See drone multiple times before alerting
        private const val SIGNAL_INTERFERENCE_THRESHOLD_DBM = 20 // Significant signal change
        private const val HIDDEN_NETWORK_SUSPICIOUS_RATIO = 0.4f // 40% hidden is suspicious
        private const val MIN_SURVEILLANCE_CAMERAS = 8 // Camera count for surveillance alert
        private const val JAMMER_NETWORK_DROP_RATIO = 0.33f // Networks must drop to 1/3 (was 1/2)

        // Drone WiFi patterns - tightened to reduce false positives
        // Only match patterns that are highly specific to drones
        private val DRONE_SSID_PATTERNS = listOf(
            Regex("(?i)^dji[-_].+"),              // DJI drones (require suffix)
            Regex("(?i)^phantom[-_]?[0-9].*"),    // DJI Phantom with model number
            Regex("(?i)^mavic[-_]?(pro|air|mini|[0-9]).*"), // DJI Mavic specific models
            Regex("(?i)^inspire[-_]?[0-9].*"),    // DJI Inspire with number
            Regex("(?i)^tello[-_]?[0-9a-f]+"),    // Ryze Tello with ID
            Regex("(?i)^parrot[-_]?(anafi|bebop|disco|mambo).*"), // Parrot specific models
            Regex("(?i)^anafi[-_]?.*"),           // Parrot Anafi
            Regex("(?i)^bebop[-_]?[0-9].*"),      // Parrot Bebop with number
            Regex("(?i)^skydio[-_]?[0-9].*"),     // Skydio with model
            Regex("(?i)^autel[-_]?(evo|robotics).*"), // Autel specific
            Regex("(?i)^evo[-_]?(ii|2|lite)[-_].*"), // Autel EVO (require more context)
            Regex("(?i)^yuneec[-_]?(typhoon|mantis|breeze).*"), // Yuneec specific models
            Regex("(?i)^typhoon[-_]?[hq4].*"),    // Yuneec Typhoon specific
            Regex("(?i)^hubsan[-_]?(zino|x4).*"), // Hubsan specific models
            Regex("(?i)^holy[-_]?stone[-_]?hs.*"),// Holy Stone with model prefix
            Regex("(?i)^potensic[-_]?[a-z][0-9].*"), // Potensic with model
            Regex("(?i)^snaptain[-_]?[a-z][0-9].*"), // Snaptain with model
            // Removed overly broad patterns: drone-*, fpv-*, quad-*, uav-*, mini-*, spark-*
            // These match too many non-drone devices
        )

        // DJI OUI prefixes (DJI drones use specific MAC ranges)
        private val DRONE_OUIS = setOf(
            "60:60:1F", // DJI
            "34:D2:62", // DJI
            "48:1C:B9", // DJI
            "60:C7:98", // DJI (Phantom/Mavic)
            "A0:14:3D", // Parrot
            "90:03:B7", // Parrot
            "00:12:1C", // Parrot
            "00:26:7E", // Parrot
            "A0:14:3D", // Parrot
        )

        // Surveillance area indicators (many cameras/IoT in one place)
        private val SURVEILLANCE_AREA_OUIS = setOf(
            // Security camera manufacturers
            "B4:A3:82", "44:19:B6", "54:C4:15", "28:57:BE", // Hikvision
            "E0:50:8B", "3C:EF:8C", "4C:11:BF", "A0:BD:1D", // Dahua
            "00:80:F0", // Panasonic
            "00:30:53", // Axis Communications
            "00:40:8C", // Axis Communications
            "AC:CC:8E", // Axis Communications
            "00:04:7D", // Pelco
            "9C:8E:CD", // Amcrest
            "9C:28:B3", // Vivotek
            "00:02:D1", // Vivotek
        )

        // Common frequencies and their uses (for reference/display)
        private val FREQUENCY_BANDS = mapOf(
            2400..2500 to "2.4 GHz WiFi/BT/IoT",
            5150..5350 to "5 GHz WiFi (UNII-1/2)",
            5470..5725 to "5 GHz WiFi (UNII-2C/3)",
            5725..5875 to "5.8 GHz ISM (Drones/FPV)",
            5955..7125 to "6 GHz WiFi 6E"
        )
    }

    // Monitoring state
    private var isMonitoring = false
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    // Signal history for trend analysis
    private val signalHistory = mutableListOf<RfSnapshot>()
    private var lastScanNetworkCount = 0
    private var lastScanTimestamp = 0L
    private var baselineNetworkCount: Int? = null
    private var baselineSignalStrength: Int? = null
    private var lastAnomalyTimes = mutableMapOf<RfAnomalyType, Long>()

    // Consecutive anomaly counters to reduce false positives
    private var consecutiveJammerReadings = 0
    private var consecutiveInterferenceReadings = 0
    private val pendingDroneSightings = mutableMapOf<String, Int>() // BSSID -> sighting count

    // Drone tracking
    private val detectedDrones = mutableMapOf<String, DroneInfo>()

    // Hidden network tracking for temporal analysis
    private val hiddenBssidHistory = mutableMapOf<String, MutableList<Long>>() // BSSID -> timestamps seen
    private var lastHiddenBssidSet = setOf<String>() // BSSIDs from previous scan
    private var hiddenNetworkFirstAppearance = mutableMapOf<String, Long>() // BSSID -> first seen timestamp

    // State flows
    private val _anomalies = MutableStateFlow<List<RfAnomaly>>(emptyList())
    val anomalies: StateFlow<List<RfAnomaly>> = _anomalies.asStateFlow()

    private val _rfStatus = MutableStateFlow<RfEnvironmentStatus?>(null)
    val rfStatus: StateFlow<RfEnvironmentStatus?> = _rfStatus.asStateFlow()

    private val _rfEvents = MutableStateFlow<List<RfEvent>>(emptyList())
    val rfEvents: StateFlow<List<RfEvent>> = _rfEvents.asStateFlow()

    private val _detectedDrones = MutableStateFlow<List<DroneInfo>>(emptyList())
    val dronesDetected: StateFlow<List<DroneInfo>> = _detectedDrones.asStateFlow()

    private val detectedAnomalies = mutableListOf<RfAnomaly>()
    private val eventHistory = mutableListOf<RfEvent>()
    private val maxEventHistory = 200

    // Data classes
    data class RfSnapshot(
        val timestamp: Long,
        val wifiNetworkCount: Int,
        val averageSignalStrength: Int,
        val strongestSignal: Int,
        val weakestSignal: Int,
        val channelDistribution: Map<Int, Int>,
        val band24Count: Int,
        val band5Count: Int,
        val band6Count: Int,
        val openNetworkCount: Int,
        val hiddenNetworkCount: Int,
        val droneNetworkCount: Int,
        val surveillanceCameraCount: Int,
        // Enhanced hidden network analysis fields
        val hiddenNetworkAnalysis: HiddenNetworkAnalysis? = null
    )

    /**
     * Detailed analysis of hidden WiFi networks for anomaly enrichment.
     */
    data class HiddenNetworkAnalysis(
        // Signal characteristics
        val hiddenAvgSignalStrength: Int,           // Average RSSI of hidden networks
        val visibleAvgSignalStrength: Int,          // Average RSSI of visible networks
        val hiddenSignalStrongerThanVisible: Boolean, // Hidden networks have stronger signals (suspicious)
        val hiddenSignalVariance: Float,            // Low variance = same hardware
        val signalClusterCount: Int,                // Number of signal strength clusters

        // Band distribution
        val hiddenBand24Count: Int,                 // Hidden networks on 2.4GHz
        val hiddenBand5Count: Int,                  // Hidden networks on 5GHz
        val hiddenBand6Count: Int,                  // Hidden networks on 6GHz
        val predominantBand: String,                // Most common band for hidden networks

        // Channel analysis
        val hiddenChannelDistribution: Map<Int, Int>, // Channels used by hidden networks
        val channelConcentration: Boolean,          // Hidden networks clustered on few channels

        // OUI/Manufacturer analysis
        val hiddenOuiDistribution: Map<String, Int>, // OUI prefix -> count
        val sharedOuiCount: Int,                    // Count of hidden networks sharing same OUI
        val knownSurveillanceOuiCount: Int,         // Hidden networks with surveillance vendor OUIs
        val uniqueOuiCount: Int,                    // Number of unique OUI prefixes

        // Temporal analysis (requires history)
        val persistentHiddenBssids: Int,            // BSSIDs seen in multiple scans
        val newHiddenBssidsThisScan: Int,           // BSSIDs not seen before
        val simultaneousAppearance: Boolean,        // Many hidden networks appeared at once

        // Correlation with other indicators
        val hiddenNearSurveillanceCameras: Int,     // Hidden networks with similar signal to cameras
        val openAndHiddenFromSameOui: Int           // Same vendor has both open and hidden networks
    )

    data class DroneInfo(
        val bssid: String,
        val ssid: String,
        val manufacturer: String,
        val firstSeen: Long,
        var lastSeen: Long,
        var rssi: Int,
        var seenCount: Int = 1,
        val latitude: Double?,
        val longitude: Double?,
        val estimatedDistance: String
    )

    data class RfAnomaly(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: RfAnomalyType,
        val severity: ThreatLevel,
        val confidence: AnomalyConfidence,
        val description: String,
        val technicalDetails: String,
        val latitude: Double?,
        val longitude: Double?,
        val contributingFactors: List<String> = emptyList()
    ) {
        /**
         * Returns true if this anomaly should only be shown to advanced users.
         * Low-confidence anomalies like signal interference and spectrum anomalies
         * are hidden by default to reduce noise for regular users.
         */
        val isAdvancedOnly: Boolean
            get() = confidence == AnomalyConfidence.LOW ||
                type == RfAnomalyType.SIGNAL_INTERFERENCE ||
                type == RfAnomalyType.SPECTRUM_ANOMALY ||
                type == RfAnomalyType.UNUSUAL_ACTIVITY

        /**
         * User-friendly display name that's more descriptive than the raw type.
         */
        val displayName: String
            get() = when (type) {
                RfAnomalyType.POSSIBLE_JAMMER -> "RF Jammer Detected"
                RfAnomalyType.DRONE_DETECTED -> "Drone Nearby"
                RfAnomalyType.SURVEILLANCE_AREA -> "Surveillance Zone"
                RfAnomalyType.SIGNAL_INTERFERENCE -> "RF Interference"
                RfAnomalyType.SPECTRUM_ANOMALY -> "RF Environment Change"
                RfAnomalyType.UNUSUAL_ACTIVITY -> "Unusual RF Pattern"
                RfAnomalyType.HIDDEN_TRANSMITTER -> "Hidden Transmitter"
            }
    }

    enum class AnomalyConfidence(val displayName: String) {
        LOW("Low - Possibly Normal"),
        MEDIUM("Medium - Suspicious"),
        HIGH("High - Likely Threat"),
        CRITICAL("Critical - Strong Indicators")
    }

    enum class RfAnomalyType(
        val displayName: String,
        val baseScore: Int,
        val emoji: String
    ) {
        POSSIBLE_JAMMER("Possible RF Jammer", 85, "📵"),
        SPECTRUM_ANOMALY("Spectrum Anomaly", 60, "📊"),
        DRONE_DETECTED("Drone Detected", 70, "🚁"),
        SURVEILLANCE_AREA("High Surveillance Area", 65, "📹"),
        UNUSUAL_ACTIVITY("Unusual RF Activity", 50, "📡"),
        SIGNAL_INTERFERENCE("Signal Interference", 55, "⚡"),
        HIDDEN_TRANSMITTER("Possible Hidden Transmitter", 75, "📻")
    }

    enum class RfEventType(val displayName: String, val emoji: String) {
        SCAN_COMPLETED("RF Scan", "📡"),
        JAMMER_SUSPECTED("Jammer Suspected", "📵"),
        DRONE_DETECTED("Drone Detected", "🚁"),
        ANOMALY_DETECTED("Anomaly Detected", "⚠️"),
        MONITORING_STARTED("Monitoring Started", "▶️"),
        MONITORING_STOPPED("Monitoring Stopped", "⏹️"),
        ENVIRONMENT_CHANGE("Environment Changed", "🔄")
    }

    data class RfEvent(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: RfEventType,
        val title: String,
        val description: String,
        val isAnomaly: Boolean = false,
        val threatLevel: ThreatLevel = ThreatLevel.INFO,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    data class RfEnvironmentStatus(
        val totalNetworks: Int,
        val band24GHz: Int,
        val band5GHz: Int,
        val band6GHz: Int,
        val averageSignalStrength: Int,
        val noiseLevel: NoiseLevel,
        val jammerSuspected: Boolean,
        val dronesDetected: Int,
        val surveillanceCameras: Int,
        val channelCongestion: ChannelCongestion,
        val lastScanTime: Long,
        val environmentRisk: EnvironmentRisk
    )

    enum class NoiseLevel(val displayName: String, val emoji: String) {
        LOW("Low", "🟢"),
        MODERATE("Moderate", "🟡"),
        HIGH("High", "🟠"),
        EXTREME("Extreme", "🔴")
    }

    enum class ChannelCongestion(val displayName: String) {
        CLEAR("Clear"),
        LIGHT("Light"),
        MODERATE("Moderate"),
        HEAVY("Heavy"),
        SEVERE("Severe")
    }

    enum class EnvironmentRisk(val displayName: String, val emoji: String) {
        LOW("Low Risk", "🟢"),
        MODERATE("Moderate Risk", "🟡"),
        ELEVATED("Elevated Risk", "🟠"),
        HIGH("High Risk", "🔴")
    }

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        Log.d(TAG, "Starting RF signal analysis")

        addTimelineEvent(
            type = RfEventType.MONITORING_STARTED,
            title = "RF Analysis Started",
            description = "Monitoring for jammers, drones, and RF anomalies"
        )

        errorCallback?.onDetectorStarted(ScanningService.DetectorHealthStatus.DETECTOR_RF_SIGNAL)
    }

    fun stopMonitoring() {
        isMonitoring = false

        addTimelineEvent(
            type = RfEventType.MONITORING_STOPPED,
            title = "RF Analysis Stopped",
            description = "RF surveillance detection paused"
        )

        errorCallback?.onDetectorStopped(ScanningService.DetectorHealthStatus.DETECTOR_RF_SIGNAL)
        Log.d(TAG, "Stopped RF signal analysis")
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    /**
     * Analyze WiFi scan results for RF anomalies
     */
    fun analyzeWifiScan(results: List<ScanResult>) {
        if (!isMonitoring) return

        try {
            analyzeWifiScanInternal(results)
            errorCallback?.onScanSuccess(ScanningService.DetectorHealthStatus.DETECTOR_RF_SIGNAL)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing RF environment", e)
            errorCallback?.onError(
                ScanningService.DetectorHealthStatus.DETECTOR_RF_SIGNAL,
                "Analysis error: ${e.message}",
                recoverable = true
            )
        }
    }

    /**
     * Internal WiFi scan analysis
     */
    private fun analyzeWifiScanInternal(results: List<ScanResult>) {
        val now = System.currentTimeMillis()

        // Build snapshot
        val snapshot = buildSnapshot(results, now)
        signalHistory.add(snapshot)
        if (signalHistory.size > SIGNAL_HISTORY_SIZE) {
            signalHistory.removeAt(0)
        }

        // Set baseline after collecting enough samples for reliability
        if (baselineNetworkCount == null && signalHistory.size >= MIN_BASELINE_SAMPLES) {
            baselineNetworkCount = signalHistory.map { it.wifiNetworkCount }.average().toInt()
            baselineSignalStrength = signalHistory.map { it.averageSignalStrength }.average().toInt()
            Log.d(TAG, "Baseline established: $baselineNetworkCount networks, ${baselineSignalStrength}dBm avg")
        }

        // Check for jammers
        checkForJammer(snapshot)

        // Check for drones
        checkForDrones(results)

        // Check for surveillance area
        checkForSurveillanceArea(results, snapshot)

        // Check for spectrum anomalies
        checkForSpectrumAnomalies(snapshot)

        // Update status
        updateStatus(snapshot)

        lastScanNetworkCount = results.size
        lastScanTimestamp = now
    }

    /**
     * Analyze BLE scan for RF patterns
     */
    @Suppress("UNUSED_PARAMETER")
    fun analyzeBleEnvironment(deviceCount: Int, averageRssi: Int) {
        // BLE can help detect jammer (if WiFi and BLE both drop simultaneously)
        // This is a supplementary signal
    }

    private fun buildSnapshot(results: List<ScanResult>, timestamp: Long): RfSnapshot {
        val signals = results.mapNotNull { it.level.takeIf { l -> l != 0 } }
        val avgSignal = if (signals.isNotEmpty()) signals.average().toInt() else -100

        val channelDist = mutableMapOf<Int, Int>()
        var band24 = 0
        var band5 = 0
        var band6 = 0
        var openCount = 0
        var hiddenCount = 0
        var droneCount = 0
        var cameraCount = 0

        // Enhanced tracking for hidden network analysis
        val hiddenNetworks = mutableListOf<HiddenNetworkData>()
        val visibleNetworks = mutableListOf<VisibleNetworkData>()
        val openNetworkOuis = mutableSetOf<String>()
        val cameraSignals = mutableListOf<Int>()

        for (result in results) {
            val freq = result.frequency
            val channel = frequencyToChannel(freq)
            channelDist[channel] = (channelDist[channel] ?: 0) + 1

            when {
                freq in 2400..2500 -> band24++
                freq in 5000..5900 -> band5++
                freq in 5925..7125 -> band6++
            }

            val capabilities = result.capabilities ?: ""
            val isOpen = !capabilities.contains("WPA") && !capabilities.contains("WEP") &&
                !capabilities.contains("RSN")
            if (isOpen) {
                openCount++
            }

            @Suppress("DEPRECATION")
            val ssid = result.SSID ?: ""
            val bssid = result.BSSID?.uppercase() ?: ""
            val oui = bssid.take(8)

            if (ssid.isEmpty()) {
                hiddenCount++
                hiddenNetworks.add(HiddenNetworkData(
                    bssid = bssid,
                    oui = oui,
                    rssi = result.level,
                    frequency = freq,
                    channel = channel
                ))
            } else {
                visibleNetworks.add(VisibleNetworkData(
                    bssid = bssid,
                    oui = oui,
                    rssi = result.level
                ))
            }

            // Track open network OUIs for correlation
            if (isOpen && oui.isNotEmpty()) {
                openNetworkOuis.add(oui)
            }

            // Check if drone
            if (oui in DRONE_OUIS || DRONE_SSID_PATTERNS.any { it.matches(ssid) }) {
                droneCount++
            }

            // Check if surveillance camera
            if (oui in SURVEILLANCE_AREA_OUIS) {
                cameraCount++
                cameraSignals.add(result.level)
            }
        }

        // Build enhanced hidden network analysis if we have hidden networks
        val hiddenAnalysis = if (hiddenNetworks.isNotEmpty()) {
            buildHiddenNetworkAnalysis(
                hiddenNetworks = hiddenNetworks,
                visibleNetworks = visibleNetworks,
                openNetworkOuis = openNetworkOuis,
                cameraSignals = cameraSignals,
                timestamp = timestamp
            )
        } else null

        return RfSnapshot(
            timestamp = timestamp,
            wifiNetworkCount = results.size,
            averageSignalStrength = avgSignal,
            strongestSignal = signals.maxOrNull() ?: -100,
            weakestSignal = signals.minOrNull() ?: -100,
            channelDistribution = channelDist,
            band24Count = band24,
            band5Count = band5,
            band6Count = band6,
            openNetworkCount = openCount,
            hiddenNetworkCount = hiddenCount,
            droneNetworkCount = droneCount,
            surveillanceCameraCount = cameraCount,
            hiddenNetworkAnalysis = hiddenAnalysis
        )
    }

    // Helper data classes for analysis
    private data class HiddenNetworkData(
        val bssid: String,
        val oui: String,
        val rssi: Int,
        val frequency: Int,
        val channel: Int
    )

    private data class VisibleNetworkData(
        val bssid: String,
        val oui: String,
        val rssi: Int
    )

    /**
     * Build comprehensive hidden network analysis from scan data.
     */
    private fun buildHiddenNetworkAnalysis(
        hiddenNetworks: List<HiddenNetworkData>,
        visibleNetworks: List<VisibleNetworkData>,
        openNetworkOuis: Set<String>,
        cameraSignals: List<Int>,
        timestamp: Long
    ): HiddenNetworkAnalysis {
        // Signal characteristics
        val hiddenSignals = hiddenNetworks.map { it.rssi }
        val visibleSignals = visibleNetworks.map { it.rssi }
        val hiddenAvg = if (hiddenSignals.isNotEmpty()) hiddenSignals.average().toInt() else -100
        val visibleAvg = if (visibleSignals.isNotEmpty()) visibleSignals.average().toInt() else -100

        // Calculate signal variance for hidden networks (low variance = same hardware)
        val hiddenVariance = if (hiddenSignals.size > 1) {
            val mean = hiddenSignals.average()
            hiddenSignals.map { (it - mean) * (it - mean) }.average().toFloat()
        } else 0f

        // Count signal clusters (group signals within 5dBm)
        val signalClusters = countSignalClusters(hiddenSignals, threshold = 5)

        // Band distribution for hidden networks
        var hiddenBand24 = 0
        var hiddenBand5 = 0
        var hiddenBand6 = 0
        for (network in hiddenNetworks) {
            when {
                network.frequency in 2400..2500 -> hiddenBand24++
                network.frequency in 5000..5900 -> hiddenBand5++
                network.frequency in 5925..7125 -> hiddenBand6++
            }
        }
        val predominantBand = when {
            hiddenBand24 >= hiddenBand5 && hiddenBand24 >= hiddenBand6 -> "2.4GHz"
            hiddenBand5 >= hiddenBand24 && hiddenBand5 >= hiddenBand6 -> "5GHz"
            else -> "6GHz"
        }

        // Channel distribution for hidden networks
        val hiddenChannelDist = hiddenNetworks.groupingBy { it.channel }.eachCount()
        val maxChannelCount = hiddenChannelDist.values.maxOrNull() ?: 0
        val channelConcentration = hiddenChannelDist.size <= 3 && maxChannelCount >= hiddenNetworks.size / 2

        // OUI analysis
        val ouiDistribution = hiddenNetworks.filter { it.oui.isNotEmpty() }
            .groupingBy { it.oui }.eachCount()
        val uniqueOuiCount = ouiDistribution.size
        val sharedOuiCount = ouiDistribution.values.filter { it > 1 }.sum()
        val knownSurveillanceOuiCount = hiddenNetworks.count { it.oui in SURVEILLANCE_AREA_OUIS }

        // Temporal analysis - track hidden BSSIDs across scans
        val currentHiddenBssids = hiddenNetworks.map { it.bssid }.toSet()

        // Update history
        for (bssid in currentHiddenBssids) {
            val history = hiddenBssidHistory.getOrPut(bssid) { mutableListOf() }
            history.add(timestamp)
            // Keep only last 10 sightings
            while (history.size > 10) history.removeAt(0)

            // Track first appearance
            if (bssid !in hiddenNetworkFirstAppearance) {
                hiddenNetworkFirstAppearance[bssid] = timestamp
            }
        }

        // Count persistent BSSIDs (seen in multiple scans)
        val persistentBssids = currentHiddenBssids.count { bssid ->
            (hiddenBssidHistory[bssid]?.size ?: 0) > 1
        }

        // Count new BSSIDs this scan
        val newBssids = currentHiddenBssids.count { it !in lastHiddenBssidSet }

        // Check for simultaneous appearance (many new hidden networks at once)
        val simultaneousAppearance = newBssids >= 5 && lastHiddenBssidSet.isNotEmpty()

        // Update tracking for next scan
        lastHiddenBssidSet = currentHiddenBssids

        // Clean up old entries (not seen in 5 minutes)
        val cutoffTime = timestamp - 300_000L
        hiddenBssidHistory.entries.removeIf { (_, timestamps) ->
            timestamps.all { it < cutoffTime }
        }
        hiddenNetworkFirstAppearance.entries.removeIf { (bssid, _) ->
            bssid !in hiddenBssidHistory
        }

        // Correlation with cameras - count hidden networks with similar signal to cameras
        val avgCameraSignal = if (cameraSignals.isNotEmpty()) cameraSignals.average() else -100.0
        val hiddenNearCameras = if (cameraSignals.isNotEmpty()) {
            hiddenNetworks.count { kotlin.math.abs(it.rssi - avgCameraSignal) < 10 }
        } else 0

        // Count OUIs that have both open and hidden networks
        val hiddenOuis = hiddenNetworks.map { it.oui }.toSet()
        val openAndHiddenSameOui = hiddenOuis.count { it in openNetworkOuis && it.isNotEmpty() }

        return HiddenNetworkAnalysis(
            hiddenAvgSignalStrength = hiddenAvg,
            visibleAvgSignalStrength = visibleAvg,
            hiddenSignalStrongerThanVisible = hiddenAvg > visibleAvg + 5, // 5dBm threshold
            hiddenSignalVariance = hiddenVariance,
            signalClusterCount = signalClusters,
            hiddenBand24Count = hiddenBand24,
            hiddenBand5Count = hiddenBand5,
            hiddenBand6Count = hiddenBand6,
            predominantBand = predominantBand,
            hiddenChannelDistribution = hiddenChannelDist,
            channelConcentration = channelConcentration,
            hiddenOuiDistribution = ouiDistribution,
            sharedOuiCount = sharedOuiCount,
            knownSurveillanceOuiCount = knownSurveillanceOuiCount,
            uniqueOuiCount = uniqueOuiCount,
            persistentHiddenBssids = persistentBssids,
            newHiddenBssidsThisScan = newBssids,
            simultaneousAppearance = simultaneousAppearance,
            hiddenNearSurveillanceCameras = hiddenNearCameras,
            openAndHiddenFromSameOui = openAndHiddenSameOui
        )
    }

    /**
     * Count signal strength clusters (signals within threshold dBm of each other).
     */
    private fun countSignalClusters(signals: List<Int>, threshold: Int): Int {
        if (signals.isEmpty()) return 0
        val sorted = signals.sorted()
        var clusters = 1
        var clusterStart = sorted[0]
        for (signal in sorted) {
            if (signal - clusterStart > threshold) {
                clusters++
                clusterStart = signal
            }
        }
        return clusters
    }

    private fun checkForJammer(snapshot: RfSnapshot) {
        // Jammer detection: requires sustained significant drop in networks AND signal strength
        if (baselineNetworkCount == null || baselineSignalStrength == null) return

        val baseline = baselineNetworkCount!!
        val baselineSignal = baselineSignalStrength!!
        val current = snapshot.wifiNetworkCount

        // Check if current reading looks like jamming (significant drop to 1/3 or less)
        val networkDropThreshold = (baseline * JAMMER_NETWORK_DROP_RATIO).toInt()
        val looksLikeJamming = baseline >= NORMAL_NETWORK_FLOOR &&
            current <= networkDropThreshold &&
            snapshot.averageSignalStrength < baselineSignal - JAMMER_SIGNAL_DROP_THRESHOLD

        if (looksLikeJamming) {
            consecutiveJammerReadings++
            Log.d(TAG, "Potential jammer reading $consecutiveJammerReadings/$MIN_CONSECUTIVE_ANOMALOUS_READINGS")
        } else {
            // Reset counter if environment looks normal
            if (consecutiveJammerReadings > 0) {
                Log.d(TAG, "Jammer counter reset - environment normalized")
            }
            consecutiveJammerReadings = 0
            return
        }

        // Only alert after sustained anomalous readings
        if (consecutiveJammerReadings >= MIN_CONSECUTIVE_ANOMALOUS_READINGS) {
            val recentAvgSignal = signalHistory.takeLast(MIN_CONSECUTIVE_ANOMALOUS_READINGS)
                .map { it.averageSignalStrength }
                .average()
                .toInt()

            reportAnomaly(
                type = RfAnomalyType.POSSIBLE_JAMMER,
                description = "Sustained RF signal disruption detected - possible jammer",
                technicalDetails = "Network count dropped from $baseline to $current for " +
                    "$consecutiveJammerReadings consecutive scans. Signal: ${baselineSignal}dBm → ${recentAvgSignal}dBm.",
                confidence = if (consecutiveJammerReadings >= 5) AnomalyConfidence.HIGH else AnomalyConfidence.MEDIUM,
                contributingFactors = listOf(
                    "Network count: $baseline → $current (${((baseline - current) * 100 / baseline)}% drop)",
                    "Signal strength: ${baselineSignal}dBm → ${recentAvgSignal}dBm",
                    "Sustained for $consecutiveJammerReadings readings"
                )
            )
            // Reset after alerting
            consecutiveJammerReadings = 0
        }
    }

    private fun checkForDrones(results: List<ScanResult>) {
        val now = System.currentTimeMillis()

        for (result in results) {
            val bssid = result.BSSID?.uppercase() ?: continue
            @Suppress("DEPRECATION")
            val ssid = result.SSID ?: ""
            val oui = bssid.take(8)

            // Require BOTH OUI match AND SSID pattern for higher confidence,
            // OR just OUI for known drone manufacturers
            val ouiMatch = oui in DRONE_OUIS
            val ssidMatch = DRONE_SSID_PATTERNS.any { it.matches(ssid) }

            // High confidence: OUI matches known drone manufacturer
            // Medium confidence: SSID matches drone pattern (requires multiple sightings)
            val isDrone = ouiMatch || ssidMatch
            val isHighConfidence = ouiMatch

            if (isDrone) {
                val existing = detectedDrones[bssid]
                val manufacturer = when {
                    oui.startsWith("60:60:1F") || oui.startsWith("34:D2:62") ||
                    oui.startsWith("48:1C:B9") || oui.startsWith("60:C7:98") -> "DJI"
                    oui.startsWith("A0:14:3D") || oui.startsWith("90:03:B7") ||
                    oui.startsWith("00:12:1C") || oui.startsWith("00:26:7E") -> "Parrot"
                    ssid.lowercase().contains("skydio") -> "Skydio"
                    ssid.lowercase().contains("autel") || ssid.lowercase().contains("evo") -> "Autel"
                    ssid.lowercase().contains("yuneec") -> "Yuneec"
                    else -> "Unknown"
                }

                if (existing != null) {
                    // Already tracking this drone
                    existing.lastSeen = now
                    existing.rssi = result.level
                    existing.seenCount++
                } else {
                    // New potential drone - track sightings before alerting
                    val sightings = pendingDroneSightings.getOrDefault(bssid, 0) + 1
                    pendingDroneSightings[bssid] = sightings

                    // High confidence (OUI match) or seen multiple times -> confirm as drone
                    val shouldConfirm = isHighConfidence || sightings >= MIN_DRONE_SIGHTINGS

                    if (shouldConfirm) {
                        val drone = DroneInfo(
                            bssid = bssid,
                            ssid = ssid,
                            manufacturer = manufacturer,
                            firstSeen = now,
                            lastSeen = now,
                            rssi = result.level,
                            latitude = currentLatitude,
                            longitude = currentLongitude,
                            estimatedDistance = rssiToDistance(result.level)
                        )
                        detectedDrones[bssid] = drone
                        pendingDroneSightings.remove(bssid)

                        val confidence = if (isHighConfidence) AnomalyConfidence.HIGH else AnomalyConfidence.MEDIUM

                        reportAnomaly(
                            type = RfAnomalyType.DRONE_DETECTED,
                            description = "Drone WiFi signal detected: $manufacturer",
                            technicalDetails = "SSID: '$ssid', Signal: ${result.level}dBm, " +
                                "Estimated distance: ${rssiToDistance(result.level)}" +
                                if (!isHighConfidence) ", Seen $sightings times" else "",
                            confidence = confidence,
                            contributingFactors = listOf(
                                "Manufacturer: $manufacturer",
                                "SSID: $ssid",
                                "Signal strength: ${result.level}dBm",
                                "Estimated distance: ${rssiToDistance(result.level)}",
                                if (ouiMatch) "MAC address matches drone OUI" else "SSID matches drone pattern"
                            )
                        )

                        addTimelineEvent(
                            type = RfEventType.DRONE_DETECTED,
                            title = "🚁 Drone Detected",
                            description = "$manufacturer drone at ${rssiToDistance(result.level)}",
                            threatLevel = ThreatLevel.MEDIUM
                        )
                    } else {
                        Log.d(TAG, "Potential drone '$ssid' sighting $sightings/$MIN_DRONE_SIGHTINGS")
                    }
                }
            }
        }

        // Clean up old drone sightings and pending sightings
        detectedDrones.entries.removeIf { now - it.value.lastSeen > DRONE_DETECTION_COOLDOWN_MS }
        _detectedDrones.value = detectedDrones.values.toList()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun checkForSurveillanceArea(results: List<ScanResult>, snapshot: RfSnapshot) {
        // Count surveillance cameras - require higher threshold to reduce FPs
        val cameraCount = snapshot.surveillanceCameraCount

        if (cameraCount >= MIN_SURVEILLANCE_CAMERAS) {
            reportAnomaly(
                type = RfAnomalyType.SURVEILLANCE_AREA,
                description = "High concentration of surveillance cameras detected",
                technicalDetails = "$cameraCount surveillance camera WiFi networks detected. " +
                    "This area has significant video surveillance infrastructure.",
                confidence = if (cameraCount >= 15) AnomalyConfidence.HIGH else AnomalyConfidence.MEDIUM,
                contributingFactors = listOf(
                    "$cameraCount camera networks detected",
                    "Manufacturers: Hikvision, Dahua, Axis, etc.",
                    "Consider: This may be a commercial/government facility"
                )
            )
        }

        // Hidden network detection - requires BOTH high density AND high hidden ratio
        // Only check if enableHiddenNetworkRfAnomaly is true (disabled by default due to high false positive rate)
        if (enableHiddenNetworkRfAnomaly && snapshot.wifiNetworkCount > DENSE_NETWORK_THRESHOLD) {
            val hiddenRatio = snapshot.hiddenNetworkCount.toFloat() / snapshot.wifiNetworkCount

            // Stricter threshold: 40% hidden AND at least 15 hidden networks
            if (hiddenRatio > HIDDEN_NETWORK_SUSPICIOUS_RATIO && snapshot.hiddenNetworkCount >= 15) {
                val analysis = snapshot.hiddenNetworkAnalysis

                // Calculate confidence based on multiple indicators
                val confidence = calculateHiddenNetworkConfidence(analysis, hiddenRatio)

                // Build enriched technical details
                val technicalDetails = buildHiddenNetworkTechnicalDetails(
                    snapshot, hiddenRatio, analysis
                )

                // Build comprehensive contributing factors
                val contributingFactors = buildHiddenNetworkContributingFactors(
                    snapshot, hiddenRatio, analysis
                )

                reportAnomaly(
                    type = RfAnomalyType.UNUSUAL_ACTIVITY,
                    description = buildHiddenNetworkDescription(analysis),
                    technicalDetails = technicalDetails,
                    confidence = confidence,
                    contributingFactors = contributingFactors
                )
            }
        }
    }

    /**
     * Calculate confidence level based on multiple hidden network indicators.
     */
    private fun calculateHiddenNetworkConfidence(
        analysis: HiddenNetworkAnalysis?,
        hiddenRatio: Float
    ): AnomalyConfidence {
        if (analysis == null) return AnomalyConfidence.LOW

        var suspicionScore = 0

        // High hidden ratio is suspicious
        if (hiddenRatio > 0.5f) suspicionScore += 2
        else if (hiddenRatio > 0.4f) suspicionScore += 1

        // Hidden networks stronger than visible = very suspicious
        if (analysis.hiddenSignalStrongerThanVisible) suspicionScore += 2

        // Low signal variance = same hardware = coordinated deployment
        if (analysis.hiddenSignalVariance < 50f && analysis.signalClusterCount <= 2) suspicionScore += 2

        // Multiple hidden networks share same OUI = coordinated
        if (analysis.sharedOuiCount >= 5) suspicionScore += 2
        else if (analysis.sharedOuiCount >= 3) suspicionScore += 1

        // Known surveillance vendor OUIs
        if (analysis.knownSurveillanceOuiCount > 0) suspicionScore += 2

        // Channel concentration suggests coordinated deployment
        if (analysis.channelConcentration) suspicionScore += 1

        // Many new hidden networks appeared simultaneously
        if (analysis.simultaneousAppearance) suspicionScore += 2

        // Hidden networks near surveillance cameras
        if (analysis.hiddenNearSurveillanceCameras >= 5) suspicionScore += 1

        // Persistent hidden networks (seen across multiple scans)
        if (analysis.persistentHiddenBssids >= 10) suspicionScore += 1

        return when {
            suspicionScore >= 8 -> AnomalyConfidence.HIGH
            suspicionScore >= 5 -> AnomalyConfidence.MEDIUM
            else -> AnomalyConfidence.LOW
        }
    }

    /**
     * Build a descriptive summary based on analysis findings.
     */
    private fun buildHiddenNetworkDescription(analysis: HiddenNetworkAnalysis?): String {
        if (analysis == null) return "Unusually high number of hidden WiFi networks"

        val indicators = mutableListOf<String>()

        if (analysis.hiddenSignalStrongerThanVisible) {
            indicators.add("stronger signals than visible networks")
        }
        if (analysis.sharedOuiCount >= 3) {
            indicators.add("shared hardware vendors")
        }
        if (analysis.knownSurveillanceOuiCount > 0) {
            indicators.add("known surveillance equipment")
        }
        if (analysis.simultaneousAppearance) {
            indicators.add("appeared simultaneously")
        }
        if (analysis.channelConcentration) {
            indicators.add("concentrated on few channels")
        }

        return if (indicators.isNotEmpty()) {
            "Hidden WiFi network anomaly: ${indicators.joinToString(", ")}"
        } else {
            "Unusually high number of hidden WiFi networks"
        }
    }

    /**
     * Build detailed technical information for the detection.
     */
    private fun buildHiddenNetworkTechnicalDetails(
        snapshot: RfSnapshot,
        hiddenRatio: Float,
        analysis: HiddenNetworkAnalysis?
    ): String {
        val sb = StringBuilder()

        sb.append("${snapshot.hiddenNetworkCount} of ${snapshot.wifiNetworkCount} ")
        sb.append("networks are hidden (${(hiddenRatio * 100).toInt()}%). ")

        if (analysis != null) {
            // Signal analysis
            sb.append("\n\nSIGNAL ANALYSIS: ")
            sb.append("Hidden networks avg ${analysis.hiddenAvgSignalStrength}dBm ")
            sb.append("vs visible ${analysis.visibleAvgSignalStrength}dBm. ")
            if (analysis.hiddenSignalStrongerThanVisible) {
                sb.append("⚠️ Hidden networks have STRONGER signals (unusual). ")
            }
            if (analysis.hiddenSignalVariance < 100f) {
                sb.append("Signal variance: ${String.format("%.1f", analysis.hiddenSignalVariance)} ")
                sb.append("(${if (analysis.hiddenSignalVariance < 50f) "LOW - suggests same hardware" else "moderate"}). ")
            }
            sb.append("${analysis.signalClusterCount} signal strength clusters detected.")

            // Band distribution
            sb.append("\n\nBAND DISTRIBUTION: ")
            sb.append("2.4GHz: ${analysis.hiddenBand24Count}, ")
            sb.append("5GHz: ${analysis.hiddenBand5Count}, ")
            sb.append("6GHz: ${analysis.hiddenBand6Count}. ")
            sb.append("Predominantly ${analysis.predominantBand}.")

            // Channel analysis
            if (analysis.channelConcentration) {
                sb.append("\n\n⚠️ CHANNEL CONCENTRATION: ")
                sb.append("Hidden networks clustered on ${analysis.hiddenChannelDistribution.size} channels. ")
                sb.append("This suggests coordinated deployment.")
            }

            // OUI/Manufacturer analysis
            sb.append("\n\nMANUFACTURER ANALYSIS: ")
            sb.append("${analysis.uniqueOuiCount} unique vendors identified. ")
            if (analysis.sharedOuiCount > 0) {
                sb.append("${analysis.sharedOuiCount} hidden networks share same vendor OUI. ")
            }
            if (analysis.knownSurveillanceOuiCount > 0) {
                sb.append("⚠️ ${analysis.knownSurveillanceOuiCount} networks from KNOWN surveillance equipment vendors. ")
            }

            // Temporal patterns
            sb.append("\n\nTEMPORAL PATTERNS: ")
            sb.append("${analysis.persistentHiddenBssids} persistent (seen multiple scans), ")
            sb.append("${analysis.newHiddenBssidsThisScan} new this scan. ")
            if (analysis.simultaneousAppearance) {
                sb.append("⚠️ Many hidden networks appeared SIMULTANEOUSLY - suggests coordinated activation.")
            }

            // Correlations
            if (analysis.hiddenNearSurveillanceCameras > 0 || analysis.openAndHiddenFromSameOui > 0) {
                sb.append("\n\nCORRELATIONS: ")
                if (analysis.hiddenNearSurveillanceCameras > 0) {
                    sb.append("${analysis.hiddenNearSurveillanceCameras} hidden networks at similar signal strength to cameras. ")
                }
                if (analysis.openAndHiddenFromSameOui > 0) {
                    sb.append("${analysis.openAndHiddenFromSameOui} vendors have both open AND hidden networks (dual-mode equipment).")
                }
            }
        } else {
            sb.append("High hidden network density can indicate covert surveillance.")
        }

        return sb.toString()
    }

    /**
     * Build comprehensive list of contributing factors for the detection.
     */
    private fun buildHiddenNetworkContributingFactors(
        snapshot: RfSnapshot,
        hiddenRatio: Float,
        analysis: HiddenNetworkAnalysis?
    ): List<String> {
        val factors = mutableListOf<String>()

        // Basic counts
        factors.add("${snapshot.wifiNetworkCount} total networks detected")
        factors.add("${snapshot.hiddenNetworkCount} hidden networks (${(hiddenRatio * 100).toInt()}%)")

        if (analysis != null) {
            // Signal characteristics
            factors.add("Hidden avg signal: ${analysis.hiddenAvgSignalStrength}dBm")
            if (analysis.hiddenSignalStrongerThanVisible) {
                factors.add("⚠️ Hidden signals STRONGER than visible networks")
            }
            if (analysis.hiddenSignalVariance < 50f) {
                factors.add("⚠️ Low signal variance (${String.format("%.1f", analysis.hiddenSignalVariance)}) - same hardware likely")
            }
            factors.add("${analysis.signalClusterCount} signal strength clusters")

            // Band info
            factors.add("Band distribution: ${analysis.hiddenBand24Count} on 2.4GHz, ${analysis.hiddenBand5Count} on 5GHz")

            // Channel concentration
            if (analysis.channelConcentration) {
                factors.add("⚠️ Channel concentration detected (${analysis.hiddenChannelDistribution.size} channels)")
            }

            // OUI analysis
            factors.add("${analysis.uniqueOuiCount} unique hardware vendors")
            if (analysis.sharedOuiCount >= 3) {
                factors.add("⚠️ ${analysis.sharedOuiCount} networks share same vendor")
            }
            if (analysis.knownSurveillanceOuiCount > 0) {
                factors.add("⚠️ ${analysis.knownSurveillanceOuiCount} from known surveillance vendors")
            }

            // Temporal
            if (analysis.persistentHiddenBssids > 5) {
                factors.add("${analysis.persistentHiddenBssids} persistent hidden networks")
            }
            if (analysis.simultaneousAppearance) {
                factors.add("⚠️ ${analysis.newHiddenBssidsThisScan} networks appeared simultaneously")
            }

            // Correlations
            if (analysis.hiddenNearSurveillanceCameras >= 3) {
                factors.add("${analysis.hiddenNearSurveillanceCameras} co-located with cameras")
            }
            if (analysis.openAndHiddenFromSameOui > 0) {
                factors.add("${analysis.openAndHiddenFromSameOui} dual-mode vendors detected")
            }
        }

        return factors
    }

    private fun checkForSpectrumAnomalies(snapshot: RfSnapshot) {
        if (signalHistory.size < MIN_BASELINE_SAMPLES) return // Need stable history

        // Check for sustained signal strength anomalies (not just single-scan fluctuations)
        val recentSnapshots = signalHistory.takeLast(MIN_CONSECUTIVE_ANOMALOUS_READINGS)
        val olderSnapshots = signalHistory.dropLast(MIN_CONSECUTIVE_ANOMALOUS_READINGS)
            .takeLast(MIN_BASELINE_SAMPLES)

        if (olderSnapshots.size < MIN_BASELINE_SAMPLES / 2) return

        val recentAvg = recentSnapshots.map { it.averageSignalStrength }.average()
        val olderAvg = olderSnapshots.map { it.averageSignalStrength }.average()
        val signalDelta = kotlin.math.abs(recentAvg - olderAvg)

        // Check if the change is significant AND sustained
        val isSignificantChange = signalDelta > SIGNAL_INTERFERENCE_THRESHOLD_DBM

        if (isSignificantChange) {
            consecutiveInterferenceReadings++
        } else {
            consecutiveInterferenceReadings = 0
            return
        }

        // Only alert after sustained anomalous readings
        if (consecutiveInterferenceReadings >= MIN_CONSECUTIVE_ANOMALOUS_READINGS) {
            reportAnomaly(
                type = RfAnomalyType.SIGNAL_INTERFERENCE,
                description = "Sustained change in RF environment detected",
                technicalDetails = "Average signal strength changed by ${signalDelta.toInt()}dBm " +
                    "sustained over $consecutiveInterferenceReadings scans. May indicate interference.",
                confidence = AnomalyConfidence.LOW,
                contributingFactors = listOf(
                    "Signal change: ${olderAvg.toInt()}dBm → ${recentAvg.toInt()}dBm",
                    "Networks visible: ${snapshot.wifiNetworkCount}",
                    "Sustained for $consecutiveInterferenceReadings readings"
                )
            )
            consecutiveInterferenceReadings = 0
        }
    }

    private fun updateStatus(snapshot: RfSnapshot) {
        val noiseLevel = when {
            snapshot.wifiNetworkCount > 50 -> NoiseLevel.EXTREME
            snapshot.wifiNetworkCount > 30 -> NoiseLevel.HIGH
            snapshot.wifiNetworkCount > 15 -> NoiseLevel.MODERATE
            else -> NoiseLevel.LOW
        }

        val maxChannelCount = snapshot.channelDistribution.values.maxOrNull() ?: 0
        val channelCongestion = when {
            maxChannelCount > 15 -> ChannelCongestion.SEVERE
            maxChannelCount > 10 -> ChannelCongestion.HEAVY
            maxChannelCount > 5 -> ChannelCongestion.MODERATE
            maxChannelCount > 2 -> ChannelCongestion.LIGHT
            else -> ChannelCongestion.CLEAR
        }

        // Calculate environment risk
        val riskFactors = mutableListOf<Int>()
        if (snapshot.surveillanceCameraCount > 0) riskFactors.add(snapshot.surveillanceCameraCount * 5)
        if (snapshot.droneNetworkCount > 0) riskFactors.add(30)
        if (snapshot.hiddenNetworkCount > 5) riskFactors.add(15)
        if (snapshot.openNetworkCount > 10) riskFactors.add(10)

        val totalRisk = riskFactors.sum()
        val environmentRisk = when {
            totalRisk > 50 -> EnvironmentRisk.HIGH
            totalRisk > 30 -> EnvironmentRisk.ELEVATED
            totalRisk > 15 -> EnvironmentRisk.MODERATE
            else -> EnvironmentRisk.LOW
        }

        val jammerSuspected = baselineNetworkCount?.let { baseline ->
            val threshold = (baseline * JAMMER_NETWORK_DROP_RATIO).toInt()
            snapshot.wifiNetworkCount <= threshold && baseline >= NORMAL_NETWORK_FLOOR &&
                consecutiveJammerReadings >= MIN_CONSECUTIVE_ANOMALOUS_READINGS - 1
        } ?: false

        _rfStatus.value = RfEnvironmentStatus(
            totalNetworks = snapshot.wifiNetworkCount,
            band24GHz = snapshot.band24Count,
            band5GHz = snapshot.band5Count,
            band6GHz = snapshot.band6Count,
            averageSignalStrength = snapshot.averageSignalStrength,
            noiseLevel = noiseLevel,
            jammerSuspected = jammerSuspected,
            dronesDetected = detectedDrones.size,
            surveillanceCameras = snapshot.surveillanceCameraCount,
            channelCongestion = channelCongestion,
            lastScanTime = snapshot.timestamp,
            environmentRisk = environmentRisk
        )
    }

    private fun reportAnomaly(
        type: RfAnomalyType,
        description: String,
        technicalDetails: String,
        confidence: AnomalyConfidence,
        contributingFactors: List<String>
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

        val anomaly = RfAnomaly(
            type = type,
            severity = severity,
            confidence = confidence,
            description = description,
            technicalDetails = technicalDetails,
            latitude = currentLatitude,
            longitude = currentLongitude,
            contributingFactors = contributingFactors
        )

        detectedAnomalies.add(anomaly)
        _anomalies.value = detectedAnomalies.toList()

        addTimelineEvent(
            type = RfEventType.ANOMALY_DETECTED,
            title = "${type.emoji} ${type.displayName}",
            description = description,
            isAnomaly = true,
            threatLevel = severity
        )

        Log.w(TAG, "RF ANOMALY [${confidence.displayName}]: ${type.displayName} - $description")
    }

    private fun addTimelineEvent(
        type: RfEventType,
        title: String,
        description: String,
        isAnomaly: Boolean = false,
        threatLevel: ThreatLevel = ThreatLevel.INFO
    ) {
        val event = RfEvent(
            type = type,
            title = title,
            description = description,
            isAnomaly = isAnomaly,
            threatLevel = threatLevel,
            latitude = currentLatitude,
            longitude = currentLongitude
        )

        eventHistory.add(0, event)
        if (eventHistory.size > maxEventHistory) {
            eventHistory.removeAt(eventHistory.size - 1)
        }
        _rfEvents.value = eventHistory.toList()
    }

    private fun frequencyToChannel(frequency: Int): Int {
        return when {
            frequency in 2412..2484 -> (frequency - 2412) / 5 + 1
            frequency in 5170..5825 -> (frequency - 5170) / 5 + 34
            frequency in 5955..7115 -> (frequency - 5955) / 5 + 1
            else -> 0
        }
    }

    private fun rssiToDistance(rssi: Int): String {
        return when {
            rssi > -40 -> "< 5m"
            rssi > -50 -> "~5-15m"
            rssi > -60 -> "~15-30m"
            rssi > -70 -> "~30-50m"
            rssi > -80 -> "~50-100m"
            else -> "> 100m"
        }
    }

    fun clearAnomalies() {
        detectedAnomalies.clear()
        _anomalies.value = emptyList()
    }

    fun clearHistory() {
        signalHistory.clear()
        detectedDrones.clear()
        eventHistory.clear()
        pendingDroneSightings.clear()
        hiddenBssidHistory.clear()
        lastHiddenBssidSet = emptySet()
        hiddenNetworkFirstAppearance.clear()
        _rfEvents.value = emptyList()
        _detectedDrones.value = emptyList()
        baselineNetworkCount = null
        baselineSignalStrength = null
        consecutiveJammerReadings = 0
        consecutiveInterferenceReadings = 0
    }

    fun destroy() {
        stopMonitoring()
    }

    /**
     * Convert RF anomaly to Detection for storage
     */
    fun anomalyToDetection(anomaly: RfAnomaly): Detection {
        val detectionMethod = when (anomaly.type) {
            RfAnomalyType.POSSIBLE_JAMMER -> DetectionMethod.RF_JAMMER
            RfAnomalyType.DRONE_DETECTED -> DetectionMethod.RF_DRONE
            RfAnomalyType.SURVEILLANCE_AREA -> DetectionMethod.RF_SURVEILLANCE_AREA
            RfAnomalyType.SPECTRUM_ANOMALY -> DetectionMethod.RF_SPECTRUM_ANOMALY
            RfAnomalyType.UNUSUAL_ACTIVITY -> DetectionMethod.RF_UNUSUAL_ACTIVITY
            RfAnomalyType.SIGNAL_INTERFERENCE -> DetectionMethod.RF_INTERFERENCE
            RfAnomalyType.HIDDEN_TRANSMITTER -> DetectionMethod.RF_HIDDEN_TRANSMITTER
        }

        val deviceType = when (anomaly.type) {
            RfAnomalyType.POSSIBLE_JAMMER -> DeviceType.RF_JAMMER
            RfAnomalyType.DRONE_DETECTED -> DeviceType.DRONE
            RfAnomalyType.SURVEILLANCE_AREA -> DeviceType.SURVEILLANCE_INFRASTRUCTURE
            RfAnomalyType.SIGNAL_INTERFERENCE -> DeviceType.RF_INTERFERENCE
            RfAnomalyType.SPECTRUM_ANOMALY -> DeviceType.RF_ANOMALY
            RfAnomalyType.UNUSUAL_ACTIVITY -> DeviceType.RF_ANOMALY
            RfAnomalyType.HIDDEN_TRANSMITTER -> DeviceType.HIDDEN_TRANSMITTER
        }

        return Detection(
            deviceType = deviceType,
            protocol = DetectionProtocol.WIFI, // RF analysis uses WiFi as proxy
            detectionMethod = detectionMethod,
            deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}",
            macAddress = null,
            ssid = null,
            rssi = -50, // Approximate
            signalStrength = SignalStrength.MEDIUM,
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            threatLevel = anomaly.severity,
            threatScore = anomaly.type.baseScore,
            matchedPatterns = anomaly.contributingFactors.joinToString(", ")
        )
    }

    /**
     * Convert drone detection to Detection
     */
    fun droneToDetection(drone: DroneInfo): Detection {
        return Detection(
            deviceType = DeviceType.DRONE,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.RF_DRONE,
            deviceName = "🚁 ${drone.manufacturer} Drone",
            macAddress = drone.bssid,
            ssid = drone.ssid,
            rssi = drone.rssi,
            signalStrength = rssiToSignalStrength(drone.rssi),
            latitude = drone.latitude,
            longitude = drone.longitude,
            threatLevel = ThreatLevel.MEDIUM,
            threatScore = 70,
            manufacturer = drone.manufacturer,
            matchedPatterns = "Drone WiFi pattern, Est. distance: ${drone.estimatedDistance}"
        )
    }
}

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
class RfSignalAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "RfSignalAnalyzer"

        // Thresholds
        private const val JAMMER_DETECTION_WINDOW_MS = 30_000L // 30 seconds
        private const val NORMAL_NETWORK_FLOOR = 3 // Expect at least 3 networks normally
        private const val SIGNAL_HISTORY_SIZE = 60 // ~1 minute of samples at 1/sec
        private const val JAMMER_SIGNAL_DROP_THRESHOLD = 20 // dBm drop
        private const val ANOMALY_COOLDOWN_MS = 60_000L // 1 minute between same type
        private const val DENSE_NETWORK_THRESHOLD = 30 // Many networks = possible surveillance area
        private const val DRONE_DETECTION_COOLDOWN_MS = 300_000L // 5 minutes

        // Drone WiFi patterns (common consumer/prosumer drones)
        private val DRONE_SSID_PATTERNS = listOf(
            Regex("(?i)^dji[-_]?.*"),           // DJI drones
            Regex("(?i)^phantom[-_]?.*"),       // DJI Phantom series
            Regex("(?i)^mavic[-_]?.*"),         // DJI Mavic series
            Regex("(?i)^spark[-_]?.*"),         // DJI Spark
            Regex("(?i)^inspire[-_]?.*"),       // DJI Inspire
            Regex("(?i)^mini[-_]?se?[_-]?.*"),  // DJI Mini series (but careful of false positives)
            Regex("(?i)^tello[-_]?.*"),         // Ryze Tello (DJI)
            Regex("(?i)^parrot[-_]?.*"),        // Parrot drones
            Regex("(?i)^anafi[-_]?.*"),         // Parrot Anafi
            Regex("(?i)^bebop[-_]?.*"),         // Parrot Bebop
            Regex("(?i)^skydio[-_]?.*"),        // Skydio drones
            Regex("(?i)^autel[-_]?.*"),         // Autel drones
            Regex("(?i)^evo[-_]?(ii|2|lite).*"),// Autel EVO series
            Regex("(?i)^yuneec[-_]?.*"),        // Yuneec drones
            Regex("(?i)^typhoon[-_]?.*"),       // Yuneec Typhoon
            Regex("(?i)^hubsan[-_]?.*"),        // Hubsan drones
            Regex("(?i)^holy[-_]?stone[-_]?.*"),// Holy Stone drones
            Regex("(?i)^potensic[-_]?.*"),      // Potensic drones
            Regex("(?i)^snaptain[-_]?.*"),      // Snaptain drones
            Regex("(?i)^drone[-_]?[0-9a-f]+"),  // Generic drone pattern
            Regex("(?i)^fpv[-_]?.*"),           // FPV racing drones
            Regex("(?i)^quad[-_]?.*"),          // Quadcopter generic
            Regex("(?i)^uav[-_]?.*"),           // UAV generic
            // Police/surveillance drones often use these patterns
            Regex("(?i)^(pd|police|tactical|aerial)[-_]?(drone|uav|unit).*"),
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
    private var lastAnomalyTimes = mutableMapOf<RfAnomalyType, Long>()

    // Drone tracking
    private val detectedDrones = mutableMapOf<String, DroneInfo>()

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
        val surveillanceCameraCount: Int
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
    )

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
    }

    fun stopMonitoring() {
        isMonitoring = false

        addTimelineEvent(
            type = RfEventType.MONITORING_STOPPED,
            title = "RF Analysis Stopped",
            description = "RF surveillance detection paused"
        )

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

        val now = System.currentTimeMillis()

        // Build snapshot
        val snapshot = buildSnapshot(results, now)
        signalHistory.add(snapshot)
        if (signalHistory.size > SIGNAL_HISTORY_SIZE) {
            signalHistory.removeAt(0)
        }

        // Set baseline if not set
        if (baselineNetworkCount == null && signalHistory.size >= 3) {
            baselineNetworkCount = signalHistory.map { it.wifiNetworkCount }.average().toInt()
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
            if (!capabilities.contains("WPA") && !capabilities.contains("WEP") &&
                !capabilities.contains("RSN")) {
                openCount++
            }

            @Suppress("DEPRECATION")
            val ssid = result.SSID ?: ""
            if (ssid.isEmpty()) hiddenCount++

            // Check if drone
            val bssid = result.BSSID?.uppercase() ?: ""
            val oui = bssid.take(8)
            if (oui in DRONE_OUIS || DRONE_SSID_PATTERNS.any { it.matches(ssid) }) {
                droneCount++
            }

            // Check if surveillance camera
            if (oui in SURVEILLANCE_AREA_OUIS) {
                cameraCount++
            }
        }

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
            surveillanceCameraCount = cameraCount
        )
    }

    private fun checkForJammer(snapshot: RfSnapshot) {
        // Jammer detection: sudden significant drop in visible networks
        if (baselineNetworkCount == null) return

        val baseline = baselineNetworkCount!!
        val current = snapshot.wifiNetworkCount

        // If we had a reasonable baseline and it suddenly dropped significantly
        if (baseline >= NORMAL_NETWORK_FLOOR && current < baseline / 2) {
            // Also check signal strength - jammer would cause overall signal degradation
            val recentAvgSignal = signalHistory.takeLast(3)
                .map { it.averageSignalStrength }
                .average()
                .toInt()

            val historicalAvgSignal = signalHistory.take(signalHistory.size / 2)
                .map { it.averageSignalStrength }
                .average()
                .toInt()

            if (recentAvgSignal < historicalAvgSignal - JAMMER_SIGNAL_DROP_THRESHOLD) {
                reportAnomaly(
                    type = RfAnomalyType.POSSIBLE_JAMMER,
                    description = "Sudden drop in RF signals suggests possible jammer nearby",
                    technicalDetails = "Network count dropped from $baseline to $current. " +
                        "Average signal dropped from ${historicalAvgSignal}dBm to ${recentAvgSignal}dBm.",
                    confidence = AnomalyConfidence.MEDIUM,
                    contributingFactors = listOf(
                        "Network count: $baseline → $current (${((baseline - current) * 100 / baseline)}% drop)",
                        "Signal strength: ${historicalAvgSignal}dBm → ${recentAvgSignal}dBm",
                        "Time window: ${JAMMER_DETECTION_WINDOW_MS / 1000}s"
                    )
                )
            }
        }
    }

    private fun checkForDrones(results: List<ScanResult>) {
        val now = System.currentTimeMillis()

        for (result in results) {
            val bssid = result.BSSID?.uppercase() ?: continue
            @Suppress("DEPRECATION")
            val ssid = result.SSID ?: ""
            val oui = bssid.take(8)

            val isDrone = oui in DRONE_OUIS || DRONE_SSID_PATTERNS.any { it.matches(ssid) }

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
                    existing.lastSeen = now
                    existing.rssi = result.level
                    existing.seenCount++
                } else {
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

                    // Report new drone detection
                    reportAnomaly(
                        type = RfAnomalyType.DRONE_DETECTED,
                        description = "Drone WiFi signal detected: $manufacturer",
                        technicalDetails = "SSID: '$ssid', Signal: ${result.level}dBm, " +
                            "Estimated distance: ${rssiToDistance(result.level)}",
                        confidence = AnomalyConfidence.HIGH,
                        contributingFactors = listOf(
                            "Manufacturer: $manufacturer",
                            "SSID: $ssid",
                            "Signal strength: ${result.level}dBm",
                            "Estimated distance: ${rssiToDistance(result.level)}"
                        )
                    )

                    addTimelineEvent(
                        type = RfEventType.DRONE_DETECTED,
                        title = "🚁 Drone Detected",
                        description = "$manufacturer drone at ${rssiToDistance(result.level)}",
                        threatLevel = ThreatLevel.MEDIUM
                    )
                }
            }
        }

        // Clean up old drone sightings
        detectedDrones.entries.removeIf { now - it.value.lastSeen > DRONE_DETECTION_COOLDOWN_MS }
        _detectedDrones.value = detectedDrones.values.toList()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun checkForSurveillanceArea(results: List<ScanResult>, snapshot: RfSnapshot) {
        // Count surveillance cameras
        val cameraCount = snapshot.surveillanceCameraCount

        if (cameraCount >= 5) {
            reportAnomaly(
                type = RfAnomalyType.SURVEILLANCE_AREA,
                description = "High concentration of surveillance cameras detected",
                technicalDetails = "$cameraCount surveillance camera WiFi networks detected. " +
                    "This area has significant video surveillance infrastructure.",
                confidence = if (cameraCount >= 10) AnomalyConfidence.HIGH else AnomalyConfidence.MEDIUM,
                contributingFactors = listOf(
                    "$cameraCount camera networks detected",
                    "Manufacturers: Hikvision, Dahua, Axis, etc.",
                    "Consider: This may be a commercial/government facility"
                )
            )
        }

        // Also check for unusual density of networks overall
        if (snapshot.wifiNetworkCount > DENSE_NETWORK_THRESHOLD) {
            // Many networks in one area - could be surveillance infrastructure
            val hiddenRatio = snapshot.hiddenNetworkCount.toFloat() / snapshot.wifiNetworkCount

            if (hiddenRatio > 0.3) { // >30% hidden networks is suspicious
                reportAnomaly(
                    type = RfAnomalyType.UNUSUAL_ACTIVITY,
                    description = "Unusually high number of hidden WiFi networks",
                    technicalDetails = "${snapshot.hiddenNetworkCount} of ${snapshot.wifiNetworkCount} " +
                        "networks are hidden (${(hiddenRatio * 100).toInt()}%). " +
                        "High hidden network density can indicate covert surveillance.",
                    confidence = AnomalyConfidence.LOW,
                    contributingFactors = listOf(
                        "${snapshot.wifiNetworkCount} total networks",
                        "${snapshot.hiddenNetworkCount} hidden networks",
                        "${(hiddenRatio * 100).toInt()}% hidden ratio"
                    )
                )
            }
        }
    }

    private fun checkForSpectrumAnomalies(snapshot: RfSnapshot) {
        if (signalHistory.size < 10) return // Need history for comparison

        // Check for sudden signal strength changes across all networks
        val recentSnapshots = signalHistory.takeLast(5)
        val olderSnapshots = signalHistory.dropLast(5).takeLast(5)

        if (olderSnapshots.isEmpty()) return

        val recentAvg = recentSnapshots.map { it.averageSignalStrength }.average()
        val olderAvg = olderSnapshots.map { it.averageSignalStrength }.average()

        // Significant overall signal change could indicate interference
        if (kotlin.math.abs(recentAvg - olderAvg) > 15) {
            reportAnomaly(
                type = RfAnomalyType.SIGNAL_INTERFERENCE,
                description = "Significant change in overall RF environment",
                technicalDetails = "Average signal strength changed by ${kotlin.math.abs(recentAvg - olderAvg).toInt()}dBm " +
                    "over the last few scans. This could indicate RF interference or environmental changes.",
                confidence = AnomalyConfidence.LOW,
                contributingFactors = listOf(
                    "Signal change: ${olderAvg.toInt()}dBm → ${recentAvg.toInt()}dBm",
                    "Networks visible: ${snapshot.wifiNetworkCount}"
                )
            )
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
            snapshot.wifiNetworkCount < baseline / 2 && baseline >= NORMAL_NETWORK_FLOOR
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
        _rfEvents.value = emptyList()
        _detectedDrones.value = emptyList()
        baselineNetworkCount = null
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
            else -> DeviceType.UNKNOWN_SURVEILLANCE
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

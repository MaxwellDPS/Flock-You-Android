package com.flockyou.detection.handler

import android.net.wifi.ScanResult
import com.flockyou.data.model.*
import com.flockyou.service.RfSignalAnalyzer.RfAnomaly
import com.flockyou.service.RfSignalAnalyzer.RfAnomalyType
import com.flockyou.service.RfSignalAnalyzer.AnomalyConfidence
import com.flockyou.service.RfSignalAnalyzer.HiddenNetworkAnalysis
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RF Detection Handler for the Flock-You surveillance detection system.
 *
 * Handles all RF-based detection methods by analyzing WiFi scan results
 * for anomalies that indicate surveillance, jamming, or covert activity.
 *
 * Detection Methods Supported:
 * - RF_JAMMER: Sudden signal drop indicating active jamming
 * - RF_DRONE: Drone WiFi patterns (DJI, Parrot, etc.)
 * - RF_SURVEILLANCE_AREA: High camera concentration
 * - RF_SPECTRUM_ANOMALY: Unusual RF activity patterns
 * - RF_UNUSUAL_ACTIVITY: Abnormal RF patterns (high hidden network density)
 * - RF_INTERFERENCE: Environmental RF changes
 * - RF_HIDDEN_TRANSMITTER: Covert transmission detection
 *
 * Device Types:
 * - RF_JAMMER, DRONE, SURVEILLANCE_INFRASTRUCTURE
 * - RF_INTERFERENCE, RF_ANOMALY, HIDDEN_TRANSMITTER
 */
@Singleton
class RfDetectionHandler @Inject constructor() {

    companion object {
        private const val TAG = "RfDetectionHandler"

        // Detection thresholds
        private const val HIDDEN_NETWORK_SUSPICIOUS_COUNT = 10
        private const val HIDDEN_NETWORK_SUSPICIOUS_RATIO = 0.35f
        private const val SURVEILLANCE_CAMERA_MIN_COUNT = 5
        private const val STRONG_SIGNAL_THRESHOLD = -50 // dBm
        private const val SIGNAL_VARIANCE_LOW_THRESHOLD = 50f

        // Known surveillance camera OUIs
        private val SURVEILLANCE_OUIS = setOf(
            // Hikvision
            "B4:A3:82", "44:19:B6", "54:C4:15", "28:57:BE",
            // Dahua
            "E0:50:8B", "3C:EF:8C", "4C:11:BF", "A0:BD:1D",
            // Axis Communications
            "00:40:8C", "AC:CC:8E", "00:30:53",
            // Panasonic
            "00:80:F0",
            // Pelco
            "00:04:7D",
            // Amcrest
            "9C:8E:CD",
            // Vivotek
            "9C:28:B3", "00:02:D1"
        )

        // Drone manufacturer OUIs - comprehensive list
        private val DRONE_OUIS = setOf(
            // DJI (Shenzhen DJI Sciences and Technologies)
            "60:60:1F", "34:D2:62", "48:1C:B9", "60:C7:98",
            "D8:71:4D", "F0:76:1C", "F8:8A:3C", "70:4D:7B",
            "98:3E:B4", "C8:F0:9E", "40:A2:DB", "B4:E0:8C",
            "CC:50:E3", "AC:67:84", "D4:D9:19", "24:0D:C2",
            "2C:D1:46", "1C:CC:D6", "90:8D:78", "64:D4:BD",

            // Parrot SA
            "A0:14:3D", "90:03:B7", "00:12:1C", "00:26:7E",
            "00:26:7D", "90:3A:E6", "D0:3A:E3", "A0:94:69",

            // Autel Robotics
            "30:84:54", "58:D5:6E", "84:D4:7E",

            // Yuneec International
            "00:1B:C5", "00:1C:12", "1C:1B:B5", "64:1C:AE",

            // Skydio
            "84:71:27", "3C:A9:F4",

            // Holy Stone / HS (Shenzhen)
            "88:3F:4A", "84:C9:B2", "E8:65:D4",

            // Hubsan
            "48:02:2A", "78:A5:04", "A4:E4:2E",

            // Syma
            "C8:3A:35", "D4:22:3F",

            // JJRC
            "E8:AB:F3", "AC:12:2F",

            // Walkera
            "00:1E:10", "64:69:BC",

            // Eachine
            "84:F3:EB", "10:08:B1",

            // MJX
            "74:DF:BF", "D4:36:DB",

            // EHang
            "58:7A:62",

            // PowerVision
            "00:04:4B", "B4:E6:2A",

            // GoPro Karma
            "D8:96:85", "24:D9:21",

            // Intel Falcon / Aero
            "7C:D1:C3", "94:EB:CD",

            // 3D Robotics (Solo)
            "74:DA:EA", "84:CC:A8",

            // Xiaomi / FIMI
            "64:CC:2E", "78:11:DC", "B0:E2:35", "F8:A4:5F",

            // Potensic
            "CC:D2:81",

            // Snaptain
            "48:BF:6B",

            // Ruko
            "E4:5F:01",

            // DroneX Pro / Eachine rebrands
            "D4:D9:00", "B0:6E:BF"
        )

        // Drone SSID patterns
        private val DRONE_SSID_PATTERNS = listOf(
            Regex("(?i)^dji[-_].+"),
            Regex("(?i)^phantom[-_]?[0-9].*"),
            Regex("(?i)^mavic[-_]?(pro|air|mini|[0-9]).*"),
            Regex("(?i)^inspire[-_]?[0-9].*"),
            Regex("(?i)^tello[-_]?[0-9a-f]+"),
            Regex("(?i)^parrot[-_]?(anafi|bebop|disco|mambo).*"),
            Regex("(?i)^anafi[-_]?.*"),
            Regex("(?i)^skydio[-_]?[0-9].*"),
            Regex("(?i)^autel[-_]?(evo|robotics).*"),
            Regex("(?i)^yuneec[-_]?(typhoon|mantis|breeze).*")
        )
    }

    // Current location for geo-tagging detections
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    /**
     * Handle RF detection from context and produce detections.
     *
     * @param context The RfDetectionContext containing scan results and analysis data
     * @return List of Detection objects for any RF anomalies found
     */
    fun handleDetection(context: RfDetectionContext): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Process each pre-computed anomaly
        context.anomalies.forEach { anomaly ->
            val detection = convertAnomalyToDetection(anomaly, context)
            detections.add(detection)
        }

        // Check for additional patterns in WiFi scan results
        context.wifiScanResults?.let { results ->
            detections.addAll(analyzeWifiScanForRfAnomalies(results, context))
        }

        return detections
    }

    /**
     * Get the protocol this handler supports.
     */
    fun getProtocol(): DetectionProtocol = DetectionProtocol.RF

    /**
     * Get the device types this handler can detect.
     */
    fun getSupportedDeviceTypes(): Set<DeviceType> = setOf(
        DeviceType.RF_JAMMER,
        DeviceType.DRONE,
        DeviceType.SURVEILLANCE_INFRASTRUCTURE,
        DeviceType.RF_INTERFERENCE,
        DeviceType.RF_ANOMALY,
        DeviceType.HIDDEN_TRANSMITTER
    )

    /**
     * Get the supported detection methods.
     */
    fun getSupportedMethods(): Set<DetectionMethod> = setOf(
        DetectionMethod.RF_JAMMER,
        DetectionMethod.RF_DRONE,
        DetectionMethod.RF_SURVEILLANCE_AREA,
        DetectionMethod.RF_SPECTRUM_ANOMALY,
        DetectionMethod.RF_UNUSUAL_ACTIVITY,
        DetectionMethod.RF_INTERFERENCE,
        DetectionMethod.RF_HIDDEN_TRANSMITTER
    )

    /**
     * Check if this handler supports the given detection method.
     */
    fun supports(method: DetectionMethod): Boolean = method in getSupportedMethods()

    /**
     * Check if a specific pattern/method is enabled.
     */
    fun isPatternEnabled(method: DetectionMethod): Boolean = method in getSupportedMethods()

    /**
     * Update location for geo-tagging detections.
     */
    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    /**
     * Calculate RF threat score for the given context.
     * This is analogous to IMSI catcher score for cellular.
     */
    fun calculateRfThreatScore(context: RfDetectionContext): Int {
        var score = 0

        // Hidden network analysis
        if (context.totalNetworks > 0) {
            val hiddenRatio = context.hiddenNetworkCount.toFloat() / context.totalNetworks
            if (hiddenRatio > 0.5f) score += 20
            else if (hiddenRatio > 0.35f) score += 10
        }

        // Suspicious patterns
        score += context.suspiciousPatterns.size * 5

        // Hidden network analysis factors
        context.hiddenNetworkAnalysis?.let { analysis ->
            if (analysis.hiddenSignalStrongerThanVisible) score += 15
            if (analysis.knownSurveillanceOuiCount > 0) score += 20
            if (analysis.simultaneousAppearance) score += 15
            if (analysis.channelConcentration) score += 10
            if (analysis.sharedOuiCount >= 5) score += 15
            else if (analysis.sharedOuiCount >= 3) score += 10
        }

        // Anomalies
        context.anomalies.forEach { anomaly ->
            score += when (anomaly.confidence) {
                AnomalyConfidence.CRITICAL -> 25
                AnomalyConfidence.HIGH -> 20
                AnomalyConfidence.MEDIUM -> 10
                AnomalyConfidence.LOW -> 5
            }
        }

        return score.coerceIn(0, 100)
    }

    /**
     * Generate an AI prompt for spectrum analysis results.
     */
    fun generateAiPrompt(context: RfDetectionContext): String {
        val sb = StringBuilder()

        sb.appendLine("Analyze this RF spectrum environment for surveillance indicators.")
        sb.appendLine()
        sb.appendLine("=== RF ENVIRONMENT SUMMARY ===")
        sb.appendLine("Total Networks: ${context.totalNetworks}")
        sb.appendLine("Hidden Networks: ${context.hiddenNetworkCount} (${
            if (context.totalNetworks > 0)
                String.format("%.1f", context.hiddenNetworkCount.toFloat() / context.totalNetworks * 100)
            else "0"
        }%)")
        sb.appendLine("Average Signal Strength: ${context.avgSignalStrength} dBm")
        sb.appendLine("Signal Variance: ${String.format("%.2f", context.signalVariance)}")
        sb.appendLine()

        if (context.suspiciousPatterns.isNotEmpty()) {
            sb.appendLine("=== SUSPICIOUS PATTERNS DETECTED ===")
            context.suspiciousPatterns.forEachIndexed { index, pattern ->
                sb.appendLine("${index + 1}. $pattern")
            }
            sb.appendLine()
        }

        context.hiddenNetworkAnalysis?.let { analysis ->
            sb.appendLine("=== HIDDEN NETWORK ANALYSIS ===")
            sb.appendLine("Hidden Signal Strength: ${analysis.hiddenAvgSignalStrength} dBm (visible: ${analysis.visibleAvgSignalStrength} dBm)")
            if (analysis.hiddenSignalStrongerThanVisible) {
                sb.appendLine("WARNING: Hidden networks have STRONGER signals than visible networks")
            }
            sb.appendLine("Signal Variance: ${String.format("%.1f", analysis.hiddenSignalVariance)}")
            sb.appendLine("Signal Clusters: ${analysis.signalClusterCount}")
            sb.appendLine("Band Distribution: 2.4GHz=${analysis.hiddenBand24Count}, 5GHz=${analysis.hiddenBand5Count}, 6GHz=${analysis.hiddenBand6Count}")
            if (analysis.channelConcentration) {
                sb.appendLine("WARNING: Hidden networks concentrated on few channels")
            }
            sb.appendLine("Unique OUI Vendors: ${analysis.uniqueOuiCount}")
            sb.appendLine("Shared OUI Count: ${analysis.sharedOuiCount}")
            if (analysis.knownSurveillanceOuiCount > 0) {
                sb.appendLine("WARNING: ${analysis.knownSurveillanceOuiCount} from known surveillance equipment vendors")
            }
            if (analysis.simultaneousAppearance) {
                sb.appendLine("WARNING: Multiple hidden networks appeared simultaneously")
            }
            sb.appendLine()
        }

        // Include anomaly details
        if (context.anomalies.isNotEmpty()) {
            sb.appendLine("=== DETECTED ANOMALIES ===")
            context.anomalies.forEachIndexed { index, anomaly ->
                sb.appendLine("${index + 1}. ${anomaly.type.displayName}")
                sb.appendLine("   Severity: ${anomaly.severity.displayName}")
                sb.appendLine("   Confidence: ${anomaly.confidence.displayName}")
                sb.appendLine("   Details: ${anomaly.description}")
                if (anomaly.contributingFactors.isNotEmpty()) {
                    sb.appendLine("   Factors:")
                    anomaly.contributingFactors.forEach { factor ->
                        sb.appendLine("   - $factor")
                    }
                }
            }
            sb.appendLine()
        }

        sb.appendLine("=== ANALYSIS REQUESTED ===")
        sb.appendLine("1. Assess the likelihood of coordinated surveillance in this RF environment")
        sb.appendLine("2. Identify the most concerning indicators")
        sb.appendLine("3. Provide specific recommendations for the user")
        sb.appendLine("4. Rate overall RF environment risk (LOW/MEDIUM/HIGH/CRITICAL)")
        sb.appendLine()
        sb.appendLine("Format response as:")
        sb.appendLine("## Assessment")
        sb.appendLine("[Your analysis]")
        sb.appendLine()
        sb.appendLine("## Risk Level: [LEVEL]")
        sb.appendLine()
        sb.appendLine("## Key Indicators")
        sb.appendLine("- [Indicator 1]")
        sb.appendLine("- [Indicator 2]")
        sb.appendLine()
        sb.appendLine("## Recommendations")
        sb.appendLine("1. [Action 1]")
        sb.appendLine("2. [Action 2]")
        sb.appendLine("3. [Action 3]")

        return sb.toString()
    }

    /**
     * Convert an RfAnomaly to a Detection object.
     */
    private fun convertAnomalyToDetection(
        anomaly: RfAnomaly,
        context: RfDetectionContext
    ): Detection {
        val detectionMethod = mapAnomalyTypeToMethod(anomaly.type)
        val deviceType = mapAnomalyTypeToDeviceType(anomaly.type)

        return Detection(
            deviceType = deviceType,
            protocol = DetectionProtocol.RF,
            detectionMethod = detectionMethod,
            deviceName = anomaly.displayName,
            macAddress = null,
            ssid = null,
            rssi = context.avgSignalStrength,
            signalStrength = rssiToSignalStrength(context.avgSignalStrength),
            latitude = anomaly.latitude ?: context.latitude ?: currentLatitude,
            longitude = anomaly.longitude ?: context.longitude ?: currentLongitude,
            threatLevel = anomaly.severity,
            threatScore = calculateThreatScore(anomaly, context),
            matchedPatterns = anomaly.contributingFactors.joinToString("; ")
        )
    }

    /**
     * Analyze WiFi scan results for RF anomalies.
     */
    private fun analyzeWifiScanForRfAnomalies(
        results: List<ScanResult>,
        context: RfDetectionContext
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Analyze for hidden network density
        analyzeHiddenNetworkDensity(results, context)?.let { detections.add(it) }

        // Analyze for surveillance camera concentration
        analyzeSurveillanceCameraConcentration(results, context)?.let { detections.add(it) }

        // Analyze for drone signals
        detections.addAll(analyzeDroneSignals(results, context))

        // Analyze for hidden transmitters
        analyzeHiddenTransmitters(results, context)?.let { detections.add(it) }

        return detections
    }

    /**
     * Analyze hidden network density for suspicious patterns.
     */
    private fun analyzeHiddenNetworkDensity(
        results: List<ScanResult>,
        context: RfDetectionContext
    ): Detection? {
        if (context.hiddenNetworkCount < HIDDEN_NETWORK_SUSPICIOUS_COUNT) return null
        if (context.totalNetworks == 0) return null

        val hiddenRatio = context.hiddenNetworkCount.toFloat() / context.totalNetworks
        if (hiddenRatio < HIDDEN_NETWORK_SUSPICIOUS_RATIO) return null

        // Calculate confidence based on multiple factors
        val confidence = calculateHiddenNetworkConfidence(context)
        if (confidence == AnomalyConfidence.LOW && context.suspiciousPatterns.isEmpty()) return null

        val threatLevel = when (confidence) {
            AnomalyConfidence.CRITICAL -> ThreatLevel.CRITICAL
            AnomalyConfidence.HIGH -> ThreatLevel.HIGH
            AnomalyConfidence.MEDIUM -> ThreatLevel.MEDIUM
            AnomalyConfidence.LOW -> ThreatLevel.LOW
        }

        val factors = buildHiddenNetworkFactors(context, hiddenRatio)

        return Detection(
            deviceType = DeviceType.RF_ANOMALY,
            protocol = DetectionProtocol.RF,
            detectionMethod = DetectionMethod.RF_UNUSUAL_ACTIVITY,
            deviceName = "Hidden Network Anomaly",
            rssi = context.avgSignalStrength,
            signalStrength = rssiToSignalStrength(context.avgSignalStrength),
            latitude = context.latitude ?: currentLatitude,
            longitude = context.longitude ?: currentLongitude,
            threatLevel = threatLevel,
            threatScore = calculateHiddenNetworkThreatScore(context, confidence),
            matchedPatterns = factors.joinToString("; ")
        )
    }

    /**
     * Analyze for surveillance camera concentration.
     */
    private fun analyzeSurveillanceCameraConcentration(
        results: List<ScanResult>,
        context: RfDetectionContext
    ): Detection? {
        val cameraCount = results.count { result ->
            val oui = result.BSSID?.uppercase()?.take(8) ?: ""
            oui in SURVEILLANCE_OUIS
        }

        if (cameraCount < SURVEILLANCE_CAMERA_MIN_COUNT) return null

        val threatLevel = when {
            cameraCount >= 15 -> ThreatLevel.HIGH
            cameraCount >= 10 -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }

        return Detection(
            deviceType = DeviceType.SURVEILLANCE_INFRASTRUCTURE,
            protocol = DetectionProtocol.RF,
            detectionMethod = DetectionMethod.RF_SURVEILLANCE_AREA,
            deviceName = "Surveillance Area ($cameraCount cameras)",
            rssi = context.avgSignalStrength,
            signalStrength = rssiToSignalStrength(context.avgSignalStrength),
            latitude = context.latitude ?: currentLatitude,
            longitude = context.longitude ?: currentLongitude,
            threatLevel = threatLevel,
            threatScore = 50 + (cameraCount * 3).coerceAtMost(45),
            matchedPatterns = "$cameraCount surveillance camera networks detected; Known camera OUIs: Hikvision, Dahua, Axis, etc."
        )
    }

    /**
     * Analyze for drone WiFi signals.
     */
    private fun analyzeDroneSignals(
        results: List<ScanResult>,
        context: RfDetectionContext
    ): List<Detection> {
        val droneDetections = mutableListOf<Detection>()

        for (result in results) {
            @Suppress("DEPRECATION")
            val ssid = result.SSID ?: ""
            val bssid = result.BSSID?.uppercase() ?: continue
            val oui = bssid.take(8)

            val isOuiMatch = oui in DRONE_OUIS
            val isSsidMatch = DRONE_SSID_PATTERNS.any { it.matches(ssid) }

            if (isOuiMatch || isSsidMatch) {
                val manufacturer = identifyDroneManufacturer(oui, ssid)
                val confidence = if (isOuiMatch) AnomalyConfidence.HIGH else AnomalyConfidence.MEDIUM

                droneDetections.add(
                    Detection(
                        deviceType = DeviceType.DRONE,
                        protocol = DetectionProtocol.WIFI,
                        detectionMethod = DetectionMethod.RF_DRONE,
                        deviceName = "$manufacturer Drone",
                        macAddress = bssid,
                        ssid = ssid,
                        rssi = result.level,
                        signalStrength = rssiToSignalStrength(result.level),
                        latitude = context.latitude ?: currentLatitude,
                        longitude = context.longitude ?: currentLongitude,
                        threatLevel = ThreatLevel.MEDIUM,
                        threatScore = if (confidence == AnomalyConfidence.HIGH) 75 else 60,
                        manufacturer = manufacturer,
                        matchedPatterns = buildDroneMatchPatterns(isOuiMatch, isSsidMatch, ssid, manufacturer)
                    )
                )
            }
        }

        return droneDetections
    }

    /**
     * Analyze for hidden transmitters.
     */
    private fun analyzeHiddenTransmitters(
        results: List<ScanResult>,
        context: RfDetectionContext
    ): Detection? {
        // Look for hidden networks with unusually strong signals
        val hiddenStrongSignals = results.filter { result ->
            @Suppress("DEPRECATION")
            val ssid = result.SSID ?: ""
            ssid.isEmpty() && result.level > STRONG_SIGNAL_THRESHOLD
        }

        if (hiddenStrongSignals.size < 3) return null

        // Check if signals are clustered (same hardware)
        val signalLevels = hiddenStrongSignals.map { it.level }
        val avgSignal = signalLevels.average()
        val variance = signalLevels.map { (it - avgSignal) * (it - avgSignal) }.average()

        // Low variance with strong signals suggests coordinated hidden transmitters
        if (variance > SIGNAL_VARIANCE_LOW_THRESHOLD) return null

        return Detection(
            deviceType = DeviceType.HIDDEN_TRANSMITTER,
            protocol = DetectionProtocol.RF,
            detectionMethod = DetectionMethod.RF_HIDDEN_TRANSMITTER,
            deviceName = "Possible Hidden Transmitters",
            rssi = avgSignal.toInt(),
            signalStrength = rssiToSignalStrength(avgSignal.toInt()),
            latitude = context.latitude ?: currentLatitude,
            longitude = context.longitude ?: currentLongitude,
            threatLevel = ThreatLevel.HIGH,
            threatScore = 75,
            matchedPatterns = "${hiddenStrongSignals.size} strong hidden signals detected; Low variance (${String.format("%.1f", variance)}) suggests same hardware; Avg signal: ${avgSignal.toInt()} dBm"
        )
    }

    /**
     * Map RfAnomalyType to DetectionMethod.
     */
    private fun mapAnomalyTypeToMethod(type: RfAnomalyType): DetectionMethod = when (type) {
        RfAnomalyType.POSSIBLE_JAMMER -> DetectionMethod.RF_JAMMER
        RfAnomalyType.DRONE_DETECTED -> DetectionMethod.RF_DRONE
        RfAnomalyType.SURVEILLANCE_AREA -> DetectionMethod.RF_SURVEILLANCE_AREA
        RfAnomalyType.SPECTRUM_ANOMALY -> DetectionMethod.RF_SPECTRUM_ANOMALY
        RfAnomalyType.UNUSUAL_ACTIVITY -> DetectionMethod.RF_UNUSUAL_ACTIVITY
        RfAnomalyType.SIGNAL_INTERFERENCE -> DetectionMethod.RF_INTERFERENCE
        RfAnomalyType.HIDDEN_TRANSMITTER -> DetectionMethod.RF_HIDDEN_TRANSMITTER
    }

    /**
     * Map RfAnomalyType to DeviceType.
     */
    private fun mapAnomalyTypeToDeviceType(type: RfAnomalyType): DeviceType = when (type) {
        RfAnomalyType.POSSIBLE_JAMMER -> DeviceType.RF_JAMMER
        RfAnomalyType.DRONE_DETECTED -> DeviceType.DRONE
        RfAnomalyType.SURVEILLANCE_AREA -> DeviceType.SURVEILLANCE_INFRASTRUCTURE
        RfAnomalyType.SIGNAL_INTERFERENCE -> DeviceType.RF_INTERFERENCE
        RfAnomalyType.SPECTRUM_ANOMALY -> DeviceType.RF_ANOMALY
        RfAnomalyType.UNUSUAL_ACTIVITY -> DeviceType.RF_ANOMALY
        RfAnomalyType.HIDDEN_TRANSMITTER -> DeviceType.HIDDEN_TRANSMITTER
    }

    /**
     * Calculate threat score based on anomaly and context.
     */
    private fun calculateThreatScore(anomaly: RfAnomaly, context: RfDetectionContext): Int {
        var score = anomaly.type.baseScore

        // Adjust based on confidence
        score += when (anomaly.confidence) {
            AnomalyConfidence.CRITICAL -> 15
            AnomalyConfidence.HIGH -> 10
            AnomalyConfidence.MEDIUM -> 5
            AnomalyConfidence.LOW -> 0
        }

        // Adjust based on context
        if (context.hiddenNetworkCount > 20) score += 5
        if (context.signalVariance < SIGNAL_VARIANCE_LOW_THRESHOLD) score += 5
        if (context.suspiciousPatterns.size > 3) score += 5

        return score.coerceIn(0, 100)
    }

    /**
     * Calculate confidence for hidden network anomalies.
     */
    private fun calculateHiddenNetworkConfidence(context: RfDetectionContext): AnomalyConfidence {
        val analysis = context.hiddenNetworkAnalysis ?: return AnomalyConfidence.LOW

        var suspicionScore = 0

        // Hidden ratio factor
        val hiddenRatio = if (context.totalNetworks > 0)
            context.hiddenNetworkCount.toFloat() / context.totalNetworks
        else 0f
        if (hiddenRatio > 0.5f) suspicionScore += 2
        else if (hiddenRatio > 0.35f) suspicionScore += 1

        // Signal strength comparison
        if (analysis.hiddenSignalStrongerThanVisible) suspicionScore += 2

        // Low variance (same hardware)
        if (analysis.hiddenSignalVariance < SIGNAL_VARIANCE_LOW_THRESHOLD && analysis.signalClusterCount <= 2) {
            suspicionScore += 2
        }

        // Shared OUI
        if (analysis.sharedOuiCount >= 5) suspicionScore += 2
        else if (analysis.sharedOuiCount >= 3) suspicionScore += 1

        // Surveillance vendors
        if (analysis.knownSurveillanceOuiCount > 0) suspicionScore += 2

        // Channel concentration
        if (analysis.channelConcentration) suspicionScore += 1

        // Simultaneous appearance
        if (analysis.simultaneousAppearance) suspicionScore += 2

        return when {
            suspicionScore >= 8 -> AnomalyConfidence.HIGH
            suspicionScore >= 5 -> AnomalyConfidence.MEDIUM
            else -> AnomalyConfidence.LOW
        }
    }

    /**
     * Calculate threat score for hidden network anomalies.
     */
    private fun calculateHiddenNetworkThreatScore(
        context: RfDetectionContext,
        confidence: AnomalyConfidence
    ): Int {
        var score = 50

        score += when (confidence) {
            AnomalyConfidence.CRITICAL -> 40
            AnomalyConfidence.HIGH -> 30
            AnomalyConfidence.MEDIUM -> 15
            AnomalyConfidence.LOW -> 0
        }

        // Additional factors
        context.hiddenNetworkAnalysis?.let { analysis ->
            if (analysis.hiddenSignalStrongerThanVisible) score += 5
            if (analysis.knownSurveillanceOuiCount > 0) score += 10
            if (analysis.simultaneousAppearance) score += 5
        }

        return score.coerceIn(0, 100)
    }

    /**
     * Build contributing factors list for hidden network detection.
     */
    private fun buildHiddenNetworkFactors(context: RfDetectionContext, hiddenRatio: Float): List<String> {
        val factors = mutableListOf<String>()

        factors.add("${context.hiddenNetworkCount} hidden networks (${String.format("%.0f", hiddenRatio * 100)}% of total)")
        factors.add("${context.totalNetworks} total networks")

        context.hiddenNetworkAnalysis?.let { analysis ->
            factors.add("Hidden avg signal: ${analysis.hiddenAvgSignalStrength} dBm")

            if (analysis.hiddenSignalStrongerThanVisible) {
                factors.add("Hidden signals STRONGER than visible")
            }

            if (analysis.hiddenSignalVariance < SIGNAL_VARIANCE_LOW_THRESHOLD) {
                factors.add("Low signal variance (same hardware likely)")
            }

            if (analysis.sharedOuiCount >= 3) {
                factors.add("${analysis.sharedOuiCount} networks share same vendor")
            }

            if (analysis.knownSurveillanceOuiCount > 0) {
                factors.add("${analysis.knownSurveillanceOuiCount} from surveillance vendors")
            }

            if (analysis.channelConcentration) {
                factors.add("Channel concentration detected")
            }

            if (analysis.simultaneousAppearance) {
                factors.add("Networks appeared simultaneously")
            }
        }

        return factors
    }

    /**
     * Identify drone manufacturer from OUI and SSID.
     */
    private fun identifyDroneManufacturer(oui: String, ssid: String): String {
        // DJI OUIs
        val djiOuis = setOf(
            "60:60:1F", "34:D2:62", "48:1C:B9", "60:C7:98",
            "D8:71:4D", "F0:76:1C", "F8:8A:3C", "70:4D:7B",
            "98:3E:B4", "C8:F0:9E", "40:A2:DB", "B4:E0:8C",
            "CC:50:E3", "AC:67:84", "D4:D9:19", "24:0D:C2",
            "2C:D1:46", "1C:CC:D6", "90:8D:78", "64:D4:BD"
        )
        // Parrot OUIs
        val parrotOuis = setOf(
            "A0:14:3D", "90:03:B7", "00:12:1C", "00:26:7E",
            "00:26:7D", "90:3A:E6", "D0:3A:E3", "A0:94:69"
        )
        // Autel OUIs
        val autelOuis = setOf("30:84:54", "58:D5:6E", "84:D4:7E")
        // Yuneec OUIs
        val yuneecOuis = setOf("00:1B:C5", "00:1C:12", "1C:1B:B5", "64:1C:AE")
        // Skydio OUIs
        val skydioOuis = setOf("84:71:27", "3C:A9:F4")
        // Holy Stone OUIs
        val holyStoneOuis = setOf("88:3F:4A", "84:C9:B2", "E8:65:D4")
        // Hubsan OUIs
        val hubsanOuis = setOf("48:02:2A", "78:A5:04", "A4:E4:2E")
        // Xiaomi/FIMI OUIs
        val xiaomiOuis = setOf("64:CC:2E", "78:11:DC", "B0:E2:35", "F8:A4:5F")
        // GoPro Karma OUIs
        val goProOuis = setOf("D8:96:85", "24:D9:21")
        // 3DR OUIs
        val threedrOuis = setOf("74:DA:EA", "84:CC:A8")

        return when {
            djiOuis.any { oui.startsWith(it) } -> "DJI"
            parrotOuis.any { oui.startsWith(it) } -> "Parrot"
            autelOuis.any { oui.startsWith(it) } -> "Autel"
            yuneecOuis.any { oui.startsWith(it) } -> "Yuneec"
            skydioOuis.any { oui.startsWith(it) } -> "Skydio"
            holyStoneOuis.any { oui.startsWith(it) } -> "Holy Stone"
            hubsanOuis.any { oui.startsWith(it) } -> "Hubsan"
            xiaomiOuis.any { oui.startsWith(it) } -> "Xiaomi/FIMI"
            goProOuis.any { oui.startsWith(it) } -> "GoPro"
            threedrOuis.any { oui.startsWith(it) } -> "3D Robotics"
            // SSID-based fallback detection
            ssid.lowercase().contains("skydio") -> "Skydio"
            ssid.lowercase().contains("autel") || ssid.lowercase().contains("evo") -> "Autel"
            ssid.lowercase().contains("yuneec") || ssid.lowercase().contains("typhoon") -> "Yuneec"
            ssid.lowercase().contains("hubsan") -> "Hubsan"
            ssid.lowercase().contains("holy") && ssid.lowercase().contains("stone") -> "Holy Stone"
            ssid.lowercase().contains("fimi") -> "Xiaomi/FIMI"
            ssid.lowercase().contains("karma") -> "GoPro"
            ssid.lowercase().contains("potensic") -> "Potensic"
            ssid.lowercase().contains("snaptain") -> "Snaptain"
            ssid.lowercase().contains("ruko") -> "Ruko"
            ssid.lowercase().contains("eachine") -> "Eachine"
            ssid.lowercase().contains("syma") -> "Syma"
            ssid.lowercase().contains("mjx") -> "MJX"
            ssid.lowercase().contains("jjrc") -> "JJRC"
            else -> "Unknown"
        }
    }

    /**
     * Build match patterns description for drone detection.
     */
    private fun buildDroneMatchPatterns(
        isOuiMatch: Boolean,
        isSsidMatch: Boolean,
        ssid: String,
        manufacturer: String
    ): String {
        val patterns = mutableListOf<String>()
        patterns.add("Manufacturer: $manufacturer")
        if (isOuiMatch) patterns.add("MAC address matches drone OUI")
        if (isSsidMatch) patterns.add("SSID '$ssid' matches drone pattern")
        return patterns.joinToString("; ")
    }
}

/**
 * Detection context for RF analysis.
 *
 * Contains all relevant data from WiFi/RF scans needed for anomaly detection.
 * Implements DetectionContext interface for compatibility with handler framework.
 */
data class RfDetectionContext(
    /** Total number of networks detected in scan */
    val totalNetworks: Int,

    /** Number of hidden (no SSID) networks */
    val hiddenNetworkCount: Int,

    /** Average signal strength across all networks (dBm) */
    val avgSignalStrength: Int,

    /** Signal variance across all networks */
    val signalVariance: Float,

    /** List of suspicious patterns identified */
    val suspiciousPatterns: List<String> = emptyList(),

    /** Current location latitude */
    val latitude: Double? = null,

    /** Current location longitude */
    val longitude: Double? = null,

    /** Raw WiFi scan results for detailed analysis */
    val wifiScanResults: List<ScanResult>? = null,

    /** Pre-computed anomalies from RfSignalAnalyzer */
    val anomalies: List<RfAnomaly> = emptyList(),

    /** Detailed hidden network analysis */
    val hiddenNetworkAnalysis: HiddenNetworkAnalysis? = null,

    /** Timestamp of the scan */
    val timestamp: Long = System.currentTimeMillis()
)

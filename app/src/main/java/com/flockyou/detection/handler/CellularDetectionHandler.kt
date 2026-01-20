package com.flockyou.detection.handler

import android.util.Log
import com.flockyou.data.CellularPattern
import com.flockyou.data.CellularThresholds
import com.flockyou.data.DetectionSettingsRepository
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.rssiToSignalStrength
import com.flockyou.service.CellularMonitor
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detection Handler for Cellular Network Anomalies
 *
 * Handles detection and analysis of cellular network anomalies that may indicate
 * IMSI catchers (StingRay), cell site simulators, or other cellular surveillance devices.
 *
 * Detection Methods:
 * - CELL_ENCRYPTION_DOWNGRADE: 5G/4G to 2G downgrade (weak/no encryption)
 * - CELL_SUSPICIOUS_NETWORK: Invalid or test MCC-MNC codes
 * - CELL_TOWER_CHANGE: Unexpected cell tower changes while stationary
 * - CELL_RAPID_SWITCHING: Abnormal tower switching patterns
 * - CELL_SIGNAL_ANOMALY: Sudden signal strength changes
 * - CELL_LAC_TAC_ANOMALY: Location area code changes without cell change
 *
 * Primarily targets STINGRAY_IMSI device type detections.
 */
@Singleton
class CellularDetectionHandler @Inject constructor(
    private val detectionSettingsRepository: DetectionSettingsRepository
) : CellularDetectionHandlerInterface<CellularDetectionContext> {

    companion object {
        private const val TAG = "CellularDetectionHandler"

        // Known suspicious test/invalid MCC-MNC codes
        private val SUSPICIOUS_MCC_MNC = setOf(
            "001-01", "001-00", "001-02", // ITU test networks
            "999-99", "999-01",           // Reserved test networks
            "000-00"                       // Invalid
        )

        // IMSI catcher score thresholds
        private const val IMSI_CRITICAL_THRESHOLD = 70
        private const val IMSI_HIGH_THRESHOLD = 50
    }

    private var lastDetectionTime = mutableMapOf<DetectionMethod, Long>()

    /**
     * Check if this handler can process the given context.
     */
    override fun canHandle(context: CellularDetectionContext): Boolean {
        return context.anomalyType in listOf(
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE,
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK,
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE,
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE,
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING,
            CellularMonitor.AnomalyType.SIGNAL_SPIKE,
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY,
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA
        )
    }

    /**
     * Process a CellularDetectionContext and return a Detection if warranted.
     */
    override suspend fun handle(context: CellularDetectionContext): Detection? {
        val settings = detectionSettingsRepository.settings.first()

        // Check if cellular detection is enabled globally
        if (!settings.enableCellularDetection) {
            Log.d(TAG, "Cellular detection disabled globally")
            return null
        }

        val detectionMethod = mapAnomalyTypeToMethod(context.anomalyType)

        // Check if this detection method is enabled
        val pattern = mapMethodToPattern(detectionMethod)
        if (pattern != null && pattern !in settings.enabledCellularPatterns) {
            Log.d(TAG, "Detection method ${detectionMethod.name} is disabled, skipping")
            return null
        }

        // Rate limiting check
        val thresholds = settings.cellularThresholds
        val now = System.currentTimeMillis()
        val lastTime = lastDetectionTime[detectionMethod] ?: 0L
        if (now - lastTime < thresholds.minAnomalyIntervalMs) {
            Log.d(TAG, "Rate limiting detection ${detectionMethod.name}")
            return null
        }
        lastDetectionTime[detectionMethod] = now

        // Check severity threshold - only HIGH and CRITICAL generate detections by default
        if (context.severity != null && context.severity !in listOf(ThreatLevel.HIGH, ThreatLevel.CRITICAL)) {
            if (context.severity == ThreatLevel.MEDIUM) {
                if (!meetsThresholds(context, thresholds)) {
                    Log.d(TAG, "Anomaly doesn't meet thresholds: ${context.anomalyType}")
                    return null
                }
            } else {
                Log.d(TAG, "Anomaly severity too low: ${context.severity}")
                return null
            }
        }

        // Build the detection
        val detection = buildDetection(context, detectionMethod)

        Log.i(TAG, "Created detection: ${detection.deviceName}, threat=${detection.threatLevel}, " +
            "IMSI score=${context.imsiCatcherScore}%")

        return detection
    }

    /**
     * Generate an enriched AI prompt for cellular anomaly analysis.
     */
    override fun generateAiPrompt(context: CellularDetectionContext, detection: Detection): String {
        return buildString {
            appendLine("=== CELLULAR ANOMALY ANALYSIS ===")
            appendLine()

            // Header info
            appendLine("IMSI Catcher Likelihood: ${context.imsiCatcherScore}%")
            appendLine("Detection Method: ${mapAnomalyTypeToMethod(context.anomalyType).displayName}")
            appendLine("Confidence: ${context.confidence?.displayName ?: "Unknown"}")
            appendLine("Severity: ${context.severity?.displayName ?: "Unknown"}")
            appendLine()

            // Encryption analysis
            appendLine("== ENCRYPTION STATUS ==")
            appendLine("Current Encryption: ${context.encryptionType.displayName}")
            context.previousNetworkType?.let {
                appendLine("Previous Network: $it")
                if (context.encryptionType == EncryptionType.WEAK_2G ||
                    context.encryptionType == EncryptionType.NONE) {
                    appendLine("WARNING: Encryption downgrade detected!")
                    appendLine("Vulnerability: ${getEncryptionVulnerability(context.encryptionType)}")
                }
            }
            appendLine()

            // Network context
            appendLine("== NETWORK CONTEXT ==")
            appendLine("Network Type: ${context.networkType}")
            appendLine("MCC-MNC: ${context.mcc}-${context.mnc}")
            appendLine("Cell ID: ${context.cellId}")
            context.previousCellId?.let { appendLine("Previous Cell: $it") }
            context.lac?.let { appendLine("LAC: $it") }
            context.tac?.let { appendLine("TAC: $it") }
            appendLine("Roaming: ${if (context.isRoaming) "Yes" else "No"}")
            appendLine()

            // Signal analysis
            appendLine("== SIGNAL ANALYSIS ==")
            appendLine("Signal Strength: ${context.signalStrength} dBm (${getSignalQuality(context.signalStrength)})")
            context.previousSignalStrength?.let { prev ->
                val delta = context.signalStrength - prev
                appendLine("Signal Delta: ${if (delta >= 0) "+" else ""}$delta dBm")
            }
            appendLine()

            // Movement analysis
            context.movementType?.let { movement ->
                appendLine("== MOVEMENT ANALYSIS ==")
                appendLine("Movement Type: ${movement.displayName}")
                context.speedKmh?.let { appendLine("Speed: ${String.format("%.1f", it)} km/h") }
                context.distanceMeters?.let { appendLine("Distance: ${String.format("%.1f", it)} m") }
                if (movement == MovementType.IMPOSSIBLE) {
                    appendLine("CRITICAL: Impossible movement detected - potential spoofing!")
                }
                appendLine()
            }

            // Cell trust analysis
            context.cellTrustScore?.let { trust ->
                appendLine("== CELL TRUST ANALYSIS ==")
                appendLine("Trust Score: $trust%")
                appendLine("Status: ${getTrustStatus(trust)}")
                context.cellSeenCount?.let { appendLine("Times Seen: $it") }
                appendLine()
            }

            // Contributing factors
            if (context.contributingFactors.isNotEmpty()) {
                appendLine("== CONTRIBUTING FACTORS ==")
                context.contributingFactors.forEach { factor ->
                    appendLine("- $factor")
                }
                appendLine()
            }

            // IMSI catcher signature analysis
            appendLine("== IMSI CATCHER SIGNATURE ==")
            appendLine("Score: ${context.imsiCatcherScore}%")
            appendLine("Assessment: ${getImsiAssessment(context.imsiCatcherScore)}")
            appendLine()

            // Recommendations
            appendLine("== RECOMMENDATIONS ==")
            appendLine(getRecommendations(context))
        }
    }

    /**
     * Convert a CellularAnomaly directly to Detection.
     */
    suspend fun convertAnomalyToDetection(anomaly: CellularMonitor.CellularAnomaly): Detection? {
        // Build context from anomaly
        val context = CellularDetectionContext(
            anomalyType = anomaly.type,
            mcc = anomaly.mccMnc?.split("-")?.getOrNull(0),
            mnc = anomaly.mccMnc?.split("-")?.getOrNull(1),
            cellId = anomaly.cellId?.toLong(),
            previousCellId = anomaly.previousCellId?.toLong(),
            lac = null, // Not available directly from anomaly
            tac = null,
            signalStrength = anomaly.signalStrength,
            previousSignalStrength = anomaly.previousSignalStrength,
            networkType = anomaly.networkType,
            previousNetworkType = anomaly.previousNetworkType,
            encryptionType = mapNetworkTypeToEncryption(anomaly.networkType),
            isRoaming = false, // Not available directly
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            timestamp = anomaly.timestamp,
            imsiCatcherScore = calculateImsiScoreFromAnomaly(anomaly),
            contributingFactors = anomaly.contributingFactors,
            confidence = anomaly.confidence,
            severity = anomaly.severity
        )

        return handle(context)
    }

    /**
     * Calculate IMSI catcher likelihood score (0-100).
     */
    fun calculateImsiCatcherScore(context: CellularDetectionContext): Int {
        var score = 0

        // Base score from anomaly type
        score += getAnomalyBaseScore(context.anomalyType)

        // Encryption downgrade is highly suspicious
        if (context.encryptionType == EncryptionType.WEAK_2G ||
            context.encryptionType == EncryptionType.NONE) {
            score += 25
        }

        // Signal spike with tower change
        context.previousSignalStrength?.let { prev ->
            val delta = context.signalStrength - prev
            if (delta > 20) { // signalSpikeThreshold default
                score += 20
            }
        }

        // Unknown/untrusted cell
        if (context.cellTrustScore != null && context.cellTrustScore < 30) {
            score += 15
        }

        // Stationary but cell changed
        if (context.movementType == MovementType.STATIONARY &&
            context.cellId != context.previousCellId) {
            score += 15
        }

        // Impossible movement speed
        if (context.movementType == MovementType.IMPOSSIBLE) {
            score += 20
        }

        // Test network MCC-MNC
        val mccMnc = "${context.mcc}-${context.mnc}"
        if (mccMnc in SUSPICIOUS_MCC_MNC) {
            score += 30
        }

        return score.coerceIn(0, 100)
    }

    // ==================== Private Helper Methods ====================

    private fun meetsThresholds(context: CellularDetectionContext, thresholds: CellularThresholds): Boolean {
        // For MEDIUM severity, check if IMSI score is high enough
        return context.imsiCatcherScore >= IMSI_HIGH_THRESHOLD
    }

    private fun buildDetection(context: CellularDetectionContext, method: DetectionMethod): Detection {
        val anomalyEmoji = getAnomalyEmoji(context.anomalyType)
        val anomalyName = getAnomalyDisplayName(context.anomalyType)
        val deviceName = "$anomalyEmoji $anomalyName"

        val threatLevel = determineThreatLevel(context)
        val threatScore = context.imsiCatcherScore

        // Build matched patterns string from contributing factors
        val matchedPatterns = context.contributingFactors.joinToString(", ")

        return Detection(
            id = UUID.randomUUID().toString(),
            timestamp = context.timestamp,
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = method,
            deviceType = DeviceType.STINGRAY_IMSI,
            deviceName = deviceName,
            macAddress = null,
            ssid = null,
            rssi = context.signalStrength,
            signalStrength = rssiToSignalStrength(context.signalStrength),
            latitude = context.latitude,
            longitude = context.longitude,
            threatLevel = threatLevel,
            threatScore = threatScore,
            manufacturer = "Cell: ${context.cellId ?: "Unknown"}",
            firmwareVersion = null,
            serviceUuids = null,
            matchedPatterns = matchedPatterns,
            rawData = buildRawDataString(context),
            isActive = true,
            seenCount = 1,
            lastSeenTimestamp = context.timestamp
        )
    }

    private fun buildRawDataString(context: CellularDetectionContext): String {
        return buildString {
            appendLine("MCC-MNC: ${context.mcc}-${context.mnc}")
            appendLine("Cell ID: ${context.cellId}")
            appendLine("Network: ${context.networkType}")
            appendLine("Signal: ${context.signalStrength} dBm")
            appendLine("Encryption: ${context.encryptionType.displayName}")
            appendLine("IMSI Score: ${context.imsiCatcherScore}%")
            context.movementType?.let { appendLine("Movement: ${it.displayName}") }
        }
    }

    private fun determineThreatLevel(context: CellularDetectionContext): ThreatLevel {
        // Use provided severity if available
        context.severity?.let { return it }

        // Calculate based on IMSI score and confidence
        return when {
            context.imsiCatcherScore >= IMSI_CRITICAL_THRESHOLD -> ThreatLevel.CRITICAL
            context.imsiCatcherScore >= IMSI_HIGH_THRESHOLD -> ThreatLevel.HIGH
            context.confidence == CellularMonitor.AnomalyConfidence.CRITICAL -> ThreatLevel.CRITICAL
            context.confidence == CellularMonitor.AnomalyConfidence.HIGH -> ThreatLevel.HIGH
            context.confidence == CellularMonitor.AnomalyConfidence.MEDIUM -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }
    }

    private fun mapAnomalyTypeToMethod(type: CellularMonitor.AnomalyType): DetectionMethod {
        return when (type) {
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE -> DetectionMethod.CELL_ENCRYPTION_DOWNGRADE
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK -> DetectionMethod.CELL_SUSPICIOUS_NETWORK
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE -> DetectionMethod.CELL_TOWER_CHANGE
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING -> DetectionMethod.CELL_RAPID_SWITCHING
            CellularMonitor.AnomalyType.SIGNAL_SPIKE -> DetectionMethod.CELL_SIGNAL_ANOMALY
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY -> DetectionMethod.CELL_LAC_TAC_ANOMALY
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> DetectionMethod.CELL_TOWER_CHANGE
        }
    }

    private fun mapMethodToPattern(method: DetectionMethod): CellularPattern? {
        return when (method) {
            DetectionMethod.CELL_ENCRYPTION_DOWNGRADE -> CellularPattern.ENCRYPTION_DOWNGRADE
            DetectionMethod.CELL_SUSPICIOUS_NETWORK -> CellularPattern.SUSPICIOUS_NETWORK_ID
            DetectionMethod.CELL_TOWER_CHANGE -> CellularPattern.CELL_ID_CHANGE
            DetectionMethod.CELL_RAPID_SWITCHING -> CellularPattern.RAPID_CELL_SWITCHING
            DetectionMethod.CELL_SIGNAL_ANOMALY -> CellularPattern.SIGNAL_SPIKE
            DetectionMethod.CELL_LAC_TAC_ANOMALY -> CellularPattern.LAC_TAC_ANOMALY
            else -> null
        }
    }

    private fun getAnomalyBaseScore(type: CellularMonitor.AnomalyType): Int {
        return when (type) {
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK -> 95
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE -> 80
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> 60
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE -> 50
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING -> 40
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY -> 35
            CellularMonitor.AnomalyType.SIGNAL_SPIKE -> 30
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE -> 20
        }
    }

    private fun getAnomalyEmoji(type: CellularMonitor.AnomalyType): String {
        return when (type) {
            CellularMonitor.AnomalyType.SIGNAL_SPIKE -> "📶"
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE -> "🗼"
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE -> "🔓"
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK -> "⚠️"
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> "❓"
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING -> "🔄"
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY -> "📍"
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE -> "🚫"
        }
    }

    private fun getAnomalyDisplayName(type: CellularMonitor.AnomalyType): String {
        return when (type) {
            CellularMonitor.AnomalyType.SIGNAL_SPIKE -> "Sudden Signal Spike"
            CellularMonitor.AnomalyType.CELL_TOWER_CHANGE -> "Cell Tower Change"
            CellularMonitor.AnomalyType.ENCRYPTION_DOWNGRADE -> "Encryption Downgrade"
            CellularMonitor.AnomalyType.SUSPICIOUS_NETWORK -> "Suspicious Network ID"
            CellularMonitor.AnomalyType.UNKNOWN_CELL_FAMILIAR_AREA -> "Unknown Cell in Familiar Area"
            CellularMonitor.AnomalyType.RAPID_CELL_SWITCHING -> "Rapid Cell Switching"
            CellularMonitor.AnomalyType.LAC_TAC_ANOMALY -> "Location Area Anomaly"
            CellularMonitor.AnomalyType.STATIONARY_CELL_CHANGE -> "Cell Changed While Stationary"
        }
    }

    private fun mapNetworkTypeToEncryption(networkType: String): EncryptionType {
        return when {
            networkType.contains("5G", ignoreCase = true) ||
            networkType.contains("NR", ignoreCase = true) -> EncryptionType.STRONG_5G_LTE
            networkType.contains("4G", ignoreCase = true) ||
            networkType.contains("LTE", ignoreCase = true) -> EncryptionType.STRONG_5G_LTE
            networkType.contains("3G", ignoreCase = true) ||
            networkType.contains("UMTS", ignoreCase = true) ||
            networkType.contains("HSPA", ignoreCase = true) -> EncryptionType.MODERATE_3G
            networkType.contains("2G", ignoreCase = true) ||
            networkType.contains("GSM", ignoreCase = true) ||
            networkType.contains("EDGE", ignoreCase = true) ||
            networkType.contains("GPRS", ignoreCase = true) -> EncryptionType.WEAK_2G
            else -> EncryptionType.UNKNOWN
        }
    }

    private fun calculateImsiScoreFromAnomaly(anomaly: CellularMonitor.CellularAnomaly): Int {
        var score = getAnomalyBaseScore(anomaly.type)

        // Boost score based on confidence
        when (anomaly.confidence) {
            CellularMonitor.AnomalyConfidence.CRITICAL -> score += 20
            CellularMonitor.AnomalyConfidence.HIGH -> score += 10
            CellularMonitor.AnomalyConfidence.MEDIUM -> score += 5
            CellularMonitor.AnomalyConfidence.LOW -> { /* no boost */ }
        }

        // Boost for multiple contributing factors
        if (anomaly.contributingFactors.size >= 4) score += 15
        else if (anomaly.contributingFactors.size >= 3) score += 10
        else if (anomaly.contributingFactors.size >= 2) score += 5

        return score.coerceIn(0, 100)
    }

    private fun getSignalQuality(signalDbm: Int): String {
        return when {
            signalDbm >= -70 -> "Excellent"
            signalDbm >= -85 -> "Good"
            signalDbm >= -100 -> "Fair"
            signalDbm >= -110 -> "Poor"
            else -> "Very Poor"
        }
    }

    private fun getTrustStatus(trustScore: Int): String {
        return when {
            trustScore >= 80 -> "Trusted (frequently seen)"
            trustScore >= 60 -> "Known (seen before)"
            trustScore >= 30 -> "Unfamiliar (rarely seen)"
            else -> "Unknown (never seen before)"
        }
    }

    private fun getEncryptionVulnerability(encryption: EncryptionType): String {
        return when (encryption) {
            EncryptionType.NONE -> "No encryption - all communications in plaintext"
            EncryptionType.WEAK_2G -> "A5/1 cipher can be cracked in real-time with commodity hardware"
            EncryptionType.MODERATE_3G -> "KASUMI cipher has known weaknesses but requires significant resources"
            EncryptionType.STRONG_5G_LTE -> "AES-256 encryption - considered secure"
            EncryptionType.UNKNOWN -> "Unknown encryption status"
        }
    }

    private fun getImsiAssessment(score: Int): String {
        return when {
            score >= 80 -> "CRITICAL - Strong indicators of IMSI catcher/cell site simulator"
            score >= 60 -> "HIGH - Multiple suspicious indicators detected"
            score >= 40 -> "MODERATE - Some suspicious activity detected"
            score >= 20 -> "LOW - Minor anomalies detected"
            else -> "MINIMAL - Normal cellular behavior"
        }
    }

    private fun getRecommendations(context: CellularDetectionContext): String {
        return buildString {
            if (context.imsiCatcherScore >= 60) {
                appendLine("1. Avoid making sensitive calls or sending SMS")
                appendLine("2. Use end-to-end encrypted messaging apps")
                appendLine("3. Consider enabling airplane mode temporarily")
                appendLine("4. Move to a different location if possible")
            } else if (context.imsiCatcherScore >= 40) {
                appendLine("1. Monitor for additional anomalies")
                appendLine("2. Prefer encrypted communications")
                appendLine("3. Note current location for pattern analysis")
            } else {
                appendLine("1. Continue normal monitoring")
                appendLine("2. No immediate action required")
            }
        }
    }
}

/**
 * Interface for cellular detection handlers.
 *
 * This interface follows the pattern from SatelliteDetectionHandler for
 * anomaly-based detection processing.
 */
interface CellularDetectionHandlerInterface<T> {
    /**
     * Check if this handler can process the given context.
     */
    fun canHandle(context: T): Boolean

    /**
     * Process the context and return a Detection if warranted.
     * Returns null if the anomaly doesn't meet thresholds or is disabled.
     */
    suspend fun handle(context: T): Detection?

    /**
     * Generate an AI analysis prompt for the detection.
     */
    fun generateAiPrompt(context: T, detection: Detection): String
}

/**
 * Cellular-specific detection context containing all relevant cell data.
 */
data class CellularDetectionContext(
    // Anomaly info
    val anomalyType: CellularMonitor.AnomalyType,

    // Network identifiers
    val mcc: String?,
    val mnc: String?,
    val cellId: Long?,
    val previousCellId: Long?,
    val lac: Int?,  // Location Area Code (2G/3G)
    val tac: Int?,  // Tracking Area Code (4G/5G)

    // Signal info
    val signalStrength: Int,
    val previousSignalStrength: Int?,

    // Network type
    val networkType: String,
    val previousNetworkType: String?,
    val encryptionType: EncryptionType,

    // Roaming status
    val isRoaming: Boolean,

    // Location
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long,

    // IMSI catcher analysis
    val imsiCatcherScore: Int,
    val contributingFactors: List<String> = emptyList(),

    // Confidence and severity from CellularMonitor
    val confidence: CellularMonitor.AnomalyConfidence? = null,
    val severity: ThreatLevel? = null,

    // Movement analysis (optional, enriched)
    val movementType: MovementType? = null,
    val speedKmh: Double? = null,
    val distanceMeters: Double? = null,

    // Cell trust analysis (optional, enriched)
    val cellTrustScore: Int? = null,
    val cellSeenCount: Int? = null,
    val isInFamiliarArea: Boolean? = null,

    // Encryption downgrade chain
    val encryptionDowngradeChain: List<String>? = null
)

/**
 * Encryption strength classification for cellular networks.
 */
enum class EncryptionType(val displayName: String, val description: String) {
    STRONG_5G_LTE("Strong (5G/LTE)", "AES-256 encryption"),
    MODERATE_3G("Moderate (3G)", "KASUMI cipher - known weaknesses"),
    WEAK_2G("Weak (2G)", "A5/1 cipher - crackable in real-time"),
    NONE("None", "No encryption - plaintext"),
    UNKNOWN("Unknown", "Encryption status unknown")
}

/**
 * Movement type classification based on speed.
 */
enum class MovementType(val displayName: String, val maxSpeedKmh: Double) {
    STATIONARY("Stationary", 1.0),
    WALKING("Walking", 7.0),
    RUNNING("Running", 20.0),
    CYCLING("Cycling", 40.0),
    VEHICLE("Vehicle", 150.0),
    HIGH_SPEED_VEHICLE("High-Speed Vehicle", 350.0),
    IMPOSSIBLE("Impossible/Teleport", Double.MAX_VALUE);

    companion object {
        fun fromSpeed(speedKmh: Double): MovementType {
            return when {
                speedKmh < STATIONARY.maxSpeedKmh -> STATIONARY
                speedKmh < WALKING.maxSpeedKmh -> WALKING
                speedKmh < RUNNING.maxSpeedKmh -> RUNNING
                speedKmh < CYCLING.maxSpeedKmh -> CYCLING
                speedKmh < VEHICLE.maxSpeedKmh -> VEHICLE
                speedKmh < HIGH_SPEED_VEHICLE.maxSpeedKmh -> HIGH_SPEED_VEHICLE
                else -> IMPOSSIBLE
            }
        }
    }
}

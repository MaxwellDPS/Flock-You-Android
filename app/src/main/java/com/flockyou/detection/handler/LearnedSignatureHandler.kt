package com.flockyou.detection.handler

import android.content.Context
import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.rssiToSignalStrength
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learned Signature Detection Handler
 *
 * Handles detection of user-confirmed suspicious devices based on learned signatures.
 * This handler allows users to "learn" device signatures from devices they've encountered
 * and want to be alerted about in the future.
 *
 * ## Features
 * - Cross-protocol signature matching (BLE and WiFi)
 * - MAC prefix matching
 * - BLE service UUID matching
 * - Manufacturer ID matching
 * - User notes and context preservation
 *
 * ## Usage Flow
 * 1. User sees an unknown device in the "Seen Devices" list
 * 2. User marks the device as "suspicious" via learnSignature()
 * 3. Handler stores the device's fingerprint (MAC prefix, UUIDs, manufacturer IDs)
 * 4. Future scans check against learned signatures
 * 5. Matches generate HIGH threat detections
 *
 * @author Flock You Android Team
 */
@Singleton
class LearnedSignatureHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "LearnedSignatureHandler"

        /** Default threat score for learned signature matches */
        const val LEARNED_SIGNATURE_THREAT_SCORE = 85

        /** Rate limit between detections of the same device (milliseconds) */
        const val DETECTION_RATE_LIMIT_MS = 60000L

        /** Maximum number of learned signatures to store */
        const val MAX_LEARNED_SIGNATURES = 100
    }

    // ==================== Handler Properties ====================

    val displayName: String = "Learned Signature Handler"

    val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.UNKNOWN_SURVEILLANCE
    )

    val supportedMethods: Set<DetectionMethod> = setOf(
        DetectionMethod.BLE_DEVICE_NAME,
        DetectionMethod.MAC_PREFIX
    )

    // ==================== State ====================

    private var _isActive: Boolean = false
    val isActive: Boolean get() = _isActive

    /** Learning mode - when enabled, signatures can be learned */
    private val _learningModeEnabled = MutableStateFlow(false)
    val learningModeEnabled: StateFlow<Boolean> = _learningModeEnabled.asStateFlow()

    /** Stored learned signatures */
    private val _learnedSignatures = MutableStateFlow<List<LearnedSignature>>(emptyList())
    val learnedSignatures: StateFlow<List<LearnedSignature>> = _learnedSignatures.asStateFlow()

    /** Detection flow */
    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    val detections: Flow<Detection> = _detections.asSharedFlow()

    /** Last detection time per MAC address for rate limiting */
    private val lastDetectionTime = ConcurrentHashMap<String, Long>()

    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    // ==================== Lifecycle ====================

    fun startMonitoring() {
        _isActive = true
        Log.d(TAG, "Learned signature monitoring started with ${_learnedSignatures.value.size} signatures")
    }

    fun stopMonitoring() {
        _isActive = false
        Log.d(TAG, "Learned signature monitoring stopped")
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    fun destroy() {
        stopMonitoring()
        lastDetectionTime.clear()
    }

    // ==================== Learning Mode ====================

    /**
     * Enable learning mode to capture device signatures.
     */
    fun enableLearningMode() {
        _learningModeEnabled.value = true
        Log.d(TAG, "Learning mode enabled")
    }

    /**
     * Disable learning mode.
     */
    fun disableLearningMode() {
        _learningModeEnabled.value = false
        Log.d(TAG, "Learning mode disabled")
    }

    /**
     * Learn a BLE device signature from observed device data.
     *
     * @param macAddress The device's MAC address
     * @param deviceName The advertised device name (if any)
     * @param serviceUuids List of advertised service UUIDs
     * @param manufacturerIds List of manufacturer IDs from advertisement data
     * @param notes Optional user notes about why this device is suspicious
     */
    fun learnBleSignature(
        macAddress: String,
        deviceName: String?,
        serviceUuids: List<String>,
        manufacturerIds: List<Int>,
        notes: String? = null
    ) {
        val signature = LearnedSignature(
            id = macAddress,
            name = deviceName,
            protocol = DetectionProtocol.BLUETOOTH_LE,
            macPrefix = macAddress.take(8).uppercase(),
            serviceUuids = serviceUuids,
            manufacturerIds = manufacturerIds,
            notes = notes
        )
        addSignature(signature)
    }

    /**
     * Learn a WiFi device signature from observed network data.
     *
     * @param bssid The access point's BSSID (MAC address)
     * @param ssid The network SSID
     * @param notes Optional user notes about why this network is suspicious
     */
    fun learnWifiSignature(
        bssid: String,
        ssid: String?,
        notes: String? = null
    ) {
        val signature = LearnedSignature(
            id = bssid,
            name = ssid,
            protocol = DetectionProtocol.WIFI,
            macPrefix = bssid.take(8).uppercase(),
            serviceUuids = emptyList(),
            manufacturerIds = emptyList(),
            ssid = ssid,
            notes = notes
        )
        addSignature(signature)
    }

    /**
     * Add a signature to the learned list.
     */
    private fun addSignature(signature: LearnedSignature) {
        val current = _learnedSignatures.value.toMutableList()

        // Remove existing signature for same device if present
        current.removeAll { it.id == signature.id }

        // Add new signature at the beginning
        current.add(0, signature)

        // Limit total signatures
        while (current.size > MAX_LEARNED_SIGNATURES) {
            current.removeAt(current.lastIndex)
        }

        _learnedSignatures.value = current
        Log.i(TAG, "Learned signature for ${signature.name ?: signature.id}: ${signature.protocol.name}")
    }

    /**
     * Remove a learned signature.
     *
     * @param signatureId The ID of the signature to remove
     */
    fun removeSignature(signatureId: String) {
        val current = _learnedSignatures.value.toMutableList()
        val removed = current.removeAll { it.id == signatureId }
        if (removed) {
            _learnedSignatures.value = current
            Log.d(TAG, "Removed learned signature: $signatureId")
        }
    }

    /**
     * Clear all learned signatures.
     */
    fun clearSignatures() {
        _learnedSignatures.value = emptyList()
        lastDetectionTime.clear()
        Log.d(TAG, "Cleared all learned signatures")
    }

    /**
     * Import signatures from external source.
     */
    fun importSignatures(signatures: List<LearnedSignature>) {
        val current = _learnedSignatures.value.toMutableList()
        for (sig in signatures) {
            current.removeAll { it.id == sig.id }
            current.add(0, sig)
        }
        while (current.size > MAX_LEARNED_SIGNATURES) {
            current.removeAt(current.lastIndex)
        }
        _learnedSignatures.value = current
        Log.i(TAG, "Imported ${signatures.size} signatures, total: ${_learnedSignatures.value.size}")
    }

    // ==================== Detection Processing ====================

    /**
     * Check a BLE device against learned signatures.
     *
     * @param context The BLE detection context
     * @return LearnedSignatureResult if a match is found, null otherwise
     */
    fun checkBleDevice(context: LearnedSignatureContext.Ble): LearnedSignatureResult? {
        if (!_isActive || _learnedSignatures.value.isEmpty()) {
            return null
        }

        // Rate limiting
        val now = System.currentTimeMillis()
        val lastTime = lastDetectionTime[context.macAddress] ?: 0L
        if (now - lastTime < DETECTION_RATE_LIMIT_MS) {
            return null
        }

        val macPrefix = context.macAddress.take(8).uppercase()
        val uuidStrings = context.serviceUuids.map { it.toString() }

        for (signature in _learnedSignatures.value) {
            // Only check BLE signatures
            if (signature.protocol != DetectionProtocol.BLUETOOTH_LE) continue

            val matchesPrefix = signature.macPrefix == macPrefix
            val matchesUuids = signature.serviceUuids.isNotEmpty() &&
                    signature.serviceUuids.any { it in uuidStrings }
            val matchesMfg = signature.manufacturerIds.isNotEmpty() &&
                    signature.manufacturerIds.any { context.manufacturerIds.contains(it) }

            if (matchesPrefix || matchesUuids || matchesMfg) {
                Log.w(TAG, "LEARNED SIGNATURE MATCH: ${context.macAddress} matches ${signature.id}")
                lastDetectionTime[context.macAddress] = now

                val matchReasons = mutableListOf<String>()
                if (matchesPrefix) matchReasons.add("MAC prefix match")
                if (matchesUuids) matchReasons.add("Service UUID match")
                if (matchesMfg) matchReasons.add("Manufacturer ID match")

                val detection = createDetection(
                    protocol = DetectionProtocol.BLUETOOTH_LE,
                    macAddress = context.macAddress,
                    deviceName = context.deviceName ?: signature.name,
                    rssi = context.rssi,
                    signature = signature,
                    matchReasons = matchReasons
                )

                return LearnedSignatureResult(
                    detection = detection,
                    signature = signature,
                    matchReasons = matchReasons,
                    aiPrompt = buildAiPrompt(context, signature, matchReasons)
                )
            }
        }

        return null
    }

    /**
     * Check a WiFi network against learned signatures.
     *
     * @param context The WiFi detection context
     * @return LearnedSignatureResult if a match is found, null otherwise
     */
    fun checkWifiNetwork(context: LearnedSignatureContext.Wifi): LearnedSignatureResult? {
        if (!_isActive || _learnedSignatures.value.isEmpty()) {
            return null
        }

        // Rate limiting
        val now = System.currentTimeMillis()
        val lastTime = lastDetectionTime[context.bssid] ?: 0L
        if (now - lastTime < DETECTION_RATE_LIMIT_MS) {
            return null
        }

        val macPrefix = context.bssid.take(8).uppercase()

        for (signature in _learnedSignatures.value) {
            // Only check WiFi signatures
            if (signature.protocol != DetectionProtocol.WIFI) continue

            val matchesPrefix = signature.macPrefix == macPrefix
            val matchesSsid = signature.ssid != null && signature.ssid == context.ssid

            if (matchesPrefix || matchesSsid) {
                Log.w(TAG, "LEARNED SIGNATURE MATCH: ${context.bssid} matches ${signature.id}")
                lastDetectionTime[context.bssid] = now

                val matchReasons = mutableListOf<String>()
                if (matchesPrefix) matchReasons.add("MAC prefix match")
                if (matchesSsid) matchReasons.add("SSID match")

                val detection = createDetection(
                    protocol = DetectionProtocol.WIFI,
                    macAddress = context.bssid,
                    deviceName = context.ssid ?: signature.name,
                    rssi = context.rssi,
                    signature = signature,
                    matchReasons = matchReasons,
                    ssid = context.ssid
                )

                return LearnedSignatureResult(
                    detection = detection,
                    signature = signature,
                    matchReasons = matchReasons,
                    aiPrompt = buildWifiAiPrompt(context, signature, matchReasons)
                )
            }
        }

        return null
    }

    /**
     * Process a BLE device and emit detection if it matches a learned signature.
     */
    suspend fun processBleDevice(context: LearnedSignatureContext.Ble): Detection? {
        val result = checkBleDevice(context) ?: return null
        _detections.emit(result.detection)
        return result.detection
    }

    /**
     * Process a WiFi network and emit detection if it matches a learned signature.
     */
    suspend fun processWifiNetwork(context: LearnedSignatureContext.Wifi): Detection? {
        val result = checkWifiNetwork(context) ?: return null
        _detections.emit(result.detection)
        return result.detection
    }

    // ==================== Helper Methods ====================

    private fun createDetection(
        protocol: DetectionProtocol,
        macAddress: String,
        deviceName: String?,
        rssi: Int,
        signature: LearnedSignature,
        matchReasons: List<String>,
        ssid: String? = null
    ): Detection {
        return Detection(
            protocol = protocol,
            detectionMethod = DetectionMethod.BLE_DEVICE_NAME,
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            deviceName = deviceName,
            macAddress = macAddress,
            ssid = ssid,
            rssi = rssi,
            signalStrength = rssiToSignalStrength(rssi),
            latitude = currentLatitude,
            longitude = currentLongitude,
            threatLevel = ThreatLevel.HIGH,
            threatScore = LEARNED_SIGNATURE_THREAT_SCORE,
            manufacturer = "Learned Signature",
            serviceUuids = signature.serviceUuids.joinToString(","),
            matchedPatterns = buildMatchedPatternsJson(
                listOf(
                    "Matches learned signature: ${signature.id}",
                    signature.notes ?: "User-confirmed suspicious device"
                ) + matchReasons
            )
        )
    }

    private fun buildMatchedPatternsJson(patterns: List<String>): String {
        return patterns.joinToString(
            prefix = "[\"",
            postfix = "\"]",
            separator = "\",\""
        ) { it.replace("\"", "\\\"") }
    }

    private fun buildAiPrompt(
        context: LearnedSignatureContext.Ble,
        signature: LearnedSignature,
        matchReasons: List<String>
    ): String {
        return """User-Learned Suspicious Device Detected

=== Detection Data ===
MAC Address: ${context.macAddress}
Device Name: ${context.deviceName ?: "(none)"}
Signal Strength: ${context.rssi} dBm
Location: ${formatLocation()}

=== Learned Signature Match ===
Original Device ID: ${signature.id}
Original Name: ${signature.name ?: "(none)"}
Learned At: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(signature.learnedAt)}
User Notes: ${signature.notes ?: "(none)"}

Match Reasons:
${matchReasons.joinToString("\n") { "- $it" }}

=== Signature Details ===
MAC Prefix: ${signature.macPrefix}
Service UUIDs: ${signature.serviceUuids.joinToString(", ").ifEmpty { "(none)" }}
Manufacturer IDs: ${signature.manufacturerIds.joinToString(", ") { "0x${it.toString(16).uppercase()}" }.ifEmpty { "(none)" }}

=== Context ===
This device was previously flagged as suspicious by the user.
The user chose to "learn" this device's signature after observing it.

This is a HIGH priority alert because the user specifically identified
this device pattern as concerning.

Please analyze:
1. Why might this device be following the user?
2. What type of device does this signature suggest?
3. Recommended actions for the user
4. Any additional context about the device type"""
    }

    private fun buildWifiAiPrompt(
        context: LearnedSignatureContext.Wifi,
        signature: LearnedSignature,
        matchReasons: List<String>
    ): String {
        return """User-Learned Suspicious WiFi Network Detected

=== Detection Data ===
BSSID: ${context.bssid}
SSID: ${context.ssid ?: "(hidden)"}
Signal Strength: ${context.rssi} dBm
Location: ${formatLocation()}

=== Learned Signature Match ===
Original Network ID: ${signature.id}
Original SSID: ${signature.ssid ?: signature.name ?: "(none)"}
Learned At: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(signature.learnedAt)}
User Notes: ${signature.notes ?: "(none)"}

Match Reasons:
${matchReasons.joinToString("\n") { "- $it" }}

=== Context ===
This WiFi network was previously flagged as suspicious by the user.
The user chose to "learn" this network's signature after observing it.

This is a HIGH priority alert because the user specifically identified
this network pattern as concerning.

Possible reasons for concern:
- Network appearing at multiple locations (following pattern)
- Unusual SSID pattern
- Appearing during sensitive activities
- Matches known surveillance equipment patterns

Please analyze:
1. Why might this network be following the user?
2. Is this likely a surveillance device or tracking mechanism?
3. Recommended actions for the user"""
    }

    private fun formatLocation(): String {
        return if (currentLatitude != null && currentLongitude != null) {
            "%.6f, %.6f".format(currentLatitude, currentLongitude)
        } else {
            "Location unavailable"
        }
    }
}

// ==================== Data Classes ====================

/**
 * Learned signature representing a user-confirmed suspicious device.
 *
 * @property id Unique identifier (usually the original MAC address)
 * @property name Device/network name at time of learning
 * @property protocol The detection protocol (BLE or WiFi)
 * @property macPrefix First 8 characters of MAC address (3 octets with colons)
 * @property serviceUuids List of BLE service UUIDs (for BLE devices)
 * @property manufacturerIds List of BLE manufacturer IDs (for BLE devices)
 * @property ssid WiFi SSID (for WiFi networks)
 * @property learnedAt Timestamp when the signature was learned
 * @property notes Optional user notes explaining why this device is suspicious
 */
data class LearnedSignature(
    val id: String,
    val name: String?,
    val protocol: DetectionProtocol,
    val macPrefix: String,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerIds: List<Int> = emptyList(),
    val ssid: String? = null,
    val learnedAt: Long = System.currentTimeMillis(),
    val notes: String? = null
)

/**
 * Sealed class for learned signature detection contexts.
 */
sealed class LearnedSignatureContext {
    /**
     * BLE device context for learned signature matching.
     */
    data class Ble(
        val macAddress: String,
        val deviceName: String?,
        val rssi: Int,
        val serviceUuids: List<UUID>,
        val manufacturerIds: List<Int>,
        val timestamp: Long = System.currentTimeMillis()
    ) : LearnedSignatureContext()

    /**
     * WiFi network context for learned signature matching.
     */
    data class Wifi(
        val bssid: String,
        val ssid: String?,
        val rssi: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) : LearnedSignatureContext()
}

/**
 * Result of a learned signature match.
 *
 * @property detection The generated Detection object
 * @property signature The matched learned signature
 * @property matchReasons List of reasons why this matched (MAC prefix, UUID, etc.)
 * @property aiPrompt Contextual prompt for AI analysis
 */
data class LearnedSignatureResult(
    val detection: Detection,
    val signature: LearnedSignature,
    val matchReasons: List<String>,
    val aiPrompt: String
)

package com.flockyou.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a detected surveillance device
 */
@Entity(tableName = "detections")
data class Detection(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val protocol: DetectionProtocol,
    val detectionMethod: DetectionMethod,
    val deviceType: DeviceType,
    val deviceName: String?,
    val macAddress: String?,
    val ssid: String?,
    val rssi: Int,
    val signalStrength: SignalStrength,
    val latitude: Double?,
    val longitude: Double?,
    val threatLevel: ThreatLevel,
    val threatScore: Int,
    val manufacturer: String?,
    val firmwareVersion: String?,
    val serviceUuids: String?, // JSON array of service UUIDs
    val matchedPatterns: String?, // JSON array of matched patterns
    val isActive: Boolean = true
)

enum class DetectionProtocol {
    WIFI,
    BLUETOOTH_LE
}

enum class DetectionMethod {
    SSID_PATTERN,
    MAC_PREFIX,
    BLE_DEVICE_NAME,
    BLE_SERVICE_UUID,
    RAVEN_SERVICE_UUID,
    PROBE_REQUEST,
    BEACON_FRAME
}

enum class DeviceType {
    FLOCK_SAFETY_CAMERA,
    PENGUIN_SURVEILLANCE,
    PIGVISION_SYSTEM,
    RAVEN_GUNSHOT_DETECTOR,
    UNKNOWN_SURVEILLANCE
}

enum class SignalStrength {
    EXCELLENT,  // > -50 dBm
    GOOD,       // -50 to -60 dBm
    MEDIUM,     // -60 to -70 dBm
    WEAK,       // -70 to -80 dBm
    VERY_WEAK   // < -80 dBm
}

enum class ThreatLevel {
    CRITICAL,   // Score 90-100
    HIGH,       // Score 70-89
    MEDIUM,     // Score 50-69
    LOW,        // Score 30-49
    INFO        // Score 0-29
}

/**
 * Converts RSSI value to SignalStrength
 */
fun rssiToSignalStrength(rssi: Int): SignalStrength = when {
    rssi > -50 -> SignalStrength.EXCELLENT
    rssi > -60 -> SignalStrength.GOOD
    rssi > -70 -> SignalStrength.MEDIUM
    rssi > -80 -> SignalStrength.WEAK
    else -> SignalStrength.VERY_WEAK
}

/**
 * Converts threat score to ThreatLevel
 */
fun scoreToThreatLevel(score: Int): ThreatLevel = when {
    score >= 90 -> ThreatLevel.CRITICAL
    score >= 70 -> ThreatLevel.HIGH
    score >= 50 -> ThreatLevel.MEDIUM
    score >= 30 -> ThreatLevel.LOW
    else -> ThreatLevel.INFO
}

/**
 * Detection pattern configuration
 */
data class DetectionPattern(
    val type: PatternType,
    val pattern: String,
    val deviceType: DeviceType,
    val manufacturer: String?,
    val threatScore: Int,
    val description: String
)

enum class PatternType {
    SSID_REGEX,
    MAC_PREFIX,
    BLE_NAME_REGEX,
    BLE_SERVICE_UUID
}

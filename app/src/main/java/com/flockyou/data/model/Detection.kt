package com.flockyou.data.model

import androidx.compose.ui.graphics.Color
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

enum class DetectionProtocol(val displayName: String, val icon: String) {
    WIFI("WiFi", "📡"),
    BLUETOOTH_LE("Bluetooth LE", "📶")
}

enum class DetectionMethod(val displayName: String, val description: String) {
    SSID_PATTERN("SSID Match", "Device identified by WiFi network name pattern"),
    MAC_PREFIX("MAC Address", "Device identified by manufacturer OUI prefix"),
    BLE_DEVICE_NAME("BLE Name", "Device identified by Bluetooth advertised name"),
    BLE_SERVICE_UUID("BLE Service", "Device identified by Bluetooth service UUIDs"),
    RAVEN_SERVICE_UUID("Raven Services", "Raven gunshot detector identified by specific BLE services"),
    PROBE_REQUEST("Probe Request", "Device detected via WiFi probe request"),
    BEACON_FRAME("Beacon Frame", "Device detected via WiFi beacon broadcast")
}

enum class DeviceType(val displayName: String, val emoji: String) {
    FLOCK_SAFETY_CAMERA("Flock Safety ALPR", "📸"),
    PENGUIN_SURVEILLANCE("Penguin Surveillance", "🐧"),
    PIGVISION_SYSTEM("Pigvision System", "🐷"),
    RAVEN_GUNSHOT_DETECTOR("Raven Gunshot Detector", "🦅"),
    UNKNOWN_SURVEILLANCE("Unknown Surveillance", "❓")
}

enum class SignalStrength(val displayName: String, val description: String) {
    EXCELLENT("Excellent", "Very close - within ~10m"),
    GOOD("Good", "Close proximity - within ~25m"),
    MEDIUM("Medium", "Moderate distance - within ~50m"),
    WEAK("Weak", "Far - within ~75m"),
    VERY_WEAK("Very Weak", "Edge of range - 75m+")
}

enum class ThreatLevel(val displayName: String, val description: String) {
    CRITICAL("Critical", "Active acoustic/audio surveillance - recording sounds"),
    HIGH("High", "Confirmed surveillance device - recording vehicle data"),
    MEDIUM("Medium", "Likely surveillance equipment"),
    LOW("Low", "Possible surveillance device"),
    INFO("Info", "Device of interest - may not be surveillance")
}

/**
 * Extension to get color for ThreatLevel
 */
fun ThreatLevel.toColor(): Color = when (this) {
    ThreatLevel.CRITICAL -> Color(0xFFD32F2F) // Red
    ThreatLevel.HIGH -> Color(0xFFF57C00) // Orange
    ThreatLevel.MEDIUM -> Color(0xFFFBC02D) // Yellow
    ThreatLevel.LOW -> Color(0xFF388E3C) // Green
    ThreatLevel.INFO -> Color(0xFF1976D2) // Blue
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
 * Get estimated distance from RSSI
 */
fun rssiToDistance(rssi: Int): String {
    // Rough estimation based on free-space path loss
    // Assumes 2.4GHz, reference RSSI of -40 at 1m
    val distance = when {
        rssi > -40 -> "< 1m"
        rssi > -50 -> "~1-5m"
        rssi > -60 -> "~5-15m"
        rssi > -70 -> "~15-30m"
        rssi > -80 -> "~30-50m"
        rssi > -90 -> "~50-100m"
        else -> "> 100m"
    }
    return distance
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

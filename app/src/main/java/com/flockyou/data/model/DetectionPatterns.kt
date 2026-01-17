package com.flockyou.data.model

import java.util.UUID

/**
 * Database of known surveillance device signatures
 * Based on data from deflock.me and GainSec research
 */
object DetectionPatterns {
    
    // ==================== SSID Patterns ====================
    val ssidPatterns = listOf(
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^flock.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Flock Safety ALPR Camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^fs_.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Safety Camera (FS prefix)"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^penguin.*",
            deviceType = DeviceType.PENGUIN_SURVEILLANCE,
            manufacturer = "Penguin",
            threatScore = 85,
            description = "Penguin Surveillance Device"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^pigvision.*",
            deviceType = DeviceType.PIGVISION_SYSTEM,
            manufacturer = "Pigvision",
            threatScore = 85,
            description = "Pigvision Surveillance System"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^alpr.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 80,
            description = "Generic ALPR System"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^lpr.*cam.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 75,
            description = "License Plate Reader Camera"
        )
    )
    
    // ==================== BLE Device Name Patterns ====================
    val bleNamePatterns = listOf(
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^flock.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Flock Safety BLE Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^penguin.*",
            deviceType = DeviceType.PENGUIN_SURVEILLANCE,
            manufacturer = "Penguin",
            threatScore = 85,
            description = "Penguin BLE Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^pigvision.*",
            deviceType = DeviceType.PIGVISION_SYSTEM,
            manufacturer = "Pigvision",
            threatScore = 85,
            description = "Pigvision BLE Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^raven.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "SoundThinking/ShotSpotter",
            threatScore = 100,
            description = "Raven Gunshot Detection Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^shotspotter.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "SoundThinking/ShotSpotter",
            threatScore = 100,
            description = "ShotSpotter Acoustic Sensor"
        )
    )
    
    // ==================== Raven Service UUIDs ====================
    // Based on GainSec research and raven_configurations.json
    data class RavenServiceInfo(
        val uuid: UUID,
        val description: String,
        val firmwareVersions: List<String>
    )
    
    val ravenServiceUuids = listOf(
        // Device Information Service
        RavenServiceInfo(
            uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
            description = "Device Information Service (Serial, Model, Firmware)",
            firmwareVersions = listOf("1.1.x", "1.2.x", "1.3.x")
        ),
        // GPS Location Service
        RavenServiceInfo(
            uuid = UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"),
            description = "GPS Location Service (Lat/Lon/Alt)",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        // Power Management Service
        RavenServiceInfo(
            uuid = UUID.fromString("00003200-0000-1000-8000-00805f9b34fb"),
            description = "Power Management Service (Battery/Solar)",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        // Network Status Service
        RavenServiceInfo(
            uuid = UUID.fromString("00003300-0000-1000-8000-00805f9b34fb"),
            description = "Network Status Service (LTE/WiFi)",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        // Upload Statistics Service
        RavenServiceInfo(
            uuid = UUID.fromString("00003400-0000-1000-8000-00805f9b34fb"),
            description = "Upload Statistics Service (Data Transmission)",
            firmwareVersions = listOf("1.3.x")
        ),
        // Error/Failure Service
        RavenServiceInfo(
            uuid = UUID.fromString("00003500-0000-1000-8000-00805f9b34fb"),
            description = "Error/Failure Service (Diagnostics)",
            firmwareVersions = listOf("1.3.x")
        ),
        // Legacy Health Thermometer Service (1.1.x)
        RavenServiceInfo(
            uuid = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb"),
            description = "Health Thermometer Service (Legacy)",
            firmwareVersions = listOf("1.1.x")
        ),
        // Legacy Location/Navigation Service (1.1.x)
        RavenServiceInfo(
            uuid = UUID.fromString("00001819-0000-1000-8000-00805f9b34fb"),
            description = "Location/Navigation Service (Legacy)",
            firmwareVersions = listOf("1.1.x")
        )
    )
    
    val ravenServiceUuidSet: Set<UUID> = ravenServiceUuids.map { it.uuid }.toSet()
    
    /**
     * Estimate Raven firmware version based on advertised services
     */
    fun estimateRavenFirmwareVersion(serviceUuids: List<UUID>): String {
        val hasLegacyHealth = serviceUuids.contains(UUID.fromString("00001809-0000-1000-8000-00805f9b34fb"))
        val hasLegacyLocation = serviceUuids.contains(UUID.fromString("00001819-0000-1000-8000-00805f9b34fb"))
        val hasGps = serviceUuids.contains(UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"))
        val hasPower = serviceUuids.contains(UUID.fromString("00003200-0000-1000-8000-00805f9b34fb"))
        val hasUpload = serviceUuids.contains(UUID.fromString("00003400-0000-1000-8000-00805f9b34fb"))
        val hasError = serviceUuids.contains(UUID.fromString("00003500-0000-1000-8000-00805f9b34fb"))
        
        return when {
            hasUpload || hasError -> "1.3.x (Latest)"
            hasGps && hasPower -> "1.2.x"
            hasLegacyHealth || hasLegacyLocation -> "1.1.x (Legacy)"
            else -> "Unknown"
        }
    }
    
    // ==================== MAC Address Prefixes ====================
    // OUI prefixes known to be used by surveillance equipment
    val macPrefixes = listOf(
        // Note: These are example prefixes - in a real implementation,
        // you would get these from deflock.me datasets
        MacPrefix("00:1A:2B", DeviceType.FLOCK_SAFETY_CAMERA, "Flock Safety", 90),
        MacPrefix("00:1B:2C", DeviceType.PENGUIN_SURVEILLANCE, "Penguin", 85),
        MacPrefix("00:1C:2D", DeviceType.PIGVISION_SYSTEM, "Pigvision", 85),
        MacPrefix("00:1D:2E", DeviceType.RAVEN_GUNSHOT_DETECTOR, "SoundThinking", 100)
    )
    
    data class MacPrefix(
        val prefix: String,
        val deviceType: DeviceType,
        val manufacturer: String,
        val threatScore: Int
    )
    
    /**
     * Check if a MAC address matches any known prefix
     */
    fun matchMacPrefix(macAddress: String): MacPrefix? {
        val normalizedMac = macAddress.uppercase().replace("-", ":")
        return macPrefixes.find { normalizedMac.startsWith(it.prefix.uppercase()) }
    }
    
    /**
     * Check if SSID matches any known pattern
     */
    fun matchSsidPattern(ssid: String): DetectionPattern? {
        return ssidPatterns.find { pattern ->
            Regex(pattern.pattern).matches(ssid)
        }
    }
    
    /**
     * Check if BLE device name matches any known pattern
     */
    fun matchBleNamePattern(deviceName: String): DetectionPattern? {
        return bleNamePatterns.find { pattern ->
            Regex(pattern.pattern).matches(deviceName)
        }
    }
    
    /**
     * Check if any service UUIDs match Raven patterns
     */
    fun matchRavenServices(serviceUuids: List<UUID>): List<RavenServiceInfo> {
        return ravenServiceUuids.filter { it.uuid in serviceUuids }
    }
    
    /**
     * Check if this is a Raven device based on service UUIDs
     */
    fun isRavenDevice(serviceUuids: List<UUID>): Boolean {
        return serviceUuids.any { it in ravenServiceUuidSet }
    }
}

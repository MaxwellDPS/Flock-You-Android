package com.flockyou.data.model

import java.util.UUID

/**
 * Database of known surveillance device signatures
 * Based on data from deflock.me, GainSec research, and FCC filings
 * 
 * Detection methodology:
 * - WiFi: SSID patterns, MAC OUI prefixes from LTE modems
 * - BLE: Device names, Service UUIDs (especially for Raven)
 * 
 * Flock cameras use cellular LTE modems (Quectel, Telit, Sierra Wireless)
 * and emit WiFi for configuration/management
 */
object DetectionPatterns {
    
    // ==================== SSID Patterns ====================
    // Primary detection method - Flock cameras advertise specific SSIDs
    val ssidPatterns = listOf(
        // Flock Safety - Primary patterns
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^flock[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Flock Safety ALPR Camera - captures license plates and vehicle characteristics"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^fs[_-].*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Safety Camera (FS prefix variant)"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^falcon[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Falcon ALPR - standard pole-mounted camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^sparrow[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Sparrow ALPR - compact camera model"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^condor[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Condor ALPR - high-speed multi-lane camera"
        ),
        
        // Penguin surveillance
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^penguin[_-]?.*",
            deviceType = DeviceType.PENGUIN_SURVEILLANCE,
            manufacturer = "Penguin",
            threatScore = 85,
            description = "Penguin Surveillance Device - mobile ALPR system"
        ),
        
        // Pigvision
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^pigvision[_-]?.*",
            deviceType = DeviceType.PIGVISION_SYSTEM,
            manufacturer = "Pigvision",
            threatScore = 85,
            description = "Pigvision Surveillance System"
        ),
        
        // Generic ALPR patterns
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^alpr[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 80,
            description = "Generic ALPR System - Automated License Plate Reader"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^lpr[_-]?cam.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 75,
            description = "License Plate Reader Camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^vigilant[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Motorola Solutions",
            threatScore = 85,
            description = "Vigilant ALPR (Motorola) - competitor to Flock"
        )
    )
    
    // ==================== BLE Device Name Patterns ====================
    val bleNamePatterns = listOf(
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^flock[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Flock Safety BLE Configuration Interface"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^falcon[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Falcon Camera BLE"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^raven[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "Flock Safety / SoundThinking",
            threatScore = 100,
            description = "Raven Acoustic Gunshot Detector - listens for gunfire and 'human distress'"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^shotspotter[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "SoundThinking (formerly ShotSpotter)",
            threatScore = 100,
            description = "ShotSpotter Acoustic Sensor - gunfire detection system"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^soundthinking[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "SoundThinking",
            threatScore = 100,
            description = "SoundThinking Acoustic Surveillance Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^penguin[_-]?.*",
            deviceType = DeviceType.PENGUIN_SURVEILLANCE,
            manufacturer = "Penguin",
            threatScore = 85,
            description = "Penguin BLE Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^pigvision[_-]?.*",
            deviceType = DeviceType.PIGVISION_SYSTEM,
            manufacturer = "Pigvision",
            threatScore = 85,
            description = "Pigvision BLE Device"
        )
    )
    
    // ==================== Raven Service UUIDs ====================
    // Based on GainSec research - raven_configurations.json
    // Firmware versions 1.1.7, 1.2.0, 1.3.1
    data class RavenServiceInfo(
        val uuid: UUID,
        val name: String,
        val description: String,
        val dataExposed: String,
        val firmwareVersions: List<String>
    )
    
    val ravenServiceUuids = listOf(
        RavenServiceInfo(
            uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
            name = "Device Information",
            description = "Standard BLE Device Information Service",
            dataExposed = "Serial number, model number, firmware version, manufacturer",
            firmwareVersions = listOf("1.1.x", "1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"),
            name = "GPS Location",
            description = "Real-time GPS coordinates of the device",
            dataExposed = "Latitude, longitude, altitude, GPS fix status",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003200-0000-1000-8000-00805f9b34fb"),
            name = "Power Management",
            description = "Battery and solar panel status",
            dataExposed = "Battery level, charging status, solar input voltage",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003300-0000-1000-8000-00805f9b34fb"),
            name = "Network Status",
            description = "Cellular and WiFi connectivity info",
            dataExposed = "LTE signal strength, carrier, data usage, WiFi status",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003400-0000-1000-8000-00805f9b34fb"),
            name = "Upload Statistics",
            description = "Data transmission metrics",
            dataExposed = "Bytes uploaded, detection count, last upload time",
            firmwareVersions = listOf("1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003500-0000-1000-8000-00805f9b34fb"),
            name = "Error/Diagnostics",
            description = "System diagnostics and error logs",
            dataExposed = "Error codes, system health, diagnostic data",
            firmwareVersions = listOf("1.3.x")
        ),
        // Legacy services (firmware 1.1.x)
        RavenServiceInfo(
            uuid = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb"),
            name = "Health Thermometer (Legacy)",
            description = "Repurposed standard BLE service",
            dataExposed = "Device temperature, environmental data",
            firmwareVersions = listOf("1.1.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00001819-0000-1000-8000-00805f9b34fb"),
            name = "Location/Navigation (Legacy)",
            description = "Repurposed standard BLE location service",
            dataExposed = "Basic location data",
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
            hasUpload || hasError -> "1.3.x (Latest - Full diagnostics)"
            hasGps && hasPower -> "1.2.x (GPS + Power monitoring)"
            hasLegacyHealth || hasLegacyLocation -> "1.1.x (Legacy firmware)"
            else -> "Unknown version"
        }
    }
    
    // ==================== MAC Address Prefixes (OUI) ====================
    // LTE modems commonly used in surveillance equipment
    // Flock uses cellular connectivity - these are modem manufacturer OUIs
    val macPrefixes = listOf(
        // Quectel - Common LTE modem manufacturer
        MacPrefix("50:29:4D", DeviceType.FLOCK_SAFETY_CAMERA, "Quectel (LTE Modem)", 70,
            "Quectel LTE modem - commonly used in Flock cameras"),
        MacPrefix("86:25:19", DeviceType.FLOCK_SAFETY_CAMERA, "Quectel (LTE Modem)", 70,
            "Quectel cellular module"),
        
        // Telit - Another common IoT/LTE modem maker
        MacPrefix("00:14:2D", DeviceType.UNKNOWN_SURVEILLANCE, "Telit (LTE Modem)", 65,
            "Telit cellular modem - used in IoT surveillance"),
        MacPrefix("D8:C7:71", DeviceType.UNKNOWN_SURVEILLANCE, "Telit Wireless", 65,
            "Telit wireless module"),
            
        // Sierra Wireless
        MacPrefix("00:14:3E", DeviceType.UNKNOWN_SURVEILLANCE, "Sierra Wireless", 65,
            "Sierra Wireless modem - IoT/M2M applications"),
        MacPrefix("00:A0:D5", DeviceType.UNKNOWN_SURVEILLANCE, "Sierra Wireless", 65,
            "Sierra Wireless cellular module"),
            
        // u-blox - GPS/cellular modules
        MacPrefix("D4:CA:6E", DeviceType.UNKNOWN_SURVEILLANCE, "u-blox", 60,
            "u-blox cellular/GPS module"),
            
        // Cradlepoint / Ericsson
        MacPrefix("00:10:8B", DeviceType.UNKNOWN_SURVEILLANCE, "Cradlepoint", 65,
            "Cradlepoint router - often used for mobile surveillance"),
            
        // Raspberry Pi (used in DIY/prototype ALPR)
        MacPrefix("B8:27:EB", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi - potential DIY ALPR system"),
        MacPrefix("DC:A6:32", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi 4 - potential DIY surveillance"),
        MacPrefix("E4:5F:01", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi - IoT device")
    )
    
    data class MacPrefix(
        val prefix: String,
        val deviceType: DeviceType,
        val manufacturer: String,
        val threatScore: Int,
        val description: String = ""
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
            try {
                Regex(pattern.pattern).matches(ssid)
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Check if BLE device name matches any known pattern
     */
    fun matchBleNamePattern(deviceName: String): DetectionPattern? {
        return bleNamePatterns.find { pattern ->
            try {
                Regex(pattern.pattern).matches(deviceName)
            } catch (e: Exception) {
                false
            }
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
        // Need at least 2 Raven-specific services to confirm
        val matchCount = serviceUuids.count { it in ravenServiceUuidSet }
        return matchCount >= 2
    }
    
    /**
     * Get detailed info about a device type
     */
    fun getDeviceTypeInfo(deviceType: DeviceType): DeviceTypeInfo {
        return when (deviceType) {
            DeviceType.FLOCK_SAFETY_CAMERA -> DeviceTypeInfo(
                name = "Flock Safety ALPR Camera",
                shortDescription = "Automated License Plate Reader",
                fullDescription = "Flock Safety cameras capture images of vehicles and license plates. " +
                    "They use 'Vehicle Fingerprint' technology to identify make, model, color, and " +
                    "distinguishing features. Data is stored for 30 days and shared with law enforcement. " +
                    "Over 5,000 communities use Flock with 20+ billion monthly plate scans.",
                capabilities = listOf(
                    "License plate capture (up to 100 mph)",
                    "Vehicle make/model/color identification",
                    "Vehicle 'fingerprinting' (dents, stickers, etc.)",
                    "Real-time hotlist alerts",
                    "Integration with NCIC database",
                    "Cross-jurisdiction data sharing"
                ),
                privacyConcerns = listOf(
                    "Mass surveillance of vehicle movements",
                    "30-day data retention (may vary by jurisdiction)",
                    "Shared across law enforcement network",
                    "No warrant required for access",
                    "Can be integrated with Palantir"
                )
            )
            DeviceType.RAVEN_GUNSHOT_DETECTOR -> DeviceTypeInfo(
                name = "Raven Acoustic Gunshot Detector",
                shortDescription = "Audio Surveillance Device",
                fullDescription = "Raven devices (by Flock Safety, similar to ShotSpotter) continuously " +
                    "record audio in 5-second clips, using AI to detect gunfire. As of October 2025, " +
                    "Flock announced Ravens will also listen for 'human distress' (screaming). " +
                    "Solar-powered with cellular connectivity.",
                capabilities = listOf(
                    "Continuous audio monitoring",
                    "Gunshot detection and location",
                    "Human distress/scream detection (new)",
                    "GPS location tracking",
                    "Instant alerts to law enforcement",
                    "AI-powered audio analysis"
                ),
                privacyConcerns = listOf(
                    "Constant audio surveillance",
                    "'Human distress' detection is vague",
                    "Audio recordings may capture conversations",
                    "No consent from recorded individuals",
                    "Potential for false positives"
                )
            )
            DeviceType.PENGUIN_SURVEILLANCE -> DeviceTypeInfo(
                name = "Penguin Surveillance Device",
                shortDescription = "Mobile ALPR System",
                fullDescription = "Penguin devices are mobile surveillance systems often mounted on vehicles.",
                capabilities = listOf("Mobile license plate reading", "Vehicle tracking"),
                privacyConcerns = listOf("Mobile mass surveillance", "Covert operation")
            )
            DeviceType.PIGVISION_SYSTEM -> DeviceTypeInfo(
                name = "Pigvision System",
                shortDescription = "Surveillance Camera System",
                fullDescription = "Pigvision surveillance camera network.",
                capabilities = listOf("Video surveillance", "License plate capture"),
                privacyConcerns = listOf("Mass surveillance", "Data retention unknown")
            )
            DeviceType.UNKNOWN_SURVEILLANCE -> DeviceTypeInfo(
                name = "Unknown Surveillance Device",
                shortDescription = "Unidentified Surveillance Equipment",
                fullDescription = "This device matches patterns associated with surveillance equipment " +
                    "but the specific manufacturer/model is unknown.",
                capabilities = listOf("Unknown - potentially ALPR or audio surveillance"),
                privacyConcerns = listOf("Unknown data collection practices")
            )
        }
    }
    
    data class DeviceTypeInfo(
        val name: String,
        val shortDescription: String,
        val fullDescription: String,
        val capabilities: List<String>,
        val privacyConcerns: List<String>
    )
    
    // ==================== OUI Manufacturer Lookup ====================
    // Common manufacturer OUIs for quick identification
    private val ouiManufacturers = mapOf(
        "00:00:0C" to "Cisco",
        "00:01:42" to "Cisco",
        "00:0C:29" to "VMware",
        "00:0D:3A" to "Microsoft",
        "00:14:22" to "Dell",
        "00:17:88" to "Philips",
        "00:1A:11" to "Google",
        "00:1E:C2" to "Apple",
        "00:23:32" to "Apple",
        "00:25:00" to "Apple",
        "00:26:BB" to "Apple",
        "00:50:56" to "VMware",
        "08:00:27" to "Oracle VirtualBox",
        "14:13:46" to "Xiaomi",
        "18:65:90" to "Apple",
        "28:6A:B8" to "Apple",
        "2C:BE:08" to "Apple",
        "34:23:BA" to "Xiaomi",
        "38:F9:D3" to "Apple",
        "3C:06:30" to "Apple",
        "40:4E:36" to "HP",
        "44:D9:E7" to "Ubiquiti",
        "50:29:4D" to "Quectel",
        "54:60:09" to "Google",
        "58:CB:52" to "Google",
        "5C:CF:7F" to "Espressif",
        "60:01:94" to "Espressif",
        "70:B3:D5" to "IEEE Registration",
        "78:4F:43" to "Apple",
        "80:6D:97" to "Samsung",
        "84:D8:1B" to "Apple",
        "88:E9:FE" to "Apple",
        "8C:85:90" to "Apple",
        "94:65:2D" to "OnePlus",
        "98:D6:F7" to "LG",
        "9C:8E:99" to "Hewlett Packard",
        "A4:77:33" to "Google",
        "A4:C6:39" to "Intel",
        "AC:37:43" to "HTC",
        "B0:34:95" to "Apple",
        "B4:F1:DA" to "LG",
        "B8:27:EB" to "Raspberry Pi",
        "BC:83:85" to "Microsoft",
        "C0:EE:FB" to "OnePlus",
        "C8:3D:D4" to "CyberTAN",
        "CC:46:D6" to "Cisco",
        "D0:03:4B" to "Apple",
        "D4:61:9D" to "Apple",
        "D4:CA:6E" to "u-blox",
        "D8:C7:71" to "Telit",
        "DC:A6:32" to "Raspberry Pi",
        "E0:5F:45" to "Apple",
        "E4:5F:01" to "Raspberry Pi",
        "EC:85:2F" to "Apple",
        "F0:18:98" to "Apple",
        "F4:F5:D8" to "Google",
        "F8:1A:67" to "TP-Link",
        "FC:A1:3E" to "Samsung"
    )
    
    /**
     * Try to identify manufacturer from MAC OUI (first 3 octets)
     */
    fun getManufacturerFromOui(oui: String): String? {
        val normalizedOui = oui.uppercase().replace("-", ":").take(8)
        return ouiManufacturers[normalizedOui] ?: macPrefixes.find { 
            normalizedOui.startsWith(it.prefix.uppercase()) 
        }?.manufacturer
    }
}

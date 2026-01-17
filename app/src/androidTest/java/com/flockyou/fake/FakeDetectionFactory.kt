package com.flockyou.fake

import com.flockyou.data.model.*
import java.util.UUID

/**
 * Factory for creating fake detection data for testing purposes.
 */
object FakeDetectionFactory {
    
    /**
     * Creates a fake Flock Safety camera detection via WiFi SSID pattern.
     */
    fun createFlockSafetyWifiDetection(
        ssid: String = "Flock_Camera_001",
        rssi: Int = -65,
        latitude: Double? = 47.6062,
        longitude: Double? = -122.3321
    ) = Detection(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
        deviceName = null,
        macAddress = "AA:BB:CC:DD:EE:FF",
        ssid = ssid,
        rssi = rssi,
        signalStrength = rssiToSignalStrength(rssi),
        latitude = latitude,
        longitude = longitude,
        threatLevel = ThreatLevel.CRITICAL,
        threatScore = 95,
        manufacturer = "Flock Safety",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = "[\"Flock Safety ALPR Camera\"]",
        isActive = true
    )
    
    /**
     * Creates a fake Raven gunshot detector detection via BLE service UUID.
     */
    fun createRavenBleDetection(
        deviceName: String = "Raven-Device-001",
        rssi: Int = -72,
        firmwareVersion: String = "1.3.x (Latest)",
        latitude: Double? = 47.6062,
        longitude: Double? = -122.3321
    ) = Detection(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        protocol = DetectionProtocol.BLUETOOTH_LE,
        detectionMethod = DetectionMethod.RAVEN_SERVICE_UUID,
        deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
        deviceName = deviceName,
        macAddress = "11:22:33:44:55:66",
        ssid = null,
        rssi = rssi,
        signalStrength = rssiToSignalStrength(rssi),
        latitude = latitude,
        longitude = longitude,
        threatLevel = ThreatLevel.CRITICAL,
        threatScore = 100,
        manufacturer = "SoundThinking/ShotSpotter",
        firmwareVersion = firmwareVersion,
        serviceUuids = "[\"0000180a-0000-1000-8000-00805f9b34fb\",\"00003100-0000-1000-8000-00805f9b34fb\"]",
        matchedPatterns = "[\"Raven Gunshot Detection Device\"]",
        isActive = true
    )
    
    /**
     * Creates a fake Penguin surveillance detection via WiFi.
     */
    fun createPenguinWifiDetection(
        ssid: String = "Penguin_Surveillance_01",
        rssi: Int = -58
    ) = Detection(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType = DeviceType.PENGUIN_SURVEILLANCE,
        deviceName = null,
        macAddress = "DD:EE:FF:00:11:22",
        ssid = ssid,
        rssi = rssi,
        signalStrength = rssiToSignalStrength(rssi),
        latitude = 47.6062,
        longitude = -122.3321,
        threatLevel = ThreatLevel.HIGH,
        threatScore = 85,
        manufacturer = "Penguin",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = "[\"Penguin Surveillance Device\"]",
        isActive = true
    )
    
    /**
     * Creates a fake Pigvision system detection.
     */
    fun createPigvisionDetection(
        rssi: Int = -70
    ) = Detection(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType = DeviceType.PIGVISION_SYSTEM,
        deviceName = null,
        macAddress = "FF:00:11:22:33:44",
        ssid = "Pigvision_Camera_01",
        rssi = rssi,
        signalStrength = rssiToSignalStrength(rssi),
        latitude = 47.6062,
        longitude = -122.3321,
        threatLevel = ThreatLevel.HIGH,
        threatScore = 85,
        manufacturer = "Pigvision",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = "[\"Pigvision Surveillance System\"]",
        isActive = true
    )
    
    /**
     * Creates a fake MAC prefix-based detection.
     */
    fun createMacPrefixDetection(
        macAddress: String = "00:1A:2B:CC:DD:EE",
        rssi: Int = -60
    ) = Detection(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        protocol = DetectionProtocol.WIFI,
        detectionMethod = DetectionMethod.MAC_PREFIX,
        deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
        deviceName = null,
        macAddress = macAddress,
        ssid = "Unknown_Network",
        rssi = rssi,
        signalStrength = rssiToSignalStrength(rssi),
        latitude = 47.6062,
        longitude = -122.3321,
        threatLevel = ThreatLevel.CRITICAL,
        threatScore = 90,
        manufacturer = "Flock Safety",
        firmwareVersion = null,
        serviceUuids = null,
        matchedPatterns = "[\"MAC prefix: 00:1A:2B\"]",
        isActive = true
    )
    
    /**
     * Creates a list of varied detections for testing.
     */
    fun createMixedDetections(count: Int = 5): List<Detection> {
        return listOf(
            createFlockSafetyWifiDetection(),
            createRavenBleDetection(),
            createPenguinWifiDetection(),
            createPigvisionDetection(),
            createMacPrefixDetection()
        ).take(count)
    }
    
    /**
     * Creates detections with varying signal strengths for testing.
     */
    fun createDetectionsWithVaryingSignalStrengths(): List<Detection> {
        return listOf(
            createFlockSafetyWifiDetection(rssi = -40),  // Excellent
            createFlockSafetyWifiDetection(rssi = -55),  // Good
            createFlockSafetyWifiDetection(rssi = -65),  // Medium
            createFlockSafetyWifiDetection(rssi = -75),  // Weak
            createFlockSafetyWifiDetection(rssi = -85)   // Very weak
        )
    }
    
    /**
     * Creates an inactive detection.
     */
    fun createInactiveDetection() = createFlockSafetyWifiDetection().copy(
        isActive = false,
        timestamp = System.currentTimeMillis() - 120000 // 2 minutes ago
    )
}

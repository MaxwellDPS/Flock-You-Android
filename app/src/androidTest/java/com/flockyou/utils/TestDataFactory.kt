package com.flockyou.utils

import com.flockyou.data.model.*
import com.flockyou.data.*
import java.util.UUID

/**
 * Factory for creating test data objects.
 * Provides consistent test data across all test suites.
 */
object TestDataFactory {

    // ==================== Detection Test Data ====================

    fun createTestDetection(
        protocol: DetectionProtocol = DetectionProtocol.WIFI,
        detectionMethod: DetectionMethod = DetectionMethod.SSID_PATTERN,
        deviceType: DeviceType = DeviceType.FLOCK_SAFETY_CAMERA,
        threatLevel: ThreatLevel = ThreatLevel.HIGH,
        macAddress: String? = "AA:BB:CC:DD:EE:FF",
        ssid: String? = "Test-Network",
        latitude: Double? = 37.7749,
        longitude: Double? = -122.4194,
        isActive: Boolean = true
    ): Detection {
        return Detection(
            protocol = protocol,
            detectionMethod = detectionMethod,
            deviceType = deviceType,
            deviceName = "Test ${deviceType.displayName}",
            rssi = -60,
            signalStrength = SignalStrength.MEDIUM,
            threatLevel = threatLevel,
            threatScore = 75,
            macAddress = macAddress,
            ssid = ssid,
            latitude = latitude,
            longitude = longitude,
            isActive = isActive,
            manufacturer = "Test Manufacturer",
            matchedPatterns = "Test pattern match"
        )
    }

    fun createFlockSafetyCameraDetection(): Detection {
        return createTestDetection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            threatLevel = ThreatLevel.HIGH,
            macAddress = "B4:A3:82:11:22:33",
            ssid = "Flock-ABC123"
        )
    }

    fun createStingrayDetection(): Detection {
        return createTestDetection(
            protocol = DetectionProtocol.CELLULAR,
            detectionMethod = DetectionMethod.CELL_ENCRYPTION_DOWNGRADE,
            deviceType = DeviceType.STINGRAY_IMSI,
            threatLevel = ThreatLevel.CRITICAL,
            macAddress = null,
            ssid = null
        )
    }

    fun createDroneDetection(): Detection {
        return createTestDetection(
            protocol = DetectionProtocol.WIFI,
            detectionMethod = DetectionMethod.RF_DRONE,
            deviceType = DeviceType.DRONE,
            threatLevel = ThreatLevel.MEDIUM,
            macAddress = "60:60:1F:AA:BB:CC",
            ssid = "DJI_Mavic"
        )
    }

    fun createUltrasonicBeaconDetection(): Detection {
        return createTestDetection(
            protocol = DetectionProtocol.AUDIO,
            detectionMethod = DetectionMethod.ULTRASONIC_AD_BEACON,
            deviceType = DeviceType.ULTRASONIC_BEACON,
            threatLevel = ThreatLevel.HIGH,
            macAddress = null,
            ssid = "18000Hz",
            latitude = null,
            longitude = null
        )
    }

    fun createSatelliteDetection(): Detection {
        return createTestDetection(
            protocol = DetectionProtocol.SATELLITE,
            detectionMethod = DetectionMethod.SAT_UNEXPECTED_CONNECTION,
            deviceType = DeviceType.SATELLITE_NTN,
            threatLevel = ThreatLevel.MEDIUM,
            macAddress = null,
            ssid = "T-Mobile SpaceX"
        )
    }

    // ==================== Settings Test Data ====================

    fun createDefaultPrivacySettings(): PrivacySettings {
        return PrivacySettings(
            ephemeralModeEnabled = false,
            retentionPeriod = RetentionPeriod.THREE_DAYS,
            storeLocationWithDetections = true,
            autoPurgeOnScreenLock = false,
            quickWipeRequiresConfirmation = true,
            ultrasonicDetectionEnabled = false,
            ultrasonicConsentAcknowledged = false
        )
    }

    fun createPrivacyModeSettings(): PrivacySettings {
        return PrivacySettings(
            ephemeralModeEnabled = true,
            retentionPeriod = RetentionPeriod.FOUR_HOURS,
            storeLocationWithDetections = false,
            autoPurgeOnScreenLock = true,
            quickWipeRequiresConfirmation = false,
            ultrasonicDetectionEnabled = false,
            ultrasonicConsentAcknowledged = false
        )
    }

    fun createDefaultNukeSettings(): NukeSettings {
        return NukeSettings(
            nukeEnabled = false,
            wipeDatabase = true,
            wipeSettings = true,
            wipeCache = true,
            secureWipe = true,
            secureWipePasses = 3
        )
    }

    fun createFullyArmedNukeSettings(): NukeSettings {
        return NukeSettings(
            nukeEnabled = true,
            usbTriggerEnabled = true,
            failedAuthTriggerEnabled = true,
            deadManSwitchEnabled = true,
            networkIsolationTriggerEnabled = true,
            simRemovalTriggerEnabled = true,
            rapidRebootTriggerEnabled = true,
            geofenceTriggerEnabled = false,
            duressPinEnabled = true,
            wipeDatabase = true,
            wipeSettings = true,
            wipeCache = true,
            secureWipe = true,
            secureWipePasses = 3
        )
    }

    fun createSecuritySettings(
        lockEnabled: Boolean = true,
        biometricEnabled: Boolean = true,
        autoLockMinutes: Int = 5
    ): SecuritySettings {
        return SecuritySettings(
            lockEnabled = lockEnabled,
            pinHash = "test_hash",
            pinSalt = "test_salt",
            biometricEnabled = biometricEnabled,
            autoLockMinutes = autoLockMinutes
        )
    }

    // ==================== OEM Test Data ====================

    fun createDangerZone(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Police Station",
        latitude: Double = 37.7749,
        longitude: Double = -122.4194,
        radiusMeters: Float = 500f,
        enabled: Boolean = true
    ): DangerZone {
        return DangerZone(
            id = id,
            name = name,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            enabled = enabled
        )
    }

    // ==================== Collections ====================

    fun createMultipleDetections(count: Int): List<Detection> {
        return List(count) { index ->
            createTestDetection(
                macAddress = "AA:BB:CC:DD:EE:${String.format("%02X", index % 256)}",
                ssid = "Network-$index",
                threatLevel = when (index % 4) {
                    0 -> ThreatLevel.CRITICAL
                    1 -> ThreatLevel.HIGH
                    2 -> ThreatLevel.MEDIUM
                    else -> ThreatLevel.LOW
                }
            )
        }
    }

    fun createMixedProtocolDetections(): List<Detection> {
        return listOf(
            createFlockSafetyCameraDetection(),
            createStingrayDetection(),
            createDroneDetection(),
            createUltrasonicBeaconDetection(),
            createSatelliteDetection()
        )
    }
}

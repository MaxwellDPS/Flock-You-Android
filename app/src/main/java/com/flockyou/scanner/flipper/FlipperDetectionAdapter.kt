package com.flockyou.scanner.flipper

import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionType
import com.flockyou.data.model.SurveillancePattern
import com.flockyou.data.model.ThreatLevel
import java.util.UUID

/**
 * Converts Flipper Zero scan results to Detection objects for storage and display.
 */
class FlipperDetectionAdapter {

    fun wifiNetworkToDetection(
        network: FlipperWifiNetwork,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?,
        patterns: List<SurveillancePattern>
    ): Detection? {
        val threatLevel = assessWifiThreat(network, patterns)

        return Detection(
            id = UUID.randomUUID().toString(),
            deviceId = network.bssid,
            deviceName = network.ssid.ifEmpty { "[Hidden Network]" },
            deviceType = DetectionType.WIFI,
            threatLevel = threatLevel,
            signalStrength = network.rssi,
            latitude = latitude,
            longitude = longitude,
            firstSeen = timestamp,
            lastSeen = timestamp,
            metadata = mapOf(
                "channel" to network.channel.toString(),
                "security" to network.security.name,
                "hidden" to network.hidden.toString(),
                "source" to "flipper"
            )
        )
    }

    fun subGhzDetectionToDetection(
        detection: FlipperSubGhzDetection,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?
    ): Detection {
        val threatLevel = assessSubGhzThreat(detection)
        val freqDesc = SubGhzFrequencies.formatFrequency(detection.frequency)

        return Detection(
            id = UUID.randomUUID().toString(),
            deviceId = "subghz_${detection.frequency}_${detection.protocolId}",
            deviceName = "${detection.protocolName} @ $freqDesc",
            deviceType = DetectionType.RF,
            threatLevel = threatLevel,
            signalStrength = detection.rssi,
            latitude = latitude,
            longitude = longitude,
            firstSeen = timestamp,
            lastSeen = timestamp,
            metadata = mapOf(
                "frequency" to detection.frequency.toString(),
                "modulation" to detection.modulation.name,
                "duration_ms" to detection.durationMs.toString(),
                "protocol_id" to detection.protocolId.toString(),
                "protocol_name" to detection.protocolName,
                "source" to "flipper"
            )
        )
    }

    fun bleDeviceToDetection(
        device: FlipperBleDevice,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?
    ): Detection? {
        val threatLevel = assessBleThreat(device)

        return Detection(
            id = UUID.randomUUID().toString(),
            deviceId = device.macAddress,
            deviceName = device.name.ifEmpty { "[Unknown BLE Device]" },
            deviceType = DetectionType.BLUETOOTH,
            threatLevel = threatLevel,
            signalStrength = device.rssi,
            latitude = latitude,
            longitude = longitude,
            firstSeen = timestamp,
            lastSeen = timestamp,
            metadata = mapOf(
                "address_type" to device.addressType.name,
                "connectable" to device.isConnectable.toString(),
                "manufacturer_id" to device.manufacturerId.toString(),
                "service_uuids" to device.serviceUuids.joinToString(","),
                "source" to "flipper"
            )
        )
    }

    fun irDetectionToDetection(
        detection: FlipperIrDetection,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?
    ): Detection {
        return Detection(
            id = UUID.randomUUID().toString(),
            deviceId = "ir_${detection.address}_${detection.command}",
            deviceName = "${detection.protocolName} IR Signal",
            deviceType = DetectionType.IR,
            threatLevel = ThreatLevel.LOW,
            signalStrength = detection.signalStrength,
            latitude = latitude,
            longitude = longitude,
            firstSeen = timestamp,
            lastSeen = timestamp,
            metadata = mapOf(
                "protocol_id" to detection.protocolId.toString(),
                "protocol_name" to detection.protocolName,
                "address" to detection.address.toString(),
                "command" to detection.command.toString(),
                "is_repeat" to detection.isRepeat.toString(),
                "source" to "flipper"
            )
        )
    }

    fun nfcDetectionToDetection(
        detection: FlipperNfcDetection,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?
    ): Detection {
        return Detection(
            id = UUID.randomUUID().toString(),
            deviceId = "nfc_${detection.uidString}",
            deviceName = "${detection.typeName} NFC Tag",
            deviceType = DetectionType.NFC,
            threatLevel = ThreatLevel.LOW,
            signalStrength = null,
            latitude = latitude,
            longitude = longitude,
            firstSeen = timestamp,
            lastSeen = timestamp,
            metadata = mapOf(
                "uid" to detection.uidString,
                "nfc_type" to detection.nfcType.name,
                "sak" to detection.sak.toString(),
                "type_name" to detection.typeName,
                "source" to "flipper"
            )
        )
    }

    fun wipsEventToDetection(
        event: FlipperWipsEvent,
        latitude: Double?,
        longitude: Double?
    ): Detection {
        val threatLevel = when (event.severity) {
            WipsSeverity.CRITICAL -> ThreatLevel.CRITICAL
            WipsSeverity.HIGH -> ThreatLevel.HIGH
            WipsSeverity.MEDIUM -> ThreatLevel.MEDIUM
            WipsSeverity.LOW -> ThreatLevel.LOW
            WipsSeverity.INFO -> ThreatLevel.SAFE
        }

        return Detection(
            id = UUID.randomUUID().toString(),
            deviceId = "wips_${event.type.name}_${event.bssid}",
            deviceName = "WIPS Alert: ${event.type.name}",
            deviceType = DetectionType.WIPS,
            threatLevel = threatLevel,
            signalStrength = null,
            latitude = latitude,
            longitude = longitude,
            firstSeen = event.timestamp,
            lastSeen = event.timestamp,
            metadata = mapOf(
                "alert_type" to event.type.name,
                "ssid" to event.ssid,
                "bssid" to event.bssid,
                "description" to event.description,
                "severity" to event.severity.name,
                "source" to "flipper_wips"
            )
        )
    }

    private fun assessWifiThreat(network: FlipperWifiNetwork, patterns: List<SurveillancePattern>): ThreatLevel {
        // Check against surveillance patterns
        for (pattern in patterns) {
            if (pattern.matchesSsid(network.ssid) || pattern.matchesBssid(network.bssid)) {
                return ThreatLevel.HIGH
            }
        }

        // Weak encryption
        if (network.security == WifiSecurityType.WEP || network.security == WifiSecurityType.OPEN) {
            return ThreatLevel.MEDIUM
        }

        // Strong hidden network could be suspicious
        if (network.hidden && network.rssi > -60) {
            return ThreatLevel.MEDIUM
        }

        return ThreatLevel.SAFE
    }

    private fun assessSubGhzThreat(detection: FlipperSubGhzDetection): ThreatLevel {
        // Unknown protocols at certain frequencies may be suspicious
        if (detection.protocolId == 0) {
            // Strong unknown signal at known tracker frequencies
            if (detection.rssi > -50 && isTrackerFrequency(detection.frequency)) {
                return ThreatLevel.HIGH
            }
            return ThreatLevel.MEDIUM
        }

        // Known rolling code protocols (car remotes) - informational
        if (detection.protocolId == SubGhzProtocols.KEELOQ) {
            return ThreatLevel.LOW
        }

        return ThreatLevel.LOW
    }

    private fun assessBleThreat(device: FlipperBleDevice): ThreatLevel {
        // Apple/Google Find My type trackers
        if (isKnownTrackerManufacturer(device.manufacturerId)) {
            return ThreatLevel.HIGH
        }

        // Unknown device with strong signal following you
        if (device.name.isEmpty() && device.rssi > -50) {
            return ThreatLevel.MEDIUM
        }

        return ThreatLevel.SAFE
    }

    private fun isTrackerFrequency(frequency: Long): Boolean {
        val trackerFrequencies = listOf(
            433_920_000L, // Common tracker frequency
            868_350_000L, // EU ISM
            915_000_000L  // US ISM
        )
        return trackerFrequencies.any { kotlin.math.abs(frequency - it) < 1_000_000 }
    }

    private fun isKnownTrackerManufacturer(manufacturerId: Int): Boolean {
        val trackerManufacturers = setOf(
            0x004C, // Apple
            0x00E0, // Google
            0x0075  // Samsung
        )
        return manufacturerId in trackerManufacturers
    }
}

// Extension function for pattern matching
private fun SurveillancePattern.matchesSsid(ssid: String): Boolean {
    return this.ssidPatterns?.any { pattern ->
        try {
            ssid.contains(pattern, ignoreCase = true) ||
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(ssid)
        } catch (_: Exception) {
            ssid.contains(pattern, ignoreCase = true)
        }
    } ?: false
}

private fun SurveillancePattern.matchesBssid(bssid: String): Boolean {
    return this.macPrefixes?.any { prefix ->
        bssid.uppercase().startsWith(prefix.uppercase())
    } ?: false
}

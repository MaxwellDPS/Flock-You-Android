package com.flockyou.scanner.flipper

import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionMethod
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DetectionSource
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.SignalStrength
import com.flockyou.data.model.SurveillancePattern
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.rssiToSignalStrength
import com.flockyou.detection.framework.*
import java.util.UUID

/**
 * Converts Flipper Zero scan results to Detection objects for storage and display.
 *
 * Enhanced with the unified detection framework for:
 * - Expanded tracker manufacturer database
 * - iBeacon and Eddystone beacon parsing
 * - Rotating MAC address detection
 * - Unwanted tracking protocol detection (FD6F)
 * - Cross-domain threat correlation
 */
class FlipperDetectionAdapter {

    // Framework components
    private val bleAddressTracker = BleAddressTracker()
    private val unwantedTrackingDetector = UnwantedTrackingDetector()
    private val crossDomainCorrelator = CrossDomainCorrelator()

    fun wifiNetworkToDetection(
        network: FlipperWifiNetwork,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?,
        patterns: List<SurveillancePattern>
    ): Detection? {
        val threatLevel = assessWifiThreat(network, patterns)

        val detectionId = UUID.randomUUID().toString()

        // Register with cross-domain correlator
        val indicators = mutableListOf<String>()
        if (network.hidden) indicators.add("Hidden network")
        if (network.security == WifiSecurityType.OPEN) indicators.add("Open network (no encryption)")
        if (network.security == WifiSecurityType.WEP) indicators.add("Weak WEP encryption")

        crossDomainCorrelator.registerWifiDetection(
            id = detectionId,
            ssid = network.ssid,
            bssid = network.bssid,
            channel = network.channel,
            rssi = network.rssi,
            latitude = latitude,
            longitude = longitude,
            threatLevel = threatLevel,
            indicators = indicators
        )

        return Detection(
            id = detectionId,
            timestamp = timestamp,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = if (network.hidden) DetectionMethod.BEACON_FRAME else DetectionMethod.SSID_PATTERN,
            deviceType = DeviceType.ROGUE_AP,
            deviceName = network.ssid.ifEmpty { "[Hidden Network]" },
            macAddress = network.bssid,
            ssid = network.ssid.ifEmpty { null },
            rssi = network.rssi,
            signalStrength = rssiToSignalStrength(network.rssi),
            latitude = latitude,
            longitude = longitude,
            threatLevel = threatLevel,
            detectionSource = DetectionSource.FLIPPER_WIFI,
            rawData = "channel:${network.channel},security:${network.security.name},hidden:${network.hidden}",
            lastSeenTimestamp = timestamp
        )
    }

    fun subGhzDetectionToDetection(
        detection: FlipperSubGhzDetection,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?
    ): Detection {
        val threatLevel = assessEnhancedSubGhzThreat(detection)
        val freqDesc = SubGhzFrequencies.formatFrequency(detection.frequency)

        // Look up matching RF tracker signatures
        val matchedSignatures = TrackerDatabase.findRfByFrequency(detection.frequency)
        val primaryMatch = matchedSignatures.firstOrNull()

        // Build enhanced metadata
        val metadata = mutableMapOf(
            "frequency" to detection.frequency.toString(),
            "modulation" to detection.modulation.name,
            "duration_ms" to detection.durationMs.toString(),
            "protocol_id" to detection.protocolId.toString(),
            "protocol_name" to detection.protocolName
        )

        // Add tracker signature info if matched
        primaryMatch?.let { sig ->
            metadata["tracker_category"] = sig.category.displayName
            metadata["matched_signature"] = sig.name
            metadata["signature_threat"] = sig.threatLevel.name
        }

        // Determine device name
        val deviceName = when {
            primaryMatch != null -> "${primaryMatch.name} @ $freqDesc"
            detection.protocolName.isNotEmpty() && detection.protocolName != "Unknown" ->
                "${detection.protocolName} @ $freqDesc"
            else -> "RF Signal @ $freqDesc"
        }

        // Register with cross-domain correlator
        val detectionId = UUID.randomUUID().toString()
        val indicators = mutableListOf<String>()

        if (primaryMatch != null) {
            indicators.add("Matches ${primaryMatch.name} signature")
            if (primaryMatch.category == TrackerCategory.GPS_TRACKER) {
                indicators.add("GPS tracker frequency band")
            }
        }

        if (isTrackerFrequency(detection.frequency)) {
            indicators.add("Known tracker frequency range")
        }

        crossDomainCorrelator.registerRfDetection(
            id = detectionId,
            frequency = detection.frequency,
            modulation = detection.modulation.name,
            protocolName = detection.protocolName.takeIf { it.isNotEmpty() },
            rssi = detection.rssi,
            latitude = latitude,
            longitude = longitude,
            threatLevel = threatLevel,
            indicators = indicators
        )

        return Detection(
            id = detectionId,
            timestamp = timestamp,
            protocol = DetectionProtocol.RF,
            detectionMethod = DetectionMethod.RF_SPECTRUM_ANOMALY,
            deviceType = if (primaryMatch?.category == TrackerCategory.GPS_TRACKER) DeviceType.TRACKING_DEVICE else DeviceType.RF_ANOMALY,
            deviceName = deviceName,
            macAddress = "subghz_${detection.frequency}_${detection.protocolId}",
            rssi = detection.rssi,
            signalStrength = rssiToSignalStrength(detection.rssi),
            latitude = latitude,
            longitude = longitude,
            threatLevel = threatLevel,
            detectionSource = DetectionSource.FLIPPER_SUBGHZ,
            rawData = metadata.entries.joinToString(",") { "${it.key}:${it.value}" },
            lastSeenTimestamp = timestamp
        )
    }

    /**
     * Enhanced Sub-GHz threat assessment using tracker database
     */
    private fun assessEnhancedSubGhzThreat(detection: FlipperSubGhzDetection): ThreatLevel {
        // Check against tracker database
        val matchedSignatures = TrackerDatabase.findRfByFrequency(detection.frequency)

        // High threat if matches known GPS tracker signature with strong signal
        if (matchedSignatures.any { it.category == TrackerCategory.GPS_TRACKER } && detection.rssi > -60) {
            return ThreatLevel.HIGH
        }

        // Medium if matches any tracker signature
        if (matchedSignatures.isNotEmpty()) {
            return ThreatLevel.MEDIUM
        }

        // Check for unknown protocol on tracker frequencies
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

    fun bleDeviceToDetection(
        device: FlipperBleDevice,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?
    ): Detection? {
        // Process through enhanced detection framework
        val trackingAnalysis = bleAddressTracker.processAdvertisement(
            macAddress = device.macAddress,
            deviceName = device.name.takeIf { it.isNotEmpty() },
            manufacturerId = device.manufacturerId.takeIf { it != 0 },
            manufacturerData = device.manufacturerData.takeIf { it.isNotEmpty() },
            serviceUuids = device.serviceUuids,
            rssi = device.rssi,
            latitude = latitude,
            longitude = longitude
        )

        // Process for unwanted tracking (AirTag stalking detection)
        val unwantedResult = unwantedTrackingDetector.processAdvertisement(
            macAddress = device.macAddress,
            deviceName = device.name.takeIf { it.isNotEmpty() },
            manufacturerId = device.manufacturerId.takeIf { it != 0 },
            manufacturerData = device.manufacturerData.takeIf { it.isNotEmpty() },
            serviceUuids = device.serviceUuids,
            rssi = device.rssi,
            latitude = latitude,
            longitude = longitude
        )

        // Parse beacon protocols
        val beaconType = BeaconParser.detectBeaconType(
            manufacturerId = device.manufacturerId.takeIf { it != 0 },
            manufacturerData = device.manufacturerData.takeIf { it.isNotEmpty() },
            serviceUuids = device.serviceUuids,
            serviceData = emptyMap()  // Flipper doesn't provide service data yet
        )

        // Parse iBeacon if applicable
        val iBeaconData = if (beaconType == BeaconProtocolType.IBEACON && device.manufacturerData.isNotEmpty()) {
            BeaconParser.parseIBeacon(device.manufacturerId, device.manufacturerData, device.rssi)
        } else null

        // Assess threat using enhanced analysis
        val threatLevel = assessEnhancedBleThreat(device, trackingAnalysis, unwantedResult, beaconType)

        // Build enhanced metadata
        val metadata = mutableMapOf(
            "address_type" to device.addressType.name,
            "connectable" to device.isConnectable.toString(),
            "manufacturer_id" to device.manufacturerId.toString(),
            "service_uuids" to device.serviceUuids.joinToString(","),
            "beacon_protocol" to beaconType.displayName
        )

        // Add tracking analysis metadata
        if (trackingAnalysis.isRotatingAddress) {
            metadata["rotating_address"] = "true"
            metadata["unique_macs"] = trackingAnalysis.uniqueAddressCount.toString()
        }
        if (trackingAnalysis.isFollowing) {
            metadata["following"] = "true"
            metadata["locations_followed"] = trackingAnalysis.uniqueLocationsCount.toString()
        }
        if (unwantedResult.hasSeparationAlert) {
            metadata["separation_alert"] = "true"
            metadata["tracker_type"] = unwantedResult.trackerType.displayName
        }

        // Add iBeacon data if present
        iBeaconData?.let {
            metadata["ibeacon_uuid"] = it.uuid
            metadata["ibeacon_major"] = it.major.toString()
            metadata["ibeacon_minor"] = it.minor.toString()
            metadata["ibeacon_proximity"] = it.getProximity().displayName
        }

        // Add matched signatures
        if (trackingAnalysis.matchedSignatures.isNotEmpty()) {
            metadata["matched_signatures"] = trackingAnalysis.matchedSignatures.joinToString(",") { it.name }
        }

        // Get manufacturer name from database
        val manufacturerInfo = TrackerDatabase.getManufacturerInfo(device.manufacturerId)
        val deviceName = when {
            device.name.isNotEmpty() -> device.name
            manufacturerInfo != null -> "${manufacturerInfo.name} Device"
            trackingAnalysis.matchedSignatures.isNotEmpty() -> trackingAnalysis.matchedSignatures.first().name
            else -> "[Unknown BLE Device]"
        }

        // Register with cross-domain correlator
        val detectionId = UUID.randomUUID().toString()
        crossDomainCorrelator.registerBleDetection(
            id = detectionId,
            macAddress = device.macAddress,
            deviceName = deviceName,
            manufacturerId = device.manufacturerId.takeIf { it != 0 },
            serviceUuids = device.serviceUuids,
            rssi = device.rssi,
            latitude = latitude,
            longitude = longitude,
            threatLevel = threatLevel,
            indicators = trackingAnalysis.threatIndicators + unwantedResult.indicators
        )

        return Detection(
            id = detectionId,
            timestamp = timestamp,
            protocol = DetectionProtocol.BLUETOOTH_LE,
            detectionMethod = DetectionMethod.BLE_DEVICE_NAME,
            deviceType = when {
                unwantedResult.hasSeparationAlert -> DeviceType.AIRTAG
                trackingAnalysis.matchedSignatures.isNotEmpty() -> DeviceType.GENERIC_BLE_TRACKER
                beaconType == BeaconProtocolType.IBEACON -> DeviceType.BLUETOOTH_BEACON
                else -> DeviceType.UNKNOWN_SURVEILLANCE
            },
            deviceName = deviceName,
            macAddress = device.macAddress,
            rssi = device.rssi,
            signalStrength = rssiToSignalStrength(device.rssi),
            latitude = latitude,
            longitude = longitude,
            threatLevel = threatLevel,
            detectionSource = DetectionSource.FLIPPER_BLE,
            serviceUuids = device.serviceUuids.joinToString(",").takeIf { it.isNotEmpty() },
            rawData = metadata.entries.joinToString(",") { "${it.key}:${it.value}" },
            lastSeenTimestamp = timestamp
        )
    }

    /**
     * Enhanced BLE threat assessment using the detection framework
     */
    private fun assessEnhancedBleThreat(
        device: FlipperBleDevice,
        trackingAnalysis: BleTrackingAnalysis,
        unwantedResult: UnwantedTrackingResult,
        beaconType: BeaconProtocolType
    ): ThreatLevel {
        // Critical: Unwanted tracking alert (separated AirTag)
        if (unwantedResult.hasSeparationAlert) {
            return ThreatLevel.CRITICAL
        }

        // High: Device is following across multiple locations
        if (trackingAnalysis.isFollowing && trackingAnalysis.uniqueLocationsCount >= 3) {
            return ThreatLevel.HIGH
        }

        // High: Known tracker with strong signal
        if (trackingAnalysis.matchedSignatures.any { it.threatLevel == ThreatLevel.HIGH } &&
            device.rssi > -60) {
            return ThreatLevel.HIGH
        }

        // High: Rotating address with following pattern
        if (trackingAnalysis.isRotatingAddress && trackingAnalysis.followingScore > 0.5f) {
            return ThreatLevel.HIGH
        }

        // Medium: Known tracker manufacturer
        if (TrackerDatabase.isKnownTrackerManufacturer(device.manufacturerId)) {
            return ThreatLevel.MEDIUM
        }

        // Medium: Retail/advertising beacon
        if (beaconType in listOf(
                BeaconProtocolType.IBEACON,
                BeaconProtocolType.EDDYSTONE_UID,
                BeaconProtocolType.EDDYSTONE_URL
            )) {
            return ThreatLevel.MEDIUM
        }

        // Low: Rotating address (privacy feature but suspicious)
        if (trackingAnalysis.isRotatingAddress) {
            return ThreatLevel.LOW
        }

        // Low: Unknown device with strong signal
        if (device.name.isEmpty() && device.rssi > -50) {
            return ThreatLevel.LOW
        }

        return ThreatLevel.INFO
    }

    fun irDetectionToDetection(
        detection: FlipperIrDetection,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?
    ): Detection {
        return Detection(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            protocol = DetectionProtocol.RF,
            detectionMethod = DetectionMethod.RF_HIDDEN_TRANSMITTER,
            deviceType = DeviceType.HIDDEN_TRANSMITTER,
            deviceName = "${detection.protocolName} IR Signal",
            macAddress = "ir_${detection.address}_${detection.command}",
            rssi = detection.signalStrength,
            signalStrength = rssiToSignalStrength(detection.signalStrength),
            latitude = latitude,
            longitude = longitude,
            threatLevel = ThreatLevel.LOW,
            detectionSource = DetectionSource.FLIPPER_IR,
            rawData = "protocol_id:${detection.protocolId},protocol_name:${detection.protocolName},address:${detection.address},command:${detection.command},is_repeat:${detection.isRepeat}",
            lastSeenTimestamp = timestamp
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
            timestamp = timestamp,
            protocol = DetectionProtocol.RF,
            detectionMethod = DetectionMethod.RF_HIDDEN_TRANSMITTER,
            deviceType = DeviceType.GENERIC_BLE_TRACKER,
            deviceName = "${detection.typeName} NFC Tag",
            macAddress = "nfc_${detection.uidString}",
            rssi = 0,
            signalStrength = SignalStrength.UNKNOWN,
            latitude = latitude,
            longitude = longitude,
            threatLevel = ThreatLevel.LOW,
            detectionSource = DetectionSource.FLIPPER_NFC,
            rawData = "uid:${detection.uidString},nfc_type:${detection.nfcType.name},sak:${detection.sak},type_name:${detection.typeName}",
            lastSeenTimestamp = timestamp
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
            WipsSeverity.INFO -> ThreatLevel.INFO
        }

        return Detection(
            id = UUID.randomUUID().toString(),
            timestamp = event.timestamp,
            protocol = DetectionProtocol.WIFI,
            detectionMethod = when (event.type) {
                FlipperWipsEventType.EVIL_TWIN_DETECTED -> DetectionMethod.WIFI_EVIL_TWIN
                FlipperWipsEventType.DEAUTH_DETECTED -> DetectionMethod.WIFI_DEAUTH_ATTACK
                FlipperWipsEventType.KARMA_DETECTED -> DetectionMethod.WIFI_KARMA_ATTACK
                FlipperWipsEventType.ROGUE_AP -> DetectionMethod.WIFI_ROGUE_AP
                else -> DetectionMethod.WIFI_SIGNAL_ANOMALY
            },
            deviceType = DeviceType.ROGUE_AP,
            deviceName = "WIPS Alert: ${event.type.name}",
            macAddress = event.bssid,
            ssid = event.ssid.takeIf { it.isNotEmpty() },
            rssi = 0,
            signalStrength = SignalStrength.UNKNOWN,
            latitude = latitude,
            longitude = longitude,
            threatLevel = threatLevel,
            detectionSource = DetectionSource.FLIPPER_WIPS,
            rawData = "alert_type:${event.type.name},ssid:${event.ssid},bssid:${event.bssid},description:${event.description},severity:${event.severity.name}",
            lastSeenTimestamp = event.timestamp
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

        return ThreatLevel.INFO
    }

    private fun isTrackerFrequency(frequency: Long): Boolean {
        // Use expanded tracker database
        val allTrackerRanges = TrackerDatabase.getAllRfTrackerFrequencies()
        return allTrackerRanges.any { range -> range.contains(frequency) }
    }

    // ============================================================================
    // Framework Accessors
    // ============================================================================

    /**
     * Get suspicious BLE devices detected by address tracking
     */
    fun getSuspiciousBleDevices() = bleAddressTracker.suspiciousDevices

    /**
     * Get devices that appear to be following the user
     */
    fun getFollowingDevices() = bleAddressTracker.followingDevices

    /**
     * Get unwanted tracking alerts (AirTag stalking etc.)
     */
    fun getUnwantedTrackingAlerts() = unwantedTrackingDetector.alerts

    /**
     * Get currently active tracking threats
     */
    fun getActiveTrackingThreats() = unwantedTrackingDetector.activeThreats

    /**
     * Get cross-domain correlated threats
     */
    fun getCorrelatedThreats() = crossDomainCorrelator.threats

    /**
     * Get correlation statistics
     */
    fun getCorrelationStats() = crossDomainCorrelator.getStats()

    /**
     * Clear all detection framework state
     */
    fun clearFrameworkState() {
        bleAddressTracker.clear()
        unwantedTrackingDetector.clear()
        crossDomainCorrelator.clear()
    }

    /**
     * Clear only unwanted tracking alerts
     */
    fun clearUnwantedTrackingAlerts() {
        unwantedTrackingDetector.clearAlerts()
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

package com.flockyou.testmode

import com.flockyou.data.model.DeviceType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Provides mock data for test scenarios.
 *
 * This class returns realistic mock detection data based on actual device signatures
 * documented in DetectionPatterns.kt, including:
 * - Real MAC address OUI prefixes for surveillance equipment manufacturers
 * - Authentic SSID patterns for Flock Safety, police equipment, etc.
 * - BLE service UUIDs for AirTags, Tiles, and other trackers
 * - Realistic signal strengths and cellular anomaly indicators
 */
@Singleton
class TestScenarioProvider @Inject constructor() {

    /**
     * Get all available test scenarios.
     */
    fun getAllScenarios(): List<TestScenario> = TestScenario.all()

    /**
     * Get a scenario by its ID.
     */
    fun getScenario(id: String): TestScenario? = TestScenario.fromId(id)

    /**
     * Get realistic mock data for a specific scenario.
     *
     * This method returns TestScenarioData populated with realistic device
     * signatures that match actual surveillance equipment patterns.
     *
     * @param scenario The scenario to get data for
     * @return TestScenarioData with mock devices
     */
    fun getScenarioData(scenario: TestScenario): TestScenarioData {
        return getScenarioDataById(scenario.id)
    }

    /**
     * Get scenario data by ID.
     */
    fun getScenarioDataById(scenarioId: String): TestScenarioData {
        return when (scenarioId) {
            "tracker_following" -> createTrackerFollowingData()
            "cell_site_simulator" -> createStingrayData()
            "gnss_spoofing" -> createGnssSpoofingData()
            "surveillance_camera" -> createSurveillanceCameraData()
            "ultrasonic_beacon" -> createUltrasonicBeaconData()
            "high_threat_environment" -> createHighThreatEnvironmentData()
            "normal_environment" -> createNormalEnvironmentData()
            "drone_surveillance" -> createDroneSurveillanceData()
            else -> TestScenarioData()
        }
    }

    // ==================== Scenario Data Generators ====================

    /**
     * Creates mock data for tracker following scenario.
     * Includes AirTag, Tile, and SmartTag trackers.
     */
    private fun createTrackerFollowingData(): TestScenarioData {
        return TestScenarioData(
            bleDevices = listOf(
                // Apple AirTag - uses Find My network
                MockBleDevice(
                    name = null,  // AirTags don't broadcast names
                    macAddress = generateAppleMac(),
                    rssi = varyRssi(-65),  // Strong signal = close proximity
                    serviceUuids = listOf(
                        "7DFC9000-7D1C-4951-86AA-8D9728F8D66C"  // Apple Find My service UUID
                    ),
                    manufacturerId = MockBleDevice.MANUFACTURER_APPLE,
                    manufacturerData = generateAirTagManufacturerData(),
                    deviceType = DeviceType.AIRTAG
                ),
                // Tile Pro Tracker
                MockBleDevice(
                    name = "Tile",
                    macAddress = generateRandomMac("C0:A5:3E"),  // Nordic Semiconductor
                    rssi = varyRssi(-70),
                    serviceUuids = listOf(
                        "0000FEED-0000-1000-8000-00805F9B34FB"  // Tile service UUID
                    ),
                    manufacturerId = MockBleDevice.MANUFACTURER_TILE,
                    manufacturerData = null,
                    deviceType = DeviceType.TILE_TRACKER
                )
            )
        )
    }

    /**
     * Creates mock data for StingRay/IMSI catcher scenario.
     */
    private fun createStingrayData(): TestScenarioData {
        return TestScenarioData(
            cellularState = MockCellularState(
                cellId = 999999,  // Suspicious cell ID
                lac = 65535,  // Maximum LAC - suspicious
                mcc = 310,  // USA
                mnc = 260,  // T-Mobile
                signalStrength = -45,  // Unusually strong
                networkType = "GSM",  // Forced 2G downgrade
                anomalyType = CellularAnomalyType.ENCRYPTION_DOWNGRADE
            )
        )
    }

    /**
     * Creates mock data for GNSS spoofing scenario.
     */
    private fun createGnssSpoofingData(): TestScenarioData {
        return TestScenarioData(
            gnssState = MockGnssState(
                satellites = listOf(
                    // All satellites with suspiciously similar CN0 values
                    MockSatellite(1, "GPS", 48.0, 45f, 90f),
                    MockSatellite(3, "GPS", 47.5, 45f, 180f),
                    MockSatellite(6, "GPS", 48.2, 45f, 270f),
                    MockSatellite(9, "GPS", 47.8, 45f, 360f)
                ),
                cn0Average = 47.9,  // Suspiciously uniform
                hdop = 0.8,  // Too good
                isJammed = false,
                isSpoofed = true
            )
        )
    }

    /**
     * Creates mock data for surveillance camera detection.
     * Based on real Flock Safety and similar camera patterns.
     */
    private fun createSurveillanceCameraData(): TestScenarioData {
        return TestScenarioData(
            wifiNetworks = listOf(
                // Flock Safety ALPR camera
                MockWifiNetwork(
                    ssid = "Flock-${generateHexString(6).uppercase()}",
                    bssid = generateRandomMac("B4:A3:82"),  // Quectel OUI (used in Flock)
                    rssi = varyRssi(-55),
                    frequency = 2437,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isHidden = false,
                    deviceType = DeviceType.FLOCK_SAFETY_CAMERA
                ),
                // Penguin camera
                MockWifiNetwork(
                    ssid = "PENGUIN_CAM_${generateHexString(4).uppercase()}",
                    bssid = generateRandomMac("00:1A:2B"),
                    rssi = varyRssi(-60),
                    frequency = 5180,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isHidden = false,
                    deviceType = DeviceType.PENGUIN_SURVEILLANCE
                )
            ),
            bleDevices = listOf(
                // Camera BLE beacon
                MockBleDevice(
                    name = "CAM-${generateHexString(4).uppercase()}",
                    macAddress = generateRandomMac("B4:A3:82"),
                    rssi = varyRssi(-60),
                    serviceUuids = emptyList(),
                    manufacturerId = null,
                    manufacturerData = null,
                    deviceType = DeviceType.FLOCK_SAFETY_CAMERA
                )
            )
        )
    }

    /**
     * Creates mock data for ultrasonic beacon detection.
     */
    private fun createUltrasonicBeaconData(): TestScenarioData {
        return TestScenarioData(
            audioBeacons = listOf(
                // SilverPush-style cross-device tracking
                MockAudioBeacon(
                    frequencyHz = 18000,
                    amplitudeDb = -25.0,
                    durationMs = 3000,
                    beaconType = MockAudioBeacon.TYPE_AD_TRACKING
                ),
                // Alphonso TV attribution
                MockAudioBeacon(
                    frequencyHz = 19500,
                    amplitudeDb = -30.0,
                    durationMs = 5000,
                    beaconType = MockAudioBeacon.TYPE_TV_ATTRIBUTION
                ),
                // Shopkick retail beacon
                MockAudioBeacon(
                    frequencyHz = 20000,
                    amplitudeDb = -28.0,
                    durationMs = 2000,
                    beaconType = MockAudioBeacon.TYPE_RETAIL_ANALYTICS
                )
            )
        )
    }

    /**
     * Creates mock data for high-threat environment.
     * Combines multiple threat types for stress testing.
     */
    private fun createHighThreatEnvironmentData(): TestScenarioData {
        return TestScenarioData(
            wifiNetworks = listOf(
                // Flock camera
                MockWifiNetwork(
                    ssid = "Flock-${generateHexString(6).uppercase()}",
                    bssid = generateRandomMac("B4:A3:82"),
                    rssi = varyRssi(-50),
                    frequency = 2437,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isHidden = false,
                    deviceType = DeviceType.FLOCK_SAFETY_CAMERA
                ),
                // Police body camera
                MockWifiNetwork(
                    ssid = "Axon_Body_${generateHexString(4).uppercase()}",
                    bssid = generateRandomMac("00:1A:2B"),
                    rssi = varyRssi(-55),
                    frequency = 5180,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isHidden = true,
                    deviceType = DeviceType.AXON_POLICE_TECH
                )
            ),
            bleDevices = listOf(
                // Following AirTag
                MockBleDevice(
                    name = null,
                    macAddress = generateAppleMac(),
                    rssi = varyRssi(-60),
                    serviceUuids = listOf("7DFC9000-7D1C-4951-86AA-8D9728F8D66C"),
                    manufacturerId = MockBleDevice.MANUFACTURER_APPLE,
                    manufacturerData = generateAirTagManufacturerData(),
                    deviceType = DeviceType.AIRTAG
                ),
                // Police radio
                MockBleDevice(
                    name = "APX",
                    macAddress = generateRandomMac("00:1E:C0"),  // Motorola OUI
                    rssi = varyRssi(-55),
                    serviceUuids = emptyList(),
                    manufacturerId = null,
                    manufacturerData = null,
                    deviceType = DeviceType.MOTOROLA_POLICE_TECH
                )
            ),
            cellularState = MockCellularState(
                cellId = 999999,
                lac = 65535,
                mcc = 310,
                mnc = 260,
                signalStrength = -50,
                networkType = "GSM",
                anomalyType = CellularAnomalyType.ENCRYPTION_DOWNGRADE
            ),
            gnssState = MockGnssState(
                satellites = listOf(
                    MockSatellite(1, "GPS", 20.0, 45f, 90f),
                    MockSatellite(3, "GPS", 18.0, 30f, 180f),
                    MockSatellite(6, "GPS", 15.0, 60f, 270f)
                ),
                cn0Average = 17.7,
                hdop = 8.5,
                isJammed = true,
                isSpoofed = false
            )
        )
    }

    /**
     * Creates mock data for normal/benign environment.
     * Used for false positive testing.
     */
    private fun createNormalEnvironmentData(): TestScenarioData {
        return TestScenarioData(
            wifiNetworks = listOf(
                MockWifiNetwork(
                    ssid = "xfinitywifi",
                    bssid = generateRandomMac("44:E5:17"),  // Comcast OUI
                    rssi = varyRssi(-70),
                    frequency = 2437,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isHidden = false,
                    deviceType = DeviceType.UNKNOWN_SURVEILLANCE
                ),
                MockWifiNetwork(
                    ssid = "Starbucks WiFi",
                    bssid = generateRandomMac("00:1A:2B"),
                    rssi = varyRssi(-65),
                    frequency = 5180,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isHidden = false,
                    deviceType = DeviceType.UNKNOWN_SURVEILLANCE
                )
            ),
            bleDevices = listOf(
                MockBleDevice(
                    name = "JBL Flip 6",
                    macAddress = generateRandomMac("20:74:CF"),
                    rssi = varyRssi(-50),
                    serviceUuids = listOf("0000111E-0000-1000-8000-00805F9B34FB"),
                    manufacturerId = null,
                    manufacturerData = null,
                    deviceType = DeviceType.UNKNOWN_SURVEILLANCE
                ),
                MockBleDevice(
                    name = "Fitbit Versa",
                    macAddress = generateRandomMac("D0:B0:CD"),
                    rssi = varyRssi(-60),
                    serviceUuids = emptyList(),
                    manufacturerId = null,
                    manufacturerData = null,
                    deviceType = DeviceType.UNKNOWN_SURVEILLANCE
                )
            )
        )
    }

    /**
     * Creates mock data for drone surveillance scenario.
     */
    private fun createDroneSurveillanceData(): TestScenarioData {
        return TestScenarioData(
            wifiNetworks = listOf(
                // DJI drone WiFi
                MockWifiNetwork(
                    ssid = "DJI_Mavic3_${generateHexString(4).uppercase()}",
                    bssid = generateRandomMac("60:60:1F"),  // DJI OUI
                    rssi = varyRssi(-50),
                    frequency = 5745,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    isHidden = false,
                    deviceType = DeviceType.DRONE
                )
            ),
            bleDevices = listOf(
                // DJI controller BLE
                MockBleDevice(
                    name = "RC-N1",
                    macAddress = generateRandomMac("60:60:1F"),
                    rssi = varyRssi(-55),
                    serviceUuids = emptyList(),
                    manufacturerId = null,
                    manufacturerData = null,
                    deviceType = DeviceType.DRONE
                )
            )
        )
    }

    // ==================== Helper Methods ====================

    private fun generateRandomMac(prefix: String? = null): String {
        val bytes = if (prefix != null) {
            val prefixBytes = prefix.split(":").map { it.toInt(16) }
            prefixBytes + (0 until (6 - prefixBytes.size)).map { Random.nextInt(256) }
        } else {
            (0 until 6).map { Random.nextInt(256) }
        }
        return bytes.joinToString(":") { it.toString(16).padStart(2, '0').uppercase() }
    }

    private fun generateAppleMac(): String {
        val prefixes = listOf("AC:BC:32", "BC:52:B7", "DC:A9:04", "F0:18:98")
        return generateRandomMac(prefixes.random())
    }

    private fun generateHexString(length: Int): String {
        return (0 until length).map {
            "0123456789ABCDEF"[Random.nextInt(16)]
        }.joinToString("")
    }

    private fun generateAirTagManufacturerData(): ByteArray {
        // Simplified AirTag manufacturer data structure
        return byteArrayOf(
            0x12, 0x19,  // AirTag identifier
            Random.nextInt(256).toByte(),
            Random.nextInt(256).toByte(),
            Random.nextInt(256).toByte(),
            Random.nextInt(256).toByte()
        )
    }

    private fun varyRssi(baseRssi: Int, variation: Int = 5): Int {
        return baseRssi + Random.nextInt(-variation, variation + 1)
    }
}

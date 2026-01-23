package com.flockyou.testmode

import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.ThreatLevel

/**
 * Defines available test scenarios for simulating surveillance device detections.
 */
sealed class TestScenario(
    val id: String,
    val name: String,
    val description: String,
    val threatLevel: ThreatLevel,
    val protocols: Set<DetectionProtocol>
) {
    /** AirTag/Tile tracker following scenario */
    data object TrackerFollowing : TestScenario(
        id = "tracker_following",
        name = "Tracker Following",
        description = "Simulates AirTag or Tile tracker following you",
        threatLevel = ThreatLevel.HIGH,
        protocols = setOf(DetectionProtocol.BLUETOOTH_LE)
    )

    /** StingRay/IMSI catcher scenario */
    data object CellSiteSimulator : TestScenario(
        id = "cell_site_simulator",
        name = "Cell Site Simulator (StingRay)",
        description = "Simulates IMSI catcher / fake cell tower attack",
        threatLevel = ThreatLevel.CRITICAL,
        protocols = setOf(DetectionProtocol.CELLULAR)
    )

    /** GPS spoofing scenario */
    data object GnssSpoofing : TestScenario(
        id = "gnss_spoofing",
        name = "GPS Spoofing",
        description = "Simulates GPS spoofing attack with uniform satellite signals",
        threatLevel = ThreatLevel.CRITICAL,
        protocols = setOf(DetectionProtocol.GNSS)
    )

    /** Flock Safety / surveillance camera scenario */
    data object SurveillanceCamera : TestScenario(
        id = "surveillance_camera",
        name = "Surveillance Camera Network",
        description = "Simulates detection of Flock Safety ALPR cameras",
        threatLevel = ThreatLevel.HIGH,
        protocols = setOf(DetectionProtocol.WIFI, DetectionProtocol.BLUETOOTH_LE)
    )

    /** Ultrasonic beacon tracking scenario */
    data object UltrasonicBeacon : TestScenario(
        id = "ultrasonic_beacon",
        name = "Ultrasonic Tracking Beacon",
        description = "Simulates cross-device tracking via ultrasonic beacons",
        threatLevel = ThreatLevel.MEDIUM,
        protocols = setOf(DetectionProtocol.AUDIO)
    )

    /** Multiple simultaneous threats scenario */
    data object HighThreatEnvironment : TestScenario(
        id = "high_threat_environment",
        name = "High Threat Environment",
        description = "Multiple surveillance devices active simultaneously",
        threatLevel = ThreatLevel.CRITICAL,
        protocols = setOf(DetectionProtocol.WIFI, DetectionProtocol.BLUETOOTH_LE, DetectionProtocol.CELLULAR, DetectionProtocol.GNSS)
    )

    /** Normal/benign environment for false positive testing */
    data object NormalEnvironment : TestScenario(
        id = "normal_environment",
        name = "Normal Environment",
        description = "Benign consumer devices for false positive testing",
        threatLevel = ThreatLevel.INFO,
        protocols = setOf(DetectionProtocol.WIFI, DetectionProtocol.BLUETOOTH_LE)
    )

    /** Drone surveillance scenario */
    data object DroneSurveillance : TestScenario(
        id = "drone_surveillance",
        name = "Drone Surveillance",
        description = "Simulates detection of nearby surveillance drone",
        threatLevel = ThreatLevel.HIGH,
        protocols = setOf(DetectionProtocol.WIFI, DetectionProtocol.BLUETOOTH_LE)
    )

    companion object {
        fun all(): List<TestScenario> = listOf(
            TrackerFollowing,
            CellSiteSimulator,
            GnssSpoofing,
            SurveillanceCamera,
            UltrasonicBeacon,
            HighThreatEnvironment,
            NormalEnvironment,
            DroneSurveillance
        )

        fun fromId(id: String): TestScenario? = all().find { it.id == id }
    }
}

/**
 * Container for all mock data a test scenario produces.
 */
data class TestScenarioData(
    val wifiNetworks: List<MockWifiNetwork> = emptyList(),
    val bleDevices: List<MockBleDevice> = emptyList(),
    val cellularState: MockCellularState? = null,
    val gnssState: MockGnssState? = null,
    val audioBeacons: List<MockAudioBeacon> = emptyList()
)

/**
 * Mock WiFi network for test scenarios.
 */
data class MockWifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String,
    val isHidden: Boolean = false,
    val deviceType: DeviceType
)

/**
 * Mock BLE device for test scenarios.
 */
data class MockBleDevice(
    val name: String?,
    val macAddress: String,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerId: Int?,
    val manufacturerData: ByteArray?,
    val deviceType: DeviceType
) {
    companion object {
        const val MANUFACTURER_APPLE = 0x004C
        const val MANUFACTURER_TILE = 0x00C7
        const val MANUFACTURER_SAMSUNG = 0x0075
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MockBleDevice

        if (name != other.name) return false
        if (macAddress != other.macAddress) return false
        if (rssi != other.rssi) return false
        if (serviceUuids != other.serviceUuids) return false
        if (manufacturerId != other.manufacturerId) return false
        if (manufacturerData != null) {
            if (other.manufacturerData == null) return false
            if (!manufacturerData.contentEquals(other.manufacturerData)) return false
        } else if (other.manufacturerData != null) return false
        if (deviceType != other.deviceType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + macAddress.hashCode()
        result = 31 * result + rssi
        result = 31 * result + serviceUuids.hashCode()
        result = 31 * result + (manufacturerId ?: 0)
        result = 31 * result + (manufacturerData?.contentHashCode() ?: 0)
        result = 31 * result + deviceType.hashCode()
        return result
    }
}

/**
 * Mock cellular state for test scenarios.
 */
data class MockCellularState(
    val cellId: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val signalStrength: Int,
    val networkType: String,
    val anomalyType: CellularAnomalyType? = null
)

/**
 * Types of cellular anomalies for test mode.
 */
enum class CellularAnomalyType(val description: String) {
    ENCRYPTION_DOWNGRADE("Encryption downgrade detected"),
    SUSPICIOUS_NETWORK("Suspicious network parameters"),
    RAPID_TOWER_SWITCHING("Rapid cell tower switching"),
    SIGNAL_SPIKE("Abnormally strong signal"),
    UNEXPECTED_CELL_CHANGE("Unexpected cell change while stationary"),
    LAC_ANOMALY("Unusual Location Area Code")
}

/**
 * Mock GNSS state for test scenarios.
 */
data class MockGnssState(
    val satellites: List<MockSatellite>,
    val cn0Average: Double,
    val hdop: Double,
    val isJammed: Boolean = false,
    val isSpoofed: Boolean = false
)

/**
 * Mock satellite data for GNSS scenarios.
 */
data class MockSatellite(
    val svid: Int,
    val constellation: String,
    val cn0DbHz: Double,
    val elevation: Float,
    val azimuth: Float
) {
    fun hasGoodSignal(): Boolean = cn0DbHz >= 25.0
}

/**
 * Mock audio beacon for ultrasonic scenarios.
 */
data class MockAudioBeacon(
    val frequencyHz: Int,
    val amplitudeDb: Double,
    val durationMs: Long,
    val beaconType: String
) {
    companion object {
        const val TYPE_AD_TRACKING = "AD_TRACKING"
        const val TYPE_TV_ATTRIBUTION = "TV_ATTRIBUTION"
        const val TYPE_RETAIL_ANALYTICS = "RETAIL_ANALYTICS"
        const val TYPE_CROSS_DEVICE = "CROSS_DEVICE"
    }
}

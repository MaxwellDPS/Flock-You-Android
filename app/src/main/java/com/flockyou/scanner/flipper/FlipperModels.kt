package com.flockyou.scanner.flipper

/**
 * Data models for Flipper Zero communication protocol.
 * These represent the structured data exchanged between the Flipper Zero
 * and the Android app over Bluetooth LE Serial or USB CDC.
 */

// ============================================================================
// WiFi Scan Results
// ============================================================================

data class FlipperWifiScanResult(
    val timestamp: Long,
    val networks: List<FlipperWifiNetwork>
)

data class FlipperWifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val channel: Int,
    val security: WifiSecurityType,
    val hidden: Boolean
)

enum class WifiSecurityType {
    OPEN, WEP, WPA, WPA2, WPA3, UNKNOWN;

    companion object {
        fun fromByte(b: Byte): WifiSecurityType = when (b.toInt() and 0xFF) {
            0 -> OPEN
            1 -> WEP
            2 -> WPA
            3 -> WPA2
            4 -> WPA3
            else -> UNKNOWN
        }
    }
}

// ============================================================================
// Sub-GHz / RF Scan Results
// ============================================================================

data class FlipperSubGhzScanResult(
    val timestamp: Long,
    val frequencyStart: Long,
    val frequencyEnd: Long,
    val detections: List<FlipperSubGhzDetection>
)

data class FlipperSubGhzDetection(
    val frequency: Long,
    val rssi: Int,
    val modulation: SubGhzModulation,
    val durationMs: Int,
    val bandwidth: Long,
    val protocolId: Int,
    val protocolName: String
)

/**
 * Real-time Sub-GHz scanner status/metadata.
 * Sent periodically and on frequency hops to provide visibility into scanner state.
 */
data class FlipperSubGhzScanStatus(
    val timestamp: Long,          // Uptime in milliseconds
    val currentFrequency: Long,   // Current frequency in Hz
    val currentPreset: Int,       // Modulation preset (0=OOK650, 1=OOK270, 2=2FSK238, 3=2FSK476)
    val frequencyIndex: Int,      // Index in frequency hop list (0-9)
    val totalFrequencies: Int,    // Total frequencies in hop list
    val scanActive: Boolean,      // Scanner is actively running
    val decodeInProgress: Boolean,// Actively decoding a signal
    val jammingDetected: Boolean, // Jamming detected at current frequency
    val currentRssi: Int,         // Current RSSI reading (dBm)
    val hopCount: Long,           // Total frequency hops since start
    val detectionCount: Long,     // Total protocol detections since start
    val dwellTimeMs: Long         // Dwell time per frequency (ms)
) {
    val presetName: String
        get() = when (currentPreset) {
            0 -> "OOK 650kHz"
            1 -> "OOK 270kHz"
            2 -> "2-FSK 2.38kHz"
            3 -> "2-FSK 4.76kHz"
            else -> "Unknown"
        }

    val frequencyMhz: Double
        get() = currentFrequency / 1_000_000.0
}

enum class SubGhzModulation {
    AM, FM, ASK, FSK, PSK, OOK, GFSK, UNKNOWN;

    companion object {
        fun fromByte(b: Byte): SubGhzModulation = when (b.toInt() and 0xFF) {
            0 -> AM
            1 -> FM
            2 -> ASK
            3 -> FSK
            4 -> PSK
            5 -> OOK
            6 -> GFSK
            else -> UNKNOWN
        }
    }
}

// ============================================================================
// BLE Scan Results
// ============================================================================

data class FlipperBleScanResult(
    val timestamp: Long,
    val devices: List<FlipperBleDevice>
)

data class FlipperBleDevice(
    val macAddress: String,
    val name: String,
    val rssi: Int,
    val addressType: BleAddressType,
    val isConnectable: Boolean,
    val serviceUuids: List<String>,
    val manufacturerId: Int,
    val manufacturerData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FlipperBleDevice
        return macAddress == other.macAddress
    }

    override fun hashCode(): Int = macAddress.hashCode()
}

enum class BleAddressType {
    PUBLIC, RANDOM;

    companion object {
        fun fromByte(b: Byte): BleAddressType = when (b.toInt() and 0xFF) {
            0 -> PUBLIC
            else -> RANDOM
        }
    }
}

// ============================================================================
// IR Scan Results
// ============================================================================

data class FlipperIrScanResult(
    val timestamp: Long,
    val detections: List<FlipperIrDetection>
)

data class FlipperIrDetection(
    val timestamp: Long,
    val protocolId: Int,
    val protocolName: String,
    val address: Long,
    val command: Long,
    val isRepeat: Boolean,
    val signalStrength: Int
)

object IrProtocols {
    const val UNKNOWN = 0
    const val NEC = 1
    const val SAMSUNG32 = 5
    const val RC5 = 6
    const val RC6 = 8
    const val SIRC = 9

    fun getName(protocolId: Int): String = when (protocolId) {
        NEC -> "NEC"
        SAMSUNG32 -> "Samsung32"
        RC5 -> "RC5"
        RC6 -> "RC6"
        SIRC -> "SIRC"
        else -> "Unknown"
    }
}

// ============================================================================
// NFC Scan Results
// ============================================================================

data class FlipperNfcScanResult(
    val timestamp: Long,
    val detections: List<FlipperNfcDetection>
)

data class FlipperNfcDetection(
    val uid: ByteArray,
    val uidLength: Int,
    val nfcType: NfcType,
    val sak: Int,
    val atqa: ByteArray,
    val typeName: String
) {
    val uidString: String
        get() = uid.take(uidLength).joinToString(":") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FlipperNfcDetection
        return uid.contentEquals(other.uid) && uidLength == other.uidLength
    }

    override fun hashCode(): Int = uid.contentHashCode()
}

enum class NfcType {
    TYPE_A, TYPE_B, TYPE_F, TYPE_V, UNKNOWN;

    companion object {
        fun fromByte(b: Byte): NfcType = when (b.toInt() and 0xFF) {
            0 -> TYPE_A
            1 -> TYPE_B
            2 -> TYPE_F
            3 -> TYPE_V
            else -> UNKNOWN
        }
    }
}

// ============================================================================
// WIPS Alert
// ============================================================================

data class FlipperWipsAlert(
    val timestamp: Long,
    val alertType: WipsAlertType,
    val severity: WipsSeverity,
    val ssid: String,
    val bssids: List<String>,
    val description: String
)

enum class WipsAlertType {
    EVIL_TWIN,
    DEAUTH_ATTACK,
    KARMA_ATTACK,
    HIDDEN_NETWORK_STRONG,
    SUSPICIOUS_OPEN_NETWORK,
    WEAK_ENCRYPTION,
    CHANNEL_INTERFERENCE,
    MAC_SPOOFING,
    ROGUE_AP,
    SIGNAL_ANOMALY,
    BEACON_FLOOD,
    UNKNOWN;

    companion object {
        fun fromByte(b: Byte): WipsAlertType = when (b.toInt() and 0xFF) {
            0 -> EVIL_TWIN
            1 -> DEAUTH_ATTACK
            2 -> KARMA_ATTACK
            3 -> HIDDEN_NETWORK_STRONG
            4 -> SUSPICIOUS_OPEN_NETWORK
            5 -> WEAK_ENCRYPTION
            6 -> CHANNEL_INTERFERENCE
            7 -> MAC_SPOOFING
            8 -> ROGUE_AP
            9 -> SIGNAL_ANOMALY
            10 -> BEACON_FLOOD
            else -> UNKNOWN
        }
    }
}

enum class WipsSeverity {
    CRITICAL, HIGH, MEDIUM, LOW, INFO;

    companion object {
        fun fromByte(b: Byte): WipsSeverity = when (b.toInt() and 0xFF) {
            0 -> CRITICAL
            1 -> HIGH
            2 -> MEDIUM
            3 -> LOW
            4 -> INFO
            else -> INFO
        }
    }
}

// ============================================================================
// Status Response
// ============================================================================

data class FlipperStatusResponse(
    val protocolVersion: Int,
    val wifiBoardConnected: Boolean,
    val subGhzReady: Boolean,
    val bleReady: Boolean,
    val irReady: Boolean,
    val nfcReady: Boolean,
    val batteryPercent: Int,
    val uptimeSeconds: Long,
    val wifiScanCount: Int,
    val subGhzDetectionCount: Int,
    val bleScanCount: Int,
    val irDetectionCount: Int,
    val nfcDetectionCount: Int,
    val wipsAlertCount: Int
)

// ============================================================================
// Connection State
// ============================================================================

enum class FlipperConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    LAUNCHING_FAP,  // FAP not running, sending CLI command to launch it
    READY,
    ERROR
}

// ============================================================================
// Protocol Messages
// ============================================================================

sealed class FlipperMessage {
    data object Heartbeat : FlipperMessage()
    data class WifiScanResult(val result: FlipperWifiScanResult) : FlipperMessage()
    data class SubGhzScanResult(val result: FlipperSubGhzScanResult) : FlipperMessage()
    data class SubGhzScanStatus(val status: FlipperSubGhzScanStatus) : FlipperMessage()
    data class BleScanResult(val result: FlipperBleScanResult) : FlipperMessage()
    data class IrScanResult(val result: FlipperIrScanResult) : FlipperMessage()
    data class NfcScanResult(val result: FlipperNfcScanResult) : FlipperMessage()
    data class WipsAlert(val alert: FlipperWipsAlert) : FlipperMessage()
    data class StatusResponse(val status: FlipperStatusResponse) : FlipperMessage()
    data class Error(val code: Int, val message: String) : FlipperMessage()
}

// ============================================================================
// Sub-GHz Frequency Reference
// ============================================================================

object SubGhzFrequencies {
    const val FREQ_315_MHZ = 315_000_000L
    const val FREQ_433_MHZ = 433_920_000L
    const val FREQ_868_MHZ = 868_350_000L
    const val FREQ_915_MHZ = 915_000_000L
    const val FREQUENCY_TOLERANCE = 5_000_000L

    fun isSubGhzRange(frequency: Long): Boolean =
        frequency in 300_000_000L..928_000_000L

    fun formatFrequency(hz: Long): String = when {
        hz >= 1_000_000_000 -> String.format("%.3f GHz", hz / 1_000_000_000.0)
        hz >= 1_000_000 -> String.format("%.3f MHz", hz / 1_000_000.0)
        else -> "$hz Hz"
    }
}

object SubGhzProtocols {
    const val UNKNOWN = 0
    const val PRINCETON = 1
    const val KEELOQ = 2
    const val NICE_FLO = 3
    const val CAME = 4

    fun getName(protocolId: Int): String = when (protocolId) {
        PRINCETON -> "Princeton"
        KEELOQ -> "KeeLoq"
        NICE_FLO -> "NICE Flo"
        CAME -> "CAME"
        else -> "Unknown"
    }
}

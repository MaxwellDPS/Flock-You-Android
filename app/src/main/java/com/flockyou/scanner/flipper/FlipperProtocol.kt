package com.flockyou.scanner.flipper

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary protocol for communication with Flipper Zero over BLE Serial or USB CDC.
 *
 * Message Format:
 * [version:1][type:1][payload_length:2][payload:N]
 *
 * All multi-byte values are little-endian.
 */
object FlipperProtocol {
    const val VERSION = 1

    // Message types
    const val MSG_HEARTBEAT = 0x00
    const val MSG_WIFI_SCAN_REQUEST = 0x01
    const val MSG_WIFI_SCAN_RESULT = 0x02
    const val MSG_SUBGHZ_SCAN_REQUEST = 0x03
    const val MSG_SUBGHZ_SCAN_RESULT = 0x04
    const val MSG_STATUS_REQUEST = 0x05
    const val MSG_STATUS_RESPONSE = 0x06
    const val MSG_WIPS_ALERT = 0x07
    const val MSG_BLE_SCAN_REQUEST = 0x08
    const val MSG_BLE_SCAN_RESULT = 0x09
    const val MSG_IR_SCAN_REQUEST = 0x0A
    const val MSG_IR_SCAN_RESULT = 0x0B
    const val MSG_NFC_SCAN_REQUEST = 0x0C
    const val MSG_NFC_SCAN_RESULT = 0x0D
    const val MSG_ERROR = 0xFF

    private const val HEADER_SIZE = 4
    private const val MAX_WIFI_NETWORKS = 32
    private const val MAX_SUBGHZ_DETECTIONS = 16
    private const val MAX_BLE_DEVICES = 32
    private const val MAX_IR_DETECTIONS = 16
    private const val MAX_NFC_DETECTIONS = 8

    // ========================================================================
    // Request Creation
    // ========================================================================

    fun createHeartbeat(): ByteArray = createHeader(MSG_HEARTBEAT, 0)

    fun createWifiScanRequest(): ByteArray = createHeader(MSG_WIFI_SCAN_REQUEST, 0)

    /**
     * Create a Sub-GHz scan request with frequency range.
     * Frequencies are stored as unsigned 32-bit integers in the protocol.
     * Valid range: 0 to 4,294,967,295 Hz (0 to ~4.3 GHz)
     *
     * @throws IllegalArgumentException if frequency is out of valid range
     */
    fun createSubGhzScanRequest(
        frequencyStart: Long = 300_000_000L,
        frequencyEnd: Long = 928_000_000L
    ): ByteArray {
        // Validate frequency range fits in unsigned 32-bit integer
        val maxFrequency = 0xFFFFFFFFL // 4,294,967,295 Hz
        require(frequencyStart in 0..maxFrequency) {
            "frequencyStart must be between 0 and $maxFrequency Hz, got $frequencyStart"
        }
        require(frequencyEnd in 0..maxFrequency) {
            "frequencyEnd must be between 0 and $maxFrequency Hz, got $frequencyEnd"
        }
        require(frequencyStart <= frequencyEnd) {
            "frequencyStart ($frequencyStart) must be <= frequencyEnd ($frequencyEnd)"
        }

        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        // Store as unsigned 32-bit by masking with 0xFFFFFFFF
        payload.putInt((frequencyStart and 0xFFFFFFFFL).toInt())
        payload.putInt((frequencyEnd and 0xFFFFFFFFL).toInt())
        return createHeader(MSG_SUBGHZ_SCAN_REQUEST, 8) + payload.array()
    }

    fun createStatusRequest(): ByteArray = createHeader(MSG_STATUS_REQUEST, 0)

    fun createBleScanRequest(): ByteArray = createHeader(MSG_BLE_SCAN_REQUEST, 0)

    fun createIrScanRequest(): ByteArray = createHeader(MSG_IR_SCAN_REQUEST, 0)

    fun createNfcScanRequest(): ByteArray = createHeader(MSG_NFC_SCAN_REQUEST, 0)

    // ========================================================================
    // Response Parsing
    // ========================================================================

    fun parseMessage(data: ByteArray): FlipperMessage? {
        if (data.size < HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.get().toInt() and 0xFF
        val type = buffer.get().toInt() and 0xFF
        val payloadLength = buffer.short.toInt() and 0xFFFF

        if (version != VERSION) {
            return FlipperMessage.Error(-1, "Protocol version mismatch: expected $VERSION, got $version")
        }

        if (data.size < HEADER_SIZE + payloadLength) {
            return null // Incomplete message
        }

        return when (type) {
            MSG_HEARTBEAT -> FlipperMessage.Heartbeat
            MSG_WIFI_SCAN_RESULT -> parseWifiScanResult(buffer, payloadLength)
            MSG_SUBGHZ_SCAN_RESULT -> parseSubGhzScanResult(buffer, payloadLength)
            MSG_STATUS_RESPONSE -> parseStatusResponse(buffer)
            MSG_WIPS_ALERT -> parseWipsAlert(buffer)
            MSG_BLE_SCAN_RESULT -> parseBleScanResult(buffer, payloadLength)
            MSG_IR_SCAN_RESULT -> parseIrScanResult(buffer, payloadLength)
            MSG_NFC_SCAN_RESULT -> parseNfcScanResult(buffer, payloadLength)
            MSG_ERROR -> parseError(buffer)
            else -> FlipperMessage.Error(-2, "Unknown message type: $type")
        }
    }

    private fun parseWifiScanResult(buffer: ByteBuffer, payloadLength: Int): FlipperMessage {
        if (payloadLength < 5) return FlipperMessage.Error(-3, "WiFi scan result too short")

        val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
        val networkCount = (buffer.get().toInt() and 0xFF).coerceAtMost(MAX_WIFI_NETWORKS)

        val networks = mutableListOf<FlipperWifiNetwork>()
        repeat(networkCount) {
            try {
                val ssidBytes = ByteArray(33)
                buffer.get(ssidBytes)
                val ssid = ssidBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

                val bssid = ByteArray(6)
                buffer.get(bssid)
                val bssidStr = bssid.joinToString(":") { "%02X".format(it) }

                val rssi = buffer.get().toInt()
                val channel = buffer.get().toInt() and 0xFF
                val security = WifiSecurityType.fromByte(buffer.get())
                val hidden = buffer.get() != 0.toByte()

                networks.add(FlipperWifiNetwork(ssid, bssidStr, rssi, channel, security, hidden))
            } catch (e: Exception) {
                android.util.Log.w("FlipperProtocol", "Error parsing WiFi network $it: ${e.message}")
                return@repeat
            }
        }

        return FlipperMessage.WifiScanResult(FlipperWifiScanResult(timestamp * 1000, networks))
    }

    private fun parseSubGhzScanResult(buffer: ByteBuffer, payloadLength: Int): FlipperMessage {
        if (payloadLength < 13) return FlipperMessage.Error(-4, "Sub-GHz scan result too short")

        val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
        val frequencyStart = buffer.int.toLong() and 0xFFFFFFFFL
        val frequencyEnd = buffer.int.toLong() and 0xFFFFFFFFL
        val detectionCount = (buffer.get().toInt() and 0xFF).coerceAtMost(MAX_SUBGHZ_DETECTIONS)

        val detections = mutableListOf<FlipperSubGhzDetection>()
        repeat(detectionCount) {
            try {
                val frequency = buffer.int.toLong() and 0xFFFFFFFFL
                val rssi = buffer.get().toInt()
                val modulation = SubGhzModulation.fromByte(buffer.get())
                val durationMs = buffer.short.toInt() and 0xFFFF
                val bandwidth = buffer.int.toLong() and 0xFFFFFFFFL
                val protocolId = buffer.get().toInt() and 0xFF

                val protocolNameBytes = ByteArray(16)
                buffer.get(protocolNameBytes)
                val protocolName = protocolNameBytes.takeWhile { it != 0.toByte() }
                    .toByteArray().toString(Charsets.UTF_8)
                    .ifEmpty { SubGhzProtocols.getName(protocolId) }

                detections.add(FlipperSubGhzDetection(frequency, rssi, modulation, durationMs, bandwidth, protocolId, protocolName))
            } catch (e: Exception) {
                android.util.Log.w("FlipperProtocol", "Error parsing Sub-GHz detection $it: ${e.message}")
                return@repeat
            }
        }

        return FlipperMessage.SubGhzScanResult(FlipperSubGhzScanResult(timestamp * 1000, frequencyStart, frequencyEnd, detections))
    }

    private fun parseStatusResponse(buffer: ByteBuffer): FlipperMessage {
        return try {
            FlipperMessage.StatusResponse(
                FlipperStatusResponse(
                    protocolVersion = buffer.get().toInt() and 0xFF,
                    wifiBoardConnected = buffer.get() != 0.toByte(),
                    subGhzReady = buffer.get() != 0.toByte(),
                    bleReady = buffer.get() != 0.toByte(),
                    irReady = buffer.get() != 0.toByte(),
                    nfcReady = buffer.get() != 0.toByte(),
                    batteryPercent = buffer.get().toInt() and 0xFF,
                    uptimeSeconds = buffer.int.toLong() and 0xFFFFFFFFL,
                    wifiScanCount = buffer.short.toInt() and 0xFFFF,
                    subGhzDetectionCount = buffer.short.toInt() and 0xFFFF,
                    bleScanCount = buffer.short.toInt() and 0xFFFF,
                    irDetectionCount = buffer.short.toInt() and 0xFFFF,
                    nfcDetectionCount = buffer.short.toInt() and 0xFFFF,
                    wipsAlertCount = buffer.short.toInt() and 0xFFFF
                )
            )
        } catch (e: Exception) {
            FlipperMessage.Error(-5, "Failed to parse status response: ${e.message}")
        }
    }

    private fun parseWipsAlert(buffer: ByteBuffer): FlipperMessage {
        return try {
            val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
            val alertType = WipsAlertType.fromByte(buffer.get())
            val severity = WipsSeverity.fromByte(buffer.get())

            val ssidBytes = ByteArray(33)
            buffer.get(ssidBytes)
            val ssid = ssidBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

            val bssidCount = (buffer.get().toInt() and 0xFF).coerceAtMost(4)
            val bssids = mutableListOf<String>()
            repeat(4) { i ->
                val bssid = ByteArray(6)
                buffer.get(bssid)
                if (i < bssidCount) {
                    bssids.add(bssid.joinToString(":") { "%02X".format(it) })
                }
            }

            val descBytes = ByteArray(64)
            buffer.get(descBytes)
            val description = descBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

            FlipperMessage.WipsAlert(FlipperWipsAlert(timestamp * 1000, alertType, severity, ssid, bssids, description))
        } catch (e: Exception) {
            FlipperMessage.Error(-7, "Failed to parse WIPS alert: ${e.message}")
        }
    }

    private fun parseBleScanResult(buffer: ByteBuffer, payloadLength: Int): FlipperMessage {
        if (payloadLength < 5) return FlipperMessage.Error(-8, "BLE scan result too short")

        val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
        val deviceCount = (buffer.get().toInt() and 0xFF).coerceAtMost(MAX_BLE_DEVICES)

        val devices = mutableListOf<FlipperBleDevice>()
        repeat(deviceCount) {
            try {
                val mac = ByteArray(6)
                buffer.get(mac)
                val macStr = mac.joinToString(":") { "%02X".format(it) }

                val nameBytes = ByteArray(32)
                buffer.get(nameBytes)
                val name = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

                val rssi = buffer.get().toInt()
                val addressType = BleAddressType.fromByte(buffer.get())
                val isConnectable = buffer.get() != 0.toByte()

                val uuidCount = (buffer.get().toInt() and 0xFF).coerceAtMost(4)
                val serviceUuids = mutableListOf<String>()
                repeat(4) { i ->
                    val uuid = ByteArray(16)
                    buffer.get(uuid)
                    if (i < uuidCount) {
                        serviceUuids.add(formatUuid(uuid))
                    }
                }

                val manufacturerId = buffer.short.toInt() and 0xFFFF
                val mfrDataLen = (buffer.get().toInt() and 0xFF).coerceAtMost(32)
                val mfrDataBytes = ByteArray(32)
                buffer.get(mfrDataBytes)
                val manufacturerData = mfrDataBytes.take(mfrDataLen).toByteArray()

                devices.add(FlipperBleDevice(macStr, name, rssi, addressType, isConnectable, serviceUuids, manufacturerId, manufacturerData))
            } catch (e: Exception) {
                android.util.Log.w("FlipperProtocol", "Error parsing BLE device $it: ${e.message}")
                return@repeat
            }
        }

        return FlipperMessage.BleScanResult(FlipperBleScanResult(timestamp * 1000, devices))
    }

    private fun parseIrScanResult(buffer: ByteBuffer, payloadLength: Int): FlipperMessage {
        if (payloadLength < 5) return FlipperMessage.Error(-9, "IR scan result too short")

        val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
        val detectionCount = (buffer.get().toInt() and 0xFF).coerceAtMost(MAX_IR_DETECTIONS)

        val detections = mutableListOf<FlipperIrDetection>()
        repeat(detectionCount) {
            try {
                val detectionTimestamp = buffer.int.toLong() and 0xFFFFFFFFL
                val protocolId = buffer.get().toInt() and 0xFF

                val protocolNameBytes = ByteArray(16)
                buffer.get(protocolNameBytes)
                val protocolName = protocolNameBytes.takeWhile { it != 0.toByte() }
                    .toByteArray().toString(Charsets.UTF_8)
                    .ifEmpty { IrProtocols.getName(protocolId) }

                val address = buffer.int.toLong() and 0xFFFFFFFFL
                val command = buffer.int.toLong() and 0xFFFFFFFFL
                val isRepeat = buffer.get() != 0.toByte()
                val signalStrength = buffer.get().toInt()

                detections.add(FlipperIrDetection(detectionTimestamp * 1000, protocolId, protocolName, address, command, isRepeat, signalStrength))
            } catch (e: Exception) {
                android.util.Log.w("FlipperProtocol", "Error parsing IR detection $it: ${e.message}")
                return@repeat
            }
        }

        return FlipperMessage.IrScanResult(FlipperIrScanResult(timestamp * 1000, detections))
    }

    private fun parseNfcScanResult(buffer: ByteBuffer, payloadLength: Int): FlipperMessage {
        if (payloadLength < 5) return FlipperMessage.Error(-10, "NFC scan result too short")

        val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
        val detectionCount = (buffer.get().toInt() and 0xFF).coerceAtMost(MAX_NFC_DETECTIONS)

        val detections = mutableListOf<FlipperNfcDetection>()
        repeat(detectionCount) {
            try {
                val uid = ByteArray(10)
                buffer.get(uid)
                val uidLen = buffer.get().toInt() and 0xFF
                val nfcType = NfcType.fromByte(buffer.get())
                val sak = buffer.get().toInt() and 0xFF
                val atqa = ByteArray(2)
                buffer.get(atqa)

                val typeNameBytes = ByteArray(16)
                buffer.get(typeNameBytes)
                val typeName = typeNameBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

                detections.add(FlipperNfcDetection(uid, uidLen, nfcType, sak, atqa, typeName))
            } catch (e: Exception) {
                android.util.Log.w("FlipperProtocol", "Error parsing NFC detection $it: ${e.message}")
                return@repeat
            }
        }

        return FlipperMessage.NfcScanResult(FlipperNfcScanResult(timestamp * 1000, detections))
    }

    private fun parseError(buffer: ByteBuffer): FlipperMessage {
        return try {
            val code = buffer.get().toInt()
            val remaining = ByteArray(buffer.remaining().coerceAtMost(64))
            buffer.get(remaining)
            val message = remaining.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            FlipperMessage.Error(code, message)
        } catch (e: Exception) {
            android.util.Log.w("FlipperProtocol", "Error parsing error message: ${e.message}")
            FlipperMessage.Error(-6, "Failed to parse error message")
        }
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun formatUuid(bytes: ByteArray): String {
        if (bytes.size != 16) return bytes.joinToString("") { "%02X".format(it) }
        return buildString {
            for (i in bytes.indices) {
                append("%02x".format(bytes[i]))
                if (i == 3 || i == 5 || i == 7 || i == 9) append("-")
            }
        }
    }

    private fun createHeader(type: Int, payloadLength: Int): ByteArray {
        return ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(VERSION.toByte())
            .put(type.toByte())
            .putShort(payloadLength.toShort())
            .array()
    }

    fun getExpectedMessageSize(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) return -1
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(2)
        val payloadLength = buffer.short.toInt() and 0xFFFF
        return HEADER_SIZE + payloadLength
    }

    fun hasCompleteMessage(data: ByteArray): Boolean {
        if (data.size < HEADER_SIZE) return false
        return data.size >= getExpectedMessageSize(data)
    }
}

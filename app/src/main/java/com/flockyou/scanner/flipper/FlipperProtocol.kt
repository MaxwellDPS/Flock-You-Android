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

    // Active Probe TX Commands - Public Safety & Fleet
    const val MSG_LF_PROBE_TX = 0x0E          // Tire Kicker - 125kHz TPMS wake
    const val MSG_IR_STROBE_TX = 0x0F         // Opticom Verifier - Traffic preemption
    const val MSG_WIFI_PROBE_TX = 0x10        // Honey-Potter - Fleet SSID probing
    const val MSG_BLE_ACTIVE_SCAN = 0x11      // BlueForce Handshake - Force SCAN_RSP

    // Active Probe TX Commands - Infrastructure
    const val MSG_ZIGBEE_BEACON_TX = 0x12     // Zigbee Knocker - Mesh mapping
    const val MSG_GPIO_PULSE_TX = 0x13        // Ghost Car - Inductive loop spoof

    // Active Probe TX Commands - Physical Access
    const val MSG_SUBGHZ_REPLAY_TX = 0x14     // Sleep Denial - Alarm fatigue
    const val MSG_WIEGAND_REPLAY_TX = 0x15    // Replay Injector - Card bypass
    const val MSG_MAGSPOOF_TX = 0x16          // MagSpoof - Magstripe emulation
    const val MSG_IBUTTON_EMULATE = 0x17      // Master Key - 1-Wire emulation

    // Active Probe TX Commands - Digital
    const val MSG_NRF24_INJECT_TX = 0x18      // MouseJacker - Keystroke injection

    // Passive Scan Configuration
    const val MSG_SUBGHZ_CONFIG = 0x20        // Configure Sub-GHz listener params
    const val MSG_IR_CONFIG = 0x21            // Configure IR listener
    const val MSG_NRF24_CONFIG = 0x22         // Configure NRF24 scanner

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
    // Active Probe Request Creation
    // ========================================================================

    // --- Public Safety & Fleet ---

    /**
     * Tire Kicker: 125kHz LF burst to wake TPMS sensors on parked vehicles.
     * @param durationMs Duration to hold carrier (100-5000ms, firmware caps at 5s for battery)
     */
    fun createLfProbeRequest(durationMs: Int = 2500): ByteArray {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(durationMs.coerceIn(100, 5000).toShort())
        return createHeader(MSG_LF_PROBE_TX, 2) + payload.array()
    }

    /**
     * Opticom Verifier: Emit IR strobe to test traffic preemption infrastructure.
     * @param freqHz 14 for High Priority (Emergency), 10 for Low Priority (Transit)
     * @param durationMs Strobe duration (100-10000ms)
     * @param dutyCycle PWM duty cycle 0-100
     */
    fun createIrStrobeRequest(freqHz: Int, durationMs: Int = 5000, dutyCycle: Int = 50): ByteArray {
        val payload = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(freqHz.coerceIn(1, 100).toShort())
        payload.put(dutyCycle.coerceIn(0, 100).toByte())
        payload.putShort(durationMs.coerceIn(100, 10000).toShort())
        return createHeader(MSG_IR_STROBE_TX, 5) + payload.array()
    }

    /**
     * Honey-Potter: Send directed Wi-Fi probe requests for fleet SSIDs.
     * Forces hidden MDT networks to decloak.
     * @param targetSsid Target SSID (max 32 chars)
     */
    fun createWifiProbeRequest(targetSsid: String): ByteArray {
        val ssidBytes = targetSsid.take(32).toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(1 + ssidBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(ssidBytes.size.toByte())
        payload.put(ssidBytes)
        return createHeader(MSG_WIFI_PROBE_TX, payload.position()) + payload.array()
    }

    /**
     * BlueForce Handshake: Enable active BLE scanning to force SCAN_RSP.
     * Reveals device model/unit ID from body cams and holsters.
     * @param activeMode true to enable active scanning
     */
    fun createBleActiveScanRequest(activeMode: Boolean = true): ByteArray {
        val payload = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(if (activeMode) 1.toByte() else 0.toByte())
        return createHeader(MSG_BLE_ACTIVE_SCAN, 1) + payload.array()
    }

    // --- Infrastructure & Utilities ---

    /**
     * Zigbee Knocker: Send Zigbee beacon request to map smart meter mesh.
     * @param channel Zigbee channel (11-26), 0 for channel hop
     */
    fun createZigbeeBeaconRequest(channel: Int = 0): ByteArray {
        val payload = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(channel.coerceIn(0, 26).toByte())
        return createHeader(MSG_ZIGBEE_BEACON_TX, 1) + payload.array()
    }

    /**
     * Ghost Car: Pulse GPIO coil at resonant frequency to spoof inductive loop.
     * @param frequencyHz Resonant frequency of target loop (typically 20000-150000 Hz)
     * @param durationMs Pulse duration
     * @param pulseCount Number of pulses to simulate vehicle presence
     */
    fun createGpioLoopPulseRequest(frequencyHz: Int, durationMs: Int = 500, pulseCount: Int = 3): ByteArray {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(frequencyHz.coerceIn(1000, 200000))
        payload.putShort(durationMs.coerceIn(50, 5000).toShort())
        payload.putShort(pulseCount.coerceIn(1, 20).toShort())
        return createHeader(MSG_GPIO_PULSE_TX, 8) + payload.array()
    }

    // --- Physical Access ---

    /**
     * Sleep Denial: Replay Sub-GHz signal to cause alarm fatigue.
     * @param frequency Target frequency in Hz
     * @param rawData Captured signal data to replay
     * @param repeatCount Number of times to replay
     */
    fun createSubGhzReplayRequest(frequency: Long, rawData: ByteArray, repeatCount: Int = 5): ByteArray {
        val dataLen = rawData.size.coerceAtMost(256)
        val payload = ByteBuffer.allocate(7 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt((frequency and 0xFFFFFFFFL).toInt())
        payload.putShort(dataLen.toShort())
        payload.put(repeatCount.coerceIn(1, 100).toByte())
        payload.put(rawData, 0, dataLen)
        return createHeader(MSG_SUBGHZ_REPLAY_TX, payload.position()) + payload.array()
    }

    /**
     * Replay Injector: Replay captured Wiegand signal to bypass card reader.
     * @param facilityCode Facility code from captured card
     * @param cardNumber Card number from captured card
     * @param bitLength Wiegand format (26, 34, 37 bits)
     */
    fun createWiegandReplayRequest(facilityCode: Int, cardNumber: Int, bitLength: Int = 26): ByteArray {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(facilityCode)
        payload.putInt(cardNumber)
        payload.put(bitLength.coerceIn(26, 48).toByte())
        return createHeader(MSG_WIEGAND_REPLAY_TX, 9) + payload.array()
    }

    /**
     * MagSpoof: Emulate magstripe via electromagnetic pulses.
     * @param track1 Track 1 data (max 79 chars, alphanumeric)
     * @param track2 Track 2 data (max 40 chars, numeric)
     */
    fun createMagSpoofRequest(track1: String?, track2: String?): ByteArray {
        val t1Bytes = (track1?.take(79) ?: "").toByteArray(Charsets.US_ASCII)
        val t2Bytes = (track2?.take(40) ?: "").toByteArray(Charsets.US_ASCII)
        val payload = ByteBuffer.allocate(2 + t1Bytes.size + t2Bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(t1Bytes.size.toByte())
        payload.put(t1Bytes)
        payload.put(t2Bytes.size.toByte())
        payload.put(t2Bytes)
        return createHeader(MSG_MAGSPOOF_TX, payload.position()) + payload.array()
    }

    /**
     * Master Key: Emulate iButton/Dallas 1-Wire key.
     * @param keyId 8-byte key ID (DS1990A format)
     */
    fun createIButtonEmulateRequest(keyId: ByteArray): ByteArray {
        require(keyId.size == 8) { "iButton key ID must be 8 bytes" }
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(keyId)
        return createHeader(MSG_IBUTTON_EMULATE, 8) + payload.array()
    }

    // --- Digital Peripherals ---

    /**
     * MouseJacker: Inject keystrokes into vulnerable wireless HID dongle.
     * @param address 5-byte NRF24 address of target dongle
     * @param keystrokes HID keycodes to inject
     */
    fun createNrf24InjectRequest(address: ByteArray, keystrokes: ByteArray): ByteArray {
        require(address.size == 5) { "NRF24 address must be 5 bytes" }
        val ksLen = keystrokes.size.coerceAtMost(64)
        val payload = ByteBuffer.allocate(6 + ksLen).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(address)
        payload.put(ksLen.toByte())
        payload.put(keystrokes, 0, ksLen)
        return createHeader(MSG_NRF24_INJECT_TX, payload.position()) + payload.array()
    }

    // ========================================================================
    // Passive Scan Configuration
    // ========================================================================

    /**
     * Configure Sub-GHz scanner for specific probe type.
     * @param probeType 0=TPMS, 1=P25, 2=LoJack, 3=Pager, 4=PowerGrid, 5=Crane, 6=ESL, 7=Thermal
     * @param frequencyHz Target frequency (0 for default)
     * @param modulation 0=ASK, 1=FSK, 2=GFSK
     */
    fun createSubGhzConfigRequest(probeType: Int, frequencyHz: Long = 0, modulation: Int = 0): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(probeType.coerceIn(0, 15).toByte())
        payload.putInt((frequencyHz and 0xFFFFFFFFL).toInt())
        payload.put(modulation.coerceIn(0, 10).toByte())
        return createHeader(MSG_SUBGHZ_CONFIG, 6) + payload.array()
    }

    /**
     * Configure IR scanner for specific detection mode.
     * @param detectOpticom true to detect 14/10Hz emergency strobes
     */
    fun createIrConfigRequest(detectOpticom: Boolean = true): ByteArray {
        val payload = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(if (detectOpticom) 1.toByte() else 0.toByte())
        return createHeader(MSG_IR_CONFIG, 1) + payload.array()
    }

    /**
     * Configure NRF24 scanner for promiscuous mode.
     * @param promiscuous true to scan all channels for vulnerable devices
     */
    fun createNrf24ConfigRequest(promiscuous: Boolean = true): ByteArray {
        val payload = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(if (promiscuous) 1.toByte() else 0.toByte())
        return createHeader(MSG_NRF24_CONFIG, 1) + payload.array()
    }

    // ========================================================================
    // Storage Commands (for FAP installation)
    // ========================================================================

    // Storage command types
    private const val MSG_STORAGE_WRITE_START = 0x20
    private const val MSG_STORAGE_WRITE_DATA = 0x21
    private const val MSG_STORAGE_WRITE_END = 0x22

    /**
     * Build command to start a file write operation
     */
    fun buildStorageWriteStartCommand(path: String, totalSize: Long): ByteArray {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val payloadSize = 4 + pathBytes.size + 1 // 4 bytes for size, path bytes, null terminator

        val payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(totalSize.toInt())
        payload.put(pathBytes)
        payload.put(0.toByte()) // null terminator

        return createHeader(MSG_STORAGE_WRITE_START, payloadSize) + payload.array()
    }

    /**
     * Build command to write a chunk of data
     */
    fun buildStorageWriteDataCommand(data: ByteArray): ByteArray {
        return createHeader(MSG_STORAGE_WRITE_DATA, data.size) + data
    }

    /**
     * Build command to end a file write operation
     */
    fun buildStorageWriteEndCommand(): ByteArray {
        return createHeader(MSG_STORAGE_WRITE_END, 0)
    }

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

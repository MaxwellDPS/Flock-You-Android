package com.flockyou.scanner.flipper

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Unified Flipper Zero client that supports both Bluetooth LE and USB connections.
 */
class FlipperClient(private val context: Context) {

    companion object {
        private const val TAG = "FlipperClient"
    }

    enum class ConnectionType {
        NONE, BLUETOOTH, USB
    }

    private var bleClient: FlipperBluetoothClient? = null
    private var usbClient: FlipperUsbClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(FlipperConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FlipperConnectionState> = _connectionState.asStateFlow()

    private val _connectionType = MutableStateFlow(ConnectionType.NONE)
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    private val _messages = MutableSharedFlow<FlipperMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<FlipperMessage> = _messages.asSharedFlow()

    private val _wipsEvents = MutableSharedFlow<FlipperWipsEvent>(extraBufferCapacity = 32)
    val wipsEvents: SharedFlow<FlipperWipsEvent> = _wipsEvents.asSharedFlow()

    private val _status = MutableStateFlow<FlipperStatusResponse?>(null)
    val status: StateFlow<FlipperStatusResponse?> = _status.asStateFlow()

    init {
        initializeClients()
    }

    private fun initializeClients() {
        try {
            bleClient = FlipperBluetoothClient(context).also { client ->
                scope.launch {
                    client.connectionState.collect { state ->
                        if (_connectionType.value == ConnectionType.BLUETOOTH) {
                            _connectionState.value = state
                        }
                    }
                }
                scope.launch {
                    client.messages.collect { message ->
                        if (_connectionType.value == ConnectionType.BLUETOOTH) {
                            handleMessage(message)
                        }
                    }
                }
                // Note: WIPS events are handled in handleMessage() via WipsAlert messages.
                // Do NOT also collect from client.wipsEvents to avoid duplicate emissions.
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth client", e)
        }

        try {
            usbClient = FlipperUsbClient(context).also { client ->
                scope.launch {
                    client.connectionState.collect { state ->
                        if (_connectionType.value == ConnectionType.USB) {
                            _connectionState.value = state
                        }
                        if (state == FlipperConnectionState.READY && _connectionType.value != ConnectionType.USB) {
                            Log.i(TAG, "USB connected, switching to USB mode")
                            _connectionType.value = ConnectionType.USB
                            _connectionState.value = state
                        }
                    }
                }
                scope.launch {
                    client.messages.collect { message ->
                        if (_connectionType.value == ConnectionType.USB) {
                            handleMessage(message)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing USB client", e)
        }
    }

    private suspend fun handleMessage(message: FlipperMessage) {
        _messages.emit(message)

        when (message) {
            is FlipperMessage.StatusResponse -> _status.value = message.status
            is FlipperMessage.WipsAlert -> {
                val event = FlipperWipsEvent(
                    type = when (message.alert.alertType) {
                        WipsAlertType.EVIL_TWIN -> FlipperWipsEventType.EVIL_TWIN_DETECTED
                        WipsAlertType.DEAUTH_ATTACK -> FlipperWipsEventType.DEAUTH_DETECTED
                        WipsAlertType.KARMA_ATTACK -> FlipperWipsEventType.KARMA_DETECTED
                        WipsAlertType.HIDDEN_NETWORK_STRONG -> FlipperWipsEventType.HIDDEN_NETWORK_STRONG
                        WipsAlertType.SUSPICIOUS_OPEN_NETWORK -> FlipperWipsEventType.SUSPICIOUS_OPEN_NETWORK
                        WipsAlertType.WEAK_ENCRYPTION -> FlipperWipsEventType.WEAK_ENCRYPTION
                        WipsAlertType.MAC_SPOOFING -> FlipperWipsEventType.MAC_SPOOFING
                        WipsAlertType.ROGUE_AP -> FlipperWipsEventType.ROGUE_AP
                        else -> FlipperWipsEventType.DEAUTH_DETECTED
                    },
                    ssid = message.alert.ssid,
                    bssid = message.alert.bssids.firstOrNull() ?: "",
                    description = message.alert.description,
                    severity = message.alert.severity,
                    timestamp = message.alert.timestamp
                )
                _wipsEvents.emit(event)
            }
            else -> {}
        }
    }

    fun connectBluetooth(deviceAddress: String) {
        Log.i(TAG, "Connecting via Bluetooth to: $deviceAddress")
        _connectionType.value = ConnectionType.BLUETOOTH
        scope.launch { bleClient?.connect(deviceAddress) }
    }

    fun connectUsb(device: UsbDevice) {
        Log.i(TAG, "Connecting via USB to: ${device.deviceName}")
        _connectionType.value = ConnectionType.USB
        usbClient?.connect(device)
    }

    fun connectUsb(): Boolean {
        val devices = usbClient?.findFlipperDevices() ?: emptyList()
        val device = devices.firstOrNull()
        if (device != null) {
            connectUsb(device)
            return true
        }
        return false
    }

    fun findUsbDevices(): List<UsbDevice> = usbClient?.findFlipperDevices() ?: emptyList()

    fun disconnect() {
        when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.disconnect()
            ConnectionType.USB -> usbClient?.disconnect()
            ConnectionType.NONE -> {}
        }
        _connectionType.value = ConnectionType.NONE
        _connectionState.value = FlipperConnectionState.DISCONNECTED
    }

    fun isConnected(): Boolean = _connectionState.value == FlipperConnectionState.READY

    fun requestWifiScan(): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> { scope.launch { bleClient?.requestWifiScan() }; true }
        ConnectionType.USB -> usbClient?.requestWifiScan() ?: false
        ConnectionType.NONE -> false
    }

    fun requestSubGhzScan(freqStart: Long = 300_000_000L, freqEnd: Long = 928_000_000L): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> { scope.launch { bleClient?.requestSubGhzScan(freqStart, freqEnd) }; true }
        ConnectionType.USB -> usbClient?.requestSubGhzScan(freqStart, freqEnd) ?: false
        ConnectionType.NONE -> false
    }

    fun requestBleScan(): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.send(FlipperProtocol.createBleScanRequest()) ?: false
        ConnectionType.USB -> usbClient?.requestBleScan() ?: false
        ConnectionType.NONE -> false
    }

    fun requestIrScan(): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.send(FlipperProtocol.createIrScanRequest()) ?: false
        ConnectionType.USB -> usbClient?.requestIrScan() ?: false
        ConnectionType.NONE -> false
    }

    fun requestNfcScan(): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.send(FlipperProtocol.createNfcScanRequest()) ?: false
        ConnectionType.USB -> usbClient?.requestNfcScan() ?: false
        ConnectionType.NONE -> false
    }

    fun requestStatus(): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> { scope.launch { bleClient?.requestStatus() }; true }
        ConnectionType.USB -> usbClient?.requestStatus() ?: false
        ConnectionType.NONE -> false
    }

    fun sendHeartbeat(): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.sendHeartbeat() ?: false
        ConnectionType.USB -> usbClient?.sendHeartbeat() ?: false
        ConnectionType.NONE -> false
    }

    /**
     * Send a raw command to the Flipper (used for storage operations)
     */
    fun sendRawCommand(data: ByteArray): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.send(data) ?: false
        ConnectionType.USB -> usbClient?.send(data) ?: false
        ConnectionType.NONE -> false
    }

    fun destroy() {
        disconnect()
        bleClient?.disconnect()
        usbClient?.destroy()
        scope.cancel()
    }

    // ========================================================================
    // Active Probes - Public Safety & Fleet
    // ========================================================================

    /**
     * Tire Kicker: Send 125kHz LF burst to wake TPMS sensors on parked vehicles.
     * @param durationMs Duration in milliseconds (default 2500, max 5000)
     */
    fun sendTpmsWakeupSignal(durationMs: Int = 2500): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.send(FlipperProtocol.createLfProbeRequest(durationMs)) ?: false
        ConnectionType.USB -> usbClient?.send(FlipperProtocol.createLfProbeRequest(durationMs)) ?: false
        ConnectionType.NONE -> false
    }

    /**
     * Opticom Verifier: Emit IR strobe for traffic infrastructure testing.
     * @param emergencyPriority true for 14Hz (High Priority), false for 10Hz (Low Priority)
     * @param durationMs Strobe duration in milliseconds
     */
    fun sendTrafficInfraTest(emergencyPriority: Boolean, durationMs: Int = 5000): Boolean {
        val freq = if (emergencyPriority) 14 else 10
        val cmd = FlipperProtocol.createIrStrobeRequest(freq, durationMs)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }

    /**
     * Honey-Potter: Send directed Wi-Fi probe requests for fleet SSIDs.
     * Forces hidden MDT networks to decloak.
     * @param ssid Target SSID to probe for (e.g., "NETMOTION", "UNIT_WIFI")
     */
    fun sendWifiHoneyPot(ssid: String): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.send(FlipperProtocol.createWifiProbeRequest(ssid)) ?: false
        ConnectionType.USB -> usbClient?.send(FlipperProtocol.createWifiProbeRequest(ssid)) ?: false
        ConnectionType.NONE -> false
    }

    /**
     * BlueForce Handshake: Enable active BLE scanning to request SCAN_RSP.
     * Reveals more device information from body cams and holsters.
     */
    fun sendBleActiveScan(active: Boolean = true): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.send(FlipperProtocol.createBleActiveScanRequest(active)) ?: false
        ConnectionType.USB -> usbClient?.send(FlipperProtocol.createBleActiveScanRequest(active)) ?: false
        ConnectionType.NONE -> false
    }

    // ========================================================================
    // Active Probes - Infrastructure & Utilities
    // ========================================================================

    /**
     * Zigbee Knocker: Send Zigbee beacon request to map smart meter mesh.
     * @param channel Zigbee channel (11-26), 0 for channel hop
     */
    fun sendZigbeeBeacon(channel: Int = 0): Boolean = when (_connectionType.value) {
        ConnectionType.BLUETOOTH -> bleClient?.send(FlipperProtocol.createZigbeeBeaconRequest(channel)) ?: false
        ConnectionType.USB -> usbClient?.send(FlipperProtocol.createZigbeeBeaconRequest(channel)) ?: false
        ConnectionType.NONE -> false
    }

    /**
     * Ghost Car: Pulse GPIO coil to spoof inductive loop sensors.
     * @param frequencyHz Resonant frequency of target loop
     * @param durationMs Pulse duration
     * @param pulseCount Number of pulses to simulate vehicle presence
     */
    fun sendInductiveLoopPulse(frequencyHz: Int, durationMs: Int = 500, pulseCount: Int = 3): Boolean {
        val cmd = FlipperProtocol.createGpioLoopPulseRequest(frequencyHz, durationMs, pulseCount)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }

    // ========================================================================
    // Active Probes - Physical Access
    // ========================================================================

    /**
     * Sleep Denial: Replay Sub-GHz signal to cause alarm fatigue.
     * @param frequency Target frequency in Hz
     * @param rawData Captured signal data to replay
     * @param repeatCount Number of times to replay
     */
    fun sendSubGhzReplay(frequency: Long, rawData: ByteArray, repeatCount: Int = 5): Boolean {
        val cmd = FlipperProtocol.createSubGhzReplayRequest(frequency, rawData, repeatCount)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }

    /**
     * Replay Injector: Replay captured Wiegand signal to bypass card reader.
     * @param facilityCode Facility code from captured card
     * @param cardNumber Card number from captured card
     * @param bitLength Wiegand format (26, 34, 37 bits)
     */
    fun sendWiegandReplay(facilityCode: Int, cardNumber: Int, bitLength: Int = 26): Boolean {
        val cmd = FlipperProtocol.createWiegandReplayRequest(facilityCode, cardNumber, bitLength)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }

    /**
     * MagSpoof: Emulate magstripe via electromagnetic pulses.
     * @param track1 Track 1 data (max 79 chars)
     * @param track2 Track 2 data (max 40 chars)
     */
    fun sendMagSpoof(track1: String?, track2: String?): Boolean {
        val cmd = FlipperProtocol.createMagSpoofRequest(track1, track2)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }

    /**
     * Master Key: Emulate iButton/Dallas 1-Wire key.
     * @param keyId 8-byte key ID (DS1990A format)
     */
    fun sendIButtonEmulate(keyId: ByteArray): Boolean {
        if (keyId.size != 8) return false
        val cmd = FlipperProtocol.createIButtonEmulateRequest(keyId)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }

    // ========================================================================
    // Active Probes - Digital Peripherals
    // ========================================================================

    /**
     * MouseJacker: Inject keystrokes into vulnerable wireless HID dongle.
     * @param address 5-byte NRF24 address of target dongle
     * @param keystrokes HID keycodes to inject
     */
    fun sendNrf24Inject(address: ByteArray, keystrokes: ByteArray): Boolean {
        if (address.size != 5) return false
        val cmd = FlipperProtocol.createNrf24InjectRequest(address, keystrokes)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
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
    fun configureSubGhzScanner(probeType: Int, frequencyHz: Long = 0, modulation: Int = 0): Boolean {
        val cmd = FlipperProtocol.createSubGhzConfigRequest(probeType, frequencyHz, modulation)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }

    /**
     * Configure IR scanner for Opticom detection.
     * @param detectOpticom true to detect 14/10Hz emergency strobes
     */
    fun configureIrScanner(detectOpticom: Boolean = true): Boolean {
        val cmd = FlipperProtocol.createIrConfigRequest(detectOpticom)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }

    /**
     * Configure NRF24 scanner for promiscuous mode.
     * @param promiscuous true to scan all channels for vulnerable devices
     */
    fun configureNrf24Scanner(promiscuous: Boolean = true): Boolean {
        val cmd = FlipperProtocol.createNrf24ConfigRequest(promiscuous)
        return when (_connectionType.value) {
            ConnectionType.BLUETOOTH -> bleClient?.send(cmd) ?: false
            ConnectionType.USB -> usbClient?.send(cmd) ?: false
            ConnectionType.NONE -> false
        }
    }
}

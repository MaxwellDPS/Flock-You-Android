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
            scope.launch {
                client.wipsEvents.collect { event ->
                    _wipsEvents.emit(event)
                }
            }
        }

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

    fun destroy() {
        disconnect()
        bleClient?.disconnect()
        usbClient?.destroy()
        scope.cancel()
    }
}

package com.flockyou.scanner.flipper

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Bluetooth LE client for communicating with Flipper Zero.
 * Uses BLE Serial protocol over custom service UUID.
 */
@SuppressLint("MissingPermission")
class FlipperBluetoothClient(private val context: Context) {

    companion object {
        private const val TAG = "FlipperBleClient"

        // Flipper Zero BLE Serial Service UUIDs
        // See: https://github.com/EstebanFuentealba/flipper-zero-bluetooth-serial-poc
        val SERVICE_UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        private val TX_CHAR_UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
        private val RX_CHAR_UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_MTU = 244
        private const val CONNECTION_TIMEOUT_MS = 15_000L
    }

    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveBuffer = mutableListOf<Byte>()

    // State flows
    private val _connectionState = MutableStateFlow(FlipperConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FlipperConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<FlipperMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<FlipperMessage> = _messages.asSharedFlow()

    private val _wipsEvents = MutableSharedFlow<FlipperWipsEvent>(extraBufferCapacity = 32)
    val wipsEvents: SharedFlow<FlipperWipsEvent> = _wipsEvents.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _connectedDevice = MutableStateFlow<String?>(null)
    val connectedDevice: StateFlow<String?> = _connectedDevice.asStateFlow()

    private val _flipperStatus = MutableStateFlow<FlipperStatusResponse?>(null)
    val flipperStatus: StateFlow<FlipperStatusResponse?> = _flipperStatus.asStateFlow()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to Flipper GATT server")
                    _connectionState.value = FlipperConnectionState.CONNECTED
                    _connectedDevice.value = gatt.device.address
                    gatt.requestMtu(MAX_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from Flipper")
                    cleanup()
                    _connectionState.value = FlipperConnectionState.DISCONNECTED
                    _connectedDevice.value = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu (status: $status)")
            _connectionState.value = FlipperConnectionState.DISCOVERING_SERVICES
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")
                setupCharacteristics(gatt)
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                _lastError.value = "Service discovery failed"
                _connectionState.value = FlipperConnectionState.ERROR
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                handleReceivedData(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                handleReceivedData(characteristic.value)
            }
        }
    }

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter?.isEnabled == true

    suspend fun connect(deviceAddress: String): Boolean {
        if (!isBluetoothAvailable()) {
            _lastError.value = "Bluetooth not available"
            return false
        }

        disconnect()
        _connectionState.value = FlipperConnectionState.CONNECTING

        return try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device == null) {
                _lastError.value = "Device not found"
                _connectionState.value = FlipperConnectionState.ERROR
                return false
            }

            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            // Wait for connection
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                _connectionState.first { it == FlipperConnectionState.READY || it == FlipperConnectionState.ERROR }
            }

            _connectionState.value == FlipperConnectionState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _lastError.value = e.message
            _connectionState.value = FlipperConnectionState.ERROR
            false
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        cleanup()
    }

    private fun cleanup() {
        gatt?.close()
        gatt = null
        txCharacteristic = null
        rxCharacteristic = null
        receiveBuffer.clear()
    }

    private fun setupCharacteristics(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Flipper BLE Serial service not found")
            _lastError.value = "Service not found"
            _connectionState.value = FlipperConnectionState.ERROR
            return
        }

        txCharacteristic = service.getCharacteristic(TX_CHAR_UUID)
        rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)

        if (txCharacteristic == null || rxCharacteristic == null) {
            Log.e(TAG, "Required characteristics not found")
            _lastError.value = "Characteristics not found"
            _connectionState.value = FlipperConnectionState.ERROR
            return
        }

        // Enable notifications on RX characteristic
        gatt.setCharacteristicNotification(rxCharacteristic, true)
        val descriptor = rxCharacteristic?.getDescriptor(CCCD_UUID)
        descriptor?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(it)
            }
        }

        Log.i(TAG, "Flipper BLE connection ready")
        _connectionState.value = FlipperConnectionState.READY
    }

    private fun handleReceivedData(data: ByteArray) {
        synchronized(receiveBuffer) {
            receiveBuffer.addAll(data.toList())
            processBuffer()
        }
    }

    private fun processBuffer() {
        while (receiveBuffer.size >= 4) {
            val headerBytes = receiveBuffer.take(4).toByteArray()
            val expectedSize = FlipperProtocol.getExpectedMessageSize(headerBytes)

            if (expectedSize <= 0 || receiveBuffer.size < expectedSize) break

            val messageData = receiveBuffer.take(expectedSize).toByteArray()
            repeat(expectedSize) { receiveBuffer.removeAt(0) }

            val message = FlipperProtocol.parseMessage(messageData)
            if (message != null) {
                scope.launch {
                    _messages.emit(message)
                    handleMessage(message)
                }
            }
        }
    }

    private suspend fun handleMessage(message: FlipperMessage) {
        when (message) {
            is FlipperMessage.StatusResponse -> _flipperStatus.value = message.status
            is FlipperMessage.WipsAlert -> {
                val event = FlipperWipsEvent(
                    type = mapAlertType(message.alert.alertType),
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

    private fun mapAlertType(type: WipsAlertType): FlipperWipsEventType = when (type) {
        WipsAlertType.EVIL_TWIN -> FlipperWipsEventType.EVIL_TWIN_DETECTED
        WipsAlertType.DEAUTH_ATTACK -> FlipperWipsEventType.DEAUTH_DETECTED
        WipsAlertType.KARMA_ATTACK -> FlipperWipsEventType.KARMA_DETECTED
        WipsAlertType.HIDDEN_NETWORK_STRONG -> FlipperWipsEventType.HIDDEN_NETWORK_STRONG
        WipsAlertType.SUSPICIOUS_OPEN_NETWORK -> FlipperWipsEventType.SUSPICIOUS_OPEN_NETWORK
        WipsAlertType.WEAK_ENCRYPTION -> FlipperWipsEventType.WEAK_ENCRYPTION
        WipsAlertType.MAC_SPOOFING -> FlipperWipsEventType.MAC_SPOOFING
        WipsAlertType.ROGUE_AP -> FlipperWipsEventType.ROGUE_AP
        else -> FlipperWipsEventType.DEAUTH_DETECTED
    }

    fun send(data: ByteArray): Boolean {
        val tx = txCharacteristic ?: return false
        val g = gatt ?: return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(tx, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                tx.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(tx)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            false
        }
    }

    suspend fun requestWifiScan(): Boolean = send(FlipperProtocol.createWifiScanRequest())
    suspend fun requestSubGhzScan(freqStart: Long, freqEnd: Long): Boolean = send(FlipperProtocol.createSubGhzScanRequest(freqStart, freqEnd))
    suspend fun requestStatus(): FlipperStatusResponse? {
        send(FlipperProtocol.createStatusRequest())
        return withTimeoutOrNull(2000) { _flipperStatus.first { it != null } }
    }
    fun sendHeartbeat(): Boolean = send(FlipperProtocol.createHeartbeat())
}

enum class FlipperWipsEventType {
    EVIL_TWIN_DETECTED,
    DEAUTH_DETECTED,
    KARMA_DETECTED,
    HIDDEN_NETWORK_STRONG,
    SUSPICIOUS_OPEN_NETWORK,
    WEAK_ENCRYPTION,
    MAC_SPOOFING,
    ROGUE_AP
}

data class FlipperWipsEvent(
    val type: FlipperWipsEventType,
    val ssid: String,
    val bssid: String,
    val description: String,
    val severity: WipsSeverity,
    val timestamp: Long = System.currentTimeMillis()
)

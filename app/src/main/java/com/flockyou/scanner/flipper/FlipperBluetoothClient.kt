package com.flockyou.scanner.flipper

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Bluetooth LE client for communicating with Flipper Zero.
 * Uses BLE Serial protocol over custom service UUID.
 * Automatically launches the Flock Bridge FAP if not running.
 */
@SuppressLint("MissingPermission")
class FlipperBluetoothClient(private val context: Context) {

    companion object {
        private const val TAG = "FlipperBleClient"

        // Flipper Zero BLE Serial Service UUIDs
        // Uses Nordic UART Service (NUS) - this is what Flipper's ble_profile_serial uses when FAP is running
        val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        // TX from Android perspective = RX on Flipper (we write to this)
        private val TX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        // RX from Android perspective = TX on Flipper (we receive notifications from this)
        private val RX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Flipper Zero default serial service UUIDs (CLI access when FAP is not running)
        private val FLIPPER_SERIAL_SERVICE_UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        private val FLIPPER_SERIAL_TX_UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000") // Phone writes
        private val FLIPPER_SERIAL_RX_UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000") // Phone reads

        // FAP path
        private const val FAP_PATH = "/ext/apps/Tools/flock_bridge.fap"
        private const val FAP_LAUNCH_COMMAND = "loader open $FAP_PATH\r\n"
        private const val FAP_LAUNCH_DELAY_MS = 3000L

        private const val MAX_MTU = 244
        private const val CONNECTION_TIMEOUT_MS = 15_000L
    }

    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    // CLI characteristics for launching FAP when not running
    private var cliTxCharacteristic: BluetoothGattCharacteristic? = null
    private var cliRxCharacteristic: BluetoothGattCharacteristic? = null
    private var isLaunchingFap = false
    private var pendingDeviceAddress: String? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveBuffer = mutableListOf<Byte>()

    // State flows
    private val _connectionState = MutableStateFlow(FlipperConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FlipperConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<FlipperMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<FlipperMessage> = _messages.asSharedFlow()

    private val _wipsEvents = MutableSharedFlow<FlipperWipsEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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

        // Store address for potential FAP launch reconnection
        pendingDeviceAddress = deviceAddress

        // Only disconnect if not already launching FAP (reconnect scenario)
        if (!isLaunchingFap) {
            disconnect()
        } else {
            // Clear previous gatt without full cleanup
            gatt?.close()
            gatt = null
            txCharacteristic = null
            rxCharacteristic = null
        }

        _connectionState.value = FlipperConnectionState.CONNECTING

        return try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device == null) {
                _lastError.value = "Device not found"
                _connectionState.value = FlipperConnectionState.ERROR
                return false
            }

            Log.i(TAG, "Connecting to Flipper at $deviceAddress${if (isLaunchingFap) " (reconnect after FAP launch)" else ""}")

            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            // Wait for connection - use longer timeout if launching FAP
            val timeout = if (isLaunchingFap) CONNECTION_TIMEOUT_MS * 2 else CONNECTION_TIMEOUT_MS
            withTimeoutOrNull(timeout) {
                _connectionState.first {
                    it == FlipperConnectionState.READY ||
                    it == FlipperConnectionState.ERROR ||
                    it == FlipperConnectionState.LAUNCHING_FAP  // Will reconnect after FAP launches
                }
            }

            _connectionState.value == FlipperConnectionState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _lastError.value = e.message
            _connectionState.value = FlipperConnectionState.ERROR
            isLaunchingFap = false
            false
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        cleanup()
    }

    /**
     * Releases all resources and cancels the CoroutineScope.
     * Call this when the client is no longer needed.
     */
    fun destroy() {
        disconnect()
        scope.cancel()
    }

    private fun cleanup() {
        gatt?.close()
        gatt = null
        txCharacteristic = null
        rxCharacteristic = null
        cliTxCharacteristic = null
        cliRxCharacteristic = null
        isLaunchingFap = false
        pendingDeviceAddress = null
        receiveBuffer.clear()
    }

    private fun setupCharacteristics(gatt: BluetoothGatt) {
        // First try to find the FAP's NUS service (FAP is running)
        val nusService = gatt.getService(SERVICE_UUID)
        if (nusService != null) {
            Log.i(TAG, "Found NUS service - FAP is running")
            txCharacteristic = nusService.getCharacteristic(TX_CHAR_UUID)
            rxCharacteristic = nusService.getCharacteristic(RX_CHAR_UUID)

            if (txCharacteristic == null || rxCharacteristic == null) {
                Log.e(TAG, "Required NUS characteristics not found")
                _lastError.value = "NUS characteristics not found"
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
            isLaunchingFap = false
            _connectionState.value = FlipperConnectionState.READY
            return
        }

        // NUS service not found - try Flipper CLI service to launch FAP
        val cliService = gatt.getService(FLIPPER_SERIAL_SERVICE_UUID)
        if (cliService != null) {
            Log.i(TAG, "Found CLI service - FAP not running, will launch it")
            cliTxCharacteristic = cliService.getCharacteristic(FLIPPER_SERIAL_TX_UUID)
            cliRxCharacteristic = cliService.getCharacteristic(FLIPPER_SERIAL_RX_UUID)

            if (cliTxCharacteristic == null) {
                Log.e(TAG, "CLI TX characteristic not found")
                _lastError.value = "CLI characteristic not found"
                _connectionState.value = FlipperConnectionState.ERROR
                return
            }

            // Launch the FAP
            launchFapViaCli(gatt)
            return
        }

        // Neither service found
        Log.e(TAG, "Neither NUS nor CLI service found - incompatible Flipper firmware?")
        _lastError.value = "Flipper services not found"
        _connectionState.value = FlipperConnectionState.ERROR
    }

    private fun launchFapViaCli(gatt: BluetoothGatt) {
        isLaunchingFap = true
        _connectionState.value = FlipperConnectionState.LAUNCHING_FAP
        Log.i(TAG, "Launching FAP via CLI: $FAP_LAUNCH_COMMAND")

        scope.launch {
            try {
                // Send the loader command
                val data = FAP_LAUNCH_COMMAND.toByteArray(Charsets.UTF_8)
                val tx = cliTxCharacteristic ?: return@launch

                val success = sendCliCommand(gatt, tx, data)

                if (success) {
                    Log.i(TAG, "FAP launch command sent, waiting for FAP to start...")
                } else {
                    Log.e(TAG, "Failed to send FAP launch command")
                }

                // Wait for FAP to start and initialize its BLE serial profile
                delay(FAP_LAUNCH_DELAY_MS)

                // Disconnect and reconnect to pick up the NUS service
                val deviceAddress = pendingDeviceAddress
                Log.i(TAG, "Reconnecting to device to connect to FAP...")
                gatt.disconnect()

                // Small delay before reconnecting
                delay(500)

                // Reconnect
                if (deviceAddress != null) {
                    connect(deviceAddress)
                } else {
                    _lastError.value = "Lost device address"
                    _connectionState.value = FlipperConnectionState.ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error launching FAP", e)
                _lastError.value = "Failed to launch FAP: ${e.message}"
                _connectionState.value = FlipperConnectionState.ERROR
            }
        }
    }

    private fun sendCliCommand(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendCliCommandApi33(gatt, characteristic, data)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending CLI command", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun sendCliCommandApi33(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
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
        _flipperStatus.value = null
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

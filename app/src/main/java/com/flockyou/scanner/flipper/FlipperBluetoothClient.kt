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
 * Represents a discovered Bluetooth device during scanning.
 */
data class DiscoveredFlipperDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

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
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
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
        private const val FAP_LAUNCH_INITIAL_DELAY_MS = 4000L  // Initial wait for FAP to start BLE serial

        private const val MAX_MTU = 244
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val SCAN_TIMEOUT_MS = 10_000L

        // Flipper device name patterns
        private val FLIPPER_NAME_PATTERNS = listOf("Flipper", "flipper", "FLIPPER")
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
    private var fapLaunchAttempts = 0
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

    // Device scanning state
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredFlipperDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredFlipperDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _currentRssi = MutableStateFlow<Int?>(null)
    val currentRssi: StateFlow<Int?> = _currentRssi.asStateFlow()

    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private var rssiReadJob: Job? = null
    private var servicesDiscoveryStarted = false  // Guard against duplicate discoverServices calls

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
            // Guard against duplicate discoverServices calls (onMtuChanged can fire multiple times)
            if (!servicesDiscoveryStarted) {
                servicesDiscoveryStarted = true
                _connectionState.value = FlipperConnectionState.DISCOVERING_SERVICES
                gatt.discoverServices()
            } else {
                Log.d(TAG, "Service discovery already started, skipping duplicate call")
            }
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

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _currentRssi.value = rssi
                Log.d(TAG, "RSSI read: $rssi dBm")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Notifications enabled successfully - connection ready")
                    isLaunchingFap = false
                    fapLaunchAttempts = 0
                    _connectionState.value = FlipperConnectionState.READY
                } else {
                    Log.e(TAG, "Failed to enable notifications: status $status")
                    _lastError.value = "Failed to enable notifications"
                    _connectionState.value = FlipperConnectionState.ERROR
                }
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
        fapLaunchAttempts = 0
        pendingDeviceAddress = null
        servicesDiscoveryStarted = false
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
            // State will be set to READY in onDescriptorWrite callback
            gatt.setCharacteristicNotification(rxCharacteristic, true)
            val descriptor = rxCharacteristic?.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                Log.i(TAG, "Enabling notifications on RX characteristic...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                // No CCCD descriptor - mark ready anyway (some devices don't require it)
                Log.w(TAG, "No CCCD descriptor found, marking ready anyway")
                isLaunchingFap = false
                fapLaunchAttempts = 0
                _connectionState.value = FlipperConnectionState.READY
            }
            return
        }

        // NUS service not found - try Flipper CLI service to launch FAP
        val cliService = gatt.getService(FLIPPER_SERIAL_SERVICE_UUID)
        if (cliService != null) {
            // If we were launching FAP and still see CLI, the FAP didn't start yet
            // Try waiting longer and reconnecting
            if (isLaunchingFap) {
                fapLaunchAttempts++
                if (fapLaunchAttempts >= 3) {
                    Log.e(TAG, "Still found CLI service after $fapLaunchAttempts reconnect attempts - FAP did not start")
                    _lastError.value = "FAP did not start. Check if flock_bridge.fap exists on Flipper."
                    _connectionState.value = FlipperConnectionState.ERROR
                    isLaunchingFap = false
                    fapLaunchAttempts = 0
                    return
                }

                // Wait longer and try again - BLE stack restart can take time
                val delayMs = 3000L * fapLaunchAttempts  // 3s, 6s, etc.
                Log.w(TAG, "Still found CLI service after FAP launch (attempt $fapLaunchAttempts), waiting ${delayMs}ms and retrying...")
                _connectionState.value = FlipperConnectionState.LAUNCHING_FAP

                scope.launch {
                    gatt.disconnect()
                    delay(delayMs)
                    pendingDeviceAddress?.let { connect(it) }
                }
                return
            }

            // First time seeing CLI - launch the FAP
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
            fapLaunchAttempts = 1
            launchFapViaCli(gatt)
            return
        }

        // Neither service found
        Log.e(TAG, "Neither NUS nor CLI service found - incompatible Flipper firmware?")
        _lastError.value = "Flipper services not found"
        _connectionState.value = FlipperConnectionState.ERROR
    }

    private fun launchFapViaCli(gatt: BluetoothGatt) {
        // Prevent multiple concurrent FAP launch attempts
        if (isLaunchingFap) {
            Log.d(TAG, "FAP launch already in progress, skipping")
            return
        }
        isLaunchingFap = true
        _connectionState.value = FlipperConnectionState.LAUNCHING_FAP
        Log.i(TAG, "Launching FAP via CLI: $FAP_LAUNCH_COMMAND")

        scope.launch {
            try {
                // Send the loader command
                val data = FAP_LAUNCH_COMMAND.toByteArray(Charsets.UTF_8)
                val tx = cliTxCharacteristic ?: return@launch
                val deviceAddress = pendingDeviceAddress

                val success = sendCliCommand(gatt, tx, data)

                if (success) {
                    Log.i(TAG, "FAP launch command sent, waiting for FAP to start...")
                } else {
                    Log.e(TAG, "Failed to send FAP launch command")
                    _lastError.value = "Failed to send FAP launch command"
                    _connectionState.value = FlipperConnectionState.ERROR
                    return@launch
                }

                // Disconnect immediately so Flipper can restart BLE stack
                Log.i(TAG, "Disconnecting to allow Flipper to restart BLE stack...")
                gatt.disconnect()
                gatt.close()

                if (deviceAddress == null) {
                    _lastError.value = "Lost device address"
                    _connectionState.value = FlipperConnectionState.ERROR
                    return@launch
                }

                // Wait for FAP to start and initialize its BLE serial profile
                // The Flipper doesn't advertise service UUIDs, so we can't scan for NUS.
                // Instead, we wait and then reconnect to check services via GATT.
                Log.i(TAG, "Waiting ${FAP_LAUNCH_INITIAL_DELAY_MS}ms for FAP to initialize BLE serial...")
                delay(FAP_LAUNCH_INITIAL_DELAY_MS)

                // Reconnect - setupCharacteristics will check for NUS service
                Log.i(TAG, "Reconnecting to check if FAP started...")
                connect(deviceAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching FAP", e)
                _lastError.value = "Failed to launch FAP: ${e.message}"
                _connectionState.value = FlipperConnectionState.ERROR
                isLaunchingFap = false
            }
        }
    }

    private fun sendCliCommand(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return try {
            Log.d(TAG, "Sending CLI command: ${String(data).trim()}, char properties: ${characteristic.properties}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendCliCommandApi33(gatt, characteristic, data)
            } else {
                // For older APIs, set write type based on characteristic properties
                val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                characteristic.writeType = writeType
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
        // Try WRITE_TYPE_NO_RESPONSE first (Flipper CLI serial typically uses this)
        var result = gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        if (result == BluetoothStatusCodes.SUCCESS) {
            return true
        }
        Log.d(TAG, "WRITE_TYPE_NO_RESPONSE failed ($result), trying WRITE_TYPE_DEFAULT")
        // Fallback to default write type
        result = gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        return result == BluetoothStatusCodes.SUCCESS
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
                Log.d(TAG, "Parsed message: ${message::class.simpleName}")
                scope.launch {
                    _messages.emit(message)
                    handleMessage(message)
                }
            } else {
                Log.w(TAG, "Failed to parse message of size $expectedSize")
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

    // ========== Bluetooth Device Scanning ==========

    /**
     * Start scanning for Flipper Zero devices via Bluetooth LE.
     * Results are emitted to discoveredDevices flow.
     * @param timeoutMs How long to scan in milliseconds (default 10s)
     */
    fun startDeviceScan(timeoutMs: Long = SCAN_TIMEOUT_MS) {
        if (!isBluetoothAvailable()) {
            Log.w(TAG, "Bluetooth not available for device scan")
            return
        }

        if (_isScanning.value) {
            Log.d(TAG, "Already scanning for devices")
            return
        }

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return
        }

        // Clear previous results
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        // Create scan callback
        scanCallback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = device.name
                val hasNus = hasNusService(result)
                val hasCli = hasFlipperCliService(result)

                // Debug: log devices with names or Flipper services
                if (name != null || hasNus || hasCli) {
                    Log.d(TAG, "BLE scan found: '$name' (${device.address}) RSSI: ${result.rssi} hasNUS: $hasNus hasCLI: $hasCli")
                }

                // Accept device if: has Flipper name pattern OR has NUS service (FAP running) OR has CLI service (FAP not running)
                val isFlipperByName = isFlipperDevice(name)
                val isFlipperByNus = hasNus && name != null
                val isFlipperByCli = hasCli && name != null  // CLI service means it's a Flipper even without FAP
                val isLikelyFlipper = isFlipperByName || isFlipperByNus || isFlipperByCli

                // Always show devices with names (user may have custom Flipper name like "Ruciro")
                if (name == null) return

                val displayName = if (isLikelyFlipper) {
                    name
                } else {
                    "$name (select if Flipper)"
                }
                Log.i(TAG, "Found BLE device: $displayName (${device.address}) isFlipper=$isLikelyFlipper")

                val discovered = DiscoveredFlipperDevice(
                    address = device.address,
                    name = displayName,
                    rssi = result.rssi,
                    lastSeen = System.currentTimeMillis()
                )

                // Update or add to list
                val currentList = _discoveredDevices.value.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.address == discovered.address }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = discovered
                } else {
                    currentList.add(discovered)
                }
                _discoveredDevices.value = currentList.sortedByDescending { it.rssi }

                Log.d(TAG, "Found Flipper device: ${discovered.name} (${discovered.address}) RSSI: ${discovered.rssi}")
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                _isScanning.value = false
            }
        }

        // Configure scan settings
        val scanSettings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            bleScanner?.startScan(null, scanSettings, scanCallback)
            Log.i(TAG, "Started BLE device scan")

            // Auto-stop after timeout
            scope.launch {
                delay(timeoutMs)
                stopDeviceScan()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            _isScanning.value = false
        }
    }

    /**
     * Stop scanning for Bluetooth devices.
     */
    fun stopDeviceScan() {
        if (!_isScanning.value) return

        try {
            scanCallback?.let { callback ->
                bleScanner?.stopScan(callback)
            }
            Log.i(TAG, "Stopped BLE device scan")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE scan", e)
        } finally {
            _isScanning.value = false
            scanCallback = null
        }
    }

    /**
     * Check if a device name matches Flipper Zero patterns.
     */
    private fun isFlipperDevice(name: String?): Boolean {
        if (name == null) return false
        return FLIPPER_NAME_PATTERNS.any { pattern -> name.contains(pattern, ignoreCase = true) }
    }

    /**
     * Check if a device has the Nordic UART Service UUID (indicates Flipper FAP serial profile).
     */
    private fun hasNusService(result: android.bluetooth.le.ScanResult): Boolean {
        val serviceUuids = result.scanRecord?.serviceUuids ?: return false
        return serviceUuids.any { it.uuid == SERVICE_UUID }
    }

    /**
     * Check if a device has the Flipper CLI Service UUID (indicates Flipper without FAP running).
     * This allows detecting Flipper even when Flock Bridge FAP is not running.
     */
    private fun hasFlipperCliService(result: android.bluetooth.le.ScanResult): Boolean {
        val serviceUuids = result.scanRecord?.serviceUuids ?: return false
        return serviceUuids.any { it.uuid == FLIPPER_SERIAL_SERVICE_UUID }
    }

    /**
     * Clear discovered devices list.
     */
    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    // ========== Connection Quality (RSSI) ==========

    /**
     * Start periodic RSSI reading for connection quality indication.
     * @param intervalMs How often to read RSSI (default 2000ms)
     */
    fun startRssiMonitoring(intervalMs: Long = 2000L) {
        rssiReadJob?.cancel()
        rssiReadJob = scope.launch {
            while (_connectionState.value == FlipperConnectionState.READY) {
                readRssi()
                delay(intervalMs)
            }
            _currentRssi.value = null
        }
    }

    /**
     * Stop RSSI monitoring.
     */
    fun stopRssiMonitoring() {
        rssiReadJob?.cancel()
        rssiReadJob = null
        _currentRssi.value = null
    }

    /**
     * Request a single RSSI reading from the connected device.
     */
    private fun readRssi() {
        val g = gatt ?: return
        if (_connectionState.value != FlipperConnectionState.READY) return

        try {
            g.readRemoteRssi()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read RSSI", e)
        }
    }

    /**
     * Get signal strength as a level (0-4) based on RSSI.
     */
    fun getSignalLevel(rssi: Int? = _currentRssi.value): Int {
        return when {
            rssi == null -> 0
            rssi >= -50 -> 4  // Excellent
            rssi >= -60 -> 3  // Good
            rssi >= -70 -> 2  // Fair
            rssi >= -80 -> 1  // Weak
            else -> 0         // Very weak
        }
    }
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

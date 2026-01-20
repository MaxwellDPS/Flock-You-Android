package com.flockyou.scanner.flipper

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors

/**
 * USB Serial Client for communicating with Flipper Zero via USB CDC.
 * Implements AutoCloseable to ensure proper resource cleanup.
 */
class FlipperUsbClient(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "FlipperUsbClient"
        private const val FLIPPER_VENDOR_ID = 0x0483  // STMicroelectronics
        private const val FLIPPER_PRODUCT_ID = 0x5740 // Flipper Zero CDC
        private const val ACTION_USB_PERMISSION = "com.flockyou.USB_PERMISSION"
        private const val BAUD_RATE = 115200
    }

    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private var usbConnection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()

    private val _connectionState = MutableStateFlow(FlipperConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FlipperConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<FlipperMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<FlipperMessage> = _messages.asSharedFlow()

    // Dynamic receive buffer with max size limit to handle large messages
    private val maxBufferSize = 65536 // 64KB max
    private var receiveBuffer = ByteArray(4096)
    private var receiveBufferPosition = 0

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(TAG, "USB permission granted")
                        device?.let { connectToDevice(it) }
                    } else {
                        Log.w(TAG, "USB permission denied")
                        _connectionState.value = FlipperConnectionState.ERROR
                    }
                }
            }
        }
    }

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (isFlipperDevice(device)) {
                        Log.i(TAG, "Flipper Zero attached via USB")
                        device?.let { connect(it) }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (isFlipperDevice(device)) {
                        Log.i(TAG, "Flipper Zero detached")
                        disconnect()
                    }
                }
            }
        }
    }

    private var receiversRegistered = false
    @Volatile private var isInitialized = false

    init {
        // Delay receiver registration to avoid race conditions
        // Receivers will be registered on first use
        isInitialized = true
    }

    /**
     * Ensures receivers are registered. Safe to call multiple times.
     * Must be called before any USB operations.
     */
    fun ensureReceiversRegistered() {
        if (!isInitialized) return
        registerReceivers()
    }

    private fun registerReceivers() {
        if (receiversRegistered) return

        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val stateFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
            // USB attach/detach are system broadcasts - use RECEIVER_NOT_EXPORTED for security
            // System broadcasts are delivered regardless of the export flag
            context.registerReceiver(usbStateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, permissionFilter)
            context.registerReceiver(usbStateReceiver, stateFilter)
        }

        receiversRegistered = true
    }

    fun findFlipperDevices(): List<UsbDevice> {
        Log.i(TAG, "findFlipperDevices called")
        ensureReceiversRegistered()
        return try {
            val deviceList = usbManager?.deviceList
            Log.i(TAG, "USB device count: ${deviceList?.size ?: 0}")
            val flippers = deviceList?.values?.filter { isFlipperDevice(it) } ?: emptyList()
            Log.i(TAG, "Found ${flippers.size} Flipper device(s)")
            flippers
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Flipper devices: ${e.message}", e)
            emptyList()
        }
    }

    private fun isFlipperDevice(device: UsbDevice?): Boolean {
        if (device == null) return false
        return device.vendorId == FLIPPER_VENDOR_ID && device.productId == FLIPPER_PRODUCT_ID
    }

    fun connect(device: UsbDevice) {
        ensureReceiversRegistered()
        try {
            val manager = usbManager
            if (manager == null) {
                Log.e(TAG, "USB Manager not available")
                _connectionState.value = FlipperConnectionState.ERROR
                return
            }

            Log.i(TAG, "Connecting to Flipper Zero: ${device.deviceName}")
            _connectionState.value = FlipperConnectionState.CONNECTING

            if (!manager.hasPermission(device)) {
                Log.i(TAG, "Requesting USB permission")
                // Create explicit intent with package to satisfy Android 14+ requirements
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(context.packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
                manager.requestPermission(device, permissionIntent)
                return
            }

            connectToDevice(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            _connectionState.value = FlipperConnectionState.ERROR
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        scope.launch {
            try {
                val manager = usbManager
                if (manager == null) {
                    Log.e(TAG, "USB Manager not available")
                    _connectionState.value = FlipperConnectionState.ERROR
                    return@launch
                }

                val driver = findDriver(device)
                if (driver == null) {
                    Log.e(TAG, "No driver found for device")
                    _connectionState.value = FlipperConnectionState.ERROR
                    return@launch
                }

                val connection = manager.openDevice(device)
                if (connection == null) {
                    Log.e(TAG, "Failed to open USB connection")
                    _connectionState.value = FlipperConnectionState.ERROR
                    return@launch
                }

                usbConnection = connection

                // Check if driver has any ports
                if (driver.ports.isEmpty()) {
                    Log.e(TAG, "No serial ports found for device")
                    _connectionState.value = FlipperConnectionState.ERROR
                    return@launch
                }

                // Select the correct port:
                // - In dual CDC mode (FAP running): use port 1 (Flock protocol)
                // - In single CDC mode: use port 0 (CLI, but FAP not running)
                val portIndex = if (driver.ports.size > 1) 1 else 0
                Log.i(TAG, "Found ${driver.ports.size} CDC port(s), using port $portIndex")
                val port = driver.ports[portIndex]
                port.open(connection)
                port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = true
                port.rts = true

                serialPort = port

                val listener = object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        handleReceivedData(data)
                    }

                    override fun onRunError(e: Exception) {
                        Log.e(TAG, "USB I/O error", e)
                        scope.launch {
                            disconnect()
                            _connectionState.value = FlipperConnectionState.ERROR
                        }
                    }
                }

                ioManager = SerialInputOutputManager(port, listener).also {
                    executor.submit(it)
                }

                _connectionState.value = FlipperConnectionState.READY
                Log.i(TAG, "Connected to Flipper Zero via USB on port $portIndex of ${driver.ports.size}")

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = FlipperConnectionState.ERROR
            }
        }
    }

    private fun findDriver(device: UsbDevice): com.hoho.android.usbserial.driver.UsbSerialDriver? {
        Log.i(TAG, "findDriver: device=${device.deviceName}, vendor=${device.vendorId}, product=${device.productId}")
        Log.i(TAG, "findDriver: interfaceCount=${device.interfaceCount}")

        return try {
            // For Flipper Zero, prefer CdcAcmSerialDriver directly
            // The default prober might select wrong driver for STM devices
            if (isFlipperDevice(device)) {
                Log.i(TAG, "Creating CdcAcmSerialDriver for Flipper Zero")
                val driver = CdcAcmSerialDriver(device)
                Log.i(TAG, "CdcAcmSerialDriver created, ports=${driver.ports.size}")
                return driver
            }

            // For other devices, try default prober first
            Log.i(TAG, "Trying default prober")
            val defaultDrivers = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (defaultDrivers != null) {
                Log.i(TAG, "Default prober found driver: ${defaultDrivers.javaClass.simpleName}")
                return defaultDrivers
            }
            Log.i(TAG, "Default prober returned null, creating CdcAcmSerialDriver")
            CdcAcmSerialDriver(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding driver for device: ${e.message}", e)
            null
        } catch (e: Error) {
            Log.e(TAG, "Fatal error finding driver for device: ${e.message}", e)
            null
        }
    }

    private fun handleReceivedData(data: ByteArray) {
        Log.d(TAG, "Received ${data.size} bytes: ${data.take(8).joinToString(" ") { "%02X".format(it) }}")
        synchronized(receiveBuffer) {
            // Check if we need to grow the buffer
            val requiredSize = receiveBufferPosition + data.size
            if (requiredSize > receiveBuffer.size) {
                if (requiredSize > maxBufferSize) {
                    // Buffer would exceed max size - log warning and drop oldest data
                    Log.w(TAG, "Receive buffer overflow, dropping old data (required: $requiredSize, max: $maxBufferSize)")
                    val keepSize = (maxBufferSize - data.size).coerceAtLeast(0)
                    if (keepSize > 0) {
                        val dropSize = receiveBufferPosition - keepSize
                        System.arraycopy(receiveBuffer, dropSize, receiveBuffer, 0, keepSize)
                        receiveBufferPosition = keepSize
                    } else {
                        receiveBufferPosition = 0
                    }
                } else {
                    // Grow buffer to accommodate new data (double size or required size, whichever is larger)
                    val newSize = maxOf(receiveBuffer.size * 2, requiredSize).coerceAtMost(maxBufferSize)
                    val newBuffer = ByteArray(newSize)
                    System.arraycopy(receiveBuffer, 0, newBuffer, 0, receiveBufferPosition)
                    receiveBuffer = newBuffer
                }
            }

            val toCopy = data.size.coerceAtMost(receiveBuffer.size - receiveBufferPosition)
            System.arraycopy(data, 0, receiveBuffer, receiveBufferPosition, toCopy)
            receiveBufferPosition += toCopy
            processBuffer()
        }
    }

    private fun processBuffer() {
        while (receiveBufferPosition >= 4) {
            val expectedSize = FlipperProtocol.getExpectedMessageSize(receiveBuffer.copyOf(receiveBufferPosition))

            // Invalid header (wrong version, garbage data) - skip one byte and try again
            if (expectedSize < 0) {
                System.arraycopy(receiveBuffer, 1, receiveBuffer, 0, receiveBufferPosition - 1)
                receiveBufferPosition--
                continue
            }

            // Not enough data for complete message - wait for more
            if (receiveBufferPosition < expectedSize) break

            val messageData = receiveBuffer.copyOf(expectedSize)
            val message = FlipperProtocol.parseMessage(messageData)

            System.arraycopy(receiveBuffer, expectedSize, receiveBuffer, 0, receiveBufferPosition - expectedSize)
            receiveBufferPosition -= expectedSize

            if (message != null) {
                scope.launch { _messages.emit(message) }
            }
        }
    }

    fun send(data: ByteArray): Boolean {
        val port = serialPort ?: return false
        return try {
            port.write(data, 1000)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data", e)
            false
        }
    }

    fun requestWifiScan(): Boolean {
        Log.d(TAG, "Sending WiFi scan request")
        return send(FlipperProtocol.createWifiScanRequest())
    }
    fun requestSubGhzScan(freqStart: Long = 300_000_000L, freqEnd: Long = 928_000_000L): Boolean {
        Log.d(TAG, "Sending Sub-GHz scan request")
        return send(FlipperProtocol.createSubGhzScanRequest(freqStart, freqEnd))
    }
    fun requestBleScan(): Boolean {
        Log.d(TAG, "Sending BLE scan request")
        return send(FlipperProtocol.createBleScanRequest())
    }
    fun requestIrScan(): Boolean {
        Log.d(TAG, "Sending IR scan request")
        return send(FlipperProtocol.createIrScanRequest())
    }
    fun requestNfcScan(): Boolean {
        Log.d(TAG, "Sending NFC scan request")
        return send(FlipperProtocol.createNfcScanRequest())
    }
    fun requestStatus(): Boolean {
        Log.d(TAG, "Sending status request")
        return send(FlipperProtocol.createStatusRequest())
    }
    fun sendHeartbeat(): Boolean = send(FlipperProtocol.createHeartbeat())

    fun disconnect() {
        Log.i(TAG, "Disconnecting from Flipper Zero USB")

        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null

        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null

        try { usbConnection?.close() } catch (_: Exception) {}
        usbConnection = null

        receiveBufferPosition = 0
        _connectionState.value = FlipperConnectionState.DISCONNECTED
    }

    fun destroy() {
        disconnect()

        if (receiversRegistered) {
            try {
                context.unregisterReceiver(usbPermissionReceiver)
                context.unregisterReceiver(usbStateReceiver)
            } catch (_: Exception) {}
            receiversRegistered = false
        }

        scope.cancel()
        executor.shutdown()
    }

    /**
     * AutoCloseable implementation - delegates to destroy().
     * Ensures proper resource cleanup when used with use {} block or try-with-resources.
     */
    override fun close() {
        destroy()
    }
}

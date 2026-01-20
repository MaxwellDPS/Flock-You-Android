package com.flockyou.scanner.flipper

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.flockyou.data.model.Detection
import com.flockyou.data.model.SurveillancePattern
import com.flockyou.data.repository.DetectionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Flipper Zero scanning operations, coordinating between the Flipper client,
 * detection adapter, and app repositories.
 */
@Singleton
class FlipperScannerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detectionRepository: DetectionRepository,
    private val settingsRepository: FlipperSettingsRepository
) {
    companion object {
        private const val TAG = "FlipperScannerManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flipperClient: FlipperClient? = null
    private val detectionAdapter = FlipperDetectionAdapter()

    // State flows
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectionState = MutableStateFlow(FlipperConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FlipperConnectionState> = _connectionState.asStateFlow()

    private val _connectionType = MutableStateFlow(FlipperClient.ConnectionType.NONE)
    val connectionType: StateFlow<FlipperClient.ConnectionType> = _connectionType.asStateFlow()

    private val _flipperStatus = MutableStateFlow<FlipperStatusResponse?>(null)
    val flipperStatus: StateFlow<FlipperStatusResponse?> = _flipperStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _detectionCount = MutableStateFlow(0)
    val detectionCount: StateFlow<Int> = _detectionCount.asStateFlow()

    private val _wipsAlertCount = MutableStateFlow(0)
    val wipsAlertCount: StateFlow<Int> = _wipsAlertCount.asStateFlow()

    // Current settings cache
    private var currentSettings = FlipperSettings()
    private var surveillancePatterns = emptyList<SurveillancePattern>()

    // Current location
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    // Scan jobs
    private var wifiScanJob: Job? = null
    private var subGhzScanJob: Job? = null
    private var bleScanJob: Job? = null
    private var heartbeatJob: Job? = null

    init {
        // Observe settings changes
        scope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                if (_isRunning.value) {
                    restartScanLoops()
                }
            }
        }
    }

    /**
     * Initialize the Flipper client
     */
    fun initialize() {
        if (flipperClient != null) return

        flipperClient = FlipperClient(context).also { client ->
            // Observe connection state
            scope.launch {
                client.connectionState.collect { state ->
                    _connectionState.value = state
                    if (state == FlipperConnectionState.ERROR) {
                        _lastError.value = "Connection error"
                    }
                }
            }

            // Observe connection type
            scope.launch {
                client.connectionType.collect { type ->
                    _connectionType.value = type
                }
            }

            // Observe messages
            scope.launch {
                client.messages.collect { message ->
                    handleFlipperMessage(message)
                }
            }

            // Observe WIPS events
            scope.launch {
                client.wipsEvents.collect { event ->
                    handleWipsEvent(event)
                }
            }

            // Observe status
            scope.launch {
                client.status.collect { status ->
                    _flipperStatus.value = status
                }
            }
        }

        Log.i(TAG, "FlipperScannerManager initialized")
    }

    /**
     * Connect to Flipper via Bluetooth
     */
    fun connectBluetooth(deviceAddress: String) {
        initialize()
        flipperClient?.connectBluetooth(deviceAddress)
    }

    /**
     * Connect to Flipper via USB
     */
    fun connectUsb(device: UsbDevice) {
        initialize()
        flipperClient?.connectUsb(device)
    }

    /**
     * Auto-connect to USB Flipper if available
     */
    fun connectUsb(): Boolean {
        initialize()
        return flipperClient?.connectUsb() ?: false
    }

    /**
     * Find available USB Flipper devices
     */
    fun findUsbDevices(): List<UsbDevice> {
        initialize()
        return flipperClient?.findUsbDevices() ?: emptyList()
    }

    /**
     * Disconnect from Flipper
     */
    fun disconnect() {
        stopScanning()
        flipperClient?.disconnect()
    }

    /**
     * Connect using preferred method from settings
     */
    fun connect() {
        initialize()
        scope.launch {
            val settings = settingsRepository.settings.first()
            when (settings.preferredConnection) {
                FlipperConnectionPreference.USB_PREFERRED,
                FlipperConnectionPreference.USB_ONLY -> {
                    if (!connectUsb()) {
                        if (settings.preferredConnection == FlipperConnectionPreference.USB_PREFERRED) {
                            settings.savedBluetoothAddress?.let { connectBluetooth(it) }
                        }
                    }
                }
                FlipperConnectionPreference.BLUETOOTH_PREFERRED,
                FlipperConnectionPreference.BLUETOOTH_ONLY -> {
                    settings.savedBluetoothAddress?.let { address ->
                        connectBluetooth(address)
                    } ?: run {
                        if (settings.preferredConnection == FlipperConnectionPreference.BLUETOOTH_PREFERRED) {
                            connectUsb()
                        }
                    }
                }
            }
        }
    }

    /**
     * Start the manager (auto-connect if settings allow)
     */
    fun start() {
        initialize()
        scope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.autoConnectUsb || settings.autoConnectBluetooth) {
                connect()
            }
        }
    }

    /**
     * Stop the manager
     */
    fun stop() {
        stopScanning()
        disconnect()
    }

    /**
     * Upload a file to the connected Flipper Zero
     * Returns true if successful
     *
     * On failure, sends abort command to clean up partial write state on Flipper.
     */
    suspend fun uploadFile(
        localFile: java.io.File,
        remotePath: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (!isConnected()) return false

        return withContext(Dispatchers.IO) {
            val client = flipperClient ?: return@withContext false
            var writeStarted = false

            try {
                val data = localFile.readBytes()
                val totalSize = data.size

                if (totalSize == 0) {
                    Log.w(TAG, "Cannot upload empty file: $remotePath")
                    return@withContext false
                }

                // Send storage write command
                // The Flipper storage protocol requires:
                // 1. Start write with path
                // 2. Send chunks
                // 3. End write

                val startCommand = FlipperProtocol.buildStorageWriteStartCommand(remotePath, totalSize.toLong())
                if (!client.sendRawCommand(startCommand)) {
                    Log.e(TAG, "Failed to send write start command")
                    return@withContext false
                }
                writeStarted = true

                // Send data in chunks
                val chunkSize = 512
                var offset = 0
                while (offset < totalSize) {
                    val remaining = totalSize - offset
                    val currentChunkSize = minOf(chunkSize, remaining)
                    val chunk = data.copyOfRange(offset, offset + currentChunkSize)

                    val chunkCommand = FlipperProtocol.buildStorageWriteDataCommand(chunk)
                    if (!client.sendRawCommand(chunkCommand)) {
                        throw java.io.IOException("Failed to send data chunk at offset $offset")
                    }

                    offset += currentChunkSize
                    onProgress(offset.toFloat() / totalSize)

                    // Small delay to not overwhelm the connection
                    delay(10)
                }

                // Send end command
                val endCommand = FlipperProtocol.buildStorageWriteEndCommand()
                if (!client.sendRawCommand(endCommand)) {
                    throw java.io.IOException("Failed to send write end command")
                }

                onProgress(1f)
                Log.i(TAG, "File uploaded successfully: $remotePath")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload file: $remotePath", e)

                // Send abort command to clean up partial write state on Flipper
                if (writeStarted) {
                    try {
                        val abortCommand = FlipperProtocol.buildStorageWriteAbortCommand()
                        client.sendRawCommand(abortCommand)
                        Log.d(TAG, "Sent storage write abort command")
                    } catch (abortError: Exception) {
                        Log.w(TAG, "Failed to send abort command", abortError)
                    }
                }
                false
            }
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = flipperClient?.isConnected() ?: false

    /**
     * Get the FlipperClient for direct probe execution.
     * Returns null if not initialized.
     */
    val client: FlipperClient?
        get() = flipperClient

    /**
     * Start scanning with current settings
     */
    fun startScanning(patterns: List<SurveillancePattern> = emptyList()) {
        if (_isRunning.value) return
        if (!isConnected()) {
            _lastError.value = "Not connected to Flipper"
            return
        }

        surveillancePatterns = patterns
        _isRunning.value = true
        _detectionCount.value = 0
        _wipsAlertCount.value = 0

        startScanLoops()
        Log.i(TAG, "Flipper scanning started")
    }

    /**
     * Stop scanning
     */
    fun stopScanning() {
        if (!_isRunning.value) return

        _isRunning.value = false
        cancelScanLoops()
        Log.i(TAG, "Flipper scanning stopped")
    }

    /**
     * Update current location for detection tagging
     */
    fun updateLocation(latitude: Double?, longitude: Double?) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    /**
     * Update surveillance patterns for threat detection
     */
    fun updatePatterns(patterns: List<SurveillancePattern>) {
        surveillancePatterns = patterns
    }

    /**
     * Request Flipper status
     */
    fun requestStatus() {
        flipperClient?.requestStatus()
    }

    private fun startScanLoops() {
        // WiFi scan loop
        if (currentSettings.enableWifiScanning) {
            wifiScanJob = scope.launch {
                while (_isRunning.value) {
                    flipperClient?.requestWifiScan()
                    delay(currentSettings.wifiScanIntervalSeconds * 1000L)
                }
            }
        }

        // Sub-GHz scan loop
        if (currentSettings.enableSubGhzScanning) {
            subGhzScanJob = scope.launch {
                while (_isRunning.value) {
                    flipperClient?.requestSubGhzScan(
                        currentSettings.subGhzFrequencyStart,
                        currentSettings.subGhzFrequencyEnd
                    )
                    delay(currentSettings.subGhzScanIntervalSeconds * 1000L)
                }
            }
        }

        // BLE scan loop
        if (currentSettings.enableBleScanning) {
            bleScanJob = scope.launch {
                while (_isRunning.value) {
                    flipperClient?.requestBleScan()
                    delay(currentSettings.bleScanIntervalSeconds * 1000L)
                }
            }
        }

        // IR scan (one-shot if enabled)
        if (currentSettings.enableIrScanning) {
            scope.launch {
                flipperClient?.requestIrScan()
            }
        }

        // NFC scan (one-shot if enabled)
        if (currentSettings.enableNfcScanning) {
            scope.launch {
                flipperClient?.requestNfcScan()
            }
        }

        // Heartbeat loop
        heartbeatJob = scope.launch {
            while (_isRunning.value) {
                flipperClient?.sendHeartbeat()
                delay(currentSettings.heartbeatIntervalSeconds * 1000L)
            }
        }
    }

    private fun cancelScanLoops() {
        wifiScanJob?.cancel()
        subGhzScanJob?.cancel()
        bleScanJob?.cancel()
        heartbeatJob?.cancel()
        wifiScanJob = null
        subGhzScanJob = null
        bleScanJob = null
        heartbeatJob = null
    }

    private fun restartScanLoops() {
        cancelScanLoops()
        if (_isRunning.value) {
            startScanLoops()
        }
    }

    private suspend fun handleFlipperMessage(message: FlipperMessage) {
        val timestamp = System.currentTimeMillis()

        when (message) {
            is FlipperMessage.WifiScanResult -> {
                message.result.networks.forEach { network ->
                    val detection = detectionAdapter.wifiNetworkToDetection(
                        network, timestamp, currentLatitude, currentLongitude, surveillancePatterns
                    )
                    detection?.let { saveDetection(it) }
                }
            }

            is FlipperMessage.SubGhzScanResult -> {
                message.result.detections.forEach { subGhzDetection ->
                    val detection = detectionAdapter.subGhzDetectionToDetection(
                        subGhzDetection, timestamp, currentLatitude, currentLongitude
                    )
                    saveDetection(detection)
                }
            }

            is FlipperMessage.BleScanResult -> {
                message.result.devices.forEach { bleDevice ->
                    val detection = detectionAdapter.bleDeviceToDetection(
                        bleDevice, timestamp, currentLatitude, currentLongitude
                    )
                    detection?.let { saveDetection(it) }
                }
            }

            is FlipperMessage.IrScanResult -> {
                message.result.detections.forEach { irDetection ->
                    val detection = detectionAdapter.irDetectionToDetection(
                        irDetection, timestamp, currentLatitude, currentLongitude
                    )
                    saveDetection(detection)
                }
            }

            is FlipperMessage.NfcScanResult -> {
                message.result.detections.forEach { nfcDetection ->
                    val detection = detectionAdapter.nfcDetectionToDetection(
                        nfcDetection, timestamp, currentLatitude, currentLongitude
                    )
                    saveDetection(detection)
                }
            }

            is FlipperMessage.StatusResponse -> {
                _flipperStatus.value = message.status
            }

            is FlipperMessage.Error -> {
                _lastError.value = "Flipper error ${message.code}: ${message.message}"
                Log.e(TAG, "Flipper error: ${message.code} - ${message.message}")
            }

            is FlipperMessage.Heartbeat -> {
                // Connection alive
            }

            is FlipperMessage.WipsAlert -> {
                // Handled via wipsEvents flow
            }
        }
    }

    private suspend fun handleWipsEvent(event: FlipperWipsEvent) {
        if (!currentSettings.wipsEnabled) return

        // Check if specific detection is enabled
        val shouldProcess = when (event.type) {
            FlipperWipsEventType.EVIL_TWIN_DETECTED -> currentSettings.wipsEvilTwinDetection
            FlipperWipsEventType.DEAUTH_DETECTED -> currentSettings.wipsDeauthDetection
            FlipperWipsEventType.KARMA_DETECTED -> currentSettings.wipsKarmaDetection
            FlipperWipsEventType.ROGUE_AP -> currentSettings.wipsRogueApDetection
            else -> true
        }

        if (!shouldProcess) return

        _wipsAlertCount.value++

        val detection = detectionAdapter.wipsEventToDetection(
            event, currentLatitude, currentLongitude
        )
        saveDetection(detection)

        Log.w(TAG, "WIPS Alert: ${event.type.name} - ${event.description}")
    }

    private suspend fun saveDetection(detection: Detection) {
        try {
            val isNew = detectionRepository.upsertDetection(detection)
            if (isNew) {
                _detectionCount.value++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save detection", e)
        }
    }

    /**
     * Cleanup resources
     */
    fun destroy() {
        stopScanning()
        flipperClient?.destroy()
        flipperClient = null
        scope.cancel()
    }
}

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
    private val settingsRepository: FlipperSettingsRepository,
    private val alertManager: FlipperAlertManager
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

    // Scan scheduler status
    private val _scanSchedulerStatus = MutableStateFlow(ScanSchedulerStatus())
    val scanSchedulerStatus: StateFlow<ScanSchedulerStatus> = _scanSchedulerStatus.asStateFlow()

    // Live Sub-GHz scan status from Flipper (frequency hopping, RSSI, etc.)
    private val _subGhzScanStatus = MutableStateFlow<FlipperSubGhzScanStatus?>(null)
    val subGhzScanStatus: StateFlow<FlipperSubGhzScanStatus?> = _subGhzScanStatus.asStateFlow()

    // Auto-reconnect state
    private val _autoReconnectState = MutableStateFlow(AutoReconnectState())
    val autoReconnectState: StateFlow<AutoReconnectState> = _autoReconnectState.asStateFlow()

    // Discovered Bluetooth devices (from scanning)
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredFlipperDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredFlipperDevice>> = _discoveredDevices.asStateFlow()

    // Device scanning state
    private val _isScanningForDevices = MutableStateFlow(false)
    val isScanningForDevices: StateFlow<Boolean> = _isScanningForDevices.asStateFlow()

    // Connection quality (RSSI)
    private val _connectionRssi = MutableStateFlow<Int?>(null)
    val connectionRssi: StateFlow<Int?> = _connectionRssi.asStateFlow()

    // Recent devices from settings
    val recentDevices: Flow<List<RecentFlipperDevice>> = settingsRepository.recentDevices

    // Current settings cache
    private var currentSettings = FlipperSettings()
    private var surveillancePatterns = emptyList<SurveillancePattern>()

    // Auto-reconnect job
    private var autoReconnectJob: Job? = null
    private var lastConnectedAddress: String? = null
    private var lastConnectedName: String? = null
    private var lastConnectionType: FlipperClient.ConnectionType = FlipperClient.ConnectionType.NONE

    // Current location
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    // Scan jobs
    private var wifiScanJob: Job? = null
    private var subGhzScanJob: Job? = null
    private var bleScanJob: Job? = null
    private var heartbeatJob: Job? = null
    private var nfcScanJob: Job? = null
    private var irScanJob: Job? = null

    // Pause state
    private val _isPaused = MutableStateFlow(false)

    // Last scan times
    private val _lastWifiScanTime = MutableStateFlow<Long?>(null)
    private val _lastSubGhzScanTime = MutableStateFlow<Long?>(null)
    private val _lastBleScanTime = MutableStateFlow<Long?>(null)
    private val _lastNfcScanTime = MutableStateFlow<Long?>(null)
    private val _lastIrScanTime = MutableStateFlow<Long?>(null)

    // Currently scanning flags
    private val _isWifiScanning = MutableStateFlow(false)
    private val _isSubGhzScanning = MutableStateFlow(false)
    private val _isBleScanning = MutableStateFlow(false)
    private val _isNfcScanning = MutableStateFlow(false)
    private val _isIrScanning = MutableStateFlow(false)

    // Cooldown timestamps
    private val _wifiScanCooldownUntil = MutableStateFlow(0L)
    private val _subGhzScanCooldownUntil = MutableStateFlow(0L)
    private val _bleScanCooldownUntil = MutableStateFlow(0L)
    private val _nfcScanCooldownUntil = MutableStateFlow(0L)
    private val _irScanCooldownUntil = MutableStateFlow(0L)

    // Cooldown duration in milliseconds (3 seconds)
    private val SCAN_COOLDOWN_MS = 3000L

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

        // Auto-start scanning and request status when connection becomes READY
        // Also handle auto-reconnect on disconnection
        scope.launch {
            _connectionState.collect { state ->
                when (state) {
                    FlipperConnectionState.READY -> {
                        // Request status to populate Flipper info (battery, uptime, etc.)
                        Log.i(TAG, "Connection ready, requesting Flipper status")
                        flipperClient?.requestStatus()

                        // Cancel any pending auto-reconnect
                        cancelAutoReconnect()

                        // Save to recent devices if we have address info
                        saveConnectionToHistory()

                        // Auto-start scanning if not already running
                        if (!_isRunning.value) {
                            Log.i(TAG, "Auto-starting Flipper scanning")
                            startScanning(surveillancePatterns)
                        }

                        // Start RSSI monitoring for Bluetooth connections
                        if (_connectionType.value == FlipperClient.ConnectionType.BLUETOOTH) {
                            startRssiMonitoring()
                        }
                    }
                    FlipperConnectionState.DISCONNECTED -> {
                        // Stop RSSI monitoring
                        stopRssiMonitoring()

                        // Start auto-reconnect if enabled and we have a previous connection
                        if (lastConnectedAddress != null || lastConnectionType == FlipperClient.ConnectionType.USB) {
                            scope.launch {
                                val autoReconnectEnabled = settingsRepository.autoReconnectEnabled.first()
                                if (autoReconnectEnabled) {
                                    startAutoReconnect()
                                }
                            }
                        }
                    }
                    FlipperConnectionState.ERROR -> {
                        // Stop RSSI monitoring
                        stopRssiMonitoring()
                    }
                    else -> {}
                }
            }
        }

        // Periodic status refresh to keep Flipper info updated
        scope.launch {
            while (true) {
                delay(30_000) // Every 30 seconds
                if (_connectionState.value == FlipperConnectionState.READY) {
                    flipperClient?.requestStatus()
                }
            }
        }
    }

    /**
     * Initialize the Flipper client
     */
    fun initialize() {
        if (flipperClient != null) return

        try {
            flipperClient = FlipperClient(context).also { client ->
            // Observe connection state
            scope.launch {
                client.connectionState.collect { state ->
                    _connectionState.update { state }
                    if (state == FlipperConnectionState.ERROR) {
                        _lastError.update { "Connection error" }
                    }
                }
            }

            // Observe connection type
            scope.launch {
                client.connectionType.collect { type ->
                    _connectionType.update { type }
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
                    _flipperStatus.update { status }
                }
            }
        }

            Log.i(TAG, "FlipperScannerManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing FlipperClient", e)
            _lastError.update { "Failed to initialize: ${e.message}" }
            _connectionState.update { FlipperConnectionState.ERROR }
        } catch (e: Error) {
            Log.e(TAG, "Fatal error initializing FlipperClient", e)
            _lastError.update { "Fatal error: ${e.message}" }
            _connectionState.update { FlipperConnectionState.ERROR }
        }
    }

    /**
     * Connect to Flipper via Bluetooth
     */
    fun connectBluetooth(deviceAddress: String, deviceName: String = "Flipper") {
        initialize()
        lastConnectedAddress = deviceAddress
        lastConnectedName = deviceName
        lastConnectionType = FlipperClient.ConnectionType.BLUETOOTH
        flipperClient?.connectBluetooth(deviceAddress)
    }

    /**
     * Connect to Flipper via USB
     */
    fun connectUsb(device: UsbDevice) {
        initialize()
        lastConnectedAddress = null
        lastConnectedName = "Flipper (USB)"
        lastConnectionType = FlipperClient.ConnectionType.USB
        flipperClient?.connectUsb(device)
    }

    /**
     * Auto-connect to USB Flipper if available
     */
    fun connectUsb(): Boolean {
        initialize()
        lastConnectedAddress = null
        lastConnectedName = "Flipper (USB)"
        lastConnectionType = FlipperClient.ConnectionType.USB
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
        cancelAutoReconnect()
        stopScanning()
        stopRssiMonitoring()
        flipperClient?.disconnect()
        // Clear last connected info to prevent auto-reconnect
        lastConnectedAddress = null
        lastConnectedName = null
        lastConnectionType = FlipperClient.ConnectionType.NONE
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
            _lastError.update { "Not connected to Flipper" }
            return
        }

        surveillancePatterns = patterns
        _isRunning.update { true }
        _detectionCount.update { 0 }
        _wipsAlertCount.update { 0 }

        startScanLoops()
        Log.i(TAG, "Flipper scanning started")
    }

    /**
     * Stop scanning
     */
    fun stopScanning() {
        if (!_isRunning.value) return

        _isRunning.update { false }
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
                while (_isRunning.value && !_isPaused.value) {
                    _isWifiScanning.value = true
                    updateScanSchedulerStatus()
                    flipperClient?.requestWifiScan()
                    _lastWifiScanTime.value = System.currentTimeMillis()
                    delay(500) // Brief scanning animation
                    _isWifiScanning.value = false
                    updateScanSchedulerStatus()
                    delay((currentSettings.wifiScanIntervalSeconds * 1000L) - 500)
                }
            }
        }

        // Sub-GHz scan loop
        if (currentSettings.enableSubGhzScanning) {
            subGhzScanJob = scope.launch {
                while (_isRunning.value && !_isPaused.value) {
                    _isSubGhzScanning.value = true
                    updateScanSchedulerStatus()
                    flipperClient?.requestSubGhzScan(
                        currentSettings.subGhzFrequencyStart,
                        currentSettings.subGhzFrequencyEnd
                    )
                    _lastSubGhzScanTime.value = System.currentTimeMillis()
                    delay(500) // Brief scanning animation
                    _isSubGhzScanning.value = false
                    updateScanSchedulerStatus()
                    delay((currentSettings.subGhzScanIntervalSeconds * 1000L) - 500)
                }
            }
        }

        // BLE scan loop
        if (currentSettings.enableBleScanning) {
            bleScanJob = scope.launch {
                while (_isRunning.value && !_isPaused.value) {
                    _isBleScanning.value = true
                    updateScanSchedulerStatus()
                    flipperClient?.requestBleScan()
                    _lastBleScanTime.value = System.currentTimeMillis()
                    delay(500) // Brief scanning animation
                    _isBleScanning.value = false
                    updateScanSchedulerStatus()
                    delay((currentSettings.bleScanIntervalSeconds * 1000L) - 500)
                }
            }
        }

        // IR scan (one-shot if enabled, but can be retriggered)
        if (currentSettings.enableIrScanning) {
            irScanJob = scope.launch {
                _isIrScanning.value = true
                updateScanSchedulerStatus()
                flipperClient?.requestIrScan()
                _lastIrScanTime.value = System.currentTimeMillis()
                delay(500)
                _isIrScanning.value = false
                updateScanSchedulerStatus()
            }
        }

        // NFC scan (one-shot if enabled, but can be retriggered)
        if (currentSettings.enableNfcScanning) {
            nfcScanJob = scope.launch {
                _isNfcScanning.value = true
                updateScanSchedulerStatus()
                flipperClient?.requestNfcScan()
                _lastNfcScanTime.value = System.currentTimeMillis()
                delay(500)
                _isNfcScanning.value = false
                updateScanSchedulerStatus()
            }
        }

        // Heartbeat loop
        heartbeatJob = scope.launch {
            while (_isRunning.value) {
                flipperClient?.sendHeartbeat()
                delay(currentSettings.heartbeatIntervalSeconds * 1000L)
            }
        }

        // Update scheduler status after starting loops
        updateScanSchedulerStatus()
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

        // Update scheduler status after canceling loops
        updateScanSchedulerStatus()
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

            is FlipperMessage.SubGhzScanStatus -> {
                // Update live Sub-GHz scan status (frequency, RSSI, hop count, etc.)
                _subGhzScanStatus.update { message.status }
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
                _flipperStatus.update { message.status }
            }

            is FlipperMessage.Error -> {
                _lastError.update { "Flipper error ${message.code}: ${message.message}" }
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

        _wipsAlertCount.update { it + 1 }

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
                _detectionCount.update { it + 1 }
                // Trigger alert for new detections (haptic, sound, notification)
                alertManager.onDetection(detection, isNew = true)
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

    /**
     * Update scan scheduler status based on current state
     */
    private fun updateScanSchedulerStatus() {
        _scanSchedulerStatus.update {
            ScanSchedulerStatus(
                wifiScanActive = wifiScanJob?.isActive == true && !_isPaused.value,
                wifiScanIntervalSeconds = currentSettings.wifiScanIntervalSeconds,
                subGhzScanActive = subGhzScanJob?.isActive == true && !_isPaused.value,
                subGhzScanIntervalSeconds = currentSettings.subGhzScanIntervalSeconds,
                subGhzFrequencyStart = currentSettings.subGhzFrequencyStart,
                subGhzFrequencyEnd = currentSettings.subGhzFrequencyEnd,
                bleScanActive = bleScanJob?.isActive == true && !_isPaused.value,
                bleScanIntervalSeconds = currentSettings.bleScanIntervalSeconds,
                heartbeatActive = heartbeatJob?.isActive == true,
                heartbeatIntervalSeconds = currentSettings.heartbeatIntervalSeconds,
                irScanEnabled = currentSettings.enableIrScanning,
                nfcScanEnabled = currentSettings.enableNfcScanning,
                wipsEnabled = currentSettings.wipsEnabled,
                isPaused = _isPaused.value,
                lastWifiScanTime = _lastWifiScanTime.value,
                lastSubGhzScanTime = _lastSubGhzScanTime.value,
                lastBleScanTime = _lastBleScanTime.value,
                lastNfcScanTime = _lastNfcScanTime.value,
                lastIrScanTime = _lastIrScanTime.value,
                isWifiScanning = _isWifiScanning.value,
                isSubGhzScanning = _isSubGhzScanning.value,
                isBleScanning = _isBleScanning.value,
                isNfcScanning = _isNfcScanning.value,
                isIrScanning = _isIrScanning.value,
                wifiScanCooldownUntil = _wifiScanCooldownUntil.value,
                subGhzScanCooldownUntil = _subGhzScanCooldownUntil.value,
                bleScanCooldownUntil = _bleScanCooldownUntil.value,
                nfcScanCooldownUntil = _nfcScanCooldownUntil.value,
                irScanCooldownUntil = _irScanCooldownUntil.value
            )
        }
    }

    /**
     * Pause all scanning loops (keeps connection alive)
     */
    fun pauseScanning() {
        if (_isPaused.value || !_isRunning.value) return

        Log.i(TAG, "Pausing Flipper scanning")
        _isPaused.value = true

        // Cancel scan loops but keep heartbeat running
        wifiScanJob?.cancel()
        subGhzScanJob?.cancel()
        bleScanJob?.cancel()
        nfcScanJob?.cancel()
        irScanJob?.cancel()
        wifiScanJob = null
        subGhzScanJob = null
        bleScanJob = null
        nfcScanJob = null
        irScanJob = null

        updateScanSchedulerStatus()
    }

    /**
     * Resume scanning after pause
     */
    fun resumeScanning() {
        if (!_isPaused.value || !_isRunning.value) return

        Log.i(TAG, "Resuming Flipper scanning")
        _isPaused.value = false

        startScanLoops()
    }

    /**
     * Toggle pause/resume state
     */
    fun togglePauseScanning() {
        if (_isPaused.value) {
            resumeScanning()
        } else {
            pauseScanning()
        }
    }

    /**
     * Trigger a manual scan for a specific scan type.
     * Returns true if scan was triggered, false if on cooldown or not connected.
     */
    fun triggerManualScan(scanType: FlipperScanType): Boolean {
        if (!isConnected()) {
            Log.w(TAG, "Cannot trigger manual scan: not connected")
            return false
        }

        val now = System.currentTimeMillis()

        // Check cooldown
        val cooldownUntil = when (scanType) {
            FlipperScanType.WIFI -> _wifiScanCooldownUntil.value
            FlipperScanType.SUB_GHZ -> _subGhzScanCooldownUntil.value
            FlipperScanType.BLE -> _bleScanCooldownUntil.value
            FlipperScanType.NFC -> _nfcScanCooldownUntil.value
            FlipperScanType.IR -> _irScanCooldownUntil.value
        }

        if (now < cooldownUntil) {
            Log.d(TAG, "Manual scan on cooldown for $scanType")
            return false
        }

        // Set scanning flag and cooldown
        when (scanType) {
            FlipperScanType.WIFI -> {
                _isWifiScanning.value = true
                _wifiScanCooldownUntil.value = now + SCAN_COOLDOWN_MS
            }
            FlipperScanType.SUB_GHZ -> {
                _isSubGhzScanning.value = true
                _subGhzScanCooldownUntil.value = now + SCAN_COOLDOWN_MS
            }
            FlipperScanType.BLE -> {
                _isBleScanning.value = true
                _bleScanCooldownUntil.value = now + SCAN_COOLDOWN_MS
            }
            FlipperScanType.NFC -> {
                _isNfcScanning.value = true
                _nfcScanCooldownUntil.value = now + SCAN_COOLDOWN_MS
            }
            FlipperScanType.IR -> {
                _isIrScanning.value = true
                _irScanCooldownUntil.value = now + SCAN_COOLDOWN_MS
            }
        }

        updateScanSchedulerStatus()

        scope.launch {
            try {
                when (scanType) {
                    FlipperScanType.WIFI -> {
                        flipperClient?.requestWifiScan()
                        _lastWifiScanTime.value = System.currentTimeMillis()
                    }
                    FlipperScanType.SUB_GHZ -> {
                        flipperClient?.requestSubGhzScan(
                            currentSettings.subGhzFrequencyStart,
                            currentSettings.subGhzFrequencyEnd
                        )
                        _lastSubGhzScanTime.value = System.currentTimeMillis()
                    }
                    FlipperScanType.BLE -> {
                        flipperClient?.requestBleScan()
                        _lastBleScanTime.value = System.currentTimeMillis()
                    }
                    FlipperScanType.NFC -> {
                        flipperClient?.requestNfcScan()
                        _lastNfcScanTime.value = System.currentTimeMillis()
                    }
                    FlipperScanType.IR -> {
                        flipperClient?.requestIrScan()
                        _lastIrScanTime.value = System.currentTimeMillis()
                    }
                }

                // Reset scanning flag after a brief delay to allow animation
                delay(1500)

                when (scanType) {
                    FlipperScanType.WIFI -> _isWifiScanning.value = false
                    FlipperScanType.SUB_GHZ -> _isSubGhzScanning.value = false
                    FlipperScanType.BLE -> _isBleScanning.value = false
                    FlipperScanType.NFC -> _isNfcScanning.value = false
                    FlipperScanType.IR -> _isIrScanning.value = false
                }

                updateScanSchedulerStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Error during manual scan", e)
                // Reset scanning flag on error
                when (scanType) {
                    FlipperScanType.WIFI -> _isWifiScanning.value = false
                    FlipperScanType.SUB_GHZ -> _isSubGhzScanning.value = false
                    FlipperScanType.BLE -> _isBleScanning.value = false
                    FlipperScanType.NFC -> _isNfcScanning.value = false
                    FlipperScanType.IR -> _isIrScanning.value = false
                }
                updateScanSchedulerStatus()
            }
        }

        Log.i(TAG, "Triggered manual scan: $scanType")
        return true
    }

    /**
     * Check if a scan type is currently on cooldown
     */
    fun isScanOnCooldown(scanType: FlipperScanType): Boolean {
        val now = System.currentTimeMillis()
        return when (scanType) {
            FlipperScanType.WIFI -> now < _wifiScanCooldownUntil.value
            FlipperScanType.SUB_GHZ -> now < _subGhzScanCooldownUntil.value
            FlipperScanType.BLE -> now < _bleScanCooldownUntil.value
            FlipperScanType.NFC -> now < _nfcScanCooldownUntil.value
            FlipperScanType.IR -> now < _irScanCooldownUntil.value
        }
    }

    // ========== Auto-Reconnect ==========

    private fun startAutoReconnect() {
        if (autoReconnectJob?.isActive == true) return

        autoReconnectJob = scope.launch {
            val maxAttempts = settingsRepository.autoReconnectMaxAttempts.first()
            var attemptNumber = 0
            val baseDelayMs = 2000L
            val maxDelayMs = 30000L

            while (attemptNumber < maxAttempts && _connectionState.value != FlipperConnectionState.READY) {
                attemptNumber++
                val delayMs = minOf(baseDelayMs * attemptNumber, maxDelayMs)

                _autoReconnectState.value = AutoReconnectState(
                    isReconnecting = true,
                    attemptNumber = attemptNumber,
                    maxAttempts = maxAttempts,
                    lastAttemptTimestamp = System.currentTimeMillis(),
                    nextAttemptDelayMs = delayMs
                )

                Log.i(TAG, "Auto-reconnect attempt $attemptNumber/$maxAttempts")

                // Attempt reconnection based on last connection type
                when (lastConnectionType) {
                    FlipperClient.ConnectionType.BLUETOOTH -> {
                        lastConnectedAddress?.let { address ->
                            flipperClient?.connectBluetooth(address)
                        }
                    }
                    FlipperClient.ConnectionType.USB -> {
                        flipperClient?.connectUsb()
                    }
                    FlipperClient.ConnectionType.NONE -> {
                        // Try USB first, then Bluetooth
                        if (flipperClient?.connectUsb() != true) {
                            lastConnectedAddress?.let { address ->
                                flipperClient?.connectBluetooth(address)
                            }
                        }
                    }
                }

                // Wait and check if connected
                delay(delayMs)

                if (_connectionState.value == FlipperConnectionState.READY) {
                    Log.i(TAG, "Auto-reconnect successful on attempt $attemptNumber")
                    break
                }
            }

            if (_connectionState.value != FlipperConnectionState.READY) {
                Log.w(TAG, "Auto-reconnect failed after $maxAttempts attempts")
                _lastError.value = "Reconnection failed after $maxAttempts attempts"
            }

            _autoReconnectState.value = AutoReconnectState(isReconnecting = false)
        }
    }

    /**
     * Cancel auto-reconnect attempts.
     */
    fun cancelAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        _autoReconnectState.value = AutoReconnectState(isReconnecting = false)
    }

    private fun saveConnectionToHistory() {
        scope.launch {
            val address = lastConnectedAddress
            val name = lastConnectedName ?: "Flipper"
            val type = lastConnectionType.name

            if (address != null) {
                settingsRepository.addRecentDevice(address, name, type)
                Log.d(TAG, "Saved connection to history: $name ($address)")
            }
        }
    }

    // ========== Bluetooth Device Scanning ==========

    private var bleClient: FlipperBluetoothClient? = null

    /**
     * Start scanning for Flipper devices via Bluetooth.
     */
    fun startDeviceScan() {
        if (bleClient == null) {
            bleClient = FlipperBluetoothClient(context)
        }

        // Observe discovered devices
        scope.launch {
            bleClient?.discoveredDevices?.collect { devices ->
                _discoveredDevices.value = devices
            }
        }

        // Observe scanning state
        scope.launch {
            bleClient?.isScanning?.collect { scanning ->
                _isScanningForDevices.value = scanning
            }
        }

        bleClient?.startDeviceScan()
        Log.i(TAG, "Started Bluetooth device scan")
    }

    /**
     * Stop scanning for Bluetooth devices.
     */
    fun stopDeviceScan() {
        bleClient?.stopDeviceScan()
        Log.i(TAG, "Stopped Bluetooth device scan")
    }

    /**
     * Clear discovered devices list.
     */
    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
        bleClient?.clearDiscoveredDevices()
    }

    /**
     * Connect to a recently used device from history.
     */
    fun connectToRecentDevice(device: RecentFlipperDevice) {
        when (device.connectionType) {
            "BLUETOOTH" -> connectBluetooth(device.address, device.name)
            "USB" -> connectUsb()
            else -> Log.w(TAG, "Unknown connection type: ${device.connectionType}")
        }
    }

    /**
     * Remove a device from connection history.
     */
    fun removeFromHistory(address: String) {
        scope.launch {
            settingsRepository.removeRecentDevice(address)
        }
    }

    // ========== Connection Quality (RSSI) ==========

    private var rssiMonitorJob: Job? = null

    private fun startRssiMonitoring() {
        rssiMonitorJob?.cancel()
        rssiMonitorJob = scope.launch {
            // Get the BLE client from the FlipperClient if using Bluetooth
            // For now, we'll monitor via the bleClient we use for scanning
            if (bleClient == null) {
                bleClient = FlipperBluetoothClient(context)
            }

            bleClient?.currentRssi?.collect { rssi ->
                _connectionRssi.value = rssi
            }
        }

        // Start monitoring on the underlying BLE client
        bleClient?.startRssiMonitoring()
    }

    private fun stopRssiMonitoring() {
        rssiMonitorJob?.cancel()
        rssiMonitorJob = null
        _connectionRssi.value = null
        bleClient?.stopRssiMonitoring()
    }

    /**
     * Get signal level (0-4) based on current RSSI.
     */
    fun getSignalLevel(): Int {
        val rssi = _connectionRssi.value ?: return 0
        return when {
            rssi >= -50 -> 4  // Excellent
            rssi >= -60 -> 3  // Good
            rssi >= -70 -> 2  // Fair
            rssi >= -80 -> 1  // Weak
            else -> 0         // Very weak
        }
    }
}

/**
 * Scan type enum for triggering manual scans and tracking last scan times
 */
enum class FlipperScanType {
    WIFI,
    SUB_GHZ,
    BLE,
    NFC,
    IR
}

/**
 * Status of the Flipper scan scheduler showing which scan loops are active
 */
data class ScanSchedulerStatus(
    val wifiScanActive: Boolean = false,
    val wifiScanIntervalSeconds: Int = 30,
    val subGhzScanActive: Boolean = false,
    val subGhzScanIntervalSeconds: Int = 15,
    val subGhzFrequencyStart: Long = 300_000_000L,
    val subGhzFrequencyEnd: Long = 928_000_000L,
    val bleScanActive: Boolean = false,
    val bleScanIntervalSeconds: Int = 20,
    val heartbeatActive: Boolean = false,
    val heartbeatIntervalSeconds: Int = 5,
    val irScanEnabled: Boolean = false,
    val nfcScanEnabled: Boolean = false,
    val wipsEnabled: Boolean = true,
    // Pause/Resume state
    val isPaused: Boolean = false,
    // Last scan timestamps (null if never scanned)
    val lastWifiScanTime: Long? = null,
    val lastSubGhzScanTime: Long? = null,
    val lastBleScanTime: Long? = null,
    val lastNfcScanTime: Long? = null,
    val lastIrScanTime: Long? = null,
    // Currently scanning flags for animation
    val isWifiScanning: Boolean = false,
    val isSubGhzScanning: Boolean = false,
    val isBleScanning: Boolean = false,
    val isNfcScanning: Boolean = false,
    val isIrScanning: Boolean = false,
    // Manual scan cooldown tracking
    val wifiScanCooldownUntil: Long = 0L,
    val subGhzScanCooldownUntil: Long = 0L,
    val bleScanCooldownUntil: Long = 0L,
    val nfcScanCooldownUntil: Long = 0L,
    val irScanCooldownUntil: Long = 0L
)

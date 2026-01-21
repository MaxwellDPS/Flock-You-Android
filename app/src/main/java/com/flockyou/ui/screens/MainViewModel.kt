package com.flockyou.ui.screens

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.flockyou.data.BroadcastSettings
import com.flockyou.data.BroadcastSettingsRepository
import com.flockyou.data.DetectionSettingsRepository
import com.flockyou.data.NetworkSettings
import com.flockyou.data.NetworkSettingsRepository
import com.flockyou.data.OuiSettings
import com.flockyou.data.OuiSettingsRepository
import com.flockyou.data.PrivacySettings
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.RetentionPeriod
import com.flockyou.data.model.*
import com.flockyou.data.repository.EphemeralDetectionRepository
import com.flockyou.worker.DataRetentionWorker
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.network.OrbotHelper
import com.flockyou.network.TorAwareHttpClient
import com.flockyou.network.TorConnectionStatus
import com.flockyou.scanner.flipper.FlipperClient
import com.flockyou.scanner.flipper.FlipperConnectionState
import com.flockyou.scanner.flipper.FlipperScannerManager
import com.flockyou.scanner.flipper.FlipperStatusResponse
import com.flockyou.service.CellularMonitor
import com.flockyou.service.RfSignalAnalyzer
import com.flockyou.service.RogueWifiMonitor
import com.flockyou.service.ScanningService
import com.flockyou.service.ScanningServiceConnection
import com.flockyou.service.UltrasonicDetector
import com.flockyou.worker.OuiUpdateWorker
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

data class MainUiState(
    val isScanning: Boolean = false,
    val isLoading: Boolean = true,
    val detections: List<Detection> = emptyList(),
    val totalCount: Int = 0,
    val highThreatCount: Int = 0,
    val lastDetection: Detection? = null,
    val selectedTab: Int = 0,
    val filterThreatLevel: ThreatLevel? = null,
    val filterDeviceTypes: Set<DeviceType> = emptySet(), // Multiple device types now
    val filterMatchAll: Boolean = true, // true = AND, false = OR
    val hideFalsePositives: Boolean = true, // Hide detections flagged as FP by default
    val fpFilterThreshold: Float = 0.6f, // FP score threshold for filtering (MEDIUM_CONFIDENCE)
    // Status information
    val scanStatus: ScanningService.ScanStatus = ScanningService.ScanStatus.Idle,
    val bleStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    val wifiStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    val locationStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    val cellularStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    val satelliteStatus: ScanningService.SubsystemStatus = ScanningService.SubsystemStatus.Idle,
    val recentErrors: List<ScanningService.ScanError> = emptyList(),
    // Seen devices (from IPC)
    val seenBleDevices: List<ScanningService.SeenDevice> = emptyList(),
    val seenWifiNetworks: List<ScanningService.SeenDevice> = emptyList(),
    // Cellular monitoring
    val cellStatus: CellularMonitor.CellStatus? = null,
    val cellularAnomalies: List<CellularMonitor.CellularAnomaly> = emptyList(),
    val seenCellTowers: List<CellularMonitor.SeenCellTower> = emptyList(),
    val cellularEvents: List<CellularMonitor.CellularEvent> = emptyList(),
    // Satellite monitoring
    val satelliteState: com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionState? = null,
    val satelliteAnomalies: List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly> = emptyList(),
    val satelliteHistory: List<com.flockyou.monitoring.SatelliteMonitor.SatelliteConnectionEvent> = emptyList(),
    // Rogue WiFi monitoring
    val rogueWifiStatus: RogueWifiMonitor.WifiEnvironmentStatus? = null,
    val rogueWifiAnomalies: List<RogueWifiMonitor.WifiAnomaly> = emptyList(),
    val suspiciousNetworks: List<RogueWifiMonitor.SuspiciousNetwork> = emptyList(),
    // RF signal analysis
    val rfStatus: RfSignalAnalyzer.RfEnvironmentStatus? = null,
    val rfAnomalies: List<RfSignalAnalyzer.RfAnomaly> = emptyList(),
    val detectedDrones: List<RfSignalAnalyzer.DroneInfo> = emptyList(),
    // Ultrasonic detection
    val ultrasonicStatus: UltrasonicDetector.UltrasonicStatus? = null,
    val ultrasonicAnomalies: List<UltrasonicDetector.UltrasonicAnomaly> = emptyList(),
    val ultrasonicBeacons: List<UltrasonicDetector.BeaconDetection> = emptyList(),
    // GNSS satellite monitoring
    val gnssStatus: com.flockyou.monitoring.GnssSatelliteMonitor.GnssEnvironmentStatus? = null,
    val gnssSatellites: List<com.flockyou.monitoring.GnssSatelliteMonitor.SatelliteInfo> = emptyList(),
    val gnssAnomalies: List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly> = emptyList(),
    val gnssEvents: List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEvent> = emptyList(),
    val gnssMeasurements: com.flockyou.monitoring.GnssSatelliteMonitor.GnssMeasurementData? = null,
    // Detector health status
    val detectorHealth: Map<String, ScanningService.DetectorHealthStatus> = emptyMap(),
    // Scan statistics
    val scanStats: ScanningService.ScanStatistics = ScanningService.ScanStatistics(),
    // Threading monitor
    val threadingSystemState: com.flockyou.monitoring.ScannerThreadingMonitor.SystemThreadingState? = null,
    val threadingScannerStates: Map<String, com.flockyou.monitoring.ScannerThreadingMonitor.ScannerThreadState> = emptyMap(),
    val threadingAlerts: List<com.flockyou.monitoring.ScannerThreadingMonitor.ThreadingAlert> = emptyList(),
    // UI settings
    val advancedMode: Boolean = false,
    // AI Analysis
    val analyzingDetectionId: String? = null,
    val analysisResult: com.flockyou.data.AiAnalysisResult? = null,
    val isAiAnalysisAvailable: Boolean = false,
    // Flipper Zero
    val flipperConnectionState: FlipperConnectionState = FlipperConnectionState.DISCONNECTED,
    val flipperConnectionType: FlipperClient.ConnectionType = FlipperClient.ConnectionType.NONE,
    val flipperStatus: FlipperStatusResponse? = null,
    val flipperIsScanning: Boolean = false,
    val flipperDetectionCount: Int = 0,
    val flipperWipsAlertCount: Int = 0,
    val flipperLastError: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
    private val repository: DetectionRepository,
    private val ephemeralRepository: EphemeralDetectionRepository,
    private val settingsRepository: DetectionSettingsRepository,
    private val ouiSettingsRepository: OuiSettingsRepository,
    private val networkSettingsRepository: NetworkSettingsRepository,
    private val broadcastSettingsRepository: BroadcastSettingsRepository,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val orbotHelper: OrbotHelper,
    private val torAwareHttpClient: TorAwareHttpClient,
    private val workManager: WorkManager,
    private val detectionAnalyzer: com.flockyou.ai.DetectionAnalyzer,
    private val serviceConnection: ScanningServiceConnection,  // Injected singleton
    private val flipperScannerManager: FlipperScannerManager  // Flipper Zero integration
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Expose the service connection bound state for debugging
    val serviceConnectionBound: StateFlow<Boolean> = serviceConnection.isBound

    // OUI Settings
    val ouiSettings: StateFlow<OuiSettings> = ouiSettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = OuiSettings()
        )

    private val _isOuiUpdating = MutableStateFlow(false)
    val isOuiUpdating: StateFlow<Boolean> = _isOuiUpdating.asStateFlow()

    // Network Settings
    val networkSettings: StateFlow<NetworkSettings> = networkSettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = NetworkSettings()
        )

    // Broadcast Settings
    val broadcastSettings: StateFlow<BroadcastSettings> = broadcastSettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BroadcastSettings()
        )

    // Privacy Settings
    val privacySettings: StateFlow<PrivacySettings> = privacySettingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PrivacySettings()
        )

    private val _isOrbotInstalled = MutableStateFlow(false)
    val isOrbotInstalled: StateFlow<Boolean> = _isOrbotInstalled.asStateFlow()

    private val _isOrbotRunning = MutableStateFlow(false)
    val isOrbotRunning: StateFlow<Boolean> = _isOrbotRunning.asStateFlow()

    private val _torConnectionStatus = MutableStateFlow<TorConnectionStatus?>(null)
    val torConnectionStatus: StateFlow<TorConnectionStatus?> = _torConnectionStatus.asStateFlow()

    private val _isTorTesting = MutableStateFlow(false)
    val isTorTesting: StateFlow<Boolean> = _isTorTesting.asStateFlow()

    init {
        // The serviceConnection is now a singleton injected via Hilt
        // It is automatically bound when created by the provider

        // Consolidated IPC state collection - combines all service connection flows into a single collection
        // This reduces context switching overhead and makes state updates more atomic
        viewModelScope.launch {
            combine(
                serviceConnection.isScanning,
                serviceConnection.scanStatus,
                serviceConnection.bleStatus,
                serviceConnection.wifiStatus,
                serviceConnection.locationStatus,
                serviceConnection.cellularStatus,
                serviceConnection.satelliteStatus,
                serviceConnection.lastDetection
            ) { values ->
                IpcStateUpdate(
                    isScanning = values[0] as Boolean,
                    scanStatus = values[1] as String,
                    bleStatus = values[2] as String,
                    wifiStatus = values[3] as String,
                    locationStatus = values[4] as String,
                    cellularStatus = values[5] as String,
                    satelliteStatus = values[6] as String,
                    lastDetection = values[7] as? Detection
                )
            }.collect { update ->
                _uiState.update {
                    it.copy(
                        isScanning = update.isScanning,
                        scanStatus = ScanningService.ScanStatus.fromIpcString(update.scanStatus),
                        bleStatus = ScanningService.SubsystemStatus.fromIpcString(update.bleStatus),
                        wifiStatus = ScanningService.SubsystemStatus.fromIpcString(update.wifiStatus),
                        locationStatus = ScanningService.SubsystemStatus.fromIpcString(update.locationStatus),
                        cellularStatus = ScanningService.SubsystemStatus.fromIpcString(update.cellularStatus),
                        satelliteStatus = ScanningService.SubsystemStatus.fromIpcString(update.satelliteStatus),
                        lastDetection = update.lastDetection
                    )
                }
            }
        }

        // Consolidated device/network data collection
        viewModelScope.launch {
            combine(
                serviceConnection.seenBleDevices,
                serviceConnection.seenWifiNetworks
            ) { bleDevices, wifiNetworks ->
                Pair(bleDevices, wifiNetworks)
            }.collect { (bleDevices, wifiNetworks) ->
                _uiState.update {
                    it.copy(
                        seenBleDevices = bleDevices,
                        seenWifiNetworks = wifiNetworks
                    )
                }
            }
        }

        // Consolidated cellular data collection
        viewModelScope.launch {
            combine(
                serviceConnection.cellStatus,
                serviceConnection.seenCellTowers,
                serviceConnection.cellularAnomalies,
                serviceConnection.cellularEvents
            ) { cellStatus, towers, anomalies, events ->
                CellularDataUpdate(cellStatus, towers, anomalies, events)
            }.collect { update ->
                _uiState.update {
                    it.copy(
                        cellStatus = update.cellStatus,
                        seenCellTowers = update.seenCellTowers,
                        cellularAnomalies = update.cellularAnomalies,
                        cellularEvents = update.cellularEvents
                    )
                }
            }
        }

        // Consolidated satellite data collection
        viewModelScope.launch {
            combine(
                serviceConnection.satelliteState,
                serviceConnection.satelliteAnomalies,
                serviceConnection.satelliteHistory
            ) { state, anomalies, history ->
                Triple(state, anomalies, history)
            }.collect { (state, anomalies, history) ->
                _uiState.update {
                    it.copy(
                        satelliteState = state,
                        satelliteAnomalies = anomalies,
                        satelliteHistory = history
                    )
                }
            }
        }

        // Consolidated rogue WiFi data collection
        viewModelScope.launch {
            combine(
                serviceConnection.rogueWifiStatus,
                serviceConnection.rogueWifiAnomalies,
                serviceConnection.suspiciousNetworks
            ) { status, anomalies, suspicious ->
                Triple(status, anomalies, suspicious)
            }.collect { (status, anomalies, suspicious) ->
                _uiState.update {
                    it.copy(
                        rogueWifiStatus = status,
                        rogueWifiAnomalies = anomalies,
                        suspiciousNetworks = suspicious
                    )
                }
            }
        }

        // Consolidated RF data collection
        viewModelScope.launch {
            combine(
                serviceConnection.rfStatus,
                serviceConnection.rfAnomalies,
                serviceConnection.detectedDrones
            ) { status, anomalies, drones ->
                Triple(status, anomalies, drones)
            }.collect { (status, anomalies, drones) ->
                _uiState.update {
                    it.copy(
                        rfStatus = status,
                        rfAnomalies = anomalies,
                        detectedDrones = drones
                    )
                }
            }
        }

        // Consolidated ultrasonic data collection
        viewModelScope.launch {
            combine(
                serviceConnection.ultrasonicStatus,
                serviceConnection.ultrasonicAnomalies,
                serviceConnection.ultrasonicBeacons
            ) { status, anomalies, beacons ->
                Triple(status, anomalies, beacons)
            }.collect { (status, anomalies, beacons) ->
                _uiState.update {
                    it.copy(
                        ultrasonicStatus = status,
                        ultrasonicAnomalies = anomalies,
                        ultrasonicBeacons = beacons
                    )
                }
            }
        }

        // Consolidated GNSS satellite data collection
        viewModelScope.launch {
            combine(
                serviceConnection.gnssStatus,
                serviceConnection.gnssSatellites,
                serviceConnection.gnssAnomalies,
                serviceConnection.gnssEvents,
                serviceConnection.gnssMeasurements
            ) { status, satellites, anomalies, events, measurements ->
                GnssDataUpdate(status, satellites, anomalies, events, measurements)
            }.collect { update ->
                _uiState.update {
                    it.copy(
                        gnssStatus = update.status,
                        gnssSatellites = update.satellites,
                        gnssAnomalies = update.anomalies,
                        gnssEvents = update.events,
                        gnssMeasurements = update.measurements
                    )
                }
            }
        }

        // Detector health status collection
        viewModelScope.launch {
            serviceConnection.detectorHealth.collect { health ->
                Log.d("MainViewModel", "Received detector health update: ${health.size} detectors, running=${health.values.count { it.isRunning }}")
                _uiState.update { it.copy(detectorHealth = health) }
            }
        }

        // Scan statistics collection
        viewModelScope.launch {
            serviceConnection.scanStats.collect { stats ->
                Log.d("MainViewModel", "Received scan stats update: totalBleScans=${stats.totalBleScans}, totalWifiScans=${stats.totalWifiScans}")
                _uiState.update { it.copy(scanStats = stats) }
            }
        }

        // Threading monitor data collection
        viewModelScope.launch {
            serviceConnection.threadingSystemState.collect { state ->
                _uiState.update { it.copy(threadingSystemState = state) }
            }
        }

        viewModelScope.launch {
            serviceConnection.threadingScannerStates.collect { states ->
                _uiState.update { it.copy(threadingScannerStates = states) }
            }
        }

        viewModelScope.launch {
            serviceConnection.threadingAlerts.collect { alerts ->
                _uiState.update { it.copy(threadingAlerts = alerts) }
            }
        }

        // Database state collection - consolidated
        viewModelScope.launch {
            combine(
                repository.allDetections,
                repository.totalDetectionCount,
                repository.highThreatCount
            ) { detections, totalCount, highThreatCount ->
                Triple(detections, totalCount, highThreatCount)
            }.collect { (detections, totalCount, highThreatCount) ->
                _uiState.update {
                    it.copy(
                        detections = detections,
                        totalCount = totalCount,
                        highThreatCount = highThreatCount,
                        isLoading = false
                    )
                }
            }
        }

        // Observe detection refresh events from service via IPC (cross-process notification)
        viewModelScope.launch {
            serviceConnection.detectionRefreshEvent.collect {
                refreshDetections()
            }
        }

        // Check Orbot status periodically
        viewModelScope.launch {
            while (true) {
                try {
                    _isOrbotInstalled.value = orbotHelper.isOrbotInstalled()
                    if (_isOrbotInstalled.value) {
                        val settings = networkSettings.value
                        _isOrbotRunning.value = orbotHelper.isOrbotRunning(
                            settings.torProxyHost,
                            settings.torProxyPort
                        )
                    } else {
                        _isOrbotRunning.value = false
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error checking Orbot status", e)
                }
                delay(5000) // Check every 5 seconds
            }
        }

        // Observe advanced mode setting
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(advancedMode = settings.advancedMode) }
            }
        }

        // Observe error log from service via IPC
        viewModelScope.launch {
            serviceConnection.errorLog.collect { errors ->
                _uiState.update { it.copy(recentErrors = errors) }
            }
        }

        // Observe Flipper Zero state
        viewModelScope.launch {
            combine(
                flipperScannerManager.connectionState,
                flipperScannerManager.connectionType,
                flipperScannerManager.flipperStatus,
                flipperScannerManager.isRunning,
                flipperScannerManager.detectionCount,
                flipperScannerManager.wipsAlertCount,
                flipperScannerManager.lastError
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                FlipperStateUpdate(
                    connectionState = values[0] as FlipperConnectionState,
                    connectionType = values[1] as FlipperClient.ConnectionType,
                    status = values[2] as? FlipperStatusResponse,
                    isScanning = values[3] as Boolean,
                    detectionCount = values[4] as Int,
                    wipsAlertCount = values[5] as Int,
                    lastError = values[6] as? String
                )
            }.collect { update ->
                _uiState.update {
                    it.copy(
                        flipperConnectionState = update.connectionState,
                        flipperConnectionType = update.connectionType,
                        flipperStatus = update.status,
                        flipperIsScanning = update.isScanning,
                        flipperDetectionCount = update.detectionCount,
                        flipperWipsAlertCount = update.wipsAlertCount,
                        flipperLastError = update.lastError
                    )
                }
            }
        }

        // Request the current state after all collectors are set up
        // This ensures state updates will be properly received
        serviceConnection.requestState()
    }

    // Data classes for consolidated state updates
    private data class IpcStateUpdate(
        val isScanning: Boolean,
        val scanStatus: String,
        val bleStatus: String,
        val wifiStatus: String,
        val locationStatus: String,
        val cellularStatus: String,
        val satelliteStatus: String,
        val lastDetection: Detection?
    )

    private data class CellularDataUpdate(
        val cellStatus: CellularMonitor.CellStatus?,
        val seenCellTowers: List<CellularMonitor.SeenCellTower>,
        val cellularAnomalies: List<CellularMonitor.CellularAnomaly>,
        val cellularEvents: List<CellularMonitor.CellularEvent>
    )

    private data class GnssDataUpdate(
        val status: com.flockyou.monitoring.GnssSatelliteMonitor.GnssEnvironmentStatus?,
        val satellites: List<com.flockyou.monitoring.GnssSatelliteMonitor.SatelliteInfo>,
        val anomalies: List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly>,
        val events: List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssEvent>,
        val measurements: com.flockyou.monitoring.GnssSatelliteMonitor.GnssMeasurementData?
    )

    private data class FlipperStateUpdate(
        val connectionState: FlipperConnectionState,
        val connectionType: FlipperClient.ConnectionType,
        val status: FlipperStatusResponse?,
        val isScanning: Boolean,
        val detectionCount: Int,
        val wipsAlertCount: Int,
        val lastError: String?
    )

    override fun onCleared() {
        super.onCleared()
        // The serviceConnection is a singleton - do not unbind here
        // It will remain bound for the lifetime of the application
    }

    fun startScanning() {
        // Optimistically update UI state immediately for responsive feedback
        // The actual IPC state sync will confirm or correct this
        _uiState.update { it.copy(isScanning = true, scanStatus = ScanningService.ScanStatus.Starting) }

        // First, start the service (required for foreground service)
        val intent = Intent(application, ScanningService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
        // Also send IPC command (service will receive both, but IPC ensures state sync)
        serviceConnection.startScanning()

        // Request state after a short delay to ensure we get the actual service state
        // This handles the race condition where IPC client isn't registered yet
        viewModelScope.launch {
            delay(500)
            serviceConnection.requestState()
        }
    }

    fun stopScanning() {
        // Optimistically update UI state immediately for responsive feedback
        _uiState.update { it.copy(isScanning = false, scanStatus = ScanningService.ScanStatus.Idle) }

        // Send IPC command to stop scanning
        serviceConnection.stopScanning()
        // Also stop the service
        val intent = Intent(application, ScanningService::class.java)
        application.stopService(intent)
    }

    fun toggleScanning() {
        if (_uiState.value.isScanning) {
            stopScanning()
        } else {
            startScanning()
        }
    }

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index) }
    }
    
    fun setThreatFilter(threatLevel: ThreatLevel?) {
        _uiState.update { it.copy(filterThreatLevel = threatLevel) }
    }

    fun addDeviceTypeFilter(deviceType: DeviceType) {
        _uiState.update { it.copy(filterDeviceTypes = it.filterDeviceTypes + deviceType) }
    }

    fun removeDeviceTypeFilter(deviceType: DeviceType) {
        _uiState.update { it.copy(filterDeviceTypes = it.filterDeviceTypes - deviceType) }
    }

    fun toggleDeviceTypeFilter(deviceType: DeviceType) {
        _uiState.update { state ->
            if (deviceType in state.filterDeviceTypes) {
                state.copy(filterDeviceTypes = state.filterDeviceTypes - deviceType)
            } else {
                state.copy(filterDeviceTypes = state.filterDeviceTypes + deviceType)
            }
        }
    }

    fun setFilterMatchAll(matchAll: Boolean) {
        _uiState.update { it.copy(filterMatchAll = matchAll) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(filterThreatLevel = null, filterDeviceTypes = emptySet()) }
    }

    /**
     * Toggle whether to hide false positive detections.
     */
    fun toggleHideFalsePositives() {
        _uiState.update { it.copy(hideFalsePositives = !it.hideFalsePositives) }
    }

    /**
     * Set false positive filter threshold.
     * Detections with fpScore >= threshold will be hidden when hideFalsePositives is true.
     */
    fun setFpFilterThreshold(threshold: Float) {
        _uiState.update { it.copy(fpFilterThreshold = threshold.coerceIn(0f, 1f)) }
    }

    /**
     * Get count of detections that are filtered as false positives.
     */
    fun getFalsePositiveCount(): Int {
        val state = _uiState.value
        return state.detections.count { detection ->
            val fpScore = detection.fpScore ?: 0f
            fpScore >= state.fpFilterThreshold
        }
    }

    fun deleteDetection(detection: Detection) {
        viewModelScope.launch {
            repository.deleteDetection(detection)
        }
    }
    
    fun clearAllDetections() {
        viewModelScope.launch {
            repository.deleteAllDetections()
        }
    }
    
    fun clearErrors() {
        ScanningService.clearErrors()
    }

    /**
     * Request refresh of all service data via IPC.
     * This triggers the service to send all current state to the UI.
     */
    fun requestRefresh() {
        serviceConnection.requestState()
        viewModelScope.launch {
            refreshDetections()
        }
    }

    /**
     * Manually refresh detections from database.
     * Called when detection refresh events are received to ensure UI stays in sync
     * even if Room's Flow emissions don't trigger properly with SQLCipher.
     */
    private suspend fun refreshDetections() {
        try {
            val detections = repository.getAllDetectionsSnapshot()
            val totalCount = repository.getTotalDetectionCount()
            _uiState.update {
                it.copy(
                    detections = detections,
                    totalCount = totalCount
                )
            }
        } catch (e: Exception) {
            // Log error but don't crash - Room Flow should still work as backup
            android.util.Log.e("MainViewModel", "Error refreshing detections: ${e.message}", e)
        }
    }

    fun setAdvancedMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAdvancedMode(enabled)
        }
    }
    
    fun getFilteredDetections(): List<Detection> {
        val state = _uiState.value
        return state.detections.filter { detection ->
            // FP filter - hide detections flagged as false positives
            val fpMatch = if (state.hideFalsePositives) {
                val fpScore = detection.fpScore ?: 0f
                fpScore < state.fpFilterThreshold
            } else {
                true // Show all when FP filter is disabled
            }

            val threatMatch = state.filterThreatLevel?.let { detection.threatLevel == it } ?: true
            val typeMatch = if (state.filterDeviceTypes.isEmpty()) {
                true
            } else {
                detection.deviceType in state.filterDeviceTypes
            }

            // FP filter is always AND with other filters
            fpMatch && if (state.filterMatchAll) {
                // AND: both conditions must match
                threatMatch && typeMatch
            } else {
                // OR: either condition can match (if both are set)
                if (state.filterThreatLevel != null && state.filterDeviceTypes.isNotEmpty()) {
                    threatMatch || typeMatch
                } else {
                    // If only one filter type is set, just use that
                    threatMatch && typeMatch
                }
            }
        }
    }

    /**
     * Returns RF anomalies filtered based on advanced mode.
     * Low-confidence anomalies (interference, spectrum anomalies, unusual activity)
     * are hidden from non-advanced users to reduce noise.
     */
    fun getFilteredRfAnomalies(): List<RfSignalAnalyzer.RfAnomaly> {
        val state = _uiState.value
        return if (state.advancedMode) {
            state.rfAnomalies
        } else {
            state.rfAnomalies.filter { !it.isAdvancedOnly }
        }
    }

    /**
     * Returns cellular anomalies filtered to exclude those marked as false positives.
     * Matches anomalies to detections by timestamp proximity and type.
     */
    fun getFilteredCellularAnomalies(): List<CellularMonitor.CellularAnomaly> {
        val state = _uiState.value
        if (!state.hideFalsePositives) {
            return state.cellularAnomalies
        }

        // Get cellular detections that are marked as FP
        val fpCellularDetections = state.detections.filter { detection ->
            detection.protocol == com.flockyou.data.model.DetectionProtocol.CELLULAR &&
            (detection.fpScore ?: 0f) >= state.fpFilterThreshold
        }

        if (fpCellularDetections.isEmpty()) {
            return state.cellularAnomalies
        }

        // Filter out anomalies that match FP detections (by timestamp within 5 seconds and similar characteristics)
        return state.cellularAnomalies.filter { anomaly ->
            val matchesFpDetection = fpCellularDetections.any { detection ->
                val timeDiff = kotlin.math.abs(anomaly.timestamp - detection.timestamp)
                val cellIdMatch = detection.manufacturer?.contains(anomaly.cellId?.toString() ?: "") == true
                timeDiff < 5000 && cellIdMatch
            }
            !matchesFpDetection
        }
    }

    /**
     * Returns GNSS anomalies filtered to exclude those marked as false positives.
     */
    fun getFilteredGnssAnomalies(): List<com.flockyou.monitoring.GnssSatelliteMonitor.GnssAnomaly> {
        val state = _uiState.value
        if (!state.hideFalsePositives) {
            return state.gnssAnomalies
        }

        val fpGnssDetections = state.detections.filter { detection ->
            detection.protocol == com.flockyou.data.model.DetectionProtocol.GNSS &&
            (detection.fpScore ?: 0f) >= state.fpFilterThreshold
        }

        if (fpGnssDetections.isEmpty()) {
            return state.gnssAnomalies
        }

        return state.gnssAnomalies.filter { anomaly ->
            val matchesFpDetection = fpGnssDetections.any { detection ->
                val timeDiff = kotlin.math.abs(anomaly.timestamp - detection.timestamp)
                timeDiff < 5000
            }
            !matchesFpDetection
        }
    }

    /**
     * Returns satellite anomalies filtered to exclude those marked as false positives.
     */
    fun getFilteredSatelliteAnomalies(): List<com.flockyou.monitoring.SatelliteMonitor.SatelliteAnomaly> {
        val state = _uiState.value
        if (!state.hideFalsePositives) {
            return state.satelliteAnomalies
        }

        val fpSatelliteDetections = state.detections.filter { detection ->
            detection.protocol == com.flockyou.data.model.DetectionProtocol.SATELLITE &&
            (detection.fpScore ?: 0f) >= state.fpFilterThreshold
        }

        if (fpSatelliteDetections.isEmpty()) {
            return state.satelliteAnomalies
        }

        return state.satelliteAnomalies.filter { anomaly ->
            val matchesFpDetection = fpSatelliteDetections.any { detection ->
                val timeDiff = kotlin.math.abs(anomaly.timestamp - detection.timestamp)
                timeDiff < 5000
            }
            !matchesFpDetection
        }
    }

    /**
     * Returns ultrasonic anomalies filtered to exclude those marked as false positives.
     */
    fun getFilteredUltrasonicAnomalies(): List<UltrasonicDetector.UltrasonicAnomaly> {
        val state = _uiState.value
        if (!state.hideFalsePositives) {
            return state.ultrasonicAnomalies
        }

        val fpUltrasonicDetections = state.detections.filter { detection ->
            detection.protocol == com.flockyou.data.model.DetectionProtocol.AUDIO &&
            (detection.fpScore ?: 0f) >= state.fpFilterThreshold
        }

        if (fpUltrasonicDetections.isEmpty()) {
            return state.ultrasonicAnomalies
        }

        return state.ultrasonicAnomalies.filter { anomaly ->
            val matchesFpDetection = fpUltrasonicDetections.any { detection ->
                val timeDiff = kotlin.math.abs(anomaly.timestamp - detection.timestamp)
                // Match by frequency stored in ssid field
                val freqMatch = detection.ssid?.contains(anomaly.frequency.toString()) == true
                timeDiff < 5000 && freqMatch
            }
            !matchesFpDetection
        }
    }

    /**
     * Returns rogue WiFi anomalies filtered to exclude those marked as false positives.
     */
    fun getFilteredRogueWifiAnomalies(): List<RogueWifiMonitor.WifiAnomaly> {
        val state = _uiState.value
        if (!state.hideFalsePositives) {
            return state.rogueWifiAnomalies
        }

        val fpWifiDetections = state.detections.filter { detection ->
            detection.protocol == com.flockyou.data.model.DetectionProtocol.WIFI &&
            (detection.fpScore ?: 0f) >= state.fpFilterThreshold
        }

        if (fpWifiDetections.isEmpty()) {
            return state.rogueWifiAnomalies
        }

        return state.rogueWifiAnomalies.filter { anomaly ->
            val matchesFpDetection = fpWifiDetections.any { detection ->
                val timeDiff = kotlin.math.abs(anomaly.timestamp - detection.timestamp)
                // Match by BSSID/MAC or SSID
                val bssidMatch = detection.macAddress?.equals(anomaly.bssid, ignoreCase = true) == true
                val ssidMatch = detection.ssid?.equals(anomaly.ssid, ignoreCase = true) == true
                timeDiff < 10000 && (bssidMatch || ssidMatch)
            }
            !matchesFpDetection
        }
    }

    /**
     * Returns RF anomalies filtered based on advanced mode AND false positive status.
     */
    fun getFilteredRfAnomaliesWithFp(): List<RfSignalAnalyzer.RfAnomaly> {
        val state = _uiState.value

        // First apply advanced mode filter
        val advancedFiltered = if (state.advancedMode) {
            state.rfAnomalies
        } else {
            state.rfAnomalies.filter { !it.isAdvancedOnly }
        }

        if (!state.hideFalsePositives) {
            return advancedFiltered
        }

        val fpRfDetections = state.detections.filter { detection ->
            detection.protocol == com.flockyou.data.model.DetectionProtocol.RF &&
            (detection.fpScore ?: 0f) >= state.fpFilterThreshold
        }

        if (fpRfDetections.isEmpty()) {
            return advancedFiltered
        }

        return advancedFiltered.filter { anomaly ->
            val matchesFpDetection = fpRfDetections.any { detection ->
                val timeDiff = kotlin.math.abs(anomaly.timestamp - detection.timestamp)
                timeDiff < 5000
            }
            !matchesFpDetection
        }
    }

    // OUI Database Management
    fun setOuiAutoUpdate(enabled: Boolean) {
        viewModelScope.launch {
            ouiSettingsRepository.setAutoUpdateEnabled(enabled)
            if (enabled) {
                val settings = ouiSettings.value
                OuiUpdateWorker.schedulePeriodicUpdate(
                    application,
                    settings.updateIntervalHours,
                    settings.useWifiOnly
                )
            } else {
                OuiUpdateWorker.cancelAll(application)
            }
        }
    }

    fun setOuiUpdateInterval(hours: Int) {
        viewModelScope.launch {
            ouiSettingsRepository.setUpdateInterval(hours)
            if (ouiSettings.value.autoUpdateEnabled) {
                OuiUpdateWorker.schedulePeriodicUpdate(
                    application,
                    hours,
                    ouiSettings.value.useWifiOnly
                )
            }
        }
    }

    fun setOuiWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            ouiSettingsRepository.setUseWifiOnly(wifiOnly)
            if (ouiSettings.value.autoUpdateEnabled) {
                OuiUpdateWorker.schedulePeriodicUpdate(
                    application,
                    ouiSettings.value.updateIntervalHours,
                    wifiOnly
                )
            }
        }
    }

    fun triggerOuiUpdate() {
        _isOuiUpdating.value = true

        val workId = OuiUpdateWorker.triggerImmediateUpdate(application)

        // Observe work completion
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo?.state?.isFinished == true) {
                    _isOuiUpdating.value = false
                }
            }
        }
    }

    // Network Settings Management
    fun setUseTorProxy(enabled: Boolean) {
        viewModelScope.launch {
            networkSettingsRepository.setUseTorProxy(enabled)
        }
    }

    fun launchOrbot() {
        orbotHelper.launchOrbot()
    }

    fun openOrbotInstallPage() {
        orbotHelper.openOrbotInstallPage()
    }

    fun testTorConnection() {
        viewModelScope.launch {
            _isTorTesting.value = true
            _torConnectionStatus.value = torAwareHttpClient.testTorConnection()
            _isTorTesting.value = false
        }
    }

    fun clearTorStatus() {
        _torConnectionStatus.value = null
    }

    // Broadcast Settings Management
    fun setBroadcastEnabled(enabled: Boolean) {
        viewModelScope.launch {
            broadcastSettingsRepository.setEnabled(enabled)
        }
    }

    fun setBroadcastOnDetection(enabled: Boolean) {
        viewModelScope.launch {
            broadcastSettingsRepository.setBroadcastOnDetection(enabled)
        }
    }

    fun setBroadcastOnCellular(enabled: Boolean) {
        viewModelScope.launch {
            broadcastSettingsRepository.setBroadcastOnCellular(enabled)
        }
    }

    fun setBroadcastOnSatellite(enabled: Boolean) {
        viewModelScope.launch {
            broadcastSettingsRepository.setBroadcastOnSatellite(enabled)
        }
    }

    fun setBroadcastOnWifi(enabled: Boolean) {
        viewModelScope.launch {
            broadcastSettingsRepository.setBroadcastOnWifi(enabled)
        }
    }

    fun setBroadcastOnRf(enabled: Boolean) {
        viewModelScope.launch {
            broadcastSettingsRepository.setBroadcastOnRf(enabled)
        }
    }

    fun setBroadcastOnUltrasonic(enabled: Boolean) {
        viewModelScope.launch {
            broadcastSettingsRepository.setBroadcastOnUltrasonic(enabled)
        }
    }

    fun setBroadcastIncludeLocation(enabled: Boolean) {
        viewModelScope.launch {
            broadcastSettingsRepository.setIncludeLocation(enabled)
        }
    }

    fun setBroadcastMinThreatLevel(level: String) {
        viewModelScope.launch {
            broadcastSettingsRepository.setMinThreatLevel(level)
        }
    }

    // Privacy Settings Management
    fun setEphemeralMode(enabled: Boolean) {
        viewModelScope.launch {
            privacySettingsRepository.setEphemeralModeEnabled(enabled)
            if (enabled) {
                // Clear persistent storage when enabling ephemeral mode
                repository.deleteAllDetections()
            } else {
                // Clear ephemeral storage when disabling
                ephemeralRepository.clearAll()
            }
        }
    }

    fun setRetentionPeriod(period: RetentionPeriod) {
        viewModelScope.launch {
            privacySettingsRepository.setRetentionPeriod(period)
            // Update the data retention worker schedule
            DataRetentionWorker.schedulePeriodicCleanupHours(application, period.hours)
        }
    }

    fun setStoreLocationWithDetections(enabled: Boolean) {
        viewModelScope.launch {
            privacySettingsRepository.setStoreLocationWithDetections(enabled)
        }
    }

    fun setAutoPurgeOnScreenLock(enabled: Boolean) {
        viewModelScope.launch {
            privacySettingsRepository.setAutoPurgeOnScreenLock(enabled)
        }
    }

    fun setQuickWipeRequiresConfirmation(required: Boolean) {
        viewModelScope.launch {
            privacySettingsRepository.setQuickWipeRequiresConfirmation(required)
        }
    }

    /**
     * Perform a quick wipe - delete all detection data.
     */
    fun performQuickWipe() {
        viewModelScope.launch {
            // Clear persistent database
            repository.deleteAllDetections()

            // Clear ephemeral storage
            ephemeralRepository.clearAll()

            // Clear service runtime data via IPC
            serviceConnection.clearSeenDevices()
            serviceConnection.clearCellularHistory()
            serviceConnection.clearSatelliteHistory()
            serviceConnection.clearErrors()
            serviceConnection.clearLearnedSignatures()
            serviceConnection.resetDetectionCount()
        }
    }

    /**
     * Clear seen devices via IPC.
     */
    fun clearSeenDevices() {
        serviceConnection.clearSeenDevices()
    }

    /**
     * Clear cellular history via IPC.
     */
    fun clearCellularHistory() {
        serviceConnection.clearCellularHistory()
    }

    /**
     * Clear satellite history via IPC.
     */
    fun clearSatelliteHistory() {
        serviceConnection.clearSatelliteHistory()
    }

    // ========== AI Analysis ==========

    // Track detection IDs that have been prioritized for enrichment
    private val _prioritizedEnrichmentIds = MutableStateFlow<Set<String>>(emptySet())
    val prioritizedEnrichmentIds: StateFlow<Set<String>> = _prioritizedEnrichmentIds.asStateFlow()

    /**
     * Prioritize a detection for immediate LLM enrichment.
     * This triggers the BackgroundAnalysisWorker to analyze the specific detection immediately.
     */
    fun prioritizeEnrichment(detection: Detection) {
        viewModelScope.launch {
            Log.d("MainViewModel", "Prioritizing enrichment for detection: ${detection.id}")

            // Add to the set of prioritized IDs
            _prioritizedEnrichmentIds.update { it + detection.id }

            // Trigger the background analysis worker for this specific detection
            com.flockyou.worker.BackgroundAnalysisWorker.triggerForDetections(
                application,
                listOf(detection.id)
            )

            // Remove from prioritized set after a delay (the worker will process it)
            delay(5000)
            _prioritizedEnrichmentIds.update { it - detection.id }
        }
    }

    /**
     * Check if a detection is currently queued for prioritized enrichment.
     */
    fun isEnrichmentPending(detectionId: String): Boolean {
        return _prioritizedEnrichmentIds.value.contains(detectionId)
    }

    /**
     * Analyze a detection using the on-device AI.
     */
    fun analyzeDetection(detection: Detection) {
        viewModelScope.launch {
            Log.d("MainViewModel", "Starting AI analysis for detection: ${detection.id} (${detection.deviceType})")
            _uiState.update { it.copy(analyzingDetectionId = detection.id, analysisResult = null) }

            try {
                val result = detectionAnalyzer.analyzeDetection(detection)
                Log.d("MainViewModel", "AI analysis complete: success=${result.success}, model=${result.modelUsed}, " +
                    "error=${result.error}, analysisLength=${result.analysis?.length ?: 0}")
                _uiState.update { it.copy(analyzingDetectionId = null, analysisResult = result) }
            } catch (e: Exception) {
                Log.e("MainViewModel", "AI analysis failed with exception", e)
                _uiState.update {
                    it.copy(
                        analyzingDetectionId = null,
                        analysisResult = com.flockyou.data.AiAnalysisResult(
                            success = false,
                            error = e.message ?: "Analysis failed"
                        )
                    )
                }
            }
        }
    }

    /**
     * Clear the analysis result.
     */
    fun clearAnalysisResult() {
        _uiState.update { it.copy(analysisResult = null) }
    }

    /**
     * Check if AI analysis is available (always true since rule-based fallback exists).
     */
    fun isAiAnalysisAvailable(): Boolean {
        // Rule-based analysis is always available as a fallback
        return true
    }

    // ========== Debug Export ==========

    /**
     * Export all detection debug information for algorithm tuning.
     * Returns a formatted string containing all detection-related state.
     */
    fun exportAllDebugInfo(): String {
        val state = _uiState.value
        val sb = StringBuilder()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        val now = System.currentTimeMillis()

        sb.appendLine("=== FLOCK-YOU DETECTION DEBUG EXPORT ===")
        sb.appendLine("Export Time: ${dateFormat.format(java.util.Date(now))}")
        sb.appendLine("App Version: ${getAppVersion()}")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("Advanced Mode: ${state.advancedMode}")
        sb.appendLine("Scanning Active: ${state.isScanning}")
        sb.appendLine("Scan Status: ${state.scanStatus}")
        sb.appendLine()

        // Subsystem Status
        sb.appendLine("=== SUBSYSTEM STATUS ===")
        sb.appendLine("BLE: ${state.bleStatus}")
        sb.appendLine("WiFi: ${state.wifiStatus}")
        sb.appendLine("Location: ${state.locationStatus}")
        sb.appendLine("Cellular: ${state.cellularStatus}")
        sb.appendLine("Satellite: ${state.satelliteStatus}")
        sb.appendLine()

        // Detection Summary
        sb.appendLine("=== DETECTION SUMMARY ===")
        sb.appendLine("Total Detections: ${state.totalCount}")
        sb.appendLine("High Threat Count: ${state.highThreatCount}")
        sb.appendLine("Last Detection: ${state.lastDetection?.let { "${it.deviceType} - ${it.deviceName}" } ?: "none"}")
        sb.appendLine()

        // Scan Statistics
        sb.appendLine("=== SCAN STATISTICS ===")
        val stats = state.scanStats
        sb.appendLine("Total BLE Scans: ${stats.totalBleScans}")
        sb.appendLine("Total WiFi Scans: ${stats.totalWifiScans}")
        sb.appendLine("BLE Devices Seen: ${stats.bleDevicesSeen}")
        sb.appendLine("WiFi Networks Seen: ${stats.wifiNetworksSeen}")
        sb.appendLine("Detections Created: ${stats.detectionsCreated}")
        sb.appendLine("Last BLE Scan: ${stats.lastBleSuccessTime?.let { dateFormat.format(java.util.Date(it)) } ?: "never"}")
        sb.appendLine("Last WiFi Scan: ${stats.lastWifiSuccessTime?.let { dateFormat.format(java.util.Date(it)) } ?: "never"}")
        sb.appendLine()

        // Seen BLE Devices
        sb.appendLine("=== SEEN BLE DEVICES (${state.seenBleDevices.size}) ===")
        if (state.seenBleDevices.isEmpty()) {
            sb.appendLine("No BLE devices seen")
        } else {
            state.seenBleDevices.sortedByDescending { it.lastSeen }.take(50).forEach { device ->
                sb.appendLine("${device.name ?: "Unknown"} [${device.id}]")
                sb.appendLine("  RSSI: ${device.rssi}dBm, Seen: ${device.seenCount}x")
                sb.appendLine("  Last: ${dateFormat.format(java.util.Date(device.lastSeen))}")
            }
            if (state.seenBleDevices.size > 50) {
                sb.appendLine("... and ${state.seenBleDevices.size - 50} more")
            }
        }
        sb.appendLine()

        // Seen WiFi Networks
        sb.appendLine("=== SEEN WIFI NETWORKS (${state.seenWifiNetworks.size}) ===")
        if (state.seenWifiNetworks.isEmpty()) {
            sb.appendLine("No WiFi networks seen")
        } else {
            state.seenWifiNetworks.sortedByDescending { it.lastSeen }.take(50).forEach { network ->
                sb.appendLine("${network.name ?: "Hidden"} [${network.id}]")
                sb.appendLine("  RSSI: ${network.rssi}dBm, Seen: ${network.seenCount}x")
                sb.appendLine("  Last: ${dateFormat.format(java.util.Date(network.lastSeen))}")
            }
            if (state.seenWifiNetworks.size > 50) {
                sb.appendLine("... and ${state.seenWifiNetworks.size - 50} more")
            }
        }
        sb.appendLine()

        // Cellular Status
        sb.appendLine("=== CELLULAR STATUS ===")
        val cellStatus = state.cellStatus
        if (cellStatus != null) {
            sb.appendLine("Network Type: ${cellStatus.networkType}")
            sb.appendLine("Operator: ${cellStatus.operator}")
            sb.appendLine("Signal Strength: ${cellStatus.signalStrength}dBm")
            sb.appendLine("Cell ID: ${cellStatus.cellId}")
            sb.appendLine("LAC: ${cellStatus.lac}")
            sb.appendLine("MCC: ${cellStatus.mcc}, MNC: ${cellStatus.mnc}")
        } else {
            sb.appendLine("No cellular status available")
        }
        sb.appendLine()

        // Seen Cell Towers
        sb.appendLine("=== SEEN CELL TOWERS (${state.seenCellTowers.size}) ===")
        if (state.seenCellTowers.isEmpty()) {
            sb.appendLine("No cell towers seen")
        } else {
            state.seenCellTowers.sortedByDescending { it.lastSeen }.forEach { tower ->
                sb.appendLine("Cell ${tower.cellId} (${tower.networkType})")
                sb.appendLine("  Signal: ${tower.lastSignal}dBm, Seen: ${tower.seenCount}x")
                sb.appendLine("  LAC: ${tower.lac}, MCC: ${tower.mcc}, MNC: ${tower.mnc}")
                sb.appendLine("  Last: ${dateFormat.format(java.util.Date(tower.lastSeen))}")
            }
        }
        sb.appendLine()

        // Cellular Anomalies
        sb.appendLine("=== CELLULAR ANOMALIES (${state.cellularAnomalies.size}) ===")
        if (state.cellularAnomalies.isEmpty()) {
            sb.appendLine("No cellular anomalies detected")
        } else {
            state.cellularAnomalies.sortedByDescending { it.timestamp }.forEach { anomaly ->
                sb.appendLine("--- ${anomaly.type} ---")
                sb.appendLine("  Time: ${dateFormat.format(java.util.Date(anomaly.timestamp))}")
                sb.appendLine("  Description: ${anomaly.description}")
                sb.appendLine("  Severity: ${anomaly.severity}")
            }
        }
        sb.appendLine()

        // Cellular Events
        sb.appendLine("=== CELLULAR EVENTS (last 20) ===")
        if (state.cellularEvents.isEmpty()) {
            sb.appendLine("No cellular events")
        } else {
            state.cellularEvents.take(20).forEach { event ->
                sb.appendLine("${dateFormat.format(java.util.Date(event.timestamp))} [${event.type}] ${event.description}")
            }
        }
        sb.appendLine()

        // Satellite Status
        sb.appendLine("=== SATELLITE CONNECTION STATUS ===")
        val satState = state.satelliteState
        if (satState != null) {
            sb.appendLine("Connected: ${satState.isConnected}")
            sb.appendLine("Connection Type: ${satState.connectionType}")
            sb.appendLine("Provider: ${satState.provider}")
            sb.appendLine("Signal Strength: ${satState.signalStrength ?: "unknown"}")
        } else {
            sb.appendLine("No satellite status available")
        }
        sb.appendLine()

        // Satellite Anomalies
        sb.appendLine("=== SATELLITE ANOMALIES (${state.satelliteAnomalies.size}) ===")
        if (state.satelliteAnomalies.isEmpty()) {
            sb.appendLine("No satellite anomalies detected")
        } else {
            state.satelliteAnomalies.sortedByDescending { it.timestamp }.forEach { anomaly ->
                sb.appendLine("--- ${anomaly.type} ---")
                sb.appendLine("  Time: ${dateFormat.format(java.util.Date(anomaly.timestamp))}")
                sb.appendLine("  Description: ${anomaly.description}")
            }
        }
        sb.appendLine()

        // GNSS Status
        sb.appendLine("=== GNSS STATUS ===")
        val gnssStatus = state.gnssStatus
        if (gnssStatus != null) {
            sb.appendLine("Total Satellites: ${gnssStatus.totalSatellites}")
            sb.appendLine("Satellites Used In Fix: ${gnssStatus.satellitesUsedInFix}")
            sb.appendLine("Has Fix: ${gnssStatus.hasFix}")
            sb.appendLine("Fix Accuracy: ${gnssStatus.fixAccuracyMeters ?: "unknown"}m")
            sb.appendLine("Spoofing Risk Level: ${gnssStatus.spoofingRiskLevel}")
        } else {
            sb.appendLine("No GNSS status available")
        }
        sb.appendLine()

        // GNSS Satellites
        sb.appendLine("=== GNSS SATELLITES (${state.gnssSatellites.size}) ===")
        if (state.gnssSatellites.isEmpty()) {
            sb.appendLine("No GNSS satellites visible")
        } else {
            state.gnssSatellites.sortedByDescending { it.cn0DbHz }.forEach { sat ->
                sb.appendLine("${sat.constellation} PRN ${sat.svid}: ${sat.cn0DbHz}dB-Hz, Elev=${sat.elevationDegrees}, Az=${sat.azimuthDegrees}, Used=${sat.usedInFix}")
            }
        }
        sb.appendLine()

        // GNSS Anomalies
        sb.appendLine("=== GNSS ANOMALIES (${state.gnssAnomalies.size}) ===")
        if (state.gnssAnomalies.isEmpty()) {
            sb.appendLine("No GNSS anomalies detected")
        } else {
            state.gnssAnomalies.sortedByDescending { it.timestamp }.forEach { anomaly ->
                sb.appendLine("--- ${anomaly.type} ---")
                sb.appendLine("  Time: ${dateFormat.format(java.util.Date(anomaly.timestamp))}")
                sb.appendLine("  Description: ${anomaly.description}")
                sb.appendLine("  Severity: ${anomaly.severity}")
            }
        }
        sb.appendLine()

        // GNSS Measurements
        sb.appendLine("=== GNSS MEASUREMENTS ===")
        val gnssMeas = state.gnssMeasurements
        if (gnssMeas != null) {
            sb.appendLine("Clock Bias: ${gnssMeas.clockBiasNs ?: "unknown"}ns")
            sb.appendLine("Clock Drift: ${gnssMeas.clockDriftNsPerSec ?: "unknown"}ns/s")
            sb.appendLine("Measurement Count: ${gnssMeas.measurementCount}")
        } else {
            sb.appendLine("No GNSS measurements available")
        }
        sb.appendLine()

        // Ultrasonic Status
        sb.appendLine("=== ULTRASONIC STATUS ===")
        val ultraStatus = state.ultrasonicStatus
        if (ultraStatus != null) {
            sb.appendLine("Scanning Active: ${ultraStatus.isScanning}")
            sb.appendLine("Noise Floor: ${ultraStatus.noiseFloorDb}dB")
            sb.appendLine("Ultrasonic Activity Detected: ${ultraStatus.ultrasonicActivityDetected}")
            sb.appendLine("Active Beacon Count: ${ultraStatus.activeBeaconCount}")
        } else {
            sb.appendLine("No ultrasonic status available")
        }
        sb.appendLine()

        // Ultrasonic Anomalies
        sb.appendLine("=== ULTRASONIC ANOMALIES (${state.ultrasonicAnomalies.size}) ===")
        if (state.ultrasonicAnomalies.isEmpty()) {
            sb.appendLine("No ultrasonic anomalies detected")
        } else {
            state.ultrasonicAnomalies.sortedByDescending { it.timestamp }.forEach { anomaly ->
                sb.appendLine("--- ${anomaly.type} ---")
                sb.appendLine("  Time: ${dateFormat.format(java.util.Date(anomaly.timestamp))}")
                sb.appendLine("  Description: ${anomaly.description}")
                sb.appendLine("  Frequency: ${anomaly.frequency ?: "unknown"}Hz")
            }
        }
        sb.appendLine()

        // Ultrasonic Beacons
        sb.appendLine("=== ULTRASONIC BEACONS (${state.ultrasonicBeacons.size}) ===")
        if (state.ultrasonicBeacons.isEmpty()) {
            sb.appendLine("No ultrasonic beacons detected")
        } else {
            state.ultrasonicBeacons.sortedByDescending { it.lastDetected }.forEach { beacon ->
                sb.appendLine("Beacon at ${beacon.frequency}Hz")
                sb.appendLine("  Amplitude: ${beacon.peakAmplitudeDb}dB, Source: ${beacon.possibleSource}")
                sb.appendLine("  First: ${dateFormat.format(java.util.Date(beacon.firstDetected))}")
                sb.appendLine("  Last: ${dateFormat.format(java.util.Date(beacon.lastDetected))}")
            }
        }
        sb.appendLine()

        // RF Status
        sb.appendLine("=== RF STATUS ===")
        val rfStatus = state.rfStatus
        if (rfStatus != null) {
            sb.appendLine("Total Networks: ${rfStatus.totalNetworks}")
            sb.appendLine("Band Distribution: 2.4GHz=${rfStatus.band24GHz}, 5GHz=${rfStatus.band5GHz}, 6GHz=${rfStatus.band6GHz}")
            sb.appendLine("Average Signal: ${rfStatus.averageSignalStrength}dBm")
            sb.appendLine("Noise Level: ${rfStatus.noiseLevel.displayName}")
            sb.appendLine("Channel Congestion: ${rfStatus.channelCongestion.displayName}")
            sb.appendLine("Environment Risk: ${rfStatus.environmentRisk.displayName}")
            sb.appendLine("Jammer Suspected: ${rfStatus.jammerSuspected}")
            sb.appendLine("Drones Detected: ${rfStatus.dronesDetected}")
            sb.appendLine("Surveillance Cameras: ${rfStatus.surveillanceCameras}")
            sb.appendLine("Last Scan: ${dateFormat.format(java.util.Date(rfStatus.lastScanTime))}")
        } else {
            sb.appendLine("No RF status available")
        }
        sb.appendLine()

        // RF Anomalies
        sb.appendLine("=== RF ANOMALIES (${state.rfAnomalies.size}) ===")
        if (state.rfAnomalies.isEmpty()) {
            sb.appendLine("No RF anomalies detected")
        } else {
            state.rfAnomalies.sortedByDescending { it.timestamp }.forEach { anomaly ->
                sb.appendLine("--- ${anomaly.type.emoji} ${anomaly.type.displayName} ---")
                sb.appendLine("  Time: ${dateFormat.format(java.util.Date(anomaly.timestamp))}")
                sb.appendLine("  Severity: ${anomaly.severity}, Confidence: ${anomaly.confidence.displayName}")
                sb.appendLine("  Advanced Only: ${anomaly.isAdvancedOnly}")
                sb.appendLine("  Description: ${anomaly.description}")
                sb.appendLine("  Technical Details:")
                anomaly.technicalDetails.lines().forEach { line ->
                    sb.appendLine("    $line")
                }
                if (anomaly.contributingFactors.isNotEmpty()) {
                    sb.appendLine("  Contributing Factors:")
                    anomaly.contributingFactors.forEach { factor ->
                        sb.appendLine("    - $factor")
                    }
                }
            }
        }
        sb.appendLine()

        // Detected Drones
        sb.appendLine("=== DETECTED DRONES (${state.detectedDrones.size}) ===")
        if (state.detectedDrones.isEmpty()) {
            sb.appendLine("No drones detected")
        } else {
            state.detectedDrones.sortedByDescending { it.lastSeen }.forEach { drone ->
                sb.appendLine("--- ${drone.manufacturer} Drone ---")
                sb.appendLine("  BSSID: ${drone.bssid}, SSID: ${drone.ssid}")
                sb.appendLine("  RSSI: ${drone.rssi}dBm, Distance: ${drone.estimatedDistance}")
                sb.appendLine("  Seen: ${drone.seenCount}x")
                sb.appendLine("  First: ${dateFormat.format(java.util.Date(drone.firstSeen))}")
                sb.appendLine("  Last: ${dateFormat.format(java.util.Date(drone.lastSeen))}")
            }
        }
        sb.appendLine()

        // Rogue WiFi Status
        sb.appendLine("=== ROGUE WIFI STATUS ===")
        val rogueStatus = state.rogueWifiStatus
        if (rogueStatus != null) {
            sb.appendLine("Total Networks: ${rogueStatus.totalNetworks}")
            sb.appendLine("Open Networks: ${rogueStatus.openNetworks}")
            sb.appendLine("Suspicious: ${rogueStatus.suspiciousNetworks}")
            sb.appendLine("Hidden Networks: ${rogueStatus.hiddenNetworks}")
            sb.appendLine("Potential Evil Twins: ${rogueStatus.potentialEvilTwins}")
        } else {
            sb.appendLine("No rogue WiFi status available")
        }
        sb.appendLine()

        // Suspicious Networks
        sb.appendLine("=== SUSPICIOUS NETWORKS (${state.suspiciousNetworks.size}) ===")
        if (state.suspiciousNetworks.isEmpty()) {
            sb.appendLine("No suspicious networks")
        } else {
            state.suspiciousNetworks.forEach { network ->
                sb.appendLine("${network.ssid} [${network.bssid}]")
                sb.appendLine("  Reason: ${network.reason}")
                sb.appendLine("  Threat Level: ${network.threatLevel}, RSSI: ${network.rssi}dBm")
            }
        }
        sb.appendLine()

        // Rogue WiFi Anomalies
        sb.appendLine("=== ROGUE WIFI ANOMALIES (${state.rogueWifiAnomalies.size}) ===")
        if (state.rogueWifiAnomalies.isEmpty()) {
            sb.appendLine("No rogue WiFi anomalies")
        } else {
            state.rogueWifiAnomalies.sortedByDescending { it.timestamp }.forEach { anomaly ->
                sb.appendLine("--- ${anomaly.type} ---")
                sb.appendLine("  Time: ${dateFormat.format(java.util.Date(anomaly.timestamp))}")
                sb.appendLine("  Description: ${anomaly.description}")
                sb.appendLine("  Severity: ${anomaly.severity}")
            }
        }
        sb.appendLine()

        // Detector Health Status
        sb.appendLine("=== DETECTOR HEALTH STATUS ===")
        if (state.detectorHealth.isEmpty()) {
            sb.appendLine("No detector health data")
        } else {
            state.detectorHealth.forEach { (name, health) ->
                sb.appendLine("$name:")
                sb.appendLine("  Running: ${health.isRunning}, Healthy: ${health.isHealthy}")
                sb.appendLine("  Last Success: ${health.lastSuccessfulScan?.let { dateFormat.format(java.util.Date(it)) } ?: "never"}")
                sb.appendLine("  Last Error: ${health.lastError ?: "none"}")
                sb.appendLine("  Consecutive Failures: ${health.consecutiveFailures}")
            }
        }
        sb.appendLine()

        // Recent Errors
        sb.appendLine("=== RECENT ERRORS (${state.recentErrors.size}) ===")
        if (state.recentErrors.isEmpty()) {
            sb.appendLine("No recent errors")
        } else {
            state.recentErrors.forEach { error ->
                sb.appendLine("${dateFormat.format(java.util.Date(error.timestamp))} [${error.subsystem}]: ${error.message}")
            }
        }
        sb.appendLine()

        // Detection Type Breakdown - comprehensive summary of all detections
        sb.appendLine("=== DETECTION TYPE BREAKDOWN (${state.detections.size} total) ===")
        if (state.detections.isEmpty()) {
            sb.appendLine("No detections recorded")
        } else {
            // Group by device type
            val byDeviceType = state.detections.groupBy { it.deviceType }
            sb.appendLine("By Device Type:")
            byDeviceType.entries.sortedByDescending { it.value.size }.forEach { (type, detections) ->
                sb.appendLine("  $type: ${detections.size}")
            }
            sb.appendLine()

            // Group by protocol
            val byProtocol = state.detections.groupBy { it.protocol }
            sb.appendLine("By Protocol:")
            byProtocol.entries.sortedByDescending { it.value.size }.forEach { (protocol, detections) ->
                sb.appendLine("  $protocol: ${detections.size}")
            }
            sb.appendLine()

            // Group by detection method
            val byMethod = state.detections.groupBy { it.detectionMethod }
            sb.appendLine("By Detection Method:")
            byMethod.entries.sortedByDescending { it.value.size }.forEach { (method, detections) ->
                sb.appendLine("  $method: ${detections.size}")
            }
            sb.appendLine()

            // Group by threat level
            val byThreatLevel = state.detections.groupBy { it.threatLevel }
            sb.appendLine("By Threat Level:")
            byThreatLevel.entries.sortedByDescending { it.key.ordinal }.forEach { (level, detections) ->
                sb.appendLine("  $level: ${detections.size}")
            }
            sb.appendLine()

            // Time distribution
            val oldestDetection = state.detections.minByOrNull { it.timestamp }
            val newestDetection = state.detections.maxByOrNull { it.timestamp }
            if (oldestDetection != null && newestDetection != null) {
                sb.appendLine("Time Range:")
                sb.appendLine("  Oldest: ${dateFormat.format(java.util.Date(oldestDetection.timestamp))}")
                sb.appendLine("  Newest: ${dateFormat.format(java.util.Date(newestDetection.timestamp))}")
                val durationMs = newestDetection.timestamp - oldestDetection.timestamp
                val durationMin = durationMs / 60_000
                sb.appendLine("  Span: ${durationMin} minutes")
                if (durationMin > 0) {
                    sb.appendLine("  Rate: ${String.format("%.1f", state.detections.size.toFloat() / durationMin)} detections/min")
                }
            }
        }
        sb.appendLine()

        // All Detections - full list for debugging
        sb.appendLine("=== ALL DETECTIONS (${state.detections.size}) ===")
        if (state.detections.isEmpty()) {
            sb.appendLine("No detections")
        } else {
            state.detections.sortedByDescending { it.timestamp }.forEach { detection ->
                sb.appendLine("--- ${detection.deviceType} ---")
                sb.appendLine("  Name: ${detection.deviceName}")
                sb.appendLine("  Time: ${dateFormat.format(java.util.Date(detection.timestamp))}")
                sb.appendLine("  Protocol: ${detection.protocol}, Method: ${detection.detectionMethod}")
                sb.appendLine("  Threat: ${detection.threatLevel} (score: ${detection.threatScore})")
                sb.appendLine("  MAC: ${detection.macAddress ?: "unknown"}")
                sb.appendLine("  RSSI: ${detection.rssi}dBm")
                detection.matchedPatterns?.let { sb.appendLine("  Patterns: $it") }
                detection.manufacturer?.let { sb.appendLine("  Manufacturer: $it") }
                detection.ssid?.let { sb.appendLine("  SSID: $it") }
                if (detection.latitude != null && detection.longitude != null) {
                    sb.appendLine("  Location: ${detection.latitude}, ${detection.longitude}")
                }
            }
        }
        sb.appendLine()

        // Flipper Zero Status
        sb.appendLine("=== FLIPPER ZERO STATUS ===")
        sb.appendLine("Connection State: ${state.flipperConnectionState}")
        sb.appendLine("Connection Type: ${state.flipperConnectionType}")
        sb.appendLine("Scanning: ${state.flipperIsScanning}")
        sb.appendLine("Detection Count: ${state.flipperDetectionCount}")
        sb.appendLine("WIPS Alerts: ${state.flipperWipsAlertCount}")
        state.flipperLastError?.let { sb.appendLine("Last Error: $it") }
        state.flipperStatus?.let { status ->
            sb.appendLine("Flipper Status:")
            sb.appendLine("  Battery: ${status.batteryPercent}%")
            sb.appendLine("  Uptime: ${status.uptimeSeconds}s")
            sb.appendLine("  WiFi Board Connected: ${status.wifiBoardConnected}")
        }
        sb.appendLine()

        sb.appendLine("=== END DEBUG EXPORT ===")
        sb.appendLine()
        sb.appendLine("Please share this export to help improve detection accuracy.")

        return sb.toString()
    }

    /**
     * Export RF detection debug information for algorithm tuning.
     * Returns a formatted string containing all RF-related state.
     */
    fun exportRfDebugInfo(): String {
        val state = _uiState.value
        val sb = StringBuilder()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        val now = System.currentTimeMillis()

        sb.appendLine("=== FLOCK-YOU RF DETECTION DEBUG EXPORT ===")
        sb.appendLine("Export Time: ${dateFormat.format(java.util.Date(now))}")
        sb.appendLine("App Version: ${getAppVersion()}")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("Advanced Mode: ${state.advancedMode}")
        sb.appendLine("Scanning Active: ${state.isScanning}")
        sb.appendLine()

        // Current RF Status
        sb.appendLine("=== CURRENT RF STATUS ===")
        val rfStatus = state.rfStatus
        if (rfStatus != null) {
            sb.appendLine("Total Networks: ${rfStatus.totalNetworks}")
            sb.appendLine("Band Distribution: 2.4GHz=${rfStatus.band24GHz}, 5GHz=${rfStatus.band5GHz}, 6GHz=${rfStatus.band6GHz}")
            sb.appendLine("Average Signal Strength: ${rfStatus.averageSignalStrength}dBm")
            sb.appendLine("Noise Level: ${rfStatus.noiseLevel.displayName} ${rfStatus.noiseLevel.emoji}")
            sb.appendLine("Channel Congestion: ${rfStatus.channelCongestion.displayName}")
            sb.appendLine("Environment Risk: ${rfStatus.environmentRisk.displayName} ${rfStatus.environmentRisk.emoji}")
            sb.appendLine("Jammer Suspected: ${rfStatus.jammerSuspected}")
            sb.appendLine("Drones Detected: ${rfStatus.dronesDetected}")
            sb.appendLine("Surveillance Cameras: ${rfStatus.surveillanceCameras}")
            sb.appendLine("Last Scan: ${dateFormat.format(java.util.Date(rfStatus.lastScanTime))}")
        } else {
            sb.appendLine("No RF status available (scanning may not have started)")
        }
        sb.appendLine()

        // All RF Anomalies (including advanced-only)
        sb.appendLine("=== ALL RF ANOMALIES (${state.rfAnomalies.size} total) ===")
        if (state.rfAnomalies.isEmpty()) {
            sb.appendLine("No anomalies detected")
        } else {
            state.rfAnomalies.sortedByDescending { it.timestamp }.forEach { anomaly ->
                sb.appendLine("--- ${anomaly.type.emoji} ${anomaly.type.displayName} ---")
                sb.appendLine("  ID: ${anomaly.id}")
                sb.appendLine("  Time: ${dateFormat.format(java.util.Date(anomaly.timestamp))}")
                sb.appendLine("  Display Name: ${anomaly.displayName}")
                sb.appendLine("  Severity: ${anomaly.severity}")
                sb.appendLine("  Confidence: ${anomaly.confidence.displayName}")
                sb.appendLine("  Advanced Only: ${anomaly.isAdvancedOnly}")
                sb.appendLine("  Description: ${anomaly.description}")
                sb.appendLine("  Technical Details:")
                anomaly.technicalDetails.lines().forEach { line ->
                    sb.appendLine("    $line")
                }
                if (anomaly.contributingFactors.isNotEmpty()) {
                    sb.appendLine("  Contributing Factors:")
                    anomaly.contributingFactors.forEach { factor ->
                        sb.appendLine("    - $factor")
                    }
                }
                if (anomaly.latitude != null && anomaly.longitude != null) {
                    sb.appendLine("  Location: ${anomaly.latitude}, ${anomaly.longitude}")
                }
                sb.appendLine()
            }
        }

        // Detected Drones
        sb.appendLine("=== DETECTED DRONES (${state.detectedDrones.size}) ===")
        if (state.detectedDrones.isEmpty()) {
            sb.appendLine("No drones detected")
        } else {
            state.detectedDrones.sortedByDescending { it.lastSeen }.forEach { drone ->
                sb.appendLine("--- ${drone.manufacturer} Drone ---")
                sb.appendLine("  BSSID: ${drone.bssid}")
                sb.appendLine("  SSID: ${drone.ssid}")
                sb.appendLine("  First Seen: ${dateFormat.format(java.util.Date(drone.firstSeen))}")
                sb.appendLine("  Last Seen: ${dateFormat.format(java.util.Date(drone.lastSeen))}")
                sb.appendLine("  Seen Count: ${drone.seenCount}")
                sb.appendLine("  RSSI: ${drone.rssi}dBm")
                sb.appendLine("  Estimated Distance: ${drone.estimatedDistance}")
                if (drone.latitude != null && drone.longitude != null) {
                    sb.appendLine("  Location: ${drone.latitude}, ${drone.longitude}")
                }
                sb.appendLine()
            }
        }

        // Rogue WiFi Status (related to RF analysis)
        sb.appendLine("=== ROGUE WIFI STATUS ===")
        val rogueStatus = state.rogueWifiStatus
        if (rogueStatus != null) {
            sb.appendLine("Total Networks: ${rogueStatus.totalNetworks}")
            sb.appendLine("Open Networks: ${rogueStatus.openNetworks}")
            sb.appendLine("Suspicious Networks: ${rogueStatus.suspiciousNetworks}")
            sb.appendLine("Hidden Networks: ${rogueStatus.hiddenNetworks}")
            sb.appendLine("Potential Evil Twins: ${rogueStatus.potentialEvilTwins}")
        } else {
            sb.appendLine("No rogue WiFi status available")
        }
        sb.appendLine()

        // Suspicious Networks
        sb.appendLine("=== SUSPICIOUS NETWORKS (${state.suspiciousNetworks.size}) ===")
        if (state.suspiciousNetworks.isEmpty()) {
            sb.appendLine("No suspicious networks detected")
        } else {
            state.suspiciousNetworks.forEach { network ->
                sb.appendLine("--- ${network.ssid} ---")
                sb.appendLine("  BSSID: ${network.bssid}")
                sb.appendLine("  Reason: ${network.reason}")
                sb.appendLine("  Threat Level: ${network.threatLevel}")
                sb.appendLine("  RSSI: ${network.rssi}dBm")
                sb.appendLine()
            }
        }

        // Scan Statistics
        sb.appendLine("=== SCAN STATISTICS ===")
        val stats = state.scanStats
        sb.appendLine("Total BLE Scans: ${stats.totalBleScans}")
        sb.appendLine("Total WiFi Scans: ${stats.totalWifiScans}")
        sb.appendLine("BLE Devices Seen: ${stats.bleDevicesSeen}")
        sb.appendLine("WiFi Networks Seen: ${stats.wifiNetworksSeen}")
        sb.appendLine("Detections Created: ${stats.detectionsCreated}")
        sb.appendLine("Last BLE Scan: ${stats.lastBleSuccessTime?.let { dateFormat.format(java.util.Date(it)) } ?: "never"}")
        sb.appendLine("Last WiFi Scan: ${stats.lastWifiSuccessTime?.let { dateFormat.format(java.util.Date(it)) } ?: "never"}")
        sb.appendLine()

        // Detector Health Status
        sb.appendLine("=== DETECTOR HEALTH STATUS ===")
        if (state.detectorHealth.isEmpty()) {
            sb.appendLine("No detector health data available")
        } else {
            state.detectorHealth.forEach { (name, health) ->
                sb.appendLine("$name:")
                sb.appendLine("  Running: ${health.isRunning}")
                sb.appendLine("  Healthy: ${health.isHealthy}")
                sb.appendLine("  Last Success: ${health.lastSuccessfulScan?.let { dateFormat.format(java.util.Date(it)) } ?: "never"}")
                sb.appendLine("  Last Error: ${health.lastError ?: "none"}")
                sb.appendLine("  Consecutive Failures: ${health.consecutiveFailures}")
            }
        }
        sb.appendLine()

        // Recent Errors
        sb.appendLine("=== RECENT ERRORS (${state.recentErrors.size}) ===")
        if (state.recentErrors.isEmpty()) {
            sb.appendLine("No recent errors")
        } else {
            state.recentErrors.forEach { error ->
                sb.appendLine("${dateFormat.format(java.util.Date(error.timestamp))} [${error.subsystem}]: ${error.message}")
            }
        }
        sb.appendLine()

        sb.appendLine("=== END DEBUG EXPORT ===")
        sb.appendLine()
        sb.appendLine("Please share this export to help improve detection accuracy.")

        return sb.toString()
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }
}

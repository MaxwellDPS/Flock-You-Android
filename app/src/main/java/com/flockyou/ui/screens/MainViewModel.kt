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
    val filterDeviceType: DeviceType? = null,
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
    // UI settings
    val advancedMode: Boolean = false,
    // AI Analysis
    val analyzingDetectionId: String? = null,
    val analysisResult: com.flockyou.data.AiAnalysisResult? = null,
    val isAiAnalysisAvailable: Boolean = false
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
    private val detectionAnalyzer: com.flockyou.ai.DetectionAnalyzer
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // IPC connection to the scanning service (runs in separate process)
    private val serviceConnection = ScanningServiceConnection(application)

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
        // Bind to the scanning service for cross-process IPC
        serviceConnection.bind()

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
                _uiState.update { it.copy(detectorHealth = health) }
            }
        }

        // Scan statistics collection
        viewModelScope.launch {
            serviceConnection.scanStats.collect { stats ->
                _uiState.update { it.copy(scanStats = stats) }
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

    override fun onCleared() {
        super.onCleared()
        // Unbind from the service when ViewModel is destroyed
        serviceConnection.unbind()
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
    
    fun setDeviceTypeFilter(deviceType: DeviceType?) {
        _uiState.update { it.copy(filterDeviceType = deviceType) }
    }
    
    fun clearFilters() {
        _uiState.update { it.copy(filterThreatLevel = null, filterDeviceType = null) }
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
            val threatMatch = state.filterThreatLevel?.let { detection.threatLevel == it } ?: true
            val typeMatch = state.filterDeviceType?.let { detection.deviceType == it } ?: true
            threatMatch && typeMatch
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

    // ========== AI Analysis ==========

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
}

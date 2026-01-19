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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    // UI settings
    val advancedMode: Boolean = false
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
    private val workManager: WorkManager
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

        // Observe scanning state from IPC service connection (cross-process)
        viewModelScope.launch {
            serviceConnection.isScanning.collect { isScanning ->
                _uiState.update { it.copy(isScanning = isScanning) }
            }
        }

        // Observe scan status from IPC
        viewModelScope.launch {
            serviceConnection.scanStatus.collect { statusStr ->
                val status = ScanningService.ScanStatus.fromIpcString(statusStr)
                _uiState.update { it.copy(scanStatus = status) }
            }
        }

        // Observe subsystem statuses from IPC
        viewModelScope.launch {
            serviceConnection.bleStatus.collect { statusStr ->
                val status = ScanningService.SubsystemStatus.fromIpcString(statusStr)
                _uiState.update { it.copy(bleStatus = status) }
            }
        }
        viewModelScope.launch {
            serviceConnection.wifiStatus.collect { statusStr ->
                val status = ScanningService.SubsystemStatus.fromIpcString(statusStr)
                _uiState.update { it.copy(wifiStatus = status) }
            }
        }
        viewModelScope.launch {
            serviceConnection.locationStatus.collect { statusStr ->
                val status = ScanningService.SubsystemStatus.fromIpcString(statusStr)
                _uiState.update { it.copy(locationStatus = status) }
            }
        }
        viewModelScope.launch {
            serviceConnection.cellularStatus.collect { statusStr ->
                val status = ScanningService.SubsystemStatus.fromIpcString(statusStr)
                _uiState.update { it.copy(cellularStatus = status) }
            }
        }
        viewModelScope.launch {
            serviceConnection.satelliteStatus.collect { statusStr ->
                val status = ScanningService.SubsystemStatus.fromIpcString(statusStr)
                _uiState.update { it.copy(satelliteStatus = status) }
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
        
        // Observe last detection from IPC service connection
        viewModelScope.launch {
            serviceConnection.lastDetection.collect { detection ->
                _uiState.update { it.copy(lastDetection = detection) }
            }
        }

        // Observe all detections from database
        viewModelScope.launch {
            repository.allDetections.collect { detections ->
                _uiState.update { it.copy(detections = detections, isLoading = false) }
            }
        }

        // Observe detection refresh events from service (ensures UI updates even if Room Flow fails)
        viewModelScope.launch {
            ScanningService.detectionRefreshEvent.collect {
                // Manually refresh detection list and counts from database
                refreshDetections()
            }
        }

        // Observe total count from database
        viewModelScope.launch {
            repository.totalDetectionCount.collect { count ->
                _uiState.update { it.copy(totalCount = count) }
            }
        }

        // Observe high threat count from database
        viewModelScope.launch {
            repository.highThreatCount.collect { count ->
                _uiState.update { it.copy(highThreatCount = count) }
            }
        }

        // Observe seen BLE devices from IPC
        viewModelScope.launch {
            serviceConnection.seenBleDevices.collect { devices ->
                _uiState.update { it.copy(seenBleDevices = devices) }
            }
        }

        // Observe seen WiFi networks from IPC
        viewModelScope.launch {
            serviceConnection.seenWifiNetworks.collect { networks ->
                _uiState.update { it.copy(seenWifiNetworks = networks) }
            }
        }

        // Observe cellular data from IPC
        viewModelScope.launch {
            serviceConnection.cellStatus.collect { status ->
                _uiState.update { it.copy(cellStatus = status) }
            }
        }
        viewModelScope.launch {
            serviceConnection.seenCellTowers.collect { towers ->
                _uiState.update { it.copy(seenCellTowers = towers) }
            }
        }
        viewModelScope.launch {
            serviceConnection.cellularAnomalies.collect { anomalies ->
                _uiState.update { it.copy(cellularAnomalies = anomalies) }
            }
        }
        viewModelScope.launch {
            serviceConnection.cellularEvents.collect { events ->
                _uiState.update { it.copy(cellularEvents = events) }
            }
        }

        // Observe satellite data from IPC
        viewModelScope.launch {
            serviceConnection.satelliteState.collect { state ->
                _uiState.update { it.copy(satelliteState = state) }
            }
        }
        viewModelScope.launch {
            serviceConnection.satelliteAnomalies.collect { anomalies ->
                _uiState.update { it.copy(satelliteAnomalies = anomalies) }
            }
        }
        viewModelScope.launch {
            serviceConnection.satelliteHistory.collect { history ->
                _uiState.update { it.copy(satelliteHistory = history) }
            }
        }

        // Observe rogue WiFi data from IPC
        viewModelScope.launch {
            serviceConnection.rogueWifiStatus.collect { status ->
                _uiState.update { it.copy(rogueWifiStatus = status) }
            }
        }
        viewModelScope.launch {
            serviceConnection.rogueWifiAnomalies.collect { anomalies ->
                _uiState.update { it.copy(rogueWifiAnomalies = anomalies) }
            }
        }
        viewModelScope.launch {
            serviceConnection.suspiciousNetworks.collect { networks ->
                _uiState.update { it.copy(suspiciousNetworks = networks) }
            }
        }

        // Observe RF data from IPC
        viewModelScope.launch {
            serviceConnection.rfStatus.collect { status ->
                _uiState.update { it.copy(rfStatus = status) }
            }
        }
        viewModelScope.launch {
            serviceConnection.rfAnomalies.collect { anomalies ->
                _uiState.update { it.copy(rfAnomalies = anomalies) }
            }
        }
        viewModelScope.launch {
            serviceConnection.detectedDrones.collect { drones ->
                _uiState.update { it.copy(detectedDrones = drones) }
            }
        }

        // Observe ultrasonic data from IPC
        viewModelScope.launch {
            serviceConnection.ultrasonicStatus.collect { status ->
                _uiState.update { it.copy(ultrasonicStatus = status) }
            }
        }
        viewModelScope.launch {
            serviceConnection.ultrasonicAnomalies.collect { anomalies ->
                _uiState.update { it.copy(ultrasonicAnomalies = anomalies) }
            }
        }
        viewModelScope.launch {
            serviceConnection.ultrasonicBeacons.collect { beacons ->
                _uiState.update { it.copy(ultrasonicBeacons = beacons) }
            }
        }

        // Observe advanced mode setting
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(advancedMode = settings.advancedMode) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Unbind from the service when ViewModel is destroyed
        serviceConnection.unbind()
    }

    fun startScanning() {
        // First, start the service (required for foreground service)
        val intent = Intent(application, ScanningService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
        // Also send IPC command (service will receive both, but IPC ensures state sync)
        serviceConnection.startScanning()
    }

    fun stopScanning() {
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
}

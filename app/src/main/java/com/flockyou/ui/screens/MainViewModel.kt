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
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.network.OrbotHelper
import com.flockyou.service.CellularMonitor
import com.flockyou.service.RfSignalAnalyzer
import com.flockyou.service.RogueWifiMonitor
import com.flockyou.service.ScanningService
import com.flockyou.service.UltrasonicDetector
import com.flockyou.worker.OuiUpdateWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isScanning: Boolean = false,
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
    // Cellular monitoring
    val cellStatus: CellularMonitor.CellStatus? = null,
    val cellularAnomalies: List<CellularMonitor.CellularAnomaly> = emptyList(),
    val seenCellTowers: List<CellularMonitor.SeenCellTower> = emptyList(),
    val cellularEvents: List<CellularMonitor.CellularEvent> = emptyList(),
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
    private val settingsRepository: DetectionSettingsRepository,
    private val ouiSettingsRepository: OuiSettingsRepository,
    private val networkSettingsRepository: NetworkSettingsRepository,
    private val broadcastSettingsRepository: BroadcastSettingsRepository,
    private val orbotHelper: OrbotHelper,
    private val workManager: WorkManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

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

    private val _isOrbotInstalled = MutableStateFlow(false)
    val isOrbotInstalled: StateFlow<Boolean> = _isOrbotInstalled.asStateFlow()

    private val _isOrbotRunning = MutableStateFlow(false)
    val isOrbotRunning: StateFlow<Boolean> = _isOrbotRunning.asStateFlow()

    init {
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

        // Observe scanning state from service
        viewModelScope.launch {
            ScanningService.isScanning.collect { isScanning ->
                _uiState.update { it.copy(isScanning = isScanning) }
            }
        }
        
        // Observe last detection from service
        viewModelScope.launch {
            ScanningService.lastDetection.collect { detection ->
                _uiState.update { it.copy(lastDetection = detection) }
            }
        }
        
        // Observe all detections
        viewModelScope.launch {
            repository.allDetections.collect { detections ->
                _uiState.update { it.copy(detections = detections) }
            }
        }

        // Observe detection refresh events from service (ensures UI updates even if Room Flow fails)
        viewModelScope.launch {
            ScanningService.detectionRefreshEvent.collect {
                // Manually refresh detection list and counts from database
                refreshDetections()
            }
        }

        // Observe total count
        viewModelScope.launch {
            repository.totalDetectionCount.collect { count ->
                _uiState.update { it.copy(totalCount = count) }
            }
        }

        // Observe high threat count
        viewModelScope.launch {
            repository.highThreatCount.collect { count ->
                _uiState.update { it.copy(highThreatCount = count) }
            }
        }
        
        // Observe scan status
        viewModelScope.launch {
            ScanningService.scanStatus.collect { status ->
                _uiState.update { it.copy(scanStatus = status) }
            }
        }
        
        // Observe BLE status
        viewModelScope.launch {
            ScanningService.bleStatus.collect { status ->
                _uiState.update { it.copy(bleStatus = status) }
            }
        }
        
        // Observe WiFi status
        viewModelScope.launch {
            ScanningService.wifiStatus.collect { status ->
                _uiState.update { it.copy(wifiStatus = status) }
            }
        }
        
        // Observe location status
        viewModelScope.launch {
            ScanningService.locationStatus.collect { status ->
                _uiState.update { it.copy(locationStatus = status) }
            }
        }
        
        // Observe error log (only keep last 5 for UI)
        viewModelScope.launch {
            ScanningService.errorLog.collect { errors ->
                _uiState.update { it.copy(recentErrors = errors.take(5)) }
            }
        }
        
        // Observe cellular status
        viewModelScope.launch {
            ScanningService.cellularStatus.collect { status ->
                _uiState.update { it.copy(cellularStatus = status) }
            }
        }
        
        // Observe satellite status
        viewModelScope.launch {
            ScanningService.satelliteStatus.collect { status ->
                _uiState.update { it.copy(satelliteStatus = status) }
            }
        }
        
        // Observe cell tower status
        viewModelScope.launch {
            ScanningService.cellStatus.collect { status ->
                _uiState.update { it.copy(cellStatus = status) }
            }
        }
        
        // Observe cellular anomalies
        viewModelScope.launch {
            ScanningService.cellularAnomalies.collect { anomalies ->
                _uiState.update { it.copy(cellularAnomalies = anomalies) }
            }
        }
        
        // Observe seen cell towers
        viewModelScope.launch {
            ScanningService.seenCellTowers.collect { towers ->
                _uiState.update { it.copy(seenCellTowers = towers) }
            }
        }
        
        // Observe cellular events timeline
        viewModelScope.launch {
            ScanningService.cellularEvents.collect { events ->
                _uiState.update { it.copy(cellularEvents = events) }
            }
        }

        // Observe rogue WiFi status
        viewModelScope.launch {
            ScanningService.rogueWifiStatus.collect { status ->
                _uiState.update { it.copy(rogueWifiStatus = status) }
            }
        }

        // Observe rogue WiFi anomalies
        viewModelScope.launch {
            ScanningService.rogueWifiAnomalies.collect { anomalies ->
                _uiState.update { it.copy(rogueWifiAnomalies = anomalies) }
            }
        }

        // Observe suspicious networks
        viewModelScope.launch {
            ScanningService.suspiciousNetworks.collect { networks ->
                _uiState.update { it.copy(suspiciousNetworks = networks) }
            }
        }

        // Observe RF status
        viewModelScope.launch {
            ScanningService.rfStatus.collect { status ->
                _uiState.update { it.copy(rfStatus = status) }
            }
        }

        // Observe RF anomalies
        viewModelScope.launch {
            ScanningService.rfAnomalies.collect { anomalies ->
                _uiState.update { it.copy(rfAnomalies = anomalies) }
            }
        }

        // Observe detected drones
        viewModelScope.launch {
            ScanningService.detectedDrones.collect { drones ->
                _uiState.update { it.copy(detectedDrones = drones) }
            }
        }

        // Observe ultrasonic status
        viewModelScope.launch {
            ScanningService.ultrasonicStatus.collect { status ->
                _uiState.update { it.copy(ultrasonicStatus = status) }
            }
        }

        // Observe ultrasonic anomalies
        viewModelScope.launch {
            ScanningService.ultrasonicAnomalies.collect { anomalies ->
                _uiState.update { it.copy(ultrasonicAnomalies = anomalies) }
            }
        }

        // Observe ultrasonic beacons
        viewModelScope.launch {
            ScanningService.ultrasonicBeacons.collect { beacons ->
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
    
    fun startScanning() {
        val intent = Intent(application, ScanningService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
    }
    
    fun stopScanning() {
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
}

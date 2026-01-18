package com.flockyou.ui.screens

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.service.ScanningService
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val recentErrors: List<ScanningService.ScanError> = emptyList()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
    private val repository: DetectionRepository
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
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
    
    fun getFilteredDetections(): List<Detection> {
        val state = _uiState.value
        return state.detections.filter { detection ->
            val threatMatch = state.filterThreatLevel?.let { detection.threatLevel == it } ?: true
            val typeMatch = state.filterDeviceType?.let { detection.deviceType == it } ?: true
            threatMatch && typeMatch
        }
    }
}

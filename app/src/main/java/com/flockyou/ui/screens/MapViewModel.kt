package com.flockyou.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.SignalStrength
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.repository.DetectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val allDetectionsWithLocation: List<Detection> = emptyList(),
    val showHeatmap: Boolean = false,
    // Filter state (same as MainUiState)
    val filterThreatLevel: ThreatLevel? = null,
    val filterDeviceTypes: Set<DeviceType> = emptySet(),
    val filterMatchAll: Boolean = true,
    val filterProtocols: Set<DetectionProtocol> = emptySet(),
    val filterTimeRange: TimeRange = TimeRange.ALL_TIME,
    val filterCustomStartTime: Long? = null,
    val filterCustomEndTime: Long? = null,
    val filterSignalStrength: Set<SignalStrength> = emptySet(),
    val filterActiveOnly: Boolean = false
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: DetectionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /**
     * Flow of filtered detections with location
     */
    val detectionsWithLocation: StateFlow<List<Detection>> = _uiState
        .map { state -> getFilteredDetections(state) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            repository.detectionsWithLocation.collect { detections ->
                _uiState.update { it.copy(allDetectionsWithLocation = detections) }
            }
        }
    }

    private fun getFilteredDetections(state: MapUiState): List<Detection> {
        return state.allDetectionsWithLocation.filter { detection ->
            // 1. Threat Level filter
            val threatPass = state.filterThreatLevel?.let { detection.threatLevel == it } ?: true

            // 2. Device Type filter
            val typePass = if (state.filterDeviceTypes.isEmpty()) {
                true
            } else {
                detection.deviceType in state.filterDeviceTypes
            }

            // 3. Protocol filter
            val protocolPass = if (state.filterProtocols.isEmpty()) {
                true
            } else {
                detection.protocol in state.filterProtocols
            }

            // 4. Time Range filter
            val timePass = when (state.filterTimeRange) {
                TimeRange.ALL_TIME -> true
                TimeRange.CUSTOM -> {
                    val start = state.filterCustomStartTime ?: 0L
                    val end = state.filterCustomEndTime ?: Long.MAX_VALUE
                    detection.timestamp in start..end
                }
                else -> {
                    val cutoff = System.currentTimeMillis() - (state.filterTimeRange.durationMs ?: 0L)
                    detection.timestamp >= cutoff
                }
            }

            // 5. Signal Strength filter
            val signalPass = if (state.filterSignalStrength.isEmpty()) {
                true
            } else {
                detection.signalStrength in state.filterSignalStrength
            }

            // 6. Active Only filter
            val activePass = !state.filterActiveOnly || detection.isActive

            // Combine threat+type with AND/OR logic
            val threatTypePass = if (state.filterMatchAll) {
                threatPass && typePass
            } else {
                if (state.filterThreatLevel != null && state.filterDeviceTypes.isNotEmpty()) {
                    threatPass || typePass
                } else {
                    threatPass && typePass
                }
            }

            threatTypePass && protocolPass && timePass && signalPass && activePass
        }
    }

    fun toggleHeatmap() {
        _uiState.update { it.copy(showHeatmap = !it.showHeatmap) }
    }

    // Filter setter methods
    fun setThreatFilter(threatLevel: ThreatLevel?) {
        _uiState.update { it.copy(filterThreatLevel = threatLevel) }
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

    fun toggleProtocolFilter(protocol: DetectionProtocol) {
        _uiState.update { state ->
            if (protocol in state.filterProtocols) {
                state.copy(filterProtocols = state.filterProtocols - protocol)
            } else {
                state.copy(filterProtocols = state.filterProtocols + protocol)
            }
        }
    }

    fun setTimeRange(range: TimeRange) {
        _uiState.update {
            if (range != TimeRange.CUSTOM) {
                it.copy(
                    filterTimeRange = range,
                    filterCustomStartTime = null,
                    filterCustomEndTime = null
                )
            } else {
                it.copy(filterTimeRange = range)
            }
        }
    }

    fun setCustomTimeRange(start: Long, end: Long) {
        _uiState.update {
            it.copy(
                filterTimeRange = TimeRange.CUSTOM,
                filterCustomStartTime = start,
                filterCustomEndTime = end
            )
        }
    }

    fun toggleSignalStrengthFilter(strength: SignalStrength) {
        _uiState.update { state ->
            if (strength in state.filterSignalStrength) {
                state.copy(filterSignalStrength = state.filterSignalStrength - strength)
            } else {
                state.copy(filterSignalStrength = state.filterSignalStrength + strength)
            }
        }
    }

    fun setActiveOnly(activeOnly: Boolean) {
        _uiState.update { it.copy(filterActiveOnly = activeOnly) }
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                filterThreatLevel = null,
                filterDeviceTypes = emptySet(),
                filterProtocols = emptySet(),
                filterTimeRange = TimeRange.ALL_TIME,
                filterCustomStartTime = null,
                filterCustomEndTime = null,
                filterSignalStrength = emptySet(),
                filterActiveOnly = false
            )
        }
    }

    fun getActiveFilterCount(): Int {
        val state = _uiState.value
        var count = 0
        if (state.filterThreatLevel != null) count++
        if (state.filterDeviceTypes.isNotEmpty()) count += state.filterDeviceTypes.size
        if (state.filterProtocols.isNotEmpty()) count += state.filterProtocols.size
        if (state.filterTimeRange != TimeRange.ALL_TIME) count++
        if (state.filterSignalStrength.isNotEmpty()) count += state.filterSignalStrength.size
        if (state.filterActiveOnly) count++
        return count
    }
}

package com.flockyou.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.data.model.Detection
import com.flockyou.data.repository.DetectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val detectionsWithLocation: List<Detection> = emptyList(),
    val showHeatmap: Boolean = false
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: DetectionRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            repository.detectionsWithLocation.collect { detections ->
                _uiState.update { it.copy(detectionsWithLocation = detections) }
            }
        }
    }
    
    fun toggleHeatmap() {
        _uiState.update { it.copy(showHeatmap = !it.showHeatmap) }
    }
}

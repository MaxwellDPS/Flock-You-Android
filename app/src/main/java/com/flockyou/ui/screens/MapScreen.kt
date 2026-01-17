package com.flockyou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.model.*
import com.flockyou.ui.components.ThreatBadge
import com.flockyou.ui.components.toIcon
import com.flockyou.ui.theme.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedDetection by remember { mutableStateOf<Detection?>(null) }
    
    val cameraPositionState = rememberCameraPositionState {
        // Default to US center
        position = CameraPosition.fromLatLngZoom(LatLng(39.8283, -98.5795), 4f)
    }
    
    // Center on detections when they load
    LaunchedEffect(uiState.detectionsWithLocation) {
        uiState.detectionsWithLocation.firstOrNull()?.let { detection ->
            if (detection.latitude != null && detection.longitude != null) {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(detection.latitude, detection.longitude),
                        14f
                    )
                )
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detection Map") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleHeatmap() }) {
                        Icon(
                            imageVector = if (uiState.showHeatmap) 
                                Icons.Default.Layers 
                            else 
                                Icons.Default.LayersClear,
                            contentDescription = "Toggle heatmap"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    mapType = MapType.NORMAL,
                    isMyLocationEnabled = false
                ),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = true,
                    compassEnabled = true
                )
            ) {
                // Add markers for each detection with location
                uiState.detectionsWithLocation.forEach { detection ->
                    if (detection.latitude != null && detection.longitude != null) {
                        val position = LatLng(detection.latitude, detection.longitude)
                        val hue = when (detection.threatLevel) {
                            ThreatLevel.CRITICAL -> BitmapDescriptorFactory.HUE_RED
                            ThreatLevel.HIGH -> BitmapDescriptorFactory.HUE_ORANGE
                            ThreatLevel.MEDIUM -> BitmapDescriptorFactory.HUE_YELLOW
                            ThreatLevel.LOW -> BitmapDescriptorFactory.HUE_GREEN
                            ThreatLevel.INFO -> BitmapDescriptorFactory.HUE_AZURE
                        }
                        
                        Marker(
                            state = MarkerState(position = position),
                            title = detection.deviceType.name.replace("_", " "),
                            snippet = detection.macAddress ?: detection.ssid,
                            icon = BitmapDescriptorFactory.defaultMarker(hue),
                            onClick = {
                                selectedDetection = detection
                                true
                            }
                        )
                    }
                }
            }
            
            // Legend
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "Threat Levels",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LegendItem("Critical", ThreatCritical)
                    LegendItem("High", ThreatHigh)
                    LegendItem("Medium", ThreatMedium)
                    LegendItem("Low", ThreatLow)
                    LegendItem("Info", ThreatInfo)
                }
            }
            
            // Detection count badge
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${uiState.detectionsWithLocation.size} locations",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
    
    // Detection detail sheet
    selectedDetection?.let { detection ->
        MapDetectionSheet(
            detection = detection,
            onDismiss = { selectedDetection = null }
        )
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .padding(2.dp)
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = color)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapDetectionSheet(
    detection: Detection,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = detection.deviceType.toIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detection.deviceType.name.replace("_", " "),
                        style = MaterialTheme.typography.titleMedium
                    )
                    detection.manufacturer?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ThreatBadge(threatLevel = detection.threatLevel)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            detection.macAddress?.let {
                Text("MAC: $it", style = MaterialTheme.typography.bodySmall)
            }
            detection.ssid?.let {
                Text("SSID: $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Signal: ${detection.rssi} dBm",
                style = MaterialTheme.typography.bodySmall
            )
            if (detection.latitude != null && detection.longitude != null) {
                Text(
                    "Location: %.6f, %.6f".format(detection.latitude, detection.longitude),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

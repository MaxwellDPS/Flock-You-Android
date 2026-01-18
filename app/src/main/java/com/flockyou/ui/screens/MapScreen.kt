package com.flockyou.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.model.*
import com.flockyou.ui.components.ThreatBadge
import com.flockyou.ui.components.toIcon
import com.flockyou.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var selectedDetection by remember { mutableStateOf<Detection?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var hasZoomedToFit by remember { mutableStateOf(false) }
    
    // Initialize osmdroid configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.getExternalFilesDir(null)
            osmdroidTileCache = context.getExternalFilesDir("tiles")
        }
    }
    
    // Update markers when detections change
    LaunchedEffect(uiState.detectionsWithLocation, mapView) {
        mapView?.let { map ->
            // Clear existing markers
            map.overlays.removeAll { it is Marker }
            
            val points = mutableListOf<GeoPoint>()
            
            // Add markers for each detection
            uiState.detectionsWithLocation.forEach { detection ->
                if (detection.latitude != null && detection.longitude != null) {
                    val geoPoint = GeoPoint(detection.latitude, detection.longitude)
                    points.add(geoPoint)
                    
                    val marker = Marker(map).apply {
                        position = geoPoint
                        title = detection.deviceType.name.replace("_", " ")
                        snippet = detection.macAddress ?: detection.ssid ?: "Unknown"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = createMarkerDrawable(detection.threatLevel)
                        
                        setOnMarkerClickListener { _, _ ->
                            selectedDetection = detection
                            true
                        }
                    }
                    map.overlays.add(marker)
                }
            }
            
            // Auto-zoom to fit all markers
            if (points.isNotEmpty() && !hasZoomedToFit) {
                hasZoomedToFit = true
                zoomToFitPoints(map, points)
            }
            
            map.invalidate()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detection Map") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Zoom to fit all markers
                    IconButton(onClick = {
                        mapView?.let { map ->
                            val points = uiState.detectionsWithLocation
                                .filter { it.latitude != null && it.longitude != null }
                                .map { GeoPoint(it.latitude!!, it.longitude!!) }
                            
                            if (points.isNotEmpty()) {
                                zoomToFitPoints(map, points)
                            }
                        }
                    }) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = "Fit All")
                    }
                    
                    // Center on user location (if available from detections)
                    IconButton(onClick = {
                        mapView?.let { map ->
                            val latest = uiState.detectionsWithLocation
                                .filter { it.latitude != null && it.longitude != null }
                                .maxByOrNull { it.timestamp }
                            
                            if (latest != null) {
                                map.controller.animateTo(
                                    GeoPoint(latest.latitude!!, latest.longitude!!),
                                    16.0,
                                    500L
                                )
                            }
                        }
                    }) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Latest")
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
            // OpenStreetMap View
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(4.0)
                        controller.setCenter(GeoPoint(39.8283, -98.5795))
                        zoomController.setVisibility(
                            org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
                        )
                        mapView = this
                    }
                },
                update = { map ->
                    mapView = map
                }
            )
            
            // Legend
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
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
            
            // Detection count
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
            
            // Attribution
            Card(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                )
            ) {
                Text(
                    text = "© OpenStreetMap",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
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
    
    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDetach()
        }
    }
}

private fun zoomToFitPoints(map: MapView, points: List<GeoPoint>) {
    if (points.isEmpty()) return
    
    if (points.size == 1) {
        // Single point - just center and zoom
        map.controller.animateTo(points.first(), 16.0, 500L)
        return
    }
    
    // Calculate bounding box for all points
    var north = -90.0
    var south = 90.0
    var east = -180.0
    var west = 180.0
    
    for (point in points) {
        if (point.latitude > north) north = point.latitude
        if (point.latitude < south) south = point.latitude
        if (point.longitude > east) east = point.longitude
        if (point.longitude < west) west = point.longitude
    }
    
    // Add padding (10%)
    val latPadding = (north - south) * 0.1
    val lonPadding = (east - west) * 0.1
    
    val boundingBox = BoundingBox(
        north + latPadding,
        east + lonPadding,
        south - latPadding,
        west - lonPadding
    )
    
    // Zoom to bounding box
    map.post {
        map.zoomToBoundingBox(boundingBox, true, 50)
    }
}

private fun createMarkerDrawable(threatLevel: ThreatLevel): android.graphics.drawable.Drawable {
    val color = when (threatLevel) {
        ThreatLevel.CRITICAL -> Color.parseColor("#D32F2F")
        ThreatLevel.HIGH -> Color.parseColor("#F57C00")
        ThreatLevel.MEDIUM -> Color.parseColor("#FBC02D")
        ThreatLevel.LOW -> Color.parseColor("#388E3C")
        ThreatLevel.INFO -> Color.parseColor("#1976D2")
    }
    
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(4, Color.WHITE)
        setSize(48, 48)
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
        Box(modifier = Modifier.size(12.dp).padding(2.dp)) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = color)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    style = MaterialTheme.typography.titleMedium
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

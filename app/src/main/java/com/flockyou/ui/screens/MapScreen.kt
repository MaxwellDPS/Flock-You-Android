package com.flockyou.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.model.*
import com.flockyou.service.ScanningService
import com.flockyou.ui.components.ThreatBadge
import com.flockyou.ui.components.toIcon
import com.flockyou.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sqrt

// Cluster radius in degrees (approximately 100m at equator)
private const val CLUSTER_RADIUS_DEGREES = 0.001

/**
 * Represents a cluster of detections on the map
 */
private data class DetectionCluster(
    val center: GeoPoint,
    val detections: List<Detection>,
    val highestThreatLevel: ThreatLevel
)

/**
 * GPS status for the indicator
 */
private enum class GpsStatus {
    ACTIVE, SEARCHING, DISABLED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToDetectionDetail: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var selectedDetection by remember { mutableStateOf<Detection?>(null) }
    var selectedCluster by remember { mutableStateOf<DetectionCluster?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var currentZoom by remember { mutableStateOf(4.0) }

    // GPS status state
    var gpsStatus by remember { mutableStateOf(GpsStatus.SEARCHING) }
    var gpsAccuracyMeters by remember { mutableStateOf<Float?>(null) }
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }

    // Location permission launcher for requesting location permissions
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions result received - the map will update automatically when
        // new detections with location data are received
    }

    // Function to request location permissions
    val requestLocationPermissions: () -> Unit = {
        val permissionsToRequest = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        locationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    // Function to start scanning service
    val startScanning: () -> Unit = {
        val intent = Intent(context, ScanningService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // Update GPS status based on detections with location
    LaunchedEffect(uiState.detectionsWithLocation) {
        val hasLocationData = uiState.detectionsWithLocation.any {
            it.latitude != null && it.longitude != null
        }
        gpsStatus = when {
            hasLocationData -> GpsStatus.ACTIVE
            uiState.detectionsWithLocation.isEmpty() -> GpsStatus.SEARCHING
            else -> GpsStatus.DISABLED
        }

        // Get user location from most recent detection
        uiState.detectionsWithLocation
            .filter { it.latitude != null && it.longitude != null }
            .maxByOrNull { it.timestamp }
            ?.let { latest ->
                userLocation = GeoPoint(latest.latitude!!, latest.longitude!!)
                // Estimate accuracy based on signal strength (rough approximation)
                gpsAccuracyMeters = when (latest.signalStrength) {
                    SignalStrength.EXCELLENT -> 10f
                    SignalStrength.GOOD -> 25f
                    SignalStrength.MEDIUM -> 50f
                    SignalStrength.WEAK -> 100f
                    else -> 150f
                }
            }
    }
    
    // Initialize osmdroid configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.getExternalFilesDir(null)
            osmdroidTileCache = context.getExternalFilesDir("tiles")
        }
    }
    
    // Update markers when detections change - with clustering support
    LaunchedEffect(uiState.detectionsWithLocation, mapView, currentZoom) {
        mapView?.let { map ->
            // Clear existing markers and overlays
            map.overlays.removeAll { it is Marker || it is Polygon }

            val detectionsWithCoords = uiState.detectionsWithLocation.filter {
                it.latitude != null && it.longitude != null
            }

            if (detectionsWithCoords.isEmpty()) {
                map.invalidate()
                return@let
            }

            val points = mutableListOf<GeoPoint>()

            // Determine if we should cluster based on zoom level
            val shouldCluster = currentZoom < 15.0

            if (shouldCluster) {
                // Create clusters
                val clusters = clusterDetections(detectionsWithCoords)

                clusters.forEach { cluster ->
                    points.add(cluster.center)

                    if (cluster.detections.size == 1) {
                        // Single detection - show normal marker
                        val detection = cluster.detections.first()
                        val marker = Marker(map).apply {
                            position = cluster.center
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
                    } else {
                        // Multiple detections - show cluster marker
                        val marker = Marker(map).apply {
                            position = cluster.center
                            title = "${cluster.detections.size} detections"
                            snippet = "Tap to zoom in"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = createClusterDrawable(
                                count = cluster.detections.size,
                                threatLevel = cluster.highestThreatLevel
                            )

                            setOnMarkerClickListener { _, _ ->
                                // Zoom in to show individual markers
                                val clusterPoints = cluster.detections.map {
                                    GeoPoint(it.latitude!!, it.longitude!!)
                                }
                                zoomToFitPoints(map, clusterPoints, minZoom = 16.0)
                                true
                            }
                        }
                        map.overlays.add(marker)
                    }
                }
            } else {
                // No clustering - show individual markers
                detectionsWithCoords.forEach { detection ->
                    val geoPoint = GeoPoint(detection.latitude!!, detection.longitude!!)
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

            // Add GPS accuracy circle if user location is available
            userLocation?.let { location ->
                gpsAccuracyMeters?.let { accuracy ->
                    val accuracyCircle = createAccuracyCircle(location, accuracy.toDouble())
                    map.overlays.add(0, accuracyCircle) // Add at bottom so markers are on top
                }
            }

            // Auto-zoom to fit all markers on first load
            if (points.isNotEmpty() && currentZoom == 4.0) {
                zoomToFitPoints(map, points)
            }

            map.invalidate()
        }
    }

    // Track zoom level changes
    LaunchedEffect(mapView) {
        mapView?.let { map ->
            map.addMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                    currentZoom = map.zoomLevelDouble
                    return false
                }

                override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                    currentZoom = map.zoomLevelDouble
                    return false
                }
            })
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detection Map") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Reset View / Zoom to fit all markers - always works
                    IconButton(onClick = {
                        mapView?.let { map ->
                            val points = uiState.detectionsWithLocation
                                .filter { it.latitude != null && it.longitude != null }
                                .map { GeoPoint(it.latitude!!, it.longitude!!) }

                            if (points.isNotEmpty()) {
                                zoomToFitPoints(map, points)
                            } else {
                                // Reset to default view of USA
                                map.controller.animateTo(
                                    GeoPoint(39.8283, -98.5795),
                                    4.0,
                                    500L
                                )
                            }
                        }
                    }) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = "Reset View")
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
                        Icon(Icons.Default.MyLocation, contentDescription = "My Location")
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
            // Check if we have location data
            val hasLocationData = uiState.detectionsWithLocation.any {
                it.latitude != null && it.longitude != null
            }

            if (!hasLocationData && uiState.detectionsWithLocation.isNotEmpty()) {
                // Empty state - detections exist but no location data
                MapEmptyState(
                    hasDetections = true,
                    onRequestPermissions = requestLocationPermissions
                )
            } else if (uiState.detectionsWithLocation.isEmpty()) {
                // Empty state - no detections at all
                MapEmptyState(
                    hasDetections = false,
                    onRequestPermissions = startScanning
                )
            } else {
                // OpenStreetMap View with HTTPS tile source
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            // Use HTTPS tile source for security
                            setTileSource(HTTPS_MAPNIK)
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
            }

            // GPS Status Indicator (top-start corner)
            GpsStatusIndicator(
                status = gpsStatus,
                accuracyMeters = gpsAccuracyMeters,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )

            // Detection count (top-end corner)
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
                        text = "${uiState.detectionsWithLocation.filter { it.latitude != null }.size} locations",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Legend (bottom-start corner)
            if (hasLocationData) {
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
            }

            // Attribution (bottom-end corner)
            Card(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                )
            ) {
                Text(
                    text = "(C) OpenStreetMap",
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
            onDismiss = { selectedDetection = null },
            onCopyCoordinates = { lat, lon ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("coordinates", "$lat, $lon")
                clipboard.setPrimaryClip(clip)
            },
            onViewFullDetails = onNavigateToDetectionDetail?.let { { id -> it(id) } }
        )
    }
    
    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDetach()
        }
    }
}

private fun zoomToFitPoints(map: MapView, points: List<GeoPoint>, minZoom: Double? = null) {
    if (points.isEmpty()) return

    if (points.size == 1) {
        // Single point - just center and zoom
        val zoom = minZoom ?: 16.0
        map.controller.animateTo(points.first(), zoom, 500L)
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
        // Ensure minimum zoom level if specified
        minZoom?.let {
            if (map.zoomLevelDouble < it) {
                map.controller.setZoom(it)
            }
        }
    }
}

/**
 * Cluster detections that are within close proximity
 */
private fun clusterDetections(detections: List<Detection>): List<DetectionCluster> {
    if (detections.isEmpty()) return emptyList()

    val clusters = mutableListOf<DetectionCluster>()
    val assigned = mutableSetOf<String>()

    for (detection in detections) {
        if (detection.id in assigned) continue
        if (detection.latitude == null || detection.longitude == null) continue

        // Find all nearby detections
        val nearby = mutableListOf(detection)
        for (other in detections) {
            if (other.id in assigned || other.id == detection.id) continue
            if (other.latitude == null || other.longitude == null) continue

            val distance = calculateDistance(
                detection.latitude, detection.longitude,
                other.latitude, other.longitude
            )

            if (distance < CLUSTER_RADIUS_DEGREES) {
                nearby.add(other)
            }
        }

        // Mark all as assigned
        nearby.forEach { assigned.add(it.id) }

        // Calculate cluster center
        val centerLat = nearby.map { it.latitude!! }.average()
        val centerLon = nearby.map { it.longitude!! }.average()

        // Find highest threat level
        val highestThreat = nearby.maxByOrNull { threatLevelOrdinal(it.threatLevel) }?.threatLevel
            ?: ThreatLevel.INFO

        clusters.add(
            DetectionCluster(
                center = GeoPoint(centerLat, centerLon),
                detections = nearby,
                highestThreatLevel = highestThreat
            )
        )
    }

    return clusters
}

/**
 * Get ordinal value for threat level comparison
 */
private fun threatLevelOrdinal(level: ThreatLevel): Int = when (level) {
    ThreatLevel.CRITICAL -> 4
    ThreatLevel.HIGH -> 3
    ThreatLevel.MEDIUM -> 2
    ThreatLevel.LOW -> 1
    ThreatLevel.INFO -> 0
}

/**
 * Calculate simple distance between two points (in degrees)
 */
private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = lat2 - lat1
    val dLon = lon2 - lon1
    return sqrt(dLat * dLat + dLon * dLon)
}

/**
 * Create GPS accuracy circle overlay
 */
private fun createAccuracyCircle(center: GeoPoint, radiusMeters: Double): Polygon {
    val circle = Polygon()
    val points = mutableListOf<GeoPoint>()

    // Convert meters to degrees (rough approximation)
    // 1 degree latitude ~ 111km
    // 1 degree longitude ~ 111km * cos(latitude)
    val radiusLatDegrees = radiusMeters / 111000.0
    val radiusLonDegrees = radiusMeters / (111000.0 * cos(Math.toRadians(center.latitude)))

    // Create circle points
    for (i in 0..36) {
        val angle = Math.toRadians(i * 10.0)
        val lat = center.latitude + radiusLatDegrees * kotlin.math.sin(angle)
        val lon = center.longitude + radiusLonDegrees * kotlin.math.cos(angle)
        points.add(GeoPoint(lat, lon))
    }

    circle.points = points
    circle.fillPaint.color = Color.argb(50, 33, 150, 243) // Semi-transparent blue
    circle.outlinePaint.color = Color.argb(150, 33, 150, 243) // Blue outline
    circle.outlinePaint.strokeWidth = 3f

    return circle
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

/**
 * Create a cluster marker drawable with count indicator
 */
private fun createClusterDrawable(count: Int, threatLevel: ThreatLevel): android.graphics.drawable.Drawable {
    val color = when (threatLevel) {
        ThreatLevel.CRITICAL -> Color.parseColor("#D32F2F")
        ThreatLevel.HIGH -> Color.parseColor("#F57C00")
        ThreatLevel.MEDIUM -> Color.parseColor("#FBC02D")
        ThreatLevel.LOW -> Color.parseColor("#388E3C")
        ThreatLevel.INFO -> Color.parseColor("#1976D2")
    }

    // Create a larger circle for clusters
    val size = when {
        count >= 10 -> 72
        count >= 5 -> 64
        else -> 56
    }

    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(4, Color.WHITE)
        setSize(size, size)
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

/**
 * HTTPS tile source for OpenStreetMap.
 * Uses HTTPS to prevent MITM attacks on map tile requests.
 */
private val HTTPS_MAPNIK = object : OnlineTileSourceBase(
    "Mapnik-HTTPS",
    0,
    19,
    256,
    ".png",
    arrayOf(
        "https://a.tile.openstreetmap.org/",
        "https://b.tile.openstreetmap.org/",
        "https://c.tile.openstreetmap.org/"
    ),
    "© OpenStreetMap contributors"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return baseUrl + zoom + "/" + x + "/" + y + mImageFilenameEnding
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapDetectionSheet(
    detection: Detection,
    onDismiss: () -> Unit,
    onCopyCoordinates: ((Double, Double) -> Unit)? = null,
    onViewFullDetails: ((String) -> Unit)? = null
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()) }
    var showCopiedToast by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with device info
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

            // Detection statistics
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    // Seen count
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Seen ${detection.seenCount} time${if (detection.seenCount > 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // First seen timestamp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "First seen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = dateFormat.format(Date(detection.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Last seen timestamp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Update,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Last seen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = dateFormat.format(Date(detection.lastSeenTimestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Device identifiers
            detection.macAddress?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "MAC: $it",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            detection.ssid?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "SSID: $it",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SignalCellularAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Signal: ${detection.rssi} dBm (${detection.signalStrength.displayName})",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Location with copy button
            if (detection.latitude != null && detection.longitude != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Location",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "%.6f, %.6f".format(detection.latitude, detection.longitude),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (onCopyCoordinates != null) {
                            IconButton(
                                onClick = {
                                    onCopyCoordinates(detection.latitude, detection.longitude)
                                    showCopiedToast = true
                                }
                            ) {
                                Icon(
                                    imageVector = if (showCopiedToast) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy coordinates",
                                    tint = if (showCopiedToast) StatusActive else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Reset copied toast after delay
                LaunchedEffect(showCopiedToast) {
                    if (showCopiedToast) {
                        kotlinx.coroutines.delay(2000)
                        showCopiedToast = false
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // View Full Details button
                if (onViewFullDetails != null) {
                    OutlinedButton(
                        onClick = {
                            onViewFullDetails(detection.id)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Full Details")
                    }
                }

                // Close button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Close")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * GPS Status Indicator component
 */
@Composable
private fun GpsStatusIndicator(
    status: GpsStatus,
    accuracyMeters: Float?,
    modifier: Modifier = Modifier
) {
    val (statusColor, statusIcon, statusText) = when (status) {
        GpsStatus.ACTIVE -> Triple(
            StatusActive,
            Icons.Default.GpsFixed,
            "GPS Active"
        )
        GpsStatus.SEARCHING -> Triple(
            StatusWarning,
            Icons.Default.GpsNotFixed,
            "Searching..."
        )
        GpsStatus.DISABLED -> Triple(
            StatusError,
            Icons.Default.GpsOff,
            "Disabled"
        )
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = statusColor
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
                if (status == GpsStatus.ACTIVE && accuracyMeters != null) {
                    Text(
                        text = "+/- ${accuracyMeters.toInt()}m",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Empty state for the map screen
 */
@Composable
private fun MapEmptyState(
    hasDetections: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (hasDetections) Icons.Default.LocationOff else Icons.Default.Map,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (hasDetections)
                        "No Location Data"
                    else
                        "No Detections Yet",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (hasDetections)
                        "Detections were found but none have location data. Enable GPS permission to see detections on the map."
                    else
                        "Start scanning to detect surveillance devices. They will appear on the map when found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRequestPermissions
                ) {
                    Icon(
                        imageVector = if (hasDetections) Icons.Default.LocationOn else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasDetections)
                            "Enable Location"
                        else
                            "Start Scanning"
                    )
                }
            }
        }
    }
}

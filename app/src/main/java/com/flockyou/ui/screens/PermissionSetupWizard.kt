package com.flockyou.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Permission category with associated detection methods
 */
data class PermissionCategory(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val permissions: List<String>,
    val detectionMethods: List<DetectionMethodInfo>,
    val isRequired: Boolean = true,
    val minSdk: Int = 1
)

/**
 * Information about a detection method enabled by a permission
 */
data class DetectionMethodInfo(
    val name: String,
    val description: String,
    val devicesDetected: List<String>,
    val historyInfo: String,
    val nearbyInfo: String,
    val evidenceCollected: List<String>
)

/**
 * Main permission setup wizard composable
 * Only shows pages for permission categories that have missing permissions.
 */
@Suppress("UNUSED_PARAMETER") // onRequestBackgroundLocation reserved for separate background location flow
@Composable
fun PermissionSetupWizard(
    onRequestPermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentPage by remember { mutableIntStateOf(0) }

    // Filter categories to only show those with missing permissions
    val permissionCategories = remember {
        getPermissionCategories().filter { category ->
            // Check SDK requirement
            if (Build.VERSION.SDK_INT < category.minSdk) return@filter false
            // Check if category has any missing permissions
            category.permissions.any { permission ->
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            }
        }
    }

    // Total pages = welcome + permission categories + final (but no final if no missing permissions)
    val totalPages = if (permissionCategories.isEmpty()) 1 else permissionCategories.size + 2

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Progress indicator
        LinearProgressIndicator(
            progress = { (currentPage + 1).toFloat() / totalPages },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        // Page dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(totalPages) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (index == currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == currentPage) MaterialTheme.colorScheme.primary
                            else if (index < currentPage) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        // Content
        when {
            currentPage == 0 -> {
                WelcomePage(
                    onNext = { currentPage++ },
                    hasPermissionsToRequest = permissionCategories.isNotEmpty()
                )
            }
            permissionCategories.isEmpty() -> {
                // All permissions already granted - this shouldn't normally happen
                // as wizard wouldn't be shown, but handle gracefully
                LaunchedEffect(Unit) {
                    onRequestPermissions()
                }
            }
            currentPage <= permissionCategories.size -> {
                val category = permissionCategories[currentPage - 1]
                PermissionCategoryPage(
                    category = category,
                    pageNumber = currentPage,
                    totalPages = permissionCategories.size,
                    onNext = { currentPage++ },
                    onPrevious = { currentPage-- }
                )
            }
            else -> {
                FinalPage(
                    categories = permissionCategories,
                    onGrantPermissions = onRequestPermissions,
                    onPrevious = { currentPage-- }
                )
            }
        }
    }
}

@Composable
private fun WelcomePage(
    onNext: () -> Unit,
    hasPermissionsToRequest: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Flock You",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Surveillance Detection App",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "What This App Does",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Flock You detects surveillance devices around you, including:\n\n" +
                            "• ALPR cameras (license plate readers)\n" +
                            "• Gunshot detectors that also listen for screams\n" +
                            "• Cell site simulators (StingRay/IMSI catchers)\n" +
                            "• Rogue WiFi access points\n" +
                            "• Hidden cameras and tracking beacons\n" +
                            "• Police body cameras and vehicle systems",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "All data stays on your device. No telemetry or cloud uploads.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasPermissionsToRequest) "See Required Permissions" else "Continue")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun PermissionCategoryPage(
    category: PermissionCategory,
    pageNumber: Int,
    totalPages: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    var expandedMethod by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = category.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Permission ${pageNumber} of ${totalPages}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            if (!category.isRequired) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "Optional",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Detection methods list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    text = "Detection Methods Enabled:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(category.detectionMethods) { method ->
                DetectionMethodCard(
                    method = method,
                    isExpanded = expandedMethod == method.name,
                    onToggleExpand = {
                        expandedMethod = if (expandedMethod == method.name) null else method.name
                    }
                )
            }
        }

        // Navigation buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Previous")
            }

            Button(onClick = onNext) {
                Text("Next")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun DetectionMethodCard(
    method: DetectionMethodInfo,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = method.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = method.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Devices Detected
                    InfoSection(
                        icon = Icons.Default.DevicesOther,
                        title = "Devices Detected",
                        items = method.devicesDetected
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // History Info
                    InfoRow(
                        icon = Icons.Default.History,
                        title = "History",
                        content = method.historyInfo
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Nearby Locations
                    InfoRow(
                        icon = Icons.Default.NearMe,
                        title = "Nearby Detection",
                        content = method.nearbyInfo
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Evidence Collected
                    InfoSection(
                        icon = Icons.Default.FindInPage,
                        title = "Evidence Collected",
                        items = method.evidenceCollected
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoSection(
    icon: ImageVector,
    title: String,
    items: List<String>
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        items.forEach { item ->
            Row(
                modifier = Modifier.padding(start = 26.dp, top = 2.dp)
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    content: String
) {
    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FinalPage(
    onGrantPermissions: () -> Unit,
    onPrevious: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Ready to Protect You",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Grant the permissions to start detecting surveillance devices around you.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Summary cards
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "What Happens Next",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                SummaryRow(icon = Icons.Default.LocationOn, text = "Location permission dialog will appear")
                SummaryRow(icon = Icons.Default.Bluetooth, text = "Bluetooth permission dialog will appear")
                SummaryRow(icon = Icons.Default.Notifications, text = "Notification permission dialog will appear")
                SummaryRow(icon = Icons.Default.Mic, text = "Microphone permission dialog will appear")
                SummaryRow(icon = Icons.Default.Phone, text = "Phone state permission dialog will appear")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "After granting permissions, you'll be asked to disable battery optimization so the app can scan reliably in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onGrantPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant All Permissions")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Review Permissions")
        }
    }
}

@Composable
private fun SummaryRow(
    icon: ImageVector,
    text: String
) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * Get all permission categories with their detection methods
 */
private fun getPermissionCategories(): List<PermissionCategory> = listOf(
    // Location Permission
    PermissionCategory(
        id = "location",
        title = "Location Access",
        icon = Icons.Default.LocationOn,
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ),
        detectionMethods = listOf(
            DetectionMethodInfo(
                name = "WiFi Network Scanning",
                description = "Scans WiFi networks to identify surveillance devices by SSID patterns and MAC addresses",
                devicesDetected = listOf(
                    "Flock Safety ALPR cameras",
                    "Penguin/Pigvision surveillance systems",
                    "Police vehicle routers (Sierra Wireless, Cradlepoint)",
                    "Hidden cameras broadcasting WiFi",
                    "Rogue access points / Evil twin attacks",
                    "Surveillance vans with mobile hotspots"
                ),
                historyInfo = "Each WiFi detection is stored with timestamp, location (GPS coordinates), signal strength, and how many times the device has been seen. View in History tab sorted by date or threat level.",
                nearbyInfo = "Real-time list of all WiFi networks currently visible, with threat matches highlighted. Signal strength indicates approximate distance (stronger = closer).",
                evidenceCollected = listOf(
                    "SSID (network name)",
                    "MAC address with manufacturer lookup",
                    "Signal strength (dBm) and estimated distance",
                    "GPS location where detected",
                    "Timestamp of first and last detection",
                    "Number of times seen at this and other locations",
                    "Matched pattern rules that triggered detection"
                )
            ),
            DetectionMethodInfo(
                name = "Bluetooth LE Scanning",
                description = "Scans for Bluetooth Low Energy devices that match surveillance equipment signatures",
                devicesDetected = listOf(
                    "Flock Safety cameras (ALPR)",
                    "Raven gunshot/scream detectors",
                    "Axon body cameras and Signal triggers",
                    "Police radio systems (Motorola APX)",
                    "Emergency vehicle lightbars (Whelen, Federal Signal)",
                    "Cellebrite/GrayKey forensic devices"
                ),
                historyInfo = "BLE detections logged with device name, MAC, service UUIDs, and location. Tracks if device is following you by appearing at multiple locations.",
                nearbyInfo = "Shows all BLE devices broadcasting nearby. Suspicious devices sorted to top. Tap to see detailed characteristics and threat assessment.",
                evidenceCollected = listOf(
                    "Device advertised name",
                    "MAC address (may be randomized)",
                    "BLE Service UUIDs (identifies device capabilities)",
                    "Signal strength and proximity estimate",
                    "GPS location of detection",
                    "For Raven: GPS data, battery status, LTE signal, upload stats (from service UUIDs)",
                    "Advertisement data characteristics"
                )
            ),
            DetectionMethodInfo(
                name = "Location Correlation",
                description = "Correlates detections across time and location to identify devices following you",
                devicesDetected = listOf(
                    "Any tracking device appearing at multiple locations",
                    "Surveillance vehicles following your route",
                    "WiFi networks that shouldn't be mobile but appear in different places"
                ),
                historyInfo = "Map view shows all detection locations. Timeline shows when devices were detected. Alerts when same device seen at 3+ locations.",
                nearbyInfo = "Current location compared against historical detections to identify following patterns.",
                evidenceCollected = listOf(
                    "GPS coordinates of each detection",
                    "Route/path of detections over time",
                    "Time gaps between sightings",
                    "Distance traveled between sightings",
                    "Location clustering analysis"
                )
            )
        )
    ),

    // Bluetooth Permission
    PermissionCategory(
        id = "bluetooth",
        title = "Bluetooth Access",
        icon = Icons.Default.Bluetooth,
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyList()
        },
        minSdk = Build.VERSION_CODES.S,
        detectionMethods = listOf(
            DetectionMethodInfo(
                name = "BLE Device Name Matching",
                description = "Matches Bluetooth device names against known surveillance equipment patterns",
                devicesDetected = listOf(
                    "Raven-XXXX (gunshot detectors)",
                    "Axon Body 3/4 cameras",
                    "WatchGuard body cameras",
                    "Motorola APX radios",
                    "Whelen/Federal Signal lightbars"
                ),
                historyInfo = "All BLE device matches logged with full device information. Search history by device name or type.",
                nearbyInfo = "Live scanning shows device names. Red highlight for known threats, yellow for suspicious patterns.",
                evidenceCollected = listOf(
                    "Exact device name as broadcast",
                    "Pattern rule that matched",
                    "Manufacturer identification",
                    "Time device has been broadcasting",
                    "Signal strength variations over time"
                )
            ),
            DetectionMethodInfo(
                name = "BLE Service UUID Analysis",
                description = "Identifies devices by their Bluetooth service characteristics",
                devicesDetected = listOf(
                    "Raven devices (8 known service UUIDs expose GPS, battery, network status)",
                    "Axon Signal auto-activators",
                    "Law enforcement radio accessories",
                    "Forensic data extraction devices"
                ),
                historyInfo = "Service UUID fingerprints stored for device identification even when names change.",
                nearbyInfo = "UUID analysis runs automatically. Devices with surveillance-associated services flagged immediately.",
                evidenceCollected = listOf(
                    "All service UUIDs advertised",
                    "Characteristic values (if readable)",
                    "For Raven: GPS coords, solar/battery %, LTE carrier, data usage, firmware version",
                    "Device capability fingerprint",
                    "UUID-to-manufacturer correlation"
                )
            ),
            DetectionMethodInfo(
                name = "Body Camera Trigger Detection",
                description = "Detects Axon Signal devices that auto-activate body cameras when sirens or weapons are drawn",
                devicesDetected = listOf(
                    "Axon Signal Sidearm (holster trigger)",
                    "Axon Signal Fleet (vehicle trigger)",
                    "Axon Signal Performance (CEW trigger)",
                    "Any high-frequency BLE advertisement spikes"
                ),
                historyInfo = "Trigger events logged with spike analysis. Shows correlation with nearby body cameras.",
                nearbyInfo = "Monitors advertisement rate. Alert when 20+ packets/second detected (indicates trigger activation).",
                evidenceCollected = listOf(
                    "Advertisement packet rate",
                    "Spike timestamps",
                    "Duration of high-activity period",
                    "Nearby body cameras detected simultaneously",
                    "Pattern correlation with police activity"
                )
            )
        )
    ),

    // Phone State Permission
    PermissionCategory(
        id = "phone_state",
        title = "Phone State (Cellular)",
        icon = Icons.Default.CellTower,
        permissions = listOf(Manifest.permission.READ_PHONE_STATE),
        detectionMethods = listOf(
            DetectionMethodInfo(
                name = "IMSI Catcher (StingRay) Detection",
                description = "Detects cell site simulators that capture phone identifiers and intercept communications",
                devicesDetected = listOf(
                    "StingRay / StingRay II",
                    "Hailstorm (portable StingRay)",
                    "Kingfish (handheld)",
                    "Crossbow (vehicle-mounted)",
                    "Dirtbox (aircraft-mounted)",
                    "Any rogue base station"
                ),
                historyInfo = "Cellular anomalies logged in dedicated timeline. Shows network type changes, signal anomalies, and suspicious tower behavior over time.",
                nearbyInfo = "Real-time monitoring of connected cell tower. Alerts on encryption downgrades, suspicious identifiers, or unusual signal patterns.",
                evidenceCollected = listOf(
                    "Cell tower ID (CID) and Location Area Code (LAC)",
                    "Network type (2G/3G/4G/5G) and changes",
                    "Signal strength and sudden changes (>25dBm spike = suspicious)",
                    "MCC/MNC (carrier) codes - invalid codes indicate fake tower",
                    "Encryption status (2G has weak/no encryption)",
                    "Tower switching frequency and pattern",
                    "Trusted vs untrusted tower classification"
                )
            ),
            DetectionMethodInfo(
                name = "Encryption Downgrade Detection",
                description = "Alerts when phone is forced from 4G/5G to 2G, bypassing modern encryption",
                devicesDetected = listOf(
                    "Cell site simulators forcing 2G",
                    "Rogue base stations",
                    "Network manipulation attacks"
                ),
                historyInfo = "All network type transitions logged. Highlights suspicious downgrades (4G→2G when 4G coverage available).",
                nearbyInfo = "Current network type displayed prominently. Warning banner when on 2G in area with known 4G/5G coverage.",
                evidenceCollected = listOf(
                    "Previous network type",
                    "New network type (2G = weak encryption)",
                    "Time of transition",
                    "Location of downgrade",
                    "Whether voluntary or forced",
                    "Duration on downgraded network"
                )
            ),
            DetectionMethodInfo(
                name = "Rapid Tower Switching Detection",
                description = "Detects abnormal cell tower handoff patterns indicating tracking or interception",
                devicesDetected = listOf(
                    "Mobile IMSI catchers in vehicles",
                    "Portable cell site simulators",
                    "Aircraft-mounted surveillance (Dirtbox)"
                ),
                historyInfo = "Tower change velocity tracked. History shows normal vs anomalous switching patterns.",
                nearbyInfo = "Live counter of tower changes per minute. Alert at 5+ changes/min (stationary) or 12+ changes/min (moving).",
                evidenceCollected = listOf(
                    "Tower IDs in switching sequence",
                    "Time between switches",
                    "User movement during switches (GPS correlation)",
                    "Signal strength of each tower",
                    "Geographic logic of switches",
                    "Comparison to trusted tower database"
                )
            )
        )
    ),

    // Microphone Permission
    PermissionCategory(
        id = "microphone",
        title = "Microphone Access",
        icon = Icons.Default.Mic,
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
        detectionMethods = listOf(
            DetectionMethodInfo(
                name = "Ultrasonic Beacon Detection",
                description = "Detects inaudible ultrasonic signals (18-22 kHz) used for cross-device tracking",
                devicesDetected = listOf(
                    "Silverpush/Alphonso TV beacons (in ads)",
                    "Shopkick retail tracking beacons",
                    "Cross-device tracking infrastructure",
                    "Continuous surveillance audio beacons"
                ),
                historyInfo = "Ultrasonic detections logged with frequency analysis. Shows pattern of beacon encounters over time.",
                nearbyInfo = "Real-time frequency spectrum display for 18-22kHz range. Alert when persistent ultrasonic signals detected.",
                evidenceCollected = listOf(
                    "Detected frequency (Hz)",
                    "Signal strength above noise floor",
                    "Duration of transmission",
                    "Modulation pattern (if determinable)",
                    "GPS location",
                    "Correlation with TV/retail environments",
                    "Time patterns (continuous vs intermittent)"
                )
            ),
            DetectionMethodInfo(
                name = "Cross-Device Tracking Detection",
                description = "Identifies when ultrasonic beacons are being used to link your devices together",
                devicesDetected = listOf(
                    "Advertising SDK tracking (SilverPush, Signal360)",
                    "Smart TV tracking beacons",
                    "Retail location analytics",
                    "Multi-device fingerprinting systems"
                ),
                historyInfo = "Links detected beacons to apps/content that may be emitting them. Timeline correlates with media consumption.",
                nearbyInfo = "Alert when same beacon frequency detected repeatedly, indicating intentional tracking vs random noise.",
                evidenceCollected = listOf(
                    "Beacon signature/pattern",
                    "Apps active during detection",
                    "Media playing during detection",
                    "Duration and repetition",
                    "Other devices potentially affected"
                )
            )
        ),
        isRequired = false // Optional - ultrasonic beacon detection is a nice-to-have
    ),

    // Notification Permission
    PermissionCategory(
        id = "notifications",
        title = "Notifications",
        icon = Icons.Default.Notifications,
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        },
        minSdk = Build.VERSION_CODES.TIRAMISU,
        detectionMethods = listOf(
            DetectionMethodInfo(
                name = "Real-Time Threat Alerts",
                description = "Push notifications when surveillance devices are detected nearby",
                devicesDetected = listOf(
                    "All high-threat devices (ALPR cameras, gunshot detectors)",
                    "Critical threats (IMSI catchers, encryption downgrades)",
                    "Devices following you (appearing at multiple locations)",
                    "Configurable alerts for any threat level"
                ),
                historyInfo = "Notification history stored with full detection details. Tap any notification to see evidence.",
                nearbyInfo = "Notifications include estimated distance and direction when possible.",
                evidenceCollected = listOf(
                    "Notification timestamp",
                    "Detection that triggered alert",
                    "User location at time of alert",
                    "Whether notification was viewed/dismissed",
                    "Actions taken from notification"
                )
            ),
            DetectionMethodInfo(
                name = "Background Scanning Status",
                description = "Persistent notification shows scanning is active and working",
                devicesDetected = listOf(
                    "N/A - Status indicator only"
                ),
                historyInfo = "Service uptime and scan statistics available in notification.",
                nearbyInfo = "Quick-access notification shows last scan time and threat count.",
                evidenceCollected = listOf(
                    "Scan intervals and timing",
                    "Detection counts per session",
                    "Battery usage",
                    "Service health metrics"
                )
            )
        )
    ),

    // Nearby WiFi Devices Permission (Android 13+)
    PermissionCategory(
        id = "nearby_wifi",
        title = "Nearby WiFi Devices",
        icon = Icons.Default.Wifi,
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            emptyList()
        },
        minSdk = Build.VERSION_CODES.TIRAMISU,
        detectionMethods = listOf(
            DetectionMethodInfo(
                name = "Enhanced WiFi Device Detection",
                description = "Improved WiFi scanning capabilities for Android 13+ devices",
                devicesDetected = listOf(
                    "All WiFi-based surveillance devices",
                    "WiFi Direct devices",
                    "Peer-to-peer networks",
                    "IoT cameras and sensors"
                ),
                historyInfo = "Enhanced metadata captured on Android 13+ for better device fingerprinting.",
                nearbyInfo = "More detailed WiFi device information including capabilities and protocols.",
                evidenceCollected = listOf(
                    "WiFi capabilities",
                    "Supported protocols",
                    "Device characteristics",
                    "Connection capabilities",
                    "Extended device metadata"
                )
            ),
            DetectionMethodInfo(
                name = "Rogue Access Point Detection",
                description = "Identifies evil twin attacks and suspicious access points",
                devicesDetected = listOf(
                    "Evil twin APs (same name, different MAC)",
                    "Karma attack APs (respond to all probes)",
                    "Deauth attack sources",
                    "Suspicious mobile hotspots"
                ),
                historyInfo = "AP fingerprints stored for comparison. Alert when known AP appears with new MAC (potential evil twin).",
                nearbyInfo = "Compares visible APs against known legitimate networks. Flags duplicates or anomalies.",
                evidenceCollected = listOf(
                    "SSID and BSSID (MAC)",
                    "Security type (open/WPA/WPA2/WPA3)",
                    "Channel and frequency",
                    "Signal strength",
                    "Vendor from MAC OUI",
                    "Historical AP fingerprint comparison",
                    "Nearby AP deduplication analysis"
                )
            )
        )
    )
)

package com.flockyou.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flockyou.BuildConfig
import com.flockyou.data.PrivacySettings
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.RetentionPeriod
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.EphemeralDetectionRepository
import com.flockyou.service.ScanningService
import com.flockyou.ui.components.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    privacySettingsRepository: PrivacySettingsRepository,
    detectionRepository: DetectionRepository,
    ephemeralRepository: EphemeralDetectionRepository,
    onNavigateBack: () -> Unit
) {
    @Suppress("UNUSED_VARIABLE")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by privacySettingsRepository.settings.collectAsState(initial = PrivacySettings())

    var showQuickWipeDialog by remember { mutableStateOf(false) }
    var showUltrasonicConsentDialog by remember { mutableStateOf(false) }
    var showUltrasonicRevokeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Data") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Priority 1: Ephemeral Mode
            item {
                SectionHeader(title = "Memory-Only Mode")
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.ephemeralModeEnabled)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = if (settings.ephemeralModeEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ephemeral Mode",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (settings.ephemeralModeEnabled)
                                        "Detections stored in RAM only - cleared on restart"
                                    else
                                        "Detections saved to encrypted database",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.ephemeralModeEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        privacySettingsRepository.setEphemeralModeEnabled(enabled)
                                        if (enabled) {
                                            // Clear persistent storage when enabling ephemeral mode
                                            detectionRepository.deleteAllDetections()
                                        } else {
                                            // Clear ephemeral storage when disabling
                                            ephemeralRepository.clearAll()
                                        }
                                    }
                                }
                            )
                        }
                        if (settings.ephemeralModeEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "No detection data is written to disk. All history is lost when the service restarts.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Priority 2: Quick Wipe
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Quick Wipe")
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ),
                    onClick = { showQuickWipeDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wipe All Data Now",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "Immediately delete all detection history",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.QuestionMark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Require Confirmation",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Ask before wiping via Quick Settings tile",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.quickWipeRequiresConfirmation,
                            onCheckedChange = { required ->
                                scope.launch {
                                    privacySettingsRepository.setQuickWipeRequiresConfirmation(required)
                                }
                            }
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add \"Quick Wipe\" to your Quick Settings panel for one-tap data deletion.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Priority 3: Data Retention
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Data Retention")
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Auto-Delete History After",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Current: ${settings.retentionPeriod.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        RetentionPeriod.entries.forEach { period ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            privacySettingsRepository.setRetentionPeriod(period)
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = settings.retentionPeriod == period,
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = period.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (period == RetentionPeriod.THREE_DAYS) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = "Default",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Priority 4: Location Storage
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Location Privacy")
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOff,
                                contentDescription = null,
                                tint = if (!settings.storeLocationWithDetections)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Store Location with Detections",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (settings.storeLocationWithDetections)
                                        "GPS coordinates saved with detections"
                                    else
                                        "No location data stored",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.storeLocationWithDetections,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        privacySettingsRepository.setStoreLocationWithDetections(enabled)
                                    }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (BuildConfig.IS_OEM_BUILD)
                                "Default: OFF for OEM installations"
                            else
                                "Default: ON for sideloaded installations",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Priority 5: Screen Lock Auto-Purge
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Screen Lock Protection")
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.autoPurgeOnScreenLock)
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ScreenLockPortrait,
                                contentDescription = null,
                                tint = if (settings.autoPurgeOnScreenLock)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Purge on Screen Lock",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (settings.autoPurgeOnScreenLock)
                                        "All data deleted when screen locks"
                                    else
                                        "Data persists through screen locks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.autoPurgeOnScreenLock,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        privacySettingsRepository.setAutoPurgeOnScreenLock(enabled)
                                    }
                                }
                            )
                        }
                        if (settings.autoPurgeOnScreenLock) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "All detection history will be permanently deleted every time your screen locks.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Ultrasonic Detection Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Ultrasonic Detection")
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.ultrasonicDetectionEnabled)
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (settings.ultrasonicDetectionEnabled)
                                    MaterialTheme.colorScheme.tertiary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ultrasonic Beacon Detection",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (settings.ultrasonicDetectionEnabled)
                                        "Actively scanning for tracking beacons"
                                    else if (settings.ultrasonicConsentAcknowledged)
                                        "Paused - tap to enable"
                                    else
                                        "Requires opt-in consent",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.ultrasonicDetectionEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (settings.ultrasonicConsentAcknowledged) {
                                            // Already consented, just enable
                                            scope.launch {
                                                privacySettingsRepository.setUltrasonicDetectionEnabled(true)
                                            }
                                        } else {
                                            // Show consent dialog
                                            showUltrasonicConsentDialog = true
                                        }
                                    } else {
                                        // Disable without revoking consent
                                        scope.launch {
                                            privacySettingsRepository.disableUltrasonic()
                                        }
                                    }
                                }
                            )
                        }

                        // Warning card when enabled
                        if (settings.ultrasonicDetectionEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Audio Security",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Audio is encrypted in memory (AES-256-GCM) and analyzed for frequencies only. No audio is recorded or stored.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Info card when not enabled
                        if (!settings.ultrasonicDetectionEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "What is ultrasonic tracking?",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Some apps and ads emit inaudible sounds (18-22 kHz) to track you across devices. This feature detects beacons from companies like SilverPush, Alphonso, and retail tracking systems.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Revoke consent option
                        if (settings.ultrasonicConsentAcknowledged) {
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(
                                onClick = { showUltrasonicRevokeDialog = true },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    text = "Revoke Consent",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Quick Wipe Confirmation Dialog
    if (showQuickWipeDialog) {
        AlertDialog(
            onDismissRequest = { showQuickWipeDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete All Data?") },
            text = {
                Text(
                    "This will permanently delete ALL detection history. This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showQuickWipeDialog = false
                        scope.launch {
                            // Clear all data
                            detectionRepository.deleteAllDetections()
                            ephemeralRepository.clearAll()
                            ScanningService.clearSeenDevices()
                            ScanningService.clearCellularHistory()
                            ScanningService.clearSatelliteHistory()
                            ScanningService.clearErrors()
                            ScanningService.clearLearnedSignatures()
                            ScanningService.detectionCount.value = 0
                            ScanningService.lastDetection.value = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickWipeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Ultrasonic Consent Dialog - Full disclosure of risks
    if (showUltrasonicConsentDialog) {
        AlertDialog(
            onDismissRequest = { showUltrasonicConsentDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Enable Ultrasonic Detection?")
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "This feature uses your microphone to detect inaudible ultrasonic tracking beacons (18-22 kHz).",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // What it detects
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "What It Detects",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "- SilverPush ad tracking beacons\n" +
                                    "- Alphonso TV tracking signals\n" +
                                    "- Retail tracking (Shopkick, etc.)\n" +
                                    "- Cross-device tracking beacons\n" +
                                    "- Unknown ultrasonic transmissions",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Security measures
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Security Measures",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "- Audio encrypted in memory (AES-256-GCM)\n" +
                                    "- Hardware-backed encryption keys\n" +
                                    "- Frequency analysis only (no speech)\n" +
                                    "- No audio stored to disk\n" +
                                    "- Data cleared after each scan",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Risks
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Understand the Risks",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "- Microphone must be active during scans\n" +
                                    "- Slightly increased battery usage\n" +
                                    "- Other apps may detect mic usage\n" +
                                    "- False positives possible (some electronics emit ultrasonic noise)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "By enabling this feature, you acknowledge that you understand how it works and accept the associated risks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUltrasonicConsentDialog = false
                        scope.launch {
                            privacySettingsRepository.enableUltrasonicWithConsent()
                        }
                    }
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("I Understand, Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUltrasonicConsentDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Revoke Consent Dialog
    if (showUltrasonicRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showUltrasonicRevokeDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Revoke Ultrasonic Consent?") },
            text = {
                Text(
                    "This will disable ultrasonic detection and revoke your consent. " +
                        "You will need to read and accept the consent dialog again to re-enable this feature."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUltrasonicRevokeDialog = false
                        scope.launch {
                            privacySettingsRepository.revokeUltrasonicConsent()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke Consent")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUltrasonicRevokeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}


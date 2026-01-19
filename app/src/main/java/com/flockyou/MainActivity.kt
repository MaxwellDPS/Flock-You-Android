package com.flockyou

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.flockyou.data.SecuritySettings
import com.flockyou.privilege.PrivilegeMode
import com.flockyou.privilege.PrivilegeModeDetector
import com.flockyou.privilege.SystemPermissionHelper
import com.flockyou.security.AppLockManager
import com.flockyou.ui.screens.LockScreen
import com.flockyou.ui.screens.MainScreen
import com.flockyou.ui.screens.MapScreen
import com.flockyou.ui.screens.SettingsScreen
import com.flockyou.ui.screens.NearbyDevicesScreen
import com.flockyou.ui.screens.DetectionPatternsScreen
import com.flockyou.ui.screens.NotificationSettingsScreen
import com.flockyou.ui.screens.RuleSettingsScreen
import com.flockyou.ui.screens.AllDetectionsScreen
import com.flockyou.ui.screens.DetectionSettingsScreen
import com.flockyou.ui.screens.SecuritySettingsScreen
import com.flockyou.ui.screens.PrivacySettingsScreen
import com.flockyou.ui.screens.PermissionSetupWizard
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.EphemeralDetectionRepository
import com.flockyou.security.DuressAuthenticator
import com.flockyou.ui.screens.NukeSettingsScreen
import com.flockyou.ui.screens.RfDetectionScreen
import com.flockyou.ui.screens.UltrasonicDetectionScreen
import com.flockyou.ui.screens.SatelliteDetectionScreen
import com.flockyou.ui.screens.WifiSecurityScreen
import com.flockyou.ui.screens.AiSettingsScreen
import com.flockyou.ui.screens.ServiceHealthStatusScreen
import com.flockyou.ui.theme.FlockYouTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var privacySettingsRepository: PrivacySettingsRepository

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var ephemeralRepository: EphemeralDetectionRepository

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    @Inject
    lateinit var duressAuthenticator: DuressAuthenticator

    private var permissionsGranted by mutableStateOf(false)
    private var batteryOptimizationChecked by mutableStateOf(false)
    private var showLockScreen by mutableStateOf(true)

    // Privilege mode detection
    private lateinit var privilegeMode: PrivilegeMode
    private var isPrivilegedMode by mutableStateOf(false)

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO) // For ultrasonic beacon detection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        if (permissionsGranted) {
            checkBatteryOptimization()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Detect privilege mode at startup
        privilegeMode = PrivilegeModeDetector.detect(this)
        isPrivilegedMode = privilegeMode.isPrivileged
        Log.i(TAG, "Privilege mode detected: $privilegeMode")
        Log.i(TAG, "Build mode: ${BuildConfig.BUILD_MODE} (system=${BuildConfig.IS_SYSTEM_BUILD}, oem=${BuildConfig.IS_OEM_BUILD})")

        // For privileged modes, skip permission screens if permissions are already granted
        if (isPrivilegedMode && SystemPermissionHelper.shouldSkipPermissionRequests(this)) {
            Log.i(TAG, "Privileged mode: skipping permission screens (permissions pre-granted)")
            permissionsGranted = true
            batteryOptimizationChecked = true
        } else {
            checkPermissions()
        }

        setContent {
            FlockYouTheme {
                val securitySettings by appLockManager.settings.collectAsState(initial = SecuritySettings())
                val scope = rememberCoroutineScope()
                val lifecycleOwner = LocalLifecycleOwner.current

                // Handle app lifecycle for lock screen
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> {
                                appLockManager.onAppBackgrounded()
                            }
                            Lifecycle.Event.ON_START -> {
                                scope.launch {
                                    appLockManager.onAppForegrounded()
                                    showLockScreen = appLockManager.shouldShowLockScreen()
                                }
                            }
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Initial lock screen check
                LaunchedEffect(Unit) {
                    showLockScreen = appLockManager.shouldShowLockScreen()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        !permissionsGranted -> {
                            PermissionSetupWizard(
                                onRequestPermissions = { requestPermissions() },
                                onRequestBackgroundLocation = { requestBackgroundLocation() }
                            )
                        }
                        !batteryOptimizationChecked -> {
                            BatteryOptimizationScreen(
                                isOptimizationDisabled = isBatteryOptimizationDisabled(),
                                onRequestDisable = { requestDisableBatteryOptimization() },
                                onSkip = { batteryOptimizationChecked = true }
                            )
                        }
                        showLockScreen -> {
                            LockScreen(
                                appLockManager = appLockManager,
                                settings = securitySettings,
                                onUnlocked = { showLockScreen = false }
                            )
                        }
                        else -> {
                            AppNavigation(
                                appLockManager = appLockManager,
                                privacySettingsRepository = privacySettingsRepository,
                                detectionRepository = detectionRepository,
                                ephemeralRepository = ephemeralRepository,
                                nukeSettingsRepository = nukeSettingsRepository,
                                duressAuthenticator = duressAuthenticator
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check battery optimization when returning to app (user may have just changed it)
        if (permissionsGranted && !batteryOptimizationChecked) {
            if (isBatteryOptimizationDisabled()) {
                batteryOptimizationChecked = true
            }
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkBatteryOptimization() {
        batteryOptimizationChecked = isBatteryOptimizationDisabled()
    }

    @SuppressLint("BatteryLife")
    private fun requestDisableBatteryOptimization() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
    
    private fun checkPermissions() {
        permissionsGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions.toTypedArray())
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }
}

@Composable
fun AppNavigation(
    appLockManager: AppLockManager,
    privacySettingsRepository: PrivacySettingsRepository,
    detectionRepository: DetectionRepository,
    ephemeralRepository: EphemeralDetectionRepository,
    nukeSettingsRepository: NukeSettingsRepository,
    duressAuthenticator: DuressAuthenticator
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            MainScreen(
                onNavigateToMap = { navController.navigate("map") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToNearby = { navController.navigate("nearby") },
                onNavigateToRfDetection = { navController.navigate("rf_detection") },
                onNavigateToUltrasonicDetection = { navController.navigate("ultrasonic_detection") },
                onNavigateToSatelliteDetection = { navController.navigate("satellite_detection") },
                onNavigateToWifiSecurity = { navController.navigate("wifi_security") },
                onNavigateToServiceHealth = { navController.navigate("service_health") }
            )
        }
        composable("map") {
            MapScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToNotifications = { navController.navigate("notifications") },
                onNavigateToDetectionSettings = { navController.navigate("detection_settings") },
                onNavigateToSecurity = { navController.navigate("security") },
                onNavigateToPrivacy = { navController.navigate("privacy") },
                onNavigateToNuke = { navController.navigate("nuke_settings") },
                onNavigateToAllDetections = { navController.navigate("all_detections") },
                onNavigateToAiSettings = { navController.navigate("ai_settings") },
                onNavigateToServiceHealth = { navController.navigate("service_health") }
            )
        }
        composable("nearby") {
            NearbyDevicesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("patterns") {
            DetectionPatternsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("notifications") {
            NotificationSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("rules") {
            RuleSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("all_detections") {
            AllDetectionsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("detection_settings") {
            DetectionSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("security") {
            SecuritySettingsScreen(
                appLockManager = appLockManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("privacy") {
            PrivacySettingsScreen(
                privacySettingsRepository = privacySettingsRepository,
                detectionRepository = detectionRepository,
                ephemeralRepository = ephemeralRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("nuke_settings") {
            NukeSettingsScreen(
                nukeSettingsRepository = nukeSettingsRepository,
                appLockManager = appLockManager,
                duressAuthenticator = duressAuthenticator,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("rf_detection") {
            RfDetectionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("ultrasonic_detection") {
            UltrasonicDetectionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("satellite_detection") {
            SatelliteDetectionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("wifi_security") {
            WifiSecurityScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("ai_settings") {
            AiSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("service_health") {
            ServiceHealthStatusScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun PermissionScreen(
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Flock You needs the following permissions to detect surveillance devices:",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        PermissionItem(
            icon = Icons.Default.LocationOn,
            title = "Location (Always)",
            description = "Required for WiFi/Bluetooth scanning and background detection. Location is stored locally with detections."
        )
        
        PermissionItem(
            icon = Icons.Default.Bluetooth,
            title = "Bluetooth",
            description = "Scan for BLE surveillance devices"
        )
        
        PermissionItem(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            description = "Alert you when devices are detected"
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Permissions")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Your privacy is important. All scanning happens locally on your device.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PermissionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun BatteryOptimizationScreen(
    isOptimizationDisabled: Boolean,
    onRequestDisable: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.BatteryChargingFull,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = if (isOptimizationDisabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (isOptimizationDisabled) "Battery Optimization Disabled"
            else "Disable Battery Optimization",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isOptimizationDisabled) {
            Text(
                text = "Flock You can now run reliably in the background without being killed by Android's battery management.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Continue")
            }
        } else {
            Text(
                text = "For reliable background scanning, Flock You needs to be exempt from battery optimization. " +
                        "This prevents Android from killing the scanning service when the app is in the background.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Without this, surveillance devices may go undetected while your screen is off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestDisable,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disable Battery Optimization")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip for now")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "This is a sideloaded app setting that allows continuous background operation.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Composable showing the current privilege mode and capabilities.
 * This can be shown in settings or as a debug screen.
 */
@Composable
fun PrivilegeModeInfoCard(
    privilegeMode: PrivilegeMode,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (privilegeMode) {
                is PrivilegeMode.OEM -> MaterialTheme.colorScheme.primaryContainer
                is PrivilegeMode.System -> MaterialTheme.colorScheme.secondaryContainer
                is PrivilegeMode.Sideload -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (privilegeMode) {
                        is PrivilegeMode.OEM -> Icons.Default.VerifiedUser
                        is PrivilegeMode.System -> Icons.Default.AdminPanelSettings
                        is PrivilegeMode.Sideload -> Icons.Default.Security
                    },
                    contentDescription = null,
                    tint = when (privilegeMode) {
                        is PrivilegeMode.OEM -> MaterialTheme.colorScheme.primary
                        is PrivilegeMode.System -> MaterialTheme.colorScheme.secondary
                        is PrivilegeMode.Sideload -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = when (privilegeMode) {
                            is PrivilegeMode.OEM -> "OEM Mode"
                            is PrivilegeMode.System -> "System Mode"
                            is PrivilegeMode.Sideload -> "Standard Mode"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = PrivilegeModeDetector.getModeDescription(privilegeMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))

            // Show capabilities
            val capabilities = PrivilegeModeDetector.getCapabilitiesSummary(privilegeMode)
            capabilities.forEach { (name, available) ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (available) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

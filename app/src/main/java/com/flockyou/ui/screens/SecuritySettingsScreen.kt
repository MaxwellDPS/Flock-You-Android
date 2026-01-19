package com.flockyou.ui.screens

import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flockyou.data.LockMethod
import com.flockyou.data.SecuritySettings
import com.flockyou.security.AppLockManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    appLockManager: AppLockManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by appLockManager.settings.collectAsState(initial = SecuritySettings())

    var showSetPinDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showRemovePinDialog by remember { mutableStateOf(false) }

    val biometricManager = remember { BiometricManager.from(context) }
    val canUseBiometric = remember {
        biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    val biometricStatus = remember {
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> "Available"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No hardware"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Unavailable"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Not enrolled"
            else -> "Unknown"
        }
    }

    val isPinSet = appLockManager.isPinSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
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
            // App Lock Section
            item {
                Text(
                    text = "APP LOCK",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
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
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (settings.appLockEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable App Lock",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (settings.appLockEnabled)
                                    "App is protected"
                                else
                                    "Require authentication to access app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.appLockEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    if (enabled && !isPinSet && !canUseBiometric) {
                                        showSetPinDialog = true
                                    } else {
                                        appLockManager.settings.collect { }
                                        // Update via repository
                                    }
                                }
                            },
                            enabled = isPinSet || canUseBiometric || settings.appLockEnabled
                        )
                    }
                }
            }

            // Lock Method
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Lock Method",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // PIN option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isPinSet) {
                                    scope.launch {
                                        // Set lock method to PIN
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.lockMethod == LockMethod.PIN,
                                onClick = null,
                                enabled = isPinSet
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "PIN",
                                    color = if (isPinSet) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (isPinSet) "PIN is set" else "Set up PIN first",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Biometric option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canUseBiometric) {
                                    scope.launch {
                                        // Set lock method to biometric
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.lockMethod == LockMethod.BIOMETRIC,
                                onClick = null,
                                enabled = canUseBiometric
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Biometric",
                                    color = if (canUseBiometric) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Status: $biometricStatus",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // PIN or Biometric option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isPinSet && canUseBiometric) {
                                    scope.launch {
                                        // Set lock method to PIN or biometric
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.lockMethod == LockMethod.PIN_OR_BIOMETRIC,
                                onClick = null,
                                enabled = isPinSet && canUseBiometric
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "PIN or Biometric",
                                    color = if (isPinSet && canUseBiometric) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Use either method to unlock",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // PIN Management
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "PIN MANAGEMENT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                if (isPinSet) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showChangePinDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Change PIN",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Update your security PIN",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSetPinDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Set Up PIN",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Create a 4-8 digit PIN",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isPinSet) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRemovePinDialog = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Remove PIN",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Delete your PIN (disables PIN-based lock)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Lock Behavior
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "LOCK BEHAVIOR",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
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
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Lock on Background",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Lock when app goes to background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.lockOnBackground,
                            onCheckedChange = { _ ->
                                scope.launch {
                                    // Update lock on background setting
                                }
                            }
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Lock Timeout",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = when (settings.lockTimeoutSeconds) {
                                        0 -> "Lock immediately"
                                        -1 -> "Only on app restart"
                                        else -> "Lock after ${settings.lockTimeoutSeconds} seconds"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Slider(
                            value = settings.lockTimeoutSeconds.toFloat(),
                            onValueChange = { _ ->
                                scope.launch {
                                    // Update timeout
                                }
                            },
                            valueRange = 0f..120f,
                            steps = 7 // 0, 15, 30, 45, 60, 75, 90, 105, 120
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Immediate",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "2 min",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Set PIN Dialog
    if (showSetPinDialog) {
        SetPinDialog(
            onDismiss = { showSetPinDialog = false },
            onPinSet = { pin ->
                if (appLockManager.setPin(pin)) {
                    showSetPinDialog = false
                }
            }
        )
    }

    // Change PIN Dialog
    if (showChangePinDialog) {
        ChangePinDialog(
            appLockManager = appLockManager,
            onDismiss = { showChangePinDialog = false },
            onPinChanged = {
                showChangePinDialog = false
            }
        )
    }

    // Remove PIN Dialog
    if (showRemovePinDialog) {
        RemovePinDialog(
            appLockManager = appLockManager,
            onDismiss = { showRemovePinDialog = false },
            onPinRemoved = {
                showRemovePinDialog = false
            }
        )
    }
}

@Composable
private fun SetPinDialog(
    onDismiss: () -> Unit,
    onPinSet: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(1) } // 1 = enter, 2 = confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(if (step == 1) "Set PIN" else "Confirm PIN") },
        text = {
            Column {
                Text(
                    text = if (step == 1) "Enter a 4-8 digit PIN" else "Re-enter your PIN to confirm",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = if (step == 1) pin else confirmPin,
                    onValueChange = { value ->
                        if (value.length <= 8 && value.all { it.isDigit() }) {
                            if (step == 1) pin = value else confirmPin = value
                            error = null
                        }
                    },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (step == 1) {
                        if (pin.length < 4) {
                            error = "PIN must be at least 4 digits"
                        } else {
                            step = 2
                        }
                    } else {
                        if (confirmPin != pin) {
                            error = "PINs don't match"
                            confirmPin = ""
                        } else {
                            onPinSet(pin)
                        }
                    }
                }
            ) {
                Text(if (step == 1) "Next" else "Set PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (step == 2) {
                    step = 1
                    confirmPin = ""
                    error = null
                } else {
                    onDismiss()
                }
            }) {
                Text(if (step == 2) "Back" else "Cancel")
            }
        }
    )
}

@Composable
private fun ChangePinDialog(
    appLockManager: AppLockManager,
    onDismiss: () -> Unit,
    onPinChanged: () -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(1) } // 1 = current, 2 = new, 3 = confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
        title = {
            Text(
                when (step) {
                    1 -> "Enter Current PIN"
                    2 -> "Enter New PIN"
                    else -> "Confirm New PIN"
                }
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = when (step) {
                        1 -> currentPin
                        2 -> newPin
                        else -> confirmPin
                    },
                    onValueChange = { value ->
                        if (value.length <= 8 && value.all { it.isDigit() }) {
                            when (step) {
                                1 -> currentPin = value
                                2 -> newPin = value
                                else -> confirmPin = value
                            }
                            error = null
                        }
                    },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (step) {
                        1 -> {
                            if (!appLockManager.verifyPin(currentPin)) {
                                error = "Incorrect PIN"
                                currentPin = ""
                            } else {
                                step = 2
                            }
                        }
                        2 -> {
                            if (newPin.length < 4) {
                                error = "PIN must be at least 4 digits"
                            } else {
                                step = 3
                            }
                        }
                        else -> {
                            if (confirmPin != newPin) {
                                error = "PINs don't match"
                                confirmPin = ""
                            } else {
                                appLockManager.setPin(newPin)
                                onPinChanged()
                            }
                        }
                    }
                }
            ) {
                Text(if (step == 3) "Change PIN" else "Next")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (step > 1) {
                    step--
                    error = null
                } else {
                    onDismiss()
                }
            }) {
                Text(if (step > 1) "Back" else "Cancel")
            }
        }
    )
}

@Composable
private fun RemovePinDialog(
    appLockManager: AppLockManager,
    onDismiss: () -> Unit,
    onPinRemoved: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Remove PIN?") },
        text = {
            Column {
                Text(
                    text = "Enter your current PIN to confirm removal. This will disable PIN-based app lock.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        if (value.length <= 8 && value.all { it.isDigit() }) {
                            pin = value
                            error = null
                        }
                    },
                    label = { Text("Current PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (appLockManager.verifyPin(pin)) {
                        appLockManager.removePin()
                        onPinRemoved()
                    } else {
                        error = "Incorrect PIN"
                        pin = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Remove PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

package com.flockyou.ui.screens

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.flockyou.data.LockMethod
import com.flockyou.data.SecuritySettings
import com.flockyou.security.AppLockManager
import kotlinx.coroutines.delay

private const val TAG = "LockScreen"

@Composable
fun LockScreen(
    appLockManager: AppLockManager,
    settings: SecuritySettings,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    var enteredPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLockedOut by remember { mutableStateOf(appLockManager.isLockedOut()) }
    var remainingLockoutTime by remember { mutableStateOf(0L) }

    // Update lockout status
    LaunchedEffect(Unit) {
        while (true) {
            isLockedOut = appLockManager.isLockedOut()
            remainingLockoutTime = appLockManager.getRemainingLockoutTime()
            if (!isLockedOut) break
            delay(1000)
        }
    }

    // Auto-trigger biometric if enabled
    LaunchedEffect(settings.lockMethod) {
        if (settings.lockMethod == LockMethod.BIOMETRIC ||
            settings.lockMethod == LockMethod.PIN_OR_BIOMETRIC) {
            triggerBiometric(context, appLockManager, onUnlocked) { errorMsg ->
                error = errorMsg
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Flock You is Locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (settings.lockMethod) {
                    LockMethod.PIN -> "Enter your PIN to unlock"
                    LockMethod.BIOMETRIC -> "Use biometrics to unlock"
                    LockMethod.PIN_OR_BIOMETRIC -> "Enter PIN or use biometrics"
                    else -> "Unlock to continue"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (isLockedOut) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Too many failed attempts",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Try again in ${remainingLockoutTime / 1000} seconds",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            } else {
                // PIN display
                if (settings.lockMethod != LockMethod.BIOMETRIC) {
                    PinDisplay(
                        length = enteredPin.length,
                        maxLength = 8
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Error message
                    error?.let { errorMsg ->
                        Text(
                            text = errorMsg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Number pad
                    NumberPad(
                        onNumberClick = { number ->
                            if (enteredPin.length < 8) {
                                enteredPin += number
                                error = null

                                // Auto-verify when 4+ digits entered
                                if (enteredPin.length >= 4) {
                                    if (appLockManager.verifyPin(enteredPin)) {
                                        onUnlocked()
                                    } else {
                                        val attempts = appLockManager.getFailedAttempts()
                                        error = if (attempts >= 5) {
                                            "Too many attempts. Please wait."
                                        } else {
                                            "Incorrect PIN (${5 - attempts} attempts remaining)"
                                        }
                                        enteredPin = ""
                                        isLockedOut = appLockManager.isLockedOut()
                                    }
                                }
                            }
                        },
                        onBackspaceClick = {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                            }
                        },
                        onBiometricClick = if (settings.lockMethod == LockMethod.PIN_OR_BIOMETRIC) {
                            {
                                triggerBiometric(context, appLockManager, onUnlocked) { errorMsg ->
                                    error = errorMsg
                                }
                            }
                        } else null
                    )
                } else {
                    // Biometric only
                    Button(
                        onClick = {
                            triggerBiometric(context, appLockManager, onUnlocked) { errorMsg ->
                                error = errorMsg
                            }
                        }
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Authenticate with Biometrics")
                    }

                    error?.let { errorMsg ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = errorMsg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDisplay(
    length: Int,
    maxLength: Int
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(maxLength.coerceAtMost(8)) { index ->
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < length) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

@Composable
private fun NumberPad(
    onNumberClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    onBiometricClick: (() -> Unit)?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Row 1-2-3
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NumberButton("1", onClick = { onNumberClick("1") })
            NumberButton("2", onClick = { onNumberClick("2") })
            NumberButton("3", onClick = { onNumberClick("3") })
        }

        // Row 4-5-6
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NumberButton("4", onClick = { onNumberClick("4") })
            NumberButton("5", onClick = { onNumberClick("5") })
            NumberButton("6", onClick = { onNumberClick("6") })
        }

        // Row 7-8-9
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NumberButton("7", onClick = { onNumberClick("7") })
            NumberButton("8", onClick = { onNumberClick("8") })
            NumberButton("9", onClick = { onNumberClick("9") })
        }

        // Row biometric/empty - 0 - backspace
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (onBiometricClick != null) {
                IconButton(
                    onClick = onBiometricClick,
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Biometric",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(80.dp))
            }

            NumberButton("0", onClick = { onNumberClick("0") })

            IconButton(
                onClick = onBackspaceClick,
                modifier = Modifier.size(80.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NumberButton(
    number: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(80.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = number,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun triggerBiometric(
    context: android.content.Context,
    appLockManager: AppLockManager,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val activity = context as? FragmentActivity
    if (activity == null) {
        Log.e(TAG, "Context is not a FragmentActivity")
        onError("Biometric authentication unavailable")
        return
    }

    val biometricManager = BiometricManager.from(context)
    when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
        BiometricManager.BIOMETRIC_SUCCESS -> {
            val executor = ContextCompat.getMainExecutor(context)
            val biometricPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        appLockManager.unlock()
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            onError(errString.toString())
                        }
                    }

                    override fun onAuthenticationFailed() {
                        onError("Biometric not recognized")
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Flock You")
                .setSubtitle("Authenticate to access the app")
                .setNegativeButtonText("Use PIN")
                .build()

            biometricPrompt.authenticate(promptInfo)
        }
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
            onError("No biometric hardware available")
        }
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
            onError("Biometric hardware unavailable")
        }
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
            onError("No biometrics enrolled. Please set up in device settings.")
        }
        else -> {
            onError("Biometric authentication unavailable")
        }
    }
}

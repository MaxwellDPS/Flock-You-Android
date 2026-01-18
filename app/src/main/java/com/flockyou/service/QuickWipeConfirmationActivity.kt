package com.flockyou.service

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.EphemeralDetectionRepository
import com.flockyou.ui.theme.FlockYouTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Transparent confirmation activity for Quick Wipe.
 * Shows a dialog overlay for user confirmation before wiping data.
 */
@AndroidEntryPoint
class QuickWipeConfirmationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuickWipeConfirmation"
    }

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var ephemeralRepository: EphemeralDetectionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FlockYouTheme {
                QuickWipeConfirmationDialog(
                    onConfirm = {
                        performQuickWipe()
                    },
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }

    private fun performQuickWipe() {
        Log.w(TAG, "Performing Quick Wipe from confirmation dialog")

        lifecycleScope.launch {
            try {
                // Clear persistent database
                detectionRepository.deleteAllDetections()

                // Clear ephemeral storage
                ephemeralRepository.clearAll()

                // Clear service runtime data
                ScanningService.clearSeenDevices()
                ScanningService.clearCellularHistory()
                ScanningService.clearSatelliteHistory()
                ScanningService.clearErrors()
                ScanningService.clearLearnedSignatures()
                ScanningService.detectionCount.value = 0
                ScanningService.lastDetection.value = null

                Log.i(TAG, "Quick Wipe completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error during Quick Wipe", e)
            }

            finish()
        }
    }
}

@Composable
fun QuickWipeConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var isWiping by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isWiping) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Quick Wipe",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "This will permanently delete ALL detection history.",
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "This action cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isWiping = true
                    onConfirm()
                },
                enabled = !isWiping,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                if (isWiping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Wiping...")
                } else {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Wipe All Data")
                }
            }
        },
        dismissButton = {
            if (!isWiping) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

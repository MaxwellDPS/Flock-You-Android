package com.flockyou.ui.screens

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.scanner.flipper.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class FlipperSettingsViewModel @Inject constructor(
    private val settingsRepository: FlipperSettingsRepository,
    private val flipperScannerManager: FlipperScannerManager
) : ViewModel() {

    companion object {
        private const val TAG = "FlipperSettingsVM"
        private const val FAP_ASSET_PATH = "flipper/flock_bridge.fap"
        private const val FAP_DEST_PATH = "/ext/apps/Tools/flock_bridge.fap"
    }

    val settings: StateFlow<FlipperSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, FlipperSettings())

    // Map the existing enum to our UI-friendly sealed class
    val connectionState: StateFlow<ConnectionState> = flipperScannerManager.connectionState
        .combine(flipperScannerManager.connectionType) { state, type ->
            when (state) {
                FlipperConnectionState.DISCONNECTED -> ConnectionState.Disconnected
                FlipperConnectionState.CONNECTING -> ConnectionState.Connecting
                FlipperConnectionState.DISCOVERING_SERVICES -> ConnectionState.Connecting
                FlipperConnectionState.CONNECTED, FlipperConnectionState.READY -> {
                    val typeString = when (type) {
                        FlipperClient.ConnectionType.USB -> "USB"
                        FlipperClient.ConnectionType.BLUETOOTH -> "Bluetooth"
                        FlipperClient.ConnectionType.NONE -> "Unknown"
                    }
                    ConnectionState.Connected(typeString)
                }
                FlipperConnectionState.ERROR -> ConnectionState.Error("Connection error")
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    private val _installProgress = MutableStateFlow<String?>(null)
    val installProgress: StateFlow<String?> = _installProgress.asStateFlow()

    // UI-friendly connection state for the screen
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val connectionType: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    // Connection methods
    fun connect() {
        viewModelScope.launch {
            flipperScannerManager.connect()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            flipperScannerManager.disconnect()
        }
    }

    // Settings methods
    suspend fun setFlipperEnabled(enabled: Boolean) {
        settingsRepository.setFlipperEnabled(enabled)
        if (enabled) {
            flipperScannerManager.start()
        } else {
            flipperScannerManager.stop()
        }
    }

    suspend fun setAutoConnectUsb(enabled: Boolean) {
        settingsRepository.setAutoConnectUsb(enabled)
    }

    suspend fun setAutoConnectBluetooth(enabled: Boolean) {
        settingsRepository.setAutoConnectBluetooth(enabled)
    }

    suspend fun setPreferredConnection(preference: FlipperConnectionPreference) {
        settingsRepository.setPreferredConnection(preference)
    }

    // Scan toggles
    suspend fun setEnableWifiScanning(enabled: Boolean) {
        settingsRepository.setEnableWifiScanning(enabled)
    }

    suspend fun setEnableSubGhzScanning(enabled: Boolean) {
        settingsRepository.setEnableSubGhzScanning(enabled)
    }

    suspend fun setEnableBleScanning(enabled: Boolean) {
        settingsRepository.setEnableBleScanning(enabled)
    }

    suspend fun setEnableIrScanning(enabled: Boolean) {
        settingsRepository.setEnableIrScanning(enabled)
    }

    suspend fun setEnableNfcScanning(enabled: Boolean) {
        settingsRepository.setEnableNfcScanning(enabled)
    }

    // WIPS settings
    suspend fun setWipsEnabled(enabled: Boolean) {
        settingsRepository.setWipsEnabled(enabled)
    }

    suspend fun setWipsEvilTwinDetection(enabled: Boolean) {
        settingsRepository.setWipsEvilTwinDetection(enabled)
    }

    suspend fun setWipsDeauthDetection(enabled: Boolean) {
        settingsRepository.setWipsDeauthDetection(enabled)
    }

    suspend fun setWipsKarmaDetection(enabled: Boolean) {
        settingsRepository.setWipsKarmaDetection(enabled)
    }

    suspend fun setWipsRogueApDetection(enabled: Boolean) {
        settingsRepository.setWipsRogueApDetection(enabled)
    }

    /**
     * Installs the Flock Bridge FAP to the connected Flipper Zero.
     */
    suspend fun installFapToFlipper(context: Context) {
        if (connectionState.value !is ConnectionState.Connected) {
            Toast.makeText(context, "Flipper not connected", Toast.LENGTH_SHORT).show()
            return
        }

        _isInstalling.value = true
        _installProgress.value = "Preparing FAP file..."

        try {
            withContext(Dispatchers.IO) {
                // Step 1: Extract FAP from assets to temp file
                _installProgress.value = "Extracting FAP from app..."
                val tempFile = File(context.cacheDir, "flock_bridge.fap")

                try {
                    context.assets.open(FAP_ASSET_PATH).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "FAP not found in assets, will need to build it first", e)
                    withContext(Dispatchers.Main) {
                        _installProgress.value = "FAP not bundled. Run: ./gradlew bundleFlipperFap"
                        Toast.makeText(
                            context,
                            "FAP file not found. Build it first using Gradle.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext
                }

                // Step 2: Upload to Flipper
                _installProgress.value = "Uploading to Flipper Zero..."
                val success = flipperScannerManager.uploadFile(
                    localFile = tempFile,
                    remotePath = FAP_DEST_PATH
                ) { progress ->
                    _installProgress.value = "Uploading: ${(progress * 100).toInt()}%"
                }

                // Step 3: Cleanup temp file
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    if (success) {
                        _installProgress.value = "Installation complete!"
                        Toast.makeText(
                            context,
                            "Flock Bridge installed! Find it in Tools on your Flipper.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        _installProgress.value = "Installation failed"
                        Toast.makeText(
                            context,
                            "Failed to install FAP to Flipper",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error installing FAP", e)
            withContext(Dispatchers.Main) {
                _installProgress.value = "Error: ${e.message}"
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            _isInstalling.value = false
            // Clear progress after delay
            viewModelScope.launch {
                kotlinx.coroutines.delay(3000)
                _installProgress.value = null
            }
        }
    }
}

package com.flockyou.detection.handler

import android.content.Context
import android.net.wifi.ScanResult
import com.flockyou.data.model.Detection
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.service.RogueWifiMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detection handler for WiFi-based surveillance detection.
 *
 * This is a standalone handler that wraps RogueWifiMonitor for WiFi-based
 * surveillance detection. It does NOT implement the DetectionHandler interface
 * as it uses a different data flow pattern (continuous monitoring vs. context-based analysis).
 *
 * Detects:
 * - Evil twin access points
 * - Deauthentication attacks
 * - Hidden cameras broadcasting WiFi
 * - Surveillance vans with mobile hotspots
 * - Networks following the user
 * - Rogue access points
 * - WiFi Pineapple and similar devices
 */
@Singleton
class WifiDetectionHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val protocol: DetectionProtocol = DetectionProtocol.WIFI

    val supportedDeviceTypes: Set<DeviceType> = setOf(
        DeviceType.ROGUE_AP,
        DeviceType.HIDDEN_CAMERA,
        DeviceType.SURVEILLANCE_VAN,
        DeviceType.TRACKING_DEVICE,
        DeviceType.WIFI_PINEAPPLE,
        DeviceType.PACKET_SNIFFER,
        DeviceType.MAN_IN_MIDDLE
    )

    val displayName: String = "WiFi Detection Handler"

    private var _isActive: Boolean = false
    val isActive: Boolean get() = _isActive

    private val _detections = MutableSharedFlow<Detection>(replay = 100)
    val detections: Flow<Detection> = _detections.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Underlying monitor that does the actual detection
    private var rogueWifiMonitor: RogueWifiMonitor? = null

    fun startMonitoring() {
        if (_isActive) return
        _isActive = true

        rogueWifiMonitor = RogueWifiMonitor(context).apply {
            startMonitoring()
        }

        // Collect anomalies and convert to detections
        scope.launch {
            rogueWifiMonitor?.anomalies?.collect { anomalies ->
                anomalies.forEach { anomaly ->
                    val detection = rogueWifiMonitor?.anomalyToDetection(anomaly)
                    if (detection != null) {
                        _detections.emit(detection)
                    }
                }
            }
        }
    }

    fun stopMonitoring() {
        _isActive = false
        rogueWifiMonitor?.stopMonitoring()
    }

    /**
     * Process WiFi scan results and return any immediate detections.
     *
     * Note: Some detections require pattern analysis over time (like "following networks")
     * and will be emitted via the [detections] flow rather than returned here.
     * Callers should also subscribe to the flow for complete coverage.
     *
     * @param data WiFi scan results to process
     * @return List of immediate detections found, may be empty if detections require more time
     */
    suspend fun processData(data: List<ScanResult>): List<Detection> {
        val monitor = rogueWifiMonitor ?: return emptyList()

        // Process the scan results
        monitor.processScanResults(data)

        // Collect any immediate detections (with short timeout)
        // This handles synchronous detection cases like evil twins, hidden cameras, etc.
        val immediateDetections = mutableListOf<Detection>()

        // Try to get any anomalies that were detected immediately
        val anomalies = withTimeoutOrNull(100L) {
            monitor.anomalies.first { it.isNotEmpty() }
        } ?: emptyList()

        // Convert anomalies to detections
        for (anomaly in anomalies) {
            val detection = monitor.anomalyToDetection(anomaly)
            if (detection != null) {
                immediateDetections.add(detection)
                // Also emit to flow for subscribers
                _detections.emit(detection)
            }
        }

        return immediateDetections
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        rogueWifiMonitor?.updateLocation(latitude, longitude)
    }

    fun clearHistory() {
        rogueWifiMonitor?.clearHistory()
    }

    fun destroy() {
        stopMonitoring()
        rogueWifiMonitor?.destroy()
        rogueWifiMonitor = null
    }

    /**
     * Get the underlying RogueWifiMonitor for direct access.
     */
    fun getMonitor(): RogueWifiMonitor? = rogueWifiMonitor
}

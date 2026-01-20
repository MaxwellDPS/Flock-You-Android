package com.flockyou.auto

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.flockyou.R
import com.flockyou.data.model.Detection
import com.flockyou.data.model.ThreatLevel
import com.flockyou.service.ScanningServiceConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main screen for Android Auto displaying threat status.
 *
 * Designed for minimal cognitive load and maximum glanceability while driving.
 * Shows simple status indicators instead of detailed detection lists.
 * Follows Android Auto safety guidelines for driver distraction.
 *
 * Status levels:
 * - GREEN (All Clear): No threats detected
 * - YELLOW (Devices Detected/Threats Nearby): Low/medium or high threats
 * - RED (ALERT): Critical threats require attention
 *
 * Thread Safety:
 * All data access is synchronized via [dataLock] to prevent race conditions
 * between the collection update coroutine and UI template rendering.
 *
 * IPC Architecture:
 * This screen uses IPC (via [ScanningServiceConnection]) to receive detection data
 * instead of direct database access. This is required because the ScanningService
 * runs in a separate process (:scanning), and direct database access from Android Auto
 * would cause SQLCipher race conditions and potential database corruption.
 *
 * @param carContext The Android Auto car context for accessing resources and services
 */
class AutoMainScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Lock object for synchronizing access to detection data */
    private val dataLock = Any()

    /** Current list of active detections, guarded by [dataLock] */
    private var detections: List<Detection> = emptyList()

    /** Threat level counts for the summary display, guarded by [dataLock] */
    private var threatCounts: Map<ThreatLevel, Int> = emptyMap()

    /** Current error state message, or null if no error */
    private var errorState: String? = null

    /** Whether the service connection is bound */
    private var isServiceBound: Boolean = false

    /** Whether boost mode is currently active (faster scanning when Android Auto is connected) */
    private var isBoostModeActive: Boolean = false

    /**
     * IPC connection to the ScanningService.
     * Used to receive detection data without direct database access,
     * avoiding multi-process database corruption issues.
     */
    private val serviceConnection: ScanningServiceConnection by lazy {
        Log.d(TAG, "Initializing ScanningServiceConnection")
        ScanningServiceConnection(carContext)
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) {
            Log.d(TAG, "Screen created, binding to service and starting detection observation")
            bindToService()
            startObservingDetections()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            Log.i(TAG, "Screen destroyed, unbinding from service and cancelling coroutine scope")
            unbindFromService()
            scope.cancel()
            lifecycle.removeObserver(this)
        }
    }

    init {
        Log.d(TAG, "AutoMainScreen initialized")
        lifecycle.addObserver(lifecycleObserver)
    }

    /**
     * Binds to the ScanningService via IPC.
     * This establishes the connection for receiving detection updates.
     * Also notifies the service that Android Auto is connected to enable boost mode.
     */
    private fun bindToService() {
        Log.d(TAG, "Binding to scanning service")
        serviceConnection.bind()
        isServiceBound = true

        // Notify service that Android Auto is connected - enables boost mode for faster scanning
        serviceConnection.notifyAndroidAutoConnected()
        Log.i(TAG, "Android Auto boost mode requested")
    }

    /**
     * Unbinds from the ScanningService.
     * Called when the screen is destroyed to clean up resources.
     * Notifies the service that Android Auto is disconnecting to disable boost mode.
     */
    private fun unbindFromService() {
        if (isServiceBound) {
            Log.d(TAG, "Unbinding from scanning service")

            // Notify service that Android Auto is disconnecting - disables boost mode
            serviceConnection.notifyAndroidAutoDisconnected()
            Log.i(TAG, "Android Auto boost mode disabled")

            try {
                serviceConnection.unbind()
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding from service", e)
            }
            isServiceBound = false
        }
    }

    /**
     * Starts observing detection data from the service via IPC.
     *
     * This method launches coroutines that collect:
     * 1. Connection state to handle service not running
     * 2. Active detections from the scanning service
     *
     * Errors are caught and displayed to the user with a retry option.
     */
    private fun startObservingDetections() {
        Log.d(TAG, "Starting detection observation via IPC")

        // Observe service connection state
        scope.launch {
            try {
                serviceConnection.isBound.collectLatest { bound ->
                    Log.d(TAG, "Service connection state: bound=$bound")
                    if (!bound && isServiceBound) {
                        // Service disconnected unexpectedly, show status update
                        Log.w(TAG, "Service disconnected, waiting for reconnection")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error observing service connection state", e)
            }
        }

        // Observe active detections from IPC
        scope.launch {
            try {
                serviceConnection.activeDetections.collectLatest { active ->
                    Log.d(TAG, "Received ${active.size} active detections via IPC")

                    val sortedDetections = active.sortedByDescending { it.timestamp }
                    val counts = active.groupBy { it.threatLevel }.mapValues { it.value.size }

                    updateData(sortedDetections, counts)
                    errorState = null
                    invalidate()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Detection observation cancelled")
                throw e // Re-throw to allow proper cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error observing detections via IPC", e)
                errorState = e.message ?: carContext.getString(R.string.auto_error_loading)
                invalidate()
            }
        }

        // Also observe connection errors
        scope.launch {
            try {
                serviceConnection.lastConnectionError.collectLatest { error ->
                    if (error != null) {
                        Log.w(TAG, "Service connection error: $error")
                        // Don't overwrite error state if we already have detections
                        // (the service might just be reconnecting)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error observing connection errors", e)
            }
        }

        // Observe boost mode status for UI feedback
        scope.launch {
            try {
                serviceConnection.isBoostModeActive.collect { isBoostActive ->
                    Log.d(TAG, "Boost mode active: $isBoostActive")
                    isBoostModeActive = isBoostActive
                    // Invalidate to update UI with boost mode indicator
                    invalidate()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error observing boost mode status", e)
            }
        }
    }

    /**
     * Thread-safe update of detection data.
     *
     * @param newDetections The new list of detections to display
     * @param newCounts The new threat level counts
     */
    private fun updateData(newDetections: List<Detection>, newCounts: Map<ThreatLevel, Int>) {
        synchronized(dataLock) {
            detections = newDetections
            threatCounts = newCounts
        }
    }

    /**
     * Thread-safe retrieval of threat counts snapshot.
     *
     * @return A map of threat levels to their counts as an immutable copy
     */
    private fun getThreatCountsSnapshot(): Map<ThreatLevel, Int> {
        synchronized(dataLock) {
            return threatCounts.toMap()
        }
    }

    /**
     * Creates the Android Auto template for display on the vehicle head unit.
     *
     * This method is called by the Android Auto framework whenever the screen
     * needs to be rendered. It returns either an error template with retry option
     * or the main message template showing threat status.
     *
     * @return The [MessageTemplate] to display with current threat status
     */
    override fun onGetTemplate(): Template {
        // Handle error state
        if (errorState != null) {
            Log.d(TAG, "Displaying error template: $errorState")
            return MessageTemplate.Builder(errorState!!)
                .setTitle(carContext.getString(R.string.auto_error_title))
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.drawable.ic_warning)
                    ).setTint(CarColor.RED).build()
                )
                .setHeaderAction(Action.APP_ICON)
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.auto_retry))
                        .setOnClickListener {
                            Log.d(TAG, "User tapped retry, reconnecting and restarting observation")
                            errorState = null
                            // Force reconnect to service
                            serviceConnection.forceReconnect()
                            startObservingDetections()
                        }
                        .build()
                )
                .build()
        }

        val currentThreatCounts = getThreatCountsSnapshot()
        val totalThreats = currentThreatCounts.values.sum()
        val criticalCount = currentThreatCounts[ThreatLevel.CRITICAL] ?: 0
        val highCount = currentThreatCounts[ThreatLevel.HIGH] ?: 0

        Log.d(TAG, "Building template: total=$totalThreats, critical=$criticalCount, high=$highCount")

        // Get simplified, glanceable status information
        val statusInfo = getStatusInfo(
            criticalCount = criticalCount,
            highCount = highCount,
            totalThreats = totalThreats
        )

        val messageBuilder = MessageTemplate.Builder(statusInfo.text)
            .setTitle(carContext.getString(R.string.auto_app_name))
            .setIcon(statusInfo.icon)
            .setHeaderAction(Action.APP_ICON)

        // Add "View Details" action - shows safety message
        messageBuilder.addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.auto_view_details))
                .setBackgroundColor(CarColor.SECONDARY)
                .setOnClickListener { showPullOverMessage() }
                .build()
        )

        // Add voice alert action strip for critical threats
        if (statusInfo.isCritical) {
            Log.d(TAG, "Adding critical alert action strip")
            val actionStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(
                            CarIcon.Builder(
                                IconCompat.createWithResource(carContext, R.drawable.ic_warning)
                            ).setTint(CarColor.RED).build()
                        )
                        .setOnClickListener { announceAlert(criticalCount) }
                        .build()
                )
                .build()
            messageBuilder.setActionStrip(actionStrip)
        }

        return messageBuilder.build()
    }

    /**
     * Determines the status display information based on threat counts.
     * Returns simplified, glanceable status for driving safety.
     *
     * Uses standard CarColor values (GREEN, YELLOW, RED) for consistency
     * and accessibility across different vehicle displays.
     *
     * @param criticalCount Number of critical-level threats
     * @param highCount Number of high-level threats
     * @param totalThreats Total number of detected threats
     * @return [StatusInfo] containing display text, icon, and color information
     */
    private fun getStatusInfo(
        criticalCount: Int,
        highCount: Int,
        totalThreats: Int
    ): StatusInfo {
        return when {
            criticalCount > 0 -> StatusInfo(
                text = carContext.getString(R.string.auto_status_critical, criticalCount),
                icon = CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_warning)
                ).setTint(CarColor.RED).build(),
                color = CarColor.RED,
                isCritical = true
            )
            highCount > 0 -> StatusInfo(
                text = carContext.getString(R.string.auto_status_threats, highCount),
                icon = CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_warning)
                ).setTint(CarColor.YELLOW).build(),
                color = CarColor.YELLOW,
                isCritical = false
            )
            totalThreats > 0 -> StatusInfo(
                text = carContext.getString(R.string.auto_status_devices, totalThreats),
                icon = CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_radar)
                ).setTint(CarColor.YELLOW).build(),
                color = CarColor.YELLOW,
                isCritical = false
            )
            else -> StatusInfo(
                text = carContext.getString(R.string.auto_status_safe),
                icon = CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_shield)
                ).setTint(CarColor.GREEN).build(),
                color = CarColor.GREEN,
                isCritical = false
            )
        }
    }

    /**
     * Shows a toast message instructing the driver to pull over before viewing details.
     * This ensures safe driving practices by not showing detailed information while moving.
     *
     * Also attempts to open the main app for detailed view when safely parked.
     */
    private fun showPullOverMessage() {
        Log.d(TAG, "Showing pull over message")
        CarToast.makeText(
            carContext,
            carContext.getString(R.string.auto_pull_over),
            CarToast.LENGTH_LONG
        ).show()

        // Attempt to open the main app for detailed information
        // The user should only interact with this when safely parked
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("flockyou://detections")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            carContext.startActivity(intent)
            Log.d(TAG, "Successfully launched details activity")
        } catch (e: Exception) {
            Log.w(TAG, "Could not open details activity", e)
            // If deep link fails, the toast message is sufficient
        }
    }

    /**
     * Announces critical alert via toast notification.
     * Provides audible/visual feedback for critical threats without
     * requiring the driver to look away from the road.
     *
     * Note: In a full implementation, this could trigger TTS announcement
     * via Android's TextToSpeech API for hands-free alerting.
     *
     * @param criticalCount Number of critical threats to announce
     */
    private fun announceAlert(criticalCount: Int) {
        Log.d(TAG, "Announcing alert for $criticalCount critical threats")
        val message = if (criticalCount == 1) {
            "Warning: 1 critical threat detected"
        } else {
            "Warning: $criticalCount critical threats detected"
        }
        CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
    }

    /**
     * Data class for status display information.
     * Encapsulates all visual elements for the current threat status.
     *
     * @property text The status message to display
     * @property icon The [CarIcon] to show alongside the status
     * @property color The [CarColor] representing the threat level
     * @property isCritical Whether this status represents a critical threat
     */
    private data class StatusInfo(
        val text: String,
        val icon: CarIcon,
        val color: CarColor,
        val isCritical: Boolean
    )

    companion object {
        private const val TAG = "AutoMainScreen"
    }
}

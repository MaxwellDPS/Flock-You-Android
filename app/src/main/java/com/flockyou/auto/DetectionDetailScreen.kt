package com.flockyou.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.flockyou.R
import com.flockyou.data.model.Detection
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.model.rssiToDistance
import com.flockyou.service.ScanningServiceConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detail screen for displaying a specific detection on Android Auto.
 *
 * This screen is pushed onto the screen stack when the user navigates to a
 * specific detection via a notification deep link or other intent-based navigation.
 * It displays simplified detection information that is safe to view while driving.
 *
 * Design Principles:
 * - Minimal information to reduce driver distraction
 * - Large, glanceable text and icons
 * - Clear threat level indication via color coding
 * - Quick back navigation to return to main status screen
 *
 * Data Flow:
 * - Receives detection ID at construction time
 * - Searches for matching detection in the active detections list from IPC
 * - Falls back to a "not found" message if detection is no longer active
 *
 * @param carContext The Android Auto car context for accessing resources and services
 * @param detectionId The unique ID of the detection to display
 */
class DetectionDetailScreen(
    carContext: CarContext,
    private val detectionId: String
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The detection to display, or null if not found */
    private var detection: Detection? = null

    /** Whether we're still loading the detection data */
    private var isLoading: Boolean = true

    /** Error message if something went wrong */
    private var errorMessage: String? = null

    /**
     * IPC connection to the ScanningService.
     * Used to retrieve active detections without direct database access.
     */
    private val serviceConnection: ScanningServiceConnection by lazy {
        Log.d(TAG, "Initializing ScanningServiceConnection for detection detail")
        ScanningServiceConnection(carContext)
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) {
            Log.d(TAG, "DetectionDetailScreen created for detection: $detectionId")
            bindToService()
            startObservingDetections()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            Log.i(TAG, "DetectionDetailScreen destroyed")
            unbindFromService()
            scope.cancel()
            lifecycle.removeObserver(this)
        }
    }

    init {
        Log.d(TAG, "DetectionDetailScreen initialized for detection: $detectionId")
        lifecycle.addObserver(lifecycleObserver)
    }

    /**
     * Binds to the ScanningService via IPC.
     */
    private fun bindToService() {
        Log.d(TAG, "Binding to scanning service")
        serviceConnection.bind()
    }

    /**
     * Unbinds from the ScanningService.
     */
    private fun unbindFromService() {
        Log.d(TAG, "Unbinding from scanning service")
        try {
            serviceConnection.unbind()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding from service", e)
        }
    }

    /**
     * Starts observing detection data from the service via IPC.
     * Searches for the specific detection ID in the active detections list.
     */
    private fun startObservingDetections() {
        Log.d(TAG, "Starting detection observation for ID: $detectionId")

        scope.launch {
            try {
                serviceConnection.activeDetections.collectLatest { detections ->
                    Log.d(TAG, "Received ${detections.size} active detections")

                    // Find the detection matching our ID
                    val found = detections.find { it.id == detectionId }

                    if (found != null) {
                        Log.d(TAG, "Found detection: ${found.deviceType.displayName}")
                        detection = found
                        isLoading = false
                        errorMessage = null
                    } else {
                        Log.w(TAG, "Detection not found in active list: $detectionId")
                        // Detection might have expired or been cleared
                        // Keep showing what we have, or show not found
                        if (detection == null) {
                            isLoading = false
                            // Detection was never found - show not found message
                        }
                    }
                    invalidate()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Detection observation cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error observing detections", e)
                errorMessage = e.message ?: carContext.getString(R.string.auto_error_loading)
                isLoading = false
                invalidate()
            }
        }
    }

    /**
     * Creates the Android Auto template for the detection detail.
     *
     * Uses a [PaneTemplate] when we have detection data to show rows of information,
     * or a [MessageTemplate] for loading/error/not-found states.
     */
    override fun onGetTemplate(): Template {
        // Show loading state
        if (isLoading) {
            return MessageTemplate.Builder(carContext.getString(R.string.auto_detail_loading))
                .setTitle(carContext.getString(R.string.auto_detail_title))
                .setHeaderAction(Action.BACK)
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.drawable.ic_radar)
                    ).build()
                )
                .build()
        }

        // Show error state
        if (errorMessage != null) {
            return MessageTemplate.Builder(errorMessage!!)
                .setTitle(carContext.getString(R.string.auto_error_title))
                .setHeaderAction(Action.BACK)
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.drawable.ic_warning)
                    ).setTint(CarColor.RED).build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.auto_retry))
                        .setOnClickListener {
                            isLoading = true
                            errorMessage = null
                            invalidate()
                            serviceConnection.forceReconnect()
                            startObservingDetections()
                        }
                        .build()
                )
                .build()
        }

        // Show not found state
        val currentDetection = detection
        if (currentDetection == null) {
            return MessageTemplate.Builder(carContext.getString(R.string.auto_detail_not_found))
                .setTitle(carContext.getString(R.string.auto_detail_title))
                .setHeaderAction(Action.BACK)
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.drawable.ic_radar)
                    ).build()
                )
                .build()
        }

        // Show detection details using PaneTemplate for multiple rows
        return buildDetailPane(currentDetection)
    }

    /**
     * Builds a [PaneTemplate] displaying the detection details.
     *
     * Shows:
     * - Device type with threat level color
     * - Signal strength and estimated distance
     * - Detection method
     * - Time detected
     *
     * @param detection The detection to display
     * @return A [PaneTemplate] with the detection information
     */
    private fun buildDetailPane(detection: Detection): Template {
        val threatColor = when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> CarColor.RED
            ThreatLevel.HIGH -> CarColor.RED
            ThreatLevel.MEDIUM -> CarColor.YELLOW
            ThreatLevel.LOW -> CarColor.YELLOW
            ThreatLevel.INFO -> CarColor.GREEN
        }

        val threatIcon = when (detection.threatLevel) {
            ThreatLevel.CRITICAL, ThreatLevel.HIGH -> R.drawable.ic_warning
            ThreatLevel.MEDIUM, ThreatLevel.LOW -> R.drawable.ic_warning
            ThreatLevel.INFO -> R.drawable.ic_radar
        }

        val paneBuilder = Pane.Builder()

        // Row 1: Device type and threat level
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(detection.deviceType.displayName)
                .addText(detection.threatLevel.displayName)
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, threatIcon)
                    ).setTint(threatColor).build()
                )
                .build()
        )

        // Row 2: Signal strength and distance
        val distance = rssiToDistance(detection.rssi)
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.auto_detail_signal))
                .addText("${detection.signalStrength.displayName} ($distance)")
                .build()
        )

        // Row 3: Detection method
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.auto_detail_method))
                .addText(detection.detectionMethod.displayName)
                .build()
        )

        // Row 4: Time detected (simplified for driving safety)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val timeStr = timeFormat.format(Date(detection.timestamp))
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.auto_detail_time))
                .addText(timeStr)
                .build()
        )

        // Build the template with appropriate header
        val title = when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> carContext.getString(R.string.auto_detail_title_critical)
            ThreatLevel.HIGH -> carContext.getString(R.string.auto_detail_title_high)
            else -> carContext.getString(R.string.auto_detail_title)
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()
    }

    companion object {
        private const val TAG = "DetectionDetailScreen"
    }
}

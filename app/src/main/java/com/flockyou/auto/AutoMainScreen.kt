package com.flockyou.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.flockyou.R
import com.flockyou.data.model.Detection
import com.flockyou.data.model.ThreatLevel
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.FlockYouDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main screen for Android Auto displaying threat status and recent detections.
 *
 * Shows a summary of current threat levels and allows viewing recent detections.
 * Optimized for glanceability while driving.
 */
class AutoMainScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var detections: List<Detection> = emptyList()
    private var threatCounts: Map<ThreatLevel, Int> = emptyMap()

    private val repository: DetectionRepository by lazy {
        val database = FlockYouDatabase.getDatabase(carContext)
        DetectionRepository(database.detectionDao())
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                startObservingDetections()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    private fun startObservingDetections() {
        scope.launch {
            repository.activeDetections.collectLatest { active ->
                detections = active.sortedByDescending { it.timestamp }.take(MAX_DISPLAY_ITEMS)
                threatCounts = active.groupBy { it.threatLevel }.mapValues { it.value.size }
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        // Add threat summary row
        val totalThreats = threatCounts.values.sum()
        val criticalCount = threatCounts[ThreatLevel.CRITICAL] ?: 0
        val highCount = threatCounts[ThreatLevel.HIGH] ?: 0

        val statusText = when {
            criticalCount > 0 -> "CRITICAL: $criticalCount active"
            highCount > 0 -> "HIGH: $highCount threats nearby"
            totalThreats > 0 -> "$totalThreats devices detected"
            else -> "No threats detected"
        }

        val statusColor = when {
            criticalCount > 0 -> CarColor.createCustom(0xFFFF0000.toInt(), 0xFFFF0000.toInt())
            highCount > 0 -> CarColor.createCustom(0xFFFF6600.toInt(), 0xFFFF6600.toInt())
            totalThreats > 0 -> CarColor.createCustom(0xFFFFCC00.toInt(), 0xFFFFCC00.toInt())
            else -> CarColor.GREEN
        }

        listBuilder.addItem(
            Row.Builder()
                .setTitle("Threat Status")
                .addText(statusText)
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.drawable.ic_shield)
                    ).setTint(statusColor).build()
                )
                .build()
        )

        // Add recent detections
        if (detections.isNotEmpty()) {
            detections.forEach { detection ->
                listBuilder.addItem(createDetectionRow(detection))
            }
        } else {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Scanning...")
                    .addText("Background scanning is active")
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("Flock You")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun createDetectionRow(detection: Detection): Row {
        val threatColor = when (detection.threatLevel) {
            ThreatLevel.CRITICAL -> CarColor.createCustom(0xFFFF0000.toInt(), 0xFFFF0000.toInt())
            ThreatLevel.HIGH -> CarColor.createCustom(0xFFFF6600.toInt(), 0xFFFF6600.toInt())
            ThreatLevel.MEDIUM -> CarColor.createCustom(0xFFFFCC00.toInt(), 0xFFFFCC00.toInt())
            ThreatLevel.LOW -> CarColor.createCustom(0xFF00AAFF.toInt(), 0xFF00AAFF.toInt())
            ThreatLevel.INFO -> CarColor.SECONDARY
        }

        val title = detection.deviceName
            ?: detection.ssid
            ?: detection.macAddress
            ?: detection.deviceType.displayName

        val subtitle = buildString {
            append(detection.deviceType.displayName)
            append(" • ")
            append(detection.threatLevel.displayName)
            detection.signalStrength.let { signal ->
                append(" • ")
                append(signal.displayName)
            }
        }

        val iconRes = when (detection.deviceType) {
            com.flockyou.data.model.DeviceType.FLOCK_SAFETY_CAMERA,
            com.flockyou.data.model.DeviceType.PENGUIN_SURVEILLANCE,
            com.flockyou.data.model.DeviceType.PIGVISION_SYSTEM -> R.drawable.ic_camera
            com.flockyou.data.model.DeviceType.BODY_CAMERA -> R.drawable.ic_camera
            com.flockyou.data.model.DeviceType.DRONE -> R.drawable.ic_drone
            com.flockyou.data.model.DeviceType.RF_JAMMER,
            com.flockyou.data.model.DeviceType.GNSS_JAMMER -> R.drawable.ic_warning
            com.flockyou.data.model.DeviceType.STINGRAY_IMSI -> R.drawable.ic_cell_tower
            else -> R.drawable.ic_radar
        }

        return Row.Builder()
            .setTitle(title)
            .addText(subtitle)
            .setImage(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, iconRes)
                ).setTint(threatColor).build()
            )
            .build()
    }

    companion object {
        private const val MAX_DISPLAY_ITEMS = 5
    }
}

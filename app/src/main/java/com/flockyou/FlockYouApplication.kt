package com.flockyou

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.flockyou.ai.DetectionAnalyzer
import com.flockyou.data.AiSettingsRepository
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.data.OuiSettingsRepository
import com.flockyou.data.repository.OuiRepository
import com.flockyou.util.NotificationChannelIds
import com.flockyou.worker.BackgroundAnalysisWorker
import com.flockyou.worker.DeadManSwitchWorker
import com.flockyou.worker.OuiUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FlockYouApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var ouiSettingsRepository: OuiSettingsRepository

    @Inject
    lateinit var ouiRepository: OuiRepository

    @Inject
    lateinit var aiSettingsRepository: AiSettingsRepository

    @Inject
    lateinit var detectionAnalyzer: DetectionAnalyzer

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Create all notification channels at app startup
        createNotificationChannels()

        // Initialize OUI database updates
        applicationScope.launch {
            initializeOuiUpdates()
        }

        // Initialize AI model if enabled
        applicationScope.launch {
            initializeAiModel()
        }

        // Initialize dead man's switch if enabled
        applicationScope.launch {
            initializeDeadManSwitch()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Scanning service channel (low priority, always-on)
            val scanningChannel = NotificationChannel(
                NotificationChannelIds.SCANNING,
                "Scanning Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background surveillance device detection service"
                setShowBadge(false)
            }

            // Detection alerts channel (high priority, bypasses DND)
            val detectionAlertsChannel = NotificationChannel(
                NotificationChannelIds.DETECTION_ALERTS,
                "Detection Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when surveillance devices are detected"
                enableVibration(true)
                setShowBadge(true)
                setBypassDnd(true)
            }

            // Dead man's switch warning channel (high priority, bypasses DND)
            val deadManSwitchChannel = NotificationChannel(
                NotificationChannelIds.DEAD_MAN_SWITCH,
                "Dead Man's Switch Warning",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Warning before automatic data wipe"
                enableVibration(true)
                setShowBadge(true)
                setBypassDnd(true)
            }

            // Critical alerts channel (max priority, bypasses DND)
            val criticalAlertsChannel = NotificationChannel(
                NotificationChannelIds.CRITICAL_ALERTS,
                "Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical security alerts requiring immediate attention"
                enableVibration(true)
                setShowBadge(true)
                setBypassDnd(true)
            }

            // Updates channel (default priority)
            val updatesChannel = NotificationChannel(
                NotificationChannelIds.UPDATES,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications about app updates and new features"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(
                listOf(
                    scanningChannel,
                    detectionAlertsChannel,
                    deadManSwitchChannel,
                    criticalAlertsChannel,
                    updatesChannel
                )
            )
        }
    }

    private suspend fun initializeOuiUpdates() {
        val settings = ouiSettingsRepository.settings.first()

        // Schedule periodic updates based on settings
        if (settings.autoUpdateEnabled && settings.updateIntervalHours > 0) {
            OuiUpdateWorker.schedulePeriodicUpdate(
                context = this,
                intervalHours = settings.updateIntervalHours,
                wifiOnly = settings.useWifiOnly
            )
        }

        // If database is empty, trigger immediate download
        if (!ouiRepository.hasData()) {
            OuiUpdateWorker.triggerImmediateUpdate(this)
        }
    }

    private suspend fun initializeAiModel() {
        val settings = aiSettingsRepository.settings.first()

        // Initialize the AI model if AI analysis is enabled
        if (settings.enabled) {
            detectionAnalyzer.initializeModel()

            // Schedule background analysis if FP filtering is enabled
            if (settings.enableFalsePositiveFiltering) {
                BackgroundAnalysisWorker.schedule(this)
            }
        } else {
            // Cancel background analysis if AI is disabled
            BackgroundAnalysisWorker.cancel(this)
        }
    }

    private suspend fun initializeDeadManSwitch() {
        val settings = nukeSettingsRepository.settings.first()

        // Schedule or cancel the dead man's switch based on settings
        if (settings.nukeEnabled && settings.deadManSwitchEnabled) {
            // Initialize the last auth time if this is the first run
            DeadManSwitchWorker.initializeIfNeeded(this)
            // Schedule periodic checks (every 30 minutes)
            DeadManSwitchWorker.schedule(this, checkIntervalMinutes = 30)
        } else {
            // Cancel if disabled
            DeadManSwitchWorker.cancel(this)
        }
    }
}

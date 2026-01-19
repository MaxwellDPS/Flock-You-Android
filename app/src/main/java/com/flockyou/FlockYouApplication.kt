package com.flockyou

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.flockyou.ai.DetectionAnalyzer
import com.flockyou.data.AiSettingsRepository
import com.flockyou.data.OuiSettingsRepository
import com.flockyou.data.repository.OuiRepository
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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize OUI database updates
        applicationScope.launch {
            initializeOuiUpdates()
        }

        // Initialize AI model if enabled
        applicationScope.launch {
            initializeAiModel()
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
        }
    }
}

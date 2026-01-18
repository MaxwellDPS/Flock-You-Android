package com.flockyou

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
}

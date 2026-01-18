package com.flockyou.service.nuke

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.flockyou.data.DangerZone
import com.flockyou.data.NukeSettings
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.security.NukeManager
import com.flockyou.security.NukeTriggerSource
import com.flockyou.worker.NukeWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors device location and triggers a nuke when entering configured danger zones.
 *
 * Danger zones can include:
 * - Police stations
 * - Border crossings
 * - Airports
 * - Courthouses
 * - Custom locations
 *
 * This provides preemptive data destruction before device seizure.
 */
@Singleton
class GeofenceWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nukeSettingsRepository: NukeSettingsRepository,
    private val nukeManager: NukeManager
) {
    companion object {
        private const val TAG = "GeofenceWatcher"
        private const val PREFS_NAME = "geofence_prefs"
        private const val KEY_LAST_TRIGGERED_ZONE = "last_triggered_zone"
        private const val KEY_LAST_TRIGGER_TIME = "last_trigger_time"

        // Minimum time between triggers for the same zone (prevents repeated triggers)
        private const val MIN_TRIGGER_INTERVAL_MS = 3600 * 1000L // 1 hour
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Check if the current location is within any danger zone.
     * Call this from the location service whenever a new location is received.
     *
     * @param location Current device location
     */
    fun checkLocation(location: Location) {
        scope.launch {
            checkLocationInternal(location)
        }
    }

    private suspend fun checkLocationInternal(location: Location) {
        val settings = nukeSettingsRepository.settings.first()

        // Check if geofence trigger is enabled
        if (!settings.nukeEnabled || !settings.geofenceTriggerEnabled) {
            return
        }

        // Check location permission
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted")
            return
        }

        val dangerZones = settings.getDangerZones().filter { it.enabled }
        if (dangerZones.isEmpty()) {
            return
        }

        // Check each danger zone
        for (zone in dangerZones) {
            if (isWithinZone(location, zone)) {
                if (shouldTriggerForZone(zone)) {
                    Log.w(TAG, "ENTERED DANGER ZONE: ${zone.name} at (${zone.latitude}, ${zone.longitude})")
                    triggerNuke(zone, settings)
                    return
                }
            }
        }
    }

    private fun isWithinZone(location: Location, zone: DangerZone): Boolean {
        val zoneLocation = Location("").apply {
            latitude = zone.latitude
            longitude = zone.longitude
        }

        val distance = location.distanceTo(zoneLocation)
        return distance <= zone.radiusMeters
    }

    private fun shouldTriggerForZone(zone: DangerZone): Boolean {
        // Prevent repeated triggers for the same zone within the minimum interval
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTriggeredZone = prefs.getString(KEY_LAST_TRIGGERED_ZONE, null)
        val lastTriggerTime = prefs.getLong(KEY_LAST_TRIGGER_TIME, 0)

        if (lastTriggeredZone == zone.id) {
            val elapsed = System.currentTimeMillis() - lastTriggerTime
            if (elapsed < MIN_TRIGGER_INTERVAL_MS) {
                Log.d(TAG, "Skipping trigger for zone ${zone.name} - too soon since last trigger")
                return false
            }
        }

        return true
    }

    private fun triggerNuke(zone: DangerZone, settings: NukeSettings) {
        // Record this trigger
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_TRIGGERED_ZONE, zone.id)
            .putLong(KEY_LAST_TRIGGER_TIME, System.currentTimeMillis())
            .apply()

        val delaySeconds = settings.geofenceTriggerDelaySeconds

        if (delaySeconds > 0) {
            Log.w(TAG, "Scheduling nuke in ${delaySeconds}s due to geofence trigger (${zone.name})")
            NukeWorker.scheduleGeofenceNuke(context, delaySeconds)
        } else {
            Log.w(TAG, "Executing immediate nuke due to geofence trigger (${zone.name})")
            scope.launch {
                nukeManager.executeNuke(NukeTriggerSource.GEOFENCE)
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Cancel any pending geofence-triggered nuke.
     * Call this when the user moves out of a danger zone.
     */
    fun cancelPendingNuke() {
        NukeWorker.cancelNuke(context, NukeWorker.WORK_NAME_GEOFENCE)
        Log.d(TAG, "Cancelled pending geofence nuke")
    }

    /**
     * Check if a nuke is currently pending from a geofence trigger.
     */
    suspend fun isNukePending(): Boolean {
        return NukeWorker.isNukePending(context, NukeWorker.WORK_NAME_GEOFENCE)
    }

    /**
     * Calculate distance to the nearest danger zone.
     * Useful for displaying warnings to the user.
     *
     * @param location Current location
     * @return Pair of (DangerZone, distance in meters) or null if no zones configured
     */
    suspend fun getNearestDangerZone(location: Location): Pair<DangerZone, Float>? {
        val settings = nukeSettingsRepository.settings.first()
        val dangerZones = settings.getDangerZones().filter { it.enabled }

        if (dangerZones.isEmpty()) {
            return null
        }

        return dangerZones.map { zone ->
            val zoneLocation = Location("").apply {
                latitude = zone.latitude
                longitude = zone.longitude
            }
            Pair(zone, location.distanceTo(zoneLocation))
        }.minByOrNull { it.second }
    }
}

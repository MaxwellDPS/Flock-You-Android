package com.flockyou.scanner.probes

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.activeProbeDataStore: DataStore<Preferences> by preferencesDataStore(name = "active_probe_settings")

/**
 * Settings for Active Probe features.
 * All active probes are disabled by default and require explicit global consent.
 * Once consent is given via authorization note, all probes in enabled categories are allowed.
 */
data class ActiveProbeSettings(
    // Master toggle - must be enabled before any active probe can fire
    val activeProbesEnabled: Boolean = false,

    // Per-category toggles
    val publicSafetyProbesEnabled: Boolean = false,
    val infrastructureProbesEnabled: Boolean = false,
    val industrialProbesEnabled: Boolean = false,
    val physicalAccessProbesEnabled: Boolean = false,
    val digitalProbesEnabled: Boolean = false,

    // Safety limits
    val maxLfDurationMs: Int = 5000,        // Max TPMS wake duration
    val maxIrStrobeDurationMs: Int = 10000, // Max Opticom strobe duration
    val maxReplayCount: Int = 10,           // Max sub-GHz replay iterations

    // Audit logging
    val logActiveProbeUsage: Boolean = true,

    // Global authorization/consent (for pentesting engagements)
    // Setting this note serves as consent for ALL active probes
    val authorizationNote: String = "",
    val authorizationTimestamp: Long = 0,

    // Authorization expiration (default 24 hours)
    val authorizationExpirationMs: Long = 24 * 60 * 60 * 1000L,

    // Geographic restriction (optional bounding box)
    val authorizationLatMin: Double? = null,
    val authorizationLatMax: Double? = null,
    val authorizationLonMin: Double? = null,
    val authorizationLonMax: Double? = null,

    // Authorization token hash for verification (SHA-256 of note+timestamp)
    val authorizationTokenHash: String = "",

    // Per-probe consent tracking
    val consentedProbes: Set<String> = emptySet()
) {
    /** Returns true if global consent has been given and is still valid */
    val hasGlobalConsent: Boolean
        get() = authorizationNote.isNotBlank() &&
                authorizationTimestamp > 0 &&
                !isAuthorizationExpired

    /** Returns true if authorization has expired */
    val isAuthorizationExpired: Boolean
        get() = authorizationTimestamp > 0 &&
                System.currentTimeMillis() - authorizationTimestamp > authorizationExpirationMs

    /** Returns remaining time until expiration in milliseconds, or 0 if expired */
    val authorizationRemainingMs: Long
        get() {
            if (authorizationTimestamp <= 0) return 0
            val elapsed = System.currentTimeMillis() - authorizationTimestamp
            return (authorizationExpirationMs - elapsed).coerceAtLeast(0)
        }

    /** Check if a location is within the authorized geofence (if set) */
    fun isLocationAuthorized(latitude: Double, longitude: Double): Boolean {
        // If no geofence is set, location is authorized
        if (authorizationLatMin == null || authorizationLatMax == null ||
            authorizationLonMin == null || authorizationLonMax == null) {
            return true
        }
        return latitude in authorizationLatMin..authorizationLatMax &&
               longitude in authorizationLonMin..authorizationLonMax
    }
}

@Singleton
class ActiveProbeSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val ACTIVE_PROBES_ENABLED = booleanPreferencesKey("active_probes_enabled")
        val PUBLIC_SAFETY_ENABLED = booleanPreferencesKey("public_safety_probes_enabled")
        val INFRASTRUCTURE_ENABLED = booleanPreferencesKey("infrastructure_probes_enabled")
        val INDUSTRIAL_ENABLED = booleanPreferencesKey("industrial_probes_enabled")
        val PHYSICAL_ACCESS_ENABLED = booleanPreferencesKey("physical_access_probes_enabled")
        val DIGITAL_ENABLED = booleanPreferencesKey("digital_probes_enabled")
        val MAX_LF_DURATION_MS = intPreferencesKey("max_lf_duration_ms")
        val MAX_IR_STROBE_DURATION_MS = intPreferencesKey("max_ir_strobe_duration_ms")
        val MAX_REPLAY_COUNT = intPreferencesKey("max_replay_count")
        val LOG_ACTIVE_PROBE_USAGE = booleanPreferencesKey("log_active_probe_usage")
        val AUTHORIZATION_NOTE = stringPreferencesKey("authorization_note")
        val AUTHORIZATION_TIMESTAMP = longPreferencesKey("authorization_timestamp")
        val AUTHORIZATION_EXPIRATION_MS = longPreferencesKey("authorization_expiration_ms")
        val AUTHORIZATION_TOKEN_HASH = stringPreferencesKey("authorization_token_hash")
        val AUTH_LAT_MIN = doublePreferencesKey("auth_lat_min")
        val AUTH_LAT_MAX = doublePreferencesKey("auth_lat_max")
        val AUTH_LON_MIN = doublePreferencesKey("auth_lon_min")
        val AUTH_LON_MAX = doublePreferencesKey("auth_lon_max")
        val CONSENTED_PROBES = stringSetPreferencesKey("consented_probes")
    }

    val settings: Flow<ActiveProbeSettings> = context.activeProbeDataStore.data.map { preferences ->
        ActiveProbeSettings(
            activeProbesEnabled = preferences[PreferencesKeys.ACTIVE_PROBES_ENABLED] ?: false,
            publicSafetyProbesEnabled = preferences[PreferencesKeys.PUBLIC_SAFETY_ENABLED] ?: false,
            infrastructureProbesEnabled = preferences[PreferencesKeys.INFRASTRUCTURE_ENABLED] ?: false,
            industrialProbesEnabled = preferences[PreferencesKeys.INDUSTRIAL_ENABLED] ?: false,
            physicalAccessProbesEnabled = preferences[PreferencesKeys.PHYSICAL_ACCESS_ENABLED] ?: false,
            digitalProbesEnabled = preferences[PreferencesKeys.DIGITAL_ENABLED] ?: false,
            maxLfDurationMs = preferences[PreferencesKeys.MAX_LF_DURATION_MS] ?: 5000,
            maxIrStrobeDurationMs = preferences[PreferencesKeys.MAX_IR_STROBE_DURATION_MS] ?: 10000,
            maxReplayCount = preferences[PreferencesKeys.MAX_REPLAY_COUNT] ?: 10,
            logActiveProbeUsage = preferences[PreferencesKeys.LOG_ACTIVE_PROBE_USAGE] ?: true,
            authorizationNote = preferences[PreferencesKeys.AUTHORIZATION_NOTE] ?: "",
            authorizationTimestamp = preferences[PreferencesKeys.AUTHORIZATION_TIMESTAMP] ?: 0,
            authorizationExpirationMs = preferences[PreferencesKeys.AUTHORIZATION_EXPIRATION_MS] ?: (24 * 60 * 60 * 1000L),
            authorizationLatMin = preferences[PreferencesKeys.AUTH_LAT_MIN],
            authorizationLatMax = preferences[PreferencesKeys.AUTH_LAT_MAX],
            authorizationLonMin = preferences[PreferencesKeys.AUTH_LON_MIN],
            authorizationLonMax = preferences[PreferencesKeys.AUTH_LON_MAX],
            authorizationTokenHash = preferences[PreferencesKeys.AUTHORIZATION_TOKEN_HASH] ?: "",
            consentedProbes = preferences[PreferencesKeys.CONSENTED_PROBES] ?: emptySet()
        )
    }

    /**
     * Enable/disable all active probes (master toggle).
     */
    suspend fun setActiveProbesEnabled(enabled: Boolean) {
        context.activeProbeDataStore.edit { preferences ->
            preferences[PreferencesKeys.ACTIVE_PROBES_ENABLED] = enabled
        }
    }

    /**
     * Enable/disable a specific category of probes.
     */
    suspend fun setCategoryEnabled(category: ProbeCategory, enabled: Boolean) {
        context.activeProbeDataStore.edit { preferences ->
            val key = when (category) {
                ProbeCategory.PUBLIC_SAFETY -> PreferencesKeys.PUBLIC_SAFETY_ENABLED
                ProbeCategory.INFRASTRUCTURE -> PreferencesKeys.INFRASTRUCTURE_ENABLED
                ProbeCategory.INDUSTRIAL -> PreferencesKeys.INDUSTRIAL_ENABLED
                ProbeCategory.PHYSICAL_ACCESS -> PreferencesKeys.PHYSICAL_ACCESS_ENABLED
                ProbeCategory.DIGITAL -> PreferencesKeys.DIGITAL_ENABLED
            }
            preferences[key] = enabled
        }
    }

    /**
     * Check if a probe is allowed to execute based on current settings.
     * With global consent model, once authorization is given all probes in enabled categories are allowed.
     *
     * @param settings The current active probe settings
     * @param probeId The probe to check
     * @param currentLatitude Optional current latitude for geofence check
     * @param currentLongitude Optional current longitude for geofence check
     */
    fun isProbeAllowed(
        settings: ActiveProbeSettings,
        probeId: String,
        currentLatitude: Double? = null,
        currentLongitude: Double? = null
    ): ProbeAllowedResult {
        val probe = ProbeCatalog.getById(probeId)
            ?: return ProbeAllowedResult.Denied("Unknown probe: $probeId")

        // Master toggle check
        if (!settings.activeProbesEnabled && probe.type != ProbeType.PASSIVE) {
            return ProbeAllowedResult.Denied("Active probes are disabled. Enable in settings first.")
        }

        // Global consent check - authorization note serves as consent for all probes
        if (!settings.hasGlobalConsent && probe.type != ProbeType.PASSIVE) {
            return ProbeAllowedResult.Denied("Global authorization required. Enable Active Probes to provide consent.")
        }

        // Check if authorization has expired
        if (settings.isAuthorizationExpired && probe.type != ProbeType.PASSIVE) {
            return ProbeAllowedResult.Denied("Authorization has expired. Please re-authorize to continue.")
        }

        // Geofence check - if location is provided and geofence is set, verify location
        if (currentLatitude != null && currentLongitude != null && probe.type != ProbeType.PASSIVE) {
            if (!settings.isLocationAuthorized(currentLatitude, currentLongitude)) {
                return ProbeAllowedResult.Denied("Current location is outside the authorized geofence.")
            }
        }

        // Category toggle check
        val categoryEnabled = when (probe.category) {
            ProbeCategory.PUBLIC_SAFETY -> settings.publicSafetyProbesEnabled
            ProbeCategory.INFRASTRUCTURE -> settings.infrastructureProbesEnabled
            ProbeCategory.INDUSTRIAL -> settings.industrialProbesEnabled
            ProbeCategory.PHYSICAL_ACCESS -> settings.physicalAccessProbesEnabled
            ProbeCategory.DIGITAL -> settings.digitalProbesEnabled
        }
        if (!categoryEnabled && probe.type != ProbeType.PASSIVE) {
            return ProbeAllowedResult.Denied("${probe.category.displayName} probes are disabled.")
        }

        return ProbeAllowedResult.Allowed
    }

    /**
     * Set authorization context (for pentesting engagements).
     *
     * @param note Authorization note/justification
     * @param expirationHours How long the authorization is valid (default 24 hours)
     * @param geofence Optional bounding box to restrict probe execution geographically
     */
    suspend fun setAuthorizationContext(
        note: String,
        expirationHours: Int = 24,
        geofence: GeofenceBounds? = null
    ) {
        val timestamp = System.currentTimeMillis()
        val expirationMs = expirationHours.toLong() * 60 * 60 * 1000

        // Generate a hash for verification (note + timestamp)
        val tokenHash = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest("$note:$timestamp".toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }

        context.activeProbeDataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTHORIZATION_NOTE] = note
            preferences[PreferencesKeys.AUTHORIZATION_TIMESTAMP] = timestamp
            preferences[PreferencesKeys.AUTHORIZATION_EXPIRATION_MS] = expirationMs
            preferences[PreferencesKeys.AUTHORIZATION_TOKEN_HASH] = tokenHash

            // Set geofence if provided
            if (geofence != null) {
                preferences[PreferencesKeys.AUTH_LAT_MIN] = geofence.latMin
                preferences[PreferencesKeys.AUTH_LAT_MAX] = geofence.latMax
                preferences[PreferencesKeys.AUTH_LON_MIN] = geofence.lonMin
                preferences[PreferencesKeys.AUTH_LON_MAX] = geofence.lonMax
            } else {
                preferences.remove(PreferencesKeys.AUTH_LAT_MIN)
                preferences.remove(PreferencesKeys.AUTH_LAT_MAX)
                preferences.remove(PreferencesKeys.AUTH_LON_MIN)
                preferences.remove(PreferencesKeys.AUTH_LON_MAX)
            }
        }
    }

    /**
     * Geographic bounding box for authorization geofencing.
     */
    data class GeofenceBounds(
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double
    )

    /**
     * Clear authorization/consent and disable active probes.
     */
    suspend fun clearAuthorization() {
        context.activeProbeDataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTHORIZATION_NOTE] = ""
            preferences[PreferencesKeys.AUTHORIZATION_TIMESTAMP] = 0
            preferences[PreferencesKeys.ACTIVE_PROBES_ENABLED] = false
            preferences[PreferencesKeys.CONSENTED_PROBES] = emptySet()
        }
    }

    /**
     * Grant consent for a specific probe.
     */
    suspend fun grantProbeConsent(probeId: String) {
        context.activeProbeDataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.CONSENTED_PROBES] ?: emptySet()
            preferences[PreferencesKeys.CONSENTED_PROBES] = current + probeId
        }
    }

    /**
     * Revoke consent for a specific probe.
     */
    suspend fun revokeProbeConsent(probeId: String) {
        context.activeProbeDataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.CONSENTED_PROBES] ?: emptySet()
            preferences[PreferencesKeys.CONSENTED_PROBES] = current - probeId
        }
    }

    /**
     * Update safety limits.
     */
    suspend fun setSafetyLimits(
        maxLfDurationMs: Int? = null,
        maxIrStrobeDurationMs: Int? = null,
        maxReplayCount: Int? = null
    ) {
        context.activeProbeDataStore.edit { preferences ->
            maxLfDurationMs?.let {
                preferences[PreferencesKeys.MAX_LF_DURATION_MS] = it.coerceIn(100, 5000)
            }
            maxIrStrobeDurationMs?.let {
                preferences[PreferencesKeys.MAX_IR_STROBE_DURATION_MS] = it.coerceIn(100, 10000)
            }
            maxReplayCount?.let {
                preferences[PreferencesKeys.MAX_REPLAY_COUNT] = it.coerceIn(1, 100)
            }
        }
    }
}

/**
 * Result of checking if a probe is allowed to execute.
 */
sealed class ProbeAllowedResult {
    object Allowed : ProbeAllowedResult()
    data class Denied(val reason: String) : ProbeAllowedResult()
}

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
 * All active probes are disabled by default and require explicit user consent.
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

    // Consent tracking - which dangerous probes has user explicitly acknowledged
    val consentedProbes: Set<String> = emptySet(),

    // Safety limits
    val maxLfDurationMs: Int = 5000,        // Max TPMS wake duration
    val maxIrStrobeDurationMs: Int = 10000, // Max Opticom strobe duration
    val maxReplayCount: Int = 10,           // Max sub-GHz replay iterations

    // Audit logging
    val logActiveProbeUsage: Boolean = true,

    // Authorization context (for pentesting engagements)
    val authorizationNote: String = "",
    val authorizationTimestamp: Long = 0
)

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
        val CONSENTED_PROBES = stringSetPreferencesKey("consented_probes")
        val MAX_LF_DURATION_MS = intPreferencesKey("max_lf_duration_ms")
        val MAX_IR_STROBE_DURATION_MS = intPreferencesKey("max_ir_strobe_duration_ms")
        val MAX_REPLAY_COUNT = intPreferencesKey("max_replay_count")
        val LOG_ACTIVE_PROBE_USAGE = booleanPreferencesKey("log_active_probe_usage")
        val AUTHORIZATION_NOTE = stringPreferencesKey("authorization_note")
        val AUTHORIZATION_TIMESTAMP = longPreferencesKey("authorization_timestamp")
    }

    val settings: Flow<ActiveProbeSettings> = context.activeProbeDataStore.data.map { preferences ->
        ActiveProbeSettings(
            activeProbesEnabled = preferences[PreferencesKeys.ACTIVE_PROBES_ENABLED] ?: false,
            publicSafetyProbesEnabled = preferences[PreferencesKeys.PUBLIC_SAFETY_ENABLED] ?: false,
            infrastructureProbesEnabled = preferences[PreferencesKeys.INFRASTRUCTURE_ENABLED] ?: false,
            industrialProbesEnabled = preferences[PreferencesKeys.INDUSTRIAL_ENABLED] ?: false,
            physicalAccessProbesEnabled = preferences[PreferencesKeys.PHYSICAL_ACCESS_ENABLED] ?: false,
            digitalProbesEnabled = preferences[PreferencesKeys.DIGITAL_ENABLED] ?: false,
            consentedProbes = preferences[PreferencesKeys.CONSENTED_PROBES] ?: emptySet(),
            maxLfDurationMs = preferences[PreferencesKeys.MAX_LF_DURATION_MS] ?: 5000,
            maxIrStrobeDurationMs = preferences[PreferencesKeys.MAX_IR_STROBE_DURATION_MS] ?: 10000,
            maxReplayCount = preferences[PreferencesKeys.MAX_REPLAY_COUNT] ?: 10,
            logActiveProbeUsage = preferences[PreferencesKeys.LOG_ACTIVE_PROBE_USAGE] ?: true,
            authorizationNote = preferences[PreferencesKeys.AUTHORIZATION_NOTE] ?: "",
            authorizationTimestamp = preferences[PreferencesKeys.AUTHORIZATION_TIMESTAMP] ?: 0
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
     * Record user consent for a specific probe after showing warning dialog.
     */
    suspend fun recordProbeConsent(probeId: String) {
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
     * Check if user has consented to a specific probe.
     */
    fun hasConsent(settings: ActiveProbeSettings, probeId: String): Boolean {
        val probe = ProbeCatalog.getById(probeId) ?: return false
        if (!probe.requiresConsent) return true
        return probeId in settings.consentedProbes
    }

    /**
     * Check if a probe is allowed to execute based on current settings.
     */
    fun isProbeAllowed(settings: ActiveProbeSettings, probeId: String): ProbeAllowedResult {
        val probe = ProbeCatalog.getById(probeId)
            ?: return ProbeAllowedResult.Denied("Unknown probe: $probeId")

        // Master toggle check
        if (!settings.activeProbesEnabled && probe.type != ProbeType.PASSIVE) {
            return ProbeAllowedResult.Denied("Active probes are disabled. Enable in settings first.")
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

        // Consent check for dangerous probes
        if (probe.requiresConsent && probeId !in settings.consentedProbes) {
            return ProbeAllowedResult.RequiresConsent(probe.consentWarning ?: "This probe requires consent.")
        }

        return ProbeAllowedResult.Allowed
    }

    /**
     * Set authorization context (for pentesting engagements).
     */
    suspend fun setAuthorizationContext(note: String) {
        context.activeProbeDataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTHORIZATION_NOTE] = note
            preferences[PreferencesKeys.AUTHORIZATION_TIMESTAMP] = System.currentTimeMillis()
        }
    }

    /**
     * Clear all consent and authorization data.
     */
    suspend fun clearAllConsent() {
        context.activeProbeDataStore.edit { preferences ->
            preferences[PreferencesKeys.CONSENTED_PROBES] = emptySet()
            preferences[PreferencesKeys.AUTHORIZATION_NOTE] = ""
            preferences[PreferencesKeys.AUTHORIZATION_TIMESTAMP] = 0
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
    data class RequiresConsent(val warning: String) : ProbeAllowedResult()
    data class Denied(val reason: String) : ProbeAllowedResult()
}

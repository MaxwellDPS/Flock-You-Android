package com.flockyou.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.flipperUiDataStore: DataStore<Preferences> by preferencesDataStore(name = "flipper_ui_settings")

/**
 * View mode for the Flipper tab
 */
enum class FlipperViewMode {
    DETAILED,  // Full card view with all cards expanded by default
    SUMMARY    // Compact single-card summary view
}

/**
 * UI settings for the Flipper tab
 */
data class FlipperUiSettings(
    val viewMode: FlipperViewMode = FlipperViewMode.DETAILED,
    val statusCardExpanded: Boolean = true,
    val schedulerCardExpanded: Boolean = true,
    val statsCardExpanded: Boolean = true,
    val capabilitiesCardExpanded: Boolean = true,
    val advancedCardExpanded: Boolean = false  // Collapsed by default
)

@Singleton
class FlipperUiSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val VIEW_MODE = stringPreferencesKey("flipper_view_mode")
        val STATUS_CARD_EXPANDED = booleanPreferencesKey("flipper_status_card_expanded")
        val SCHEDULER_CARD_EXPANDED = booleanPreferencesKey("flipper_scheduler_card_expanded")
        val STATS_CARD_EXPANDED = booleanPreferencesKey("flipper_stats_card_expanded")
        val CAPABILITIES_CARD_EXPANDED = booleanPreferencesKey("flipper_capabilities_card_expanded")
        val ADVANCED_CARD_EXPANDED = booleanPreferencesKey("flipper_advanced_card_expanded")
    }

    val settings: Flow<FlipperUiSettings> = context.flipperUiDataStore.data.map { prefs ->
        FlipperUiSettings(
            viewMode = prefs[Keys.VIEW_MODE]?.let {
                try { FlipperViewMode.valueOf(it) } catch (e: Exception) { FlipperViewMode.DETAILED }
            } ?: FlipperViewMode.DETAILED,
            statusCardExpanded = prefs[Keys.STATUS_CARD_EXPANDED] ?: true,
            schedulerCardExpanded = prefs[Keys.SCHEDULER_CARD_EXPANDED] ?: true,
            statsCardExpanded = prefs[Keys.STATS_CARD_EXPANDED] ?: true,
            capabilitiesCardExpanded = prefs[Keys.CAPABILITIES_CARD_EXPANDED] ?: true,
            advancedCardExpanded = prefs[Keys.ADVANCED_CARD_EXPANDED] ?: false
        )
    }

    suspend fun setViewMode(mode: FlipperViewMode) {
        context.flipperUiDataStore.edit { prefs ->
            prefs[Keys.VIEW_MODE] = mode.name
        }
    }

    suspend fun setStatusCardExpanded(expanded: Boolean) {
        context.flipperUiDataStore.edit { prefs ->
            prefs[Keys.STATUS_CARD_EXPANDED] = expanded
        }
    }

    suspend fun setSchedulerCardExpanded(expanded: Boolean) {
        context.flipperUiDataStore.edit { prefs ->
            prefs[Keys.SCHEDULER_CARD_EXPANDED] = expanded
        }
    }

    suspend fun setStatsCardExpanded(expanded: Boolean) {
        context.flipperUiDataStore.edit { prefs ->
            prefs[Keys.STATS_CARD_EXPANDED] = expanded
        }
    }

    suspend fun setCapabilitiesCardExpanded(expanded: Boolean) {
        context.flipperUiDataStore.edit { prefs ->
            prefs[Keys.CAPABILITIES_CARD_EXPANDED] = expanded
        }
    }

    suspend fun setAdvancedCardExpanded(expanded: Boolean) {
        context.flipperUiDataStore.edit { prefs ->
            prefs[Keys.ADVANCED_CARD_EXPANDED] = expanded
        }
    }

    suspend fun resetToDefaults() {
        context.flipperUiDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

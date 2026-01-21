package com.flockyou.scanner.flipper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.flipperOnboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "flipper_onboarding")

/**
 * Settings for Flipper Zero onboarding experience.
 * Tracks whether user has completed setup wizard and other first-time UX flags.
 */
data class FlipperOnboardingSettings(
    /**
     * Whether the user has completed or dismissed the setup wizard.
     * When false, show the wizard on first visit to Flipper tab.
     */
    val hasCompletedSetupWizard: Boolean = false,

    /**
     * Whether the user has ever successfully connected a Flipper device.
     * Used to determine if they need the full onboarding or just reconnection help.
     */
    val hasEverConnected: Boolean = false,

    /**
     * Whether to show scan type tips/tooltips.
     * Users can dismiss these after learning what each scan does.
     */
    val showScanTypeTips: Boolean = true,

    /**
     * Whether to show the "What does this detect?" expandable section.
     */
    val showDetectionExplanations: Boolean = true,

    /**
     * Number of times the user has visited the Flipper tab.
     * Used for progressive disclosure of features.
     */
    val flipperTabVisitCount: Int = 0
)

@Singleton
class FlipperOnboardingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val HAS_COMPLETED_SETUP_WIZARD = booleanPreferencesKey("has_completed_setup_wizard")
        val HAS_EVER_CONNECTED = booleanPreferencesKey("has_ever_connected")
        val SHOW_SCAN_TYPE_TIPS = booleanPreferencesKey("show_scan_type_tips")
        val SHOW_DETECTION_EXPLANATIONS = booleanPreferencesKey("show_detection_explanations")
        val FLIPPER_TAB_VISIT_COUNT = intPreferencesKey("flipper_tab_visit_count")
    }

    val settings: Flow<FlipperOnboardingSettings> = context.flipperOnboardingDataStore.data.map { preferences ->
        FlipperOnboardingSettings(
            hasCompletedSetupWizard = preferences[PreferencesKeys.HAS_COMPLETED_SETUP_WIZARD] ?: false,
            hasEverConnected = preferences[PreferencesKeys.HAS_EVER_CONNECTED] ?: false,
            showScanTypeTips = preferences[PreferencesKeys.SHOW_SCAN_TYPE_TIPS] ?: true,
            showDetectionExplanations = preferences[PreferencesKeys.SHOW_DETECTION_EXPLANATIONS] ?: true,
            flipperTabVisitCount = preferences[PreferencesKeys.FLIPPER_TAB_VISIT_COUNT] ?: 0
        )
    }

    /**
     * Mark the setup wizard as completed (or dismissed with "Don't show again").
     */
    suspend fun setSetupWizardCompleted(completed: Boolean) {
        context.flipperOnboardingDataStore.edit { preferences ->
            preferences[PreferencesKeys.HAS_COMPLETED_SETUP_WIZARD] = completed
        }
    }

    /**
     * Record that user has successfully connected a Flipper device at least once.
     */
    suspend fun setHasEverConnected(connected: Boolean) {
        context.flipperOnboardingDataStore.edit { preferences ->
            preferences[PreferencesKeys.HAS_EVER_CONNECTED] = connected
        }
    }

    /**
     * Toggle scan type tips visibility.
     */
    suspend fun setShowScanTypeTips(show: Boolean) {
        context.flipperOnboardingDataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_SCAN_TYPE_TIPS] = show
        }
    }

    /**
     * Toggle detection explanations visibility.
     */
    suspend fun setShowDetectionExplanations(show: Boolean) {
        context.flipperOnboardingDataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_DETECTION_EXPLANATIONS] = show
        }
    }

    /**
     * Increment the Flipper tab visit count.
     */
    suspend fun incrementFlipperTabVisitCount() {
        context.flipperOnboardingDataStore.edit { preferences ->
            val currentCount = preferences[PreferencesKeys.FLIPPER_TAB_VISIT_COUNT] ?: 0
            preferences[PreferencesKeys.FLIPPER_TAB_VISIT_COUNT] = currentCount + 1
        }
    }

    /**
     * Reset all onboarding state (for testing or user request).
     */
    suspend fun resetOnboarding() {
        context.flipperOnboardingDataStore.edit { preferences ->
            preferences.clear()
        }
    }
}

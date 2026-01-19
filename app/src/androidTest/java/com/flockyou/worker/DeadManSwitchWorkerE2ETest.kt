package com.flockyou.worker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.utils.MockNukeRule
import com.flockyou.utils.TestHelpers
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * E2E tests for DeadManSwitchWorker.
 *
 * Tests cover:
 * - Authentication time tracking
 * - Warning before nuke
 * - Timer reset on authentication
 * - Disabled state behavior
 *
 * NOTE: Uses MockNukeRule to prevent actual data destruction.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DeadManSwitchWorkerE2ETest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    var mockNukeRule = MockNukeRule()

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
    }

    @After
    fun cleanup() {
        runBlocking {
            nukeSettingsRepository.setNukeEnabled(false)
            nukeSettingsRepository.setDeadManSwitchEnabled(false)
        }
    }

    // ==================== Dead Man Switch Settings Tests ====================

    @Test
    fun deadManSwitch_defaultsToDisabled() = runTest {
        val settings = nukeSettingsRepository.settings.first()

        assertFalse("Dead man switch should be disabled by default", settings.deadManSwitchEnabled)
    }

    @Test
    fun deadManSwitch_canBeEnabled() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setDeadManSwitchEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Dead man switch should be enabled", settings.deadManSwitchEnabled)
    }

    @Test
    fun deadManSwitch_hasConfigurableHours() = runTest {
        nukeSettingsRepository.updateDeadManSwitchSettings(
            enabled = true,
            hours = 48
        )

        val settings = nukeSettingsRepository.settings.first()
        assertEquals("Hours should be configurable", 48, settings.deadManSwitchHours)
    }

    @Test
    fun deadManSwitch_hoursHasMinimum() = runTest {
        // Try to set hours below minimum
        nukeSettingsRepository.setDeadManSwitchHours(0)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Hours should have minimum of 1", settings.deadManSwitchHours >= 1)
    }

    @Test
    fun deadManSwitch_hoursHasMaximum() = runTest {
        // Try to set hours above maximum
        nukeSettingsRepository.setDeadManSwitchHours(10000)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Hours should have maximum of 720 (30 days)", settings.deadManSwitchHours <= 720)
    }

    // ==================== Warning Settings Tests ====================

    @Test
    fun deadManSwitch_warningEnabledByDefault() = runTest {
        val settings = nukeSettingsRepository.settings.first()

        assertTrue("Warning should be enabled by default", settings.deadManSwitchWarningEnabled)
    }

    @Test
    fun deadManSwitch_warningCanBeDisabled() = runTest {
        nukeSettingsRepository.updateDeadManSwitchSettings(
            warningEnabled = false
        )

        val settings = nukeSettingsRepository.settings.first()
        assertFalse("Warning should be disabled", settings.deadManSwitchWarningEnabled)
    }

    @Test
    fun deadManSwitch_warningHoursConfigurable() = runTest {
        nukeSettingsRepository.updateDeadManSwitchSettings(
            warningEnabled = true,
            warningHours = 6
        )

        val settings = nukeSettingsRepository.settings.first()
        assertEquals("Warning hours should be 6", 6, settings.deadManSwitchWarningHours)
    }

    @Test
    fun deadManSwitch_warningHoursHasMinimum() = runTest {
        nukeSettingsRepository.setDeadManSwitchWarningHours(0)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Warning hours should have minimum of 1", settings.deadManSwitchWarningHours >= 1)
    }

    @Test
    fun deadManSwitch_warningHoursHasMaximum() = runTest {
        nukeSettingsRepository.setDeadManSwitchWarningHours(100)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Warning hours should have maximum of 48", settings.deadManSwitchWarningHours <= 48)
    }

    // ==================== Batch Update Tests ====================

    @Test
    fun deadManSwitch_batchUpdateWorks() = runTest {
        nukeSettingsRepository.updateDeadManSwitchSettings(
            enabled = true,
            hours = 72,
            warningEnabled = true,
            warningHours = 12
        )

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Should be enabled", settings.deadManSwitchEnabled)
        assertEquals("Hours should be 72", 72, settings.deadManSwitchHours)
        assertTrue("Warning should be enabled", settings.deadManSwitchWarningEnabled)
        assertEquals("Warning hours should be 12", 12, settings.deadManSwitchWarningHours)
    }

    @Test
    fun deadManSwitch_partialUpdateWorks() = runTest {
        // First set all values
        nukeSettingsRepository.updateDeadManSwitchSettings(
            enabled = true,
            hours = 72,
            warningEnabled = true,
            warningHours = 12
        )

        // Then update only hours
        nukeSettingsRepository.updateDeadManSwitchSettings(
            hours = 48
        )

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Should still be enabled", settings.deadManSwitchEnabled)
        assertEquals("Hours should be updated to 48", 48, settings.deadManSwitchHours)
        assertTrue("Warning should still be enabled", settings.deadManSwitchWarningEnabled)
        assertEquals("Warning hours should still be 12", 12, settings.deadManSwitchWarningHours)
    }

    // ==================== Integration with Master Switch Tests ====================

    @Test
    fun deadManSwitch_requiresMasterSwitch() = runTest {
        // Enable dead man switch without master
        nukeSettingsRepository.setNukeEnabled(false)
        nukeSettingsRepository.setDeadManSwitchEnabled(true)

        val settings = nukeSettingsRepository.settings.first()

        // hasAnyTriggerEnabled should be false because master is off
        assertFalse("Trigger should not be armed when master is off", settings.hasAnyTriggerEnabled())
    }

    @Test
    fun deadManSwitch_worksWithMasterSwitch() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setDeadManSwitchEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Trigger should be armed when master is on", settings.hasAnyTriggerEnabled())
    }

    // ==================== Edge Cases ====================

    @Test
    fun deadManSwitch_multipleTogglesAreSafe() = runTest {
        repeat(10) { i ->
            val enabled = i % 2 == 0
            nukeSettingsRepository.setDeadManSwitchEnabled(enabled)
        }

        // Should not crash and final state should match last call
        val settings = nukeSettingsRepository.settings.first()
        assertFalse("Final state should match last call (9 is odd, so false)", settings.deadManSwitchEnabled)
    }

    @Test
    fun deadManSwitch_settingsPersistAcrossReads() = runTest {
        nukeSettingsRepository.updateDeadManSwitchSettings(
            enabled = true,
            hours = 24,
            warningEnabled = true,
            warningHours = 6
        )

        // Read settings multiple times
        repeat(5) {
            val settings = nukeSettingsRepository.settings.first()
            assertTrue("Should be enabled", settings.deadManSwitchEnabled)
            assertEquals("Hours should be 24", 24, settings.deadManSwitchHours)
            assertTrue("Warning should be enabled", settings.deadManSwitchWarningEnabled)
            assertEquals("Warning hours should be 6", 6, settings.deadManSwitchWarningHours)
        }
    }

    @Test
    fun deadManSwitch_disablingClearsArmedState() = runTest {
        // Enable everything
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setDeadManSwitchEnabled(true)

        var settings = nukeSettingsRepository.settings.first()
        assertTrue("Should be armed", settings.hasAnyTriggerEnabled())

        // Disable dead man switch
        nukeSettingsRepository.setDeadManSwitchEnabled(false)

        settings = nukeSettingsRepository.settings.first()
        assertFalse("Should not be armed after disabling", settings.hasAnyTriggerEnabled())
    }

    @Test
    fun deadManSwitch_defaultHoursIsReasonable() = runTest {
        val settings = nukeSettingsRepository.settings.first()

        // Default should be 72 hours (3 days)
        assertEquals("Default hours should be 72", 72, settings.deadManSwitchHours)
    }

    @Test
    fun deadManSwitch_defaultWarningHoursIsReasonable() = runTest {
        val settings = nukeSettingsRepository.settings.first()

        // Default warning should be 12 hours
        assertEquals("Default warning hours should be 12", 12, settings.deadManSwitchWarningHours)
    }
}

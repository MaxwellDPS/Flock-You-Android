package com.flockyou.nuke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.NukeSettingsRepository
import com.flockyou.security.NukeManager
import com.flockyou.security.NukeTriggerSource
import com.flockyou.utils.MockNukeRule
import com.flockyou.utils.TestDataFactory
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
 * E2E tests for nuke trigger mechanisms.
 *
 * Tests cover:
 * - USB watchdog trigger
 * - SIM state trigger
 * - Network isolation trigger
 * - Geofence trigger
 * - Rapid reboot trigger
 * - Trigger delay configuration
 * - Trigger disabling
 *
 * NOTE: Uses MockNukeRule to prevent actual data destruction.
 * These tests verify settings and trigger configuration, NOT actual nuke execution.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NukeTriggersE2ETest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    var mockNukeRule = MockNukeRule()

    @Inject
    lateinit var nukeSettingsRepository: NukeSettingsRepository

    @Inject
    lateinit var nukeManager: NukeManager

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        runBlocking {
            nukeSettingsRepository.setNukeEnabled(false)
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            nukeSettingsRepository.setNukeEnabled(false)
        }
    }

    // ==================== USB Trigger Tests ====================

    @Test
    fun usbTrigger_defaultsToDisabled() = runTest {
        val settings = nukeSettingsRepository.settings.first()
        assertFalse("USB trigger should be disabled by default", settings.usbTriggerEnabled)
    }

    @Test
    fun usbTrigger_canBeEnabled() = runTest {
        nukeSettingsRepository.setUsbTriggerEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("USB trigger should be enabled", settings.usbTriggerEnabled)
    }

    @Test
    fun usbTrigger_dataConnectionConfigurable() = runTest {
        nukeSettingsRepository.updateUsbTriggerSettings(
            enabled = true,
            onDataConnection = true
        )

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Data connection trigger should be enabled", settings.usbTriggerOnDataConnection)
    }

    @Test
    fun usbTrigger_adbConnectionConfigurable() = runTest {
        nukeSettingsRepository.updateUsbTriggerSettings(
            enabled = true,
            onAdbConnection = true
        )

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("ADB connection trigger should be enabled", settings.usbTriggerOnAdbConnection)
    }

    @Test
    fun usbTrigger_delayConfigurable() = runTest {
        nukeSettingsRepository.updateUsbTriggerSettings(
            enabled = true,
            delaySeconds = 30
        )

        val settings = nukeSettingsRepository.settings.first()
        assertEquals("Delay should be 30 seconds", 30, settings.usbTriggerDelaySeconds)
    }

    @Test
    fun usbTrigger_delayHasMaximum() = runTest {
        nukeSettingsRepository.setUsbTriggerDelaySeconds(1000)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Delay should have maximum of 60", settings.usbTriggerDelaySeconds <= 60)
    }

    @Test
    fun usbTrigger_delayHasMinimum() = runTest {
        nukeSettingsRepository.setUsbTriggerDelaySeconds(-10)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Delay should have minimum of 0", settings.usbTriggerDelaySeconds >= 0)
    }

    // ==================== SIM Removal Trigger Tests ====================

    @Test
    fun simTrigger_defaultsToDisabled() = runTest {
        val settings = nukeSettingsRepository.settings.first()
        assertFalse("SIM trigger should be disabled by default", settings.simRemovalTriggerEnabled)
    }

    @Test
    fun simTrigger_canBeEnabled() = runTest {
        nukeSettingsRepository.setSimRemovalTriggerEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("SIM trigger should be enabled", settings.simRemovalTriggerEnabled)
    }

    @Test
    fun simTrigger_delayConfigurable() = runTest {
        nukeSettingsRepository.setSimRemovalDelaySeconds(600)

        val settings = nukeSettingsRepository.settings.first()
        assertEquals("SIM removal delay should be 600 seconds", 600, settings.simRemovalDelaySeconds)
    }

    @Test
    fun simTrigger_previouslyPresentConfigurable() = runTest {
        nukeSettingsRepository.setSimRemovalTriggerOnPreviouslyPresent(false)

        val settings = nukeSettingsRepository.settings.first()
        assertFalse("Previously present setting should be false", settings.simRemovalTriggerOnPreviouslyPresent)
    }

    // ==================== Network Isolation Trigger Tests ====================

    @Test
    fun networkIsolation_defaultsToDisabled() = runTest {
        val settings = nukeSettingsRepository.settings.first()
        assertFalse("Network isolation trigger should be disabled", settings.networkIsolationTriggerEnabled)
    }

    @Test
    fun networkIsolation_canBeEnabled() = runTest {
        nukeSettingsRepository.setNetworkIsolationTriggerEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Network isolation trigger should be enabled", settings.networkIsolationTriggerEnabled)
    }

    @Test
    fun networkIsolation_hoursConfigurable() = runTest {
        nukeSettingsRepository.setNetworkIsolationHours(8)

        val settings = nukeSettingsRepository.settings.first()
        assertEquals("Isolation hours should be 8", 8, settings.networkIsolationHours)
    }

    @Test
    fun networkIsolation_requireBothConfigurable() = runTest {
        nukeSettingsRepository.setNetworkIsolationRequireBoth(false)

        val settings = nukeSettingsRepository.settings.first()
        assertFalse("Require both should be false", settings.networkIsolationRequireBoth)
    }

    // ==================== Rapid Reboot Trigger Tests ====================

    @Test
    fun rapidReboot_defaultsToDisabled() = runTest {
        val settings = nukeSettingsRepository.settings.first()
        assertFalse("Rapid reboot trigger should be disabled", settings.rapidRebootTriggerEnabled)
    }

    @Test
    fun rapidReboot_canBeEnabled() = runTest {
        nukeSettingsRepository.setRapidRebootTriggerEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Rapid reboot trigger should be enabled", settings.rapidRebootTriggerEnabled)
    }

    @Test
    fun rapidReboot_countConfigurable() = runTest {
        nukeSettingsRepository.setRapidRebootCount(5)

        val settings = nukeSettingsRepository.settings.first()
        assertEquals("Reboot count should be 5", 5, settings.rapidRebootCount)
    }

    @Test
    fun rapidReboot_windowConfigurable() = runTest {
        nukeSettingsRepository.setRapidRebootWindowMinutes(15)

        val settings = nukeSettingsRepository.settings.first()
        assertEquals("Window should be 15 minutes", 15, settings.rapidRebootWindowMinutes)
    }

    // ==================== Geofence Trigger Tests ====================

    @Test
    fun geofence_defaultsToDisabled() = runTest {
        val settings = nukeSettingsRepository.settings.first()
        assertFalse("Geofence trigger should be disabled", settings.geofenceTriggerEnabled)
    }

    @Test
    fun geofence_canBeEnabled() = runTest {
        nukeSettingsRepository.setGeofenceTriggerEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Geofence trigger should be enabled", settings.geofenceTriggerEnabled)
    }

    @Test
    fun geofence_dangerZonesCanBeAdded() = runTest {
        val zone = TestDataFactory.createDangerZone(name = "Test Zone")
        nukeSettingsRepository.addDangerZone(zone)

        val settings = nukeSettingsRepository.settings.first()
        val zones = settings.getDangerZones()

        assertEquals("Should have 1 danger zone", 1, zones.size)
        assertEquals("Zone name should match", "Test Zone", zones[0].name)
    }

    @Test
    fun geofence_dangerZonesCanBeRemoved() = runTest {
        val zone = TestDataFactory.createDangerZone(id = "test-zone-1")
        nukeSettingsRepository.addDangerZone(zone)

        var settings = nukeSettingsRepository.settings.first()
        assertEquals("Should have 1 zone", 1, settings.getDangerZones().size)

        nukeSettingsRepository.removeDangerZone("test-zone-1")

        settings = nukeSettingsRepository.settings.first()
        assertTrue("Should have no zones", settings.getDangerZones().isEmpty())
    }

    @Test
    fun geofence_multipleDangerZonesSupported() = runTest {
        val zone1 = TestDataFactory.createDangerZone(id = "zone-1", name = "Police HQ")
        val zone2 = TestDataFactory.createDangerZone(id = "zone-2", name = "Border Checkpoint")
        val zone3 = TestDataFactory.createDangerZone(id = "zone-3", name = "FBI Building")

        nukeSettingsRepository.addDangerZone(zone1)
        nukeSettingsRepository.addDangerZone(zone2)
        nukeSettingsRepository.addDangerZone(zone3)

        val settings = nukeSettingsRepository.settings.first()
        val zones = settings.getDangerZones()

        assertEquals("Should have 3 zones", 3, zones.size)
    }

    @Test
    fun geofence_delayConfigurable() = runTest {
        nukeSettingsRepository.setGeofenceTriggerDelaySeconds(60)

        val settings = nukeSettingsRepository.settings.first()
        assertEquals("Delay should be 60 seconds", 60, settings.geofenceTriggerDelaySeconds)
    }

    // ==================== Master Switch Tests ====================

    @Test
    fun triggers_allDisabledWhenMasterOff() = runTest {
        // Enable all triggers
        nukeSettingsRepository.setUsbTriggerEnabled(true)
        nukeSettingsRepository.setSimRemovalTriggerEnabled(true)
        nukeSettingsRepository.setNetworkIsolationTriggerEnabled(true)
        nukeSettingsRepository.setRapidRebootTriggerEnabled(true)
        nukeSettingsRepository.setGeofenceTriggerEnabled(true)

        // But keep master off
        nukeSettingsRepository.setNukeEnabled(false)

        val settings = nukeSettingsRepository.settings.first()
        assertFalse("No triggers should be armed when master is off", settings.hasAnyTriggerEnabled())
    }

    @Test
    fun triggers_armedWhenMasterOn() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setUsbTriggerEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("Trigger should be armed when master is on", settings.hasAnyTriggerEnabled())
    }

    // ==================== Multiple Triggers Tests ====================

    @Test
    fun triggers_multipleCanBeEnabled() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setUsbTriggerEnabled(true)
        nukeSettingsRepository.setSimRemovalTriggerEnabled(true)
        nukeSettingsRepository.setNetworkIsolationTriggerEnabled(true)

        val settings = nukeSettingsRepository.settings.first()
        assertTrue("USB trigger should be enabled", settings.usbTriggerEnabled)
        assertTrue("SIM trigger should be enabled", settings.simRemovalTriggerEnabled)
        assertTrue("Network isolation should be enabled", settings.networkIsolationTriggerEnabled)
        assertTrue("Should have triggers armed", settings.hasAnyTriggerEnabled())
    }

    @Test
    fun triggers_individualCanBeDisabled() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.setUsbTriggerEnabled(true)
        nukeSettingsRepository.setSimRemovalTriggerEnabled(true)

        // Disable just USB
        nukeSettingsRepository.setUsbTriggerEnabled(false)

        val settings = nukeSettingsRepository.settings.first()
        assertFalse("USB trigger should be disabled", settings.usbTriggerEnabled)
        assertTrue("SIM trigger should still be enabled", settings.simRemovalTriggerEnabled)
        assertTrue("Should still have triggers armed", settings.hasAnyTriggerEnabled())
    }

    // ==================== Trigger Source Tests ====================

    @Test
    fun nukeManager_supportsDifferentTriggerSources() = runTest {
        nukeSettingsRepository.setNukeEnabled(true)
        nukeSettingsRepository.updateWipeOptions(wipeCache = true)

        // Verify all trigger sources are defined
        val sources = listOf(
            NukeTriggerSource.USB_CONNECTION,
            NukeTriggerSource.ADB_DETECTED,
            NukeTriggerSource.FAILED_AUTH,
            NukeTriggerSource.DEAD_MAN_SWITCH,
            NukeTriggerSource.NETWORK_ISOLATION,
            NukeTriggerSource.SIM_REMOVAL,
            NukeTriggerSource.RAPID_REBOOT,
            NukeTriggerSource.GEOFENCE,
            NukeTriggerSource.DURESS_PIN,
            NukeTriggerSource.MANUAL
        )

        // All sources should be valid enum values
        assertEquals("Should have 10 trigger sources", 10, sources.size)
    }

    // ==================== Edge Cases ====================

    @Test
    fun triggers_rapidTogglingSafe() = runTest {
        repeat(20) {
            nukeSettingsRepository.setNukeEnabled(it % 2 == 0)
            nukeSettingsRepository.setUsbTriggerEnabled(it % 3 == 0)
            nukeSettingsRepository.setSimRemovalTriggerEnabled(it % 4 == 0)
        }

        // Should not crash
        val settings = nukeSettingsRepository.settings.first()
        assertNotNull("Settings should be readable", settings)
    }

    @Test
    fun triggers_allArmedConfiguration() = runTest {
        val settings = TestDataFactory.createFullyArmedNukeSettings()

        assertTrue("Nuke should be enabled", settings.nukeEnabled)
        assertTrue("USB trigger should be enabled", settings.usbTriggerEnabled)
        assertTrue("Failed auth should be enabled", settings.failedAuthTriggerEnabled)
        assertTrue("Dead man switch should be enabled", settings.deadManSwitchEnabled)
        assertTrue("Network isolation should be enabled", settings.networkIsolationTriggerEnabled)
        assertTrue("SIM removal should be enabled", settings.simRemovalTriggerEnabled)
        assertTrue("Rapid reboot should be enabled", settings.rapidRebootTriggerEnabled)
        assertTrue("Duress PIN should be enabled", settings.duressPinEnabled)
    }
}

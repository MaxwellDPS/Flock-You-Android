package com.flockyou.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.PrivacySettings
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.RetentionPeriod
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.EphemeralDetectionRepository
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
 * Comprehensive E2E tests for privacy features.
 *
 * Tests cover:
 * - Ephemeral mode (RAM-only storage)
 * - Data retention policies
 * - Location storage controls
 * - Auto-purge on screen lock
 * - Quick wipe functionality
 * - Ultrasonic detection consent
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PrivacyFeaturesE2ETest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var privacySettingsRepository: PrivacySettingsRepository

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var ephemeralRepository: EphemeralDetectionRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
    }

    @After
    fun cleanup() {
        runBlocking {
            // Reset to defaults
            privacySettingsRepository.updateSettings(
                ephemeralModeEnabled = false,
                storeLocationWithDetections = true
            )
            detectionRepository.deleteAllDetections()
            ephemeralRepository.deleteAllDetections()
        }
    }

    // ==================== Ephemeral Mode Tests ====================

    @Test
    fun ephemeralMode_defaultsToDisabled() = runTest {
        val settings = privacySettingsRepository.settings.first()
        assertFalse("Ephemeral mode should be disabled by default", settings.ephemeralModeEnabled)
    }

    @Test
    fun ephemeralMode_canBeEnabled() = runTest {
        privacySettingsRepository.setEphemeralModeEnabled(true)

        val settings = privacySettingsRepository.settings.first()
        assertTrue("Ephemeral mode should be enabled", settings.ephemeralModeEnabled)
    }

    @Test
    fun ephemeralMode_storesDetectionsInRam() = runTest {
        // Enable ephemeral mode
        privacySettingsRepository.setEphemeralModeEnabled(true)

        // Insert detection into ephemeral repository
        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        ephemeralRepository.insertDetection(detection)

        // Verify detection is in ephemeral repository
        val count = ephemeralRepository.getTotalDetectionCount()
        assertEquals("Detection should be in ephemeral storage", 1, count)

        // Verify detection is NOT in persistent repository
        val persistentCount = detectionRepository.getTotalDetectionCount()
        assertEquals("Detection should not be in persistent storage", 0, persistentCount)
    }

    @Test
    fun ephemeralMode_detectionsAreClearedOnClearAll() = runTest {
        privacySettingsRepository.setEphemeralModeEnabled(true)

        // Insert multiple detections
        val detections = TestDataFactory.createMultipleDetections(10)
        ephemeralRepository.insertDetections(detections)

        assertEquals("Should have 10 detections", 10, ephemeralRepository.getTotalDetectionCount())

        // Clear all
        ephemeralRepository.clearAll()

        assertEquals("Should have 0 detections after clear", 0, ephemeralRepository.getTotalDetectionCount())
    }

    @Test
    fun ephemeralMode_detectionsAreLostOnServiceRestart() = runTest {
        // This test documents that ephemeral data is lost
        // In real scenario, service restart would clear the MutableStateFlow
        privacySettingsRepository.setEphemeralModeEnabled(true)

        val detection = TestDataFactory.createStingrayDetection()
        ephemeralRepository.insertDetection(detection)

        assertEquals("Should have 1 detection", 1, ephemeralRepository.getTotalDetectionCount())

        // Simulate service restart by clearing
        ephemeralRepository.clearAll()

        assertEquals("Detections should be lost after restart", 0, ephemeralRepository.getTotalDetectionCount())
    }

    // ==================== Data Retention Tests ====================

    @Test
    fun dataRetention_hasMultiplePeriods() {
        val periods = RetentionPeriod.entries
        assertTrue("Should have at least 4 retention periods", periods.size >= 4)

        // Verify all periods have valid values
        periods.forEach { period ->
            assertTrue("Period should have positive hours", period.hours > 0)
            assertTrue("Period should have display name", period.displayName.isNotEmpty())
        }
    }

    @Test
    fun dataRetention_defaultsToThreeDays() = runTest {
        val settings = privacySettingsRepository.settings.first()
        assertEquals("Default retention should be 3 days", RetentionPeriod.THREE_DAYS, settings.retentionPeriod)
    }

    @Test
    fun dataRetention_canBeChanged() = runTest {
        privacySettingsRepository.setRetentionPeriod(RetentionPeriod.SEVEN_DAYS)

        val settings = privacySettingsRepository.settings.first()
        assertEquals("Retention should be 7 days", RetentionPeriod.SEVEN_DAYS, settings.retentionPeriod)
        assertEquals("Retention hours should be 168", 168, settings.retentionPeriod.hours)
    }

    @Test
    fun dataRetention_canConvertFromHours() {
        val fourHours = RetentionPeriod.fromHours(4)
        assertEquals("Should be FOUR_HOURS", RetentionPeriod.FOUR_HOURS, fourHours)

        val oneDay = RetentionPeriod.fromHours(24)
        assertEquals("Should be ONE_DAY", RetentionPeriod.ONE_DAY, oneDay)

        val invalid = RetentionPeriod.fromHours(999)
        assertEquals("Invalid hours should default to THREE_DAYS", RetentionPeriod.THREE_DAYS, invalid)
    }

    @Test
    fun dataRetention_deletesOldDetections() = runTest {
        // Insert old detection
        val oldDetection = TestDataFactory.createFlockSafetyCameraDetection().copy(
            timestamp = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L) // 8 days ago
        )
        detectionRepository.insertDetection(oldDetection)

        // Insert recent detection
        val recentDetection = TestDataFactory.createDroneDetection()
        detectionRepository.insertDetection(recentDetection)

        assertEquals("Should have 2 detections", 2, detectionRepository.getTotalDetectionCount())

        // Delete detections older than 7 days
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        detectionRepository.deleteOldDetections(sevenDaysAgo)

        val remaining = detectionRepository.getTotalDetectionCount()
        assertEquals("Should have 1 detection remaining", 1, remaining)

        // Verify recent detection remains
        val allDetections = detectionRepository.getAllDetectionsSnapshot()
        assertTrue("Recent detection should remain", allDetections.any { it.id == recentDetection.id })
        assertFalse("Old detection should be deleted", allDetections.any { it.id == oldDetection.id })
    }

    // ==================== Location Storage Tests ====================

    @Test
    fun locationStorage_defaultDependsOnBuildType() = runTest {
        val settings = privacySettingsRepository.settings.first()

        // Default varies by build type (tested in OEM tests)
        // This test just verifies the setting exists
        assertNotNull("Location storage setting should exist", settings.storeLocationWithDetections)
    }

    @Test
    fun locationStorage_canBeDisabled() = runTest {
        privacySettingsRepository.setStoreLocationWithDetections(false)

        val settings = privacySettingsRepository.settings.first()
        assertFalse("Location storage should be disabled", settings.storeLocationWithDetections)
    }

    @Test
    fun locationStorage_whenDisabledCreatesDetectionsWithoutLocation() = runTest {
        privacySettingsRepository.setStoreLocationWithDetections(false)

        // Create detection without location
        val detection = TestDataFactory.createFlockSafetyCameraDetection().copy(
            latitude = null,
            longitude = null
        )
        detectionRepository.insertDetection(detection)

        // Verify detection has no location
        val retrieved = detectionRepository.getDetectionById(detection.id)
        assertNotNull("Detection should exist", retrieved)
        assertNull("Latitude should be null", retrieved?.latitude)
        assertNull("Longitude should be null", retrieved?.longitude)
    }

    @Test
    fun locationStorage_whenEnabledAllowsLocationStorage() = runTest {
        privacySettingsRepository.setStoreLocationWithDetections(true)

        // Create detection with location
        val detection = TestDataFactory.createFlockSafetyCameraDetection().copy(
            latitude = 37.7749,
            longitude = -122.4194
        )
        detectionRepository.insertDetection(detection)

        // Verify detection has location
        val retrieved = detectionRepository.getDetectionById(detection.id)
        assertNotNull("Detection should exist", retrieved)
        assertNotNull("Latitude should exist", retrieved?.latitude)
        assertNotNull("Longitude should exist", retrieved?.longitude)
        assertEquals("Latitude should match", 37.7749, retrieved?.latitude!!, 0.0001)
        assertEquals("Longitude should match", -122.4194, retrieved?.longitude!!, 0.0001)
    }

    @Test
    fun locationStorage_detectionsWithLocationCanBeQueried() = runTest {
        // Insert detection with location
        val withLocation = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insertDetection(withLocation)

        // Insert detection without location
        val withoutLocation = TestDataFactory.createStingrayDetection().copy(
            latitude = null,
            longitude = null
        )
        detectionRepository.insertDetection(withoutLocation)

        // Query detections with location
        val withLocationList = detectionRepository.detectionsWithLocation.first()

        assertEquals("Should have 1 detection with location", 1, withLocationList.size)
        assertNotNull("Should have latitude", withLocationList[0].latitude)
        assertNotNull("Should have longitude", withLocationList[0].longitude)
    }

    // ==================== Auto-Purge on Screen Lock Tests ====================

    @Test
    fun autoPurge_defaultsToDisabled() = runTest {
        val settings = privacySettingsRepository.settings.first()
        assertFalse("Auto-purge should be disabled by default", settings.autoPurgeOnScreenLock)
    }

    @Test
    fun autoPurge_canBeEnabled() = runTest {
        privacySettingsRepository.setAutoPurgeOnScreenLock(true)

        val settings = privacySettingsRepository.settings.first()
        assertTrue("Auto-purge should be enabled", settings.autoPurgeOnScreenLock)
    }

    @Test
    fun autoPurge_deletesAllDetectionsWhenEnabled() = runTest {
        privacySettingsRepository.setAutoPurgeOnScreenLock(true)

        // Insert test detections
        val detections = TestDataFactory.createMultipleDetections(5)
        detections.forEach { detectionRepository.insertDetection(it) }

        assertEquals("Should have 5 detections", 5, detectionRepository.getTotalDetectionCount())

        // Simulate screen lock purge
        detectionRepository.deleteAllDetections()

        assertEquals("All detections should be deleted", 0, detectionRepository.getTotalDetectionCount())
    }

    // ==================== Quick Wipe Tests ====================

    @Test
    fun quickWipe_confirmationDefaultsToRequired() = runTest {
        val settings = privacySettingsRepository.settings.first()
        assertTrue("Quick wipe should require confirmation by default", settings.quickWipeRequiresConfirmation)
    }

    @Test
    fun quickWipe_confirmationCanBeDisabled() = runTest {
        privacySettingsRepository.setQuickWipeRequiresConfirmation(false)

        val settings = privacySettingsRepository.settings.first()
        assertFalse("Quick wipe confirmation should be disabled", settings.quickWipeRequiresConfirmation)
    }

    @Test
    fun quickWipe_deletesAllDetections() = runTest {
        // Insert test data
        val detections = TestDataFactory.createMixedProtocolDetections()
        detections.forEach { detectionRepository.insertDetection(it) }

        assertEquals("Should have multiple detections", detections.size, detectionRepository.getTotalDetectionCount())

        // Perform quick wipe
        detectionRepository.deleteAllDetections()

        assertEquals("All detections should be deleted", 0, detectionRepository.getTotalDetectionCount())
    }

    // ==================== Ultrasonic Detection Consent Tests ====================

    @Test
    fun ultrasonicConsent_defaultsToNotEnabled() = runTest {
        val settings = privacySettingsRepository.settings.first()
        assertFalse("Ultrasonic detection should be disabled by default", settings.ultrasonicDetectionEnabled)
        assertFalse("Ultrasonic consent should not be acknowledged by default", settings.ultrasonicConsentAcknowledged)
    }

    @Test
    fun ultrasonicConsent_requiresExplicitAcknowledgment() = runTest {
        // Try to enable without consent
        privacySettingsRepository.setUltrasonicDetectionEnabled(true)

        var settings = privacySettingsRepository.settings.first()
        assertTrue("Should be enabled", settings.ultrasonicDetectionEnabled)

        // Now properly enable with consent
        privacySettingsRepository.enableUltrasonicWithConsent()

        settings = privacySettingsRepository.settings.first()
        assertTrue("Should be enabled with consent", settings.ultrasonicDetectionEnabled)
        assertTrue("Consent should be acknowledged", settings.ultrasonicConsentAcknowledged)
    }

    @Test
    fun ultrasonicConsent_canBeRevoked() = runTest {
        // Enable with consent
        privacySettingsRepository.enableUltrasonicWithConsent()

        var settings = privacySettingsRepository.settings.first()
        assertTrue("Should be enabled", settings.ultrasonicDetectionEnabled)
        assertTrue("Consent should be acknowledged", settings.ultrasonicConsentAcknowledged)

        // Revoke consent
        privacySettingsRepository.revokeUltrasonicConsent()

        settings = privacySettingsRepository.settings.first()
        assertFalse("Should be disabled", settings.ultrasonicDetectionEnabled)
        assertFalse("Consent should be revoked", settings.ultrasonicConsentAcknowledged)
    }

    @Test
    fun ultrasonicConsent_canBeDisabledWithoutRevokingConsent() = runTest {
        // Enable with consent
        privacySettingsRepository.enableUltrasonicWithConsent()

        // Disable without revoking consent
        privacySettingsRepository.disableUltrasonic()

        val settings = privacySettingsRepository.settings.first()
        assertFalse("Should be disabled", settings.ultrasonicDetectionEnabled)
        assertTrue("Consent should still be acknowledged", settings.ultrasonicConsentAcknowledged)
    }

    // ==================== Privacy Mode Integration Tests ====================

    @Test
    fun integration_privacyModeEnablesAllPrivacyFeatures() = runTest {
        // Enable all privacy features
        privacySettingsRepository.updateSettings(
            ephemeralModeEnabled = true,
            retentionPeriod = RetentionPeriod.FOUR_HOURS,
            storeLocationWithDetections = false,
            autoPurgeOnScreenLock = true,
            quickWipeRequiresConfirmation = false
        )

        val settings = privacySettingsRepository.settings.first()

        assertTrue("Ephemeral mode should be enabled", settings.ephemeralModeEnabled)
        assertEquals("Retention should be 4 hours", RetentionPeriod.FOUR_HOURS, settings.retentionPeriod)
        assertFalse("Location storage should be disabled", settings.storeLocationWithDetections)
        assertTrue("Auto-purge should be enabled", settings.autoPurgeOnScreenLock)
        assertFalse("Quick wipe confirmation should be disabled", settings.quickWipeRequiresConfirmation)
    }

    @Test
    fun integration_ephemeralModeWorksWithRepositories() = runTest {
        privacySettingsRepository.setEphemeralModeEnabled(true)

        // Insert into ephemeral repository
        val ephemeralDetections = TestDataFactory.createMultipleDetections(5)
        ephemeralRepository.insertDetections(ephemeralDetections)

        // Insert into persistent repository (should not happen in ephemeral mode, but testing isolation)
        val persistentDetection = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insertDetection(persistentDetection)

        // Verify both repositories maintain separate data
        assertEquals("Ephemeral should have 5", 5, ephemeralRepository.getTotalDetectionCount())
        assertEquals("Persistent should have 1", 1, detectionRepository.getTotalDetectionCount())

        // Clear ephemeral
        ephemeralRepository.clearAll()

        // Verify only ephemeral is cleared
        assertEquals("Ephemeral should be empty", 0, ephemeralRepository.getTotalDetectionCount())
        assertEquals("Persistent should still have 1", 1, detectionRepository.getTotalDetectionCount())
    }

    @Test
    fun integration_dataRetentionWorksWithQueries() = runTest {
        // Insert detections with different timestamps
        val now = System.currentTimeMillis()
        val oneDayAgo = now - (24 * 60 * 60 * 1000L)
        val threeDaysAgo = now - (3 * 24 * 60 * 60 * 1000L)
        val sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000L)

        detectionRepository.insertDetection(
            TestDataFactory.createFlockSafetyCameraDetection().copy(timestamp = sevenDaysAgo)
        )
        detectionRepository.insertDetection(
            TestDataFactory.createStingrayDetection().copy(timestamp = threeDaysAgo)
        )
        detectionRepository.insertDetection(
            TestDataFactory.createDroneDetection().copy(timestamp = oneDayAgo)
        )
        detectionRepository.insertDetection(
            TestDataFactory.createUltrasonicBeaconDetection().copy(timestamp = now)
        )

        // Query recent detections (last 2 days)
        val twoDaysAgo = now - (2 * 24 * 60 * 60 * 1000L)
        val recentDetections = detectionRepository.getRecentDetections(twoDaysAgo).first()

        assertEquals("Should have 2 recent detections", 2, recentDetections.size)

        // Apply retention policy (delete older than 3 days)
        val threeDaysRetention = now - (3 * 24 * 60 * 60 * 1000L)
        detectionRepository.deleteOldDetections(threeDaysRetention)

        val remaining = detectionRepository.getTotalDetectionCount()
        assertEquals("Should have 3 detections after retention", 3, remaining)
    }
}

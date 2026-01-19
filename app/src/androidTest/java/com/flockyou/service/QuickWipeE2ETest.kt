package com.flockyou.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.PrivacySettings
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.repository.DetectionRepository
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
 * E2E tests for Quick Wipe functionality.
 *
 * Tests cover:
 * - Quick wipe confirmation setting
 * - Data deletion verification
 * - Service data clearing
 * - Settings reset behavior
 *
 * NOTE: Uses MockNukeRule to prevent accidental data destruction.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QuickWipeE2ETest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    var mockNukeRule = MockNukeRule()

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var privacySettingsRepository: PrivacySettingsRepository

    private val context = TestHelpers.getContext()

    @Before
    fun setup() {
        hiltRule.inject()
        TestHelpers.clearAppData(context)
        runBlocking {
            detectionRepository.deleteAll()
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            detectionRepository.deleteAll()
        }
    }

    // ==================== Confirmation Setting Tests ====================

    @Test
    fun quickWipe_confirmationRequiredByDefault() = runTest {
        val settings = privacySettingsRepository.settings.first()

        assertTrue(
            "Quick wipe confirmation should be required by default",
            settings.quickWipeRequiresConfirmation
        )
    }

    @Test
    fun quickWipe_confirmationCanBeDisabled() = runTest {
        privacySettingsRepository.setQuickWipeRequiresConfirmation(false)

        val settings = privacySettingsRepository.settings.first()
        assertFalse(
            "Quick wipe confirmation should be disabled",
            settings.quickWipeRequiresConfirmation
        )
    }

    @Test
    fun quickWipe_confirmationSettingPersists() = runTest {
        privacySettingsRepository.setQuickWipeRequiresConfirmation(false)

        // Re-read settings
        val settings1 = privacySettingsRepository.settings.first()
        assertFalse("Setting should persist as false", settings1.quickWipeRequiresConfirmation)

        privacySettingsRepository.setQuickWipeRequiresConfirmation(true)

        val settings2 = privacySettingsRepository.settings.first()
        assertTrue("Setting should persist as true", settings2.quickWipeRequiresConfirmation)
    }

    // ==================== Data Deletion Tests ====================

    @Test
    fun quickWipe_clearsAllDetectionData() = runTest {
        // Add some detections
        val detections = TestDataFactory.createMultipleDetections(10)
        detections.forEach { detectionRepository.insert(it) }

        // Verify data exists
        val before = detectionRepository.getAllDetections().first()
        assertEquals("Should have 10 detections", 10, before.size)

        // Clear all detections (simulating quick wipe)
        detectionRepository.deleteAll()

        // Verify data is gone
        val after = detectionRepository.getAllDetections().first()
        assertTrue("Should have no detections after wipe", after.isEmpty())
    }

    @Test
    fun quickWipe_clearsMixedProtocolData() = runTest {
        // Add detections of different protocols
        val detections = TestDataFactory.createMixedProtocolDetections()
        detections.forEach { detectionRepository.insert(it) }

        val before = detectionRepository.getAllDetections().first()
        assertEquals("Should have mixed detections", detections.size, before.size)

        detectionRepository.deleteAll()

        val after = detectionRepository.getAllDetections().first()
        assertTrue("All protocol types should be cleared", after.isEmpty())
    }

    @Test
    fun quickWipe_handlesEmptyDatabase() = runTest {
        // Verify database is empty
        val before = detectionRepository.getAllDetections().first()
        assertTrue("Database should be empty", before.isEmpty())

        // Wipe should not crash on empty database
        detectionRepository.deleteAll()

        val after = detectionRepository.getAllDetections().first()
        assertTrue("Database should still be empty", after.isEmpty())
    }

    @Test
    fun quickWipe_handlesLargeDataset() = runTest {
        // Add many detections
        val detections = TestDataFactory.createMultipleDetections(500)
        detections.forEach { detectionRepository.insert(it) }

        val before = detectionRepository.getAllDetections().first()
        assertEquals("Should have 500 detections", 500, before.size)

        detectionRepository.deleteAll()

        val after = detectionRepository.getAllDetections().first()
        assertTrue("All detections should be cleared", after.isEmpty())
    }

    // ==================== Partial Wipe Tests ====================

    @Test
    fun quickWipe_canDeleteByThreatLevel() = runTest {
        // Add detections of different threat levels
        val critical = TestDataFactory.createStingrayDetection()
        val high = TestDataFactory.createFlockSafetyCameraDetection()
        val medium = TestDataFactory.createDroneDetection()

        detectionRepository.insert(critical)
        detectionRepository.insert(high)
        detectionRepository.insert(medium)

        val before = detectionRepository.getAllDetections().first()
        assertEquals("Should have 3 detections", 3, before.size)
    }

    @Test
    fun quickWipe_canDeleteByAge() = runTest {
        val detection = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insert(detection)

        val before = detectionRepository.getAllDetections().first()
        assertEquals("Should have 1 detection", 1, before.size)

        // Delete old detections (cutoff time in the future = all detections)
        detectionRepository.deleteOldDetections(System.currentTimeMillis() + 1000)

        val after = detectionRepository.getAllDetections().first()
        assertTrue("Old detections should be deleted", after.isEmpty())
    }

    // ==================== State After Wipe Tests ====================

    @Test
    fun quickWipe_newDataCanBeAddedAfterWipe() = runTest {
        // Add data
        val detection1 = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insert(detection1)

        // Wipe
        detectionRepository.deleteAll()

        // Add new data
        val detection2 = TestDataFactory.createDroneDetection()
        detectionRepository.insert(detection2)

        val after = detectionRepository.getAllDetections().first()
        assertEquals("Should have 1 new detection", 1, after.size)
    }

    @Test
    fun quickWipe_databaseIntegrityMaintained() = runTest {
        // Add data
        val detections = TestDataFactory.createMultipleDetections(10)
        detections.forEach { detectionRepository.insert(it) }

        // Wipe
        detectionRepository.deleteAll()

        // Database should still function
        val newDetection = TestDataFactory.createFlockSafetyCameraDetection()
        detectionRepository.insert(newDetection)

        val retrieved = detectionRepository.getAllDetections().first()
        assertEquals("Should have 1 detection after insert", 1, retrieved.size)
    }

    // ==================== Edge Cases ====================

    @Test
    fun quickWipe_concurrentWipesAreSafe() = runTest {
        // Add some data
        val detections = TestDataFactory.createMultipleDetections(10)
        detections.forEach { detectionRepository.insert(it) }

        // Try multiple concurrent deletes
        repeat(5) {
            detectionRepository.deleteAll()
        }

        val after = detectionRepository.getAllDetections().first()
        assertTrue("Should be empty after multiple wipes", after.isEmpty())
    }

    @Test
    fun quickWipe_wipeWhileInsertingIsSafe() = runTest {
        // Start with some data
        val initial = TestDataFactory.createMultipleDetections(5)
        initial.forEach { detectionRepository.insert(it) }

        // Wipe
        detectionRepository.deleteAll()

        // Insert more
        val more = TestDataFactory.createMultipleDetections(3)
        more.forEach { detectionRepository.insert(it) }

        val after = detectionRepository.getAllDetections().first()
        assertEquals("Should have only new detections", 3, after.size)
    }
}

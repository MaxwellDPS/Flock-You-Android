package com.flockyou.worker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.flockyou.data.PrivacySettingsRepository
import com.flockyou.data.RetentionPeriod
import com.flockyou.data.model.Detection
import com.flockyou.data.repository.DetectionRepository
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
 * E2E tests for DataRetentionWorker.
 *
 * Tests cover:
 * - Old detection deletion based on retention period
 * - Retention period setting respect
 * - Edge cases with empty database
 * - Mixed old and new data handling
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DataRetentionWorkerE2ETest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

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

    // ==================== Retention Period Tests ====================

    @Test
    fun dataRetention_deletesOldDetections() = runTest {
        // Create a detection with old timestamp
        val oldTimestamp = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago
        val oldDetection = TestDataFactory.createTestDetection().copy(
            id = 1,
            firstSeen = oldTimestamp,
            lastSeen = oldTimestamp
        )
        detectionRepository.insert(oldDetection)

        // Verify it exists
        val before = detectionRepository.getAllDetections().first()
        assertEquals("Should have 1 detection", 1, before.size)

        // Delete detections older than 1 hour (simulating worker behavior)
        val cutoffTime = System.currentTimeMillis() - (1 * 60 * 60 * 1000)
        detectionRepository.deleteOldDetections(cutoffTime)

        // Verify it's deleted
        val after = detectionRepository.getAllDetections().first()
        assertTrue("Old detection should be deleted", after.isEmpty())
    }

    @Test
    fun dataRetention_preservesRecentDetections() = runTest {
        // Create a recent detection
        val recentTimestamp = System.currentTimeMillis()
        val recentDetection = TestDataFactory.createTestDetection().copy(
            id = 1,
            firstSeen = recentTimestamp,
            lastSeen = recentTimestamp
        )
        detectionRepository.insert(recentDetection)

        // Delete old detections (older than 24 hours)
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        detectionRepository.deleteOldDetections(cutoffTime)

        // Recent detection should still exist
        val after = detectionRepository.getAllDetections().first()
        assertEquals("Recent detection should be preserved", 1, after.size)
    }

    @Test
    fun dataRetention_respectsFourHourPeriod() = runTest {
        val now = System.currentTimeMillis()
        val threeHoursAgo = now - (3 * 60 * 60 * 1000)
        val fiveHoursAgo = now - (5 * 60 * 60 * 1000)

        val recentDetection = TestDataFactory.createTestDetection().copy(
            id = 1,
            firstSeen = threeHoursAgo,
            lastSeen = threeHoursAgo
        )
        val oldDetection = TestDataFactory.createTestDetection().copy(
            id = 2,
            firstSeen = fiveHoursAgo,
            lastSeen = fiveHoursAgo,
            macAddress = "11:22:33:44:55:66"
        )

        detectionRepository.insert(recentDetection)
        detectionRepository.insert(oldDetection)

        // 4-hour retention period cutoff
        val cutoffTime = now - (4 * 60 * 60 * 1000)
        detectionRepository.deleteOldDetections(cutoffTime)

        val after = detectionRepository.getAllDetections().first()
        assertEquals("Only recent detection should remain", 1, after.size)
    }

    @Test
    fun dataRetention_respectsSevenDayPeriod() = runTest {
        val now = System.currentTimeMillis()
        val sixDaysAgo = now - (6 * 24 * 60 * 60 * 1000L)
        val eightDaysAgo = now - (8 * 24 * 60 * 60 * 1000L)

        val recentDetection = TestDataFactory.createTestDetection().copy(
            id = 1,
            firstSeen = sixDaysAgo,
            lastSeen = sixDaysAgo
        )
        val oldDetection = TestDataFactory.createTestDetection().copy(
            id = 2,
            firstSeen = eightDaysAgo,
            lastSeen = eightDaysAgo,
            macAddress = "11:22:33:44:55:66"
        )

        detectionRepository.insert(recentDetection)
        detectionRepository.insert(oldDetection)

        // 7-day retention period cutoff
        val cutoffTime = now - (7 * 24 * 60 * 60 * 1000L)
        detectionRepository.deleteOldDetections(cutoffTime)

        val after = detectionRepository.getAllDetections().first()
        assertEquals("Only detection within 7 days should remain", 1, after.size)
    }

    // ==================== Settings Integration Tests ====================

    @Test
    fun dataRetention_settingsCanBeChanged() = runTest {
        privacySettingsRepository.setRetentionPeriod(RetentionPeriod.FOUR_HOURS)
        var settings = privacySettingsRepository.settings.first()
        assertEquals(RetentionPeriod.FOUR_HOURS, settings.retentionPeriod)

        privacySettingsRepository.setRetentionPeriod(RetentionPeriod.SEVEN_DAYS)
        settings = privacySettingsRepository.settings.first()
        assertEquals(RetentionPeriod.SEVEN_DAYS, settings.retentionPeriod)

        privacySettingsRepository.setRetentionPeriod(RetentionPeriod.THIRTY_DAYS)
        settings = privacySettingsRepository.settings.first()
        assertEquals(RetentionPeriod.THIRTY_DAYS, settings.retentionPeriod)
    }

    // ==================== Edge Cases ====================

    @Test
    fun dataRetention_handlesEmptyDatabase() = runTest {
        // Empty database
        val before = detectionRepository.getAllDetections().first()
        assertTrue("Database should be empty", before.isEmpty())

        // Should not crash
        detectionRepository.deleteOldDetections(System.currentTimeMillis())

        val after = detectionRepository.getAllDetections().first()
        assertTrue("Database should still be empty", after.isEmpty())
    }

    @Test
    fun dataRetention_handlesAllOldData() = runTest {
        val veryOld = System.currentTimeMillis() - (365 * 24 * 60 * 60 * 1000L) // 1 year ago

        repeat(10) { i ->
            val detection = TestDataFactory.createTestDetection().copy(
                id = (i + 1).toLong(),
                firstSeen = veryOld,
                lastSeen = veryOld,
                macAddress = String.format("AA:BB:CC:DD:EE:%02X", i)
            )
            detectionRepository.insert(detection)
        }

        val before = detectionRepository.getAllDetections().first()
        assertEquals("Should have 10 detections", 10, before.size)

        // Delete all (cutoff in future)
        detectionRepository.deleteOldDetections(System.currentTimeMillis() + 1000)

        val after = detectionRepository.getAllDetections().first()
        assertTrue("All old detections should be deleted", after.isEmpty())
    }

    @Test
    fun dataRetention_handlesAllRecentData() = runTest {
        val now = System.currentTimeMillis()

        repeat(10) { i ->
            val detection = TestDataFactory.createTestDetection().copy(
                id = (i + 1).toLong(),
                firstSeen = now,
                lastSeen = now,
                macAddress = String.format("AA:BB:CC:DD:EE:%02X", i)
            )
            detectionRepository.insert(detection)
        }

        // Delete old (1 hour ago cutoff - all are recent)
        val cutoffTime = now - (60 * 60 * 1000)
        detectionRepository.deleteOldDetections(cutoffTime)

        val after = detectionRepository.getAllDetections().first()
        assertEquals("All recent detections should remain", 10, after.size)
    }

    @Test
    fun dataRetention_mixedOldAndNewData() = runTest {
        val now = System.currentTimeMillis()
        val oneDayAgo = now - (24 * 60 * 60 * 1000)
        val threeDaysAgo = now - (3 * 24 * 60 * 60 * 1000L)
        val fiveDaysAgo = now - (5 * 24 * 60 * 60 * 1000L)

        // Add detections at different times
        val detections = listOf(
            TestDataFactory.createTestDetection().copy(id = 1, firstSeen = now, lastSeen = now, macAddress = "AA:AA:AA:AA:AA:01"),
            TestDataFactory.createTestDetection().copy(id = 2, firstSeen = oneDayAgo, lastSeen = oneDayAgo, macAddress = "AA:AA:AA:AA:AA:02"),
            TestDataFactory.createTestDetection().copy(id = 3, firstSeen = threeDaysAgo, lastSeen = threeDaysAgo, macAddress = "AA:AA:AA:AA:AA:03"),
            TestDataFactory.createTestDetection().copy(id = 4, firstSeen = fiveDaysAgo, lastSeen = fiveDaysAgo, macAddress = "AA:AA:AA:AA:AA:04")
        )
        detections.forEach { detectionRepository.insert(it) }

        // Apply 3-day retention
        val cutoffTime = now - (3 * 24 * 60 * 60 * 1000L)
        detectionRepository.deleteOldDetections(cutoffTime)

        val after = detectionRepository.getAllDetections().first()
        assertEquals("Only detections within 3 days should remain", 2, after.size)
    }

    @Test
    fun dataRetention_largeDatasetPerformance() = runTest {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - (60 * 60 * 1000)

        // Add 100 detections
        repeat(100) { i ->
            val timestamp = if (i < 50) now else oneHourAgo - (60 * 60 * 1000) // Half recent, half old
            val detection = TestDataFactory.createTestDetection().copy(
                id = (i + 1).toLong(),
                firstSeen = timestamp,
                lastSeen = timestamp,
                macAddress = String.format("AA:BB:CC:DD:%02X:%02X", i / 256, i % 256)
            )
            detectionRepository.insert(detection)
        }

        val before = detectionRepository.getAllDetections().first()
        assertEquals("Should have 100 detections", 100, before.size)

        // Apply 1-hour retention
        detectionRepository.deleteOldDetections(oneHourAgo)

        val after = detectionRepository.getAllDetections().first()
        assertEquals("50 recent detections should remain", 50, after.size)
    }
}

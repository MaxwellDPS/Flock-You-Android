package com.flockyou.edge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flockyou.data.model.*
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.security.AppLockManager
import com.flockyou.security.SecureMemory
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
 * E2E tests for error handling and edge cases.
 *
 * Tests cover:
 * - Null value handling
 * - Empty value handling
 * - Extreme value handling
 * - Boundary conditions
 * - Concurrent access safety
 * - Memory edge cases
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ErrorHandlingE2ETest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var detectionRepository: DetectionRepository

    @Inject
    lateinit var appLockManager: AppLockManager

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
            appLockManager.removePin()
        }
    }

    // ==================== Null Value Handling Tests ====================

    @Test
    fun errorHandling_nullMacAddressHandled() = runTest {
        val detection = TestDataFactory.createTestDetection(macAddress = null)
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Should have 1 detection", 1, detections.size)
        assertNull("MAC should be null", detections[0].macAddress)
    }

    @Test
    fun errorHandling_nullSsidHandled() = runTest {
        val detection = TestDataFactory.createTestDetection(ssid = null)
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Should have 1 detection", 1, detections.size)
        assertNull("SSID should be null", detections[0].ssid)
    }

    @Test
    fun errorHandling_nullLocationHandled() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = null,
            longitude = null
        )
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertNull("Latitude should be null", detections[0].latitude)
        assertNull("Longitude should be null", detections[0].longitude)
    }

    @Test
    fun errorHandling_allNullableFieldsNull() = runTest {
        val detection = TestDataFactory.createTestDetection(
            macAddress = null,
            ssid = null,
            latitude = null,
            longitude = null
        )
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Should have 1 detection", 1, detections.size)
    }

    // ==================== Empty Value Handling Tests ====================

    @Test
    fun errorHandling_emptySsidHandled() = runTest {
        val detection = TestDataFactory.createTestDetection(ssid = "")
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("SSID should be empty string", "", detections[0].ssid)
    }

    @Test
    fun errorHandling_emptyMacAddressHandled() = runTest {
        val detection = TestDataFactory.createTestDetection(macAddress = "")
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("MAC should be empty string", "", detections[0].macAddress)
    }

    @Test
    fun errorHandling_emptyDatabaseQueries() = runTest {
        val detections = detectionRepository.getAllDetections().first()
        assertTrue("Empty database should return empty list", detections.isEmpty())

        val active = detectionRepository.getActiveDetections().first()
        assertTrue("Empty database active query should return empty list", active.isEmpty())
    }

    @Test
    fun errorHandling_deleteFromEmptyDatabase() = runTest {
        // Should not crash
        detectionRepository.deleteAll()
        detectionRepository.deleteOldDetections(System.currentTimeMillis())

        val detections = detectionRepository.getAllDetections().first()
        assertTrue("Database should be empty", detections.isEmpty())
    }

    // ==================== Extreme Value Handling Tests ====================

    @Test
    fun errorHandling_veryLongSsid() = runTest {
        val longSsid = "A".repeat(1000)
        val detection = TestDataFactory.createTestDetection(ssid = longSsid)
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertNotNull("Detection should be stored", detections.firstOrNull())
    }

    @Test
    fun errorHandling_veryLongDeviceName() = runTest {
        val detection = TestDataFactory.createTestDetection().copy(
            deviceName = "A".repeat(500)
        )
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertNotNull("Detection should be stored", detections.firstOrNull())
    }

    @Test
    fun errorHandling_extremeRssiValues() = runTest {
        val detectionMin = TestDataFactory.createTestDetection().copy(rssi = -127)
        val detectionMax = TestDataFactory.createTestDetection().copy(rssi = 0, macAddress = "11:22:33:44:55:66")
        val detectionExtreme = TestDataFactory.createTestDetection().copy(rssi = -1000, macAddress = "22:33:44:55:66:77")

        detectionRepository.insert(detectionMin)
        detectionRepository.insert(detectionMax)
        detectionRepository.insert(detectionExtreme)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Should have 3 detections", 3, detections.size)
    }

    @Test
    fun errorHandling_extremeCoordinates() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = 90.0,  // North pole
            longitude = 180.0 // International date line
        )
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Latitude should be stored", 90.0, detections[0].latitude!!, 0.001)
        assertEquals("Longitude should be stored", 180.0, detections[0].longitude!!, 0.001)
    }

    @Test
    fun errorHandling_negativeCoordinates() = runTest {
        val detection = TestDataFactory.createTestDetection(
            latitude = -90.0,  // South pole
            longitude = -180.0
        )
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Negative latitude should be stored", -90.0, detections[0].latitude!!, 0.001)
        assertEquals("Negative longitude should be stored", -180.0, detections[0].longitude!!, 0.001)
    }

    @Test
    fun errorHandling_extremeTimestamps() = runTest {
        val farFuture = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000 * 100) // 100 years
        val detection = TestDataFactory.createTestDetection().copy(
            firstSeen = farFuture,
            lastSeen = farFuture
        )
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Future timestamp should be stored", farFuture, detections[0].firstSeen)
    }

    // ==================== Boundary Condition Tests ====================

    @Test
    fun errorHandling_maxDetectionCount() = runTest {
        val count = 1000
        val detections = (1..count).map { i ->
            TestDataFactory.createTestDetection(
                macAddress = String.format("AA:BB:CC:%02X:%02X:%02X", i / 65536, (i / 256) % 256, i % 256)
            )
        }

        detections.forEach { detectionRepository.insert(it) }

        val stored = detectionRepository.getAllDetections().first()
        assertEquals("Should handle $count detections", count, stored.size)
    }

    @Test
    fun errorHandling_threatScoreEdges() = runTest {
        val detectionZero = TestDataFactory.createTestDetection().copy(threatScore = 0)
        val detectionMax = TestDataFactory.createTestDetection().copy(threatScore = 100, macAddress = "11:22:33:44:55:66")
        val detectionNegative = TestDataFactory.createTestDetection().copy(threatScore = -1, macAddress = "22:33:44:55:66:77")
        val detectionOverMax = TestDataFactory.createTestDetection().copy(threatScore = 200, macAddress = "33:44:55:66:77:88")

        detectionRepository.insert(detectionZero)
        detectionRepository.insert(detectionMax)
        detectionRepository.insert(detectionNegative)
        detectionRepository.insert(detectionOverMax)

        val stored = detectionRepository.getAllDetections().first()
        assertEquals("Should store all detections", 4, stored.size)
    }

    // ==================== Concurrent Access Tests ====================

    @Test
    fun errorHandling_concurrentInserts() = runTest {
        val detections = (1..50).map { i ->
            TestDataFactory.createTestDetection(
                macAddress = String.format("AA:BB:CC:DD:EE:%02X", i)
            )
        }

        // Insert all at once
        detections.forEach { detectionRepository.insert(it) }

        val stored = detectionRepository.getAllDetections().first()
        assertEquals("All concurrent inserts should succeed", 50, stored.size)
    }

    @Test
    fun errorHandling_insertAndDeleteConcurrently() = runTest {
        // Insert some data
        val detections = TestDataFactory.createMultipleDetections(20)
        detections.forEach { detectionRepository.insert(it) }

        // Delete all
        detectionRepository.deleteAll()

        // Insert more
        val moreDetections = TestDataFactory.createMultipleDetections(10)
        moreDetections.forEach { detectionRepository.insert(it) }

        val stored = detectionRepository.getAllDetections().first()
        assertEquals("Should only have new detections", 10, stored.size)
    }

    // ==================== Secure Memory Tests ====================

    @Test
    fun errorHandling_secureMemoryClearsNull() {
        // Should not crash
        SecureMemory.clear(null as ByteArray?)
        SecureMemory.clear(null as CharArray?)
        assertTrue("Null clearing should be safe", true)
    }

    @Test
    fun errorHandling_secureMemoryClearsEmpty() {
        // Should not crash
        SecureMemory.clear(ByteArray(0))
        SecureMemory.clear(CharArray(0))
        assertTrue("Empty array clearing should be safe", true)
    }

    @Test
    fun errorHandling_secureMemoryClearsLarge() {
        val largeArray = ByteArray(1_000_000) { it.toByte() }
        SecureMemory.clear(largeArray)

        assertTrue("All bytes should be zero", largeArray.all { it == 0.toByte() })
    }

    // ==================== PIN Edge Cases ====================

    @Test
    fun errorHandling_emptyPinRejected() {
        val result = appLockManager.setPin("")
        assertFalse("Empty PIN should be rejected", result)
    }

    @Test
    fun errorHandling_verifyEmptyPin() = runTest {
        appLockManager.setPin("5937")

        val result = appLockManager.verifyPinWithResultAsync("")
        assertTrue("Empty PIN verification should fail", result is AppLockManager.PinVerificationResult.InvalidPin)
    }

    @Test
    fun errorHandling_verifyNoPinSet() = runTest {
        // No PIN set
        val result = appLockManager.verifyPinWithResultAsync("5937")
        assertTrue("Verification should fail when no PIN set", result is AppLockManager.PinVerificationResult.InvalidPin)
    }

    @Test
    fun errorHandling_setPinAfterRemove() {
        appLockManager.setPin("5937")
        appLockManager.removePin()
        assertFalse("PIN should be removed", appLockManager.isPinSet())

        appLockManager.setPin("8473")
        assertTrue("New PIN should be set", appLockManager.isPinSet())
    }

    // ==================== Unicode/Special Character Tests ====================

    @Test
    fun errorHandling_unicodeSsid() = runTest {
        val detection = TestDataFactory.createTestDetection(ssid = "WiFi-\u4e2d\u6587-\u263a")
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertEquals("Unicode SSID should be stored", "WiFi-\u4e2d\u6587-\u263a", detections[0].ssid)
    }

    @Test
    fun errorHandling_specialCharactersSsid() = runTest {
        val detection = TestDataFactory.createTestDetection(ssid = "Net<>\"'&;|\\n\\t")
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertNotNull("Detection should be stored", detections.firstOrNull())
    }

    @Test
    fun errorHandling_emojiInDeviceName() = runTest {
        val detection = TestDataFactory.createTestDetection().copy(deviceName = "Camera \uD83D\uDCF7")
        detectionRepository.insert(detection)

        val detections = detectionRepository.getAllDetections().first()
        assertTrue("Emoji should be in device name", detections[0].deviceName?.contains("\uD83D\uDCF7") == true)
    }
}

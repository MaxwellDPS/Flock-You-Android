package com.flockyou.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test utilities for Android instrumented tests.
 */
object TestUtils {
    
    /**
     * Gets the application context for tests.
     */
    fun getContext(): Context = ApplicationProvider.getApplicationContext()
    
    /**
     * Gets the instrumentation context for tests.
     */
    fun getInstrumentationContext(): Context = 
        InstrumentationRegistry.getInstrumentation().context
    
    /**
     * Checks if a permission is granted.
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(getContext(), permission) == 
            PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Waits for a condition to be true.
     */
    fun waitFor(
        timeoutMillis: Long = 5000,
        intervalMillis: Long = 100,
        condition: () -> Boolean
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (condition()) return true
            Thread.sleep(intervalMillis)
        }
        return condition()
    }
    
    /**
     * Runs a block on the main thread and waits for completion.
     */
    fun runOnMainThreadBlocking(block: () -> Unit) {
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
    }
    
    /**
     * Clears the app database.
     */
    fun clearDatabase() {
        getContext().deleteDatabase("flockyou_database")
    }
    
    /**
     * Clears shared preferences.
     */
    fun clearPreferences() {
        getContext().getSharedPreferences("flockyou_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
    
    /**
     * Clears all app data.
     */
    fun clearAllData() {
        clearDatabase()
        clearPreferences()
    }
}

/**
 * Extension function to wait for a certain condition.
 */
fun ComposeTestRule.waitForCondition(
    timeoutMillis: Long = 5000,
    description: String = "",
    condition: () -> Boolean
) {
    val startTime = System.currentTimeMillis()
    while (!condition()) {
        if (System.currentTimeMillis() - startTime > timeoutMillis) {
            throw AssertionError("Timeout waiting for: $description")
        }
        Thread.sleep(50)
    }
}

/**
 * Extension function to retry a test action.
 */
inline fun retryOnFailure(
    maxRetries: Int = 3,
    delayMillis: Long = 500,
    block: () -> Unit
) {
    var lastException: Throwable? = null
    repeat(maxRetries) { attempt ->
        try {
            block()
            return
        } catch (e: Throwable) {
            lastException = e
            if (attempt < maxRetries - 1) {
                Thread.sleep(delayMillis)
            }
        }
    }
    throw lastException ?: AssertionError("Action failed after $maxRetries attempts")
}

/**
 * Assertion utility for testing detection properties.
 */
object DetectionAssertions {
    
    /**
     * Asserts that the RSSI is in valid range.
     */
    fun assertValidRssi(rssi: Int) {
        assert(rssi in -100..0) { "RSSI $rssi is out of valid range (-100 to 0)" }
    }
    
    /**
     * Asserts that the threat score is in valid range.
     */
    fun assertValidThreatScore(score: Int) {
        assert(score in 0..100) { "Threat score $score is out of valid range (0 to 100)" }
    }
    
    /**
     * Asserts that the MAC address is in valid format.
     */
    fun assertValidMacAddress(macAddress: String?) {
        if (macAddress != null) {
            val regex = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
            assert(regex.matches(macAddress)) { "Invalid MAC address format: $macAddress" }
        }
    }
    
    /**
     * Asserts that coordinates are valid.
     */
    fun assertValidCoordinates(latitude: Double?, longitude: Double?) {
        if (latitude != null) {
            assert(latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        }
        if (longitude != null) {
            assert(longitude in -180.0..180.0) { "Invalid longitude: $longitude" }
        }
    }
}

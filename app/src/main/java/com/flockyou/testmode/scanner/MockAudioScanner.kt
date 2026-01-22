package com.flockyou.testmode.scanner

import android.util.Log
import com.flockyou.testmode.MockAudioBeacon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A detected ultrasonic beacon.
 */
data class UltrasonicDetection(
    val timestamp: Long = System.currentTimeMillis(),
    val frequencyHz: Int,
    val amplitudeDb: Double,
    val durationMs: Long,
    val beaconType: String,
    val confidence: Double = 0.85,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Mock audio/ultrasonic scanner for test mode.
 *
 * This scanner simulates ultrasonic beacon detection for testing
 * cross-device tracking detection scenarios without requiring actual
 * microphone access or audio permissions.
 */
class MockAudioScanner {

    companion object {
        private const val TAG = "MockAudioScanner"
        private const val DEFAULT_EMISSION_INTERVAL_MS = 5000L
    }

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var emissionJob: Job? = null

    // Scanner state
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Mock data flows
    private val _detections = MutableSharedFlow<UltrasonicDetection>(replay = 1, extraBufferCapacity = 10)
    val detections: Flow<UltrasonicDetection> = _detections.asSharedFlow()

    // Mock state
    private var mockBeacons: List<MockAudioBeacon> = emptyList()
    private var emissionIntervalMs: Long = DEFAULT_EMISSION_INTERVAL_MS
    private var amplitudeVariation: Boolean = true

    /**
     * Set the mock audio beacons to simulate.
     */
    fun setMockBeacons(beacons: List<MockAudioBeacon>) {
        Log.d(TAG, "Setting ${beacons.size} mock audio beacons")
        mockBeacons = beacons

        if (_isActive.value && beacons.isNotEmpty()) {
            scope.launch {
                emitBeacon(beacons.first())
            }
        }
    }

    /**
     * Configure the emission interval for mock data.
     */
    fun setEmissionInterval(intervalMs: Long) {
        emissionIntervalMs = intervalMs.coerceIn(1000L, 30000L)
    }

    /**
     * Enable or disable amplitude variation.
     */
    fun setAmplitudeVariation(enabled: Boolean) {
        amplitudeVariation = enabled
    }

    /**
     * Manually emit a beacon detection.
     */
    fun emitBeacon(beacon: MockAudioBeacon) {
        scope.launch {
            val amplitude = if (amplitudeVariation) {
                beacon.amplitudeDb + Random.nextDouble(-3.0, 3.0)
            } else {
                beacon.amplitudeDb
            }

            val detection = UltrasonicDetection(
                frequencyHz = beacon.frequencyHz,
                amplitudeDb = amplitude,
                durationMs = beacon.durationMs,
                beaconType = beacon.beaconType,
                confidence = 0.85 + Random.nextDouble(-0.1, 0.1),
                metadata = mapOf("mock" to "true")
            )

            _detections.emit(detection)
            Log.d(TAG, "Emitted beacon detection: ${beacon.frequencyHz}Hz, ${beacon.beaconType}")
        }
    }

    fun start(): Boolean {
        if (_isActive.value) {
            Log.d(TAG, "Mock scanner already active")
            return true
        }

        Log.d(TAG, "Starting mock audio scanner")

        // Recreate scope if needed
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }

        _isActive.value = true
        _lastError.value = null

        // Start periodic emission
        emissionJob = scope.launch {
            // Emit initial beacon if we have any
            if (mockBeacons.isNotEmpty()) {
                emitBeacon(mockBeacons.first())
            }

            // Then emit periodically
            var beaconIndex = 0
            while (isActive) {
                delay(emissionIntervalMs)
                if (_isActive.value && mockBeacons.isNotEmpty()) {
                    val beacon = mockBeacons[beaconIndex % mockBeacons.size]
                    emitBeacon(beacon)
                    beaconIndex++
                }
            }
        }

        return true
    }

    fun stop() {
        Log.d(TAG, "Stopping mock audio scanner")
        emissionJob?.cancel()
        emissionJob = null
        _isActive.value = false
    }
}

package com.flockyou.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.flockyou.data.model.*
import com.flockyou.security.SecureAudioBuffer
import com.flockyou.security.SecureMemory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detects ultrasonic audio beacons used for cross-device tracking.
 *
 * Many apps and advertisements embed inaudible ultrasonic tones (18-22 kHz) that are
 * picked up by other devices to track users across screens and locations.
 *
 * Detection methods:
 * 1. Ultrasonic Beacon Detection - 18-22 kHz tones from ads/TV
 * 2. Audio Fingerprinting Signals - Patterns used by SilverPush, Alphonso, etc.
 * 3. Continuous Ultrasonic Sources - Hidden audio surveillance
 * 4. Cross-device Tracking Beacons - Retail/advertising beacons
 *
 * Privacy Note: This detector samples audio but does NOT record or store it.
 * Audio data is analyzed in real-time for frequency content only.
 */
class UltrasonicDetector(private val context: Context) {

    companion object {
        private const val TAG = "UltrasonicDetector"

        // Audio parameters
        private const val SAMPLE_RATE = 44100 // 44.1kHz - standard for detecting up to ~22kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4

        // Ultrasonic frequency ranges (Hz)
        private const val ULTRASONIC_LOW = 17500  // Start of near-ultrasonic
        private const val ULTRASONIC_MID = 18000  // Common beacon frequency
        private const val ULTRASONIC_HIGH = 22000 // Upper limit of human hearing
        private const val NYQUIST_LIMIT = SAMPLE_RATE / 2 // 22050 Hz max detectable

        // Detection thresholds
        private const val FFT_SIZE = 4096 // Good frequency resolution (~10.7 Hz per bin)
        private const val DETECTION_THRESHOLD_DB = -40.0 // dB above noise floor
        private const val BEACON_DURATION_THRESHOLD_MS = 500L // Must persist for 500ms
        private const val ANOMALY_COOLDOWN_MS = 60_000L // 1 minute between alerts
        // Default timing values (can be overridden by updateScanTiming)
        private const val DEFAULT_SCAN_DURATION_MS = 5_000L // 5 second scan windows
        private const val DEFAULT_SCAN_INTERVAL_MS = 30_000L // Scan every 30 seconds

        // Known beacon frequencies (Hz) - commonly used by tracking companies
        private val KNOWN_BEACON_FREQUENCIES = listOf(
            18000, // SilverPush primary
            18500, // Alphonso primary
            19000, // Common advertising beacon
            19500, // Retail beacon
            20000, // Cross-device tracking
            20500, // Location beacon
            21000, // Premium ad tracking
        )

        // Frequency tolerance for matching (Hz)
        private const val FREQUENCY_TOLERANCE = 100
    }

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var isMonitoring = false
    private var detectorJob: Job? = null
    private val detectorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Configurable timing
    private var scanDurationMs: Long = DEFAULT_SCAN_DURATION_MS
    private var scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS

    // Location
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null

    // Detection state
    private var lastAnomalyTimes = mutableMapOf<UltrasonicAnomalyType, Long>()
    private val activeBeacons = mutableMapOf<Int, BeaconDetection>() // frequency -> detection
    private var noiseFloorDb = -60.0 // Baseline noise level

    // State flows
    private val _anomalies = MutableStateFlow<List<UltrasonicAnomaly>>(emptyList())
    val anomalies: StateFlow<List<UltrasonicAnomaly>> = _anomalies.asStateFlow()

    private val _status = MutableStateFlow<UltrasonicStatus?>(null)
    val status: StateFlow<UltrasonicStatus?> = _status.asStateFlow()

    private val _events = MutableStateFlow<List<UltrasonicEvent>>(emptyList())
    val events: StateFlow<List<UltrasonicEvent>> = _events.asStateFlow()

    private val _activeBeacons = MutableStateFlow<List<BeaconDetection>>(emptyList())
    val beaconsDetected: StateFlow<List<BeaconDetection>> = _activeBeacons.asStateFlow()

    private val detectedAnomalies = mutableListOf<UltrasonicAnomaly>()
    private val eventHistory = mutableListOf<UltrasonicEvent>()
    private val maxEventHistory = 100

    // Data classes
    data class BeaconDetection(
        val frequency: Int,
        val firstDetected: Long,
        var lastDetected: Long,
        var peakAmplitudeDb: Double,
        var detectionCount: Int = 1,
        val possibleSource: String,
        val latitude: Double?,
        val longitude: Double?,
        // Enhanced fields for enrichment
        var amplitudeHistory: MutableList<Double> = mutableListOf(),
        var locationHistory: MutableList<LocationEntry> = mutableListOf()
    )

    data class LocationEntry(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double
    )

    /**
     * Amplitude profile classification
     */
    enum class AmplitudeProfile(val displayName: String) {
        STEADY("Steady"),
        PULSING("Pulsing"),
        MODULATED("Modulated"),
        ERRATIC("Erratic")
    }

    /**
     * Known beacon type classification
     */
    enum class KnownBeaconType(val displayName: String, val company: String) {
        SILVERPUSH("SilverPush", "SilverPush Technologies"),
        ALPHONSO("Alphonso", "Alphonso Inc"),
        SIGNAL360("Signal360", "Signal360"),
        LISNR("LISNR", "LISNR"),
        SHOPKICK("Shopkick", "Shopkick/SK Telecom"),
        UNKNOWN("Unknown", "Unknown Source")
    }

    /**
     * Source category classification
     */
    enum class SourceCategory(val displayName: String) {
        ADVERTISING("Advertising/Marketing"),
        RETAIL("Retail Tracking"),
        TRACKING("Cross-Device Tracking"),
        ANALYTICS("Analytics/Attribution"),
        UNKNOWN("Unknown Purpose")
    }

    /**
     * Comprehensive beacon analysis with enriched data
     */
    data class BeaconAnalysis(
        // Amplitude Fingerprinting
        val peakAmplitudeDb: Double,
        val avgAmplitudeDb: Double,
        val amplitudeVariance: Float,
        val amplitudeProfile: AmplitudeProfile,

        // Source Attribution
        val frequencyHz: Int,
        val matchedSource: KnownBeaconType,
        val sourceConfidence: Float,              // 0-100%
        val sourceCategory: SourceCategory,

        // Cross-Location Analysis
        val locationsDetected: Int,
        val persistenceScore: Float,              // 0-1, how persistent is this beacon
        val followingUser: Boolean,               // Detected at multiple user locations
        val totalDetectionCount: Int,
        val detectionDurationMs: Long,

        // Environmental Context
        val noiseFloorDb: Double,
        val snrDb: Double,                        // Signal-to-noise ratio

        // Risk Assessment
        val trackingLikelihood: Float,            // 0-100%
        val riskIndicators: List<String>
    )

    data class UltrasonicAnomaly(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: UltrasonicAnomalyType,
        val severity: ThreatLevel,
        val confidence: AnomalyConfidence,
        val description: String,
        val technicalDetails: String,
        val frequency: Int?,
        val amplitudeDb: Double?,
        val latitude: Double?,
        val longitude: Double?,
        val contributingFactors: List<String> = emptyList()
    )

    enum class AnomalyConfidence(val displayName: String) {
        LOW("Low - Possibly Normal"),
        MEDIUM("Medium - Suspicious"),
        HIGH("High - Likely Tracking"),
        CRITICAL("Critical - Confirmed Beacon")
    }

    enum class UltrasonicAnomalyType(
        val displayName: String,
        val baseScore: Int,
        val emoji: String
    ) {
        TRACKING_BEACON("Tracking Beacon", 80, "📢"),
        ADVERTISING_BEACON("Advertising Beacon", 70, "📺"),
        RETAIL_BEACON("Retail Beacon", 65, "🏪"),
        CONTINUOUS_ULTRASONIC("Continuous Ultrasonic", 75, "🔊"),
        CROSS_DEVICE_TRACKING("Cross-Device Tracking", 85, "📲"),
        UNKNOWN_ULTRASONIC("Unknown Ultrasonic", 60, "❓")
    }

    enum class UltrasonicEventType(val displayName: String, val emoji: String) {
        SCAN_STARTED("Scan Started", "🎤"),
        SCAN_COMPLETED("Scan Completed", "✅"),
        BEACON_DETECTED("Beacon Detected", "📢"),
        BEACON_ENDED("Beacon Ended", "🔇"),
        ANOMALY_DETECTED("Anomaly Detected", "⚠️"),
        MONITORING_STARTED("Monitoring Started", "▶️"),
        MONITORING_STOPPED("Monitoring Stopped", "⏹️")
    }

    data class UltrasonicEvent(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val type: UltrasonicEventType,
        val title: String,
        val description: String,
        val frequency: Int? = null,
        val isAnomaly: Boolean = false,
        val threatLevel: ThreatLevel = ThreatLevel.INFO,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    data class UltrasonicStatus(
        val isScanning: Boolean,
        val lastScanTime: Long,
        val noiseFloorDb: Double,
        val ultrasonicActivityDetected: Boolean,
        val activeBeaconCount: Int,
        val peakFrequency: Int?,
        val peakAmplitudeDb: Double?,
        val threatLevel: ThreatLevel
    )

    data class FrequencyBin(
        val frequency: Int,
        val amplitudeDb: Double,
        val isUltrasonic: Boolean
    )

    fun startMonitoring() {
        if (isMonitoring) return

        if (!hasPermission()) {
            Log.w(TAG, "Missing RECORD_AUDIO permission")
            return
        }

        isMonitoring = true
        Log.d(TAG, "Starting ultrasonic beacon detection")

        addTimelineEvent(
            type = UltrasonicEventType.MONITORING_STARTED,
            title = "Ultrasonic Detection Started",
            description = "Monitoring for tracking beacons (18-22 kHz)"
        )

        // Start periodic scanning
        detectorJob = detectorScope.launch {
            while (isActive && isMonitoring) {
                performScan()
                delay(scanIntervalMs)
            }
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
        detectorJob?.cancel()
        detectorJob = null
        releaseAudioRecord()

        addTimelineEvent(
            type = UltrasonicEventType.MONITORING_STOPPED,
            title = "Ultrasonic Detection Stopped",
            description = "Beacon monitoring paused"
        )

        Log.d(TAG, "Stopped ultrasonic detection")
    }

    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    /**
     * Update scan timing configuration.
     * @param intervalSeconds Time between scans (15-120 seconds)
     * @param durationSeconds Duration of each scan (3-15 seconds)
     */
    fun updateScanTiming(intervalSeconds: Int, durationSeconds: Int) {
        scanIntervalMs = (intervalSeconds.coerceIn(15, 120) * 1000L)
        scanDurationMs = (durationSeconds.coerceIn(3, 15) * 1000L)
        Log.d(TAG, "Updated scan timing: interval=${scanIntervalMs}ms, duration=${scanDurationMs}ms")
    }

    /**
     * Perform a single ultrasonic scan with secure encrypted audio processing.
     *
     * SECURITY: Audio samples are encrypted in memory using AES-256-GCM.
     * Raw audio is never stored to disk and is cleared immediately after analysis.
     */
    private suspend fun performScan() {
        if (!hasPermission()) return

        withContext(Dispatchers.IO) {
            // Create secure encrypted buffer for audio samples
            var secureBuffer: SecureAudioBuffer? = null

            try {
                addTimelineEvent(
                    type = UltrasonicEventType.SCAN_STARTED,
                    title = "Scanning for Beacons",
                    description = "Analyzing audio for ultrasonic frequencies (encrypted)"
                )

                val bufferSize = maxOf(
                    AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                    FFT_SIZE * BUFFER_SIZE_MULTIPLIER
                )

                // Initialize secure buffer for encrypted audio storage
                secureBuffer = SecureAudioBuffer(FFT_SIZE, SAMPLE_RATE)

                @Suppress("MissingPermission")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@withContext
                }

                audioRecord?.startRecording()

                // Temporary buffer for reading - will be cleared after each read
                val tempBuffer = ShortArray(FFT_SIZE)
                val detectedFrequencies = mutableMapOf<Int, MutableList<Double>>()
                val scanStartTime = System.currentTimeMillis()

                // Collect samples for scan duration
                while (System.currentTimeMillis() - scanStartTime < scanDurationMs && isMonitoring) {
                    val readCount = audioRecord?.read(tempBuffer, 0, FFT_SIZE) ?: 0

                    if (readCount > 0) {
                        // Write to secure encrypted buffer
                        secureBuffer.write(tempBuffer, readCount)

                        // Analyze within secure context - data decrypted only during analysis
                        val frequencyBins = secureBuffer.analyze { samples ->
                            analyzeFrequencies(samples, samples.size)
                        }

                        // Check for ultrasonic content
                        for (bin in frequencyBins) {
                            if (bin.isUltrasonic && bin.amplitudeDb > noiseFloorDb + DETECTION_THRESHOLD_DB) {
                                val freqKey = (bin.frequency / 100) * 100 // Round to nearest 100Hz
                                detectedFrequencies.getOrPut(freqKey) { mutableListOf() }
                                    .add(bin.amplitudeDb)
                            }
                        }

                        // Update noise floor estimate (using lower frequencies as reference)
                        val lowFreqBins = frequencyBins.filter { it.frequency in 1000..5000 }
                        if (lowFreqBins.isNotEmpty()) {
                            val avgLowFreq = lowFreqBins.map { it.amplitudeDb }.average()
                            noiseFloorDb = noiseFloorDb * 0.95 + avgLowFreq * 0.05 // Slow adaptation
                        }

                        // Clear secure buffer after each analysis cycle
                        secureBuffer.clear()
                    }

                    // Securely clear temporary buffer after each read
                    SecureMemory.clear(tempBuffer)

                    delay(50) // Small delay between reads
                }

                // Final secure clear of temp buffer
                SecureMemory.clear(tempBuffer)

                audioRecord?.stop()
                releaseAudioRecord()

                // Analyze detected frequencies
                processDetectedFrequencies(detectedFrequencies)

                // Update status
                updateStatus(detectedFrequencies)

                addTimelineEvent(
                    type = UltrasonicEventType.SCAN_COMPLETED,
                    title = "Scan Complete",
                    description = "Found ${detectedFrequencies.size} ultrasonic frequencies"
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error during ultrasonic scan", e)
                releaseAudioRecord()
            } finally {
                // CRITICAL: Always destroy secure buffer to wipe encryption keys
                secureBuffer?.destroy()
            }
        }
    }

    /**
     * Simple DFT-based frequency analysis for ultrasonic range
     * (A full FFT library would be better but this works for our purposes)
     */
    private fun analyzeFrequencies(samples: ShortArray, count: Int): List<FrequencyBin> {
        val results = mutableListOf<FrequencyBin>()

        // Focus on ultrasonic frequencies only (save computation)
        val targetFrequencies = (ULTRASONIC_LOW..minOf(ULTRASONIC_HIGH, NYQUIST_LIMIT) step 100).toList()

        for (targetFreq in targetFrequencies) {
            // Goertzel algorithm for single frequency detection (more efficient than full FFT)
            val amplitude = goertzel(samples, count, targetFreq)
            val amplitudeDb = 20 * log10(amplitude + 1e-10)

            results.add(
                FrequencyBin(
                    frequency = targetFreq,
                    amplitudeDb = amplitudeDb,
                    isUltrasonic = targetFreq >= ULTRASONIC_LOW
                )
            )
        }

        return results
    }

    /**
     * Goertzel algorithm - efficient single-frequency detection
     */
    private fun goertzel(samples: ShortArray, count: Int, targetFreq: Int): Double {
        val normalizedFreq = targetFreq.toDouble() / SAMPLE_RATE
        val coeff = 2 * kotlin.math.cos(2 * Math.PI * normalizedFreq)

        var s0: Double
        var s1 = 0.0
        var s2 = 0.0

        for (i in 0 until minOf(count, samples.size)) {
            s0 = samples[i] / 32768.0 + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }

        // Calculate magnitude
        val power = s1 * s1 + s2 * s2 - s1 * s2 * coeff
        return sqrt(abs(power))
    }

    private fun processDetectedFrequencies(frequencies: Map<Int, MutableList<Double>>) {
        val now = System.currentTimeMillis()

        for ((freq, amplitudes) in frequencies) {
            if (amplitudes.size < 3) continue // Need consistent detection

            val avgAmplitude = amplitudes.average()
            val peakAmplitude = amplitudes.maxOrNull() ?: avgAmplitude

            // Check if this matches known beacon frequencies
            val isKnownBeacon = KNOWN_BEACON_FREQUENCIES.any {
                abs(freq - it) <= FREQUENCY_TOLERANCE
            }

            // Determine source type
            val possibleSource = when {
                abs(freq - 18000) <= FREQUENCY_TOLERANCE -> "SilverPush/Ad Tracking"
                abs(freq - 18500) <= FREQUENCY_TOLERANCE -> "Alphonso/TV Tracking"
                abs(freq - 19000) <= FREQUENCY_TOLERANCE -> "Advertising Beacon"
                abs(freq - 19500) <= FREQUENCY_TOLERANCE -> "Retail Tracking"
                abs(freq - 20000) <= FREQUENCY_TOLERANCE -> "Cross-Device Tracking"
                abs(freq - 20500) <= FREQUENCY_TOLERANCE -> "Location Beacon"
                abs(freq - 21000) <= FREQUENCY_TOLERANCE -> "Premium Ad Tracking"
                else -> "Unknown Ultrasonic Source"
            }

            // Update or create beacon detection
            val existing = activeBeacons[freq]
            if (existing != null) {
                existing.lastDetected = now
                existing.detectionCount++
                if (peakAmplitude > existing.peakAmplitudeDb) {
                    existing.peakAmplitudeDb = peakAmplitude
                }
                // Track amplitude history for enrichment
                existing.amplitudeHistory.add(avgAmplitude)
                if (existing.amplitudeHistory.size > 50) {
                    existing.amplitudeHistory.removeAt(0)
                }
                // Track location history for cross-location detection
                currentLatitude?.let { lat ->
                    currentLongitude?.let { lon ->
                        existing.locationHistory.add(LocationEntry(now, lat, lon))
                        if (existing.locationHistory.size > 20) {
                            existing.locationHistory.removeAt(0)
                        }
                    }
                }
            } else {
                // New beacon detected
                val initialAmplitudeHistory = mutableListOf(avgAmplitude)
                val initialLocationHistory = mutableListOf<LocationEntry>()
                currentLatitude?.let { lat ->
                    currentLongitude?.let { lon ->
                        initialLocationHistory.add(LocationEntry(now, lat, lon))
                    }
                }

                val beacon = BeaconDetection(
                    frequency = freq,
                    firstDetected = now,
                    lastDetected = now,
                    peakAmplitudeDb = peakAmplitude,
                    possibleSource = possibleSource,
                    latitude = currentLatitude,
                    longitude = currentLongitude,
                    amplitudeHistory = initialAmplitudeHistory,
                    locationHistory = initialLocationHistory
                )
                activeBeacons[freq] = beacon

                // Build enriched analysis for new beacon
                val analysis = buildBeaconAnalysis(beacon)

                addTimelineEvent(
                    type = UltrasonicEventType.BEACON_DETECTED,
                    title = "📢 Beacon Detected: ${freq}Hz",
                    description = "${possibleSource} (${String.format("%.0f", analysis.trackingLikelihood)}% tracking likelihood)",
                    frequency = freq,
                    threatLevel = if (isKnownBeacon) ThreatLevel.HIGH else ThreatLevel.MEDIUM
                )

                // Report anomaly for new beacon with enriched analysis
                val anomalyType = when {
                    possibleSource.contains("SilverPush") || possibleSource.contains("Alphonso") ->
                        UltrasonicAnomalyType.ADVERTISING_BEACON
                    possibleSource.contains("Retail") ->
                        UltrasonicAnomalyType.RETAIL_BEACON
                    possibleSource.contains("Cross-Device") ->
                        UltrasonicAnomalyType.CROSS_DEVICE_TRACKING
                    isKnownBeacon ->
                        UltrasonicAnomalyType.TRACKING_BEACON
                    else ->
                        UltrasonicAnomalyType.UNKNOWN_ULTRASONIC
                }

                // Determine confidence from enriched analysis
                val confidence = when {
                    analysis.trackingLikelihood >= 80 -> AnomalyConfidence.CRITICAL
                    analysis.trackingLikelihood >= 60 || isKnownBeacon -> AnomalyConfidence.HIGH
                    analysis.trackingLikelihood >= 40 -> AnomalyConfidence.MEDIUM
                    else -> AnomalyConfidence.LOW
                }

                reportAnomaly(
                    type = anomalyType,
                    description = "Ultrasonic tracking beacon detected at ${freq}Hz - tracking likelihood: ${String.format("%.0f", analysis.trackingLikelihood)}%",
                    technicalDetails = buildBeaconTechnicalDetails(analysis),
                    frequency = freq,
                    amplitudeDb = peakAmplitude,
                    confidence = confidence,
                    contributingFactors = buildBeaconContributingFactors(analysis)
                )
            }
        }

        // Clean up old beacons (not seen in 2 minutes)
        val expiredThreshold = now - 120_000L
        val expiredBeacons = activeBeacons.filter { it.value.lastDetected < expiredThreshold }
        for ((freq, beacon) in expiredBeacons) {
            activeBeacons.remove(freq)
            addTimelineEvent(
                type = UltrasonicEventType.BEACON_ENDED,
                title = "Beacon Ended: ${freq}Hz",
                description = "No longer detecting ${beacon.possibleSource}",
                frequency = freq
            )
        }

        _activeBeacons.value = activeBeacons.values.toList()
    }

    private fun updateStatus(detectedFrequencies: Map<Int, MutableList<Double>>) {
        val peakEntry = detectedFrequencies.maxByOrNull {
            it.value.maxOrNull() ?: Double.MIN_VALUE
        }

        val threatLevel = when {
            activeBeacons.any { KNOWN_BEACON_FREQUENCIES.any { known ->
                abs(it.key - known) <= FREQUENCY_TOLERANCE
            }} -> ThreatLevel.HIGH
            activeBeacons.isNotEmpty() -> ThreatLevel.MEDIUM
            detectedFrequencies.isNotEmpty() -> ThreatLevel.LOW
            else -> ThreatLevel.INFO
        }

        _status.value = UltrasonicStatus(
            isScanning = isMonitoring,
            lastScanTime = System.currentTimeMillis(),
            noiseFloorDb = noiseFloorDb,
            ultrasonicActivityDetected = detectedFrequencies.isNotEmpty(),
            activeBeaconCount = activeBeacons.size,
            peakFrequency = peakEntry?.key,
            peakAmplitudeDb = peakEntry?.value?.maxOrNull(),
            threatLevel = threatLevel
        )
    }

    private fun reportAnomaly(
        type: UltrasonicAnomalyType,
        description: String,
        technicalDetails: String,
        frequency: Int?,
        amplitudeDb: Double?,
        confidence: AnomalyConfidence,
        contributingFactors: List<String>
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastAnomalyTimes[type] ?: 0

        if (now - lastTime < ANOMALY_COOLDOWN_MS) {
            return
        }
        lastAnomalyTimes[type] = now

        val severity = when (confidence) {
            AnomalyConfidence.CRITICAL -> ThreatLevel.CRITICAL
            AnomalyConfidence.HIGH -> ThreatLevel.HIGH
            AnomalyConfidence.MEDIUM -> ThreatLevel.MEDIUM
            AnomalyConfidence.LOW -> ThreatLevel.LOW
        }

        val anomaly = UltrasonicAnomaly(
            type = type,
            severity = severity,
            confidence = confidence,
            description = description,
            technicalDetails = technicalDetails,
            frequency = frequency,
            amplitudeDb = amplitudeDb,
            latitude = currentLatitude,
            longitude = currentLongitude,
            contributingFactors = contributingFactors
        )

        detectedAnomalies.add(anomaly)
        _anomalies.value = detectedAnomalies.toList()

        addTimelineEvent(
            type = UltrasonicEventType.ANOMALY_DETECTED,
            title = "${type.emoji} ${type.displayName}",
            description = description,
            frequency = frequency,
            isAnomaly = true,
            threatLevel = severity
        )

        Log.w(TAG, "ULTRASONIC ANOMALY [${confidence.displayName}]: ${type.displayName} - $description")
    }

    private fun addTimelineEvent(
        type: UltrasonicEventType,
        title: String,
        description: String,
        frequency: Int? = null,
        isAnomaly: Boolean = false,
        threatLevel: ThreatLevel = ThreatLevel.INFO
    ) {
        val event = UltrasonicEvent(
            type = type,
            title = title,
            description = description,
            frequency = frequency,
            isAnomaly = isAnomaly,
            threatLevel = threatLevel,
            latitude = currentLatitude,
            longitude = currentLongitude
        )

        eventHistory.add(0, event)
        if (eventHistory.size > maxEventHistory) {
            eventHistory.removeAt(eventHistory.size - 1)
        }
        _events.value = eventHistory.toList()
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    fun clearAnomalies() {
        detectedAnomalies.clear()
        _anomalies.value = emptyList()
    }

    fun clearHistory() {
        activeBeacons.clear()
        eventHistory.clear()
        _events.value = emptyList()
        _activeBeacons.value = emptyList()
    }

    fun destroy() {
        stopMonitoring()
        detectorScope.cancel()
    }

    /**
     * Force an immediate scan (user-triggered)
     */
    fun triggerScan() {
        if (!isMonitoring) return

        detectorScope.launch {
            performScan()
        }
    }

    /**
     * Trigger a test detection to verify ultrasonic detection is working.
     * Simulates detecting an 18kHz SilverPush advertising beacon.
     */
    fun triggerTestDetection() {
        Log.i(TAG, "Triggering test ultrasonic detection")

        val testFrequency = 18000
        val testAmplitude = -35.0

        val beacon = BeaconDetection(
            frequency = testFrequency,
            firstDetected = System.currentTimeMillis(),
            lastDetected = System.currentTimeMillis(),
            peakAmplitudeDb = testAmplitude,
            detectionCount = 1,
            possibleSource = "TEST: SilverPush/Ad Tracking",
            latitude = currentLatitude,
            longitude = currentLongitude
        )
        activeBeacons[testFrequency] = beacon
        _activeBeacons.value = activeBeacons.values.toList()

        addTimelineEvent(
            type = UltrasonicEventType.BEACON_DETECTED,
            title = "TEST: Beacon Detected: ${testFrequency}Hz",
            description = "Test detection - SilverPush/Ad Tracking",
            frequency = testFrequency,
            threatLevel = ThreatLevel.HIGH
        )

        reportAnomaly(
            type = UltrasonicAnomalyType.ADVERTISING_BEACON,
            description = "TEST: Ultrasonic advertising beacon detected at ${testFrequency}Hz",
            technicalDetails = "Test detection to verify ultrasonic pipeline. " +
                "Peak amplitude: ${String.format("%.1f", testAmplitude)}dB",
            frequency = testFrequency,
            amplitudeDb = testAmplitude,
            confidence = AnomalyConfidence.HIGH,
            contributingFactors = listOf(
                "TEST DETECTION",
                "Frequency: ${testFrequency}Hz",
                "Amplitude: ${String.format("%.1f", testAmplitude)}dB"
            )
        )

        _status.value = UltrasonicStatus(
            isScanning = isMonitoring,
            lastScanTime = System.currentTimeMillis(),
            noiseFloorDb = noiseFloorDb,
            ultrasonicActivityDetected = true,
            activeBeaconCount = activeBeacons.size,
            peakFrequency = testFrequency,
            peakAmplitudeDb = testAmplitude,
            threatLevel = ThreatLevel.HIGH
        )

        Log.i(TAG, "Test detection triggered successfully")
    }

    // ==================== ENRICHMENT ANALYSIS FUNCTIONS ====================

    /**
     * Calculate Haversine distance between two points in meters
     */
    private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

        return earthRadiusMeters * c
    }

    /**
     * Analyze amplitude profile from history
     */
    private fun analyzeAmplitudeProfile(amplitudes: List<Double>): AmplitudeProfile {
        if (amplitudes.size < 3) return AmplitudeProfile.STEADY

        val avg = amplitudes.average()
        val variance = amplitudes.map { (it - avg) * (it - avg) }.average()
        val stdDev = sqrt(variance)

        // Check for pulsing (regular on/off pattern)
        val crossings = amplitudes.zipWithNext().count { (a, b) ->
            (a > avg && b < avg) || (a < avg && b > avg)
        }
        val crossingRate = crossings.toFloat() / amplitudes.size

        return when {
            stdDev < 2.0 -> AmplitudeProfile.STEADY
            crossingRate > 0.3 && crossingRate < 0.6 -> AmplitudeProfile.PULSING
            crossingRate > 0.6 -> AmplitudeProfile.ERRATIC
            else -> AmplitudeProfile.MODULATED
        }
    }

    /**
     * Attribute beacon source based on frequency and characteristics
     */
    private fun attributeBeaconSource(freq: Int, amplitude: Double, profile: AmplitudeProfile): Pair<KnownBeaconType, Float> {
        // Match against known beacon frequencies
        return when {
            abs(freq - 18000) <= FREQUENCY_TOLERANCE -> {
                // SilverPush typically uses 18kHz
                KnownBeaconType.SILVERPUSH to 85f
            }
            abs(freq - 18500) <= FREQUENCY_TOLERANCE -> {
                // Alphonso typically uses 18.5kHz
                KnownBeaconType.ALPHONSO to 85f
            }
            abs(freq - 19000) <= FREQUENCY_TOLERANCE -> {
                // Signal360 uses 19kHz range
                KnownBeaconType.SIGNAL360 to 70f
            }
            abs(freq - 19500) <= FREQUENCY_TOLERANCE -> {
                // LISNR uses 19.5kHz
                KnownBeaconType.LISNR to 70f
            }
            abs(freq - 20000) <= FREQUENCY_TOLERANCE -> {
                // Shopkick uses 20kHz
                KnownBeaconType.SHOPKICK to 65f
            }
            else -> {
                // Unknown source
                KnownBeaconType.UNKNOWN to 30f
            }
        }
    }

    /**
     * Determine source category
     */
    private fun getSourceCategory(source: KnownBeaconType, freq: Int): SourceCategory {
        return when (source) {
            KnownBeaconType.SILVERPUSH -> SourceCategory.ADVERTISING
            KnownBeaconType.ALPHONSO -> SourceCategory.ADVERTISING
            KnownBeaconType.SIGNAL360 -> SourceCategory.ANALYTICS
            KnownBeaconType.LISNR -> SourceCategory.TRACKING
            KnownBeaconType.SHOPKICK -> SourceCategory.RETAIL
            KnownBeaconType.UNKNOWN -> {
                when {
                    freq in 19500..20500 -> SourceCategory.RETAIL
                    freq >= 20500 -> SourceCategory.TRACKING
                    else -> SourceCategory.UNKNOWN
                }
            }
        }
    }

    /**
     * Count distinct locations where beacon was detected
     */
    private fun countDistinctLocations(locationHistory: List<LocationEntry>): Int {
        if (locationHistory.isEmpty()) return 0

        val distinctLocs = mutableListOf<Pair<Double, Double>>()
        for (entry in locationHistory) {
            val isDistinct = distinctLocs.none { existing ->
                haversineDistanceMeters(entry.latitude, entry.longitude, existing.first, existing.second) < 100
            }
            if (isDistinct) {
                distinctLocs.add(entry.latitude to entry.longitude)
            }
        }
        return distinctLocs.size
    }

    /**
     * Build comprehensive beacon analysis
     */
    private fun buildBeaconAnalysis(beacon: BeaconDetection): BeaconAnalysis {
        val amplitudes = beacon.amplitudeHistory
        val locations = beacon.locationHistory

        // Amplitude analysis
        val avgAmplitude = if (amplitudes.isNotEmpty()) amplitudes.average() else beacon.peakAmplitudeDb
        val amplitudeVariance = if (amplitudes.size > 1) {
            amplitudes.map { (it - avgAmplitude) * (it - avgAmplitude) }.average().toFloat()
        } else 0f

        val profile = analyzeAmplitudeProfile(amplitudes)

        // Source attribution
        val (matchedSource, confidence) = attributeBeaconSource(beacon.frequency, avgAmplitude, profile)
        val category = getSourceCategory(matchedSource, beacon.frequency)

        // Cross-location analysis
        val distinctLocations = countDistinctLocations(locations)
        val followingUser = distinctLocations >= 2
        val detectionDuration = beacon.lastDetected - beacon.firstDetected

        // Persistence score (how long has this been active)
        val persistenceScore = when {
            detectionDuration > 300_000 -> 1.0f  // > 5 minutes
            detectionDuration > 120_000 -> 0.7f  // > 2 minutes
            detectionDuration > 60_000 -> 0.5f   // > 1 minute
            detectionDuration > 30_000 -> 0.3f   // > 30 seconds
            else -> 0.1f
        }

        // Environmental context
        val snrDb = avgAmplitude - noiseFloorDb

        // Risk indicators
        val riskIndicators = mutableListOf<String>()
        if (matchedSource != KnownBeaconType.UNKNOWN) {
            riskIndicators.add("Matches ${matchedSource.company} beacon signature")
        }
        if (followingUser) {
            riskIndicators.add("Detected at $distinctLocations different locations")
        }
        if (persistenceScore > 0.5f) {
            riskIndicators.add("Persistent signal (${detectionDuration / 1000}s)")
        }
        if (snrDb > 20) {
            riskIndicators.add("Strong signal (SNR: ${String.format("%.1f", snrDb)} dB)")
        }
        if (profile == AmplitudeProfile.PULSING) {
            riskIndicators.add("Pulsing pattern - typical of beacon encoding")
        }
        if (beacon.detectionCount > 10) {
            riskIndicators.add("Repeatedly detected (${beacon.detectionCount} times)")
        }

        // Calculate tracking likelihood
        var trackingLikelihood = confidence * 0.4f
        if (followingUser) trackingLikelihood += 20f
        if (persistenceScore > 0.5f) trackingLikelihood += 15f
        if (profile == AmplitudeProfile.PULSING) trackingLikelihood += 10f
        if (snrDb > 20) trackingLikelihood += 10f
        if (category == SourceCategory.TRACKING) trackingLikelihood += 10f

        return BeaconAnalysis(
            peakAmplitudeDb = beacon.peakAmplitudeDb,
            avgAmplitudeDb = avgAmplitude,
            amplitudeVariance = amplitudeVariance,
            amplitudeProfile = profile,
            frequencyHz = beacon.frequency,
            matchedSource = matchedSource,
            sourceConfidence = confidence,
            sourceCategory = category,
            locationsDetected = distinctLocations,
            persistenceScore = persistenceScore,
            followingUser = followingUser,
            totalDetectionCount = beacon.detectionCount,
            detectionDurationMs = detectionDuration,
            noiseFloorDb = noiseFloorDb,
            snrDb = snrDb,
            trackingLikelihood = trackingLikelihood.coerceIn(0f, 100f),
            riskIndicators = riskIndicators
        )
    }

    /**
     * Build enriched technical details from analysis
     */
    private fun buildBeaconTechnicalDetails(analysis: BeaconAnalysis): String {
        val parts = mutableListOf<String>()

        // Tracking likelihood
        parts.add("Tracking Likelihood: ${String.format("%.0f", analysis.trackingLikelihood)}%")

        // Source attribution
        parts.add("Source: ${analysis.matchedSource.company} (${String.format("%.0f", analysis.sourceConfidence)}% match)")
        parts.add("Category: ${analysis.sourceCategory.displayName}")

        // Amplitude info
        parts.add("Frequency: ${analysis.frequencyHz} Hz")
        parts.add("Peak Amplitude: ${String.format("%.1f", analysis.peakAmplitudeDb)} dB")
        parts.add("Avg Amplitude: ${String.format("%.1f", analysis.avgAmplitudeDb)} dB")
        parts.add("Signal Profile: ${analysis.amplitudeProfile.displayName}")
        parts.add("SNR: ${String.format("%.1f", analysis.snrDb)} dB")

        // Persistence
        parts.add("Duration: ${analysis.detectionDurationMs / 1000}s")
        parts.add("Detections: ${analysis.totalDetectionCount}")
        parts.add("Persistence: ${String.format("%.0f", analysis.persistenceScore * 100)}%")

        // Location
        if (analysis.locationsDetected > 1) {
            parts.add("⚠️ Detected at ${analysis.locationsDetected} distinct locations")
        }
        if (analysis.followingUser) {
            parts.add("⚠️ Beacon appears to follow user movement")
        }

        return parts.joinToString("\n")
    }

    /**
     * Build contributing factors from analysis
     */
    private fun buildBeaconContributingFactors(analysis: BeaconAnalysis): List<String> {
        return analysis.riskIndicators
    }

    /**
     * Convert ultrasonic anomaly to Detection for storage
     */
    fun anomalyToDetection(anomaly: UltrasonicAnomaly): Detection {
        val detectionMethod = when (anomaly.type) {
            UltrasonicAnomalyType.TRACKING_BEACON -> DetectionMethod.ULTRASONIC_TRACKING_BEACON
            UltrasonicAnomalyType.ADVERTISING_BEACON -> DetectionMethod.ULTRASONIC_AD_BEACON
            UltrasonicAnomalyType.RETAIL_BEACON -> DetectionMethod.ULTRASONIC_RETAIL_BEACON
            UltrasonicAnomalyType.CONTINUOUS_ULTRASONIC -> DetectionMethod.ULTRASONIC_CONTINUOUS
            UltrasonicAnomalyType.CROSS_DEVICE_TRACKING -> DetectionMethod.ULTRASONIC_CROSS_DEVICE
            UltrasonicAnomalyType.UNKNOWN_ULTRASONIC -> DetectionMethod.ULTRASONIC_UNKNOWN
        }

        return Detection(
            deviceType = DeviceType.ULTRASONIC_BEACON,
            protocol = DetectionProtocol.AUDIO,
            detectionMethod = detectionMethod,
            deviceName = "${anomaly.type.emoji} ${anomaly.type.displayName}",
            macAddress = null,
            ssid = anomaly.frequency?.let { "${it}Hz" },
            rssi = anomaly.amplitudeDb?.toInt() ?: -50,
            signalStrength = when {
                (anomaly.amplitudeDb ?: -100.0) > -30 -> SignalStrength.EXCELLENT
                (anomaly.amplitudeDb ?: -100.0) > -40 -> SignalStrength.GOOD
                (anomaly.amplitudeDb ?: -100.0) > -50 -> SignalStrength.MEDIUM
                else -> SignalStrength.WEAK
            },
            latitude = anomaly.latitude,
            longitude = anomaly.longitude,
            threatLevel = anomaly.severity,
            threatScore = anomaly.type.baseScore,
            matchedPatterns = anomaly.contributingFactors.joinToString(", ")
        )
    }
}

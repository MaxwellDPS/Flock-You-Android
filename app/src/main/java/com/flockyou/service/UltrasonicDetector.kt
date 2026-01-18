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
        private const val SCAN_DURATION_MS = 5_000L // 5 second scan windows
        private const val SCAN_INTERVAL_MS = 30_000L // Scan every 30 seconds

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
        val longitude: Double?
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
                delay(SCAN_INTERVAL_MS)
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
                while (System.currentTimeMillis() - scanStartTime < SCAN_DURATION_MS && isMonitoring) {
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

        var s0 = 0.0
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
            } else {
                // New beacon detected
                val beacon = BeaconDetection(
                    frequency = freq,
                    firstDetected = now,
                    lastDetected = now,
                    peakAmplitudeDb = peakAmplitude,
                    possibleSource = possibleSource,
                    latitude = currentLatitude,
                    longitude = currentLongitude
                )
                activeBeacons[freq] = beacon

                addTimelineEvent(
                    type = UltrasonicEventType.BEACON_DETECTED,
                    title = "📢 Beacon Detected: ${freq}Hz",
                    description = possibleSource,
                    frequency = freq,
                    threatLevel = if (isKnownBeacon) ThreatLevel.HIGH else ThreatLevel.MEDIUM
                )

                // Report anomaly for new beacon
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

                reportAnomaly(
                    type = anomalyType,
                    description = "Ultrasonic tracking beacon detected at ${freq}Hz",
                    technicalDetails = "Detected $possibleSource beacon. " +
                        "Peak amplitude: ${String.format("%.1f", peakAmplitude)}dB above noise floor. " +
                        "This may be used for cross-device tracking or advertising attribution.",
                    frequency = freq,
                    amplitudeDb = peakAmplitude,
                    confidence = if (isKnownBeacon) AnomalyConfidence.HIGH else AnomalyConfidence.MEDIUM,
                    contributingFactors = listOf(
                        "Frequency: ${freq}Hz",
                        "Amplitude: ${String.format("%.1f", peakAmplitude)}dB",
                        "Source: $possibleSource",
                        if (isKnownBeacon) "Matches known tracking beacon" else "Unknown beacon pattern"
                    )
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
